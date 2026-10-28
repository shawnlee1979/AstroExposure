package app.astroexposure.domain.orientation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for [DeviceOrientationProvider] in its scripted-fake
 * form (DEVELOPMENT-RULES.md C): a fake adapter feeds a fixed sample
 * sequence synchronously, and the assertions pin only how the domain
 * consumes samples — the state migrations of [advanceAlignment] and
 * the readouts of [finderReadout] — never any real-device attitude
 * values, which cannot be reproduced on the JVM.
 */
class OrientationPipelineContractTest {

    /**
     * Scripted fake provider: [observe] registers the callback and
     * records the request; the test then pumps the scripted samples
     * synchronously, one by one or all at once, through [deliverNext] /
     * [deliverAll]. Cancelling the subscription stops all further
     * delivery. Availability is configurable at construction.
     */
    private class ScriptedFakeOrientationProvider(
        private val script: List<DeviceOrientationProvider.OrientationSample>,
        private val available: Boolean = true,
    ) : DeviceOrientationProvider {
        var lastRequest: DeviceOrientationProvider.OrientationRequest? = null
            private set
        var deliveredCount: Int = 0
            private set
        private var onSample: ((DeviceOrientationProvider.OrientationSample) -> Unit)? = null
        private var nextIndex: Int = 0
        private var cancelled: Boolean = false

        override fun isAvailable(): Boolean = available

        override fun observe(
            request: DeviceOrientationProvider.OrientationRequest,
            onSample: (DeviceOrientationProvider.OrientationSample) -> Unit,
        ): DeviceOrientationProvider.Subscription {
            lastRequest = request
            this.onSample = onSample
            cancelled = false
            return DeviceOrientationProvider.Subscription { cancelled = true }
        }

        /** Feed the next scripted sample synchronously; false when stopped or exhausted. */
        fun deliverNext(): Boolean {
            val callback = onSample ?: return false
            if (cancelled || nextIndex >= script.size) return false
            callback(script[nextIndex])
            nextIndex++
            deliveredCount++
            return true
        }

        fun deliverAll() {
            while (deliverNext()) {
                // Pump until the script is exhausted or the subscription is cancelled.
            }
        }
    }

    private fun sample(
        headingTrueDeg: Double,
        pitchDeg: Double,
        screenNormalTiltDeg: Double = 45.0,
        elapsedMillis: Long = 0L,
    ) = DeviceOrientationProvider.OrientationSample(
        headingTrueDeg = headingTrueDeg,
        pitchDeg = pitchDeg,
        screenNormalTiltDeg = screenNormalTiltDeg,
        accuracy = DeviceOrientationProvider.OrientationAccuracy.HIGH,
        elapsedMillis = elapsedMillis,
    )

    private val request = DeviceOrientationProvider.OrientationRequest(
        latitudeDeg = 40.0,
        longitudeDeg = 116.0,
        altitudeMeters = 50.0,
        epochMillis = 1_000L,
    )

    /**
     * The consuming pipeline, mirroring how the finder UI is specified
     * to consume the port: per sample, compute both deltas with the
     * domain's own delta functions, suspend the altitude axis (null
     * delta) when the target is below the horizon or the phone is flat,
     * advance the alignment state, then assemble the readout.
     */
    private class Pipeline(
        private val targetAltDeg: Double,
        private val targetAzDeg: Double,
    ) {
        var state: AlignmentState = AlignmentState()
            private set
        val states = mutableListOf<FinderAlignment>()
        val stateHistory = mutableListOf<AlignmentState>()
        val readouts = mutableListOf<FinderReadout>()

        fun onSample(sample: DeviceOrientationProvider.OrientationSample) {
            val azimuthDelta = signedAzimuthDeltaDeg(sample.headingTrueDeg, targetAzDeg)
            val altitudeDelta = if (
                targetAltDeg < 0.0 || holdingPosture(sample.screenNormalTiltDeg) == HoldingPosture.FLAT
            ) {
                null
            } else {
                pitchDeltaDeg(sample.pitchDeg, targetAltDeg)
            }
            state = advanceAlignment(state, azimuthDelta, altitudeDelta)
            states += state.combined
            stateHistory += state
            readouts += finderReadout(targetAltDeg, targetAzDeg, sample, state)
        }
    }

