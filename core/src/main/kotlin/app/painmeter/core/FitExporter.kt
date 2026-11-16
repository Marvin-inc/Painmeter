package app.painmeter.core

import com.garmin.fit.Activity
import com.garmin.fit.ActivityMesg
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
                    is JournalEntry.Lap, is JournalEntry.HeartRate -> Unit
                    is JournalEntry.Sample -> {
                        // FIT timestamps are whole seconds: keep one record per second, while recording.
                        val second = e.fix.timeMs / 1000
                        if (session.status == RideStatus.RECORDING && second > lastRecordSecond) {
                            lastRecordSecond = second
                            val snap = session.snapshot(e.fix.timeMs)
                            encoder.write(record(e.fix, snap.distanceM, snap.hrBpm))
                        }
                    }
                }
            }

            val end = stopTimeMs ?: lastTimeMs.also { session.apply(JournalEntry.Stop(it)) }
            val snap = session.snapshot(end)
            val laps = session.completedLaps
            laps.forEach { encoder.write(lapMesg(it)) }

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
                snap.maxHr?.let { maxHeartRate = it.toShort() }
                snap.hrZoneMs.forEachIndexed { i, ms -> setTimeInHrZone(i, (ms / 1000.0).toFloat()) }
                sport = Sport.CYCLING
                subSport = SubSport.ROAD
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

    private fun record(fix: Fix, distanceM: Double, hr: Int?) = RecordMesg().apply {
        hr?.let { heartRate = it.toShort() }
        timestamp = dt(fix.timeMs)
        positionLat = (fix.lat * SEMICIRCLES_PER_DEGREE).roundToLong().toInt()
        positionLong = (fix.lon * SEMICIRCLES_PER_DEGREE).roundToLong().toInt()
        distance = distanceM.toFloat()
        fix.speedMps?.let { enhancedSpeed = it.toFloat() }
        fix.altitudeM?.let { enhancedAltitude = it.toFloat() }
    }

    private fun lapMesg(l: LapSummary) = LapMesg().apply {
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
        l.maxHr?.let { maxHeartRate = it.toShort() }
        sport = Sport.CYCLING
        event = Event.LAP
        eventType = EventType.STOP
        lapTrigger = if (l.endedBySession) LapTrigger.SESSION_END else LapTrigger.MANUAL
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
