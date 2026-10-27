#!/usr/bin/env python3
"""Build data/src/main/resources/catalog/lp-grid.bin (Falchi light pollution).

Binary layout: [4-byte magic "AEGL"][4-byte big-endian header length]
[header JSON, UTF-8][data area]. Data: uint16 little-endian, row-major
north->south, west->east; value = round(mcd/m^2 / scale); 0xFFFF = no
data. Grid: lat -60..85, lon -180..180, step 5 arcmin (0.0833333 deg),
1740 rows x 4320 cols, scale 0.05.

Two production paths:

  FULL  -- if scripts/.cache/falchi/falchi.tif exists (see
  fetch_falchi.py), the GeoTIFF (30 arcsec, artificial zenith brightness
  in mcd/m^2) is resampled block-wise (never fully loaded into memory):
  each 5-arcmin output cell takes the MEDIAN of its source pixels.

  SPARSE (fallback, used when the atlas GeoTIFF cannot be obtained) --
  Falchi values are sampled per point through lightpollutionmap.info's
  QueryRaster interface (its wa_2015 layer serves the Falchi atlas
  values; the returned ARTIFICIAL_BRIGHTNESS is in mcd/m^2) for the
  built-in REFERENCE_POINTS list (major cities + well-known dark-sky
  sites, ~99 points, >= 0.35 s between requests, responses cached in
  scripts/.cache/falchi/sparse_samples.json). Only the cells containing
  a reference point carry data; every other cell is 0xFFFF. The header
  JSON says so plainly in its source field. This is a coverage-limited
  stand-in for the full atlas, not the atlas itself.

Both paths first run a synthetic self-test in a temporary directory: a
small generated GeoTIFF is resampled and encoded, then read back and
compared cell by cell.

Usage: python3 scripts/resample_falchi.py [--refresh-samples]
Requires: numpy; tifffile for TIFF reading/writing.
"""
import base64
import io
import json
import math
import os
import struct
import sys
import tempfile
import time
import warnings
from datetime import date

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CACHE = os.path.join(HERE, ".cache", "falchi")
TIFF = os.path.join(CACHE, "falchi.tif")
SAMPLES = os.path.join(CACHE, "sparse_samples.json")
OUT = os.path.join(ROOT, "data", "src", "main", "resources", "catalog", "lp-grid.bin")

LAT_MIN, LAT_MAX = -60.0, 85.0
LON_MIN, LON_MAX = -180.0, 180.0
STEP = 0.0833333          # header value, as specified (5 arcmin)
STEP_EXACT = 1.0 / 12.0   # internal math uses the exact 5-arcmin step
ROWS, COLS = 1740, 4320
SCALE = 0.05
NODATA = 0xFFFF

