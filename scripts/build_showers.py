#!/usr/bin/env python3
"""Build data/src/main/resources/catalog/showers.json from the IMO.

Source: International Meteor Organization, Meteor Shower Calendar 2026
(IMO INFO 3-25), https://www.imo.net/files/meteor-shower/cal2026.pdf

The IMO working-list web page
(https://www.imo.net/members/imo_showers/working_shower_list) serves a
JavaScript application shell with no data rows in the HTML, so the
calendar PDF published by IMO on imo.net is the parsed source instead:

  * Table 5 (Working List of Visual Meteor Showers) -> activity period,
    peak solar longitude, radiant at maximum, velocity, population
    index r, ZHR. Parsed with pypdf from the cached PDF.
  * Table 6 (radiant positions through the year) -> radiant drift: the
    values in DRIFT below are the linear slopes of the tabulated radiant
    positions bracketing each shower's maximum, transcribed per shower
    (RA converted to hours/day, Dec in deg/day).
  * Parent bodies: the commonly cited IMO / IAU MDC associations, in
    PARENT below; unknown parents are null.
  * Activity start/end solar longitudes are not tabulated directly: the
    calendar gives activity *dates*. They are derived by anchoring on
    each shower's own peak date and peak solar longitude and converting
    the day offset at 0.9856 deg/day (mean solar motion).

Current-calendar differences against older IMO listings (the current
values win): Quadrantids ZHR 80 and peak longitude 283.15 (older lists
give up to 110 / 283.2); Orionids peak longitude 208.0 (older: 208.5).

Usage: python3 scripts/build_showers.py   (requires pypdf)
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
CACHE = os.path.join(HERE, ".cache", "imo")
OUT = os.path.join(ROOT, "data", "src", "main", "resources", "catalog", "showers.json")
PDF_URL = "https://www.imo.net/files/meteor-shower/cal2026.pdf"
WORKING_LIST_URL = "https://www.imo.net/members/imo_showers/working_shower_list"

SELECT = ["QUA", "GUM", "ACE", "LYR", "ETA", "ELY", "JPE", "CAP", "SDA",
          "GDR", "ERI", "PER", "KCG", "AUR", "SPE", "ORI", "STA", "LMI",
          "EGE", "LEO", "HYD", "GEM", "COM", "URS", "MON"]

NAME_ZH = {
    "QUA": "象限仪座流星雨", "GUM": "小熊座γ流星雨", "ACE": "半人马座α流星雨",
    "LYR": "天琴座流星雨", "ETA": "宝瓶座η流星雨", "ELY": "天琴座η流星雨",
    "JPE": "七月飞马座流星雨", "CAP": "摩羯座α流星雨", "SDA": "宝瓶座δ南流星雨",
    "GDR": "七月天龙座γ流星雨", "ERI": "波江座η流星雨", "PER": "英仙座流星雨",
    "KCG": "天鹅座κ流星雨", "AUR": "御夫座流星雨", "SPE": "九月英仙座ε流星雨",
    "ORI": "猎户座流星雨", "STA": "金牛座南流星雨", "LMI": "小狮座流星雨",
    "EGE": "双子座ε流星雨", "LEO": "狮子座流星雨", "HYD": "长蛇座σ流星雨",
    "GEM": "双子座流星雨", "COM": "后发座流星雨", "URS": "小熊座流星雨",
    "MON": "麒麟座流星雨",
}

PARENT = {
    "QUA": "2003 EH1", "LYR": "C/1861 G1 (Thatcher)", "ETA": "1P/Halley",
    "CAP": "169P/NEAT", "SDA": "96P/Machholz", "PER": "109P/Swift-Tuttle",
    "KCG": "2008 ED69", "AUR": "C/1911 N1 (Kiess)", "ORI": "1P/Halley",
    "STA": "2P/Encke", "LEO": "55P/Tempel-Tuttle", "GEM": "3200 Phaethon",
    "URS": "8P/Tuttle",
}

# (drift RA hours/day, drift Dec deg/day) from IMO Calendar 2026 Table 6.
DRIFT = {
    "QUA": (0.040, -0.20), "GUM": (0.053, -0.40), "ACE": (0.083, -0.29),
    "LYR": (0.071, 0.00), "ETA": (0.057, 0.40), "ELY": (0.067, 0.07),
    "JPE": (0.053, 0.20), "CAP": (0.059, 0.24), "SDA": (0.050, 0.15),
    "GDR": (0.067, 0.00), "ERI": (0.060, 0.40), "PER": (0.093, 0.20),
    "KCG": (0.040, 0.70), "AUR": (0.067, -0.10), "SPE": (0.067, 0.00),
    "ORI": (0.043, 0.10), "STA": (0.051, 0.19), "LMI": (0.067, -0.40),
    "EGE": (0.067, 0.00), "LEO": (0.040, -0.27), "HYD": (0.053, -0.20),
    "GEM": (0.067, -0.07), "COM": (0.060, -0.40), "URS": (0.000, -0.40),
    "MON": (0.044, -0.13),
}

MONTHS = {m: i + 1 for i, m in enumerate(
    ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"])}
DAYS_IN_MONTH = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]


def fetch(url, filename):
    path = os.path.join(CACHE, filename)
    if os.path.exists(path):
        return path
    os.makedirs(CACHE, exist_ok=True)
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


def day_of_year(month_day):
    mon, day = month_day.split(" ")
    doy = sum(DAYS_IN_MONTH[:MONTHS[mon] - 1]) + int(day)
    return doy


def lon_for_date(peak_lon, peak_date, target_date):
    delta = day_of_year(target_date) - day_of_year(peak_date)
    if delta > 182:
        delta -= 365
    if delta < -182:
        delta += 365
    return round((peak_lon + delta * 0.9856) % 360, 1)


ROW_RE = re.compile(
    r"(?P<name>[^\n()]+?)\((?P<num>\d{3}) (?P<code>[A-Z]{3})\)\s+"
    r"(?P<act_start>[A-Z][a-z]{2} \d{1,2})-(?P<act_end>[A-Z][a-z]{2} \d{1,2})\s+"
    r"(?P<max_date>[A-Z][a-z]{2} \d{1,2})\s+(?P<lon>\d+(?:\.\d+)?)\s+"
    r"(?P<ra>\d+)\s+(?P<dec>[+-]?\d+)\s+(?P<vel>\d+)\s+(?P<r>\d+(?:\.\d+)?)\s+"
    r"(?P<zhr>\d+|Var)")


def parse_table5(pdf_path):
    try:
        from pypdf import PdfReader
    except ImportError:
        raise SystemExit("pypdf is required: pip install pypdf")
    reader = PdfReader(pdf_path)
    text = None
    for page in reader.pages:
        t = page.extract_text() or ""
        if "Working List of Visual Meteor Showers" in t:
            text = t
            break
    assert text, "Table 5 page not found in IMO calendar PDF"
    text = text.replace("◦", " ").replace("−", "-").replace("–", "-")
    text = re.sub(r"(\d)\s*\.\s*(\d)", r"\1.\2", text)  # 283 . 15 -> 283.15
    rows = {}
    for m in ROW_RE.finditer(text):
        name = m.group("name").split("\n")[-1].strip()
        rows[m.group("code")] = {
            "nameEn": name, "iauNum": m.group("num"),
            "actStart": m.group("act_start"), "actEnd": m.group("act_end"),
            "maxDate": m.group("max_date"),
            "peakLon": float(m.group("lon")),
            "raDeg": float(m.group("ra")), "decDeg": float(m.group("dec")),
            "velocity": float(m.group("vel")), "r": float(m.group("r")),
            "zhr": m.group("zhr"),
        }
    return rows


def main():
    fetch(WORKING_LIST_URL, "working_shower_list.html")  # provenance attempt
    rows = parse_table5(fetch(PDF_URL, "cal2026.pdf"))
    showers = []
    for code in SELECT:
        assert code in rows, f"{code} not parsed from IMO Table 5"
        r = rows[code]
        assert r["zhr"] != "Var", f"{code} has variable ZHR, not selectable"
        dra, ddec = DRIFT[code]
        showers.append({
            "iauCode": code,
            "nameEn": r["nameEn"],
            "nameZh": NAME_ZH.get(code),
            "activityStartLon": lon_for_date(r["peakLon"], r["maxDate"], r["actStart"]),
            "activityEndLon": lon_for_date(r["peakLon"], r["maxDate"], r["actEnd"]),
            "peakLon": r["peakLon"],
            "peakZhr": int(r["zhr"]),
            "radiantRaHours": round(r["raDeg"] / 15, 2),
            "radiantDecDeg": r["decDeg"],
            "driftRaPerDay": dra,
            "driftDecPerDay": ddec,
            "parent": PARENT.get(code),
            "velocityKmS": r["velocity"],
            "populationIndex": r["r"],
        })
    showers.sort(key=lambda s: s["peakLon"])
    assert len(showers) >= 20
    golden = {"PER": (140.0, 100), "GEM": (262.2, 150), "LYR": (32.32, 18),
              "LEO": (235.27, 15), "QUA": (283.15, 80), "ORI": (208.0, 20)}
    for code, (lon, zhr) in golden.items():
        s = next(x for x in showers if x["iauCode"] == code)
        assert s["peakLon"] == lon and s["peakZhr"] == zhr, f"golden failed: {code} {s}"
    payload = {
        "schemaVersion": 1,
        "dataVersion": ("IMO Meteor Shower Calendar 2026 (IMO INFO 3-25) "
                        f"(retrieved {date.today().isoformat()})"),
        "source": "IMO Meteor Shower Calendar / Working List",
        "showers": showers,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with io.open(OUT, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    print(f"showers.json: {len(showers)} showers")


if __name__ == "__main__":
    sys.exit(main())
