package app.painmeter

import android.content.Context
import app.painmeter.core.JournalEntry
import app.painmeter.core.JournalReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One folder per ride under `files/rides/`, holding `journal.txt` and, once finished, `ride.fit`. */
class RideStore(context: Context) {
    private val root = File(context.filesDir, "rides").apply { mkdirs() }

    fun newRideDir(startMs: Long): File {
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(startMs))
        return File(root, name).apply { mkdirs() }
    }

    fun journal(dir: File) = File(dir, "journal.txt")
    fun fit(dir: File) = File(dir, "ride.fit")

    fun read(dir: File): List<JournalEntry> = JournalReader.read(journal(dir))

    /** Stopped rides, newest first. */
    fun finished(): List<File> =
        (root.listFiles()?.toList() ?: emptyList())
            .filter { it.isDirectory && (fit(it).exists() || File(it, "summary.txt").exists() || read(it).any { e -> e is JournalEntry.Stop }) }
            .sortedByDescending { it.name }

    fun delete(dir: File) {
        if (dir.parentFile == root) dir.deleteRecursively()
    }

    /** One line in the history list, cached next to the journal. */
    data class Summary(
        val startMs: Long, val rideTimeMs: Long, val distanceM: Double, val gainM: Double,
        val avgPower: Double?, val np: Double?, val avgHr: Int?,
    )

    fun writeSummary(dir: File, s: app.painmeter.core.RideSnapshot) {
        File(dir, "summary.txt").writeText(
            listOf(s.startTimeMs ?: 0, s.rideTimeMs, s.distanceM, s.elevationGainM, s.avgPower ?: "", s.normalizedPower ?: "", s.avgHr ?: "")
                .joinToString(",")
        )
    }

    fun readSummary(dir: File): Summary? = try {
        val f = File(dir, "summary.txt").readText().trim().split(',')
        Summary(f[0].toLong(), f[1].toLong(), f[2].toDouble(), f[3].toDouble(), f[4].toDoubleOrNull(), f[5].toDoubleOrNull(), f[6].toIntOrNull())
    } catch (_: Exception) {
        null
    }

    /** Rides that were started and never stopped (app or phone died), newest first. */
    fun unfinished(): List<File> =
        (root.listFiles()?.toList() ?: emptyList())
            .filter { it.isDirectory }
            .filter { dir ->
                val entries = read(dir)
                entries.any { it is JournalEntry.Start } && entries.none { it is JournalEntry.Stop }
            }
            .sortedByDescending { it.name }
}
