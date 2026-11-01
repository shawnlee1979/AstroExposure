package app.astroexposure.orientation

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Pins [topEdgeWorld] against hand-built rotation matrices whose
 * geometry is known by construction (row-major, device → world;
 * columns are the device axes in world coordinates). Heading and
 * pitch are derived with the same formulas the adapter applies
 * (heading = atan2(east, north), pitch = asin(up)), so a wrong
 * row/column choice fails here off-device instead of on the sky.
 */
class RotationMatrixAxesTest {

    private fun headingDeg(matrix: FloatArray): Double {
        val top = topEdgeWorld(matrix)
        return (Math.toDegrees(atan2(top.east, top.north)) + 360.0) % 360.0
    }

    private fun pitchDeg(matrix: FloatArray): Double {
        val top = topEdgeWorld(matrix)
        return Math.toDegrees(asin(top.up.coerceIn(-1.0, 1.0)))
    }

    @Test
    fun topEdgeNorthLevel() {
        // Identity: device axes coincide with the world axes, so the
        // top edge points north and level.
        val identity = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
        )
        val top = topEdgeWorld(identity)
        assertEquals(0.0, top.east, 1e-9)
        assertEquals(1.0, top.north, 1e-9)
        assertEquals(0.0, top.up, 1e-9)
        assertEquals(0.0, headingDeg(identity), 1e-9)
        assertEquals(0.0, pitchDeg(identity), 1e-9)
    }

    @Test
    fun topEdgeEastLevel() {
        // Device turned so the top edge points east, screen up:
        // device X (right edge) points south, +Y points east, +Z up.
        // Columns: X=(0,-1,0), Y=(1,0,0), Z=(0,0,1).
        val east = floatArrayOf(
            0f, 1f, 0f,
            -1f, 0f, 0f,
            0f, 0f, 1f,
        )
        val top = topEdgeWorld(east)
        assertEquals(1.0, top.east, 1e-9)
        assertEquals(0.0, top.north, 1e-9)
        assertEquals(0.0, top.up, 1e-9)
        assertEquals(90.0, headingDeg(east), 1e-9)
        assertEquals(0.0, pitchDeg(east), 1e-9)
    }

    @Test
    fun topEdgeNorthPitched45() {
        // Device pitched back about the east axis by 45 degrees: the
        // top edge points north and 45 degrees up, the screen normal
        // tips 45 degrees toward the south-up diagonal.
        // Columns: X=(1,0,0), Y=(0,c,s), Z=(0,-s,c) with c=s=cos(45°).
        val c = 0.7071067811865476f
        val s = 0.7071067811865476f
        val pitched = floatArrayOf(
            1f, 0f, 0f,
            0f, c, -s,
            0f, s, c,
        )
        val top = topEdgeWorld(pitched)
        assertEquals(0.0, top.east, 1e-6)
        assertEquals(c.toDouble(), top.north, 1e-6)
        assertEquals(s.toDouble(), top.up, 1e-6)
        assertEquals(0.0, headingDeg(pitched), 1e-6)
        assertEquals(45.0, pitchDeg(pitched), 1e-4)
    }

    // --- The camera axis elevation γ (0.17.0) --------------------------------
    //
    // γ = asin(−R[8]) by construction of each pose below (columns
    // are the device axes in world coordinates, as above). The
    // poses are chosen so that a γ derived from the pitch — by
    // identity or by the 90° − pitch shortcut — fails at least one
    // of them: the shortcut survives only the zero-roll portrait
    // family (poses two and three), never the flat or rolled ones.

    @Test
    fun cameraAxisFlatScreenUpPointsDown() {
        // Identity (flat on a table, screen up): the rear camera
        // looks straight down, γ = −90°, while the top edge lies
        // level (pitch 0°).
        val identity = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
        )
        assertEquals(-90.0, cameraAxisElevationDeg(identity), 1e-6)
        assertEquals(0.0, pitchDeg(identity), 1e-9)
    }

    @Test
    fun cameraAxisPortraitPhotoPoseLevel() {
        // The natural aiming hold: portrait, screen toward the
        // holder, camera level facing north. Columns: X=(1,0,0),
        // Y=(0,0,1) (top edge straight up), Z=(0,−1,0) (screen
        // normal toward the holder, i.e. south). γ = 0° — the value
        // a viewfinder hold must write for a level foreground top —
        // while the pitch reads 90°: the posture gap the viewfinder
        // capture exists to close.
        val pose = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f,
        )
        assertEquals(0.0, cameraAxisElevationDeg(pose), 1e-6)
        assertEquals(90.0, pitchDeg(pose), 1e-6)
    }

    @Test
    fun cameraAxisPortraitPhotoPoseUpThirty() {
        // The same hold pitched up 30°: the camera axis rides 30°
        // above the horizon. Columns: X=(1,0,0),
        // Y=(0,−sin30°, cos30°), Z=(0,−cos30°, −sin30°).
        val pose = floatArrayOf(
            1f, 0f, 0f,
            0f, -0.5f, -0.8660254f,
            0f, 0.8660254f, -0.5f,
        )
        assertEquals(30.0, cameraAxisElevationDeg(pose), 1e-4)
        assertEquals(60.0, pitchDeg(pose), 1e-4)
    }

    @Test
    fun cameraAxisRolledNinetyDegreesStaysLevel() {
        // Rolled 90° about the camera axis (a landscape grip): the
        // camera still faces north, level, so γ = 0° — but the top
        // edge now points east and the pitch reads 0°, so the
        // 90° − pitch shortcut would write 90°. γ is hold-free by
        // construction; the shortcut is not. Columns: X=(0,0,−1),
        // Y=(1,0,0), Z=(0,−1,0).
        val pose = floatArrayOf(
            0f, 1f, 0f,
            0f, 0f, -1f,
            -1f, 0f, 0f,
        )
        assertEquals(0.0, cameraAxisElevationDeg(pose), 1e-6)
        assertEquals(0.0, pitchDeg(pose), 1e-6)
    }
}
