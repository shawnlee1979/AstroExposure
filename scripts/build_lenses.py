#!/usr/bin/env python3
"""Build data/src/main/resources/catalog/lenses.json.

Built-in lens list, curated at build time from the manufacturers' public
line-ups (design section 2.4): Sony E, Canon RF, Nikon Z, Tamron and
Sigma mirrorless lenses, astrophotography-relevant models first (fast
wide primes, then common zooms). Only models whose mount, focal length
and maximum aperture are firmly established are included; anything less
certain is left out on purpose (the app lets users add custom lenses).

Tamron and Sigma entries are their Sony E-mount versions (the most
common astro configuration of those lenses); the same optics sold for
other mounts can be added in the app as custom entries.

Schema note: `maxAperture` is the maximum aperture at the wide end. A
variable-aperture zoom additionally carries the optional field
`maxApertureTele` (maximum aperture at the telephoto end, e.g. 5.6 for
an 18-55mm f/3.5-5.6), because the calculator picks lenses from this
list and needs the effective maximum aperture at the chosen focal
length. Constant-aperture zooms and primes omit the field.

Usage: python3 scripts/build_lenses.py
"""
import io
import json
import os
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "data", "src", "main", "resources", "catalog", "lenses.json")

# (id, name, mount, focalMinMm, focalMaxMm, maxAperture[, maxApertureTele])
# The optional 7th element is set only for variable-aperture zooms.
LENSES = [
    # Sony E
    ("sony-fe-14mm-f1.8-gm", "Sony FE 14mm F1.8 GM", "Sony E", 14, 14, 1.8),
    ("sony-fe-20mm-f1.8-g", "Sony FE 20mm F1.8 G", "Sony E", 20, 20, 1.8),
    ("sony-fe-24mm-f1.4-gm", "Sony FE 24mm F1.4 GM", "Sony E", 24, 24, 1.4),
    ("sony-fe-35mm-f1.4-gm", "Sony FE 35mm F1.4 GM", "Sony E", 35, 35, 1.4),
    ("sony-fe-50mm-f1.2-gm", "Sony FE 50mm F1.2 GM", "Sony E", 50, 50, 1.2),
    ("sony-fe-50mm-f1.4-gm", "Sony FE 50mm F1.4 GM", "Sony E", 50, 50, 1.4),
    ("sony-fe-85mm-f1.4-gm-ii", "Sony FE 85mm F1.4 GM II", "Sony E", 85, 85, 1.4),
    ("sony-fe-135mm-f1.8-gm", "Sony FE 135mm F1.8 GM", "Sony E", 135, 135, 1.8),
    ("sony-fe-12-24mm-f2.8-gm", "Sony FE 12-24mm F2.8 GM", "Sony E", 12, 24, 2.8),
    ("sony-fe-16-35mm-f2.8-gm-ii", "Sony FE 16-35mm F2.8 GM II", "Sony E", 16, 35, 2.8),
    ("sony-fe-24-70mm-f2.8-gm-ii", "Sony FE 24-70mm F2.8 GM II", "Sony E", 24, 70, 2.8),
    ("sony-e-11mm-f1.8", "Sony E 11mm F1.8", "Sony E", 11, 11, 1.8),
    # Canon RF
    ("canon-rf-16mm-f2.8-stm", "Canon RF 16mm F2.8 STM", "Canon RF", 16, 16, 2.8),
    ("canon-rf-24mm-f1.4l-vcm", "Canon RF 24mm F1.4L VCM", "Canon RF", 24, 24, 1.4),
    ("canon-rf-35mm-f1.4l-vcm", "Canon RF 35mm F1.4L VCM", "Canon RF", 35, 35, 1.4),
    ("canon-rf-35mm-f1.8-macro-is-stm", "Canon RF 35mm F1.8 Macro IS STM", "Canon RF", 35, 35, 1.8),
    ("canon-rf-50mm-f1.2l-usm", "Canon RF 50mm F1.2L USM", "Canon RF", 50, 50, 1.2),
    ("canon-rf-85mm-f1.2l-usm", "Canon RF 85mm F1.2L USM", "Canon RF", 85, 85, 1.2),
    ("canon-rf-135mm-f1.8l-is-usm", "Canon RF 135mm F1.8L IS USM", "Canon RF", 135, 135, 1.8),
    ("canon-rf-14-35mm-f4l-is-usm", "Canon RF 14-35mm F4L IS USM", "Canon RF", 14, 35, 4.0),
    ("canon-rf-15-35mm-f2.8l-is-usm", "Canon RF 15-35mm F2.8L IS USM", "Canon RF", 15, 35, 2.8),
    ("canon-rf-24-70mm-f2.8l-is-usm", "Canon RF 24-70mm F2.8L IS USM", "Canon RF", 24, 70, 2.8),
    ("canon-rf-28-70mm-f2l-usm", "Canon RF 28-70mm F2L USM", "Canon RF", 28, 70, 2.0),
    # Nikon Z
    ("nikon-z-20mm-f1.8-s", "Nikon NIKKOR Z 20mm F1.8 S", "Nikon Z", 20, 20, 1.8),
    ("nikon-z-24mm-f1.8-s", "Nikon NIKKOR Z 24mm F1.8 S", "Nikon Z", 24, 24, 1.8),
    ("nikon-z-28mm-f2.8", "Nikon NIKKOR Z 28mm F2.8", "Nikon Z", 28, 28, 2.8),
    ("nikon-z-35mm-f1.8-s", "Nikon NIKKOR Z 35mm F1.8 S", "Nikon Z", 35, 35, 1.8),
    ("nikon-z-50mm-f1.2-s", "Nikon NIKKOR Z 50mm F1.2 S", "Nikon Z", 50, 50, 1.2),
    ("nikon-z-50mm-f1.8-s", "Nikon NIKKOR Z 50mm F1.8 S", "Nikon Z", 50, 50, 1.8),
    ("nikon-z-85mm-f1.2-s", "Nikon NIKKOR Z 85mm F1.2 S", "Nikon Z", 85, 85, 1.2),
    ("nikon-z-85mm-f1.8-s", "Nikon NIKKOR Z 85mm F1.8 S", "Nikon Z", 85, 85, 1.8),
    ("nikon-z-135mm-f1.8-s-plena", "Nikon NIKKOR Z 135mm F1.8 S Plena", "Nikon Z", 135, 135, 1.8),
    ("nikon-z-14-24mm-f2.8-s", "Nikon NIKKOR Z 14-24mm F2.8 S", "Nikon Z", 14, 24, 2.8),
    ("nikon-z-24-70mm-f2.8-s", "Nikon NIKKOR Z 24-70mm F2.8 S", "Nikon Z", 24, 70, 2.8),
    # Tamron (Sony E-mount versions)
    ("tamron-20mm-f2.8-di-iii-osd", "Tamron 20mm F2.8 Di III OSD M1:2", "Sony E", 20, 20, 2.8),
    ("tamron-24mm-f2.8-di-iii-osd", "Tamron 24mm F2.8 Di III OSD M1:2", "Sony E", 24, 24, 2.8),
    ("tamron-35mm-f2.8-di-iii-osd", "Tamron 35mm F2.8 Di III OSD M1:2", "Sony E", 35, 35, 2.8),
    ("tamron-11-20mm-f2.8-di-iii-a-rxd", "Tamron 11-20mm F2.8 Di III-A RXD", "Sony E", 11, 20, 2.8),
    ("tamron-17-28mm-f2.8-di-iii-rxd", "Tamron 17-28mm F2.8 Di III RXD", "Sony E", 17, 28, 2.8),
    ("tamron-28-75mm-f2.8-di-iii-vxd-g2", "Tamron 28-75mm F2.8 Di III VXD G2", "Sony E", 28, 75, 2.8),
    ("tamron-35-150mm-f2-2.8-di-iii-vxd", "Tamron 35-150mm F2-2.8 Di III VXD", "Sony E", 35, 150, 2.0, 2.8),
    # Sigma (Sony E-mount versions)
    ("sigma-14mm-f1.4-dg-dn-art", "Sigma 14mm F1.4 DG DN Art", "Sony E", 14, 14, 1.4),
    ("sigma-20mm-f1.4-dg-dn-art", "Sigma 20mm F1.4 DG DN Art", "Sony E", 20, 20, 1.4),
    ("sigma-24mm-f1.4-dg-dn-art", "Sigma 24mm F1.4 DG DN Art", "Sony E", 24, 24, 1.4),
    ("sigma-35mm-f1.4-dg-dn-art", "Sigma 35mm F1.4 DG DN Art", "Sony E", 35, 35, 1.4),
    ("sigma-50mm-f1.4-dg-dn-art", "Sigma 50mm F1.4 DG DN Art", "Sony E", 50, 50, 1.4),
    ("sigma-85mm-f1.4-dg-dn-art", "Sigma 85mm F1.4 DG DN Art", "Sony E", 85, 85, 1.4),
    ("sigma-135mm-f1.8-dg-hsm-art", "Sigma 135mm F1.8 DG HSM Art", "Sony E", 135, 135, 1.8),
    ("sigma-14-24mm-f2.8-dg-dn-art", "Sigma 14-24mm F2.8 DG DN Art", "Sony E", 14, 24, 2.8),
    ("sigma-16-28mm-f2.8-dg-dn-contemporary", "Sigma 16-28mm F2.8 DG DN Contemporary", "Sony E", 16, 28, 2.8),
    ("sigma-24-70mm-f2.8-dg-dn-art", "Sigma 24-70mm F2.8 DG DN Art", "Sony E", 24, 70, 2.8),
    ("sigma-28-70mm-f2.8-dg-dn-contemporary", "Sigma 28-70mm F2.8 DG DN Contemporary", "Sony E", 28, 70, 2.8),
]


def main():
    ids = [row[0] for row in LENSES]
    assert len(ids) == len(set(ids)), "duplicate lens id"
    assert len(LENSES) >= 40, f"only {len(LENSES)} lenses"
    lenses = []
    for row in LENSES:
        lid, name, mount, fmin, fmax, aperture = row[:6]
        tele = row[6] if len(row) > 6 else None
        assert fmin <= fmax and 0.9 <= aperture <= 8.0, lid
        entry = {
            "id": lid, "name": name, "mount": mount,
            "focalMinMm": fmin, "focalMaxMm": fmax, "maxAperture": aperture,
        }
        if tele is not None:
            # Variable-aperture zoom: tele end cannot be faster than wide.
            assert fmin < fmax and aperture <= tele <= 8.0, lid
            entry["maxApertureTele"] = tele
        lenses.append(entry)
    lenses.sort(key=lambda x: x["id"])
    payload = {
        "schemaVersion": 1,
        "dataVersion": f"manufacturer line-ups (compiled {date.today().isoformat()})",
        "lenses": lenses,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with io.open(OUT, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    print(f"lenses.json: {len(lenses)} lenses")


if __name__ == "__main__":
    sys.exit(main())
