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
  Night Sky Brightness*, Science Advances 2(6), via GFZ Data Services,
  DOI 10.5880/GFZ.1.4.2016.001.
- Licence: CC BY-NC 4.0 (non-commercial only).
- Acquisition record (2026-10-27): the DOI landing page is reachable but
  exposes no direct GeoTIFF link in its page body; GFZ distribution for
  this DOI otherwise goes through a request form / download dispatch
  whose endpoint redirects to a host that is not reliably reachable from
  the build environment (repeated attempts timed out or returned no
  file), and a web search for public mirrors (Zenodo / GitHub) found no
  copy of the atlas GeoTIFF itself. No form was submitted. The full
  GeoTIFF was therefore NOT obtained.
- Shipped grid: sparse sample grid produced by `resample_falchi.py` —
  Falchi atlas values for 99 built-in reference points (major cities and
  well-known dark-sky sites) sampled through the QueryRaster interface
  of lightpollutionmap.info (its wa_2015 layer serves the Falchi atlas
  values, ARTIFICIAL_BRIGHTNESS in mcd/m^2), written into the standard
  5-arcmin global grid; only the 99 cells containing a reference point
  carry data, all other cells are no-data. The header JSON of
  lp-grid.bin states this in its `source` field. The full-atlas median
  resample path is implemented in the same script (with a synthetic
  GeoTIFF self-test) and takes over automatically once a GeoTIFF is
  present in the pipeline cache.
