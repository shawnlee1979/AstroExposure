package app.astroexposure

import android.Manifest
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.catalog.DeepSkyObject
import app.astroexposure.domain.ephemeris.EquatorialCoordinates
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.geo.SiteTimeZone
import app.astroexposure.domain.lens.Lens
import app.astroexposure.domain.timeline.TonightDateWindow
import app.astroexposure.domain.strictness.Strictness
import app.astroexposure.domain.tracking.TrackingProfile
import app.astroexposure.domain.units.ArcsecPerMinute
import app.astroexposure.domain.units.Degrees
import app.astroexposure.location.LocatedSite
import app.astroexposure.location.LocationSource
import app.astroexposure.persistence.AppRepository
import app.astroexposure.persistence.SavedPlace
import app.astroexposure.persistence.SavedPlan
import app.astroexposure.support.LibraryTransfer
import app.astroexposure.support.PlanTextExport
import app.astroexposure.support.SkyAssembly
import app.astroexposure.support.SkyContext
import app.astroexposure.support.TimeHealthCheck
import app.astroexposure.ui.CalcCategory
import app.astroexposure.ui.CalculatorsScreen
import app.astroexposure.ui.PlansScreen
import app.astroexposure.ui.SettingsScreen
import app.astroexposure.ui.TonightScreen
import app.astroexposure.ui.theme.AstroTheme
import app.astroexposure.usecase.TonightOverviewUseCase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase-2 application shell: bottom four tabs (Tonight / Calculate /
 * Plans / Settings, ui-design §3). The device clock and GPS are read HERE,
 * at the application boundary, and passed down as values — the domain and
 * the ports never read them (design §4.1, DEVELOPMENT-RULES.md D.2).
 */
class MainActivity : AppCompatActivity() {

    private val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AstroApp(container)
        }
    }
}

