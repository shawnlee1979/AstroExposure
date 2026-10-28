package app.astroexposure.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.geo.SiteTimeZone
import app.astroexposure.domain.units.Degrees
import java.time.Instant
import java.time.ZoneId

/** Which of the three location channels produced the current site (design §2.1). */
enum class LocationSource { GPS, MANUAL, FAVOURITE, SAMPLE }

/** A located site plus its provenance and, for GPS, the fix time (time-health input). */
data class LocatedSite(
    val location: GeoLocation,
    val source: LocationSource,
    /** GPS fix time when the source is GPS; feeds the time-health clock-skew check. */
    val gpsTime: Instant?,
)

/**
 * Thin wrapper over the platform [LocationManager] — no play-services
 * dependency (the app is offline and side-loaded). Every failure path
 * reports null so the UI can degrade to manual/sample and say so.
 */
class LocationProvider(private val context: Context) {

    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    /** Last known fix, if any, without waiting. */
    fun lastKnown(zone: ZoneId): LocatedSite? {
        val lm = manager ?: return null
        if (!hasPermission()) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: Location? = null
        for (provider in providers) {
            try {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.time > best!!.time) best = loc
            } catch (e: SecurityException) {
                return null
            }
        }
        return best?.let { toSite(it, zone) }
    }

    /** One fresh fix; [callback] receives null on timeout/denial/failure. */
    fun requestSingle(zone: ZoneId, timeoutMs: Long = 15_000, callback: (LocatedSite?) -> Unit) {
        val lm = manager
        if (lm == null || !hasPermission()) {
            callback(null)
            return
        }
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                callback(lastKnown(zone))
                return
            }
        }
        // One-shot delivery guard: whichever path fires first (a real fix
        // or the timeout fallback) delivers exactly once. Without it the
        // 15 s timeout still called back with the last-known fix after a
        // successful delivery, silently overriding a place the user had
        // picked in the meantime.
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        lateinit var listener: LocationListener
        // Timeout fallback: deliver whatever last-known fix exists
        // (possibly null) — but only if no real fix arrived first.
        val timeoutTask = Runnable {
            if (!delivered.compareAndSet(false, true)) return@Runnable
            try {
                lm.removeUpdates(listener)
            } catch (e: SecurityException) {
                // Removal failing only leaks this one-shot listener.
            }
            callback(lastKnown(zone))
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!delivered.compareAndSet(false, true)) return
                handler.removeCallbacks(timeoutTask)
                try {
                    lm.removeUpdates(this)
                } catch (e: SecurityException) {
                    // Removal failing only leaks this one-shot listener; the fix is delivered.
                }
                callback(toSite(location, zone))
            }

            @Deprecated("Deprecated in API 29; kept for the minSdk 26 floor")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener)
        } catch (e: SecurityException) {
            callback(null)
            return
        } catch (e: IllegalArgumentException) {
            callback(null)
            return
        }
        handler.postDelayed(timeoutTask, timeoutMs)
    }

    private fun toSite(location: Location, fallbackZone: ZoneId): LocatedSite = LocatedSite(
        location = GeoLocation(
            latitude = Degrees(location.latitude),
            longitude = Degrees(location.longitude),
            heightMeters = if (location.hasAltitude()) location.altitude else 0.0,
            // §7.6: the site's zone comes from its coordinates, with the
            // caller's zone (the device zone) as the explicit fallback.
            zone = SiteTimeZone.resolve(location.latitude, location.longitude, fallbackZone),
            label = null,
        ),
        source = LocationSource.GPS,
        gpsTime = if (location.time > 0) Instant.ofEpochMilli(location.time) else null,
    )
}
