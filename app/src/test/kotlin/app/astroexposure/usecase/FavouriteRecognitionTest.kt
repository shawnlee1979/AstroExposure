package app.astroexposure.usecase

import app.astroexposure.domain.geo.FavouriteMatch
import app.astroexposure.location.LocationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Recognition-chain tests (ui-design §7.2): GPS fixes and manual
 * entries both recognise a saved place by coordinates; a place saved
 * FROM a manual entry is stored at that entry's own coordinates, so
 * the saved site must recognise itself — the regression the 0.8.4
 * review found (manual save left the name line empty and the save
 * button standing).
 */
class FavouriteRecognitionTest {

    private val home = FavouriteMatch.Candidate("Home", 40.0, -74.0)
    private val candidates = listOf(home)

    @Test
    fun manualSiteAtTheSavedCoordinatesRecognisesItself() {
        // The card's save path stores the current coordinates verbatim,
        // so the just-saved manual site sits at distance zero.
        val match = recognisedFavourite(LocationSource.MANUAL, 40.0, -74.0, candidates)
        assertEquals("Home", match?.name)
    }

    @Test
    fun manualSiteWithinTheRadiusMatches() {
        // ~55 m north of the saved place: inside the 100 m radius.
        val match = recognisedFavourite(LocationSource.MANUAL, 40.0005, -74.0, candidates)
        assertEquals("Home", match?.name)
    }

    @Test
    fun manualSiteBeyondTheRadiusDoesNotMatch() {
        // ~1.1 km north: a genuinely different site.
        assertNull(recognisedFavourite(LocationSource.MANUAL, 40.01, -74.0, candidates))
    }

    @Test
    fun gpsFixWithinTheRadiusStillMatches() {
        val match = recognisedFavourite(LocationSource.GPS, 40.0005, -74.0, candidates)
        assertEquals("Home", match?.name)
    }

    @Test
    fun favouriteAndSampleSitesNeverRecognise() {
        // A favourite selected from the library is already identified;
        // the sample fallback must never borrow a place's name.
        assertNull(recognisedFavourite(LocationSource.FAVOURITE, 40.0, -74.0, candidates))
        assertNull(recognisedFavourite(LocationSource.SAMPLE, 40.0, -74.0, candidates))
    }

    @Test
    fun noCandidatesNoMatch() {
        assertNull(recognisedFavourite(LocationSource.MANUAL, 40.0, -74.0, emptyList()))
        assertNull(recognisedFavourite(LocationSource.GPS, 40.0, -74.0, emptyList()))
    }
}
