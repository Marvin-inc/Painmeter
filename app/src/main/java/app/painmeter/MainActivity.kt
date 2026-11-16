package app.painmeter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import app.painmeter.ui.ElevationPanelState
import app.painmeter.ui.HomeScreen
import app.painmeter.ui.ParametersScreen
import app.painmeter.ui.RideScreen
import app.painmeter.ui.SummaryScreen
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class MainActivity : ComponentActivity() {

    private var hasLocation by mutableStateOf(false)
    private var showParameters by mutableStateOf(false)
    /** Latest position seen before the ride: latitude, longitude, accuracy (m). */
    private var position by mutableStateOf<Triple<Double, Double, Double?>?>(null)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = locationGranted()
        HrMonitor.refresh()
    }

    /** Result of the last route or map import, shown in Parameters. */
    private var importMessage by mutableStateOf<String?>(null)

    private val pickRoute = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importMessage = RouteStore.importRoute(it) ?: "Route imported" }
    }

    private val pickMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        importMessage = "Importing map…"
        lifecycleScope.launch {
            importMessage = withContext(Dispatchers.IO) { RouteStore.importMap(uri) } ?: "Map imported"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RideController.init(this)
        hasLocation = locationGranted()
        handleIncoming(intent)

        setContent {
            val ride by RideController.ride.collectAsStateWithLifecycle()
            val finished by RideController.finished.collectAsStateWithLifecycle()
            val unfinished by RideController.unfinished.collectAsStateWithLifecycle()
            val hr by HrMonitor.state.collectAsStateWithLifecycle()
            val settings by Settings.current.collectAsStateWithLifecycle()
            val route by RouteStore.route.collectAsStateWithLifecycle()
            val maps by RouteStore.maps.collectAsStateWithLifecycle()
            val routeInfo = route?.let { r ->
                RouteInfo(r.name, "${Format.distanceKm(r.lengthM)} km" + (r.gainM?.let { " · ${Math.round(it)} m" } ?: ""))
            }
            val current = ride
            val done = finished
            BackHandler(enabled = showParameters && current == null) { showParameters = false }
            when {
                current != null -> RideScreen(
                    snap = current,
                    hr = hr,
                    onDraft = { RideController.setDraft(it) },
                    draftReminder = settings.draftReminder,
                    onLap = { RideController.lap() },
                    onTogglePause = { RideController.togglePause() },
                    onStop = { RideService.stop(this) },
                    nav = RideController.nav.collectAsStateWithLifecycle().value,
                    track = RideController.track.collectAsStateWithLifecycle().value,
                    maps = maps,
                )
                done != null -> SummaryScreen(done, settings.hrZones, onShare = { shareFit(done) }, onDone = { RideController.closeSummary() })
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
                        onImportRoute = { pickRoute.launch(arrayOf("*/*")) },
                        onClearRoute = { RouteStore.clearRoute(); importMessage = null },
                        onImportMap = { pickMap.launch(arrayOf("*/*")) },
                        onDeleteMap = { RouteStore.deleteMap(it) },
                        onOpenMapDownloads = {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://download.mapsforge.org/maps/v5/")))
                        },
                    )
                }
                else -> {
                    val tiles by ElevationStore.tiles.collectAsStateWithLifecycle()
                    GpsPreview(enabled = hasLocation) { position = it }
                    val here = position
                    HomeScreen(
                        hasPermission = hasLocation,
                        gpsAccuracyM = here?.third,
                        hr = hr,
                        elevationHere = here?.let { tiles.isNotEmpty() && ElevationStore.has(it.first, it.second) },
                        route = routeInfo,
                        unfinished = unfinished,
                        onStart = { RideService.start(this) },
                        onGrant = { requestPermissions() },
                        onResume = { RideService.resume(this, it) },
                        onSaveUnfinished = { RideController.finishUnfinished(it) },
                        onParameters = { showParameters = true },
                    )
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
        importMessage = RouteStore.importRoute(uri) ?: "Route imported"
        showParameters = true
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
        perms += HrMonitor.requiredPermissions()
        askPermissions.launch(perms.distinct().toTypedArray())
    }

    private fun shareFit(ride: FinishedRide) {
        val file = ride.fitFile ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Ride ${ride.dir.name}.fit")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share FIT file"))
    }

    /** Listens to GPS while the home screen is shown, so the rider sees when the fix is ready. */
    @SuppressLint("MissingPermission")
    @androidx.compose.runtime.Composable
    private fun GpsPreview(enabled: Boolean, onPosition: (Triple<Double, Double, Double?>) -> Unit) {
        DisposableEffect(enabled) {
            if (!enabled) return@DisposableEffect onDispose { }
            val client = LocationServices.getFusedLocationProviderClient(this@MainActivity)
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { onPosition(Triple(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy.toDouble() else null)) }
                }
            }
            client.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build(), callback, Looper.getMainLooper()
            )
            onDispose { client.removeLocationUpdates(callback) }
        }
    }
}
