package app.painmeter.core

import com.garmin.fit.Activity
import com.garmin.fit.ActivityMesg
import com.garmin.fit.DeveloperDataIdMesg
import com.garmin.fit.DeveloperField
import com.garmin.fit.FieldDescriptionMesg
import com.garmin.fit.FitBaseType
import com.garmin.fit.MesgNum
import com.garmin.fit.DateTime
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.Fit
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.LapMesg
import com.garmin.fit.LapTrigger
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.SessionTrigger
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import app.painmeter.core.run.Sport as RideSport
import java.io.File
import java.util.Date
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import com.garmin.fit.File as FitFileType

/**
 * Writes a ride journal as a FIT activity file: file id, timer events, 1 Hz records while
 * recording, one lap message per lap, one session and the activity message.
 */
object FitExporter {

    private const val SEMICIRCLES_PER_DEGREE = 2147483648.0 / 180.0

    fun export(entries: List<JournalEntry>, out: File, settings: RideSettings = RideSettings()) {
        val start = entries.firstOrNull { it is JournalEntry.Start }?.timeMs
            ?: throw IllegalArgumentException("Journal has no start")

        // Replay alongside writing, so records carry the cumulative distance the session computed.
        val session = RideSession(settings)
        val encoder = FileEncoder(out, Fit.ProtocolVersion.V2_0)
        try {
            encoder.write(FileIdMesg().apply {
                type = FitFileType.ACTIVITY
                manufacturer = Manufacturer.DEVELOPMENT
                product = 1
                serialNumber = 1L
                timeCreated = dt(start)
            })
            val dev = DevFields.write(encoder)

            var lastRecordSecond = Long.MIN_VALUE
            var lastTimeMs = start
            var stopTimeMs: Long? = null
            for (e in entries) {
                session.apply(e)
                lastTimeMs = maxOf(lastTimeMs, e.timeMs)
                when (e) {
                    is JournalEntry.Start -> encoder.write(timerEvent(e.timeMs, EventType.START))
                    is JournalEntry.Pause -> encoder.write(timerEvent(e.timeMs, EventType.STOP_ALL))
                    is JournalEntry.Resume -> encoder.write(timerEvent(e.timeMs, EventType.START))
                    is JournalEntry.Stop -> {
                        encoder.write(timerEvent(e.timeMs, EventType.STOP_ALL))
                        stopTimeMs = e.timeMs
                    }
                    is JournalEntry.Lap, is JournalEntry.HeartRate, is JournalEntry.Draft, is JournalEntry.Weather,
                    is JournalEntry.Steps -> Unit
                    is JournalEntry.Sample -> {
                        // FIT timestamps are whole seconds: keep one record per second, while recording.
                        val second = e.fix.timeMs / 1000
                        if (session.status == RideStatus.RECORDING && second > lastRecordSecond) {
                            lastRecordSecond = second
                            val snap = session.snapshot(e.fix.timeMs)
                            encoder.write(record(e.fix, snap, dev))
                        }
                    }
                }
            }

            val end = stopTimeMs ?: lastTimeMs.also { session.apply(JournalEntry.Stop(it)) }
            val snap = session.snapshot(end)
            val laps = session.completedLaps
            val run = session.sport == RideSport.RUN
            laps.forEach { encoder.write(lapMesg(it, run)) }

            encoder.write(SessionMesg().apply {
                messageIndex = 0
                timestamp = dt(end)
                startTime = dt(start)
                totalElapsedTime = ((end - start) / 1000.0).toFloat()
                totalTimerTime = (snap.rideTimeMs / 1000.0).toFloat()
                totalDistance = snap.distanceM.toFloat()
                totalAscent = snap.elevationGainM.roundToInt()
                avgSpeed = snap.avgSpeedMps.toFloat()
                maxSpeed = snap.maxSpeedMps.toFloat()
                enhancedAvgSpeed = snap.avgSpeedMps.toFloat()
                enhancedMaxSpeed = snap.maxSpeedMps.toFloat()
                snap.avgHr?.let { avgHeartRate = it.toShort() }
                snap.avgPower?.let { avgPower = it.roundToInt() }
                snap.maxPower?.let { maxPower = it.roundToInt() }
                snap.normalizedPower?.let { normalizedPower = it.roundToInt() }
                if (!run) thresholdPower = snap.ftpW
                // FIT running cadence is in strides (two steps) per minute.
                snap.avgCadenceSpm?.let { avgCadence = (it / 2).toInt().toShort(); avgFractionalCadence = ((it / 2) % 1).toFloat() }
                if (run) totalDescent = snap.elevationLossM.roundToInt()
                snap.maxHr?.let { maxHeartRate = it.toShort() }
                snap.hrZoneMs.forEachIndexed { i, ms -> setTimeInHrZone(i, (ms / 1000.0).toFloat()) }
                sport = if (run) Sport.RUNNING else Sport.CYCLING
                subSport = if (run) SubSport.GENERIC else SubSport.ROAD
                firstLapIndex = 0
                numLaps = laps.size
                event = Event.SESSION
                eventType = EventType.STOP
                trigger = SessionTrigger.ACTIVITY_END
            })

            val offsetS = TimeZone.getDefault().getOffset(end) / 1000L
            encoder.write(ActivityMesg().apply {
                timestamp = dt(end)
                localTimestamp = toFitSeconds(end) + offsetS
                totalTimerTime = (snap.rideTimeMs / 1000.0).toFloat()
                numSessions = 1
                type = Activity.MANUAL
                event = Event.ACTIVITY
                eventType = EventType.STOP
            })
        } finally {
            encoder.close()
        }
    }

