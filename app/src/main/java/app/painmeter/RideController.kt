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
        if (!::store.isInitialized) store = RideStore(context.applicationContext)
        HrMonitor.onReading = { t, bpm -> onHeartRate(t, bpm) }
        refreshUnfinished()
    }

    private fun rideSettings() = Settings.current.value.toRideSettings()

    fun refreshUnfinished() {
        _unfinished.value = if (isActive) null else store.unfinished().firstOrNull()
    }

    // ------------------------------------------------------------ lifecycle (called by RideService)

    fun startNewRide(nowMs: Long) {
        if (isActive) return
        val dir = store.newRideDir(nowMs)
        open(dir, RideSession(rideSettings()))
        record(session!!.start(nowMs))
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

    fun onFix(fix: Fix) {
        val s = session ?: return
        record(s.onFix(fix))
        publish(System.currentTimeMillis())
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
