package app.astroexposure.domain.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design §2.5: the built-in Polaris profiles ship with EMPTY drift values
 * until a baseline measurement supplies them — no placeholder estimates.
 */
class TrackingProfileTest {

    @Test
    fun `built-in profiles carry no drift numbers`() {
        for (profile in BuiltInTrackingProfiles.ALL) {
            assertNull("${profile.id} typicalDrift", profile.typicalDrift)
            assertNull("${profile.id} worstDrift", profile.worstDrift)
            assertNull("${profile.id} morphology", profile.morphology)
            assertNull("${profile.id} measuredOn", profile.measuredOn)
        }
    }

    @Test
    fun `built-in profile kinds match design table`() {
        assertEquals(TrackingKind.UNTRACKED, BuiltInTrackingProfiles.UNTRACKED.kind)
        assertEquals(TrackingKind.TRACKED, BuiltInTrackingProfiles.POLARIS_SINGLE_STAR.kind)
        assertEquals(TrackingKind.TRACKED, BuiltInTrackingProfiles.POLARIS_TWO_STAR.kind)
        assertEquals(3, BuiltInTrackingProfiles.ALL.size)
        assertTrue(BuiltInTrackingProfiles.ALL.map { it.id }.distinct().size == 3)
    }
}
