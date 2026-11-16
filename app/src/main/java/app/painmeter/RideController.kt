package app.painmeter

import android.content.Context
import app.painmeter.core.FitExporter
import app.painmeter.core.Fix
import app.painmeter.core.JournalEntry
import app.painmeter.core.JournalWriter
import app.painmeter.core.LapSummary
import app.painmeter.core.RideSession
import app.painmeter.core.RideSnapshot
import app.painmeter.core.RideStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A ride that has been stopped: its summary and its FIT file. */
data class FinishedRide(
    val dir: File,
    val snapshot: RideSnapshot,
    val laps: List<LapSummary>,
    val fitFile: File?,
    val error: String? = null,
)

/**
 * The single owner of the ride in progress. All calls happen on the main thread: the location
 * callback, the 1 s ticker and the UI. Every change is written to the journal before the
 * screens see it.
 */
object RideController {
    private lateinit var store: RideStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var session: RideSession? = null
    private var journal: JournalWriter? = null
    private var rideDir: File? = null

    private val _ride = MutableStateFlow<RideSnapshot?>(null)
    /** The ride in progress, refreshed every second; null when no ride is running. */
    val ride: StateFlow<RideSnapshot?> = _ride

    private val _finished = MutableStateFlow<FinishedRide?>(null)
    val finished: StateFlow<FinishedRide?> = _finished

    private val _unfinished = MutableStateFlow<File?>(null)
    /** A ride left open by a crash, offered for resume on the home screen. */
    val unfinished: StateFlow<File?> = _unfinished

    private val _laps = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /** Emits the number of the lap just closed, for the on-screen flash and vibration. */
    val laps: SharedFlow<Int> = _laps

    val isActive: Boolean get() = session?.status.let { it == RideStatus.RECORDING || it == RideStatus.PAUSED }

    fun init(context: Context) {
        Settings.init(context)
        HrMonitor.init(context)
        ElevationStore.init(context)
        RouteStore.init(context)
        appContext = context.applicationContext
        if (!::store.isInitialized) store = RideStore(context.applicationContext)
        HrMonitor.onReading = { t, bpm -> onHeartRate(t, bpm) }
        refreshUnfinished()
    }

    private fun rideSettings() = Settings.current.value.toRideSettings()

    private lateinit var appContext: Context

    // ------------------------------------------------------------ navigation (SPEC §9.5)

    /** Where the rider is on the loaded route; null without a route or before the first fix. */
    data class Nav(val state: app.painmeter.core.route.NavState, val route: app.painmeter.core.route.Route)

    private val _nav = MutableStateFlow<Nav?>(null)
    val nav: StateFlow<Nav?> = _nav

    /** The ridden track (one point every 10 m or so) and the current heading, for the map. */
    data class Track(val points: List<Pair<Double, Double>>, val bearingDeg: Double?, val position: Pair<Double, Double>?)

    private val _track = MutableStateFlow(Track(emptyList(), null, null))
    val track: StateFlow<Track> = _track

    private var follower: app.painmeter.core.route.RouteFollower? = null
    private var turnWarned: Double? = null

    private fun updateNav(fix: Fix) {
        // Track and heading.
        val t = _track.value
        val last = t.points.lastOrNull()
        if (last == null || app.painmeter.core.Geo.distanceM(last.first, last.second, fix.lat, fix.lon) >= 10) {
            val bearing = last?.let {
                app.painmeter.core.route.Route.bearing(
                    app.painmeter.core.route.RoutePoint(it.first, it.second), app.painmeter.core.route.RoutePoint(fix.lat, fix.lon),
                )
            } ?: t.bearingDeg
            _track.value = Track(t.points + (fix.lat to fix.lon), bearing, fix.lat to fix.lon)
        } else {
            _track.value = t.copy(position = fix.lat to fix.lon)
        }

        // Route.
        val route = RouteStore.route.value
        if (route == null) { follower = null; _nav.value = null; return }
        val f = follower?.takeIf { it.route === route } ?: app.painmeter.core.route.RouteFollower(route).also { follower = it }
        val before = _nav.value?.state
        val st = f.update(fix.timeMs, fix.lat, fix.lon)
        _nav.value = Nav(st, route)
        if (st.offRoute && before?.offRoute != true) vibrate(700)
        val turn = st.nextTurn
        val dist = st.distanceToTurnM
        if (!st.offRoute && turn != null && dist != null && dist <= 100 && turnWarned != turn.distanceM) {
            turnWarned = turn.distanceM
            vibrate(150); scope.launch { kotlinx.coroutines.delay(300); vibrate(150) }
        }
    }

    private fun vibrate(ms: Long) {
        if (!::appContext.isInitialized) return
        val v = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            appContext.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(android.os.Vibrator::class.java)
        }
        v?.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun refreshUnfinished() {
        _unfinished.value = if (isActive) null else store.unfinished().firstOrNull()
    }

    // ------------------------------------------------------------ lifecycle (called by RideService)

    fun startNewRide(nowMs: Long) {
        if (isActive) return
        val dir = store.newRideDir(nowMs)
        open(dir, RideSession(rideSettings()))
        record(session!!.start(nowMs))
        weatherAt = null
        follower = null
        turnWarned = null
        _nav.value = null
        _track.value = Track(emptyList(), null, null)
        _weather.value = null
        _finished.value = null
        publish(nowMs)
    }

