#!/usr/bin/env python3
"""Build data/src/main/resources/catalog/eclipses.json from NASA GSFC.

Sources (NASA GSFC Eclipse Web Site, Fred Espenak & Jean Meeus), cached
under scripts/.cache/nasa/:
  * Decade catalogues SEdecade2021 / SEdecade2031 / LEdecade2021 /
    LEdecade2031 -- primary rows: date, TD of greatest eclipse, type,
    Saros, magnitude, central/total duration, visibility region text.
  * Five Millennium Canon century catalogues SEcat5/SE2001-2100.html and
    LEcat5/LE2001-2100.html -- supplementary columns the decade pages do
    not carry: solar path width, lunar penumbral magnitude and total-phase
    duration.

Field notes:
  * peakUtc is the catalogue TD of greatest eclipse, seconds included,
    with a "Z" suffix; the TD-UTC offset (~1 min) is not applied.
  * For penumbral lunar eclipses, `magnitude` is the *penumbral*
    magnitude (the decade table's umbral magnitude is negative there) and
    the visibility text says so explicitly.
  * Lunar centralDurationSec = duration of the total phase (total lunar
    eclipses only); solar centralDurationSec = central-line duration at
    greatest eclipse (central eclipses only); otherwise null.

Usage: python3 scripts/build_eclipses.py
"""
import io
import json
import os
import re
import subprocess
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CACHE = os.path.join(HERE, ".cache", "nasa")
OUT = os.path.join(ROOT, "data", "src", "main", "resources", "catalog", "eclipses.json")
SITE = "https://eclipse.gsfc.nasa.gov"
MONTHS = {m: i + 1 for i, m in enumerate(
    ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"])}

SOLAR_TYPE = {"Total": "TOTAL", "Annular": "ANNULAR", "Partial": "PARTIAL", "Hybrid": "HYBRID"}
LUNAR_TYPE = {"Total": "TOTAL", "Partial": "PARTIAL", "Penumbral": "PENUMBRAL"}


def fetch(rel):
    path = os.path.join(CACHE, rel.replace("/", "_"))
    if os.path.exists(path):
        return io.open(path, encoding="utf-8", errors="replace").read()
    os.makedirs(CACHE, exist_ok=True)
    url = f"{SITE}/{rel}"
    try:
        import requests
        r = requests.get(url, timeout=120)
        r.raise_for_status()
        text = r.text
    except ImportError:
        text = subprocess.run(["curl", "-fsSL", url], check=True,
                              capture_output=True, text=True).stdout
    with io.open(path, "w", encoding="utf-8") as fh:
        fh.write(text)
    return text


def strip_tags(html):
    return re.sub(r"<[^>]+>", " ", html)


def table_rows(html):
    """Yield lists of cleaned cell texts for every <tr> with >= 6 cells."""
    for tr in re.findall(r"<tr.*?</tr>", html, re.S | re.I):
        cells = [re.sub(r"\s+", " ", strip_tags(td)).strip()
                 for td in re.findall(r"<td.*?</td>", tr, re.S | re.I)]
        if len(cells) >= 6:
            yield cells


def parse_date(text):
    m = re.match(r"(\d{4}) (\w{3}) (\d{1,2})$", text.strip())
    if not m:
        return None
    return f"{m.group(1)}-{MONTHS[m.group(2)]:02d}-{int(m.group(3)):02d}"


def duration_solar(text):
    m = re.match(r"(\d+)m(\d+)s$", text.strip())
    return int(m.group(1)) * 60 + int(m.group(2)) if m else None


def duration_lunar(text):
    m = re.match(r"(\d+)h(\d+)m$", text.strip())
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 if m else None


def century_solar(html):
    # Token layout: num date(3) time dT luna saros type qse gamma mag
    # lat lng alt [width duration]  (partial eclipses end after alt=0)
    out = {}
    for line in html.splitlines():
        tok = re.sub(r"\s+", " ", strip_tags(line)).strip().split(" ")
        if len(tok) < 15 or not re.match(r"\d{4}$", tok[1] if len(tok) > 1 else ""):
            continue
        if tok[8][:1] not in "TAPH" or not re.match(r"\d\d:\d\d:\d\d$", tok[4]):
            continue
        d = parse_date(" ".join(tok[1:4]))
        width = dur = None
        if len(tok) >= 17:
            width = int(tok[15]) if tok[15].isdigit() else None
            m = re.match(r"(\d+)m(\d+)s$", tok[16])
            dur = int(m.group(1)) * 60 + int(m.group(2)) if m else None
        if d:
            out[d] = (width, dur)
    return out


def century_lunar(html):
    # Token layout: num date(3) time dT luna saros type qse gamma
    # penMag umMag penDur parDur totalDur lat lng  (durations may be "-")
    out = {}
    for line in html.splitlines():
        tok = re.sub(r"\s+", " ", strip_tags(line)).strip().split(" ")
        if len(tok) < 18 or not re.match(r"\d{4}$", tok[1] if len(tok) > 1 else ""):
            continue
        if tok[8][:1] not in "TPN" or not re.match(r"\d\d:\d\d:\d\d$", tok[4]):
            continue
        d = parse_date(" ".join(tok[1:4]))
        total_min = float(tok[15]) if tok[15] != "-" else None
        if d:
            out[d] = (float(tok[11]), float(tok[12]), total_min)
    return out


def in_range(d):
    return "2026-01-01" <= d <= "2035-12-31"


def main():
    solar_extra = century_solar(fetch("SEcat5/SE2001-2100.html"))
    lunar_extra = century_lunar(fetch("LEcat5/LE2001-2100.html"))
    eclipses = []

    for decade in ("SEdecade/SEdecade2021.html", "SEdecade/SEdecade2031.html"):
        for cells in table_rows(fetch(decade)):
            d = parse_date(cells[0])
            if not d or not in_range(d) or cells[2] not in SOLAR_TYPE:
                continue
            width, _ = solar_extra.get(d, (None, None))
            eclipses.append({
                "date": d, "kind": "SOLAR", "type": SOLAR_TYPE[cells[2]],
                "peakUtc": f"{d}T{cells[1]}Z",
                "magnitude": float(cells[4]),
                "saros": int(cells[3]),
                "pathWidthKm": width,
                "centralDurationSec": duration_solar(cells[5]),
                "visibility": " ".join(cells[6:]),
            })

    for decade in ("LEdecade/LEdecade2021.html", "LEdecade/LEdecade2031.html"):
        for cells in table_rows(fetch(decade)):
            d = parse_date(cells[0])
            if not d or not in_range(d) or cells[2] not in LUNAR_TYPE:
                continue
            etype = LUNAR_TYPE[cells[2]]
            magnitude = float(cells[4])
            visibility = cells[-1]
            central = None
            pen, um, total_min = lunar_extra.get(d, (None, None, None))
            if etype == "PENUMBRAL":
                assert pen is not None, f"no penumbral magnitude for {d}"
                magnitude = pen
                visibility += (f" [penumbral eclipse: magnitude is the penumbral "
                               f"magnitude; umbral magnitude {um}]")
            if etype == "TOTAL":
                if total_min is not None:
                    central = round(total_min * 60)
                elif len(cells) > 7:
                    central = duration_lunar(cells[6])
            eclipses.append({
                "date": d, "kind": "LUNAR", "type": etype,
                "peakUtc": f"{d}T{cells[1]}Z",
                "magnitude": magnitude,
                "saros": int(cells[3]),
                "pathWidthKm": None,
                "centralDurationSec": central,
                "visibility": visibility,
            })

    eclipses.sort(key=lambda e: (e["date"], e["kind"]))
    solar = [e for e in eclipses if e["kind"] == "SOLAR"]
    lunar = [e for e in eclipses if e["kind"] == "LUNAR"]
    assert len(eclipses) >= 40, f"only {len(eclipses)} eclipses parsed"
    golden = {("2026-08-12", "SOLAR"): "TOTAL", ("2026-03-03", "LUNAR"): "TOTAL",
              ("2027-08-02", "SOLAR"): "TOTAL", ("2026-02-17", "SOLAR"): "ANNULAR"}
    for (d, kind), want in golden.items():
        got = [e for e in eclipses if e["date"] == d and e["kind"] == kind]
        assert got and got[0]["type"] == want, f"golden check failed: {d} {kind}"
    assert solar_extra.get("2026-08-12", (None,))[0] == 294

    payload = {
        "schemaVersion": 1,
        "dataVersion": ("NASA GSFC decade + Five Millennium Canon catalogues "
                        f"(retrieved {date.today().isoformat()})"),
        "source": "NASA GSFC Eclipse Web Site (Espenak & Meeus)",
        "eclipses": eclipses,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with io.open(OUT, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    print(f"eclipses.json: {len(eclipses)} eclipses ({len(solar)} solar, {len(lunar)} lunar)")


if __name__ == "__main__":
    sys.exit(main())