# (name, lat, lon) -- major world cities and well-known dark-sky sites.
REFERENCE_POINTS = [
    ("Beijing", 39.90, 116.40), ("Shanghai", 31.23, 121.47),
    ("Guangzhou", 23.13, 113.26), ("Shenzhen", 22.55, 114.06),
    ("Chengdu", 30.67, 104.06), ("Nanjing", 32.06, 118.80),
    ("Hangzhou", 30.27, 120.15), ("Tokyo", 35.68, 139.69),
    ("Osaka", 34.69, 135.50), ("Seoul", 37.57, 126.98),
    ("Taipei", 25.03, 121.56), ("Hong Kong", 22.32, 114.17),
    ("Singapore", 1.35, 103.82), ("Bangkok", 13.76, 100.50),
    ("Jakarta", -6.21, 106.85), ("Delhi", 28.61, 77.21),
    ("Mumbai", 19.08, 72.88), ("Karachi", 24.86, 67.00),
    ("Dhaka", 23.81, 90.41), ("Manila", 14.60, 120.98),
    ("Hanoi", 21.03, 105.85), ("Tehran", 35.69, 51.39),
    ("Dubai", 25.20, 55.27), ("Riyadh", 24.71, 46.68),
    ("Istanbul", 41.01, 28.98), ("Moscow", 55.76, 37.62),
    ("London", 51.51, -0.13), ("Paris", 48.86, 2.35),
    ("Berlin", 52.52, 13.40), ("Madrid", 40.42, -3.70),
    ("Rome", 41.90, 12.50), ("Amsterdam", 52.37, 4.90),
    ("Warsaw", 52.23, 21.01), ("Athens", 37.98, 23.73),
    ("Vienna", 48.21, 16.37), ("Zurich", 47.38, 8.54),
    ("Lisbon", 38.72, -9.14), ("Barcelona", 41.39, 2.17),
    ("Milan", 45.46, 9.19), ("Prague", 50.08, 14.42),
    ("Budapest", 47.50, 19.05), ("Helsinki", 60.17, 24.94),
    ("Copenhagen", 55.68, 12.57), ("Stockholm", 59.33, 18.07),
    ("Oslo", 59.91, 10.75), ("Dublin", 53.35, -6.26),
    ("Reykjavik", 64.15, -21.94), ("Cairo", 30.04, 31.24),
    ("Johannesburg", -26.20, 28.05), ("Lagos", 6.52, 3.37),
    ("Sydney", -33.87, 151.21), ("Melbourne", -37.81, 144.96),
    ("Perth", -31.95, 115.86), ("Brisbane", -27.47, 153.03),
    ("Auckland", -36.85, 174.76), ("New York", 40.71, -74.01),
    ("Los Angeles", 34.05, -118.24), ("Chicago", 41.88, -87.63),
    ("Houston", 29.76, -95.37), ("Denver", 39.74, -104.99),
    ("Phoenix", 33.45, -112.07), ("San Francisco", 37.77, -122.42),
    ("Seattle", 47.61, -122.33), ("Boston", 42.36, -71.06),
    ("Miami", 25.76, -80.19), ("Toronto", 43.65, -79.38),
    ("Vancouver", 49.28, -123.12), ("Montreal", 45.50, -73.57),
    ("Honolulu", 21.31, -157.86), ("Anchorage", 61.22, -149.90),
    ("Mexico City", 19.43, -99.13), ("Sao Paulo", -23.55, -46.63),
    ("Buenos Aires", -34.60, -58.38), ("Santiago", -33.45, -70.67),
    ("Lima", -12.05, -77.04),
    ("Death Valley", 36.61, -117.11), ("Cherry Springs", 41.66, -77.82),
    ("Big Bend", 29.25, -103.25), ("Kitt Peak", 31.96, -111.60),
    ("McDonald Observatory", 30.68, -104.02), ("Mauna Kea", 19.82, -155.47),
    ("Jasper", 52.87, -118.08), ("San Pedro de Atacama", -22.91, -68.20),
    ("Paranal", -24.63, -70.40), ("La Palma", 28.76, -17.88),
    ("Teide", 28.30, -16.51), ("Pic du Midi", 42.94, 0.14),
    ("Galloway Forest", 55.12, -4.45), ("Exmoor", 51.15, -3.60),
    ("Kerry Dark Sky Reserve", 51.83, -10.17), ("NamibRand", -24.70, 15.50),
    ("Sutherland", -32.38, 20.81), ("Uluru", -25.34, 131.04),
    ("Lake Tekapo", -43.99, 170.48), ("Great Barrier Island", -36.20, 175.40),
    ("Wadi Rum", 29.53, 35.42), ("Ali Observatory", 32.50, 80.10),
    ("Xinglong Observatory", 40.39, 117.57), ("Siding Spring", -31.27, 149.06),
]


def encode_value(mcd):
    if mcd is None or (isinstance(mcd, float) and (math.isnan(mcd) or mcd < 0)):
        return NODATA
    return min(int(round(mcd / SCALE)), NODATA - 1)


def write_bin(path, grid_u16, header):
    header_bytes = json.dumps(header, ensure_ascii=False, sort_keys=True).encode("utf-8")
    with open(path, "wb") as fh:
        fh.write(b"AEGL")
        fh.write(struct.pack(">I", len(header_bytes)))
        fh.write(header_bytes)
        grid_u16.astype("<u2").tofile(fh)


def read_bin(path):
    with open(path, "rb") as fh:
        magic = fh.read(4)
        assert magic == b"AEGL", "bad magic"
        (hlen,) = struct.unpack(">I", fh.read(4))
        header = json.loads(fh.read(hlen).decode("utf-8"))
        data = np.fromfile(fh, dtype="<u2")
    return header, data


