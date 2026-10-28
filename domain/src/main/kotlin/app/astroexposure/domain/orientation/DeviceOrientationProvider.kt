package app.astroexposure.domain.orientation

/**
 * Port for the device's direction/orientation sensing (DEVELOPMENT-RULES.md
 * B.1; target detail dialog redesign, ui-design §7.5 as revised for 0.8.0).
 * The interface speaks only domain types: all angles are in degrees (D.3),
 * and no platform or third-party type appears in any signature.
 *
 * True north only: the sensor itself reports a magnetic-north heading, and
 * the magnetic declination depends on the place and the date, so the
 * adapter applies the declination correction internally (the platform
 * geomagnetic field model, fed with the [OrientationRequest] place and
 * epoch) and every value that crosses this port is already in true-north
 * terms. Domain code and the UI never see a declination or a platform
 * geomagnetic type — the correction stops at the adapter layer.
 *
 * Accuracy is explicit: every sample carries an [OrientationAccuracy]
 * grade so callers can present a degraded reading as degraded instead of
 * silently trusting it. Unavailability is likewise explicit and is NOT a
 * magic failure value (E.5): a device without the required sensors reports
 * [isAvailable] = false, and an interrupted stream is expressed by samples
 * simply stopping (the caller applies its own staleness judgement), never
 * by a null sample or a sentinel angle.
 */
interface DeviceOrientationProvider {

    /**
     * Whether the device has direction-sensing capability at all
     * (magnetometer + accelerometer). Callers check this before
     * subscribing and fall back to a static presentation when it is
     * false; they never subscribe and wait for samples that cannot come.
     */
    fun isAvailable(): Boolean

    /**
     * Subscribe to the orientation stream. The caller supplies the place
     * and the epoch in [request] at subscription time; the adapter uses
     * them for the magnetic-declination correction described above, so
     * the samples delivered to [onSample] are true-north values.
     * The returned [Subscription] must be cancelled to unsubscribe
     * (subscribe when the finder opens, cancel when it closes).
     */
    fun observe(request: OrientationRequest, onSample: (OrientationSample) -> Unit): Subscription

    /** Cancellation handle for a subscription returned by [observe]. */
    fun interface Subscription {
        fun cancel()
    }

    /**
     * Subscription-time context for the adapter: the observer's place
     * (degrees / metres) and the epoch the declination is evaluated at
     * (epoch milliseconds). Supplied explicitly by the caller (D.2 — the
     * port does not read a clock or a GPS itself).
     */
    data class OrientationRequest(
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val altitudeMeters: Double,
        val epochMillis: Long,
    )

    /**
     * One orientation reading, all angles in degrees.
     *
     * [headingTrueDeg] is the heading of the device's top edge in
     * true-north terms; [pitchDeg] is the elevation angle of the device's
     * top edge above the horizon (the aiming posture of ui-design
     * §7.5: portrait hold, top edge pointing at the sky); [screenNormalTiltDeg]
     * is the angle between the screen normal and the vertical, the input
     * of the flat-posture judgement in [holdingPosture] (interpreted on
     * [0, 90]); [elapsedMillis] is the sample's elapsed-time stamp on the
     * adapter's clock, for staleness judgements by the caller.
     */
    data class OrientationSample(
        val headingTrueDeg: Double,
        val pitchDeg: Double,
        val screenNormalTiltDeg: Double,
        val accuracy: OrientationAccuracy,
        val elapsedMillis: Long,
    )

    /** Accuracy grade of a sample, mapped by the adapter from the platform's sensor accuracy. */
    enum class OrientationAccuracy { HIGH, MEDIUM, LOW, UNRELIABLE }
}
