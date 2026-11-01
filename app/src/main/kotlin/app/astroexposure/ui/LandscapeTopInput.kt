package app.astroexposure.ui

import app.astroexposure.domain.orientation.DeviceOrientationProvider

/**
 * The pure logic of the foreground-top field's write entries
 * (ui-design §7.9, 0.17.0), lifted out of the composables so JVM
 * tests can pin it: the typed-input filter and its four-way submit,
 * the viewfinder hold's trailing-window mean, and the camera
 * permission flow's step mapping. No Compose, no Android types.
 */

/** The foreground-top domain, shared by every write entry (§7.9). */
internal const val TOP_MIN_DEG = -20
internal const val TOP_MAX_DEG = 80

/** Longest accepted typed buffer: a sign plus up to three digits. */
private const val TOP_INPUT_MAX_CHARS = 4

/**
 * Filter one edit of the typed buffer: the full-width minus is
 * normalised to ASCII, then only an optional leading minus and
 * digits survive, capped at [TOP_INPUT_MAX_CHARS] characters. A
 * decimal point never survives — the field is integer degrees end
 * to end, and accepting one would force a silent rounding at
 * submit, which typed input never does (§7.9).
 */
internal fun filterTopInput(raw: String): String {
    val normalised = raw.replace('−', '-')
    val out = StringBuilder()
    for ((index, ch) in normalised.withIndex()) {
        when {
            ch == '-' && index == 0 -> out.append(ch)
            ch.isDigit() -> out.append(ch)
        }
        if (out.length >= TOP_INPUT_MAX_CHARS) break
    }
    return out.toString()
}

/** The four outcomes of submitting the typed buffer (§7.9). */
internal sealed interface TopSubmit {
    /** Empty or sign-only (or unparseable): cancel — nothing changes, no error. */
    data object Cancel : TopSubmit

    /** A value inside the domain: write it through the shared write path. */
    data class Write(val valueDeg: Int) : TopSubmit

    /**
     * A value outside the domain: reject explicitly — no write, no
     * clamping; the field keeps the offending text and the caller
     * shows the range error. Carries the rejected value for the
     * error line.
     */
    data class Reject(val valueDeg: Int) : TopSubmit
}

/**
 * Decide a typed submit against the field's domain. The writer
 * asymmetry is the point (§7.9): the bounded sources (steppers,
 * captures) clamp in the shared write path, typed input never
 * clamps silently — out-of-domain text is reported, not trimmed
 * into a different number.
 */
internal fun submitTopInput(buffer: String): TopSubmit {
    val value = buffer.toIntOrNull() ?: return TopSubmit.Cancel
    return if (value in TOP_MIN_DEG..TOP_MAX_DEG) {
        TopSubmit.Write(value)
    } else {
        TopSubmit.Reject(value)
    }
}

/** The viewfinder hold's averaging window (§7.9: the last 600 ms). */
internal const val AXIS_HOLD_WINDOW_MILLIS = 600L

/**
 * The viewfinder hold value: the mean of the camera-axis elevations
 * sampled inside the trailing [windowMillis] before the latest
 * sample (the samples ride the orientation port's stream, stamped
 * on the adapter's elapsed clock). Null when there are no samples —
 * the hold is then disabled, the same discipline as the compass
 * capture's no-pitch state. Windows compare on sample stamps only;
 * this function reads no clock.
 */
internal fun meanCameraAxisDeg(
    samples: List<DeviceOrientationProvider.OrientationSample>,
    windowMillis: Long = AXIS_HOLD_WINDOW_MILLIS,
): Double? {
    val latest = samples.maxByOrNull { it.elapsedMillis } ?: return null
    val window = samples.filter {
        it.elapsedMillis >= latest.elapsedMillis - windowMillis
    }
    if (window.isEmpty()) return null
    return window.sumOf { it.cameraAxisElevationDeg } / window.size
}

/** Where the camera-permission flow stands (§7.9, 0.17.0). */
internal enum class CameraPermissionStep {
    /** Permission already held: the viewfinder opens directly. */
    GRANTED,

    /** Never asked: the in-line explanation comes first. */
    EXPLAIN_FIRST,

    /** Asked and refused, but the system will show the dialog again. */
    DENIED_ASKABLE,

    /** Refused for good: only the system settings can reopen it. */
    DENIED_PERMANENT,
}

/**
 * Map the permission facts to the flow's step. The platform's
 * permanent-denial signal is negative — the rationale flag coming
 * back false AFTER a request has been made — so [askedBefore] is
 * what separates "never asked" from "refused for good"; both facts
 * are inputs because neither is derivable from the other.
 */
internal fun cameraPermissionStep(
    granted: Boolean,
    askedBefore: Boolean,
    shouldShowRationale: Boolean,
): CameraPermissionStep = when {
    granted -> CameraPermissionStep.GRANTED
    !askedBefore -> CameraPermissionStep.EXPLAIN_FIRST
    shouldShowRationale -> CameraPermissionStep.DENIED_ASKABLE
    else -> CameraPermissionStep.DENIED_PERMANENT
}
