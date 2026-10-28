package app.astroexposure.domain.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Matching a fresh GPS fix against the saved favourite places
 * (ui-design §7.2): when the nearest favourite lies within
 * [MATCH_RADIUS_METERS] of the fix, the fix is treated as "at that
 * place" and the UI shows the favourite's name. Pure functions of
 * their inputs — the caller supplies the fix and the favourites.
 */
object FavouriteMatch {

    /**
     * Radius inside which a GPS fix counts as being at a favourite
     * place. Empirical value: phone GPS fixes wander by tens of
     * metres, so the radius must cover fix scatter without swallowing
     * a genuinely different site across the road (ui-design §7.2).
     */
    const val MATCH_RADIUS_METERS: Double = 100.0

    /** Mean Earth radius, metres (the standard spherical approximation). */
    private const val EARTH_RADIUS_METERS: Double = 6_371_000.0

    /** A favourite place reduced to what matching needs. */
    data class Candidate(
        val name: String,
        val latitudeDeg: Double,
        val longitudeDeg: Double,
    )

    /** Great-circle distance between two coordinates, metres (haversine). */
    fun distanceMeters(
        latitudeDeg1: Double,
        longitudeDeg1: Double,
        latitudeDeg2: Double,
        longitudeDeg2: Double,
    ): Double {
        val dLat = Math.toRadians(latitudeDeg2 - latitudeDeg1)
        val dLon = Math.toRadians(longitudeDeg2 - longitudeDeg1)
        val lat1 = Math.toRadians(latitudeDeg1)
        val lat2 = Math.toRadians(latitudeDeg2)
        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a).coerceAtMost(1.0))
    }

    /**
     * The nearest candidate within [radiusMeters] of the given point,
     * or null when there are no candidates or every candidate is
     * farther away. The boundary is inclusive: a candidate exactly at
     * the radius still matches.
     */
    fun nearestWithinRadius(
        latitudeDeg: Double,
        longitudeDeg: Double,
        candidates: List<Candidate>,
        radiusMeters: Double = MATCH_RADIUS_METERS,
    ): Candidate? = candidates
        .map { it to distanceMeters(latitudeDeg, longitudeDeg, it.latitudeDeg, it.longitudeDeg) }
        .filter { (_, distance) -> distance <= radiusMeters }
        .minByOrNull { (_, distance) -> distance }
        ?.first
}
