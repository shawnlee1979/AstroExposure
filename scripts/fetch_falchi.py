#!/usr/bin/env python3
"""Fetch the Falchi World Atlas GeoTIFF from GFZ Data Services.

Source: Falchi, F. et al. 2016, The New World Atlas of Artificial Night
Sky Brightness, Science Advances 2(6), doi:10.1126/sciadv.1600377.
Data via GFZ Data Services, DOI 10.5880/GFZ.1.4.2016.001.
Licence: CC BY-NC 4.0 (current GFZ landing-page terms; see
DATA-LICENSES.md for the note on the older licence wording in the
archive's own 2016 README).

What this script does:

  1. Downloads World_Atlas_2015.zip from the GFZ datapub direct link
     (https://datapub.gfz.de/download/10.5880.GFZ.1.4.2016.001/
     World_Atlas_2015.zip) with HTTP Range resume: an interrupted run
     leaves a partial file in scripts/.cache/falchi/ and re-running
     this script continues from the byte offset already on disk
     instead of starting over. The expected total size is pinned
     (EXPECTED_ZIP_BYTES) and verified on completion.
  2. Computes the SHA-256 of the downloaded ZIP.
  3. Extracts ONLY the GeoTIFF member (World_Atlas_2015.tif) to
     scripts/.cache/falchi/falchi.tif, the path resample_falchi.py
     expects; the archive's .tpk member is not extracted.
  4. Computes the SHA-256 of the extracted TIFF and validates it with
     tifffile (shape, dtype, georeferencing tags).

All measured facts (sizes, SHA-256 digests, TIFF metadata) are written
to scripts/.cache/falchi/provenance.json and printed. Re-running with
a complete cache present is a no-op apart from re-validation.

Disk budget: ZIP ~0.69 GB + extracted TIFF ~3.0 GB; keep ~4 GB free.

Usage: python3 scripts/fetch_falchi.py
Requires: requests, tifffile (validation only).
"""
import hashlib
import io
import json
import os
import sys
import time
import zipfile
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache", "falchi")
ZIP_PATH = os.path.join(CACHE, "World_Atlas_2015.zip")
TIFF = os.path.join(CACHE, "falchi.tif")
TIFF_TMP = TIFF + ".part"
DOWNLOAD_URL = ("https://datapub.gfz.de/download/"
                "10.5880.GFZ.1.4.2016.001/World_Atlas_2015.zip")
EXPECTED_ZIP_BYTES = 684_266_450
CHUNK = 1024 * 1024
MAX_ATTEMPTS = 30


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        while True:
            block = fh.read(8 * 1024 * 1024)
            if not block:
                break
            h.update(block)
    return h.hexdigest()


def download_resumable(url, dest, expected_bytes):
    """Download url to dest, resuming from any partial file on disk."""
    import requests
    attempt = 0
    while True:
        have = os.path.getsize(dest) if os.path.exists(dest) else 0
        if have == expected_bytes:
            return
        if have > expected_bytes:
            raise SystemExit(
                f"partial file {dest} is {have} bytes, larger than the "
                f"expected {expected_bytes}; refusing to continue")
        attempt += 1
        if attempt > MAX_ATTEMPTS:
            raise SystemExit(f"download did not complete after "
                             f"{MAX_ATTEMPTS} attempts ({have} bytes)")
        headers = {"Range": f"bytes={have}-"} if have else {}
        try:
            with requests.get(url, headers=headers, stream=True,
                              timeout=(30, 120)) as r:
                if have and r.status_code == 200:
                    # Server ignored the Range header: restart cleanly.
                    have = 0
                elif have and r.status_code != 206:
                    r.raise_for_status()
                mode = "ab" if have else "wb"
                with open(dest, mode) as fh:
                    for chunk in r.iter_content(CHUNK):
                        if chunk:
                            fh.write(chunk)
        except Exception as exc:  # network interruption: loop resumes
            print(f"  attempt {attempt}: {type(exc).__name__}: {exc}; "
                  f"{os.path.getsize(dest) if os.path.exists(dest) else 0} "
                  f"bytes on disk, resuming", flush=True)
            time.sleep(min(5 * attempt, 30))


def extract_tiff(zip_path, dest):
    """Extract only the World_Atlas_2015.tif member of the archive."""
    with zipfile.ZipFile(zip_path) as zf:
        members = zf.namelist()
        tiffs = [m for m in members if m.lower().endswith(".tif")]
        if len(tiffs) != 1:
            raise SystemExit(f"expected exactly one .tif member, "
                             f"archive contains: {members}")
        member = tiffs[0]
        info = zf.getinfo(member)
        print(f"extracting {member} ({info.file_size} bytes) -> {dest}",
              flush=True)
        with zf.open(member) as src, open(TIFF_TMP, "wb") as out:
            while True:
                block = src.read(8 * 1024 * 1024)
                if not block:
                    break
                out.write(block)
    if os.path.getsize(TIFF_TMP) != info.file_size:
        raise SystemExit("extracted TIFF size does not match the "
                         "archive directory entry")
    os.replace(TIFF_TMP, dest)
    return {"member": member,
            "membersSkipped": [m for m in members if m != member]}


def validate_tiff(path):
    import tifffile
    with tifffile.TiffFile(path) as tf:
        page = tf.pages[0]
        tags = {}
        for name in ("ModelPixelScaleTag", "ModelTiepointTag",
                     "GeoKeyDirectoryTag", "GDAL_NODATA"):
            tag = page.tags.get(name)
            if tag is not None:
                value = tag.value
                try:
                    value = list(value)
                except TypeError:
                    pass
                tags[name] = value
        return {"shape": list(page.shape), "dtype": str(page.dtype),
                "sizeBytes": os.path.getsize(path), "tags": tags}


def main():
    os.makedirs(CACHE, exist_ok=True)
    record = {"dataset": "Falchi et al. 2016 World Atlas "
                         "(GFZ doi:10.5880/GFZ.1.4.2016.001)",
              "downloadUrl": DOWNLOAD_URL,
              "checked": date.today().isoformat()}

    if not (os.path.exists(TIFF) and os.path.exists(ZIP_PATH)
            and os.path.getsize(ZIP_PATH) == EXPECTED_ZIP_BYTES):
        print(f"downloading {DOWNLOAD_URL} (expected "
              f"{EXPECTED_ZIP_BYTES} bytes, resumable)", flush=True)
        download_resumable(DOWNLOAD_URL, ZIP_PATH, EXPECTED_ZIP_BYTES)
    size = os.path.getsize(ZIP_PATH)
    assert size == EXPECTED_ZIP_BYTES, \
        f"ZIP size {size} != expected {EXPECTED_ZIP_BYTES}"
    record["zip"] = {"sizeBytes": size, "sha256": sha256_of(ZIP_PATH)}
    print(f"ZIP ok: {size} bytes, sha256 {record['zip']['sha256']}",
          flush=True)

    if not os.path.exists(TIFF):
        record["extraction"] = extract_tiff(ZIP_PATH, TIFF)
    record["tiff"] = validate_tiff(TIFF)
    record["tiff"]["sha256"] = sha256_of(TIFF)
    record["tiffPresent"] = True
    print(f"TIFF ok: {record['tiff']}", flush=True)

    with io.open(os.path.join(CACHE, "provenance.json"), "w",
                 encoding="utf-8") as fh:
        json.dump(record, fh, ensure_ascii=False, indent=1)
    print(json.dumps(record, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
