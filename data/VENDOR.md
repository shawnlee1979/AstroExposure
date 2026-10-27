# Vendored sources in :data

Per DEVELOPMENT-RULES.md section B.4/B.5: vendored files are pinned to an exact
upstream version, are never edited casually, and any patch must be registered
here so it can be replayed on the next upstream version.

## Astronomy Engine (Kotlin)

| Item | Value |
|---|---|
| Upstream repo | https://github.com/cosinekitty/astronomy (Don Cross) |
| Pinned commit | `865d3da7d8112bbc7911238052c6af4aaf877181` (2025-01-27) |
| File | `src/main/kotlin/io/github/cosinekitty/astronomy/astronomy.kt` |
| File SHA-256 | `d4213f7e2afc4553dfdf1d78a9c453a5c420cb77b280a2a092bb607618acd97b` |
| License | MIT (license header preserved at the top of the file) |
| Retrieved | 2026-10-26, raw file over HTTPS from the pinned commit |

### Patch list

None. The file is byte-identical to upstream at the pinned commit.
All adaptation (time/angle/unit conversion) lives in
`app.astroexposure.data.ephemeris.AstronomyEngineEphemeris`, never in the
vendored file.

### Upgrade procedure

1. Download the new upstream `astronomy.kt`, replace the file, update this page
   (commit, SHA-256, date).
2. Run the `EphemerisProvider` contract tests (`:data:test`). They must pass
   unchanged; goldens may only change with a cited public source
   (DEVELOPMENT-RULES.md section C.2).
3. Business layers must not need changes; if they do, the port interface is
   changed in a separate commit first (section B.3).
