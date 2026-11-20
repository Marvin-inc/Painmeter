package app.painmeter.core

import app.painmeter.core.power.ElevationSource
import app.painmeter.core.power.LivePowerEngine
import app.painmeter.core.power.PowerInput
import app.painmeter.core.power.PowerParams
import app.painmeter.core.power.PowerState
import app.painmeter.core.power.PowerStats
import app.painmeter.core.power.WeatherNow
import app.painmeter.core.run.CadenceMeter
import app.painmeter.core.run.Gap
import app.painmeter.core.run.PaceFilter
import app.painmeter.core.run.RunSettings
import app.painmeter.core.run.RunStats
import app.painmeter.core.run.Sport

/** Thresholds of SPEC §3. Speeds in m/s. */
data class RideSettings(
    val autoPause: Boolean = true,
    val autoPauseBelowMps: Double = 2.0 / 3.6,
    val autoPauseAfterMs: Long = 3_000,
    val autoResumeAboveMps: Double = 4.0 / 3.6,
    /** Fixes less accurate than this are not used for distance or elevation. */
    val maxAccuracyM: Double = 25.0,
    /** Elevation gain counts a rise only once it exceeds this since the last low point. */
    val elevationHysteresisM: Double = 4.0,
    val hrZones: HrZones = HrZones.fromMaxHr(185),
    val power: PowerParams = PowerParams(),
    /** Used when the ride's sport is RUN (set by its Start entry). */
    val run: RunSettings = RunSettings(),
)

/** Time-weighted heart-rate statistics over the recorded time. */
class HrStats {
    private var weightedSum = 0.0
    private var weightMs = 0L
    var max: Int? = null
        private set
    /** Milliseconds spent in zones 1..5 (index 0..4). */
    val zoneMs = LongArray(5)

    fun add(bpm: Int, weightMs: Long, zone: Int) {
        if (weightMs <= 0) return
        weightedSum += bpm.toDouble() * weightMs
        this.weightMs += weightMs
        zoneMs[zone - 1] += weightMs
        max = maxOf(max ?: bpm, bpm)
    }

    val average: Int? get() = if (weightMs > 0) Math.round(weightedSum / weightMs).toInt() else null
}

enum class RideStatus { IDLE, RECORDING, PAUSED, FINISHED }

data class LapSummary(
    val index: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val timerMs: Long,
    val distanceM: Double,
    val ascentM: Double,
    val endedBySession: Boolean,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val avgPower: Double? = null,
    val maxPower: Double? = null,
    /** Closed by the auto lap (every km when running). */
    val auto: Boolean = false,
    val descentM: Double = 0.0,
    val avgCadenceSpm: Double? = null,
    /** Average grade-adjusted speed (running), m/s. */
    val gapSpeedMps: Double? = null,
) {
    val avgSpeedMps: Double get() = if (timerMs > 0) distanceM / (timerMs / 1000.0) else 0.0
}

/** Everything the screens need, at one instant. */
data class RideSnapshot(
    val status: RideStatus,
    val autoPaused: Boolean,
    val startTimeMs: Long?,
    val rideTimeMs: Long,
    val distanceM: Double,
    val speedMps: Double?,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val elevationGainM: Double,
    val lapNumber: Int,
    val lapTimeMs: Long,
    val lapDistanceM: Double,
    val lastLap: LapSummary?,
    val gpsAccuracyM: Double?,
    val gpsAgeMs: Long?,
    /** Latest heart rate, null when no reading in the last 5 s. */
    val hrBpm: Int? = null,
    val hrZone: Int? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val lapAvgHr: Int? = null,
    /** Recorded time spent in HR zones 1..5, milliseconds. */
    val hrZoneMs: List<Long> = List(5) { 0L },
    /** Live power: windows, gradient, reliability, drafting, air (SPEC §5). */
    val power: PowerState = PowerState(),
    val avgPower: Double? = null,
    val maxPower: Double? = null,
    val normalizedPower: Double? = null,
    val lapAvgPower: Double? = null,
    val ftpW: Int = 250,
    val sport: Sport = Sport.BIKE,
    val elevationLossM: Double = 0.0,
    /** Running: speed averaged over 10 s for the pace display, m/s (null when stopped / no fix). */
    val paceSpeedMps: Double? = null,
    /** Running: grade-adjusted speed now (smoothed speed × GAP factor of the current gradient). */
    val gapSpeedMps: Double? = null,
    val avgGapSpeedMps: Double? = null,
    val lapGapSpeedMps: Double? = null,
    /** Running: steps per minute over the last 10 s, from the phone's step detector. */
    val cadenceSpm: Double? = null,
    val avgCadenceSpm: Double? = null,
    val lapAvgCadenceSpm: Double? = null,
    /** Distance at which the current lap ends (auto lap), for "next km in". */
    val autoLapM: Double? = null,
) {
    val lapAvgSpeedMps: Double get() = if (lapTimeMs > 0) lapDistanceM / (lapTimeMs / 1000.0) else 0.0
}

