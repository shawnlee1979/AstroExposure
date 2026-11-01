package app.astroexposure.orientation

/**
 * Axis extraction from the platform rotation matrix, kept as a pure
 * function of the matrix alone so JVM tests can pin it (the adapter
 * that owns the matrix cannot run off-device).
 *
 * The matrices [android.hardware.SensorManager] produces — from
 * `getRotationMatrixFromVector` and from `getRotationMatrix` alike —
 * are row-major and map device coordinates to world coordinates
 * (world X = east, Y = north, Z = up). A matrix that maps one frame
 * to another carries the image of each source axis in a COLUMN: the
 * device +Y axis (the phone's top edge) lands in the world frame at
 * (R[1], R[4], R[7]) — the second column. Taking a row instead reads
 * a world axis expressed in device coordinates, a different vector
 * entirely.
 *
 * Cross-check against the platform's own `getOrientation`: its
 * azimuth is atan2(R[1], R[4]), which is exactly
 * atan2(topEast, topNorth) over the values returned here, so the
 * heading the adapter derives from this function agrees with the
 * platform's by construction.
 */

/** The world-frame components of one device axis (dimensionless unit-vector parts). */
internal data class WorldComponents(
    val east: Double,
    val north: Double,
    val up: Double,
)

/**
 * The world-frame components of the device +Y axis (the phone's top
 * edge) in [rotationMatrix]: east = R[1], north = R[4], up = R[7].
 * The adapter derives the top edge's heading as atan2(east, north)
 * and its pitch as asin(up).
 */
internal fun topEdgeWorld(rotationMatrix: FloatArray): WorldComponents {
    require(rotationMatrix.size >= 9) {
        "rotation matrix needs at least 9 elements, has ${rotationMatrix.size}"
    }
    return WorldComponents(
        east = rotationMatrix[1].toDouble(),
        north = rotationMatrix[4].toDouble(),
        up = rotationMatrix[7].toDouble(),
    )
}

/**
 * The world-frame components of the device +Z axis in
 * [rotationMatrix]: east = R[2], north = R[5], up = R[8] — the third
 * column, by the same column semantics as [topEdgeWorld]. The +Z
 * axis points out of the screen toward the holder, so the rear
 * camera's optical axis (screen toward scene) is its negative.
 */
internal fun screenNormalWorld(rotationMatrix: FloatArray): WorldComponents {
    require(rotationMatrix.size >= 9) {
        "rotation matrix needs at least 9 elements, has ${rotationMatrix.size}"
    }
    return WorldComponents(
        east = rotationMatrix[2].toDouble(),
        north = rotationMatrix[5].toDouble(),
        up = rotationMatrix[8].toDouble(),
    )
}

/**
 * The elevation of the rear camera's optical axis above the horizon,
 * in degrees: γ = asin(−up(+Z)) (ui-design §7.9, 0.17.0). Computed
 * from the matrix directly — deliberately NOT from the top edge's
 * pitch: γ = 90° − pitch holds only for the zero-roll portrait
 * family of holds, and the viewfinder capture exists precisely for
 * the holds where it does not (in the natural camera-aiming hold
 * with a level camera axis, the pitch reads ≈ 90° while γ = 0°).
 */
internal fun cameraAxisElevationDeg(rotationMatrix: FloatArray): Double {
    val up = screenNormalWorld(rotationMatrix).up
    return Math.toDegrees(kotlin.math.asin((-up).coerceIn(-1.0, 1.0)))
}
