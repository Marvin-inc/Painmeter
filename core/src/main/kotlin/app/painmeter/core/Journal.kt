package app.painmeter.core

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.Locale

/**
 * What happened during a ride, in order. The journal is the ride's source of truth: the live
 * state, the crash recovery and the FIT export are all rebuilt from it.
 */
sealed interface JournalEntry {
    val timeMs: Long

    data class Start(override val timeMs: Long) : JournalEntry
    data class Sample(val fix: Fix) : JournalEntry {
        override val timeMs: Long get() = fix.timeMs
    }
    data class HeartRate(override val timeMs: Long, val bpm: Int) : JournalEntry
    data class Lap(override val timeMs: Long) : JournalEntry
    data class Pause(override val timeMs: Long, val auto: Boolean) : JournalEntry
    data class Resume(override val timeMs: Long, val auto: Boolean) : JournalEntry
    data class Stop(override val timeMs: Long) : JournalEntry
}

/**
 * One line of text per entry, comma separated, empty field = unknown:
 * ```
 * START,<t>
 * S,<t>,<lat>,<lon>,<alt>,<speed>,<accuracy>
 * H,<t>,<bpm>
 * LAP,<t>
 * PAUSE,<t>,<A|M>      RESUME,<t>,<A|M>
 * STOP,<t>
 * ```
 */
object JournalCodec {
    fun encode(e: JournalEntry): String = when (e) {
        is JournalEntry.Start -> "START,${e.timeMs}"
        is JournalEntry.Sample -> with(e.fix) {
            "S,$timeMs,${num(lat, 7)},${num(lon, 7)},${num(altitudeM, 1)},${num(speedMps, 2)},${num(accuracyM, 1)}"
        }
        is JournalEntry.HeartRate -> "H,${e.timeMs},${e.bpm}"
        is JournalEntry.Lap -> "LAP,${e.timeMs}"
        is JournalEntry.Pause -> "PAUSE,${e.timeMs},${if (e.auto) "A" else "M"}"
        is JournalEntry.Resume -> "RESUME,${e.timeMs},${if (e.auto) "A" else "M"}"
        is JournalEntry.Stop -> "STOP,${e.timeMs}"
    }

    /** Returns null for a line that cannot be read (for example the last line cut by a crash). */
    fun decode(line: String): JournalEntry? = try {
        val f = line.trim().split(',')
        when (f[0]) {
            "START" -> JournalEntry.Start(f[1].toLong())
            "S" -> if (f.size < 7) null else JournalEntry.Sample(
                Fix(
                    timeMs = f[1].toLong(),
                    lat = f[2].toDouble(),
                    lon = f[3].toDouble(),
                    altitudeM = f[4].toDoubleOrNull(),
                    speedMps = f[5].toDoubleOrNull(),
                    accuracyM = f[6].toDoubleOrNull(),
                )
            )
            "H" -> JournalEntry.HeartRate(f[1].toLong(), f[2].toInt())
            "LAP" -> JournalEntry.Lap(f[1].toLong())
            "PAUSE" -> JournalEntry.Pause(f[1].toLong(), f[2] == "A")
            "RESUME" -> JournalEntry.Resume(f[1].toLong(), f[2] == "A")
            "STOP" -> JournalEntry.Stop(f[1].toLong())
            else -> null
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun num(v: Double?, decimals: Int): String =
        if (v == null || v.isNaN()) "" else String.format(Locale.ROOT, "%.${decimals}f", v)
}

/** Appends entries to a file, flushing to the OS after each one so a crash loses at most one line. */
class JournalWriter(file: File) : AutoCloseable {
    private val stream = FileOutputStream(file, true)
    private val writer: Writer = OutputStreamWriter(stream, Charsets.UTF_8)

    fun append(entry: JournalEntry) {
        writer.write(JournalCodec.encode(entry))
        writer.write("\n")
        writer.flush()
    }

    /** Forces the data to the storage device (slower; called on lap, pause and stop). */
    fun sync() {
        writer.flush()
        stream.fd.sync()
    }

    override fun close() {
        writer.flush()
        writer.close()
    }
}

object JournalReader {
    fun read(file: File): List<JournalEntry> =
        if (!file.exists()) emptyList()
        else file.readLines(Charsets.UTF_8).mapNotNull { if (it.isBlank()) null else JournalCodec.decode(it) }
}
