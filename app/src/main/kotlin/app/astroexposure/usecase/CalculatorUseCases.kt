package app.astroexposure.usecase

import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.catalog.EclipseType
import app.astroexposure.domain.catalog.MeteorShower
import app.astroexposure.domain.ephemeris.CelestialBody
import app.astroexposure.domain.ephemeris.EphemerisProvider
import app.astroexposure.domain.ephemeris.EquatorialCoordinates
import app.astroexposure.domain.ephemeris.TargetPass
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.model.DeepSkyModel
import app.astroexposure.domain.model.DriftModel
import app.astroexposure.domain.model.EclipseModel
import app.astroexposure.domain.model.LandscapeModel
import app.astroexposure.domain.model.MeteorModel
import app.astroexposure.domain.model.MoonModel
import app.astroexposure.domain.model.NpfModel
import app.astroexposure.domain.model.SafetyGate
import app.astroexposure.domain.model.WideFieldModel
import app.astroexposure.domain.sky.TotalSky
import app.astroexposure.domain.strictness.Strictness
import app.astroexposure.domain.tracking.TrackingKind
import app.astroexposure.domain.tracking.TrackingProfile
import app.astroexposure.domain.units.Degrees
import app.astroexposure.domain.units.Seconds
import app.astroexposure.support.ShowerDates
import java.time.LocalDate

/**
 * Phase-2 calculator use cases, covered by straight-through tests. Each
 * use case only assembles domain models and port data — no
 * formula lives here (DEVELOPMENT-RULES.md D.4) and nothing reads the clock,
 * GPS or files; the UI supplies every instant and location explicitly.
 */

// --- Wide field (design §5.2) ----------------------------------------------

class WideFieldUseCase {

    data class Input(
        val camera: Camera,
        val focalMm: Double,
        val aperture: Double,
        val declinationDeg: Double,
        val strictness: Strictness,
        val profile: TrackingProfile,
        val totalSky: TotalSky,
    )

    data class Card(
        val tracked: Boolean,
        /** NPF longest shutter, untracked mode only. */
        val npfSeconds: Double?,
        val ruleOf500Seconds: Double?,
        val npfDeclinationCapped: Boolean,
        /** Empirical untracked ISO range, untracked mode only. */
        val untrackedIso: WideFieldModel.IsoRange?,
        /** Drift outcome, tracked mode only; Unmeasured carries formula data, never seconds. */
        val drift: DriftModel.Outcome?,
        /** Tidy tracked shutter + gain-adsorbed ISO, only when drift was measured. */
        val trackedSettings: WideFieldModel.TrackedSettings?,
        val pixelScaleArcsecPerPixel: Double,
    )

    fun execute(input: Input): Card {
        val pixelScale = DriftModel.pixelScaleArcsecPerPixel(input.camera, input.focalMm)
        if (input.profile.kind == TrackingKind.UNTRACKED) {
            val npf = NpfModel.evaluate(
                NpfModel.Input(
                    camera = input.camera,
                    focalLengthMm = input.focalMm,
                    aperture = input.aperture,
                    declination = Degrees(input.declinationDeg),
                    strictness = input.strictness,
                ),
            )
            return Card(
                tracked = false,
                npfSeconds = npf.maxShutter.value,
                ruleOf500Seconds = npf.ruleOf500.value,
                npfDeclinationCapped = npf.declinationCapped,
                untrackedIso = WideFieldModel.untrackedIso(input.totalSky, input.camera),
                drift = null,
                trackedSettings = null,
                pixelScaleArcsecPerPixel = pixelScale,
            )
        }
        val drift = DriftModel.maxExposure(
            input.camera, input.focalMm, input.profile, input.strictness,
        )
        val trackedSettings = (drift as? DriftModel.Outcome.Measured)?.let {
            WideFieldModel.trackedSettings(input.camera, it.maxExposure)
        }
        return Card(
            tracked = true,
            npfSeconds = null,
            ruleOf500Seconds = null,
            npfDeclinationCapped = false,
            untrackedIso = null,
            drift = drift,
            trackedSettings = trackedSettings,
            pixelScaleArcsecPerPixel = pixelScale,
        )
    }
}

// --- Landscape with sky (design §5.1) ----------------------------------------

class LandscapeUseCase {

    data class Input(
        /** Sky frame already computed by [WideFieldUseCase] (the models are not re-run here). */
        val skyShutterSeconds: Double,
        val skyIso: Int,
        val skyAperture: Double,
        val skyTracked: Boolean,
        val methods: Set<LandscapeModel.ForegroundMethod>,
        val minutesAfterSunset: Int?,
        val moonIllumination: Double?,
    )

    fun execute(input: Input): LandscapeModel.Result = LandscapeModel.evaluate(
        LandscapeModel.Input(
            sky = LandscapeModel.SkyFrameSnapshot(
                shutterSeconds = input.skyShutterSeconds,
                iso = input.skyIso,
                aperture = input.skyAperture,
                tracked = input.skyTracked,
            ),
            methods = input.methods,
            minutesAfterSunset = input.minutesAfterSunset,
            moonIllumination = input.moonIllumination,
        ),
    )
}

// --- Deep sky (design §5.3) ----------------------------------------------------

class DeepSkyUseCase {

    data class Input(
        val camera: Camera,
        val aperture: Double,
        val totalSky: TotalSky,
        val strictness: Strictness,
        val targetClass: DeepSkyModel.TargetClass,
        /** When set with [focalMm], the drift cap is derived from the profile. */
        val profile: TrackingProfile?,
        val focalMm: Double?,
        val chosenIso: Int? = null,
    )

