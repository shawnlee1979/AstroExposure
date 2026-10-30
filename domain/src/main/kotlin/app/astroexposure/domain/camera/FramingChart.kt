package app.astroexposure.domain.camera

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Framing-chart geometry and verdicts (ui-design §7.10): given a
 * subject centre, a focal length, a frame rotation and a landscape /
 * portrait choice, which parts of the subject fall inside the
 * 36.0 x 24.0 mm full-frame rectangle, and what does the chart show.
 * Pure spherical geometry on explicit inputs — no catalogues, no
 * clock, no device.
 *
 * Conventions (the sanctioned ones; the samples of §7.10 were
 * computed under exactly these):
 * - All judgement happens in the tangent plane at the subject
 *   centre. A full-frame sensor IS a tangent plane: the frame's
 *   corners sit at exactly (±18/f, ±12/f) in tangent units, so
 *   in-frame tests are exact geometry, never screen measurement.
 *   The display projection (gnomonic / stereographic) only changes
 *   how the same tangent coordinates are drawn.
 * - Tangent coordinates are (xEast, yNorth): east-positive,
 *   north-positive. The east-on-the-left sky-chart flip is a
 *   presentation concern and lives in the UI.
 * - [FrameSpec.positionAngleDeg] is the position angle of the
 *   frame's UP axis (the direction the top of the image points on
 *   the sky), from north through east: at 0 degrees a landscape
 *   frame's long side runs east-west and its short side
 *   north-south; in portrait the up axis IS the long side, which is
 *   why aligning a deep-sky ellipse to its catalogue position angle
 *   is a portrait-frame operation (§7.10's align action sets this
 *   angle to the catalogue angle verbatim).
 * - A point exactly on the frame edge counts as IN frame (the
 *   composition window's boundary spirit, §7.9).
 */
object FramingChart {

    /** Display projections; judgement never depends on the choice (§7.10). */
    enum class Projection { GNOMONIC, STEREOGRAPHIC }

    /** A point in the tangent plane at some centre, in tangent (tan) units. */
    data class TangentPoint(val xEast: Double, val yNorth: Double)

    /** One frame placement: focal length, orientation, rotation. */
    data class FrameSpec(
        val focalMm: Double,
        val portrait: Boolean,
        val positionAngleDeg: Double,
    ) {
        /** The same placement with landscape / portrait exchanged. */
        fun swapped(): FrameSpec = copy(portrait = !portrait)
    }

    /** Which edge of the frame a point falls outside. */
    enum class Edge { NORTH, SOUTH, EAST, WEST }

    /** One judged-out point: the caller's item and the edge it crosses. */
    data class OutPoint<T>(val item: T, val edge: Edge)

    /** A deep-sky subject's ellipse: catalogue axes and position angle. */
    data class EllipseSpec(
        val majorArcmin: Double,
        /** Null minor axis in the catalogue means circular: major is used. */
        val minorArcmin: Double?,
        /** Null means no recorded orientation: the major axis runs along RA. */
        val positionAngleDeg: Double?,
    )

    /** Which of the ellipse's own axes is responsible for an overflow. */
    enum class EllipseOverflow { LONG_AXIS, SHORT_AXIS }

    data class EllipseVerdict(val fits: Boolean, val overflow: EllipseOverflow?)

    // -- Field of view and chart extent -------------------------------------

    /**
     * The frame's diagonal field in degrees, as §7.10 states it:
     * the root-sum-square of the long- and short-side fields of view
     * (at 50 mm: sqrt(39.6^2 + 27.0^2) = 47.9 degrees).
     */
    fun frameDiagonalDeg(focalMm: Double): Double {
        val long = FullFrameFraming.longSideFovDeg(focalMm)
        val short = FullFrameFraming.shortSideFovDeg(focalMm)
        return hypot(long, short)
    }

    /**
     * The chart's diagonal field: 1.6x the frame diagonal, clamped
     * to [14, 150] degrees — the floor keeps reference stars around
     * long-focal frames, the ceiling is the stereographic usable
     * domain (§7.10's approved rule).
     */
    fun fieldDiagonalDeg(focalMm: Double): Double =
        (FIELD_FACTOR * frameDiagonalDeg(focalMm)).coerceIn(FIELD_MIN_DEG, FIELD_MAX_DEG)

    /** Gnomonic up to 90 degrees of chart diagonal, stereographic beyond. */
    fun projectionFor(fieldDiagonalDeg: Double): Projection =
        if (fieldDiagonalDeg > PROJECTION_SWITCH_DEG) Projection.STEREOGRAPHIC
        else Projection.GNOMONIC

    // -- Spherical geometry --------------------------------------------------

    /**
     * The tangent-plane coordinates of (raDeg, decDeg) about the
     * centre, or null when the point is 90+ degrees away (the
     * gnomonic denominator has crossed zero; such a point is outside
     * every chart this feature draws).
     */
    fun tangentPoint(
        centerRaDeg: Double, centerDecDeg: Double, raDeg: Double, decDeg: Double,
    ): TangentPoint? {
        val deltaRa = Math.toRadians(raDeg - centerRaDeg)
        val dec = Math.toRadians(decDeg)
        val dec0 = Math.toRadians(centerDecDeg)
        val denominator = sin(dec0) * sin(dec) + cos(dec0) * cos(dec) * cos(deltaRa)
        if (denominator <= 0.0) return null
        val x = cos(dec) * sin(deltaRa) / denominator
        val y = (cos(dec0) * sin(dec) - sin(dec0) * cos(dec) * cos(deltaRa)) / denominator
        return TangentPoint(x, y)
    }

    /**
     * The display projection of a tangent point. Gnomonic is the
     * identity (the tangent plane drawn as-is); stereographic remaps
     * the radius r = tan(rho) to 2*tan(rho/2) in closed form,
     * preserving direction — conformal, with the wide-field
     * compression §7.10 asks for. Both share the centre and the
     * north-up convention; only the radial function differs.
     */
    fun project(point: TangentPoint, projection: Projection): TangentPoint =
        when (projection) {
            Projection.GNOMONIC -> point
            Projection.STEREOGRAPHIC -> {
                val r = hypot(point.xEast, point.yNorth)
                if (r == 0.0) {
                    point
                } else {
                    val scaled = 2.0 * r / (sqrt(1.0 + r * r) + 1.0) / r
                    TangentPoint(point.xEast * scaled, point.yNorth * scaled)
                }
            }
        }

    /** The projected radius of a chart whose diagonal field is [fieldDiagonalDeg]. */
    fun projectedFieldRadius(fieldDiagonalDeg: Double, projection: Projection): Double {
        val r = tan(Math.toRadians(fieldDiagonalDeg / 2.0))
        return project(TangentPoint(r, 0.0), projection).xEast
    }

    /** Great-circle separation in degrees (stable atan2 form). */
    fun angularSeparationDeg(
        ra1Deg: Double, dec1Deg: Double, ra2Deg: Double, dec2Deg: Double,
    ): Double {
        val dRa = Math.toRadians(ra2Deg - ra1Deg)
        val d1 = Math.toRadians(dec1Deg)
        val d2 = Math.toRadians(dec2Deg)
        val y = hypot(
            cos(d2) * sin(dRa),
            cos(d1) * sin(d2) - sin(d1) * cos(d2) * cos(dRa),
        )
        val x = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(dRa)
        return Math.toDegrees(atan2(y, x))
    }

    // -- The frame -----------------------------------------------------------

    /**
     * The frame's half extents in tangent units as (right, up):
     * landscape (18/f, 12/f), portrait exchanged. Exact by
     * construction — the sensor plane is the tangent plane.
     */
    fun frameHalfExtents(spec: FrameSpec): Pair<Double, Double> {
        val longHalf = (FullFrameFraming.FRAME_LONG_MM / 2.0) / spec.focalMm
        val shortHalf = (FullFrameFraming.FRAME_SHORT_MM / 2.0) / spec.focalMm
        return if (spec.portrait) Pair(shortHalf, longHalf) else Pair(longHalf, shortHalf)
    }

    /**
     * Frame-axis coordinates (u along right, v along up) of a
     * tangent point under [spec]'s rotation. The up axis unit vector
     * at position angle theta is (sin theta, cos theta) in
     * (east, north); the right axis is (cos theta, -sin theta).
     */
    fun frameCoords(point: TangentPoint, spec: FrameSpec): Pair<Double, Double> {
        val theta = Math.toRadians(spec.positionAngleDeg)
        val u = point.xEast * cos(theta) - point.yNorth * sin(theta)
        val v = point.xEast * sin(theta) + point.yNorth * cos(theta)
        return Pair(u, v)
    }

    /** Exact in-frame test; a point on the edge counts as in. */
    fun pointInFrame(point: TangentPoint, spec: FrameSpec): Boolean {
        val (halfRight, halfUp) = frameHalfExtents(spec)
        val (u, v) = frameCoords(point, spec)
        return abs(u) <= halfRight && abs(v) <= halfUp
    }

    /**
     * Judges every (item, tangent point): the ones outside the frame,
     * each with the edge it crosses. The edge comes from the dominant
     * overflow axis in frame coordinates, mapped back to the sky
     * direction of that frame edge's outward normal and classified
     * by its dominant compass component — at zero rotation this is
     * exactly "out the north edge" for a point past the top side.
     * Callers pass only points that have a tangent projection (a
     * subject's own skeleton vertices are always well inside 90
     * degrees of its centre, so nothing judged here can be null).
     */
    fun <T> judgePoints(
        points: List<Pair<T, TangentPoint>>,
        spec: FrameSpec,
    ): List<OutPoint<T>> {
        val (halfRight, halfUp) = frameHalfExtents(spec)
        val theta = Math.toRadians(spec.positionAngleDeg)
        val upAxis = TangentPoint(sin(theta), cos(theta))
        val rightAxis = TangentPoint(cos(theta), -sin(theta))
        val out = mutableListOf<OutPoint<T>>()
        for ((item, point) in points) {
            if (pointInFrame(point, spec)) continue
            val (u, v) = frameCoords(point, spec)
            val normal = if (abs(v) / halfUp >= abs(u) / halfRight) {
                if (v >= 0) upAxis else TangentPoint(-upAxis.xEast, -upAxis.yNorth)
            } else {
                if (u >= 0) rightAxis else TangentPoint(-rightAxis.xEast, -rightAxis.yNorth)
            }
            val edge = if (abs(normal.yNorth) >= abs(normal.xEast)) {
                if (normal.yNorth >= 0) Edge.NORTH else Edge.SOUTH
            } else {
                if (normal.xEast >= 0) Edge.EAST else Edge.WEST
            }
            out += OutPoint(item, edge)
        }
        return out
    }

    /**
     * The ellipse verdict: the support half-width of the rotated
     * ellipse along each frame axis must not exceed the frame's half
     * extent on that axis. Semi-axes are tan(half-angle) in tangent
     * units; the relative angle is the ellipse's position angle
     * minus the frame's up-axis angle. When an axis overflows, the
     * verdict names the ellipse axis whose support term dominates on
     * the worse frame axis ([EllipseOverflow]).
     */
    fun judgeEllipse(ellipse: EllipseSpec, spec: FrameSpec): EllipseVerdict {
        val a = tan(Math.toRadians(ellipse.majorArcmin / 60.0 / 2.0))
        val b = tan(Math.toRadians((ellipse.minorArcmin ?: ellipse.majorArcmin) / 60.0 / 2.0))
        val phi = Math.toRadians(ellipse.positionAngleDeg ?: RA_AXIS_POSITION_ANGLE_DEG)
        val relative = phi - Math.toRadians(spec.positionAngleDeg)
        val supportRightMajor = a * sin(relative)
        val supportRightMinor = b * cos(relative)
        val supportUpMajor = a * cos(relative)
        val supportUpMinor = b * sin(relative)
        val supportRight = hypot(supportRightMajor, supportRightMinor)
        val supportUp = hypot(supportUpMajor, supportUpMinor)
        val (halfRight, halfUp) = frameHalfExtents(spec)
        val overRight = supportRight / halfRight
        val overUp = supportUp / halfUp
        if (overRight <= 1.0 && overUp <= 1.0) return EllipseVerdict(true, null)
        val overflow = if (overUp >= overRight) {
            if (abs(supportUpMajor) >= abs(supportUpMinor)) {
                EllipseOverflow.LONG_AXIS
            } else {
                EllipseOverflow.SHORT_AXIS
            }
        } else {
            if (abs(supportRightMajor) >= abs(supportRightMinor)) {
                EllipseOverflow.LONG_AXIS
            } else {
                EllipseOverflow.SHORT_AXIS
            }
        }
        return EllipseVerdict(false, overflow)
    }

    // -- Defaults --------------------------------------------------------------

    /**
     * The chart's opening focal length for a framing advice
     * (§7.10): the reference focal of the plain range form, the
     * 2000 mm cap for a too-small target, the 24 mm floor for a
     * too-large one; null for NoSizeData (no chart section at all).
     */
    fun defaultFocalMm(advice: FullFrameFraming.Advice): Double? = when (advice) {
        is FullFrameFraming.Advice.Range -> advice.referenceFocalMm
        is FullFrameFraming.Advice.CappedAtMaxFocal -> FullFrameFraming.MAX_FOCAL_MM
        is FullFrameFraming.Advice.CappedAtMinFocal -> FullFrameFraming.MIN_FOCAL_MM
        is FullFrameFraming.Advice.NoSizeData -> null
    }

    /** Chart diagonal = this multiple of the frame diagonal (§7.10). */
    const val FIELD_FACTOR = 1.6

    /** Chart diagonal floor, degrees: long-focal charts keep reference stars. */
    const val FIELD_MIN_DEG = 14.0

    /** Chart diagonal ceiling, degrees: the stereographic usable domain. */
    const val FIELD_MAX_DEG = 150.0

    /** Chart diagonal beyond which the display projection is stereographic. */
    const val PROJECTION_SWITCH_DEG = 90.0

    /** Position angle of the RA axis from north through east. */
    const val RA_AXIS_POSITION_ANGLE_DEG = 90.0
}