    /**
     * Continues a ride left open by a crash. The time between the last recorded entry and now is
     * a pause, so it does not count in the ride time.
     */
    fun resumeUnfinished(dir: File, nowMs: Long) {
        if (isActive) return
        val entries = store.read(dir)
        val s = RideSession.replay(entries, rideSettings())
        open(dir, s)
        val last = entries.maxOfOrNull { it.timeMs } ?: nowMs
        if (s.status == RideStatus.RECORDING) record(s.pause(last))
        record(s.resume(nowMs))
        _finished.value = null
        _unfinished.value = null
        publish(nowMs)
    }

    private fun open(dir: File, s: RideSession) {
        rideDir = dir
        session = s
        journal = JournalWriter(store.journal(dir))
    }

    fun onFix(raw: Fix) {
        val s = session ?: return
        val fix = raw.copy(demAltitudeM = ElevationStore.elevation(raw.lat, raw.lon))
        record(s.onFix(fix))
        updateNav(fix)
        maybeRefreshWeather(fix)
        publish(System.currentTimeMillis())
    }

    // Weather: at the start, then every 20 min or 10 km (SPEC §2).
    private var weatherAt: Fix? = null
    private var weatherBusy = false

    private fun maybeRefreshWeather(fix: Fix) {
        if (!Settings.current.value.weatherOn || weatherBusy) return
        val last = weatherAt
        val due = last == null || fix.timeMs - last.timeMs > 20 * 60_000 ||
            app.painmeter.core.Geo.distanceM(last, fix) > 10_000
        if (!due) return
        weatherBusy = true
        scope.launch {
            val w = WeatherClient.current(fix.lat, fix.lon)
            weatherBusy = false
            if (w != null) {
                weatherAt = fix
                session?.let { record(it.onWeather(System.currentTimeMillis(), w)) }
                _weather.value = w
            } else {
                // Offline: try again in 2 minutes.
                weatherAt = last?.copy(timeMs = fix.timeMs - 18 * 60_000) ?: fix.copy(timeMs = fix.timeMs - 18 * 60_000)
            }
        }
    }

    private val _weather = MutableStateFlow<app.painmeter.core.power.WeatherNow?>(null)
    /** Latest weather received during this ride, null before the first one. */
    val weather: StateFlow<app.painmeter.core.power.WeatherNow?> = _weather

    /** Solo / On a wheel (SPEC §6). */
    fun setDraft(onWheel: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val s = session ?: return
        record(s.setDraft(nowMs, onWheel))
        publish(nowMs)
    }

    fun onHeartRate(timeMs: Long, bpm: Int) {
        val s = session ?: return
        record(s.onHeartRate(timeMs, bpm))
    }

    fun tick(nowMs: Long) {
        if (session != null) publish(nowMs)
    }

    // ------------------------------------------------------------ rider commands

    fun lap(nowMs: Long = System.currentTimeMillis()) {
        val s = session ?: return
        val produced = s.lap(nowMs)
        if (produced.isEmpty()) return
        record(produced)
        journal?.sync()
        _laps.tryEmit(s.completedLaps.size)
        publish(nowMs)
    }

    fun togglePause(nowMs: Long = System.currentTimeMillis()) {
        val s = session ?: return
        record(if (s.status == RideStatus.PAUSED) s.resume(nowMs) else s.pause(nowMs))
        journal?.sync()
        publish(nowMs)
    }

    /** Stops the ride and writes the FIT file. Called by RideService on stop. */
    fun stop(nowMs: Long, onDone: () -> Unit) {
        val s = session ?: return onDone()
        val dir = rideDir ?: return onDone()
        record(s.stop(nowMs))
        journal?.sync()
        journal?.close()
        journal = null
        session = null
        rideDir = null
        _ride.value = null
        val snap = s.snapshot(nowMs)
        val laps = s.completedLaps
        scope.launch {
            _finished.value = export(dir, snap, laps)
            onDone()
        }
    }

    /** Closes a ride left open by a crash without resuming it. */
    fun finishUnfinished(dir: File) {
        if (isActive) return
        val entries = store.read(dir)
        val last = entries.maxOfOrNull { it.timeMs } ?: return
        val s = RideSession.replay(entries, rideSettings())
        val stop = s.stop(last)
        JournalWriter(store.journal(dir)).use { w -> stop.forEach(w::append); w.sync() }
        _unfinished.value = null
        scope.launch { _finished.value = export(dir, s.snapshot(last), s.completedLaps) }
    }

    fun closeSummary() {
        _finished.value = null
        refreshUnfinished()
    }

    private suspend fun export(dir: File, snap: RideSnapshot, laps: List<LapSummary>): FinishedRide =
        withContext(Dispatchers.IO) {
            val fit = store.fit(dir)
            try {
                FitExporter.export(store.read(dir), fit, rideSettings())
                FinishedRide(dir, snap, laps, fit)
            } catch (e: Exception) {
                FinishedRide(dir, snap, laps, null, error = "FIT export failed: ${e.message}")
            }
        }

    private fun record(entries: List<JournalEntry>) {
        val w = journal ?: return
        entries.forEach(w::append)
    }

    private fun publish(nowMs: Long) {
        _ride.value = session?.snapshot(nowMs)
    }
}
