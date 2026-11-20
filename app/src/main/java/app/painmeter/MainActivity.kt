package app.painmeter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import app.painmeter.ui.Pm
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import android.net.Uri
import app.painmeter.ui.Format
import app.painmeter.ui.RouteInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.painmeter.ui.AeroInfo
import app.painmeter.ui.CalibrationIntroScreen
import app.painmeter.ui.CalibrationResultScreen
import app.painmeter.ui.ElevationPanelState
import app.painmeter.ui.HistoryScreen
import app.painmeter.ui.RoutePreviewScreen
import app.painmeter.ui.RoutesScreen
import app.painmeter.ui.HomeScreen
import app.painmeter.ui.ParametersScreen
import app.painmeter.ui.RideScreen
import app.painmeter.ui.SummaryScreen

class MainActivity : ComponentActivity() {

    private var hasLocation by mutableStateOf(false)
    private enum class Screen { HOME, PARAMETERS, HISTORY, CALIBRATION, PICK_CALIBRATION_RIDE, ROUTES, ROUTE_PREVIEW }
    private var previewId by mutableStateOf<String?>(null)
    private var routesMessage by mutableStateOf<String?>(null)

    private val pickRoutes = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) routesMessage = RouteStore.importRoutes(uris)
            ?: "${uris.size} route${if (uris.size > 1) "s" else ""} imported"
    }
    private var screen by mutableStateOf(Screen.HOME)
    private var showParameters: Boolean
        get() = screen == Screen.PARAMETERS
        set(v) { screen = if (v) Screen.PARAMETERS else Screen.HOME }
    /** Latest position seen before the ride: latitude, longitude, accuracy (m). */
    private var position by mutableStateOf<Triple<Double, Double, Double?>?>(null)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = locationGranted()
        HrMonitor.refresh()
    }

    /** Result of the last route or map import, shown in Parameters. */
    private var importMessage by mutableStateOf<String?>(null)


    private val pickMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        importMessage = "Importing map…"
        lifecycleScope.launch {
            importMessage = withContext(Dispatchers.IO) { RouteStore.importMap(uri) }
                ?: if (RouteStore.lastImportKind == "theme") "Map theme imported" else "Map imported"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RideController.init(this)
        hasLocation = locationGranted()
        handleIncoming(intent)

        // Android 15 draws apps under the status bar and the navigation buttons: keep every screen
        // inside the safe area so nothing ends up behind the system buttons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#0B0D10")),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#0B0D10")),
        )
        setContent {
            Box(Modifier.fillMaxSize().background(Pm.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            val ride by RideController.ride.collectAsStateWithLifecycle()
            val finished by RideController.finished.collectAsStateWithLifecycle()
            val unfinished by RideController.unfinished.collectAsStateWithLifecycle()
            val hr by HrMonitor.state.collectAsStateWithLifecycle()
            val settings by Settings.current.collectAsStateWithLifecycle()
            val route by RouteStore.route.collectAsStateWithLifecycle()
            val maps by RouteStore.maps.collectAsStateWithLifecycle()
            val mapTheme by RouteStore.theme.collectAsStateWithLifecycle()
            val routeClimbs by RideController.routeClimbs.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(route, settings.climbs) { RideController.analyseRoute() }
            val routeInfo = route?.let { r ->
                RouteInfo(
                    r.name,
                    "${Format.distanceKm(r.lengthM)} km" + (r.gainM?.let { " · ${Math.round(it)} m" } ?: "") +
                        " · ${routeClimbs.size} climb" + (if (routeClimbs.size == 1) "" else "s"),
                )
            }
            val current = ride
            val done = finished
            val calibration by RideController.calibration.collectAsStateWithLifecycle()
            val calibrationMode by RideController.calibrationMode.collectAsStateWithLifecycle()
            BackHandler(enabled = screen != Screen.HOME && current == null) {
                screen = when (screen) {
                    Screen.PICK_CALIBRATION_RIDE -> Screen.CALIBRATION
                    Screen.ROUTE_PREVIEW -> Screen.ROUTES
                    else -> Screen.HOME
                }
            }
            when {
                current != null -> RideScreen(
                    snap = current,
                    hr = hr,
                    onDraft = { RideController.setDraft(it) },
                    draftReminder = settings.draftReminder,
                    onLap = { RideController.lap() },
                    onTogglePause = { RideController.togglePause() },
                    onStop = { RideService.stop(this@MainActivity) },
                    calibrationRuns = if (calibrationMode) RideController.calibRuns.collectAsStateWithLifecycle().value else null,
                    nav = RideController.nav.collectAsStateWithLifecycle().value,
                    track = RideController.track.collectAsStateWithLifecycle().value,
                    maps = maps,
                    theme = mapTheme?.takeIf { settings.usesTheme(current.sport) },
                    climb = RideController.climb.collectAsStateWithLifecycle().value,
                    climbAutoSwitch = settings.climbAutoSwitch,
                    zones = settings.hrZones,
                )
                calibration != null -> {
                    val c = calibration!!
                    CalibrationResultScreen(
                        c, settings.cda, settings.crr,
                        onKeepCrrFixed = { RideController.calibrate(c.dir, keepCrrFixed = it) },
                        onApply = { RideController.applyCalibration(); RideController.closeCalibration(); RideController.closeSummary(); screen = Screen.PARAMETERS },
                        onDone = { RideController.closeCalibration() },
                    )
                }
                done != null -> SummaryScreen(done, settings.hrZones, onShare = { shareFit(done) }, onDone = { RideController.closeSummary() })
                screen == Screen.ROUTES -> {
                    val library by RouteStore.library.collectAsStateWithLifecycle()
                    val activeId by RouteStore.activeId.collectAsStateWithLifecycle()
                    RoutesScreen(
                        library, activeId, routesMessage,
                        onOpen = { previewId = it.id; routesMessage = null; screen = Screen.ROUTE_PREVIEW },
                        onFreeRide = { RouteStore.select(null); screen = Screen.HOME },
                        onImport = { pickRoutes.launch(arrayOf("*/*")) },
                        onBack = { routesMessage = null; screen = Screen.HOME },
                    )
                }
                screen == Screen.ROUTE_PREVIEW -> {
                    val library by RouteStore.library.collectAsStateWithLifecycle()
                    val activeId by RouteStore.activeId.collectAsStateWithLifecycle()
                    val saved = library.firstOrNull { it.id == previewId }
                    if (saved == null) {
                        screen = Screen.ROUTES
                    } else {
                        var analysis by remember(saved.route) {
                            mutableStateOf<Pair<app.painmeter.core.climb.RouteProfile, List<app.painmeter.core.climb.RouteClimb>>?>(null)
                        }
                        androidx.compose.runtime.LaunchedEffect(saved.route) { analysis = RideController.analyse(saved.route) }
                        RoutePreviewScreen(
                            saved, saved.id == activeId, analysis, maps,
                            theme = mapTheme?.takeIf { settings.usesTheme(settings.sport) }, sport = settings.sport,
                            onUse = { RouteStore.select(saved.id); screen = Screen.HOME },
                            onRename = { RouteStore.rename(saved.id, it) },
                            onDelete = { RouteStore.delete(saved.id); screen = Screen.ROUTES },
                            onBack = { screen = Screen.ROUTES },
                        )
                    }
                }
                screen == Screen.CALIBRATION -> CalibrationIntroScreen(
                    settings.cda, settings.crr,
                    onStartTest = { screen = Screen.HOME; RideService.start(this@MainActivity, calibration = true) },
                    onFromHistory = { screen = Screen.PICK_CALIBRATION_RIDE },
                    onBack = { screen = Screen.PARAMETERS },
                )
                screen == Screen.HISTORY || screen == Screen.PICK_CALIBRATION_RIDE -> {
                    var rides by remember { mutableStateOf<List<Pair<java.io.File, RideStore.Summary>>?>(null) }
                    var version by remember { mutableStateOf(0) }
                    androidx.compose.runtime.LaunchedEffect(version) { rides = RideController.history() }
                    HistoryScreen(
                        rides, pickForCalibration = screen == Screen.PICK_CALIBRATION_RIDE,
                        onOpen = { RideController.openRide(it) },
                        onShare = { shareFile(java.io.File(it, "ride.fit"), it.name) },
                        onCalibrate = { RideController.calibrate(it) },
                        onDelete = { RideController.deleteRide(it); version++ },
                        onBack = { screen = if (screen == Screen.PICK_CALIBRATION_RIDE) Screen.CALIBRATION else Screen.HOME },
                    )
                }
                showParameters -> {
                    val found by HrMonitor.found.collectAsStateWithLifecycle()
                    val scanning by HrMonitor.scanning.collectAsStateWithLifecycle()
                    val tiles by ElevationStore.tiles.collectAsStateWithLifecycle()
                    val download by ElevationStore.download.collectAsStateWithLifecycle()
                    val here = position
                    GpsPreview(enabled = hasLocation) { position = it }
                    ParametersScreen(
                        settings = settings,
                        hr = hr,
                        found = found,
                        scanning = scanning,
                        onChange = { next -> Settings.update { next } },
                        onScan = { if (HrMonitor.hasPermission()) HrMonitor.startScan() else requestPermissions() },
                        onPair = { HrMonitor.pair(it) },
                        onForget = { HrMonitor.forget() },
                        onBack = { HrMonitor.stopScan(); showParameters = false },
                        elevation = ElevationPanelState(
                            tiles = tiles,
                            storageMb = remember(tiles) { (ElevationStore.storageBytes() / 1_000_000).toInt() },
                            download = download,
                            hasPosition = here != null,
                            hereCovered = here != null && here.first.let { ElevationStore.has(here.first, here.second) } && tiles.isNotEmpty(),
                        ),
                        onDownloadElevation = { here?.let { ElevationStore.downloadAround(it.first, it.second) } },
                        onDeleteElevation = { ElevationStore.deleteAll() },
                        route = routeInfo,
                        maps = maps,
                        message = importMessage,
                        onImportRoute = { screen = Screen.ROUTES },
                        onClearRoute = { RouteStore.clearRoute(); importMessage = null },
                        onImportMap = { pickMap.launch(arrayOf("*/*")) },
                        onDeleteMap = { RouteStore.deleteMap(it) },
                        onCalibrate = { screen = Screen.CALIBRATION },
                        onOpenMapDownloads = {
                            // Running on trails: OpenAndroMaps (paths, trail grades, contours); else Mapsforge.
                            val url = if (settings.sport == app.painmeter.core.run.Sport.RUN) "https://www.openandromaps.org/en/downloads"
                            else "https://download.mapsforge.org/maps/v5/"
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                        theme = mapTheme,
                        onDeleteTheme = { RouteStore.deleteTheme() },
                        onDownloadTheme = {
                            importMessage = "Downloading the Elevate theme…"
                            lifecycleScope.launch {
                                importMessage = withContext(Dispatchers.IO) { RouteStore.downloadTheme() } ?: "Elevate theme installed"
                            }
                        },
                        onOpenThemeDownloads = {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openandromaps.org/en/legend/elevate-mountain-hike-theme")))
                        },
                    )
                }
                else -> {
                    val tiles by ElevationStore.tiles.collectAsStateWithLifecycle()
                    GpsPreview(enabled = hasLocation) { position = it }
                    val here = position
                    var lastRide by remember { mutableStateOf<RideStore.Summary?>(null) }
                    androidx.compose.runtime.LaunchedEffect(finished) { lastRide = RideController.history().firstOrNull()?.second }
                    HomeScreen(
                        hasPermission = hasLocation,
                        gpsAccuracyM = here?.third,
                        hr = hr,
                        elevationHere = here?.let { tiles.isNotEmpty() && ElevationStore.has(it.first, it.second) },
                        route = routeInfo,
                        unfinished = unfinished,
                        onStart = { RideService.start(this@MainActivity) },
                        onGrant = { requestPermissions() },
                        onResume = { RideService.resume(this@MainActivity, it) },
                        onSaveUnfinished = { RideController.finishUnfinished(it) },
                        onParameters = { showParameters = true },
                        onHistory = { screen = Screen.HISTORY },
                        mapCount = maps.size,
                        aero = AeroInfo(settings.cda, settings.crr, settings.calibDateMs),
                        lastRide = lastRide,
                        routeProfile = route?.points?.mapNotNull { it.ele }?.let { e ->
                            if (e.size < 2) null else List(60) { i -> e[(i * (e.size - 1)) / 59] }
                        },
                        onCalibrate = { screen = Screen.CALIBRATION },
                        onRoutes = { screen = Screen.ROUTES },
                        sport = settings.sport,
                        onSport = { sp ->
                            Settings.update { it.copy(sport = sp) }
                            // Running cadence needs the step detector permission.
                            if (sp == app.painmeter.core.run.Sport.RUN && ContextCompat.checkSelfPermission(
                                    this@MainActivity, Manifest.permission.ACTIVITY_RECOGNITION,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) requestPermissions()
                        },
                        hasTheme = mapTheme != null,
                    )
                }
            }
            }
        }
        if (!hasLocation || !HrMonitor.hasPermission()) requestPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** A .gpx opened or shared from another app becomes the route. */
    private fun handleIncoming(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        } ?: return
        if (RideController.isActive) return
        routesMessage = RouteStore.importRoute(uri) ?: "Route imported and selected"
        screen = Screen.ROUTES
    }

    override fun onStart() {
        super.onStart()
        HrMonitor.acquire("ui")
    }

    override fun onStop() {
        HrMonitor.stopScan()
        HrMonitor.release("ui")
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        hasLocation = locationGranted()
        RideController.refreshUnfinished()
        HrMonitor.refresh()
    }

    /** Volume down = lap while a ride is running (SPEC §8). Otherwise the key works as usual. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && RideController.isActive) {
            if (event.repeatCount == 0) RideController.lap()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && RideController.isActive) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun locationGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
        // Step detector, for the running cadence (Android 10+).
        perms += Manifest.permission.ACTIVITY_RECOGNITION
        perms += HrMonitor.requiredPermissions()
        askPermissions.launch(perms.distinct().toTypedArray())
    }

    private fun shareFit(ride: FinishedRide) {
        shareFile(ride.fitFile ?: return, ride.dir.name)
    }

    private fun shareFile(file: java.io.File, name: String) {
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Ride $name.fit")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share FIT file"))
    }

    /**
     * Listens to the location source chosen in Parameters while the home screen is shown, so the
     * rider sees when the fix is ready (and the raw GPS chip is already warm at the start).
     */
    @androidx.compose.runtime.Composable
    private fun GpsPreview(enabled: Boolean, onPosition: (Triple<Double, Double, Double?>) -> Unit) {
        val raw = Settings.current.collectAsStateWithLifecycle().value.rawGps
        DisposableEffect(enabled, raw) {
            if (!enabled) return@DisposableEffect onDispose { }
            val feed = LocationFeed(this@MainActivity) {
                onPosition(Triple(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy.toDouble() else null))
            }
            feed.start(raw)
            onDispose { feed.stop() }
        }
    }
}
