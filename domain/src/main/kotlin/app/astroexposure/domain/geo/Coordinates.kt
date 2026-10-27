package app.astroexposure.domain.geo

/**
 * Coordinate range validation shared by every site-entry channel
 * (ui-design §7.2): latitude −90°…90°, longitude −180°…180°. Non-finite
 * values are invalid. Pure and total — callers decide how to present a
 * rejection; nothing is silently clamped into range.
 */
object Coordinates {

    fun isValidLatitude(latitudeDeg: Double): Boolean =
        latitudeDeg.isFinite() && latitudeDeg in -90.0..90.0

    fun isValidLongitude(longitudeDeg: Double): Boolean =
        longitudeDeg.isFinite() && longitudeDeg in -180.0..180.0

    fun isValid(latitudeDeg: Double, longitudeDeg: Double): Boolean =
        isValidLatitude(latitudeDeg) && isValidLongitude(longitudeDeg)
}
