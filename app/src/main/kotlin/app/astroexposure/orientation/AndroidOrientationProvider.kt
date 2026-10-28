package app.astroexposure.orientation

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import app.astroexposure.domain.orientation.DeviceOrientationProvider
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The one adapter for the [DeviceOrientationProvider] port (ADR-0004):
 * every platform detail stops here — the fused rotation vector (with
 * an accelerometer + magnetometer fallback), the rotation-matrix
 * conversion and the magnetic-declination correction via the platform
 * [GeomagneticField]. What leaves this class is already in
 * true-north terms, so the domain and the UI never see a magnetic
 * heading or a platform type.
 *
 * Angles follow the port contract: the heading is where the phone's
 * top edge points, the pitch is that edge's elevation above the
 * horizon, and the screen-normal tilt feeds the domain's flat-posture
 * judgement. Samples are low-pass smoothed here and emitted at about
 * 5 Hz; the raw sensor rate never reaches the UI.
 *
 * Two product practices are borrowed, with attribution, from
 * Trail-Sense / the Andromeda libraries by Kyle Corry (MIT License,
 * Copyright (c) Kyle Corry; see docs/compass-survey.md): the
 * true-north handling shape (platform rotation matrix,
 * declination from the platform geomagnetic model applied inside the
 * adapter, only true-north values leaving it) was cross-checked
 * against Andromeda's published approach, and the near-metal
 * behaviour — when the measured magnetic field strength leaves the
 * range the Earth's field can plausibly have, the reported accuracy
 * is downgraded one grade instead of silently trusting a disturbed
 * compass — follows Trail-Sense's practice of reporting lower
 * compass accuracy near metal. The code here is this project's own;
 * only the practices are borrowed.
 */
class AndroidOrientationProvider(context: Context) : DeviceOrientationProvider {

    private val sensorManager: SensorManager? =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val rotationVector: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    override fun isAvailable(): Boolean =
        rotationVector != null || (accelerometer != null && magnetometer != null)

    override fun observe(
        request: DeviceOrientationProvider.OrientationRequest,
        onSample: (DeviceOrientationProvider.OrientationSample) -> Unit,
    ): DeviceOrientationProvider.Subscription {
        val manager = sensorManager
            ?: return DeviceOrientationProvider.Subscription { }
        // Declination is fixed for the subscription: it changes by
        // arc-minutes per year, so one evaluation at subscribe time
        // (site + date handed in by the caller) is exact for pointing.
        val declinationDeg = GeomagneticField(
            request.latitudeDeg.toFloat(),
            request.longitudeDeg.toFloat(),
            request.altitudeMeters.toFloat(),
            request.epochMillis,
        ).declination.toDouble()

        val session = Session(declinationDeg, fusedMode = rotationVector != null, onSample)
        val registered = when {
            rotationVector != null -> {
                // The magnetometer is registered alongside the fused
                // sensor for one job only: watching the field strength
                // for the near-metal accuracy downgrade (see the class
                // doc). It never feeds the fusion in this mode.
                magnetometer?.let {
                    manager.registerListener(session, it, SensorManager.SENSOR_DELAY_UI)
                }
                manager.registerListener(
                    session, rotationVector, SensorManager.SENSOR_DELAY_UI,
                )
            }
            accelerometer != null && magnetometer != null -> {
                val accelOk = manager.registerListener(
                    session, accelerometer, SensorManager.SENSOR_DELAY_UI,
                )
                val magOk = manager.registerListener(
                    session, magnetometer, SensorManager.SENSOR_DELAY_UI,
                )
                accelOk && magOk
            }
            else -> false
        }
        if (!registered) {
            // Registration refused (sensor vanished between the
            // availability check and now): no samples will ever
            // arrive, which the UI reports as its explicit
            // no-reading state rather than a fake live compass.
            return DeviceOrientationProvider.Subscription { }
        }
        return DeviceOrientationProvider.Subscription {
            manager.unregisterListener(session)
        }
    }