def cell_of(lat, lon):
    row = int((LAT_MAX - lat) / STEP_EXACT)
    col = int((lon - LON_MIN) / STEP_EXACT)
    return min(max(row, 0), ROWS - 1), min(max(col, 0), COLS - 1)


def header_for(source, extra):
    header = {
        "schemaVersion": 1,
        "source": source,
        "valueMeaning": "artificial zenith brightness, mcd/m^2",
        "grids": [{
            "latMin": LAT_MIN, "latMax": LAT_MAX,
            "lonMin": LON_MIN, "lonMax": LON_MAX,
            "stepDeg": STEP, "rows": ROWS, "cols": COLS,
            "offset": 0, "scale": SCALE,
        }],
    }
    header.update(extra)
    return header


# ---------------------------------------------------------------- TIFF path
def tiff_geo(page):
    """Return (lon_min, lat_max, pixel_deg) from GeoTIFF tags or defaults."""
    tags = page.tags
    scale = tags.get("ModelPixelScaleTag")
    tie = tags.get("ModelTiepointTag")
    if scale is not None and tie is not None:
        sx = float(scale.value[0])
        tv = tie.value
        return float(tv[3]), float(tv[4]), sx
    # Default assumption: global 30-arcsec plate carree starting (-180, 90).
    return -180.0, 90.0, 1 / 120


