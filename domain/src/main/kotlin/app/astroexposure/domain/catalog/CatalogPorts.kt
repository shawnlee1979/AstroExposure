package app.astroexposure.domain.catalog

import app.astroexposure.domain.ephemeris.EclipseKind
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.lens.Lens
import java.time.Instant
import java.time.LocalDate

/**
 * Catalogue ports (DEVELOPMENT-RULES.md B.1): static data sets are consumed
 * only through these interfaces, which speak domain types exclusively.
 * Implementations live in :data and read the bundled data products;
 * [dataVersion] on every port is the data set's recorded version string
 * (design §8.4), surfaced on the data-sources screen.
 */

/** Light-pollution lookup: coordinates -> artificial sky luminance (design §2.6). */
interface LightPollutionSource {
    /**
     * Artificial sky luminance in mcd/m² at [location], or null when the
     * data set has no coverage there (e.g. open ocean) — callers then use
     * the design §2.8 manual-Bortle fallback, never a guessed value.
     */
    fun artificialBrightnessMcdPerM2(location: GeoLocation): Double?

    val dataVersion: String

    /**
     * The data set's header in structured form, for the location card's
     * short display (ui-design §7.2). The default derives what it can
     * from [dataVersion] alone (the grid kind stays unknown, so the
     * short display falls back to the raw string); adapters whose
     * header carries a separate grid-kind field override this.
     */
    val versionInfo: LightPollutionVersion
        get() = LightPollutionVersionFormat.parse(dataVersion, null)
}

/** Deep-sky object catalogue (design §6.1: OpenNGC-derived subset). */
interface ObjectCatalog {
    fun allObjects(): List<DeepSkyObject>
    val dataVersion: String
}

enum class DeepSkyObjectType {
    GALAXY, OPEN_CLUSTER, GLOBULAR_CLUSTER, NEBULA, PLANETARY_NEBULA,
    SUPERNOVA_REMNANT, DOUBLE_STAR, ASTERISM, OTHER,
}

data class DeepSkyObject(
    val id: String,
    val name: String,
    val nameZh: String?,
    /** Right ascension in HOURS, declination in degrees (J2000). */
    val raHours: Double,
    val decDeg: Double,
    val magnitude: Double?,
    val sizeMajorArcmin: Double?,
    val sizeMinorArcmin: Double?,
    /**
     * Position angle of the major axis in degrees (from north through
     * east, J2000), from OpenNGC's PosAng column; null when the source
     * lists none — never defaulted to 0, which would state a false
     * orientation (0.11.0).
     */
    val positionAngleDeg: Double? = null,
    val type: DeepSkyObjectType,
    val constellation: String?,
    /** Mean surface brightness, mag/arcsec², when known or estimated. */
    val surfaceBrightness: Double?,
    /** True when [surfaceBrightness] was estimated from magnitude + size (design §6.1). */
    val surfaceBrightnessEstimated: Boolean,
)

/** Eclipse catalogue 2026–2035 (design §6.1: NASA GSFC-derived static table). */
interface EclipseCatalog {
    fun eclipses(fromYear: Int, toYear: Int): List<EclipseRecord>
    val dataVersion: String
}

/** Eclipse classification; for lunar events PARTIAL = umbral partial, PENUMBRAL = penumbral only. */
enum class EclipseType { TOTAL, ANNULAR, PARTIAL, HYBRID, PENUMBRAL }

data class EclipseRecord(
    val date: LocalDate,
    val kind: EclipseKind,
    val type: EclipseType,
    val peakUtc: Instant?,
    /** Eclipse magnitude (catalogue definition) when recorded. */
    val magnitude: Double?,
    val saros: Int?,
    /** Path width in km for central solar eclipses, when recorded. */
    val pathWidthKm: Int?,
    /** Central-line duration in seconds for central solar eclipses, when recorded. */
    val centralDurationSec: Int?,
    /** Free-text visibility summary from the catalogue, when recorded. */
    val visibility: String?,
)

/** Meteor shower annual table (design §5.5: IMO / IAU MDC footing). */
interface MeteorShowerCatalog {
    fun showers(): List<MeteorShower>
    val dataVersion: String
}