/**
 * Elevation gain with hysteresis. While climbing, every new high counts. A climb only starts
 * once the altitude has risen more than [hysteresisM] above the lowest point, and a descent only
 * once it has dropped more than [hysteresisM] below the highest point. Noise smaller than the
 * band never adds gain, and a real climb is counted in full from its low point.
 */
class ElevationGain(private val hysteresisM: Double) {
    var totalM: Double = 0.0
        private set
    private var ref: Double? = null
    private var climbing = false

    fun add(altitudeM: Double) {
        val r = ref ?: run { ref = altitudeM; return }
        if (climbing) {
            if (altitudeM > r) { totalM += altitudeM - r; ref = altitudeM }
            else if (altitudeM < r - hysteresisM) { climbing = false; ref = altitudeM }
        } else {
            if (altitudeM < r) ref = altitudeM
            else if (altitudeM > r + hysteresisM) { climbing = true; totalM += altitudeM - r; ref = altitudeM }
        }
    }
}

/**
 * The ride as a state machine. Every change goes through [apply] with a [JournalEntry], so a
 * ride rebuilt from its journal ([replay]) is identical to the live one. The live methods
 * ([start], [onFix], [lap], [pause], [resume], [stop]) return the entries they produced, for the
 * caller to append to the journal.
 */
class RideSession(private val settings: RideSettings = RideSettings()) {

    var status = RideStatus.IDLE
        private set
    private var autoPaused = false
    private var startTimeMs: Long? = null

    private var timerMs = 0L
    private var segmentStartMs: Long? = null

    private var distanceM = 0.0
    private var maxSpeedMps = 0.0
    private var lastFix: Fix? = null
    private var lastGoodFix: Fix? = null
    private val elevation = ElevationGain(settings.elevationHysteresisM)
    /** Descent, counted like the gain on the altitude turned upside down. */
    private val descent = ElevationGain(settings.elevationHysteresisM)
    private var lapStartDescentM = 0.0

    var sport = Sport.BIKE
        private set
    private val pace = PaceFilter()
    private val cadence = CadenceMeter()
    private var rideRun = RunStats()
    private var lapRun = RunStats()
    private var lastSteps: Pair<Long, Long>? = null

    private val laps = mutableListOf<LapSummary>()
    private var lapStartTimeMs = 0L
    private var lapStartTimerMs = 0L
    private var lapStartDistanceM = 0.0
    private var lapStartAscentM = 0.0

    private var slowSinceMs: Long? = null

    private var lastHrTimeMs: Long? = null
    private var lastHrBpm: Int? = null
    private var rideHr = HrStats()
    private var lapHr = HrStats()

    private val engine = LivePowerEngine(settings.power)
    private val ridePower = PowerStats()
    private var lapPower = PowerStats()

    val completedLaps: List<LapSummary> get() = laps

    // ---------------------------------------------------------------- live commands

    fun start(timeMs: Long, sport: Sport = Sport.BIKE): List<JournalEntry> = emit(JournalEntry.Start(timeMs, sport))

