package app.astroexposure.domain.orientation

import kotlin.math.abs

/**
 * Pure mathematics of the target finder (target detail dialog redesign,
 * ui-design §7.5 as revised for 0.8.0): signed angular deltas, the
 * two-axis alignment state machine with hysteresis, the holding-posture
 * judgement and the readout assembly the UI renders. Everything is a
 * pure function of explicit inputs (D.1/D.2) — no clock, no sensor, no
 * state beyond the [AlignmentState] the caller carries between samples.
 * All angles are in degrees (D.3), and each formula lives here exactly
 * once (D.4): the UI only maps the returned enums to wording.
 */

/**
 * Finder thresholds, each with its source category noted (E.4 — no
 * unexplained constants; the derivations are those of ui-design
 * §7.5, cited per constant).
 */
object FinderThresholds {

    /**
     * Alignment entry line in degrees. Derivation from accuracy: the
     * fused, smoothed reading has a stationary noise of roughly
     * ±1–1.5°, and the entry line is about twice that, so noise alone
     * cannot fake an alignment (ui-design §7.5).
     */
    const val ALIGN_ENTER_DEG = 3.0

    /**
     * Hysteresis exit line in degrees: an aligned axis only drops out
     * beyond this, so it must be greater than [ALIGN_ENTER_DEG]
     * (ui-design §7.5).
     */
    const val ALIGN_EXIT_DEG = 5.0

    /**
     * Flat-posture line in degrees. Empirical rule: a screen-normal tilt
     * below this is read as the phone lying flat (ui-design §7.5).
     */
    const val FLAT_TILT_MAX_DEG = 20.0

    /**
     * Cursor staleness line in minutes: beyond this difference between
     * the cursor instant and now, the finder notes that its pointing is
     * computed for the cursor instant, not the current sky — inside it
     * the azimuth drift (< 1.5°) stays within the sensor noise
     * (ui-design §7.5).
     */
    const val STALE_CURSOR_MINUTES = 5L

    /**
     * Target refresh period in seconds: even at its fastest (near the
     * zenith) a target's position drifts by at most about 0.3° in this
     * span, far below the alignment thresholds, so re-evaluating the
     * target this often is enough (ui-design §7.5).
     */
    const val TARGET_REFRESH_SECONDS = 30L
}

/**
 * The shortest signed angular difference from [fromDeg] to [toDeg], in
 * degrees, in (−180, 180]: positive means the target lies clockwise from
 * the current direction, i.e. the user must turn RIGHT. Both inputs are
 * arbitrary reals and are normalised by the difference itself, so e.g.
 * 720° behaves as 0°. An exact half-turn is always +180 (never −180),
 * which keeps the range half-open as documented. This is the single
 * implementation of the azimuth difference for the whole app (D.4).
 */
fun signedAzimuthDeltaDeg(fromDeg: Double, toDeg: Double): Double {
    var delta = (toDeg - fromDeg) % 360.0
    if (delta <= -180.0) delta += 360.0
    if (delta > 180.0) delta -= 360.0
    // Normalise a −0.0 remainder to plain 0.0 so equality displays and
    // sign tests see one zero.
    return if (delta == 0.0) 0.0 else delta
}

/**
 * The pitch difference in degrees, target minus current: positive means
 * the device top edge must be RAISED. Both inputs are first clamped to
 * [−90, 90], the physical range of an elevation angle, so out-of-range
 * inputs are read as the nearest horizon/zenith extreme instead of
 * producing a difference beyond ±180.
 */
fun pitchDeltaDeg(currentDeg: Double, targetDeg: Double): Double =
    targetDeg.coerceIn(-90.0, 90.0) - currentDeg.coerceIn(-90.0, 90.0)

/** How the phone is being held, as far as the finder cares. */
enum class HoldingPosture { AIMING, FLAT }

