package app.astroexposure.domain.sky

import app.astroexposure.domain.camera.Camera

/** Where the artificial-light baseline came from (design §2.6). */
enum class BaselineSource { ATLAS, MANUAL_BORTLE, NONE }

/**
 * The composed "total sky" every exposure model consumes (design §5,
 * general rule 2): natural floor + artificial baseline + moonlight,
 * composed in the linear luminance domain, then expressed as mpsas.
 */
data class TotalSky(
    /** Artificial baseline in mcd/m²; null when no baseline is available. */
    val baselineArtificialMcdPerM2: Double?,
    val moonlightMcdPerM2: Double,
    val totalMcdPerM2: Double,
    val totalMpsas: Double,
    /** Bortle estimate from the total; null when there is no baseline (source NONE). */
    val bortleEstimate: Int?,
    val baselineSource: BaselineSource,
    /**
     * True when moonlight has raised the total to at least 4x the
     * baseline-only total (design §5, general rule 2 degradation trigger).
     */
    val moonDegraded: Boolean,
) {
    companion object {
        /**
         * Compose a [TotalSky]. A missing baseline counts as 0 artificial
         * light in the arithmetic but is recorded as [BaselineSource.NONE]
         * so callers can apply the design §2.8 degradation path.
         */
        fun compose(
            baselineArtificialMcdPerM2: Double?,
            baselineSource: BaselineSource,
            moonlightMcdPerM2: Double,
        ): TotalSky {
            val artificial = baselineArtificialMcdPerM2 ?: 0.0
            val baselineTotal = SkyBrightness.NATURAL_SKY_MCD_M2 + artificial
            val total = baselineTotal + moonlightMcdPerM2
            return TotalSky(
                baselineArtificialMcdPerM2 = baselineArtificialMcdPerM2,
                moonlightMcdPerM2 = moonlightMcdPerM2,
                totalMcdPerM2 = total,
                totalMpsas = SkyBrightness.totalToMpsas(total),
                bortleEstimate = if (baselineSource == BaselineSource.NONE) {
                    null
                } else {
                    SkyBrightness.bortleFromMpsas(SkyBrightness.totalToMpsas(total))
                },
                baselineSource = baselineSource,
                moonDegraded = total >= 4.0 * baselineTotal,
            )
        }

        /** Compose from a manually selected Bortle class (design §2.6 fallback path). */
        fun fromManualBortle(bortle: Int, moonlightMcdPerM2: Double): TotalSky = compose(
            baselineArtificialMcdPerM2 = SkyBrightness.artificialMcdForBortle(bortle),
            baselineSource = BaselineSource.MANUAL_BORTLE,
            moonlightMcdPerM2 = moonlightMcdPerM2,
        )

        /** No baseline available (design §2.8): moonlight and the natural floor only. */
        fun noBaseline(moonlightMcdPerM2: Double): TotalSky = compose(
            baselineArtificialMcdPerM2 = null,
            baselineSource = BaselineSource.NONE,
            moonlightMcdPerM2 = moonlightMcdPerM2,
        )
    }
}

/**
 * Sky electron rate R_sky in e-/px/s — the single implementation (D.4).
 *
 * Derivation (design §5.3 / §8.6, Glover's methodology frame): a surface
 * brightness of m mag/arcsec² corresponds to a photon flux
 *   F = 9.5e5 * 10^(-0.4*m)  ph/cm²/s/arcsec²   (V band, 0-mag footing)
 * at the top of the optics. One pixel subtends
 *   Omega = (206.265 * p / f)²  arcsec²   (p in µm, f in mm)
 * and the entrance pupil area is
 *   A = (pi/4) * (f / (10*N))²  cm²       (f/N in mm, converted to cm)
 * so R = F * Omega * A * QE, in which f cancels exactly:
 *   R = 9.5e5 * 206.265² * (pi/4) / 100 * 10^(-0.4*m) * (p²/N²) * QE
 *     = 3.174e8 * 10^(-0.4*m) * (p²/N²) * QE.
 * Acceptance footing (design §8.6): order-of-magnitude agreement with the
 * SharpCap sky calculator; the stated uncertainty is a factor of about 2.
 */
fun skyElectronRate(
    totalMpsas: Double,
    aperture: Double,
    camera: Camera,
    qe: Double = 0.5,
): Double {
    val p = camera.pixelPitch.value
    return 3.174e8 * Math.pow(10.0, -0.4 * totalMpsas) * (p * p / (aperture * aperture)) * qe
}
