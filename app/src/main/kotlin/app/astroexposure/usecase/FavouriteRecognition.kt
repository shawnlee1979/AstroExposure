package app.astroexposure.usecase

import app.astroexposure.domain.geo.FavouriteMatch
import app.astroexposure.location.LocationSource

/**
 * Favourite recognition for the current site (ui-design §7.2): a GPS
 * fix or a manual-coordinate entry whose coordinates fall within the
 * match radius of a saved place IS that place — the card shows its
 * name, the source line states the match and the save button goes
 * away. Both coordinate-carrying sources go through the same chain:
 * recognition was GPS-only before 0.8.6, which left a manual site
 * unrecognised after it was saved from the card (its coordinates are
 * the saved record's own, distance zero) and the button standing.
 * A site already selected FROM the library (FAVOURITE) needs no
 * recognition, and the built-in sample fallback (SAMPLE) never
 * matches. The coordinates themselves are never snapped; the match
 * only names the site.
 */
fun recognisedFavourite(
    source: LocationSource,
    latitudeDeg: Double,
    longitudeDeg: Double,
    candidates: List<FavouriteMatch.Candidate>,
): FavouriteMatch.Candidate? {
    if (source != LocationSource.GPS && source != LocationSource.MANUAL) {
        return null
    }
    return FavouriteMatch.nearestWithinRadius(latitudeDeg, longitudeDeg, candidates)
}
