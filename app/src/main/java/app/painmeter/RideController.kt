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
    val climbs: List<app.painmeter.core.climb.ClimbProgress> = emptyList(),
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

    // ------------------------------------------------------------ calibration (SPEC §9.7)

    private val _calibrationMode = MutableStateFlow(false)
    /** True while riding a coast-down test: the ride screen shows the test page. */
    val calibrationMode: StateFlow<Boolean> = _calibrationMode

    /** Runs marked so far in the test: each direction, and whether a run is open (coasting). */
    data class CalibRuns(val out: Int = 0, val back: Int = 0, val coasting: Boolean = false)

    private val _calibRuns = MutableStateFlow(CalibRuns())
    val calibRuns: StateFlow<CalibRuns> = _calibRuns
    private val presses = mutableListOf<Fix>()
    private var lastFix: Fix? = null

    /** A calibration result to show, from the test just ridden or a ride from the history. */
    data class Calibration(
        val dir: File,
        val result: app.painmeter.core.calibration.CoastDown.Result,
        val warnings: List<String>,
        val keepCrrFixed: Boolean,
    )

    private val _calibration = MutableStateFlow<Calibration?>(null)
    val calibration: StateFlow<Calibration?> = _calibration

    /** Runs the calibration on a stopped ride (in the background). */
    fun calibrate(dir: File, keepCrrFixed: Boolean = false) {
        val settings = rideSettings()
        scope.launch {
            val c = withContext(Dispatchers.Default) {
                val input = app.painmeter.core.calibration.RideCalibration.input(store.read(dir), settings)
                    ?: return@withContext null
                val r = app.painmeter.core.calibration.CoastDown.run(input, settings.power.massKg, settings.power.crr, keepCrrFixed)
                Calibration(dir, r, app.painmeter.core.calibration.RideCalibration.warnings(r), keepCrrFixed)
            }
            _calibration.value = c ?: Calibration(
                dir,
                app.painmeter.core.calibration.CoastDown.Result(
                    emptyList(), false, "laps", false, false, null, emptyList(), null, null, null, null, "none",
                ),
                listOf("This ride is too short for a calibration."), keepCrrFixed,
            )
        }
    }

    fun applyCalibration() {
        val fit = _calibration.value?.result?.fit ?: return
        Settings.update {
            it.copy(cda = fit.cda, crr = fit.crr, calibDateMs = System.currentTimeMillis(), calibSeCda = fit.seCda, calibSeCrr = fit.seCrr)
        }
    }

    fun closeCalibration() { _calibration.value = null }

    private fun updateCalibRuns() {
        // Pairs of presses: start and end of a coast. A run counts when it lasted 8 s to 5 min and
        // slowed down by 1 m/s or more; its direction is compared with the first run.
        var out = 0; var back = 0
        var ref: Double? = null
        for (k in 0 until presses.size - 1 step 2) {
            val a = presses[k]; val b = presses[k + 1]
            val dur = (b.timeMs - a.timeMs) / 1000
            if (dur < 8 || dur > 300 || (a.speedMps ?: 0.0) - (b.speedMps ?: 0.0) < 1) continue
            val bearing = app.painmeter.core.route.Route.bearing(
                app.painmeter.core.route.RoutePoint(a.lat, a.lon), app.painmeter.core.route.RoutePoint(b.lat, b.lon),
            )
            val r = ref ?: bearing.also { ref = it }
            if (kotlin.math.cos(Math.toRadians(bearing - r)) >= 0) out++ else back++
        }
        _calibRuns.value = CalibRuns(out, back, coasting = presses.size % 2 == 1)
    }

    fun startNewRide(nowMs: Long, calibration: Boolean = false) {
        if (isActive) return
        _calibrationMode.value = calibration
        presses.clear()
        _calibRuns.value = CalibRuns()
        val dir = store.newRideDir(nowMs)
        open(dir, RideSession(rideSettings()))
        record(session!!.start(nowMs))
        detector = app.painmeter.core.climb.LiveClimbDetector(Settings.current.value.climbs)
        finishedClimb = null
        _climb.value = ClimbUi()
        analyseRoute()
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
        var fix = raw.copy(demAltitudeM = ElevationStore.elevation(raw.lat, raw.lon))
        lastFix = raw
        updateNav(fix)
        // On the route, the route profile gives the gradient without lag (SPEC §4).
        val onRoute = _nav.value?.state?.takeIf { !it.offRoute && it.offsetM <= 50 }
        val profile = routeProfile
        if (onRoute != null && profile != null && profile.route === _nav.value?.route) {
            fix = fix.copy(routeGradePercent = profile.gradeAt(onRoute.distanceAlongM))
        }
        record(s.onFix(fix))
        if (s.status == RideStatus.RECORDING) updateClimb(fix, s.snapshot(fix.timeMs), onRoute?.distanceAlongM)
        maybeRefreshWeather(fix)
        publish(System.currentTimeMillis())
    }

    // ------------------------------------------------------------ climbs (SPEC §7)

    /** What the Climb page shows. */
    data class ClimbUi(
        /** The climb in progress, or the one just finished (finished = true) for 10 s. */
        val progress: app.painmeter.core.climb.ClimbProgress? = null,
        /** On a route climb: its whole profile (distance from its start, elevation) and the rider on it. */
        val routeProfile: List<Pair<Double, Double>>? = null,
        val riderOnClimbM: Double? = null,
        val remainingM: Double? = null,
        val remainingGainM: Double? = null,
        val climbNumber: Int? = null,
        val climbCount: Int = 0,
        val next: app.painmeter.core.climb.RouteClimb? = null,
        val distanceToNextM: Double? = null,
    ) {
        val remainingGradePercent: Double?
            get() = if (remainingM != null && remainingGainM != null && remainingM > 50) remainingGainM / remainingM * 100 else null
    }

    enum class ClimbEvent { STARTED, FINISHED }

    private val _climb = MutableStateFlow(ClimbUi())
    val climb: StateFlow<ClimbUi> = _climb
    private val _climbEvents = MutableSharedFlow<ClimbEvent>(extraBufferCapacity = 4)
    val climbEvents: SharedFlow<ClimbEvent> = _climbEvents

    private val _routeClimbs = MutableStateFlow<List<app.painmeter.core.climb.RouteClimb>>(emptyList())
    /** Climbs found on the loaded route. */
    val routeClimbs: StateFlow<List<app.painmeter.core.climb.RouteClimb>> = _routeClimbs

    private var routeProfile: app.painmeter.core.climb.RouteProfile? = null
    private var detector = app.painmeter.core.climb.LiveClimbDetector()
    private var finishedClimb: app.painmeter.core.climb.ClimbProgress? = null
    private var finishedAtMs = 0L

    /** Builds the route profile (with elevation tiles where the GPX has none) and finds its climbs. */
    fun analyseRoute() {
        val route = RouteStore.route.value ?: run { routeProfile = null; _routeClimbs.value = emptyList(); return }
        val settings = Settings.current.value.climbs
        scope.launch {
            val profile = withContext(Dispatchers.IO) {
                app.painmeter.core.climb.RouteProfile(route) { lat, lon -> ElevationStore.elevationNow(lat, lon) }
            }
            if (RouteStore.route.value !== route) return@launch
            routeProfile = profile
            _routeClimbs.value = withContext(Dispatchers.Default) { app.painmeter.core.climb.ClimbFinder.find(profile, settings) }
        }
    }

    private fun updateClimb(fix: Fix, snap: RideSnapshot, along: Double?) {
        val profile = routeProfile?.takeIf { it.route === _nav.value?.route }
        val ele = if (along != null && profile != null) profile.elevationAt(along) else fix.demAltitudeM ?: fix.altitudeM ?: return
        val climbs = _routeClimbs.value
        val rc = if (along != null && profile != null) climbs.firstOrNull { along >= it.startM && along < it.endM - 5 } else null

        val wasActive = detector.active
        if (rc != null && !detector.active) detector.startRouteClimb(rc)
        var p = detector.add(
            app.painmeter.core.climb.ClimbSample(fix.timeMs, snap.distanceM, ele, snap.power.power1Hz, snap.hrBpm),
            onRouteClimb = rc != null,
        )
        val onRouteClimb = p?.fromRoute
        if (onRouteClimb != null && !p!!.finished && (along == null || along >= onRouteClimb.endM - 5)) p = detector.endRouteClimb()
        if (!wasActive && detector.active) _climbEvents.tryEmit(ClimbEvent.STARTED)
        if (p?.finished == true) {
            finishedClimb = p
            finishedAtMs = fix.timeMs
            _climbEvents.tryEmit(ClimbEvent.FINISHED)
        }

        val showFinished = finishedClimb?.takeIf { fix.timeMs - finishedAtMs <= 10_000 && !detector.active }
        val shown = if (detector.active) p else showFinished
        val r = shown?.fromRoute
        val next = if (along != null) climbs.firstOrNull { it.startM > along } else null
        _climb.value = ClimbUi(
            progress = shown,
            routeProfile = if (r != null && profile != null) {
                generateSequence(r.startM) { it + 20 }.takeWhile { it <= r.endM }.map { (it - r.startM) to profile.elevationAt(it) }.toList()
            } else null,
            riderOnClimbM = if (r != null && along != null) (along - r.startM).coerceIn(0.0, r.lengthM) else null,
            remainingM = if (r != null && along != null && !shown.finished) (r.endM - along).coerceAtLeast(0.0) else null,
            remainingGainM = if (r != null && along != null && profile != null && !shown.finished) {
                (profile.elevationAt(r.endM) - profile.elevationAt(along)).coerceAtLeast(0.0)
            } else null,
            climbNumber = r?.let { it.index + 1 },
            climbCount = climbs.size,
            next = next,
            distanceToNextM = next?.let { it.startM - along!! },
        )
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
        if (_calibrationMode.value) lastFix?.let { presses += it; updateCalibRuns() }
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
        // A climb still going at the stop counts up to here.
        if (detector.active) detector.endRouteClimb()
        val climbs = detector.climbs.toList()
        val wasCalibration = _calibrationMode.value
        _calibrationMode.value = false
        scope.launch {
            _finished.value = export(dir, snap, laps).copy(climbs = climbs)
            if (wasCalibration) calibrate(dir)
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

    // ------------------------------------------------------------ history

    /** History lines, newest first, computed in the background (cached per ride). */
    suspend fun history(): List<Pair<File, RideStore.Summary>> = withContext(Dispatchers.IO) {
        store.finished().mapNotNull { dir ->
            val cached = store.readSummary(dir)
            if (cached != null) return@mapNotNull dir to cached
            val entries = store.read(dir)
            val end = entries.maxOfOrNull { it.timeMs } ?: return@mapNotNull null
            val snap = RideSession.replay(entries, rideSettings()).snapshot(end)
            store.writeSummary(dir, snap)
            store.readSummary(dir)?.let { dir to it }
        }
    }

    /** Opens a past ride's summary (rebuilt from its journal; climbs are not kept). */
    fun openRide(dir: File) {
        scope.launch {
            _finished.value = withContext(Dispatchers.IO) {
                val entries = store.read(dir)
                val end = entries.maxOf { it.timeMs }
                val s = RideSession.replay(entries, rideSettings())
                FinishedRide(dir, s.snapshot(end), s.completedLaps, store.fit(dir).takeIf { it.exists() })
            }
        }
    }

    fun deleteRide(dir: File) = store.delete(dir)

    fun closeSummary() {
        _finished.value = null
        refreshUnfinished()
    }

    private suspend fun export(dir: File, snap: RideSnapshot, laps: List<LapSummary>): FinishedRide =
        withContext(Dispatchers.IO) {
            val fit = store.fit(dir)
            try {
                store.writeSummary(dir, snap)
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
