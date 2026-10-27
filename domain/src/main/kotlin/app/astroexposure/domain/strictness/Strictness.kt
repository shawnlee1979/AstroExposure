package app.astroexposure.domain.strictness

/**
 * The global three-tier strictness system (design §5, general rule 1).
 * Every exposure model reads its coefficients from here; models never define
 * their own private defaults.
 */
enum class Strictness(
    /** NPF tolerance coefficient k (design §5.2.1). */
    val npfK: Int,
    /** Deep-sky "swamp" coefficient C (design §5.3). */
    val swampC: Int,
    /** Histogram background target for sky-background-capped models. */
    val backgroundTargetFraction: Double,
) {
    STRICT(npfK = 1, swampC = 10, backgroundTargetFraction = 0.20),
    STANDARD(npfK = 2, swampC = 10, backgroundTargetFraction = 0.25),
    RELAXED(npfK = 3, swampC = 5, backgroundTargetFraction = 0.33),
}
