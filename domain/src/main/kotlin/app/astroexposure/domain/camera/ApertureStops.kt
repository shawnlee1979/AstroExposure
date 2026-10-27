package app.astroexposure.domain.camera

/**
 * The standard aperture scale — the single source of aperture values for
 * the whole codebase (D.4 spirit): the UI aperture picker reads its
 * options from here, and the nominal values it offers are exactly the
 * numbers the exposure models receive. Models themselves do not snap or
 * otherwise change aperture values.
 *
 * The scale is the conventional camera 1/3-stop nominal f-number series.
 * These are nominal dial values (industry convention), not exact
 * 2^(n/6) irrationals.
 */
object ApertureStops {

    /** Conventional 1/3-stop nominal f-numbers, f/1.0 through f/22. */
    val STANDARD_SCALE: List<Double> = listOf(
        1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8, 3.2, 3.5, 4.0, 4.5,
        5.0, 5.6, 6.3, 7.1, 8.0, 9.0, 10.0, 11.0, 13.0, 14.0, 16.0, 18.0, 20.0, 22.0,
    )

    /**
     * The selectable aperture sequence for a lens whose maximum aperture is
     * [maxAperture], running up to f/22:
     * - when [maxAperture] is on the standard scale, the sequence starts at
     *   that entry;
     * - when it is off-scale (e.g. f/1.9, f/2.9), the sequence starts with
     *   [maxAperture] itself, followed by every scale value above it.
     * The result is deduplicated and strictly ascending.
     */
    fun scaleFrom(maxAperture: Double): List<Double> =
        // One construction covers both cases: an on-scale maximum is not in
        // the strictly-above tail, so prepending it reproduces the scale
        // from that entry; an off-scale maximum is prepended as itself.
        (listOf(maxAperture) + STANDARD_SCALE.filter { it > maxAperture }).distinct()
}
