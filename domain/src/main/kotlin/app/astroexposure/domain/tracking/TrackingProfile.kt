package app.astroexposure.domain.tracking

import app.astroexposure.domain.units.ArcsecPerMinute
import java.time.LocalDate

/** Whether a profile tracks at all (design §2.5). */
enum class TrackingKind { UNTRACKED, TRACKED }

/** Drift shape recorded from a measurement session (design §2.5). */
enum class DriftMorphology { LINEAR, PERIODIC }

/**
 * A tracking profile (design §2.5): drift rate is a property of the mount
 * plus its calibration quality, NOT of the camera/lens combination, so a
 * profile stores arcseconds-per-minute only; pixel tolerance times are
 * derived per combination by the drift model (§5.2.2).
 *
 * Drift fields stay null until a baseline measurement supplies them —
 * no estimated placeholder values are ever stored (design §2.5/§2.8).
 */
data class TrackingProfile(
    val id: String,
    val name: String,
    val kind: TrackingKind,
    val typicalDrift: ArcsecPerMinute? = null,
    val worstDrift: ArcsecPerMinute? = null,
    val morphology: DriftMorphology? = null,
    /** Measurement conditions note (body / lens / target / session), design §2.5. */
    val conditionsNote: String? = null,
    val measuredOn: LocalDate? = null,
)

/** Built-in profiles (design §2.5 table). Drift values are intentionally empty. */
object BuiltInTrackingProfiles {
    val UNTRACKED = TrackingProfile(
        id = "untracked",
        name = "Untracked (fixed tripod)",
        kind = TrackingKind.UNTRACKED,
    )

    /** Polaris single-star calibration; drift pending baseline measurement. */
    val POLARIS_SINGLE_STAR = TrackingProfile(
        id = "polaris-single-star",
        name = "Polaris single-star calibration",
        kind = TrackingKind.TRACKED,
        conditionsNote = "Pending baseline measurement (A1 II + 50mm, near/far target pair)",
    )

    /** Polaris two-star calibration; drift pending its own measurement. */
    val POLARIS_TWO_STAR = TrackingProfile(
        id = "polaris-two-star",
        name = "Polaris two-star calibration",
        kind = TrackingKind.TRACKED,
        conditionsNote = "Pending two-star baseline measurement",
    )

    val ALL: List<TrackingProfile> = listOf(UNTRACKED, POLARIS_SINGLE_STAR, POLARIS_TWO_STAR)
}
