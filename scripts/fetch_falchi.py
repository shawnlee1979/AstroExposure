#!/usr/bin/env python3
"""Fetch / check availability of the Falchi World Atlas GeoTIFF.

Source: Falchi, F. et al. 2016, The New World Atlas of Artificial Night
Sky Brightness, Science Advances 2(6). Data via GFZ Data Services,
DOI 10.5880/GFZ.1.4.2016.001. Licence: CC BY-NC 4.0.

What this script does (bounded, no form submission anywhere):
  1. If scripts/.cache/falchi/falchi.tif already exists, validate it
     with tifffile and report it as ready for resample_falchi.py.
  2. Otherwise make ONE attempt at the DOI landing page and ONE attempt
     at the GFZ Data Services landing page, recording HTTP status and
     whether a direct GeoTIFF download link is exposed. GFZ distributes
     this dataset through an FTP request form that asks for personal
     details; this pipeline never submits forms, so if no direct link
     is exposed the acquisition is recorded as blocked and
     resample_falchi.py falls back to its sparse-sample mode.

The outcome is written to scripts/.cache/falchi/provenance.json and
printed. Exit code 0 means "provenance recorded" (with or without the
TIFF); acquisition failure is a data-availability result, not a crash.

Usage: python3 scripts/fetch_falchi.py
"""
import io
import json
import os
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache", "falchi")
TIFF = os.path.join(CACHE, "falchi.tif")
DOI_URL = "https://doi.org/10.5880/GFZ.1.4.2016.001"
GFZ_URL = "https://dataservices.gfz-potsdam.de/panmetaworks/dispatch/10.5880/GFZ.1.4.2016.001"


def try_url(url):
    """One bounded attempt; returns a result dict, never raises."""
    try:
        import requests
        r = requests.get(url, timeout=30, allow_redirects=True)
        body = r.text[:200000]
        has_tiff_link = ".tif" in body.lower()
        return {"url": url, "status": r.status_code, "finalUrl": r.url,
                "bytesSampled": len(body), "directTiffLinkFound": has_tiff_link}
    except Exception as exc:  # network blocked / timeout / DNS
        return {"url": url, "status": None, "error": f"{type(exc).__name__}: {exc}"}


def main():
    os.makedirs(CACHE, exist_ok=True)
    record = {"dataset": "Falchi et al. 2016 World Atlas (GFZ doi:10.5880/GFZ.1.4.2016.001)",
              "checked": date.today().isoformat(), "tiffPresent": False, "attempts": []}
    if os.path.exists(TIFF):
        try:
            import tifffile
            with tifffile.TiffFile(TIFF) as tf:
                page = tf.pages[0]
                record["tiffPresent"] = True
                record["tiff"] = {"shape": list(page.shape), "dtype": str(page.dtype),
                                  "sizeBytes": os.path.getsize(TIFF)}
        except Exception as exc:
            record["tiffError"] = f"{type(exc).__name__}: {exc}"
    else:
        for url in (DOI_URL, GFZ_URL):
            record["attempts"].append(try_url(url))
        record["note"] = ("No direct GeoTIFF download link obtained. GFZ "
                          "distribution for this DOI goes through an FTP "
                          "request form asking for personal details, which "
                          "this pipeline does not submit. Public mirrors "
                          "checked by web search exposed no GeoTIFF of the "
                          "atlas itself. resample_falchi.py therefore uses "
                          "its sparse-sample fallback.")
    with io.open(os.path.join(CACHE, "provenance.json"), "w", encoding="utf-8") as fh:
        json.dump(record, fh, ensure_ascii=False, indent=1)
    print(json.dumps(record, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