private enum class Tab { TONIGHT, CALCULATE, PLANS, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AstroApp(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = container.repository

    var tab by rememberSaveable { mutableStateOf(Tab.TONIGHT) }
    var calcCategory by remember { mutableStateOf(CalcCategory.WIDE_FIELD) }
    // A catalogue target handed over from the tonight page's detail
    // dialog (ui-design §7.5): the calculators screen consumes it once
    // to prefill the declination field.
    var calcPrefill by remember { mutableStateOf<DeepSkyObject?>(null) }
    var redLight by rememberSaveable { mutableStateOf(false) }
    var redLightAuto by rememberSaveable { mutableStateOf(false) }
    var defaultStrictness by rememberSaveable { mutableStateOf(Strictness.STANDARD) }

    var cameras by remember { mutableStateOf<List<Pair<Camera, Boolean>>>(emptyList()) }
    var lenses by remember { mutableStateOf<List<Pair<Lens, Boolean>>>(emptyList()) }
    var profiles by remember { mutableStateOf<List<TrackingProfile>>(emptyList()) }
    var places by remember { mutableStateOf<List<SavedPlace>>(emptyList()) }
    var plans by remember { mutableStateOf<List<SavedPlan>>(emptyList()) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    var site by remember {
        mutableStateOf(
            LocatedSite(AppContainer.DEFAULT_LOCATION, LocationSource.SAMPLE, null),
        )
    }
    var manualBortle by remember { mutableStateOf<Int?>(null) }
    var gpsFailed by remember { mutableStateOf(false) }
    var healthTick by remember { mutableStateOf(0) }
    var gpsTime by remember { mutableStateOf<Instant?>(null) }

    val location = site.location
    // "Tonight" anchor (ui-design §7.3): read at this boundary in the
    // site's zone and refreshed on resume (healthTick) and on site
    // changes. The selected Tonight date lives beside it; calculation
    // tabs keep using the anchor.
    val today = remember(location.zone, healthTick) { LocalDate.now(location.zone) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val tonightDate = TonightDateWindow.clamp(today, selectedDate ?: today)

    suspend fun reloadLibrary() {
        cameras = repo.cameras()
        lenses = repo.lenses()
        profiles = repo.trackingProfiles()
        places = repo.places()
        plans = repo.plans()
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { repo.seedIfEmpty() }
        val settings = withContext(Dispatchers.IO) { repo.settings() }
        settings[AppRepository.KEY_DEFAULT_STRICTNESS]?.let { name ->
            runCatching { Strictness.valueOf(name) }.getOrNull()?.let { defaultStrictness = it }
        }
        redLightAuto = settings[AppRepository.KEY_RED_LIGHT_AUTO] == "true"
        withContext(Dispatchers.IO) { reloadLibrary() }
        // If a last-known GPS fix exists, adopt it silently as the GPS channel.
        container.locationProvider.lastKnown(ZoneId.systemDefault())?.let {
            site = it
            gpsTime = it.gpsTime
        }
    }

    // Time health + auto red light are evaluated on every resume (design §2.2/§7.4).
    // The observer is registered once, so it must read the CURRENT site
    // through an updated-state handle: capturing the composition's first
    // `location` value would judge the auto red light by the initial
    // sample site's sunset forever, whatever place is selected later.
    val currentLocation by rememberUpdatedState(location)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                healthTick++
                if (redLightAuto) {
                    val siteNow = currentLocation
                    val sunset = container.ephemeris
                        .sunEvents(siteNow, LocalDate.now(siteNow.zone)).sunset
                    if (sunset != null && Instant.now().isAfter(sunset)) {
                        redLight = true
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val timeHealth = remember(healthTick, location, gpsTime) {
        TimeHealthCheck.evaluate(
            systemZone = ZoneId.systemDefault(),
            locationZone = location.zone,
            systemNow = Instant.now(),
            gpsTime = gpsTime,
        )
    }

    var overview by remember { mutableStateOf<TonightOverviewUseCase.Result?>(null) }
    LaunchedEffect(location, tonightDate, manualBortle) {
        overview = withContext(Dispatchers.Default) {
            container.tonightOverview.execute(location, tonightDate, manualBortle)
        }
    }
    val sampleInstant = overview?.night?.dusk ?: Instant.now()
    val skyFor: (EquatorialCoordinates) -> SkyContext = remember(location, manualBortle, sampleInstant) {
        { target ->
            SkyAssembly.compose(
                container.ephemeris, container.lightPollution, location,
                sampleInstant, target, manualBortle,
            )
        }
    }

    // --- Location channels -------------------------------------------------
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            container.locationProvider.requestSingle(ZoneId.systemDefault()) { fix ->
                if (fix != null) {
                    site = fix
                    gpsTime = fix.gpsTime
                    gpsFailed = false
                    manualBortle = null
                } else {
                    gpsFailed = true
                }
            }
        } else {
            gpsFailed = true
        }
    }
    val useGps: () -> Unit = {
        if (container.locationProvider.hasPermission()) {
            container.locationProvider.requestSingle(ZoneId.systemDefault()) { fix ->
                if (fix != null) {
                    site = fix
                    gpsTime = fix.gpsTime
                    gpsFailed = false
                    manualBortle = null
                } else {
                    gpsFailed = true
                }
            }
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // Entering the Tonight tab requests a fresh GPS fix (ui-design
    // §7.2), once per entry; a last-known fix may already be in place as
    // an interim. Leaving the tab re-arms the next entry.
    var autoLocated by remember { mutableStateOf(false) }
    LaunchedEffect(tab) {
        if (tab == Tab.TONIGHT && !autoLocated) {
            autoLocated = true
            useGps()
        }
        if (tab != Tab.TONIGHT) {
            autoLocated = false
        }
    }

    // --- Plan export / import (thin intent wrappers) -------------------------
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportTextLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val text = pendingExport
        if (uri != null && text != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }
    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val text = pendingExport
        if (uri != null && text != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                }
                when (val result = text?.let { LibraryTransfer.import(it) }) {
                    is LibraryTransfer.ImportResult.Ok -> {
                        val backup = result.backup
                        withContext(Dispatchers.IO) {
                            backup.cameras.forEach {
                                repo.upsertCamera(it, it.id in backup.customCameraIds)
                            }
                            backup.lenses.forEach {
                                repo.upsertLens(it, it.id in backup.customLensIds)
                            }
                            backup.trackingProfiles.forEach { repo.upsertProfile(it) }
                            backup.places.forEach { repo.upsertPlace(it) }
                            backup.plans.forEach { repo.addPlan(it) }
                            reloadLibrary()
                        }
                        importMessage = context.getString(
                            R.string.plans_import_done,
                            backup.cameras.size + backup.lenses.size +
                                backup.trackingProfiles.size + backup.places.size +
                                backup.plans.size,
                            backup.skippedEntries,
                        )
                    }
                    else -> importMessage = context.getString(R.string.plans_import_failed)
                }
            }
        }
    }

    AstroTheme(redLight = redLight) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(stringResource(R.string.app_name)) })
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.TONIGHT,
                        onClick = { tab = Tab.TONIGHT },
                        icon = {},
                        label = { Text(stringResource(R.string.tab_tonight)) },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.CALCULATE,
                        onClick = { tab = Tab.CALCULATE },
                        icon = {},
                        label = { Text(stringResource(R.string.tab_calculate)) },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.PLANS,
                        onClick = { tab = Tab.PLANS },
                        icon = {},
                        label = { Text(stringResource(R.string.tab_plans)) },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.SETTINGS,
                        onClick = { tab = Tab.SETTINGS },
                        icon = {},
                        label = { Text(stringResource(R.string.tab_settings)) },
                    )
                }
            },
        ) { padding ->
            when (tab) {
                Tab.TONIGHT -> overview?.let { result ->
                    TonightScreen(
                        overview = result,
                        date = tonightDate,
                        anchorDate = today,
                        zone = location.zone,
                        deviceZone = ZoneId.systemDefault(),
                        latitudeDeg = location.latitude.value,
                        longitudeDeg = location.longitude.value,
                        locationLabel = location.label,
                        locationSourceText = stringResource(
                            when (site.source) {
                                LocationSource.GPS -> R.string.location_source_gps
                                LocationSource.MANUAL -> R.string.location_source_manual
                                LocationSource.FAVOURITE -> R.string.location_source_favourite
                                LocationSource.SAMPLE -> R.string.location_source_sample
                            },
                        ) + if (gpsFailed) " · " + stringResource(R.string.location_gps_failed) else "",
                        timeHealth = timeHealth,
                        redLight = redLight,
                        onRedLightChange = { redLight = it },
                        onUseGps = useGps,
                        manualBortle = manualBortle,
                        onManualBortleChange = { manualBortle = it },
                        onManualLocation = { lat, lon ->
                            site = LocatedSite(
                                GeoLocation(
                                    Degrees(lat), Degrees(lon), 0.0,
                                    // §7.6: manual coordinates resolve their
                                    // zone from the coordinates, with the
                                    // device zone as the explicit fallback.
                                    SiteTimeZone.resolve(lat, lon, ZoneId.systemDefault()),
                                ),
                                LocationSource.MANUAL, null,
                            )
                            gpsFailed = false
                        },
                        onDateChange = { selectedDate = it },
                        onOpenCategory = { cat ->
                            calcCategory = cat
                            tab = Tab.CALCULATE
                        },
                        onEvaluateCursor = { instant ->
                            container.tonightOverview.evaluateCursor(
                                location, tonightDate, instant, result.timeline, manualBortle,
                            )
                        },
                        onEvaluateTarget = { instant, objectId ->
                            container.tonightOverview.evaluateTargetAtCursor(
                                location, tonightDate, instant, result.timeline, manualBortle,
                                objectId,
                            )
                        },
                        onOpenTargetCalculator = { category, obj ->
                            calcCategory = category
                            calcPrefill = obj
                            tab = Tab.CALCULATE
                        },
                        modifier = Modifier.padding(padding),
                    )
                } ?: Text("…", modifier = Modifier.padding(padding))

                Tab.CALCULATE -> CalculatorsScreen(
                    container = container,
                    cameras = cameras.map { it.first },
                    lenses = lenses.map { it.first },
                    profiles = profiles,
                    showers = remember { container.showerCatalog.showers() },
                    location = location,
                    date = today,
                    defaultStrictness = defaultStrictness,
                    sampleInstant = sampleInstant,
                    skyFor = skyFor,
                    category = calcCategory,
                    onCategoryChange = { calcCategory = it },
                    prefillObject = calcPrefill,
                    onPrefillConsumed = { calcPrefill = null },
                    onSavePlan = { category, title, body, payload ->
                        scope.launch(Dispatchers.IO) {
                            repo.addPlan(
                                SavedPlan(
                                    id = 0,
                                    createdAt = Instant.now(),
                                    date = today,
                                    category = category,
                                    title = title,
                                    bodyText = body,
                                    payloadJson = payload,
                                ),
                            )
                            plans = repo.plans()
                        }
                    },
                    modifier = Modifier.padding(padding),
                )

                Tab.PLANS -> PlansScreen(
                    plans = plans,
                    importMessage = importMessage,
                    onDelete = { plan ->
                        scope.launch(Dispatchers.IO) {
                            repo.deletePlan(plan)
                            plans = repo.plans()
                        }
                    },
                    onExportText = {
                        pendingExport = PlanTextExport.plansToText(plans)
                        exportTextLauncher.launch("astroexposure-plans.txt")
                    },
                    onExportJson = {
                        pendingExport = LibraryTransfer.export(
                            cameras = cameras,
                            lenses = lenses,
                            trackingProfiles = profiles,
                            places = places,
                            plans = plans,
                            exportedAt = Instant.now(),
                        )
                        exportJsonLauncher.launch("astroexposure-backup.json")
                    },
                    onImportJson = {
                        importLauncher.launch(arrayOf("application/json"))
                    },
                    modifier = Modifier.padding(padding),
                )

                Tab.SETTINGS -> SettingsScreen(
                    cameras = cameras,
                    lenses = lenses,
                    profiles = profiles,
                    places = places,
                    defaultStrictness = defaultStrictness,
                    onDefaultStrictnessChange = { tier ->
                        defaultStrictness = tier
                        scope.launch(Dispatchers.IO) {
                            repo.putSetting(AppRepository.KEY_DEFAULT_STRICTNESS, tier.name)
                        }
                    },
                    redLight = redLight,
                    onRedLightChange = { redLight = it },
                    redLightAuto = redLightAuto,
                    onRedLightAutoChange = { auto ->
                        redLightAuto = auto
                        scope.launch(Dispatchers.IO) {
                            repo.putSetting(AppRepository.KEY_RED_LIGHT_AUTO, auto.toString())
                        }
                    },
                    onLanguageToggle = { toggleLanguage() },
                    timeHealth = timeHealth,
                    dataSourcesText = stringResource(
                        R.string.data_sources_body,
                        container.objectCatalog.dataVersion,
                        container.lightPollution.dataVersion,
                        container.eclipseCatalog.dataVersion,
                        container.showerCatalog.dataVersion,
                        container.lensCatalog.dataVersion,
                    ),
                    currentZone = ZoneId.systemDefault(),
                    onAddCamera = { camera ->
                        scope.launch(Dispatchers.IO) {
                            repo.upsertCamera(camera, custom = true)
                            reloadLibrary()
                        }
                    },
                    onDeleteCamera = { camera, custom ->
                        scope.launch(Dispatchers.IO) {
                            repo.deleteCamera(camera, custom)
                            reloadLibrary()
                        }
                    },
                    onAddLens = { lens ->
                        scope.launch(Dispatchers.IO) {
                            repo.upsertLens(lens, custom = true)
                            reloadLibrary()
                        }
                    },
                    onDeleteLens = { lens, custom ->
                        scope.launch(Dispatchers.IO) {
                            repo.deleteLens(lens, custom)
                            reloadLibrary()
                        }
                    },
                    onSaveProfileDrift = { profile, typical, worst ->
                        // Second line of defence behind the settings form:
                        // an invalid drift value never reaches the store.
                        if (app.astroexposure.persistence.validateDriftEntry(typical, worst) != null) {
                            return@SettingsScreen
                        }
                        scope.launch(Dispatchers.IO) {
                            repo.upsertProfile(
                                profile.copy(
                                    typicalDrift = typical?.let { ArcsecPerMinute(it) },
                                    worstDrift = worst?.let { ArcsecPerMinute(it) },
                                    measuredOn = if (typical != null) {
                                        LocalDate.now()
                                    } else {
                                        profile.measuredOn
                                    },
                                ),
                            )
                            reloadLibrary()
                        }
                    },
                    onAddPlace = { place ->
                        scope.launch(Dispatchers.IO) {
                            repo.upsertPlace(place)
                            reloadLibrary()
                        }
                    },
                    onDeletePlace = { place ->
                        scope.launch(Dispatchers.IO) {
                            repo.deletePlace(place)
                            reloadLibrary()
                        }
                    },
                    onUsePlace = { place ->
                        // §7.6: favourites take the same coordinate→zone
                        // path as GPS and manual entry when selected.
                        val resolved = place.location.copy(
                            zone = SiteTimeZone.resolve(
                                place.location.latitude.value,
                                place.location.longitude.value,
                                place.location.zone,
                            ),
                        )
                        site = LocatedSite(resolved, LocationSource.FAVOURITE, null)
                        manualBortle = place.manualBortle
                        tab = Tab.TONIGHT
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

/** Switch the per-app locale between Chinese (default) and English, applied immediately. */
private fun toggleLanguage() {
    val current = AppCompatDelegate.getApplicationLocales()
    val isEnglish = !current.isEmpty && current.get(0)?.language == "en"
    val next = if (isEnglish) Locale("zh") else Locale("en")
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(next))
}
