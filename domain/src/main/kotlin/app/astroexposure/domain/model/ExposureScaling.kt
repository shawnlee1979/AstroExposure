package app.astroexposure.domain.model

import kotlin.math.pow

/**
 * The one EV-shift formula every table-anchored model shares
 * (design §5, exposure reciprocity): an exposure recorded at
 * [anchorAperture] and ISO 100 scales to [aperture] / [iso] by the
 * squared aperture ratio and the inverse ISO ratio. Each model keeps
 * its own table, anchors and snapping rules; only the shift is shared.
 */
fun scaleSeconds(baseSeconds: Double, aperture: Double, anchorAperture: Double, iso: Int): Double =
    baseSeconds * (aperture / anchorAperture).pow(2) * (100.0 / iso)
