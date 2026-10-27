package app.astroexposure.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.astroexposure.AppContainer
import app.astroexposure.R
import app.astroexposure.domain.camera.ApertureStops
import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.catalog.EclipseType
import app.astroexposure.domain.catalog.MeteorShower
import app.astroexposure.domain.ephemeris.EquatorialCoordinates
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.lens.Lens
import app.astroexposure.domain.model.DeepSkyModel
import app.astroexposure.domain.model.DriftModel
import app.astroexposure.domain.model.EclipseModel
import app.astroexposure.domain.model.LandscapeModel
import app.astroexposure.domain.model.MeteorModel
import app.astroexposure.domain.strictness.Strictness
import app.astroexposure.domain.tracking.TrackingKind
import app.astroexposure.domain.tracking.TrackingProfile
import app.astroexposure.domain.units.Degrees
import app.astroexposure.support.SkyContext
import app.astroexposure.support.StrictnessRecommendation
import app.astroexposure.usecase.DeepSkyUseCase
import app.astroexposure.usecase.EclipseUseCase
import app.astroexposure.usecase.WideFieldUseCase
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

enum class CalcCategory { LANDSCAPE, WIDE_FIELD, DEEP_SKY, ECLIPSE, METEOR }

fun categoryNameRes(category: CalcCategory): Int = when (category) {
    CalcCategory.LANDSCAPE -> R.string.cat_landscape
    CalcCategory.WIDE_FIELD -> R.string.cat_wide_field
    CalcCategory.DEEP_SKY -> R.string.cat_deep_sky
    CalcCategory.ECLIPSE -> R.string.cat_eclipse
    CalcCategory.METEOR -> R.string.cat_meteor
}

/**
 * The calculators tab (ui-design §4.3 input flow, a recorded decision):
 * pick the camera first, then a lens FROM THE LIBRARY (no ad-hoc focal
 * entry — the phase-1 focal text field is gone), focal length only as a
 * slider inside a zoom's own range, aperture only via the standard-stop
 * picker starting at the effective maximum aperture. One shared equipment
 * block serves all five calculators.
 */
@Composable
fun CalculatorsScreen(
    container: AppContainer,
    cameras: List<Camera>,
    lenses: List<Lens>,
    profiles: List<TrackingProfile>,
    showers: List<MeteorShower>,
    location: GeoLocation,
    date: LocalDate,
    defaultStrictness: Strictness,
    sampleInstant: Instant,
    skyFor: (EquatorialCoordinates) -> SkyContext,
    onSavePlan: (category: String, title: String, bodyText: String, payloadJson: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var category by remember { mutableStateOf(CalcCategory.WIDE_FIELD) }
    var camera by remember(cameras) { mutableStateOf(cameras.firstOrNull()) }
    var lens by remember(lenses) { mutableStateOf(lenses.firstOrNull()) }
    var focalMm by remember(lens) { mutableStateOf(lens?.focalMinMm ?: 50.0) }
    val maxAperture = lens?.maxApertureAt(focalMm) ?: 2.8
    val apertureScale = remember(maxAperture) { ApertureStops.scaleFrom(maxAperture) }
    var aperture by remember(apertureScale) { mutableStateOf(apertureScale.first()) }
    var profile by remember(profiles) {
        mutableStateOf(profiles.firstOrNull { it.kind == TrackingKind.UNTRACKED } ?: profiles.firstOrNull())
    }
    var strictness by remember { mutableStateOf(defaultStrictness) }
    // A non-standard settings default counts as an explicit choice; the
    // standard default leaves the system recommendation in charge.
    var strictnessTouched by remember {
        mutableStateOf(defaultStrictness != Strictness.STANDARD)
    }

    val recommendation = StrictnessRecommendation.recommend(
        tracked = profile?.kind == TrackingKind.TRACKED,
        driftMeasured = profile?.typicalDrift != null,
        focalMm = focalMm,
        pixelPitchUm = camera?.pixelPitch?.value ?: 5.0,
    )
    val effectiveStrictness = if (strictnessTouched) strictness else recommendation.strictness

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CalcCategory.entries.forEach { cat ->
                FilterChip(
                    selected = category == cat,
                    onClick = { category = cat },
                    label = { Text(stringResource(categoryNameRes(cat))) },
                )
            }
        }

        EquipmentPicker(
            cameras = cameras,
            lenses = lenses,
            camera = camera,
            lens = lens,
            focalMm = focalMm,
            onCamera = { camera = it },
            onLens = {
                lens = it
                focalMm = it.focalMinMm
            },
            onFocal = { focalMm = it },
        )
        if (lens != null) {
            AperturePicker(
                scale = apertureScale,
                selected = aperture,
                onSelect = { aperture = it },
            )
        }
        SelectionMenu(
            label = stringResource(R.string.calc_profile),
            items = profiles,
            selected = profile,
            itemLabel = { it.name },
            onSelect = { profile = it },
        )
        StrictnessSelector(
            value = effectiveStrictness,
            onChange = {
                strictness = it
                strictnessTouched = true
            },
        )
        Text(
            text = stringResource(
                when (recommendation.reason) {
                    StrictnessRecommendation.Reason.UNTRACKED_TIGHT -> R.string.strictness_reason_untracked_tight
                    StrictnessRecommendation.Reason.TRACKED_MEASURED -> R.string.strictness_reason_tracked_measured
                    StrictnessRecommendation.Reason.LARGE_PIXEL_OR_SHORT_FOCAL -> R.string.strictness_reason_large_pixel
                    StrictnessRecommendation.Reason.DEFAULT_STANDARD -> R.string.strictness_reason_default
                },
            ) + " (" + stringResource(
                if (strictnessTouched) R.string.strictness_source_manual
                else R.string.strictness_source_recommended,
            ) + ")",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val cam = camera
        val ln = lens
        val prof = profile
        if (cam != null && ln != null && prof != null) {
            when (category) {
                CalcCategory.WIDE_FIELD -> WideFieldCalculator(
                    container, cam, focalMm, aperture, effectiveStrictness, prof, skyFor, date, onSavePlan,
                )
                CalcCategory.LANDSCAPE -> LandscapeCalculator(
                    container, cam, focalMm, aperture, effectiveStrictness, prof, skyFor, location, date, sampleInstant, onSavePlan,
                )
                CalcCategory.DEEP_SKY -> DeepSkyCalculator(
                    container, cam, focalMm, aperture, effectiveStrictness, prof, skyFor, date, onSavePlan,
                )
                CalcCategory.ECLIPSE -> EclipseCalculator(
                    container, cam, aperture, location, sampleInstant, date, onSavePlan,
                )
                CalcCategory.METEOR -> MeteorCalculator(
                    container, cam, focalMm, aperture, effectiveStrictness, showers, skyFor, location, date, onSavePlan,
                )
            }
        }
    }
}

