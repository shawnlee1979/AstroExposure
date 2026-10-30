#!/usr/bin/env python3
"""Build the bundled bright-star products from the HYG Database v4.4.

Source: HYG Database v4.4 (Astronexus / David Nash),
https://codeberg.org/astronexus/hyg — file data/hyg/CURRENT/hyg_v44.csv.gz
(Git LFS; SHA-256 below is the LFS object id, verified on every run).
Licence: CC BY-SA 4.0 (see DATA-LICENSES.md).

Outputs (both under data/src/main/resources/catalog/):
  * stars.bin.gz — the <= 6.5 mag subset (design section 8.3's chosen
    bright-star set) as fixed 14-byte little-endian records sorted by
    HYG id, gzip-compressed:
        header: magic "AEST" + int32 record count
        record: int32 hygId, int32 raDeg x 1e4, int32 decDeg x 1e4,
                int16 mag x 100
    The Sun placeholder row (HYG id 0) is excluded.
  * star-names.json — the name side table for the same subset: proper
    names, Bayer designations converted to display form (Greek letter
    + superscript digit, e.g. "Chi-1" -> "χ¹"), and Chinese common
    names matched from the constellation table's representative-star
    list (constellations.json). Only stars that carry at least one of
    the three appear. This file also carries the data set's
    dataVersion/source header for both products (one script emits the
    pair atomically).

UNIT TRAP (measured, do not regress): HYG v4.4's `ra` column is in
DECIMAL HOURS, `dec` is in degrees. The conversion multiplies ra by
15. The Betelgeuse assertion below pins it: after conversion
Betelgeuse must land at 88.7925 deg / +7.4071 deg within 0.01 deg.

Usage: python3 scripts/build_stars.py
"""
import csv
import gzip
import hashlib
import io
import json
import os
import struct
import subprocess
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CACHE = os.path.join(HERE, ".cache", "hyg")
CATALOG = os.path.join(ROOT, "data", "src", "main", "resources", "catalog")
OUT_BIN = os.path.join(CATALOG, "stars.bin.gz")
OUT_NAMES = os.path.join(CATALOG, "star-names.json")
CONSTELLATIONS = os.path.join(CATALOG, "constellations.json")

EXPECTED_SHA256 = "00b349893b9a53106dd488d8371e8d2fa586043e500bb3cdb8bff3931682197d"
MAG_LIMIT = 6.5
EXPECTED_COUNT = 8920       # HYG v4.4, mag <= 6.5, id != 0 (pin on version bump)
EXPECTED_PROPER = 440
EXPECTED_BAYER = 1519

GREEK = {
    "Alp": "α", "Bet": "β", "Gam": "γ", "Del": "δ", "Eps": "ε",
    "Zet": "ζ", "Eta": "η", "The": "θ", "Iot": "ι", "Kap": "κ",
    "Lam": "λ", "Mu": "μ", "Nu": "ν", "Xi": "ξ", "Omi": "ο",
    "Pi": "π", "Rho": "ρ", "Sig": "σ", "Tau": "τ", "Ups": "υ",
    "Phi": "φ", "Chi": "χ", "Psi": "ψ", "Ome": "ω",
}
SUPERSCRIPT = {"0": "⁰", "1": "¹", "2": "²", "3": "³", "4": "⁴",
               "5": "⁵", "6": "⁶", "7": "⁷", "8": "⁸", "9": "⁹"}


def fetch():
    path = os.path.join(CACHE, "hyg_v44.csv.gz")
    if not os.path.exists(path):
        os.makedirs(CACHE, exist_ok=True)
        url = ("https://codeberg.org/astronexus/hyg/media/branch/main/"
               "data/hyg/CURRENT/hyg_v44.csv.gz")
        try:
            import requests
            r = requests.get(url, timeout=300)
            r.raise_for_status()
            data = r.content
        except ImportError:
            data = subprocess.run(["curl", "-fsSL", url], check=True,
                                  capture_output=True).stdout
        with open(path, "wb") as fh:
            fh.write(data)
    digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
    assert digest == EXPECTED_SHA256, (
        f"HYG source SHA-256 mismatch: {digest} != {EXPECTED_SHA256} "
        "(upstream file changed; re-verify before updating the pin)")
    return path


