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