@Composable
private fun SavePlanButton(enabled: Boolean, onClick: () -> Unit) {
    var saved by remember { mutableStateOf(false) }
    Column {
        ActionButton(
            text = stringResource(R.string.calc_save_plan),
            onClick = {
                onClick()
                saved = true
            },
        )
        if (saved) Text(text = stringResource(R.string.calc_saved))
    }
}

@Composable
private fun WideFieldCalculator(
    container: AppContainer,
    camera: Camera,
    focalMm: Double,
    aperture: Double,
    strictness: Strictness,
    profile: TrackingProfile,
    skyFor: (EquatorialCoordinates) -> SkyContext,
    date: LocalDate,
    onSavePlan: (String, String, String, String) -> Unit,
) {
    var declinationText by remember { mutableStateOf("-29") }
    var card by remember { mutableStateOf<WideFieldUseCase.Card?>(null) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    val context = LocalContext.current

    OutlinedTextField(
        value = declinationText,
        onValueChange = { declinationText = it },
        label = { Text(stringResource(R.string.calc_declination)) },
        modifier = Modifier.fillMaxWidth(),
    )
    ActionButton(text = stringResource(R.string.calc_calculate)) {
        val dec = declinationText.toDoubleOrNull() ?: -29.0
        val sky = skyFor(EquatorialCoordinates(17.75, Degrees(dec)))
        val result = container.wideField.execute(
            WideFieldUseCase.Input(camera, focalMm, aperture, dec, strictness, profile, sky.totalSky),
        )
        card = result
        lines = buildList {
            add("${context.getString(R.string.cat_wide_field)} · ${camera.displayName} · ${focalMm.roundToInt()}mm · ${formatAperture(aperture)}")
            if (!result.tracked) {
                add("${context.getString(R.string.result_shutter)}: ${formatShutter(result.npfSeconds ?: 0.0)}")
                add("ISO ${result.untrackedIso?.low}–${result.untrackedIso?.high}")
            } else if (result.trackedSettings != null) {
                add("${context.getString(R.string.result_shutter)}: ${formatShutter(result.trackedSettings.shutter.value)}")
                add("ISO ${result.trackedSettings.iso}")
            } else {
                add(context.getString(R.string.wide_unmeasured_title))
            }
        }
    }
    val result = card ?: return
    ResultCard(
        title = stringResource(R.string.cat_wide_field),
        badge = stringResource(strictnessNameRes(strictness)),
        warning = if (result.tracked && result.drift is DriftModel.Outcome.Unmeasured) {
            stringResource(R.string.wide_unmeasured_title)
        } else {
            null
        },
    ) {
        if (!result.tracked) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                BigStat(stringResource(R.string.result_shutter), formatShutter(result.npfSeconds ?: 0.0))
                BigStat(stringResource(R.string.result_iso), "${result.untrackedIso?.low}–${result.untrackedIso?.high}")
            }
            Text(
                text = stringResource(
                    R.string.wide_untracked_reason,
                    strictness.npfK, camera.pixelPitch.value, result.ruleOf500Seconds ?: 0.0,
                ),
            )
        } else {
            when (val drift = result.drift) {
                is DriftModel.Outcome.Measured -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        BigStat(
                            stringResource(R.string.result_shutter),
                            formatShutter(result.trackedSettings?.shutter?.value ?: drift.maxExposure.value),
                        )
                        BigStat(stringResource(R.string.result_iso), "${result.trackedSettings?.iso ?: "—"}")
                    }
                    Text(
                        text = stringResource(
                            R.string.wide_tracked_reason,
                            drift.pixelScaleArcsecPerPixel, drift.driftUsed.value,
                            drift.tolerancePx, drift.maxExposure.value,
                            result.trackedSettings?.shutter?.value ?: 0.0,
                        ),
                    )
                }
                is DriftModel.Outcome.Unmeasured -> {
                    FilterChip(
                        selected = true, onClick = {},
                        label = { Text(stringResource(R.string.badge_unmeasured)) },
                    )
                    Text(
                        text = stringResource(
                            R.string.wide_unmeasured_body, drift.pixelScaleArcsecPerPixel,
                        ),
                    )
                }
                null -> Unit
            }
        }
    }
    SavePlanButton(enabled = true) {
        onSavePlan(
            "WIDE_FIELD",
            "${context.getString(R.string.cat_wide_field)} · ${focalMm.roundToInt()}mm",
            lines.joinToString("\n"),
            "{\"focalMm\":$focalMm,\"aperture\":$aperture,\"strictness\":\"${strictness.name}\",\"profileId\":\"${profile.id}\"}",
        )
    }
}

