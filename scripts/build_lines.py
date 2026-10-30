#!/usr/bin/env python3
"""Build data/src/main/resources/catalog/constellation-lines.json from
the d3-celestial constellation line set.

Source: d3-celestial (Olaf Frohn), https://github.com/ofrohn/d3-celestial
— file data/constellations.lines.json. The line figures follow the IAU
constellation pages (per the upstream README's data sources).
Licence: BSD 3-Clause, (c) 2015 Olaf Frohn (see DATA-LICENSES.md and
third-party/d3-celestial-LICENSE.txt).

Conversion: each GeoJSON vertex (longitude -180..180, latitude) is an
(RA, Dec) pair in degrees; RA normalises with % 360. Every vertex is
matched to the nearest star of the bundled HYG bright subset
(catalog/stars.bin.gz, built by build_stars.py — run it first) and the
product stores star-id chains: {code: [[id, ...], ...]}. The upstream
file carries Serpens as two features with the same id ("Ser", head and
tail); they merge into the one Ser entry of the constellation table.

Build-time assertions (the pipeline fails loudly rather than shipping
a silently degraded line set):
  * the code set equals constellations.json's 88 codes exactly;
  * every polyline has at least 2 vertices;
  * every vertex matches a subset star within 0.05 deg (measured max
    is ~0.009 deg — the line set was drawn on the same bright stars);
  * golden vertices: Orion's chi-1 Orionis vertex and Cygnus's
    Albireo vertex are in their chains.

Usage: python3 scripts/build_lines.py   (after build_stars.py)
"""
import gzip
import io
import json
import math
import os
import struct
import subprocess
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CACHE = os.path.join(HERE, ".cache", "d3-celestial")
CATALOG = os.path.join(ROOT, "data", "src", "main", "resources", "catalog")
STARS_BIN = os.path.join(CATALOG, "stars.bin.gz")
NAMES_JSON = os.path.join(CATALOG, "star-names.json")
CONSTELLATIONS = os.path.join(CATALOG, "constellations.json")
OUT = os.path.join(CATALOG, "constellation-lines.json")

MATCH_TOLERANCE_DEG = 0.05


def fetch(name):
    path = os.path.join(CACHE, name)
    if os.path.exists(path):
        return path
    os.makedirs(CACHE, exist_ok=True)
    url = f"https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data/{name}"
    try:
        import requests
        r = requests.get(url, timeout=120)
        r.raise_for_status()
        data = r.content
    except ImportError:
        data = subprocess.run(["curl", "-fsSL", url], check=True,
                              capture_output=True).stdout
    with open(path, "wb") as fh:
        fh.write(data)
    return path


def load_subset():
    raw = gzip.decompress(open(STARS_BIN, "rb").read())
    assert raw[:4] == b"AEST", "stars.bin.gz: bad magic (run build_stars.py first)"
    (count,) = struct.unpack_from("<i", raw, 4)
    stars = {}
    off = 8
    for _ in range(count):
        hyg_id, ra_q, dec_q, mag_q = struct.unpack_from("<iiih", raw, off)
        off += 14
        stars[hyg_id] = (ra_q / 10000.0, dec_q / 10000.0)
    assert off == len(raw), "stars.bin.gz: trailing bytes"
    return stars


def main():
    stars = load_subset()
    star_ids = list(stars.keys())
    with io.open(fetch("constellations.lines.json"), encoding="utf-8") as fh:
        doc = json.load(fh)
    with io.open(CONSTELLATIONS, encoding="utf-8") as fh:
        expected_codes = {c["code"] for c in json.load(fh)["constellations"]}
    with io.open(NAMES_JSON, encoding="utf-8") as fh:
        names = json.load(fh)["names"]

    def nearest(ra, dec):
        best, best_d = None, float("inf")
        cos_dec = math.cos(math.radians(dec))
        for sid in star_ids:
            sra, sdec = stars[sid]
            dra = (sra - ra) * cos_dec
            d = math.hypot(dra, sdec - dec)
            if d < best_d:
                best, best_d = sid, d
        return best, best_d

    lines = {}
    total_segments = 0
    total_vertices = 0
    max_distance = 0.0
    for feature in doc["features"]:
        code = feature["id"]
        polys = lines.setdefault(code, [])
        for segment in feature["geometry"]["coordinates"]:
            chain = []
            for lon, lat in segment:
                ra = lon % 360.0
                sid, dist = nearest(ra, lat)
                max_distance = max(max_distance, dist)
                assert dist <= MATCH_TOLERANCE_DEG, \
                    f"{code}: vertex ({ra}, {lat}) matched star {sid} at {dist} deg"
                chain.append(sid)
            assert len(chain) >= 2, f"{code}: polyline with <2 vertices"
            polys.append(chain)
            total_segments += 1
            total_vertices += len(chain)

    assert set(lines.keys()) == expected_codes, (
        f"code set mismatch: missing {expected_codes - set(lines.keys())}, "
        f"extra {set(lines.keys()) - expected_codes}")

    def star_id_by_name(code, predicate):
        for chain in lines[code]:
            for sid in chain:
                if predicate(names.get(str(sid), {})):
                    return sid
        return None

    chi1 = star_id_by_name("Ori", lambda n: n.get("bayer") == "χ¹")
    assert chi1 is not None, "Orion chain lost its chi-1 vertex"
    albireo = star_id_by_name("Cyg", lambda n: n.get("proper") == "Albireo")
    assert albireo is not None, "Cygnus chain lost its Albireo vertex"

    payload = {
        "schemaVersion": 1,
        "dataVersion": f"d3-celestial constellations.lines.json (retrieved {date.today().isoformat()})",
        "source": "d3-celestial (c) Olaf Frohn, BSD 3-Clause; figures per IAU constellation pages",
        "lines": {code: lines[code] for code in sorted(lines)},
    }
    with io.open(OUT, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=1, sort_keys=False)
        fh.write("\n")
    print(f"constellation-lines.json: {len(lines)} constellations, "
          f"{total_segments} polylines, {total_vertices} vertices, "
          f"max match distance {max_distance:.4f} deg")


if __name__ == "__main__":
    sys.exit(main())
