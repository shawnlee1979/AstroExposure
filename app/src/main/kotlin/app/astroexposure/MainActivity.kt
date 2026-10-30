package app.astroexposure

import android.Manifest
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.ephemeris.EquatorialCoordinates
import app.astroexposure.domain.geo.FavouriteMatch
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
import app.astroexposure.support.CrashTrap
import app.astroexposure.support.LibraryTransfer
import app.astroexposure.support.PlanTextExport
import app.astroexposure.support.SkyAssembly
import app.astroexposure.support.SkyContext
import app.astroexposure.support.TimeHealthCheck
import app.astroexposure.ui.CalcCategory
import app.astroexposure.ui.CalcPrefill
import app.astroexposure.ui.CalculatorsScreen
import app.astroexposure.ui.PlansScreen
import app.astroexposure.ui.SettingsScreen
import app.astroexposure.ui.TonightScreen
import app.astroexposure.ui.theme.AstroTheme
import app.astroexposure.usecase.TonightOverviewUseCase
import app.astroexposure.usecase.recognisedFavourite
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        // First thing, before any content is built: the crash trap
        // (0.11.1) must already be in place so a startup crash is
        // recorded like any other.
        CrashTrap.install(this)
        super.onCreate(savedInstanceState)
        setContent {
            AstroApp(container)
        }
    }
}

private enum class Tab { TONIGHT, CALCULATE, PLANS, SETTINGS }

/**
 * How often the Tonight tab silently refreshes its GPS fix
 * (ui-design §7.2). Empirical value: fresh enough that a fix taken
 * before moving is not stale on screen all evening, slow enough not
 * to keep the GPS radio busy.
 */