    fun onFix(raw: Fix): List<JournalEntry> {
        if (status != RideStatus.RECORDING && status != RideStatus.PAUSED) return emptyList()
        val fix = withSpeed(raw)
        val out = mutableListOf<JournalEntry>()
        val speed = if (settings.autoPause) fix.speedMps else null
        val run = sport == Sport.RUN
        val resumeAbove = if (run) settings.run.autoResumeAboveMps else settings.autoResumeAboveMps
        val pauseBelow = if (run) settings.run.autoPauseBelowMps else settings.autoPauseBelowMps
        val pauseAfter = if (run) settings.run.autoPauseAfterMs else settings.autoPauseAfterMs
        // Resume before the sample, so the distance covered in that second counts.
        if (speed != null && status == RideStatus.PAUSED && autoPaused && speed > resumeAbove) {
            out += emit(JournalEntry.Resume(fix.timeMs, auto = true))
        }
        out += emit(JournalEntry.Sample(fix))
        if (speed != null && status == RideStatus.RECORDING) {
            if (speed < pauseBelow) {
                val since = slowSinceMs ?: fix.timeMs.also { slowSinceMs = it }
                if (fix.timeMs - since >= pauseAfter) {
                    out += emit(JournalEntry.Pause(fix.timeMs, auto = true))
                }
            } else {
                slowSinceMs = null
            }
        }
        // Running: a lap every km (SPEC §16.3).
        val every = settings.run.autoLapM
        if (run && every != null && status == RideStatus.RECORDING && distanceM - lapStartDistanceM >= every) {
            out += emit(JournalEntry.Lap(fix.timeMs, auto = true))
        }
        return out
    }

    /** The step total from the phone's step detector (running), recorded about once a second. */
    fun onSteps(timeMs: Long, total: Long): List<JournalEntry> =
        if (status == RideStatus.RECORDING || status == RideStatus.PAUSED) emit(JournalEntry.Steps(timeMs, total)) else emptyList()

    /** A heart-rate reading from the strap. */
    fun onHeartRate(timeMs: Long, bpm: Int): List<JournalEntry> =
        if ((status == RideStatus.RECORDING || status == RideStatus.PAUSED) && bpm in 25..250)
            emit(JournalEntry.HeartRate(timeMs, bpm)) else emptyList()

    /**
     * Some sources (the emulator, some phones without a GPS lock) give no Doppler speed: derive it
     * from the distance to the previous fix, so speed and auto-pause still work.
     */
    private fun withSpeed(fix: Fix): Fix {
        if (fix.speedMps != null) return fix
        val prev = lastFix ?: return fix
        val dt = (fix.timeMs - prev.timeMs) / 1000.0
        if (dt <= 0 || dt > 10) return fix
        return fix.copy(speedMps = Geo.distanceM(prev, fix) / dt)
    }

    /** Solo / On a wheel (SPEC §6). */
    fun setDraft(timeMs: Long, onWheel: Boolean): List<JournalEntry> =
        if (status != RideStatus.IDLE && status != RideStatus.FINISHED && engine.drafting != onWheel)
            emit(JournalEntry.Draft(timeMs, onWheel)) else emptyList()

    fun onWeather(timeMs: Long, w: WeatherNow): List<JournalEntry> =
        if (status != RideStatus.IDLE && status != RideStatus.FINISHED) emit(JournalEntry.Weather(timeMs, w)) else emptyList()

    val drafting: Boolean get() = engine.drafting

    fun lap(timeMs: Long): List<JournalEntry> =
        if (status == RideStatus.RECORDING || status == RideStatus.PAUSED) emit(JournalEntry.Lap(timeMs)) else emptyList()

    fun pause(timeMs: Long): List<JournalEntry> =
        if (status == RideStatus.RECORDING) emit(JournalEntry.Pause(timeMs, auto = false)) else emptyList()

    fun resume(timeMs: Long): List<JournalEntry> =
        if (status == RideStatus.PAUSED) emit(JournalEntry.Resume(timeMs, auto = false)) else emptyList()

    fun stop(timeMs: Long): List<JournalEntry> =
        if (status == RideStatus.RECORDING || status == RideStatus.PAUSED) emit(JournalEntry.Stop(timeMs)) else emptyList()

    private fun emit(e: JournalEntry): List<JournalEntry> {
        apply(e)
        return listOf(e)
    }

    // ---------------------------------------------------------------- state changes