    @Test
    fun `scripted approach drives the state machine to aligned holds through the band then drops one axis`() {
        val pipeline = Pipeline(targetAltDeg = 40.0, targetAzDeg = 100.0)
        val fake = ScriptedFakeOrientationProvider(
            listOf(
                sample(70.0, 20.0), // Δaz +30, Δalt +20: both far out
                sample(90.0, 35.0), // Δaz +10, Δalt +5: closing, still out
                sample(98.0, 38.0), // Δaz +2, Δalt +2: both enter
                sample(103.0, 44.0), // Δaz −3, Δalt −4: inside the exit band, both hold
                sample(110.0, 38.0), // Δaz −10: azimuth exits; Δalt +2: altitude holds
            ),
        )
        fake.observe(request, pipeline::onSample)
        fake.deliverAll()

        assertEquals(5, fake.deliveredCount)
        assertEquals(
            listOf(
                FinderAlignment.UNALIGNED,
                FinderAlignment.UNALIGNED,
                FinderAlignment.ALIGNED,
                FinderAlignment.ALIGNED,
                FinderAlignment.ALTITUDE_ONLY,
            ),
            pipeline.states,
        )
        val first = pipeline.readouts.first()
        assertEquals(AzimuthCue.TURN_RIGHT, first.azimuthCue)
        assertEquals(AltitudeCue.RAISE, first.altitudeCue)
        val aligned = pipeline.readouts[2]
        assertEquals(AzimuthCue.ALIGNED, aligned.azimuthCue)
        assertEquals(AltitudeCue.ALIGNED, aligned.altitudeCue)
        val last = pipeline.readouts.last()
        assertEquals(AzimuthCue.TURN_LEFT, last.azimuthCue)
        assertEquals(AltitudeCue.ALIGNED, last.altitudeCue)
        assertEquals(FinderAlignment.ALTITUDE_ONLY, last.alignment)
    }

    @Test
    fun `observe records the request the caller supplied`() {
        val fake = ScriptedFakeOrientationProvider(emptyList())
        assertNull(fake.lastRequest)
        fake.observe(request) { }
        assertEquals(request, fake.lastRequest)
    }

    @Test
    fun `cancelling the subscription stops delivery of the remaining script`() {
        val received = mutableListOf<DeviceOrientationProvider.OrientationSample>()
        val fake = ScriptedFakeOrientationProvider(
            listOf(sample(0.0, 0.0), sample(10.0, 10.0), sample(20.0, 20.0)),
        )
        val subscription = fake.observe(request) { received += it }
        assertTrue(fake.deliverNext())
        subscription.cancel()
        fake.deliverAll()
        assertEquals(1, fake.deliveredCount)
        assertEquals(1, received.size)
        assertFalse(fake.deliverNext())
    }

    @Test
    fun `availability is whatever the provider was configured with`() {
        assertTrue(ScriptedFakeOrientationProvider(emptyList(), available = true).isAvailable())
        assertFalse(ScriptedFakeOrientationProvider(emptyList(), available = false).isAvailable())
    }

    @Test
    fun `a flat sample suspends the altitude axis and resuming restarts it unaligned`() {
        val pipeline = Pipeline(targetAltDeg = 40.0, targetAzDeg = 100.0)
        val fake = ScriptedFakeOrientationProvider(
            listOf(
                sample(98.0, 38.0), // aiming: both axes enter
                sample(99.0, 10.0, screenNormalTiltDeg = 5.0), // flat: altitude suspended
                sample(99.0, 41.0), // aiming again: altitude re-enters from unaligned
            ),
        )
        fake.observe(request, pipeline::onSample)
        fake.deliverAll()

        assertEquals(
            listOf(
                FinderAlignment.ALIGNED,
                FinderAlignment.AZIMUTH_ONLY, // altitude null caps the combination
                FinderAlignment.ALIGNED,
            ),
            pipeline.states,
        )
        val flat = pipeline.readouts[1]
        assertEquals(HoldingPosture.FLAT, flat.posture)
        assertEquals(AltitudeCue.FLAT_POSTURE, flat.altitudeCue)
        // The flat sample itself left the altitude axis unevaluated,
        // while the azimuth axis stayed aligned throughout.
        assertNull(pipeline.stateHistory[1].altitudeAligned)
        assertTrue(pipeline.stateHistory[1].azimuthAligned)
        assertEquals(true, pipeline.stateHistory[2].altitudeAligned)
    }

    @Test
    fun `a target below the horizon never evaluates the altitude axis`() {
        val pipeline = Pipeline(targetAltDeg = -8.0, targetAzDeg = 100.0)
        val fake = ScriptedFakeOrientationProvider(
            listOf(
                sample(70.0, 0.0), // Δaz +30: unaligned, turn right
                sample(99.0, 0.0), // Δaz +1: azimuth enters; altitude stays unevaluated
            ),
        )
        fake.observe(request, pipeline::onSample)
        fake.deliverAll()

        assertEquals(
            listOf(FinderAlignment.UNALIGNED, FinderAlignment.AZIMUTH_ONLY),
            pipeline.states,
        )
        assertNull(pipeline.state.altitudeAligned)
        val last = pipeline.readouts.last()
        assertEquals(AltitudeCue.TARGET_BELOW_HORIZON, last.altitudeCue)
        assertEquals(-8.0, last.altitudeDeltaDeg!!, 1e-9) // delta still reported
        assertEquals(AzimuthCue.ALIGNED, last.azimuthCue)
    }
}
