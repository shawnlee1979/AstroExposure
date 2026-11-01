package app.astroexposure.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Port for the viewfinder capture's live camera surface
 * (ui-design §7.9, 0.17.0; DEVELOPMENT-RULES.md B). The one
 * implementation binds the platform camera stack behind this
 * interface; the UI and the domain see only this surface and the
 * port's own failure vocabulary — no camera-stack type crosses it.
 *
 * The surface is preview-only by construction: the port offers no
 * capture, recording or frame-analysis operation at all, so "the
 * viewfinder never takes or stores a picture" is a property of the
 * interface, not a promise in a comment. The aiming overlay (the
 * reference line, the centre marker, the readouts) is plain Compose
 * on the UI side, drawn over this surface.
 *
 * Availability and failure are explicit (E.5): a device without a
 * rear camera and a camera that fails to open both arrive through
 * [Failure], and the caller falls back to the compass capture —
 * never to a silently blank preview.
 */
interface CameraViewfinderPort {

    /** Why the viewfinder cannot run, in the port's own terms. */
    enum class Failure {
        /** No rear camera on this device. */
        UNAVAILABLE,

        /** A camera exists but opening or binding it failed. */
        OPEN_FAILED,
    }

    /**
     * The live preview surface. While [redLight] is on, the surface
     * presents the red-light form (a red translucent layer over the
     * preview plus the camera's exposure compensation driven to its
     * minimum — §7.9's red-light ruling); the overlay is a dimming
     * tint, not a theme-grade dark red, and the spec says so.
     * [onFailure] fires at most once per entry into composition,
     * from a side effect, never during composition itself.
     */
    @Composable
    fun ViewfinderView(
        redLight: Boolean,
        onFailure: (Failure) -> Unit,
        modifier: Modifier = Modifier,
    )
}