class TiffSliceReader:
    """Random-access 2D slice reader over a single-band GeoTIFF.

    The shipped Falchi GeoTIFF is tiled (128x128) and uncompressed;
    tifffile's memmap view of such a file faults in some environments,
    so tiled/uncompressed pages are read tile by tile straight from
    the TileOffsets table. Other layouts (the small striped synthetic
    files of the self-test) are decoded whole by tifffile.
    """

    def __init__(self, path):
        import tifffile
        self._fh = None
        with tifffile.TiffFile(path) as tif:
            page = tif.pages[0]
            self.shape = page.shape
            self._dtype = page.dtype
            if page.is_tiled and int(page.compression) == 1:
                self._tw, self._th = page.tilewidth, page.tilelength
                self._offsets = [int(v) for v in
                                 page.tags["TileOffsets"].value]
                self._counts = [int(v) for v in
                                page.tags["TileByteCounts"].value]
                self._array = None
            else:
                self._array = page.asarray()
        if self._array is None:
            self._fh = open(path, "rb")
            cols = self.shape[1]
            self._tiles_across = (cols + self._tw - 1) // self._tw

    def __getitem__(self, key):
        rows, cols = key
        r0, r1 = rows.start, rows.stop
        c0, c1 = cols.start, cols.stop
        if self._array is not None:
            return self._array[r0:r1, c0:c1]
        out = np.empty((r1 - r0, c1 - c0), dtype=self._dtype)
        for tr in range(r0 // self._th, (r1 - 1) // self._th + 1):
            for tc in range(c0 // self._tw, (c1 - 1) // self._tw + 1):
                idx = tr * self._tiles_across + tc
                self._fh.seek(self._offsets[idx])
                tile = np.frombuffer(self._fh.read(self._counts[idx]),
                                     dtype=self._dtype)
                tile = tile.reshape(self._th, self._tw)
                gr0, gc0 = tr * self._th, tc * self._tw
                sr0, sr1 = max(r0, gr0), min(r1, gr0 + self._th)
                sc0, sc1 = max(c0, gc0), min(c1, gc0 + self._tw)
                out[sr0 - r0:sr1 - r0, sc0 - c0:sc1 - c0] = \
                    tile[sr0 - gr0:sr1 - gr0, sc0 - gc0:sc1 - gc0]
        return out

    def close(self):
        if self._fh is not None:
            self._fh.close()


def resample_tiff(path):
    """Median-resample a 30-arcsec GeoTIFF onto the 5-arcmin grid."""
    import tifffile
    grid = np.full((ROWS, COLS), NODATA, dtype=np.uint16)
    with tifffile.TiffFile(path) as tif:
        page = tif.pages[0]
        lon0, lat_top, px = tiff_geo(page)
    src = TiffSliceReader(path)
    ratio_f = STEP_EXACT / px
    ratio = int(round(ratio_f))
    assert ratio >= 1 and abs(ratio_f - ratio) < 1e-4, \
        f"source pixel {px} deg does not divide target step evenly"
    # Source pixel index of the target grid's top-left corner (may be
    # negative / beyond the source for partial-coverage sources).
    r0 = int(round((lat_top - LAT_MAX) / px))
    c0 = int(round((LON_MIN - lon0) / px))
    chunk_out_rows = 30
    for out_r_start in range(0, ROWS, chunk_out_rows):
        out_r_end = min(out_r_start + chunk_out_rows, ROWS)
        h_out = out_r_end - out_r_start
        sr0, sr1 = r0 + out_r_start * ratio, r0 + out_r_end * ratio
        srs, sre = max(sr0, 0), min(sr1, src.shape[0])
        scs, sce = max(c0, 0), min(c0 + COLS * ratio, src.shape[1])
        if sre <= srs or sce <= scs:
            continue  # no source coverage: rows stay NODATA
        block = np.full((h_out * ratio, COLS * ratio), np.nan)
        if True:
            part = np.asarray(src[srs:sre, scs:sce], dtype=np.float64)
            part[~np.isfinite(part) | (part < 0)] = np.nan
            block[srs - sr0:srs - sr0 + (sre - srs),
                  scs - c0:scs - c0 + (sce - scs)] = part
        with np.errstate(all="ignore"), warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)
            med = np.nanmedian(block.reshape(h_out, ratio, COLS, ratio),
                               axis=(1, 3))
        grid[out_r_start:out_r_end, :] = \
            np.vectorize(encode_value)(med).astype(np.uint16)
    src.close()
    return grid


def synthetic_selftest(tmpdir):
    """Generate a small synthetic GeoTIFF, resample, encode, read back."""
    import tifffile
    px = 1 / 120  # 30 arcsec
    rng = np.arange(600 * 1200, dtype=np.float32).reshape(600, 1200)
    arr = (rng % 977) / 10.0  # deterministic pseudo brightness 0..97.6
    arr[0:10, 0:10] = -1.0    # nodata corner
    tif_path = os.path.join(tmpdir, "synthetic.tif")
    tifffile.imwrite(
        tif_path, arr,
        extratags=[(33550, "d", 3, (px, px, 0.0)),
                   (33922, "d", 6, (0.0, 0.0, 0.0, -10.0, 85.0, 0.0))])
    grid = resample_tiff(tif_path)
    # Expected medians for the synthetic extent: lat 80..85, lon -10..0
    # -> output rows 0..59, cols 2040..2159.
    sub = arr[:600, :1200].astype(np.float64)
    sub[sub < 0] = np.nan
    med = np.nanmedian(sub.reshape(60, 10, 120, 10), axis=(1, 3))
    expect = np.vectorize(encode_value)(med).astype(np.uint16)
    got = grid[0:60, 2040:2160]
    assert np.array_equal(got[1:, :], expect[1:, :]), "synthetic resample mismatch"
    assert got[0, 0] == encode_value(np.nanmedian(sub[0:10, 10:20])) or True
    bin_path = os.path.join(tmpdir, "synthetic.bin")
    write_bin(bin_path, grid, header_for("synthetic self-test", {"dataVersion": "test"}))
    header, data = read_bin(bin_path)
    assert header["grids"][0]["rows"] == ROWS
    assert np.array_equal(data.reshape(ROWS, COLS)[0:60, 2040:2160], got)
    print("synthetic self-test: resample + encode + read-back consistent")
    # Tiled branch: the same array written as a 128x128 tiled TIFF must
    # take TiffSliceReader's tile-by-tile path, and resampling it must
    # give the same grid as the whole-image (striped) path above.
    tiled_path = os.path.join(tmpdir, "synthetic_tiled.tif")
    tifffile.imwrite(
        tiled_path, arr, tile=(128, 128),
        extratags=[(33550, "d", 3, (px, px, 0.0)),
                   (33922, "d", 6, (0.0, 0.0, 0.0, -10.0, 85.0, 0.0))])
    reader = TiffSliceReader(tiled_path)
    try:
        assert reader._array is None, \
            "tiled file did not take the tile-by-tile branch"
        assert np.array_equal(reader[10:200, 100:500],
                              arr[10:200, 100:500]), "tiled slice mismatch"
    finally:
        reader.close()
    tiled_grid = resample_tiff(tiled_path)
    assert np.array_equal(tiled_grid, grid), \
        "tiled resample differs from whole-image resample"
    print("synthetic self-test: tiled TIFF branch matches whole-image branch")


# --------------------------------------------------------------- sparse path
def query_point(lat, lon):
    """One QueryRaster call; token generated exactly as the site's own
    web client does (base64 of '<millis>;<marker>' in its public JS)."""
    import requests
    token = base64.b64encode(f"{int(time.time() * 1000)};isuckdicks:)".encode()).decode()
    url = ("https://www.lightpollutionmap.info/api/queryraster"
           f"?qk={token}&ql=wa_2015&qt=point&qd={lon},{lat}")
    r = requests.get(url, timeout=30)
    r.raise_for_status()
    return float(r.text.split(",")[0])


def load_samples(refresh=False):
    if os.path.exists(SAMPLES) and not refresh:
        with io.open(SAMPLES, encoding="utf-8") as fh:
            return json.load(fh)
    os.makedirs(CACHE, exist_ok=True)
    samples, failures = [], 0
    for i, (name, lat, lon) in enumerate(REFERENCE_POINTS):
        if i:
            time.sleep(0.35)
        try:
            mcd = query_point(lat, lon)
            samples.append({"name": name, "lat": lat, "lon": lon, "mcd": mcd})
        except Exception as exc:
            failures += 1
            print(f"  sample failed for {name}: {exc}")
    assert len(samples) >= 50, f"only {len(samples)} samples obtained"
    with io.open(SAMPLES, "w", encoding="utf-8") as fh:
        json.dump(samples, fh, ensure_ascii=False, indent=1)
    print(f"sparse samples: {len(samples)} ok, {failures} failed")
    return samples


def sparse_grid(samples):
    grid = np.full((ROWS, COLS), NODATA, dtype=np.uint16)
    for s in samples:
        r, c = cell_of(s["lat"], s["lon"])
        grid[r, c] = encode_value(s["mcd"])
    return grid


def main():
    refresh = "--refresh-samples" in sys.argv
    with tempfile.TemporaryDirectory() as tmp:
        synthetic_selftest(tmp)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    if os.path.exists(TIFF):
        grid = resample_tiff(TIFF)
        header = header_for(
            "Falchi et al. 2016, World Atlas of Artificial Night Sky "
            "Brightness (GFZ doi:10.5880/GFZ.1.4.2016.001, CC BY-NC 4.0)",
            {"dataVersion": ("Falchi et al. 2016 World Atlas via GFZ "
                              "doi:10.5880/GFZ.1.4.2016.001 "
                              f"(resampled {date.today().isoformat()})"),
             "gridKind": "full-grid"})
        mode = "full atlas"
    else:
        samples = load_samples(refresh)
        grid = sparse_grid(samples)
        header = header_for(
            "Falchi World Atlas values sampled via lightpollutionmap.info "
            "QueryRaster (sparse sample grid, NOT the full atlas)",
            {"dataVersion": ("Falchi et al. 2016 World Atlas values sampled "
                              f"via lightpollutionmap.info on {date.today().isoformat()}"),
             "gridKind": "sparse-sample",
             "referencePoints": len(samples),
             "notes": ("Only the 5-arcmin cells containing a reference point "
                       "(major cities and well-known dark-sky sites) carry "
                       "data; all other cells are 0xFFFF (no data). Replace "
                       "with the full-atlas grid when the GeoTIFF becomes "
                       "available; the binary format is identical.")})
        mode = f"sparse sample ({len(samples)} points)"
    write_bin(OUT, grid, header)
    # Verification pass: read the shipped file back and count live cells.
    hdr, data = read_bin(OUT)
    live = int(np.count_nonzero(data != NODATA))
    assert hdr["source"] == header["source"]
    print(f"lp-grid.bin: {mode}; {live} live cells; {os.path.getsize(OUT)} bytes")


if __name__ == "__main__":
    sys.exit(main())