    private fun record(fix: Fix, snap: RideSnapshot, dev: DevFields) = RecordMesg().apply {
        val distanceM = snap.distanceM
        snap.hrBpm?.let { heartRate = it.toShort() }
        if (snap.sport == RideSport.BIKE) snap.power.power1Hz?.let { power = it.roundToInt() }
        snap.cadenceSpm?.let { cadence = (it / 2).toInt().toShort(); fractionalCadence = ((it / 2) % 1).toFloat() }
        snap.power.gradePercent?.let { grade = it.toFloat() }
        dev.addTo(this, fix, snap)
        timestamp = dt(fix.timeMs)
        positionLat = (fix.lat * SEMICIRCLES_PER_DEGREE).roundToLong().toInt()
        positionLong = (fix.lon * SEMICIRCLES_PER_DEGREE).roundToLong().toInt()
        distance = distanceM.toFloat()
        fix.speedMps?.let { enhancedSpeed = it.toFloat() }
        fix.altitudeM?.let { enhancedAltitude = it.toFloat() }
    }

    private fun lapMesg(l: LapSummary, run: Boolean) = LapMesg().apply {
        messageIndex = l.index
        timestamp = dt(l.endTimeMs)
        startTime = dt(l.startTimeMs)
        totalElapsedTime = ((l.endTimeMs - l.startTimeMs) / 1000.0).toFloat()
        totalTimerTime = (l.timerMs / 1000.0).toFloat()
        totalDistance = l.distanceM.toFloat()
        totalAscent = l.ascentM.roundToInt()
        enhancedAvgSpeed = l.avgSpeedMps.toFloat()
        avgSpeed = l.avgSpeedMps.toFloat()
        l.avgHr?.let { avgHeartRate = it.toShort() }
        l.avgPower?.let { avgPower = it.roundToInt() }
        l.maxPower?.let { maxPower = it.roundToInt() }
        l.maxHr?.let { maxHeartRate = it.toShort() }
        l.avgCadenceSpm?.let { avgCadence = (it / 2).toInt().toShort(); avgFractionalCadence = ((it / 2) % 1).toFloat() }
        if (run) totalDescent = l.descentM.roundToInt()
        sport = if (run) Sport.RUNNING else Sport.CYCLING
        event = Event.LAP
        eventType = EventType.STOP
        lapTrigger = when {
            l.endedBySession -> LapTrigger.SESSION_END
            l.auto -> LapTrigger.DISTANCE
            else -> LapTrigger.MANUAL
        }
    }

    private fun timerEvent(timeMs: Long, type: EventType) = EventMesg().apply {
        timestamp = dt(timeMs)
        event = Event.TIMER
        eventType = type
        eventGroup = 0
    }

    private fun dt(timeMs: Long) = DateTime(Date(timeMs))

    /** Seconds since the FIT epoch (1989-12-31T00:00:00Z). */
    private fun toFitSeconds(timeMs: Long): Long = timeMs / 1000 - DateTime.OFFSET / 1000
}

/**
 * Painmeter's own record fields (SPEC §10), as FIT developer fields: GPS and elevation-tile
 * altitude, On a wheel, power reliability, headwind and air density. Readable by intervals.icu,
 * the web tool and anything using the FIT SDK; ignored by Strava.
 */
class DevFields private constructor(private val id: DeveloperDataIdMesg, private val defs: List<FieldDescriptionMesg>) {

    fun addTo(r: RecordMesg, fix: Fix, snap: RideSnapshot) {
        val values: List<Number?> = listOf(
            fix.altitudeM?.toFloat(),
            fix.demAltitudeM?.toFloat(),
            if (snap.power.drafting) 1.toShort() else 0.toShort(),
            if (snap.power.reliable) 1.toShort() else 0.toShort(),
            snap.power.headwindMps.toFloat(),
            snap.power.rho.toFloat(),
        )
        defs.forEachIndexed { i, def ->
            val v = values[i] ?: return@forEachIndexed
            r.addDeveloperField(DeveloperField(def, id).apply { setValue(v) })
        }
    }

    companion object {
        private val FIELDS = listOf(
            Triple("gps_altitude", "m", FitBaseType.FLOAT32),
            Triple("dem_altitude", "m", FitBaseType.FLOAT32),
            Triple("draft_state", "", FitBaseType.UINT8),
            Triple("power_quality", "", FitBaseType.UINT8),
            Triple("wind_head", "m/s", FitBaseType.FLOAT32),
            Triple("air_density", "kg/m3", FitBaseType.FLOAT32),
        )

        fun write(encoder: FileEncoder): DevFields {
            val id = DeveloperDataIdMesg().apply {
                developerDataIndex = 0
                "painmeter.app.v1".toByteArray().take(16).forEachIndexed { i, b -> setApplicationId(i, b) }
                applicationVersion = 1L
            }
            encoder.write(id)
            val defs = FIELDS.mapIndexed { i, (name, units, type) ->
                FieldDescriptionMesg().apply {
                    developerDataIndex = 0
                    fieldDefinitionNumber = i.toShort()
                    fitBaseTypeId = type
                    setFieldName(0, name)
                    setUnits(0, units)
                    nativeMesgNum = MesgNum.RECORD
                }.also(encoder::write)
            }
            return DevFields(id, defs)
        }
    }
}