@Composable
private fun LandscapeCalculator(
    container: AppContainer,
    camera: Camera,
    focalMm: Double,
    aperture: Double,
    strictness: Strictness,
    profile: TrackingProfile,
    skyFor: (EquatorialCoordinates) -> SkyContext,
    location: GeoLocation,
    date: LocalDate,
    defaultStrictness: Strictness,
    sampleInstant: Instant,
    onSavePlan: (String, String, String, String) -> Unit,
) {
    var methods by remember {
        mutableStateOf(setOf(LandscapeModel.ForegroundMethod.BLUE_HOUR))
    }
    var result by remember { mutableStateOf<LandscapeModel.Result?>(null) }
    var skyUnmeasured by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    val context = LocalContext.current

    LandscapeModel.ForegroundMethod.entries.forEach { method ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = method in methods,
                onCheckedChange = { checked ->
                    methods = if (checked) methods + method else methods - method
                },
            )
            Text(
                text = stringResource(
                    when (method) {
                        LandscapeModel.ForegroundMethod.BLUE_HOUR -> R.string.landscape_method_blue_hour
                        LandscapeModel.ForegroundMethod.NIGHT_LONG_EXPOSURE -> R.string.landscape_method_night
                        LandscapeModel.ForegroundMethod.HIGH_ISO_STACK -> R.string.landscape_method_stack
                    },
                ),
            )
        }
    }
    ActionButton(text = stringResource(R.string.calc_calculate)) {
        val skyCtx = skyFor(EquatorialCoordinates(17.75, Degrees(-29.0)))
        val wide = container.wideField.execute(
            WideFieldUseCase.Input(
                camera, focalMm, aperture, -29.0, strictness, profile, skyCtx.totalSky,
            ),
        )
        val skyShutter: Double
        val skyIso: Int
        when {
            !wide.tracked -> {
                skyShutter = wide.npfSeconds ?: 0.0
                skyIso = wide.untrackedIso?.low ?: 3200
                skyUnmeasured = false
            }
            wide.trackedSettings != null -> {
                skyShutter = wide.trackedSettings.shutter.value
                skyIso = wide.trackedSettings.iso ?: 800
                skyUnmeasured = false
            }
            else -> {
                // Tracked but unmeasured: the sky frame falls back to the
                // untracked NPF value and the card says so explicitly.
                val npf = container.wideField.execute(
                    WideFieldUseCase.Input(
                        camera, focalMm, aperture, -29.0, strictness,
                        app.astroexposure.domain.tracking.BuiltInTrackingProfiles.UNTRACKED,
                        skyCtx.totalSky,
                    ),
                )
                skyShutter = npf.npfSeconds ?: 0.0
                skyIso = npf.untrackedIso?.low ?: 3200
                skyUnmeasured = true
            }
        }
        val sunset = container.ephemeris.sunEvents(location, date).sunset
        val minutesAfterSunset = sunset?.let {
            java.time.Duration.between(it, sampleInstant).toMinutes().toInt()
                .takeIf { m -> m in 0..90 }
        }
        val landscape = container.landscape.execute(
            app.astroexposure.usecase.LandscapeUseCase.Input(
                skyShutterSeconds = skyShutter,
                skyIso = skyIso,
                skyAperture = aperture,
                skyTracked = profile.kind == TrackingKind.TRACKED && !skyUnmeasured,
                methods = methods,
                minutesAfterSunset = minutesAfterSunset,
                moonIllumination = skyCtx.moonIllumination,
            ),
        )
        result = landscape
        lines = buildList {
            add("${context.getString(R.string.cat_landscape)} · ${camera.displayName} · ${focalMm.roundToInt()}mm")
            add("${context.getString(R.string.landscape_sky_frame)}: ${formatShutter(skyShutter)} · ISO $skyIso · ${formatAperture(aperture)}")
            landscape.foregroundFrames.forEach { frame ->
                add("${frame.method.name}: ISO ${frame.isoRange.first}–${frame.isoRange.last} · ${formatShutter(frame.shutterMinSeconds)}")
            }
        }
    }
    val res = result ?: return
    ResultCard(
        title = stringResource(R.string.cat_landscape),
        badge = stringResource(strictnessNameRes(strictness)),
        warning = if (skyUnmeasured) stringResource(R.string.landscape_sky_unmeasured) else null,
    ) {
        Text(text = stringResource(R.string.landscape_sky_frame), style = MaterialTheme.typography.titleSmall)
        Text(
            text = stringResource(
                R.string.landscape_frame_line,
                res.skyFrame.iso.toString(), "%.1f".format(res.skyFrame.aperture),
                formatShutter(res.skyFrame.shutterSeconds),
            ),
        )
        Text(text = stringResource(R.string.landscape_foreground_frame), style = MaterialTheme.typography.titleSmall)
        res.foregroundFrames.forEach { frame ->
            Text(
                text = stringResource(
                    when (frame.method) {
                        LandscapeModel.ForegroundMethod.BLUE_HOUR -> R.string.landscape_method_blue_hour
                        LandscapeModel.ForegroundMethod.NIGHT_LONG_EXPOSURE -> R.string.landscape_method_night
                        LandscapeModel.ForegroundMethod.HIGH_ISO_STACK -> R.string.landscape_method_stack
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(
                    R.string.landscape_frame_line,
                    "${frame.isoRange.first}–${frame.isoRange.last}",
                    "${frame.apertureRange.start}–${frame.apertureRange.endInclusive}",
                    if (frame.shutterMinSeconds == frame.shutterMaxSeconds) {
                        formatShutter(frame.shutterMinSeconds)
                    } else {
                        "${formatShutter(frame.shutterMinSeconds)} – ${formatShutter(frame.shutterMaxSeconds)}"
                    },
                ),
            )
            frame.frameCountRange?.let {
                Text(text = stringResource(R.string.landscape_stack_frames, it.first, it.last))
            }
        }
        SectionTitle(stringResource(R.string.landscape_workflow_title))
        listOf(
            R.string.landscape_step_1, R.string.landscape_step_2, R.string.landscape_step_3,
            R.string.landscape_step_4, R.string.landscape_step_5,
        ).forEach { Text(text = stringResource(it), style = MaterialTheme.typography.bodySmall) }
        Text(
            text = stringResource(R.string.landscape_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SavePlanButton(enabled = true) {
        onSavePlan(
            "LANDSCAPE",
            "${context.getString(R.string.cat_landscape)} · ${focalMm.roundToInt()}mm",
            lines.joinToString("\n"),
            "{\"focalMm\":$focalMm,\"aperture\":$aperture,\"strictness\":\"${strictness.name}\"}",
        )
    }
}

@Composable
private fun DeepSkyCalculator(
    container: AppContainer,
    camera: Camera,
    focalMm: Double,
    aperture: Double,
    strictness: Strictness,
    profile: TrackingProfile,
    skyFor: (EquatorialCoordinates) -> SkyContext,
    date: LocalDate,
    onSavePlan: (String, String, String, String) -> Unit,
) {
    var targetClass by remember { mutableStateOf(DeepSkyModel.TargetClass.BRIGHT_CLUSTER_NEBULA) }
    var card by remember { mutableStateOf<DeepSkyUseCase.Card?>(null) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    val context = LocalContext.current

    Text(text = stringResource(R.string.deepsky_target_class), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DeepSkyModel.TargetClass.entries.forEach { cls ->
            FilterChip(
                selected = targetClass == cls,
                onClick = { targetClass = cls },
                label = {
                    Text(
                        stringResource(
                            when (cls) {
                                DeepSkyModel.TargetClass.BRIGHT_CLUSTER_NEBULA -> R.string.deepsky_class_bright
                                DeepSkyModel.TargetClass.GENERAL_GALAXY_DARK_NEBULA -> R.string.deepsky_class_general
                                DeepSkyModel.TargetClass.VERY_FAINT -> R.string.deepsky_class_faint
                            },
                        ),
                    )
                },
            )
        }
    }
    ActionButton(text = stringResource(R.string.calc_calculate)) {
        val sky = skyFor(EquatorialCoordinates(17.75, Degrees(-29.0)))
        val result = container.deepSky.execute(
            DeepSkyUseCase.Input(
                camera = camera, aperture = aperture, totalSky = sky.totalSky,
                strictness = strictness, targetClass = targetClass,
                profile = profile, focalMm = focalMm,
            ),
        )
        card = result
        lines = buildList {
            add("${context.getString(R.string.cat_deep_sky)} · ${camera.displayName} · ${formatAperture(aperture)}")
            when (val outcome = result.outcome) {
                is DeepSkyModel.Outcome.Ready -> {
                    add("${context.getString(R.string.deepsky_sub)}: ${formatShutter(outcome.subExposureSeconds)} · ISO ${outcome.recommendedIso} · ${outcome.frameCount} frames")
                }
                is DeepSkyModel.Outcome.Degraded -> add(outcome.methodNote)
            }
        }
    }
    val result = card ?: return
    when (val outcome = result.outcome) {
        is DeepSkyModel.Outcome.Ready -> ResultCard(
            title = stringResource(R.string.cat_deep_sky),
            badge = stringResource(strictnessNameRes(strictness)),
            warning = when {
                outcome.unverifiedGainFlag -> stringResource(R.string.deepsky_unverified_gain)
                outcome.belowGainPenaltyFlag -> stringResource(R.string.deepsky_below_gain)
                else -> null
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                BigStat(stringResource(R.string.deepsky_sub), formatShutter(outcome.subExposureSeconds))
                BigStat(stringResource(R.string.result_iso), outcome.recommendedIso.toString())
                BigStat(stringResource(R.string.result_frames), outcome.frameCount.toString())
            }
            Text(
                text = stringResource(R.string.deepsky_other_c, outcome.otherC, outcome.subExposureOtherCSeconds),
            )
            Text(
                text = if (outcome.dataGb != null) {
                    stringResource(
                        R.string.deepsky_total,
                        outcome.totalHoursRepresentative, outcome.totalHoursMin,
                        outcome.totalHoursMax, outcome.frameCount, "%.1f".format(outcome.dataGb),
                    )
                } else {
                    stringResource(
                        R.string.deepsky_total_no_data,
                        outcome.totalHoursRepresentative, outcome.totalHoursMin,
                        outcome.totalHoursMax, outcome.frameCount,
                    )
                },
            )
            if (DeepSkyModel.CapReason.TRACKING_LIMIT in outcome.capReasons) {
                Text(text = stringResource(R.string.deepsky_cap_tracking))
            }
            if (DeepSkyModel.CapReason.SINGLE_FRAME_CEILING in outcome.capReasons) {
                Text(text = stringResource(R.string.deepsky_cap_ceiling))
            }
            if (result.trackingUnmeasured) {
                Text(text = stringResource(R.string.deepsky_tracking_unmeasured))
            }
            Text(
                text = outcome.skyUncertaintyNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is DeepSkyModel.Outcome.Degraded -> ResultCard(
            title = stringResource(R.string.cat_deep_sky),
            badge = stringResource(strictnessNameRes(strictness)),
            warning = stringResource(R.string.deepsky_degraded_title),
        ) {
            Text(text = outcome.methodNote)
        }
    }
    SavePlanButton(enabled = true) {
        onSavePlan(
            "DEEP_SKY",
            "${context.getString(R.string.cat_deep_sky)} · ${camera.displayName}",
            lines.joinToString("\n"),
            "{\"aperture\":$aperture,\"strictness\":\"${strictness.name}\",\"targetClass\":\"${targetClass.name}\"}",
        )
    }
}

@Composable
private fun EclipseCalculator(
    container: AppContainer,
    camera: Camera,
    aperture: Double,
    location: GeoLocation,
    sampleInstant: Instant,
    date: LocalDate,
    onSavePlan: (String, String, String, String) -> Unit,
) {
    var solarMode by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    var eclipseType by remember { mutableStateOf(EclipseType.TOTAL) }
    var isoIndex by remember { mutableStateOf(0) }
    var moonCard by remember { mutableStateOf<EclipseUseCase.MoonCard?>(null) }
    var solarCard by remember { mutableStateOf<EclipseUseCase.SolarCard?>(null) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    val context = LocalContext.current
    val isoOptions = listOf(100, 200, 400, 800, 1600, 3200)

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = !solarMode, onClick = { solarMode = false },
            label = { Text(stringResource(R.string.eclipse_mode_moon)) },
        )
        FilterChip(
            selected = solarMode, onClick = {
                solarMode = true
                acknowledged = false // the gate is confirmed on EVERY entry (design §5.4.2)
                solarCard = null
            },
            label = { Text(stringResource(R.string.eclipse_mode_solar)) },
        )
    }
    Text(text = stringResource(R.string.eclipse_iso), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        isoOptions.forEachIndexed { index, iso ->
            FilterChip(
                selected = isoIndex == index,
                onClick = { isoIndex = index },
                label = { Text(iso.toString()) },
            )
        }
    }
    val iso = isoOptions[isoIndex]

    if (!solarMode) {
        ActionButton(text = stringResource(R.string.calc_calculate)) {
            val cardResult = container.eclipse.moon(location, sampleInstant, iso, aperture)
            moonCard = cardResult
            lines = listOf(
                "${context.getString(R.string.eclipse_mode_moon)} · ISO $iso · ${formatAperture(aperture)}",
                "${context.getString(R.string.result_shutter)}: ${formatShutter(cardResult.result.shutter.value)}",
            )
        }
        val cardResult = moonCard
        if (cardResult != null) {
            ResultCard(
                title = stringResource(R.string.eclipse_mode_moon),
                badge = null,
                warning = null,
            ) {
                BigStat(
                    stringResource(R.string.result_shutter),
                    formatShutter(cardResult.result.shutter.value),
                )
                Text(
                    text = stringResource(
                        R.string.moon_reason,
                        cardResult.result.phaseCorrectionStops,
                        Math.round(cardResult.illumination * 100),
                        if (cardResult.result.lowAltitudeCorrectionApplied) {
                            stringResource(R.string.moon_low_altitude_note)
                        } else {
                            ""
                        },
                    ),
                )
                Text(text = stringResource(R.string.moon_altitude, cardResult.moonAltitudeDeg))
            }
            SavePlanButton(enabled = true) {
                onSavePlan(
                    "ECLIPSE", context.getString(R.string.eclipse_mode_moon),
                    lines.joinToString("\n"),
                    "{\"mode\":\"MOON\",\"iso\":$iso,\"aperture\":$aperture}",
                )
            }
        }
    } else {
        // Safety gate first: no parameters are computed or shown before the
        // acknowledgement (design §5.4.2, SafetyGate in the domain model).
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.safety_gate_title),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = stringResource(R.string.safety_gate_body))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text(text = stringResource(R.string.safety_gate_checkbox))
                }
                if (!acknowledged) {
                    Text(
                        text = stringResource(R.string.safety_gate_blocked),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Text(text = stringResource(R.string.eclipse_type_label), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                EclipseType.TOTAL to R.string.eclipse_type_total,
                EclipseType.ANNULAR to R.string.eclipse_type_annular,
                EclipseType.PARTIAL to R.string.eclipse_type_partial,
                EclipseType.HYBRID to R.string.eclipse_type_hybrid,
            ).forEach { (type, res) ->
                FilterChip(
                    selected = eclipseType == type,
                    onClick = { eclipseType = type },
                    label = { Text(stringResource(res)) },
                )
            }
        }
        ActionButton(text = stringResource(R.string.calc_calculate)) {
            val cardResult = container.eclipse.solar(eclipseType, aperture, iso, acknowledged)
            solarCard = cardResult
            lines = buildList {
                add("${context.getString(R.string.cat_eclipse)} · ${eclipseType.name} · ISO $iso · ${formatAperture(aperture)}")
                cardResult.rows.forEach { row ->
                    add("${row.phenomenon.name}: ${row.shutterSeconds?.let { formatShutter(it) } ?: "-"}")
                }
            }
        }
        val cardResult = solarCard
        if (cardResult != null && !cardResult.gate.blocked) {
            ResultCard(
                title = stringResource(R.string.eclipse_mode_solar),
                badge = null,
                warning = if (cardResult.filterLocked) {
                    stringResource(R.string.eclipse_filter_locked)
                } else {
                    null
                },
            ) {
                cardResult.rows.forEach { row ->
                    Text(
                        text = stringResource(
                            R.string.eclipse_row_line,
                            stringResource(phenomenonRes(row.phenomenon)),
                            row.shutterSeconds?.let { formatShutter(it) }
                                ?: stringResource(R.string.eclipse_no_value),
                            stringResource(
                                when (row.filterRequirement) {
                                    EclipseModel.FilterRequirement.REQUIRED_ND5 -> R.string.eclipse_filter_required
                                    EclipseModel.FilterRequirement.NONE_TOTALITY_ONLY -> R.string.eclipse_filter_totality
                                    EclipseModel.FilterRequirement.TRANSITION_SECONDS -> R.string.eclipse_filter_transition
                                },
                            ),
                        ),
                    )
                }
                if (cardResult.totalityBracketSeconds.isNotEmpty() && !cardResult.filterLocked) {
                    SectionTitle(stringResource(R.string.eclipse_bracket_title))
                    Text(
                        text = cardResult.totalityBracketSeconds.joinToString(" · ") { formatShutter(it) },
                    )
                }
                Text(
                    text = stringResource(R.string.eclipse_no_local_contacts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SavePlanButton(enabled = true) {
                onSavePlan(
                    "ECLIPSE",
                    "${context.getString(R.string.eclipse_mode_solar)} · ${eclipseType.name}",
                    lines.joinToString("\n"),
                    "{\"mode\":\"SOLAR\",\"type\":\"${eclipseType.name}\",\"iso\":$iso,\"aperture\":$aperture}",
                )
            }
        }
    }
}

private fun phenomenonRes(phenomenon: EclipseModel.Phenomenon): Int = when (phenomenon) {
    EclipseModel.Phenomenon.PARTIAL_PHASE -> R.string.phenomenon_partial
    EclipseModel.Phenomenon.DIAMOND_RING -> R.string.phenomenon_diamond
    EclipseModel.Phenomenon.BAILY_BEADS -> R.string.phenomenon_baily
    EclipseModel.Phenomenon.CHROMOSPHERE -> R.string.phenomenon_chromosphere
    EclipseModel.Phenomenon.PROMINENCE -> R.string.phenomenon_prominence
    EclipseModel.Phenomenon.CORONA_0_5_RS -> R.string.phenomenon_corona_05
    EclipseModel.Phenomenon.CORONA_1_0_RS -> R.string.phenomenon_corona_10
    EclipseModel.Phenomenon.CORONA_2_0_RS -> R.string.phenomenon_corona_20
    EclipseModel.Phenomenon.CORONA_4_0_RS -> R.string.phenomenon_corona_40
    EclipseModel.Phenomenon.CORONA_8_0_RS -> R.string.phenomenon_corona_80
}

@Composable
private fun MeteorCalculator(
    container: AppContainer,
    camera: Camera,
    focalMm: Double,
    aperture: Double,
    strictness: Strictness,
    showers: List<MeteorShower>,
    skyFor: (EquatorialCoordinates) -> SkyContext,
    location: GeoLocation,
    date: LocalDate,
    onSavePlan: (String, String, String, String) -> Unit,
) {
    var shower by remember(showers) {
        mutableStateOf(showers.firstOrNull { it.iauCode == "PER" } ?: showers.firstOrNull())
    }
    var hours by remember { mutableStateOf(3.0f) }
    var card by remember { mutableStateOf<app.astroexposure.usecase.MeteorUseCase.Card?>(null) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    val context = LocalContext.current

    SelectionMenu(
        label = stringResource(R.string.meteor_shower_label),
        items = showers,
        selected = shower,
        itemLabel = { "${it.nameZh} (${it.iauCode})" },
        onSelect = { shower = it },
    )
    Text(
        text = stringResource(R.string.meteor_hours) + " · ${hours.roundToInt()}",
        style = MaterialTheme.typography.labelLarge,
    )
    Slider(value = hours, onValueChange = { hours = it }, valueRange = 1f..8f, steps = 6)
    val selectedShower = shower ?: return
    ActionButton(text = stringResource(R.string.calc_calculate)) {
        val radiant = EquatorialCoordinates(
            selectedShower.radiantRaHours, Degrees(selectedShower.radiantDecDeg),
        )
        val sky = skyFor(radiant)
        val result = container.meteor.execute(
            app.astroexposure.usecase.MeteorUseCase.Input(
                shower = selectedShower, camera = camera, focalMm = focalMm,
                aperture = aperture, totalSky = sky.totalSky, strictness = strictness,
                shootingHours = hours.toDouble(), location = location, date = date,
            ),
        )
        card = result
        lines = listOf(
            "${context.getString(R.string.cat_meteor)} · ${selectedShower.nameZh} · ${focalMm.roundToInt()}mm · ${formatAperture(aperture)}",
            "${context.getString(R.string.meteor_single)}: ${formatShutter(result.result.singleFrameSeconds)} · ISO ${result.result.iso} · ${result.result.frameCount} frames",
        )
    }
    val result = card ?: return
    val res = result.result
    ResultCard(
        title = stringResource(R.string.cat_meteor),
        badge = stringResource(strictnessNameRes(strictness)),
        warning = when (res.moonVerdict) {
            MeteorModel.MoonVerdict.SEVERE -> stringResource(R.string.meteor_moon_severe)
            MeteorModel.MoonVerdict.MODERATE -> stringResource(R.string.meteor_moon_moderate)
            MeteorModel.MoonVerdict.LOW -> null
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            BigStat(stringResource(R.string.meteor_single), formatShutter(res.singleFrameSeconds))
            BigStat(stringResource(R.string.result_iso), "${res.iso ?: "—"}")
            BigStat(stringResource(R.string.result_frames), res.frameCount.toString())
        }
        Text(
            text = stringResource(
                when (res.limitingFactor) {
                    MeteorModel.LimitingFactor.TRAILING -> R.string.meteor_limit_trailing
                    MeteorModel.LimitingFactor.SKY_BRIGHTNESS -> R.string.meteor_limit_sky
                },
            ),
        )
        if (res.skyCapUnavailable) {
            Text(text = stringResource(R.string.meteor_sky_cap_unavailable))
        }
        Text(
            text = if (res.dataGb != null) {
                stringResource(R.string.meteor_budget, res.frameCount, "%.1f".format(res.dataGb))
            } else {
                stringResource(R.string.meteor_budget_no_data, res.frameCount)
            },
        )
        if (res.moonVerdict == MeteorModel.MoonVerdict.LOW) {
            Text(text = stringResource(R.string.meteor_moon_low))
        }
        Text(
            text = stringResource(
                R.string.meteor_activity,
                stringResource(
                    when (res.activityLevel) {
                        MeteorModel.ActivityLevel.PEAK -> R.string.meteor_activity_peak
                        MeteorModel.ActivityLevel.NEAR -> R.string.meteor_activity_near
                        MeteorModel.ActivityLevel.OFF_PEAK -> R.string.meteor_activity_off
                    },
                ),
                result.approxPeakDate.toString(),
                kotlin.math.abs(result.daysFromPeak),
            ),
        )
        val pass = result.radiantPass
        if (pass != null) {
            Text(
                text = stringResource(
                    R.string.meteor_radiant,
                    formatTime(pass.culmination, location.zone),
                    pass.culminationAltitude.value,
                ),
            )
        } else {
            Text(text = stringResource(R.string.meteor_radiant_none))
        }
    }
    SavePlanButton(enabled = true) {
        onSavePlan(
            "METEOR",
            "${context.getString(R.string.cat_meteor)} · ${selectedShower.nameZh}",
            lines.joinToString("\n"),
            "{\"shower\":\"${selectedShower.iauCode}\",\"focalMm\":$focalMm,\"aperture\":$aperture,\"hours\":${hours.toDouble()}}",
        )
    }
}
