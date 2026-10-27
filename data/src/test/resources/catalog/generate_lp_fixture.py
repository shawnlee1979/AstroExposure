#!/usr/bin/env python3
"""Regenerate lp-grid.bin (TEST FIXTURE) deterministically.

Layout (see GridLightPollutionSource KDoc): magic "AEGL", 4-byte big-endian
header length, header JSON, then uint16 little-endian data area.
Row 0 is the northern (latMax) edge; 0xFFFF = no data.

Grid A (coarse, listed first in the header on purpose):
  lat 30..50, lon -80..-70, step 5.0, rows 4, cols 2, offset 0, scale 0.5
  all cells raw 100 (= 50.0 mcd/m^2) except (row 1, col 0) = 0xFFFF.
Grid B (fine):
  lat 40..45, lon -76..-74, step 1.0, rows 5, cols 2, offset 16, scale 0.25
  all cells raw 40 (= 10.0) except (row 4, col 1) = 800 (= 200.0, the NYC
  cell for 40.7128, -74.0060) and (row 0, col 1) = 0xFFFF (falls back to A).
"""
import json
import struct

NO_DATA = 0xFFFF

grid_a = [100] * 8
grid_a[1 * 2 + 0] = NO_DATA
grid_b = [40] * 10
grid_b[4 * 2 + 1] = 800
grid_b[0 * 2 + 1] = NO_DATA

header = {
    "schemaVersion": 1,
    "dataVersion": "test-fixture-1",
    "grids": [
        {"latMin": 30.0, "latMax": 50.0, "lonMin": -80.0, "lonMax": -70.0,
         "stepDeg": 5.0, "rows": 4, "cols": 2, "offset": 0, "scale": 0.5},
        {"latMin": 40.0, "latMax": 45.0, "lonMin": -76.0, "lonMax": -74.0,
         "stepDeg": 1.0, "rows": 5, "cols": 2, "offset": 16, "scale": 0.25},
    ],
}
header_bytes = json.dumps(header, separators=(",", ":")).encode("utf-8")
data = struct.pack("<8H", *grid_a) + struct.pack("<10H", *grid_b)
out = b"AEGL" + struct.pack(">I", len(header_bytes)) + header_bytes + data
with open("lp-grid.bin", "wb") as f:
    f.write(out)
print("wrote lp-grid.bin", len(out), "bytes")