    fun apply(e: JournalEntry) {
        when (e) {
            is JournalEntry.Start -> {
                status = RideStatus.RECORDING
                sport = e.sport
                startTimeMs = e.timeMs
                segmentStartMs = e.timeMs
                lapStartTimeMs = e.timeMs
            }
            is JournalEntry.Sample -> applyFix(e.fix)
            is JournalEntry.HeartRate -> applyHr(e.timeMs, e.bpm)
            is JournalEntry.Draft -> engine.setDrafting(e.onWheel)
            is JournalEntry.Weather -> engine.setWeather(e.weather)
            is JournalEntry.Lap -> closeLap(e.timeMs, bySession = false, auto = e.auto)
            is JournalEntry.Steps -> applySteps(e.timeMs, e.total)
            is JournalEntry.Pause -> if (status == RideStatus.RECORDING) {
                timerMs += e.timeMs - (segmentStartMs ?: e.timeMs)
                segmentStartMs = null
                status = RideStatus.PAUSED
                autoPaused = e.auto
                slowSinceMs = null
                engine.reset()
                pace.reset()
                cadence.reset()
            }
            is JournalEntry.Resume -> if (status == RideStatus.PAUSED) {
                status = RideStatus.RECORDING
                segmentStartMs = e.timeMs
                autoPaused = false
            }
            is JournalEntry.Stop -> {
                if (status == RideStatus.RECORDING) timerMs += e.timeMs - (segmentStartMs ?: e.timeMs)
                segmentStartMs = null
                closeLap(e.timeMs, bySession = true)
                status = RideStatus.FINISHED
                autoPaused = false
            }
        }
    }

    private fun applyFix(fix: Fix) {
        val good = fix.accuracyM == null || fix.accuracyM <= settings.maxAccuracyM
        var step = 0.0
        if (status == RideStatus.RECORDING && good) {
            lastGoodFix?.let { step = Geo.distanceM(it, fix); distanceM += step }
            (fix.demAltitudeM ?: fix.altitudeM)?.let { elevation.add(it); descent.add(-it) }
            speedOf(fix)?.let { if (it > maxSpeedMps) maxSpeedMps = it }
        }
        if (status == RideStatus.RECORDING) {
            val source = when {
                fix.routeGradePercent != null -> ElevationSource.ROUTE
                fix.demAltitudeM != null -> ElevationSource.DEM
                fix.altitudeM != null -> ElevationSource.GPS
                else -> ElevationSource.NONE
            }
            val p = engine.onInput(
                PowerInput(
                    fix.timeMs, fix.lat, fix.lon, fix.speedMps, fix.demAltitudeM ?: fix.altitudeM, source, fix.accuracyM,
                    gradeOverridePercent = fix.routeGradePercent ?: fix.terrainGradePercent,
                )
            )
            if (sport == Sport.BIKE) p.power1Hz?.let { ridePower.add(it, p.power30s); lapPower.add(it, p.power30s) }
            if (sport == Sport.RUN) {
                pace.add(fix.timeMs, fix.speedMps)
                if (step > 0) {
                    rideRun.addDistance(step, p.gradePercent)
                    lapRun.addDistance(step, p.gradePercent)
                }
            }
        }
        // While paused the position still moves on, so a resume does not add the distance walked.
        if (good) lastGoodFix = fix
        lastFix = fix
    }

    private fun speedOf(fix: Fix): Double? = fix.speedMps

    private fun applyHr(timeMs: Long, bpm: Int) {
        if (status == RideStatus.RECORDING) {
            // Each reading counts for the time since the previous one (1 s for the first), capped at
            // 2 s so a strap that drops out does not stretch its last value.
            val weight = lastHrTimeMs?.let { (timeMs - it).coerceIn(0, 2_000) } ?: 1_000
            val zone = settings.hrZones.zoneOf(bpm)
            rideHr.add(bpm, weight, zone)
            lapHr.add(bpm, weight, zone)
        }
        lastHrTimeMs = timeMs
        lastHrBpm = bpm
    }

    private fun applySteps(timeMs: Long, total: Long) {
        val last = lastSteps
        lastSteps = timeMs to total
        if (status != RideStatus.RECORDING) return
        cadence.add(timeMs, total)
        if (last == null || total < last.second) return
        // Each reading counts for the time since the previous one, capped at 2 s (as the HR).
        val over = (timeMs - last.first).coerceIn(0, 2_000)
        val n = total - last.second
        rideRun.addSteps(n, over)
        lapRun.addSteps(n, over)
    }