def bayer_display(raw):
    """HYG Bayer text ("Alp", "Chi-1", "Iot2") -> display form ("α", "χ¹", "ι²").

    Returns None when the raw value has no convertible form; the caller
    then keeps no Bayer name rather than showing the raw catalogue code.
    """
    raw = raw.strip()
    if not raw:
        return None
    head, _, tail = raw.partition("-")
    digits = ""
    if tail:
        if not tail.isdigit():
            return None
        digits = tail
    elif len(head) > 3:
        stem, num = head[:3], head[3:]
        if num.isdigit():
            head, digits = stem, num
    letter = GREEK.get(head)
    if letter is None:
        return None
    return letter + "".join(SUPERSCRIPT[d] for d in digits)


def load_subset(path):
    stars = []
    with gzip.open(path, "rt", encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh):
            mag = float(row["mag"])
            hyg_id = int(row["id"])
            if hyg_id == 0 or mag > MAG_LIMIT:
                continue
            stars.append({
                "id": hyg_id,
                "raDeg": float(row["ra"]) * 15.0,   # hours -> degrees (see header)
                "decDeg": float(row["dec"]),
                "mag": mag,
                "proper": row["proper"].strip(),
                "bayer": row["bayer"].strip(),
            })
    stars.sort(key=lambda s: s["id"])
    return stars


def zh_names(stars):
    """Chinese common names, matched from constellations.json's
    representative-star list: its nameEn is "<Proper> (<greek>)", and
    the proper part keys into the HYG subset by proper name."""
    by_proper = {s["proper"]: s for s in stars if s["proper"]}
    zh = {}
    with io.open(CONSTELLATIONS, encoding="utf-8") as fh:
        doc = json.load(fh)
    for entry in doc["constellations"]:
        for star in entry["brightStars"]:
            proper = star["nameEn"].split(" (")[0].strip()
            hit = by_proper.get(proper)
            if hit is not None:
                zh[hit["id"]] = star["nameZh"]
    return zh


def main():
    stars = load_subset(fetch())

    assert len(stars) == EXPECTED_COUNT, \
        f"subset count {len(stars)} != {EXPECTED_COUNT}"
    proper = [s for s in stars if s["proper"]]
    bayer = [s for s in stars if s["bayer"]]
    assert len(proper) == EXPECTED_PROPER, f"proper count {len(proper)}"
    assert len(bayer) == EXPECTED_BAYER, f"bayer count {len(bayer)}"
    betelgeuse = next(s for s in stars if s["proper"] == "Betelgeuse")
    assert abs(betelgeuse["raDeg"] - 88.7925) <= 0.01, betelgeuse
    assert abs(betelgeuse["decDeg"] - 7.4071) <= 0.01, betelgeuse

    zh = zh_names(stars)

    buf = io.BytesIO()
    buf.write(b"AEST")
    buf.write(struct.pack("<i", len(stars)))
    for s in stars:
        buf.write(struct.pack(
            "<iii h".replace(" ", ""), s["id"],
            round(s["raDeg"] * 10000), round(s["decDeg"] * 10000),
            round(s["mag"] * 100)))
    with open(OUT_BIN, "wb") as fh:
        fh.write(gzip.compress(buf.getvalue(), compresslevel=9, mtime=0))

    names = {}
    unconverted = 0
    for s in stars:
        entry = {}
        if s["proper"]:
            entry["proper"] = s["proper"]
        if s["bayer"]:
            disp = bayer_display(s["bayer"])
            if disp is None:
                unconverted += 1
            else:
                entry["bayer"] = disp
        if s["id"] in zh:
            entry["nameZh"] = zh[s["id"]]
        if entry:
            names[str(s["id"])] = entry
    payload = {
        "schemaVersion": 1,
        "dataVersion": f"HYG v4.4 bright subset mag <= 6.5 (retrieved {date.today().isoformat()})",
        "source": "HYG Database v4.4 (Astronexus / David Nash), CC BY-SA 4.0",
        "names": names,
    }
    with io.open(OUT_NAMES, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=1, sort_keys=False)
        fh.write("\n")
    print(f"stars.bin.gz: {len(stars)} stars, {os.path.getsize(OUT_BIN)} bytes gz")
    print(f"star-names.json: {len(names)} named stars "
          f"({len(zh)} with Chinese names, {unconverted} Bayer values unconverted)")


if __name__ == "__main__":
    sys.exit(main())