    /**
     * Per-subscription sampling state: fusion inputs, the low-pass
     * filter and the emit throttle. One session per observe() call;
     * cancelling the subscription unregisters the listener and the
     * session is dropped with it.
     */
    private class Session(
        private val declinationDeg: Double,
        private val fusedMode: Boolean,
        private val onSample: (DeviceOrientationProvider.OrientationSample) -> Unit,
    ) : SensorEventListener {
        private val rotationMatrix = FloatArray(9)
        private var gravity: FloatArray? = null
        private var magnetic: FloatArray? = null
        private var accuracy: DeviceOrientationProvider.OrientationAccuracy =
            DeviceOrientationProvider.OrientationAccuracy.MEDIUM

        /**
         * Latest total magnetic field strength in µT, from whichever
         * magnetometer events arrive (the fusion input in fallback
         * mode, the dedicated watch registration in rotation-vector
         * mode). Null until the first magnetic event.
         */
        private var fieldStrengthMicroT: Double? = null

        private var smoothHeadingSin = 0.0
        private var smoothHeadingCos = 1.0
        private var smoothPitchDeg = 0.0
        private var smoothTiltDeg = 90.0
        private var hasSmooth = false
        private var lastEmitMillis = 0L

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    emitFromMatrix()
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    gravity = event.values.copyOf()
                    emitFromFallback()
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    magnetic = event.values.copyOf()
                    val v = event.values
                    fieldStrengthMicroT = kotlin.math.sqrt(
                        (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble(),
                    )
                    // In fused mode the magnetic event is only the
                    // field-strength watch (the next fused event emits
                    // with the downgrade applied); in fallback mode it
                    // is also a fusion input.
                    if (!fusedMode) {
                        emitFromFallback()
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, newAccuracy: Int) {
            // Only the magnetic-bearing sensors say anything about
            // compass accuracy; accelerometer accuracy changes do not
            // move the pointing quality and are ignored.
            if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD ||
                sensor.type == Sensor.TYPE_ROTATION_VECTOR
            ) {
                accuracy = when (newAccuracy) {
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH ->
                        DeviceOrientationProvider.OrientationAccuracy.HIGH
                    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ->
                        DeviceOrientationProvider.OrientationAccuracy.MEDIUM
                    SensorManager.SENSOR_STATUS_ACCURACY_LOW ->
                        DeviceOrientationProvider.OrientationAccuracy.LOW
                    SensorManager.SENSOR_STATUS_UNRELIABLE ->
                        DeviceOrientationProvider.OrientationAccuracy.UNRELIABLE
                    else -> DeviceOrientationProvider.OrientationAccuracy.MEDIUM
                }
            }
        }

        private fun emitFromFallback() {
            val g = gravity ?: return
            val m = magnetic ?: return
            if (SensorManager.getRotationMatrix(rotationMatrix, null, g, m)) {
                emitFromMatrix()
            }
        }

        /**
         * Reads the device axes out of the rotation matrix (device →
         * world: world Y is magnetic north, Z is up). The phone's
         * top edge is the device +Y axis; its world components come
         * from [topEdgeWorld] (the matrix's second column — see that
         * function's doc for the column semantics and the
         * getOrientation cross-check). The screen normal is +Z, and
         * its up component R[8] feeds the flat-posture tilt.
         */
        private fun emitFromMatrix() {
            val top = topEdgeWorld(rotationMatrix)
            val normalUp = abs(rotationMatrix[8].toDouble())

            val headingMagDeg =
                (Math.toDegrees(atan2(top.east, top.north)) + 360.0) % 360.0
            val headingTrueDeg = (headingMagDeg + declinationDeg + 360.0) % 360.0
            val pitchDeg = Math.toDegrees(asin(top.up.coerceIn(-1.0, 1.0)))
            val tiltDeg = Math.toDegrees(acos(normalUp.coerceIn(0.0, 1.0)))

            // Low-pass: headings smooth on the unit circle (a plain
            // average breaks across 0°/360°), pitch and tilt linearly.
            val headingRad = Math.toRadians(headingTrueDeg)
            if (!hasSmooth) {
                smoothHeadingSin = sin(headingRad)
                smoothHeadingCos = cos(headingRad)
                smoothPitchDeg = pitchDeg
                smoothTiltDeg = tiltDeg
                hasSmooth = true
            } else {
                smoothHeadingSin += HEADING_SMOOTHING * (sin(headingRad) - smoothHeadingSin)
                smoothHeadingCos += HEADING_SMOOTHING * (cos(headingRad) - smoothHeadingCos)
                smoothPitchDeg += ANGLE_SMOOTHING * (pitchDeg - smoothPitchDeg)
                smoothTiltDeg += ANGLE_SMOOTHING * (tiltDeg - smoothTiltDeg)
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastEmitMillis < EMIT_INTERVAL_MS) return
            lastEmitMillis = now
            val smoothedHeading =
                (Math.toDegrees(atan2(smoothHeadingSin, smoothHeadingCos)) + 360.0) % 360.0
            onSample(
                DeviceOrientationProvider.OrientationSample(
                    headingTrueDeg = smoothedHeading,
                    pitchDeg = smoothPitchDeg,
                    screenNormalTiltDeg = smoothTiltDeg,
                    accuracy = reportedAccuracy(),
                    elapsedMillis = now,
                ),
            )
        }

        /**
         * The accuracy a sample is reported with: the platform grade,
         * downgraded one step while the measured field strength is
         * outside the plausible Earth-field band — near metal or a
         * magnet the compass is disturbed whether the platform says
         * so or not, and the honest move is to say the reading is
         * worse, not to keep quoting the platform grade (the borrowed
         * Trail-Sense practice; see the class doc).
         */
        private fun reportedAccuracy(): DeviceOrientationProvider.OrientationAccuracy {
            val strength = fieldStrengthMicroT ?: return accuracy
            if (strength in FIELD_PLAUSIBLE_MIN_MICRO_T..FIELD_PLAUSIBLE_MAX_MICRO_T) {
                return accuracy
            }
            return when (accuracy) {
                DeviceOrientationProvider.OrientationAccuracy.HIGH ->
                    DeviceOrientationProvider.OrientationAccuracy.MEDIUM
                DeviceOrientationProvider.OrientationAccuracy.MEDIUM ->
                    DeviceOrientationProvider.OrientationAccuracy.LOW
                DeviceOrientationProvider.OrientationAccuracy.LOW,
                DeviceOrientationProvider.OrientationAccuracy.UNRELIABLE,
                -> DeviceOrientationProvider.OrientationAccuracy.UNRELIABLE
            }
        }

        private companion object {
            /** Exponential smoothing factors at the ~60 Hz UI sensor rate. */
            const val HEADING_SMOOTHING = 0.20
            const val ANGLE_SMOOTHING = 0.30

            /** About 5 Hz toward the UI (design: smoothed redraw rate). */
            const val EMIT_INTERVAL_MS = 180L

            /**
             * Plausible band for the Earth's total field strength in
             * µT: the field runs roughly 25–65 µT over the inhabited
             * globe, and the band adds margin on both sides so only a
             * genuinely disturbed field (near metal, magnets, motors)
             * trips the downgrade.
             */
            const val FIELD_PLAUSIBLE_MIN_MICRO_T = 20.0
            const val FIELD_PLAUSIBLE_MAX_MICRO_T = 75.0
        }
    }
}