    private fun closeLap(timeMs: Long, bySession: Boolean, auto: Boolean = false) {
        val timerNow = currentTimerMs(timeMs)
        laps += LapSummary(
            index = laps.size,
            startTimeMs = lapStartTimeMs,
            endTimeMs = timeMs,
            timerMs = timerNow - lapStartTimerMs,
            distanceM = distanceM - lapStartDistanceM,
            ascentM = elevation.totalM - lapStartAscentM,
            endedBySession = bySession,
            avgHr = lapHr.average,
            maxHr = lapHr.max,
            avgPower = lapPower.average,
            maxPower = lapPower.max.takeIf { lapPower.average != null },
            auto = auto,
            descentM = descent.totalM - lapStartDescentM,
            avgCadenceSpm = lapRun.avgCadenceSpm,
            gapSpeedMps = lapRun.gapSpeedMps(timerNow - lapStartTimerMs),
        )
        lapHr = HrStats()
        lapPower = PowerStats()
        lapRun = RunStats()
        lapStartDescentM = descent.totalM
        lapStartTimeMs = timeMs
        lapStartTimerMs = timerNow
        lapStartDistanceM = distanceM
        lapStartAscentM = elevation.totalM
    }

    private fun currentTimerMs(nowMs: Long): Long =
        timerMs + (segmentStartMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0L)

    // ---------------------------------------------------------------- read

    fun snapshot(nowMs: Long): RideSnapshot {
        val timer = currentTimerMs(nowMs)
        val fix = lastFix
        val age = fix?.let { nowMs - it.timeMs }
        val fresh = age != null && age <= 5_000
        return RideSnapshot(
            status = status,
            autoPaused = autoPaused,
            startTimeMs = startTimeMs,
            rideTimeMs = timer,
            distanceM = distanceM,
            speedMps = if (fresh) fix?.speedMps else null,
            avgSpeedMps = if (timer > 0) distanceM / (timer / 1000.0) else 0.0,
            maxSpeedMps = maxSpeedMps,
            elevationGainM = elevation.totalM,
            lapNumber = laps.size + 1,
            lapTimeMs = timer - lapStartTimerMs,
            lapDistanceM = distanceM - lapStartDistanceM,
            lastLap = laps.lastOrNull(),
            gpsAccuracyM = if (fresh) fix?.accuracyM else null,
            gpsAgeMs = age,
            hrBpm = currentHr(nowMs),
            hrZone = currentHr(nowMs)?.let { settings.hrZones.zoneOf(it) },
            avgHr = rideHr.average,
            maxHr = rideHr.max,
            lapAvgHr = lapHr.average,
            hrZoneMs = rideHr.zoneMs.toList(),
            power = if (status == RideStatus.RECORDING) engine.state else engine.state.copy(power10s = null, power30s = null, power1Hz = null, powerShort = null),
            avgPower = ridePower.average,
            maxPower = ridePower.max.takeIf { ridePower.average != null },
            normalizedPower = ridePower.normalized,
            lapAvgPower = lapPower.average,
            ftpW = settings.power.ftpW,
            sport = sport,
            elevationLossM = descent.totalM,
        ).let { if (sport == Sport.RUN) withRun(it, nowMs, timer) else it }
    }

    /** Running: pace, GAP and cadence; no power (the bike model means nothing on foot). */
    private fun withRun(s: RideSnapshot, nowMs: Long, timer: Long): RideSnapshot {
        val recording = status == RideStatus.RECORDING
        val v = if (recording && s.speedMps != null) pace.speedMps(nowMs) else null
        val grade = engine.state.gradePercent
        return s.copy(
            avgPower = null, maxPower = null, normalizedPower = null, lapAvgPower = null,
            paceSpeedMps = v,
            gapSpeedMps = v?.let { it * (grade?.let(Gap::factor) ?: 1.0) },
            avgGapSpeedMps = rideRun.gapSpeedMps(timer),
            lapGapSpeedMps = lapRun.gapSpeedMps(timer - lapStartTimerMs),
            cadenceSpm = if (recording) cadence.spm(nowMs) else null,
            avgCadenceSpm = rideRun.avgCadenceSpm,
            lapAvgCadenceSpm = lapRun.avgCadenceSpm,
            autoLapM = settings.run.autoLapM?.let { lapStartDistanceM + it },
        )
    }

    private fun currentHr(nowMs: Long): Int? {
        val t = lastHrTimeMs ?: return null
        return if (nowMs - t <= 5_000) lastHrBpm else null
    }

    val hrZones: HrZones get() = settings.hrZones

    companion object {
        /** Rebuilds a ride from its journal (crash recovery, export, history). */
        fun replay(entries: List<JournalEntry>, settings: RideSettings = RideSettings()): RideSession =
            RideSession(settings).also { s -> entries.forEach(s::apply) }
    }
}