    data class Card(
        val outcome: DeepSkyModel.Outcome,
        /** True when the profile is tracked but unmeasured, so no cap could be applied. */
        val trackingUnmeasured: Boolean,
    )

    fun execute(input: Input): Card {
        var cap: Seconds? = null
        var unmeasured = false
        val profile = input.profile
        val focal = input.focalMm
        if (profile != null && focal != null && profile.kind == TrackingKind.TRACKED) {
            when (val drift = DriftModel.maxExposure(input.camera, focal, profile, input.strictness)) {
                is DriftModel.Outcome.Measured -> cap = drift.maxExposure
                is DriftModel.Outcome.Unmeasured -> unmeasured = true
            }
        }
        val outcome = DeepSkyModel.evaluate(
            DeepSkyModel.Input(
                camera = input.camera,
                aperture = input.aperture,
                totalSky = input.totalSky,
                strictness = input.strictness,
                trackingCapSeconds = cap,
                targetClass = input.targetClass,
                chosenIso = input.chosenIso,
            ),
        )
        return Card(outcome = outcome, trackingUnmeasured = unmeasured)
    }
}

// --- Moon & eclipse (design §5.4) ------------------------------------------------

class EclipseUseCase(private val ephemeris: EphemerisProvider) {

    data class MoonCard(
        val result: MoonModel.Result,
        val illumination: Double,
        val moonAltitudeDeg: Double,
    )

    fun moon(location: GeoLocation, instant: java.time.Instant, iso: Int, aperture: Double): MoonCard {
        val illumination = ephemeris.moonIllumination(instant)
        val altitude = ephemeris.horizontalPosition(location, CelestialBody.MOON, instant).altitude
        val result = MoonModel.evaluate(
            MoonModel.Input(
                iso = iso,
                aperture = aperture,
                illumination = illumination,
                moonAltitude = altitude,
            ),
        )
        return MoonCard(result = result, illumination = illumination, moonAltitudeDeg = altitude.value)
    }

    data class SolarRow(
        val phenomenon: EclipseModel.Phenomenon,
        /** Null when the table records no value for this row/aperture. */
        val shutterSeconds: Double?,
        val filterRequirement: EclipseModel.FilterRequirement,
    )

    data class SolarCard(
        val gate: SafetyGate.GateResult,
        /** Empty while the gate is blocked: parameters are not computed for display. */
        val rows: List<SolarRow>,
        val totalityBracketSeconds: List<Double>,
        val filterLocked: Boolean,
    )

    fun solar(
        eclipseType: EclipseType,
        aperture: Double,
        iso: Int,
        acknowledged: Boolean,
    ): SolarCard {
        val gate = SafetyGate.evaluate(acknowledged, eclipseType)
        val table = EclipseModel.tableFor(eclipseType)
        val rows = if (gate.blocked) {
            emptyList()
        } else {
            table.rows.map { phenomenon ->
                SolarRow(
                    phenomenon = phenomenon,
                    shutterSeconds = EclipseModel.shutterFor(phenomenon, aperture, iso)?.value,
                    filterRequirement = EclipseModel.filterRequirement(phenomenon),
                )
            }
        }
        return SolarCard(
            gate = gate,
            rows = rows,
            totalityBracketSeconds = if (gate.blocked) {
                emptyList()
            } else {
                EclipseModel.totalityBracket().map { it.value }
            },
            filterLocked = table.lockedToFilter,
        )
    }
}

// --- Meteor showers (design §5.5) -------------------------------------------------

class MeteorUseCase(private val ephemeris: EphemerisProvider) {

    data class Input(
        val shower: MeteorShower,
        val camera: Camera,
        val focalMm: Double,
        val aperture: Double,
        val totalSky: TotalSky,
        val strictness: Strictness,
        val shootingHours: Double,
        val location: GeoLocation,
        val date: LocalDate,
    )

    data class Card(
        val result: MeteorModel.Result,
        /** Signed days from the approximate peak date (ShowerDates approximation). */
        val daysFromPeak: Double,
        val approxPeakDate: LocalDate,
        val radiantPass: TargetPass?,
    )

    fun execute(input: Input): Card {
        // Sample the shooting window at 23:00 local civil time.
        val sample = input.date.atTime(23, 0).atZone(input.location.zone).toInstant()
        val illumination = ephemeris.moonIllumination(sample)
        val moonUp = ephemeris.horizontalPosition(
            input.location, CelestialBody.MOON, sample,
        ).altitude.value > 0.0
        val daysFromPeak = ShowerDates.daysFromPeak(input.shower, input.date)
        val radiant = EquatorialCoordinates(
            raHours = input.shower.radiantRaHours + input.shower.driftRaPerDay * daysFromPeak,
            declination = Degrees(input.shower.radiantDecDeg + input.shower.driftDecPerDay * daysFromPeak),
        )
        val result = MeteorModel.evaluate(
            MeteorModel.Input(
                shower = input.shower,
                camera = input.camera,
                focalMm = input.focalMm,
                aperture = input.aperture,
                totalSky = input.totalSky,
                strictness = input.strictness,
                shootingHours = input.shootingHours,
                moonIllumination = illumination,
                moonUp = moonUp,
                daysFromPeak = daysFromPeak,
            ),
        )
        return Card(
            result = result,
            daysFromPeak = daysFromPeak,
            approxPeakDate = ShowerDates.approxDate(input.shower.peakLon, input.date),
            radiantPass = ephemeris.targetPass(input.location, radiant, input.date, Degrees(30.0)),
        )
    }
}
