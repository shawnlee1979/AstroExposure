# Pipeline data sources

One entry per pipeline: source, retrieval date, upstream version,
licence. (Licence obligations for the whole project live in
[../DATA-LICENSES.md](../DATA-LICENSES.md); this file is the per-run
provenance record.)

## objects.json — OpenNGC

- Source: OpenNGC, https://github.com/mattiaverga/OpenNGC —
  `database_files/NGC.csv`, `database_files/addendum.csv`
- Upstream version: release v20260501 (files taken from the master branch
  matching that release)
- Retrieved: 2026-10-27
- Licence: CC BY-SA 4.0 (attribute OpenNGC / Mattia Verga; derivatives
  under the same licence)
- Notes: M102 is taken as NGC 5866 (OpenNGC's own M102 row is a duplicate
  placeholder for M101). Seven Messier objects have no magnitude in
  OpenNGC; integrated V magnitudes for those are supplemented from the
  Harris catalogue of globular clusters (2010 edition) and, for the open
  cluster M35, from the WEBDA open-cluster database value. Chinese names
  are included only where a widely used Chinese common name exists.

## eclipses.json — NASA GSFC eclipse catalogues

- Source: NASA GSFC Eclipse Web Site (Fred Espenak & Jean Meeus),
  https://eclipse.gsfc.nasa.gov — decade catalogues SEdecade2021,
  SEdecade2031, LEdecade2021, LEdecade2031; century catalogues
  SEcat5/SE2001-2100.html and LEcat5/LE2001-2100.html for path width and
  penumbral magnitude.
- Retrieved: 2026-10-27
- Licence/terms: U.S. Government work; reproduction with attribution to
  Espenak & Meeus / NASA GSFC.
- Notes: peakUtc is the catalogue TD of greatest eclipse expressed as
  UTC (TD-UTC difference of about one minute is not applied; the field
  carries the catalogue value verbatim). For penumbral lunar eclipses,
  `magnitude` is the penumbral magnitude and `visibility` says so.

## showers.json — IMO Meteor Shower Calendar

- Source: International Meteor Organization, *Meteor Shower Calendar
  2026* (IMO INFO 3-25), https://www.imo.net/files/meteor-shower/cal2026.pdf
  — Table 5 (Working List of Visual Meteor Showers) for activity period,
  peak solar longitude, radiant, velocity, population index and ZHR;
  Table 6 for radiant drift (linear slope of the tabulated radiant
  positions bracketing each maximum).
- Working-list web page: https://www.imo.net/members/imo_showers/working_shower_list
  serves a JavaScript application shell without data rows, which is why
  the calendar PDF is the parsed source.
- Retrieved: 2026-10-27
- Terms: factual astronomical data, IMO attribution retained.
- Notes: activity start/end are converted from the calendar's activity
  dates to solar longitudes by anchoring on each shower's own peak date
  and peak solar longitude (0.9856 deg/day). Parent bodies are the
  commonly cited IMO/IAU MDC associations; unknown parents are null.
  Differences against older IMO values: the 2026 calendar lists the
  Quadrantids at ZHR 80 (older lists: up to 110) with peak longitude
  283.15, and the Orionids peak at solar longitude 208.0 (older lists:
  208.5); the current calendar values are used.

## lenses.json — manufacturer lens line-ups

- Source: built-in curated list compiled from the manufacturers' public
  lens line-ups (Sony E-mount, Canon RF, Nikon Z, Tamron and Sigma
  mirrorless lenses), astrophotography-relevant models first (fast wide
  primes, then common zooms).
- Compiled: 2026-10-27
- Terms: factual product specifications (model name, mount, focal range,
  maximum aperture); no manufacturer text or images are reproduced.

## lp-grid.bin — Falchi World Atlas

- Source: Falchi, F. et al. 2016, *The New World Atlas of Artificial
  Night Sky Brightness*, Science Advances 2(6),
  doi:10.1126/sciadv.1600377, via GFZ Data Services,
  DOI 10.5880/GFZ.1.4.2016.001.
- Licence: CC BY-NC 4.0 (non-commercial only). The current GFZ landing
  page states CC BY-NC 4.0; the README inside the 2016 archive states
  an older licence wording. The current landing-page terms are the
  ones recorded and followed here (see DATA-LICENSES.md).
- Acquisition record (2026-10-27): an earlier record in this file
  stated that no direct download link existed; that conclusion is
  superseded. The GFZ datapub service exposes a direct archive link,
  https://datapub.gfz.de/download/10.5880.GFZ.1.4.2016.001/World_Atlas_2015.zip
  (HTTP 200, `Accept-Ranges: bytes`, Content-Length 684266450,
  verified by HEAD request and by a full resumable download with
  `scripts/fetch_falchi.py` on 2026-10-27). No form was submitted.
  - ZIP: 684,266,450 bytes,
    SHA-256 `9c6a624427463c40717efbca7696e3c6bb0f332c26d1b039e7a91e0dbc831fbf`.
  - Members: `World_Atlas_2015.tif` (extracted), `World_Atlas_2015.tpk`
    and `README.txt` (not extracted).
  - TIFF: 3,012,927,014 bytes,
    SHA-256 `9725566ed4fcf1a8fa311654143bb024c4fe2a792b85356be99adce07e1e83c1`.
- TIFF first-load validation (measured 2026-10-27 with tifffile, not
  assumed from documentation): single page, 17406 x 43200 float32,
  tiled 128x128, uncompressed. Geographic CRS WGS 84 (EPSG:4326,
  GeoKey 2048), RasterPixelIsArea, pixel scale 0.00833333 deg
  (30 arcsec), tiepoint origin (-180.0, 85.0541668645001), so the
  latitude coverage is -59.9958..85.0542 and longitude -180..180.
  The GDAL_NODATA tag declares -3.4028234663852886e+38, but a full
  scan found no pixel carrying that sentinel, no NaN and no negative
  values: all 751,939,200 pixels are finite, min 0.0, max 876.12
  mcd/m^2; ocean and unlit areas are encoded as 0.0, not as NoData.
  Values are artificial brightness in mcd/m^2 only — no natural-sky
  constant is added anywhere in the pipeline (the natural component
  is added downstream by the domain models).
- Shipped grid: full-atlas grid produced by `resample_falchi.py` —
  each 5-arcmin output cell (lat -60..85, lon -180..180, 1740 x 4320)
  takes the median of its 10x10 source pixels, quantised at
  0.05 mcd/m^2. The source raster extends about 6.5 source pixels
  north of the grid's top edge and ends about half a source pixel
  short of the grid's bottom edge; the affected bottom row is
  computed from the available source rows. Header JSON of
  lp-grid.bin: `gridKind` is `full-grid`, `source` and `dataVersion`
  carry the GFZ DOI above. Spot checks against the source raster
  (2026-10-27): Beijing 5.50, Shanghai 7.00, New York 8.95,
  London 9.40 mcd/m^2 (grid value vs raster cell median within the
  0.05 quantisation step); dark-sky sites (Death Valley, NamibRand,
  Mauna Kea, San Pedro de Atacama), open ocean and rural points
  outside the former reference-point set all read 0.0-0.05 mcd/m^2,
  consistent with their raster medians. All 7,516,800 cells carry a
  value (ocean cells carry the atlas's own 0.0); no-data remains in
  the format for points outside the grid extent.
