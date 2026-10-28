package app.astroexposure.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavouriteMatchTest {

    // A base point; offsets below are sized against ~111.19 km per
    // degree of latitude on the spherical model the implementation
    // uses, so 0.0008° ≈ 89 m (inside the 100 m radius) and
    // 0.001° ≈ 111 m (outside it).
    private val baseLat = 40.0
    private val baseLon = -74.0

    @Test
    fun noCandidatesMatchNothing() {
        assertNull(FavouriteMatch.nearestWithinRadius(baseLat, baseLon, emptyList()))
    }

    @Test
    fun candidateInsideRadiusMatches() {
        val near = FavouriteMatch.Candidate("near", baseLat + 0.0008, baseLon)
        assertEquals(near, FavouriteMatch.nearestWithinRadius(baseLat, baseLon, listOf(near)))
    }

    @Test
    fun candidateOutsideRadiusDoesNotMatch() {
        val far = FavouriteMatch.Candidate("far", baseLat + 0.001, baseLon)
        assertNull(FavouriteMatch.nearestWithinRadius(baseLat, baseLon, listOf(far)))
    }

    @Test
    fun boundaryIsInclusive() {
        val candidate = FavouriteMatch.Candidate("edge", baseLat + 0.0008, baseLon)
        val distance = FavouriteMatch.distanceMeters(
            baseLat, baseLon, candidate.latitudeDeg, candidate.longitudeDeg,
        )
        // Exactly at the radius: a match. A hair inside it: no match.
        assertEquals(
            candidate,
            FavouriteMatch.nearestWithinRadius(
                baseLat, baseLon, listOf(candidate), radiusMeters = distance,
            ),
        )
        assertNull(
            FavouriteMatch.nearestWithinRadius(
                baseLat, baseLon, listOf(candidate), radiusMeters = distance - 0.001,
            ),
        )
    }

    @Test
    fun nearestOfSeveralWinsRegardlessOfOrder() {
        val closer = FavouriteMatch.Candidate("closer", baseLat + 0.0002, baseLon)
        val farther = FavouriteMatch.Candidate("farther", baseLat + 0.0008, baseLon)
        assertEquals(
            closer,
            FavouriteMatch.nearestWithinRadius(baseLat, baseLon, listOf(farther, closer)),
        )
        assertEquals(
            closer,
            FavouriteMatch.nearestWithinRadius(baseLat, baseLon, listOf(closer, farther)),
        )
    }

    @Test
    fun candidatesBeyondRadiusAreIgnoredEvenWhenOthersMatch() {
        val inside = FavouriteMatch.Candidate("inside", baseLat, baseLon + 0.0005)
        val outside = FavouriteMatch.Candidate("outside", baseLat + 0.005, baseLon)
        assertEquals(
            inside,
            FavouriteMatch.nearestWithinRadius(baseLat, baseLon, listOf(outside, inside)),
        )
    }

    @Test
    fun distanceIsZeroForTheSamePointAndSymmetric() {
        assertEquals(
            0.0,
            FavouriteMatch.distanceMeters(baseLat, baseLon, baseLat, baseLon),
            1e-9,
        )
        val ab = FavouriteMatch.distanceMeters(baseLat, baseLon, baseLat + 0.001, baseLon + 0.001)
        val ba = FavouriteMatch.distanceMeters(baseLat + 0.001, baseLon + 0.001, baseLat, baseLon)
        assertEquals(ab, ba, 1e-6)
    }

    @Test
    fun oneDegreeOfLatitudeIsAbout111Kilometres() {
        // Independent check value: the mean Earth radius used by the
        // implementation gives 2πR/360 ≈ 111.19 km per degree.
        assertEquals(
            111_195.0,
            FavouriteMatch.distanceMeters(0.0, 0.0, 1.0, 0.0),
            50.0,
        )
    }
}