data class MeteorShower(
    /** IAU three-letter shower code, e.g. "PER". */
    val iauCode: String,
    val nameEn: String,
    val nameZh: String,
    /** Activity window and peak as solar longitudes in degrees (J2000 equinox frame of the table). */
    val activityStartLon: Double,
    val activityEndLon: Double,
    val peakLon: Double,
    /** Peak zenithal hourly rate (order-of-magnitude expectation, not a promise — design §5.5). */
    val peakZhr: Int,
    /** Radiant position at peak: RA in HOURS, Dec in degrees. */
    val radiantRaHours: Double,
    val radiantDecDeg: Double,
    /** Daily radiant drift in RA (hours/day) and Dec (degrees/day). */
    val driftRaPerDay: Double,
    val driftDecPerDay: Double,
    val parent: String?,
    /** Geocentric velocity, km/s. */
    val velocityKmS: Double,
    val populationIndex: Double,
)

/** Built-in lens library (design §2.4). */
interface LensCatalog {
    fun builtInLenses(): List<Lens>
    val dataVersion: String
}

/** One representative bright star of a constellation (design §6.1). */
data class BrightStar(
    val nameZh: String,
    val nameEn: String,
    val magnitude: Double,
)

/**
 * One constellation of the built-in 88-entry editorial table (design
 * §6.1, ui-design §7.8): the geometric centre of the figure and its
 * approximate angular extent are editorial approximations, used only
 * for tonight-list membership, lane drawing at the centre point and
 * the framing derivation — never presented as precise data. The
 * localised constellation names are NOT stored here; they resolve
 * through the UI layer's 88-code name table keyed by [code], so the
 * two can never drift apart (a contract test asserts the alignment).
 */
data class ConstellationEntry(
    /** IAU three-letter code; the primary key. */
    val code: String,
    val centerRaHours: Double,
    val centerDecDeg: Double,
    val sizeMajorDeg: Int,
    val sizeMinorDeg: Int,
    val brightStars: List<BrightStar>,
    val noteZh: String,
    val noteEn: String,
)

interface ConstellationCatalog {
    val dataVersion: String
    fun constellations(): List<ConstellationEntry>
}

/**
 * One wide-field viewpoint (design §6.1, ui-design §7.8): a Milky-Way
 * segment anchored on an in-segment landmark, at 20–40° major-axis
 * scale. Single deep-sky objects and small asterisms are never
 * viewpoints. Anchor coordinates and extents are editorial
 * approximations with the same status as the constellation table's.
 */
data class WideFieldViewpoint(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val anchorRaHours: Double,
    val anchorDecDeg: Double,
    val sizeMajorDeg: Int,
    val sizeMinorDeg: Int,
    val noteZh: String,
    val noteEn: String,
)

interface WideFieldCatalog {
    val dataVersion: String
    fun viewpoints(): List<WideFieldViewpoint>
}

/**
 * One star of the bundled bright-star subset (design §8.3: HYG v4.4,
 * magnitude <= 6.5), the framing chart's point set (ui-design §7.10).
 * Coordinates are J2000 degrees — note the domain's other catalogues
 * speak RA in hours; the star pipeline's product is degrees native
 * and this type keeps it so, conversion happening in the pipeline,
 * never silently at read time.
 */
data class ChartStar(
    /** HYG id — the constellation-line chains key on it. */
    val id: Int,
    val raDeg: Double,
    val decDeg: Double,
    val magnitude: Double,
    /** IAU proper name ("Betelgeuse"), when the star carries one. */
    val properName: String?,
    /** Bayer designation in display form ("χ¹"), when convertible. */
    val bayerName: String?,
    /** Chinese common name, when the editorial tables carry one. */
    val nameZh: String?,
)

/** Bright-star subset port (ui-design §7.10). */
interface StarCatalog {
    fun stars(): List<ChartStar>
    val dataVersion: String
}

/**
 * Constellation line figures as star-id chains (ui-design §7.10):
 * IAU three-letter code -> polylines -> ordered HYG star ids into
 * [StarCatalog]. The figures follow the IAU constellation pages via
 * the d3-celestial line set (BSD 3-Clause, DATA-LICENSES.md).
 */
interface ConstellationLines {
    fun lines(): Map<String, List<List<Int>>>
    val dataVersion: String
}