/**
 * The holding-posture judgement (ui-design §7.5): the input is the angle
 * between the screen normal and the vertical, interpreted clamped to
 * [0, 90] — values outside that range are read as the nearest end of it.
 * A clamped tilt strictly below [FinderThresholds.FLAT_TILT_MAX_DEG]
 * means the phone is lying (near-)flat, screen facing up or down, where
 * a pitch reading is meaningless; at or above the line the phone counts
 * as held up in the aiming posture.
 */
fun holdingPosture(screenNormalTiltDeg: Double): HoldingPosture =
    if (screenNormalTiltDeg.coerceIn(0.0, 90.0) < FinderThresholds.FLAT_TILT_MAX_DEG) {
        HoldingPosture.FLAT
    } else {
        HoldingPosture.AIMING
    }

/** The combined two-axis alignment, derived from an [AlignmentState]. */
enum class FinderAlignment { UNALIGNED, AZIMUTH_ONLY, ALTITUDE_ONLY, ALIGNED }

/**
 * The per-axis alignment state the caller carries between samples.
 * [altitudeAligned] is nullable on purpose (E.5 — an explicit state,
 * not a magic value): null means the altitude axis is not being
 * evaluated at all (the target has not risen, or the phone is flat), as
 * opposed to false, which means it is evaluated and not aligned.
 */
data class AlignmentState(
    val azimuthAligned: Boolean = false,
    val altitudeAligned: Boolean? = null,
) {
    /**
     * The combined alignment derived from the two axes: both axes
     * aligned is [FinderAlignment.ALIGNED]; with [altitudeAligned]
     * null the result is at most [FinderAlignment.AZIMUTH_ONLY],
     * because an unevaluated axis can never complete an alignment.
     */
    val combined: FinderAlignment
        get() = when {
            azimuthAligned && altitudeAligned == true -> FinderAlignment.ALIGNED
            azimuthAligned -> FinderAlignment.AZIMUTH_ONLY
            altitudeAligned == true -> FinderAlignment.ALTITUDE_ONLY
            else -> FinderAlignment.UNALIGNED
        }
}

/**
 * One hysteresis step of one axis: an unaligned axis enters alignment
 * when |delta| ≤ [FinderThresholds.ALIGN_ENTER_DEG]; an aligned axis
 * only leaves when |delta| > [FinderThresholds.ALIGN_EXIT_DEG]; between
 * the two lines the previous state is kept, so the state cannot flicker
 * on the boundary.
 */
private fun advanceAxis(wasAligned: Boolean, deltaDeg: Double): Boolean =
    if (wasAligned) {
        abs(deltaDeg) <= FinderThresholds.ALIGN_EXIT_DEG
    } else {
        abs(deltaDeg) <= FinderThresholds.ALIGN_ENTER_DEG
    }

/**
 * Advance the alignment state machine by one sample's deltas. The two
 * axes run independent hysteresis ([advanceAxis]): one axis entering or
 * leaving never changes the other's state. [altitudeDeltaDeg] = null
 * means the altitude axis is not evaluated for this sample (target not
 * risen, or flat posture) and the result's altitude axis is null,
 * whatever it was before; a previously null altitude axis that is
 * evaluated again starts from unaligned.
 */
fun advanceAlignment(
    previous: AlignmentState,
    azimuthDeltaDeg: Double,
    altitudeDeltaDeg: Double?,
): AlignmentState = AlignmentState(
    azimuthAligned = advanceAxis(previous.azimuthAligned, azimuthDeltaDeg),
    altitudeAligned = if (altitudeDeltaDeg == null) {
        null
    } else {
        advanceAxis(previous.altitudeAligned ?: false, altitudeDeltaDeg)
    },
)

/** What the azimuth axis of a [FinderReadout] tells the user to do. */
enum class AzimuthCue { TURN_LEFT, TURN_RIGHT, ALIGNED, NO_READING }

/** What the altitude axis of a [FinderReadout] tells the user to do. */
enum class AltitudeCue { RAISE, LOWER, ALIGNED, TARGET_BELOW_HORIZON, FLAT_POSTURE, NO_READING }

/**
 * One finder readout for the UI to render: direction cues, the numeric
 * deltas behind them (null exactly when there is no reading), the
 * combined alignment and the holding posture. The UI only maps the cues
 * to wording; every judgement has already been made here.
 */
