package app.astroexposure.domain.geo

import java.time.ZoneId

/**
 * Coordinate → civil time zone resolution for the Tonight page
 * (ui-design §7.6): every displayed instant is computed and shown in the
 * zone of the selected site, not the device zone.
 *
 * The app is offline and bundles no tz-boundary database, so resolution
 * uses a coarse table of axis-aligned boxes over major land areas,
 * ordered most-specific first. This is an approximation by design:
 *
 * - A box can straddle a real tz boundary (e.g. a neighbouring province
 *   or state on a different rule) and border areas may resolve to the
 *   neighbouring zone.
 * - Known simplifications include: Arizona (listed under the mountain
 *   box, most of the state does not observe daylight saving), the
 *   Aleutian Islands (listed under Alaska), Newfoundland (listed under
 *   Atlantic Canada), north-eastern India near the China box and
 *   western Iberia (the Portugal box matches before the Spain box, so
 *   Galicia and its surroundings resolve to Europe/Lisbon, one hour
 *   behind Madrid).
 * - Coordinates outside every box (oceans, central Asia, much of Russia,
 *   parts of Africa and South America) do not resolve at all.
 *
 * Unresolved coordinates fall back to the caller-supplied zone (the
 * device zone at the application boundary) and the UI always names the
 * zone actually in use, so a fallback or a border error is visible
 * rather than silently presented as exact. Resolved results are
 * [ZoneId]s, so daylight-saving transitions are handled by java.time.
 */
object SiteTimeZone {

    private data class Box(
        val latMin: Double, val latMax: Double,
        val lonMin: Double, val lonMax: Double,
        val zoneId: String,
    )

    // Most specific / smallest boxes first; the first containing box wins.
    private val BOXES = listOf(
        // Ireland before the wider British Isles box.
        Box(51.0, 55.5, -10.5, -5.5, "Europe/Dublin"),
        Box(49.0, 59.0, -11.0, 2.0, "Europe/London"),
        // Iberia: Portugal before Spain.
        Box(36.5, 44.0, -9.5, -6.0, "Europe/Lisbon"),
        Box(35.5, 44.0, -9.5, 3.5, "Europe/Madrid"),
        Box(38.0, 47.5, 7.0, 19.0, "Europe/Rome"),
        Box(43.0, 54.5, -2.0, 15.0, "Europe/Paris"),
        Box(55.0, 69.0, 5.0, 18.0, "Europe/Stockholm"),
        Box(49.0, 55.5, 15.0, 24.0, "Europe/Berlin"),
        Box(59.0, 70.0, 18.0, 31.5, "Europe/Helsinki"),
        Box(35.0, 42.0, 19.0, 30.0, "Europe/Athens"),
        Box(36.0, 42.5, 26.0, 45.0, "Europe/Istanbul"),
        Box(41.0, 68.0, 30.0, 55.0, "Europe/Moscow"),
        // Middle East: small Gulf box before the wider Arabia box.
        Box(22.0, 26.5, 51.0, 56.5, "Asia/Dubai"),
        Box(29.0, 33.5, 34.0, 36.0, "Asia/Jerusalem"),
        Box(12.0, 32.0, 34.0, 55.0, "Asia/Riyadh"),
        Box(25.0, 40.0, 44.0, 63.0, "Asia/Tehran"),
        // South and East Asia: India box first (see class note), then China.
        Box(6.0, 35.0, 68.0, 97.0, "Asia/Kolkata"),
        Box(5.0, 23.0, 97.0, 108.0, "Asia/Bangkok"),
        Box(33.0, 43.0, 124.0, 130.5, "Asia/Seoul"),
        Box(31.0, 46.0, 129.0, 146.0, "Asia/Tokyo"),
        Box(18.0, 54.0, 73.0, 135.0, "Asia/Shanghai"),
        // Africa (coarse: north-west and southern cones only).
        Box(15.0, 37.0, -17.0, 10.0, "Africa/Casablanca"),
        Box(-35.0, -17.0, 12.0, 33.0, "Africa/Johannesburg"),
        // North America: island and edge boxes before the continental bands.
        Box(18.0, 23.0, -161.0, -154.0, "Pacific/Honolulu"),
        Box(51.0, 72.0, -170.0, -130.0, "America/Anchorage"),
        Box(43.0, 52.0, -67.0, -52.0, "America/Halifax"),
        Box(32.0, 49.0, -125.0, -117.0, "America/Los_Angeles"),
        Box(31.0, 49.0, -117.0, -104.0, "America/Denver"),
        Box(25.0, 49.0, -104.0, -87.0, "America/Chicago"),
        Box(24.0, 49.0, -87.0, -67.0, "America/New_York"),
        Box(14.0, 33.0, -118.0, -86.0, "America/Mexico_City"),
        // South America.
        Box(-4.0, 12.0, -79.0, -66.0, "America/Bogota"),
        Box(-18.0, 0.0, -81.0, -68.0, "America/Lima"),
        Box(-56.0, -17.0, -76.0, -66.0, "America/Santiago"),
        Box(-55.0, -22.0, -73.0, -53.0, "America/Argentina/Buenos_Aires"),
        Box(-34.0, -10.0, -74.0, -34.0, "America/Sao_Paulo"),
        Box(-10.0, 5.0, -74.0, -34.0, "America/Fortaleza"),
        // Oceania.
        Box(-35.0, -12.0, 129.0, 141.0, "Australia/Adelaide"),
        Box(-39.0, -10.0, 141.0, 154.0, "Australia/Sydney"),
        Box(-35.0, -14.0, 112.0, 129.0, "Australia/Perth"),
        Box(-47.0, -34.0, 166.0, 179.0, "Pacific/Auckland"),
    )

    /** The zone of the first box containing the coordinates, or null when no box covers them. */
    fun resolveOrNull(latitudeDeg: Double, longitudeDeg: Double): ZoneId? {
        if (!Coordinates.isValid(latitudeDeg, longitudeDeg)) return null
        val box = BOXES.firstOrNull {
            latitudeDeg in it.latMin..it.latMax && longitudeDeg in it.lonMin..it.lonMax
        } ?: return null
        return ZoneId.of(box.zoneId)
    }

    /** [resolveOrNull] with an explicit caller-supplied fallback (never a silent guess). */
    fun resolve(latitudeDeg: Double, longitudeDeg: Double, fallback: ZoneId): ZoneId =
        resolveOrNull(latitudeDeg, longitudeDeg) ?: fallback
}
