#!/usr/bin/env python3
"""Build data/src/main/resources/catalog/objects.json from OpenNGC.

Source: OpenNGC (Mattia Verga), https://github.com/mattiaverga/OpenNGC
Licence: CC BY-SA 4.0 (see DATA-LICENSES.md).
Upstream files (cached under scripts/.cache/openngc/):
  database_files/NGC.csv, database_files/addendum.csv

Selection (deterministic):
  * All 110 Messier objects. OpenNGC cross-references 109 of them via its
    "M" column; M102 has no row with M=102 (the addendum carries an "M102"
    placeholder typed Dup pointing back at M101). Following the widely used
    identification, M102 is taken as NGC 5866.
  * Plus bright NGC/IC deep-sky objects from NGC.csv: magnitude <= 10.0 for
    galaxies, <= 10.5 for clusters / nebulae / planetary nebulae / supernova
    remnants (all inside the <= 11.0 ceiling), which lands the combined
    catalogue in the 300-600 object target band.

Magnitude rule: V magnitude preferred; B magnitude when V is missing, and
also when |B - V| > 1.5 (physically implausible colour index in the
upstream table, e.g. NGC 253 lists V=11.11 against B=7.94; B is the
catalogue's primary galaxy magnitude).

Surface brightness: for galaxies, OpenNGC's SurfBr column is used verbatim
(surfaceBrightnessEstimated=false). Everything else with a known size uses
the mean surface brightness SB = mag + 2.5*log10(pi*a*b/4), a/b = major /
minor axis in arcsec (surfaceBrightnessEstimated=true). No size -> null.

Usage: python3 scripts/build_objects.py
Requires: requests (or curl on PATH) for the first download; afterwards the
cache is reused.
"""
import csv
import io
import json
import math
import os
import subprocess
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CACHE = os.path.join(HERE, ".cache", "openngc")
OUT = os.path.join(ROOT, "data", "src", "main", "resources", "catalog", "objects.json")
UPSTREAM_TAG = "v20260501"  # latest OpenNGC release at retrieval time
BASE = "https://raw.githubusercontent.com/mattiaverga/OpenNGC/master/database_files"

TYPE_MAP = {
    "G": "GALAXY", "GPair": "GALAXY", "GTrpl": "GALAXY", "GGroup": "GALAXY",
    "OCl": "CLUSTER_OPEN", "GCl": "CLUSTER_GLOBULAR", "PN": "PLANETARY_NEBULA",
    "SNR": "SUPERNOVA_REMNANT", "EmN": "NEBULA", "RfN": "NEBULA", "HII": "NEBULA",
    "Neb": "NEBULA", "Cl+N": "NEBULA", "DrkN": "NEBULA",
}
DEEP_SKY_TYPES = set(TYPE_MAP)
GALAXY_TYPES = {"G", "GPair", "GTrpl", "GGroup"}

# Chinese common names for Messier objects (widely used names only;
# anything not listed here stays null rather than guessing a translation).
NAME_ZH = {
    1: "蟹状星云", 6: "蝴蝶星团", 7: "托勒密星团", 8: "礁湖星云",
    11: "野鸭星团", 13: "武仙座球状星团", 16: "鹰状星云", 17: "欧米伽星云",
    20: "三叶星云", 27: "哑铃星云", 31: "仙女座星系", 33: "三角座星系",
    41: "小蜂巢星团", 42: "猎户座星云", 44: "蜂巢星团", 45: "昴星团",
    51: "涡状星系", 57: "环状星云", 63: "向日葵星系", 64: "黑眼星系",
    74: "幻影星系", 76: "小哑铃星云", 81: "波德星系", 82: "雪茄星系",
    83: "南风车星系", 97: "猫头鹰星云", 101: "风车星系", 104: "草帽星系",
}


def fetch(name):
    path = os.path.join(CACHE, name)
    if os.path.exists(path):
        return path
    os.makedirs(CACHE, exist_ok=True)
    url = f"{BASE}/{name}"
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


def fnum(row, key):
    v = (row.get(key) or "").strip()
    try:
        return float(v)
    except ValueError:
        return None


def magnitude(row):
    b, v = fnum(row, "B-Mag"), fnum(row, "V-Mag")
    if v is not None and b is not None and abs(b - v) > 1.5:
        return b
    return v if v is not None else b


def parse_ra(text):
    h, m, s = text.strip().split(":")
    return round(int(h) + int(m) / 60 + float(s) / 3600, 4)


def parse_dec(text):
    text = text.strip()
    sign = -1 if text.startswith("-") else 1
    d, m, s = text.lstrip("+-").split(":")
    return round(sign * (int(d) + int(m) / 60 + float(s) / 3600), 4)


def pretty_name(raw):
    # NGC0224 -> "NGC 224", IC0001 -> "IC 1", Mel022 -> "Mel 22"
    for prefix in ("NGC", "IC", "Mel", "ESO", "UGC", "PGC"):
        if raw.startswith(prefix):
            num = raw[len(prefix):]
            return f"{prefix} {int(num)}" if num.isdigit() else raw
    return raw


