# Data & Third-Party Licences

The **code** in this repository is MIT-licensed (see [LICENSE](LICENSE)). The
**data and libraries** below are *not* covered by that MIT licence — each keeps
its own terms. This file lists every third-party source used by AstroExposure,
what we take from it, and the obligations that follow.

If you reuse anything from this repository, check the entry for the specific
dataset you are taking. When in doubt, go to the upstream source and take the
data from there under its original terms.

---

## Astronomy Engine — MIT

- **What:** Ephemeris library (Sun/Moon positions, twilight, moon phases and
  illumination, rise/set times, eclipse search, coordinate transforms, target
  altitude, constellation lookup). Used as code (official Kotlin version).
- **Source:** https://github.com/cosinekitty/astronomy (Don Cross)
- **Licence:** MIT License.
- **Obligations:** Retain the copyright and licence notices in copies of the
  library code. No share-alike, no non-commercial restriction.

## OpenNGC — CC BY-SA 4.0

- **What:** Deep-sky object catalogue (NGC/IC objects: coordinates, type,
  angular size, magnitudes, galaxy surface brightness, Messier cross-IDs).
  Data only, trimmed at build time.
- **Source:** https://github.com/mattiaverga/OpenNGC (Mattia Verga)
- **Licence:** Creative Commons Attribution-ShareAlike 4.0 International
  (CC BY-SA 4.0).
- **Obligations:** Attribute OpenNGC / Mattia Verga. **If you distribute this
  data or a derivative dataset, you must license the derivative under
  CC BY-SA 4.0 as well.** Upstream acknowledgements (NED, HyperLEDA, SIMBAD,
  HEASARC) are preserved in the app's data-sources screen.

## HYG Database — CC BY-SA 4.0

- **What:** Star catalogue (Hipparcos + Yale Bright Star + Gliese merge);
  this project uses a bright-star subset only.
- **Source:** https://codeberg.org/astronexus/hyg (Astronexus / David Nash;
  formerly on GitHub at astronexus/HYG-Database).
- **Licence:** Creative Commons Attribution-ShareAlike 4.0 International
  (CC BY-SA 4.0).
- **Obligations:** Attribute Astronexus / David Nash. **Derived subsets, if
  distributed, must be shared under CC BY-SA 4.0.**

## Falchi World Atlas of Artificial Night Sky Brightness — CC BY-NC 4.0

> ⚠️ **NON-COMMERCIAL ONLY.** The light-pollution data in / derived in this
> repository comes from a CC BY-NC 4.0 dataset. It may be used, shared and
> adapted **for non-commercial purposes only**. Any commercial use requires
> replacing this dataset with a differently licensed source — attribution
> alone does *not* lift the restriction.

- **What:** Modelled zenith artificial sky brightness (based on VIIRS DNB
  satellite data). The product bundled in this repository is a resampled
  adaptation of the full atlas GeoTIFF: `scripts/resample_falchi.py`
  takes the median of each 10x10 block of 30-arcsec source pixels into
  a 5-arcmin global grid (lat -60..85, lon -180..180), quantised at
  0.05 mcd/m^2. Values are artificial brightness only; no natural-sky
  component is added. Every grid cell carries a value (ocean cells
  carry the atlas's own 0.0); points outside the grid extent return
  "no data" and the app falls back to a manual Bortle entry.
- **Source:** Falchi, F. et al., *The New World Atlas of Artificial Night Sky
  Brightness*, Science Advances 2(6), 2016,
  doi:10.1126/sciadv.1600377. Data via GFZ Data Services,
  DOI 10.5880/GFZ.1.4.2016.001, downloaded as `World_Atlas_2015.zip`
  from https://datapub.gfz.de/download/10.5880.GFZ.1.4.2016.001/
  (acquisition record and SHA-256 digests in scripts/data-sources.md).
- **Licence:** Creative Commons Attribution-NonCommercial 4.0 International
  (CC BY-NC 4.0),
  https://creativecommons.org/licenses/by-nc/4.0/. Note on terms: the
  current GFZ landing page for the DOI states CC BY-NC 4.0, while the
  README bundled in the 2016 archive states an older licence wording.
  This project follows the current landing-page terms (CC BY-NC 4.0),
  the more specific and more recent statement by the distributing
  archive.
- **Obligations:** Attribute Falchi et al. (2016) with both DOIs above.
  **No commercial use** of the data or its derivatives, including the
  resampled grid in this repository.

## NASA GSFC Eclipse Catalogues — attribution terms

- **What:** Solar and lunar eclipse catalogues and Besselian elements, from
  which the app's bundled static eclipse tables are built (build-time use;
  the app itself is fully offline).
- **Source:** NASA GSFC Eclipse Web Site, https://eclipse.gsfc.nasa.gov —
  Eclipse Predictions by Fred Espenak and Jean Meeus (NASA's GSFC).
- **Terms:** The material may be reproduced with attribution to Espenak &
  Meeus / NASA GSFC, per the site's stated terms. (U.S. Government work.)
- **Obligations:** Keep the attribution line above wherever the derived
  tables are used or documented.

## IMO Meteor Shower Calendar / Working List — attribution

- **What:** Meteor shower activity periods, peak solar longitudes, ZHR,
  radiant positions and drift, parent bodies — used to build the app's
  static shower table.
- **Source:** International Meteor Organization (IMO), *Shower Calendar* and
  *Working List of Visual Meteor Showers*, https://www.imo.net ; shower data
  cross-checked against the IAU Meteor Data Center (ta3.sk).
- **Terms:** Factual astronomical data; IMO/IAU MDC attribution is required
  as a matter of scholarly practice and is retained in the app's
  data-sources screen.
- **Obligations:** Attribute IMO (and IAU MDC where applicable).

## PhotonsToPhotos camera measurements — attribution

- **What:** Measured read-noise / photographic dynamic-range figures used to
  seed the camera library (per-ISO values are read from the published charts
  during development and re-verified before release).
- **Source:** https://www.photonstophotos.net (Bill Claff).
- **Terms:** Public measurement site; figures are cited with attribution.
  No licence is claimed by this project over those measurements.
- **Obligations:** Credit PhotonsToPhotos / Bill Claff wherever the seeded
  values are documented.

---

*Summary: code = MIT. OpenNGC & HYG data = CC BY-SA 4.0 (attribute,
share-alike on derivatives). Light-pollution data = CC BY-NC 4.0
(attribute, **non-commercial only**). Eclipse and meteor tables = factual
data with NASA GSFC / IMO attribution retained.*