data class FinderReadout(
    val azimuthCue: AzimuthCue,
    val azimuthDeltaDeg: Double?,
    val altitudeCue: AltitudeCue,
    val altitudeDeltaDeg: Double?,
    val alignment: FinderAlignment,
    val posture: HoldingPosture,
)

/**
 * Assemble one finder readout for a target at ([targetAltDeg],
 * [targetAzDeg]) from the latest [sample] and the caller's current
 * [alignment] state (advanced via [advanceAlignment]).
 *
 * Judgement order, fixed here so the UI never re-derives it:
 * - [sample] = null (no reading): the posture is reported as the
 *   [HoldingPosture.AIMING] placeholder, both cues are NO_READING, both
 *   deltas are null, and the alignment is the given state's combined
 *   value, unchanged.
 * - Otherwise both deltas are computed by this file's own delta
 *   functions ([signedAzimuthDeltaDeg] / [pitchDeltaDeg] — the single
 *   implementations, D.4) and are always reported, whatever the cues.
 * - Azimuth cue: the state says azimuth-aligned → ALIGNED; otherwise
 *   Δ > 0 → TURN_RIGHT, Δ < 0 → TURN_LEFT. (Δ = 0 in an unaligned state
 *   cannot occur on the normal path — [advanceAlignment] would already
 *   have entered alignment at |Δ| ≤ 3° — and is reported as ALIGNED
 *   rather than inventing a direction for a zero turn.)
 * - Altitude cue, in priority order: target below the horizon
 *   ([targetAltDeg] < 0) → TARGET_BELOW_HORIZON, ahead of everything
 *   else, because "not up yet" is a fact about the target, not about
 *   the phone; then flat posture → FLAT_POSTURE, because a flat phone
 *   has no meaningful pitch; then the state says altitude-aligned →
 *   ALIGNED; otherwise Δ > 0 → RAISE, Δ < 0 → LOWER, with Δ = 0
 *   reported as ALIGNED for the same reason as on the azimuth axis.
 */
fun finderReadout(
    targetAltDeg: Double,
    targetAzDeg: Double,
    sample: DeviceOrientationProvider.OrientationSample?,
    alignment: AlignmentState,
): FinderReadout {
    if (sample == null) {
        return FinderReadout(
            azimuthCue = AzimuthCue.NO_READING,
            azimuthDeltaDeg = null,
            altitudeCue = AltitudeCue.NO_READING,
            altitudeDeltaDeg = null,
            alignment = alignment.combined,
            posture = HoldingPosture.AIMING,
        )
    }
    val azimuthDelta = signedAzimuthDeltaDeg(sample.headingTrueDeg, targetAzDeg)
    val altitudeDelta = pitchDeltaDeg(sample.pitchDeg, targetAltDeg)
    val posture = holdingPosture(sample.screenNormalTiltDeg)
    val azimuthCue = when {
        alignment.azimuthAligned -> AzimuthCue.ALIGNED
        azimuthDelta > 0.0 -> AzimuthCue.TURN_RIGHT
        azimuthDelta < 0.0 -> AzimuthCue.TURN_LEFT
        else -> AzimuthCue.ALIGNED
    }
    val altitudeCue = when {
        targetAltDeg < 0.0 -> AltitudeCue.TARGET_BELOW_HORIZON
        posture == HoldingPosture.FLAT -> AltitudeCue.FLAT_POSTURE
        alignment.altitudeAligned == true -> AltitudeCue.ALIGNED
        altitudeDelta > 0.0 -> AltitudeCue.RAISE
        altitudeDelta < 0.0 -> AltitudeCue.LOWER
        else -> AltitudeCue.ALIGNED
    }
    return FinderReadout(
        azimuthCue = azimuthCue,
        azimuthDeltaDeg = azimuthDelta,
        altitudeCue = altitudeCue,
        altitudeDeltaDeg = altitudeDelta,
        alignment = alignment.combined,
        posture = posture,
    )
}