# OpenNGC lists no B/V magnitude for these seven Messier objects.
# Supplementary integrated V magnitudes: globular clusters from the Harris
# catalogue of Milky Way globular clusters (Harris 1996, 2010 edition,
# McMaster University, public catalogue); M35 (open cluster) V=5.3 as
# commonly listed in open-cluster references (WEBDA database).
SUPPLEMENT_MAG = {
    "M22": 5.10, "M35": 5.30, "M62": 6.45, "M69": 7.64,
    "M70": 7.87, "M71": 8.19, "M107": 7.78,
}


def surface_brightness(row, mag, mapped_type):
    sb = fnum(row, "SurfBr")
    if mapped_type == "GALAXY" and sb is not None:
        return round(sb, 2), False
    maj, min_ = fnum(row, "MajAx"), fnum(row, "MinAx")
    if min_ is None:
        min_ = maj  # single listed diameter -> treat as circular
    if mag is not None and maj and min_:
        area = math.pi * (maj * 60) * (min_ * 60) / 4
        return round(mag + 2.5 * math.log10(area), 2), True
    return None, False


def to_object(obj_id, row, name_override=None):
    mapped = TYPE_MAP.get(row["Type"].strip(), "OTHER")
    mag = magnitude(row)
    sb, sb_est = surface_brightness(row, mag, mapped)
    common = (row.get("Common names") or "").split(",")[0].strip()
    name = name_override or common or pretty_name(row["Name"].strip())
    const = (row.get("Const") or "").strip()
    if const in ("Se1", "Se2"):
        const = "Ser"
    return {
        "id": obj_id,
        "name": name,
        "nameZh": None,  # filled by caller for Messier entries
        "raHours": parse_ra(row["RA"]),
        "decDeg": parse_dec(row["Dec"]),
        "magnitude": round(mag, 2) if mag is not None else None,
        "sizeMajorArcmin": fnum(row, "MajAx"),
        "sizeMinorArcmin": fnum(row, "MinAx"),
        "positionAngleDeg": fnum(row, "PosAng"),
        "type": mapped,
        "constellation": const or None,
        "surfaceBrightness": sb,
        "surfaceBrightnessEstimated": sb_est,
    }


def main():
    rows = []
    for fname in ("NGC.csv", "addendum.csv"):
        with io.open(fetch(fname), encoding="utf-8") as fh:
            rows.extend(csv.DictReader(fh, delimiter=";"))
    ngc_rows = []
    with io.open(fetch("NGC.csv"), encoding="utf-8") as fh:
        ngc_rows = list(csv.DictReader(fh, delimiter=";"))

    by_name = {r["Name"].strip(): r for r in rows}
    by_messier = {}
    for r in rows:
        m = (r.get("M") or "").strip()
        if m and r["Type"].strip() != "Dup":
            by_messier.setdefault(int(m), r)

    objects, used_names = [], set()
    for num in range(1, 111):
        if num == 102:
            row = by_name["NGC5866"]  # widely used M102 identification
        else:
            row = by_messier[num]
        obj = to_object(f"M{num}", row, name_override=f"Messier {num}"
                        if not (row.get("Common names") or "").strip() else None)
        if obj["magnitude"] is None and f"M{num}" in SUPPLEMENT_MAG:
            obj["magnitude"] = SUPPLEMENT_MAG[f"M{num}"]
            sb, sb_est = surface_brightness(row, obj["magnitude"], obj["type"])
            obj["surfaceBrightness"], obj["surfaceBrightnessEstimated"] = sb, sb_est
        obj["nameZh"] = NAME_ZH.get(num)
        objects.append(obj)
        used_names.add(row["Name"].strip())
    assert len(objects) == 110, f"Messier count {len(objects)} != 110"

    selected = []
    for r in ngc_rows:
        t = r["Type"].strip()
        if t not in DEEP_SKY_TYPES or r["Name"].strip() in used_names:
            continue
        if (r.get("M") or "").strip():
            continue
        mag = magnitude(r)
        if mag is None:
            continue
        limit = 10.0 if t in GALAXY_TYPES else 10.5
        if mag <= limit:
            selected.append(r)
    selected.sort(key=lambda r: r["Name"].strip())
    for r in selected:
        raw = r["Name"].strip()
        objects.append(to_object(pretty_name(raw).replace(" ", ""), r))

    total = len(objects)
    assert 300 <= total <= 600, f"total {total} outside 300-600 target band"
    # Emitted type strings must stay within the set the :data adapter maps
    # onto the domain enum (JsonObjectCatalog.parseObjectType); a typo here
    # once shipped SUPERNOVA_REMANT and made the whole catalogue unloadable.
    allowed_types = {
        "GALAXY", "CLUSTER_OPEN", "CLUSTER_GLOBULAR", "PLANETARY_NEBULA",
        "SUPERNOVA_REMNANT", "NEBULA", "DOUBLE_STAR", "ASTERISM", "OTHER",
    }
    emitted_types = {o["type"] for o in objects}
    assert emitted_types <= allowed_types, f"unmapped types emitted: {emitted_types - allowed_types}"
    payload = {
        "schemaVersion": 1,
        "dataVersion": f"OpenNGC {UPSTREAM_TAG} (retrieved {date.today().isoformat()})",
        "source": "OpenNGC (CC BY-SA 4.0)",
        "objects": objects,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with io.open(OUT, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=1, sort_keys=False)
        fh.write("\n")
    print(f"objects.json: {total} objects (110 Messier + {total - 110} NGC/IC)")


if __name__ == "__main__":
    sys.exit(main())