private const val GPS_REFRESH_INTERVAL_MS = 300_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AstroApp(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = container.repository

    var tab by rememberSaveable { mutableStateOf(Tab.TONIGHT) }
    var calcCategory by rememberSaveable { mutableStateOf(CalcCategory.WIDE_FIELD) }
    // A catalogue target handed over from the tonight page's detail
    // dialog (ui-design §7.5): the calculators screen consumes it once
    // to prefill the declination field.
    var calcPrefill by remember { mutableStateOf<CalcPrefill?>(null) }
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
    // One GPS request channel for the permission callback, manual
    // taps, the tab-entry auto-locate and the periodic refresh. A
    // request already in flight is never stacked: two live listeners
    // would race the site state, and the provider times out on its
    // own. Outcomes are deliberately quiet (ui-design §7.2): the
    // failure flag is only set when there is no usable site at all —
    // the built-in sample fallback; with any real site (an older GPS
    // fix, a manual entry, a favourite) a failed request silently
    // keeps the site already in hand.
    //
    // Automatic requests (tab entry, the periodic refresh) are
    // guarded twice over (ui-design §7.2): they are only STARTED
    // while the site is a GPS or sample site, and a fix that lands
    // after the user has meanwhile switched to a manual or favourite
    // site is dropped, never applied — an explicitly chosen site is
    // not overridden by a background refresh. An explicit request
    // (the source-line tap) is exempt: its result always applies,
    // which is what makes the tap a way back to GPS from any site.
    val currentSite by rememberUpdatedState(site)
    var gpsInFlight by remember { mutableStateOf(false) }
    var gpsRequestExplicit by remember { mutableStateOf(false) }
    val onGpsResult: (LocatedSite?) -> Unit = { fix ->
        gpsInFlight = false
        if (fix != null) {
            val source = currentSite.source
            if (
                gpsRequestExplicit ||
                source == LocationSource.GPS || source == LocationSource.SAMPLE
            ) {
                site = fix
                gpsTime = fix.gpsTime
                gpsFailed = false
                manualBortle = null
            }
        } else if (currentSite.source == LocationSource.SAMPLE) {
            gpsFailed = true
        }
    }
    val requestGpsFix: (Boolean) -> Unit = { explicit ->
        if (!gpsInFlight) {
            gpsInFlight = true
            gpsRequestExplicit = explicit
            container.locationProvider.requestSingle(
                ZoneId.systemDefault(),
                callback = onGpsResult,
            )
        } else if (explicit) {
            // A fix is already on its way; the user's tap claims its
            // result for whichever site is on screen when it lands.
            gpsRequestExplicit = true
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            requestGpsFix(gpsRequestExplicit)
        } else if (currentSite.source == LocationSource.SAMPLE) {
            gpsFailed = true
        }
    }
    /** Explicit entry point: the source-line tap only. The entry auto-locate ([autoGps]) and the refresh loop (requestGpsFix(false) called directly) take the guarded automatic path instead. */
    val useGps: () -> Unit = {
        if (container.locationProvider.hasPermission()) {
            requestGpsFix(true)
        } else {
            gpsRequestExplicit = true
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    /** Automatic entry point: same permission flow, but the result stays guarded. */
    val autoGps: () -> Unit = {
        if (container.locationProvider.hasPermission()) {
            requestGpsFix(false)
        } else {
            gpsRequestExplicit = false
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // Entering the Tonight tab requests a fresh GPS fix (ui-design
    // §7.2), once per entry; a last-known fix may already be in place
    // as an interim. The entry auto-locate — like the refresh below —
    // only applies to GPS and sample sites: a favourite or manual
    // site the user explicitly chose (for instance on the Settings
    // tab) is not overridden by coming back to this tab. While the
    // tab stays up, the fix is silently refreshed every
    // GPS_REFRESH_INTERVAL_MS so it cannot go stale on screen;
    // leaving the tab stops the loop and re-arms the next entry.
    var autoLocated by remember { mutableStateOf(false) }
    LaunchedEffect(tab) {
        if (tab == Tab.TONIGHT) {
            if (!autoLocated) {
                autoLocated = true
                val source = currentSite.source
                if (source == LocationSource.GPS || source == LocationSource.SAMPLE) {
                    autoGps()
                }
            }
            while (true) {
                delay(GPS_REFRESH_INTERVAL_MS)
                // No prompting from the loop: a refresh that would
                // need the permission dialog simply does not happen,
                // and a manual or favourite site is never refreshed
                // over at all.
                val source = currentSite.source
                if (
                    (source == LocationSource.GPS || source == LocationSource.SAMPLE) &&
                    container.locationProvider.hasPermission()
                ) {
                    requestGpsFix(false)
                }
            }
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
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        // The red-light switch lives in the title bar
                        // (ui-design §7.2): reachable from every tab
                        // without taking a row of page space. The
                        // Settings tab keeps its own switch and the
                        // auto-red-light option beside it.
                        val redLightDescription = stringResource(R.string.red_light)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = redLightDescription,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Switch(
                                checked = redLight,
                                onCheckedChange = { redLight = it },
                                modifier = Modifier.semantics {
                                    contentDescription = redLightDescription
                                },
                            )
                        }
                    },
                )
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
                    // Favourite recognition (ui-design §7.2): a GPS fix
                    // or a manual entry within the match radius of a
                    // saved place is "at that place" — the card shows
                    // its name and says the match happened; the
                    // coordinates stay the site's own, nothing is
                    // snapped. (Manual sites joined the chain in
                    // 0.8.6 so a place saved from a manual entry is
                    // recognised like any GPS-saved one.)
                    val favouriteMatch = recognisedFavourite(
                        site.source,
                        location.latitude.value,
                        location.longitude.value,
                        places.map { place ->
                            FavouriteMatch.Candidate(
                                name = place.name,
                                latitudeDeg = place.location.latitude.value,
                                longitudeDeg = place.location.longitude.value,
                            )
                        },
                    )
                    TonightScreen(
                        overview = result,
                        date = tonightDate,
                        anchorDate = today,
                        zone = location.zone,
                        deviceZone = ZoneId.systemDefault(),
                        latitudeDeg = location.latitude.value,
                        longitudeDeg = location.longitude.value,
                        locationLabel = location.label ?: favouriteMatch?.name,
                        locationSourceText = stringResource(
                            when {
                                favouriteMatch != null &&
                                    site.source == LocationSource.GPS ->
                                    R.string.location_source_gps_matched
                                favouriteMatch != null &&
                                    site.source == LocationSource.MANUAL ->
                                    R.string.location_source_manual_matched
                                site.source == LocationSource.GPS -> R.string.location_source_gps_unsaved
                                site.source == LocationSource.MANUAL -> R.string.location_source_manual
                                site.source == LocationSource.FAVOURITE -> R.string.location_source_favourite
                                else -> R.string.location_source_sample
                            },
                        ) + if (gpsFailed && site.source == LocationSource.SAMPLE) {
                            " · " + stringResource(R.string.location_gps_failed)
                        } else {
                            ""
                        },
                        timeHealth = timeHealth,
                        redLight = redLight,
                        lenses = lenses.map { it.first },
                        onUseGps = useGps,
                        showGpsSyncDot = site.source == LocationSource.GPS,
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
                        // A favourite selected from the library shows
                        // the altitude line only when its record carries
                        // a GPS altitude source (saved from the card,
                        // ui-design §7.2); onUsePlace threads that fact
                        // into site.hasAltitude, and the record's own
                        // height is location.heightMeters in that state.
                        showAltitudeLine = site.source == LocationSource.GPS ||
                            site.source == LocationSource.MANUAL ||
                            (site.source == LocationSource.FAVOURITE && site.hasAltitude),
                        gpsAltitudeMeters = if (
                            (site.source == LocationSource.GPS ||
                                site.source == LocationSource.FAVOURITE) && site.hasAltitude
                        ) {
                            location.heightMeters
                        } else {
                            null
                        },
                        canSaveFavourite = favouriteMatch == null &&
                            (site.source == LocationSource.MANUAL ||
                                site.source == LocationSource.GPS),
                        onSaveFavourite = { name ->
                            // The same store the Settings tab manages:
                            // one place repository, no second path.
                            val place = SavedPlace(
                                id = "place-" + UUID.randomUUID().toString(),
                                name = name,
                                location = location.copy(label = name),
                                manualBortle = manualBortle,
                                // Card saves record where the altitude
                                // came from: only a GPS fix that actually
                                // carried one gives the record a GPS
                                // altitude source (ui-design §7.2).
                                altitudeFromGps = site.source == LocationSource.GPS &&
                                    site.hasAltitude,
                            )
                            scope.launch(Dispatchers.IO) {
                                repo.upsertPlace(place)
                                reloadLibrary()
                            }
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
                        onEvaluateComposition = { objectId, bearingDeg, toleranceDeg ->
                            container.tonightOverview.evaluateComposition(
                                location, tonightDate, result.timeline, objectId,
                                bearingDeg, toleranceDeg,
                            )
                        },
                        onOpenTargetCalculator = { category, prefill ->
                            calcCategory = category
                            calcPrefill = prefill
                            tab = Tab.CALCULATE
                        },
                        orientationProvider = container.orientationProvider,
                        siteLatitudeDeg = location.latitude.value,
                        siteLongitudeDeg = location.longitude.value,
                        siteAltitudeMeters = location.heightMeters,
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
                    prefill = calcPrefill,
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
                        container.starCatalog.dataVersion,
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
                        site = LocatedSite(
                            resolved, LocationSource.FAVOURITE, null,
                            hasAltitude = place.altitudeFromGps,
                        )
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
