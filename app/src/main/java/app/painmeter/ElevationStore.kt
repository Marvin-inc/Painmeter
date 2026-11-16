package app.painmeter

import android.content.Context
import app.painmeter.core.elevation.HgtTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Download progress shown in Parameters. */
data class TileDownload(val done: Int, val total: Int, val failed: List<String> = emptyList(), val running: Boolean = true)

/**
 * Offline elevation tiles (SPEC §4): downloaded at home as `.hgt.gz` files (about 10–25 MB each),
 * read on demand during the ride. A tile is decompressed in the background the first time the
 * rider enters it; until it is ready, lookups return null and the GPS altitude is used.
 */
object ElevationStore {
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loaded = ConcurrentHashMap<String, HgtTile>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    private val _tiles = MutableStateFlow<List<String>>(emptyList())
    /** Names of the tiles on the phone. */
    val tiles: StateFlow<List<String>> = _tiles

    private val _download = MutableStateFlow<TileDownload?>(null)
    val download: StateFlow<TileDownload?> = _download

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "elevation").apply { mkdirs() }
        refreshList()
    }

    private fun file(name: String) = File(dir, "$name.hgt.gz")

    private fun refreshList() {
        _tiles.value = dir.listFiles()?.filter { it.name.endsWith(".hgt.gz") }?.map { it.name.take(7) }?.sorted() ?: emptyList()
        missing.clear()
    }

    fun storageBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    fun has(lat: Double, lon: Double) = file(HgtTile.nameFor(lat, lon)).exists()

    /** Ground elevation, or null when the tile is not on the phone or still being read. */
    fun elevation(lat: Double, lon: Double): Double? {
        val name = HgtTile.nameFor(lat, lon)
        loaded[name]?.let { return it.elevation(lat, lon) }
        if (name in missing || name in loading) return null
        if (!file(name).exists()) { missing += name; return null }
        loading += name
        scope.launch {
            try {
                val tile = HgtTile.read(file(name))
                // Keep at most two tiles in memory (about 26 MB each).
                if (loaded.size >= 2) loaded.keys.firstOrNull()?.let { loaded.remove(it) }
                loaded[name] = tile
            } catch (_: Exception) {
                missing += name
            } finally {
                loading -= name
            }
        }
        return null
    }

    /** Downloads the tiles within [radiusDeg] of a point (default: the 3×3 tiles around it). */
    fun downloadAround(lat: Double, lon: Double, radiusDeg: Double = 1.0) {
        if (_download.value?.running == true) return
        val names = HgtTile.tilesCovering(lat - radiusDeg, lon - radiusDeg, lat + radiusDeg, lon + radiusDeg)
            .filter { !file(it).exists() }
        _download.value = TileDownload(0, names.size, running = names.isNotEmpty())
        if (names.isEmpty()) return
        scope.launch {
            val failed = mutableListOf<String>()
            names.forEachIndexed { i, name ->
                if (!fetch(name)) failed += name
                _download.value = TileDownload(i + 1, names.size, failed.toList())
                withContext(Dispatchers.Main) { refreshList() }
            }
            _download.value = TileDownload(names.size, names.size, failed, running = false)
        }
    }

    /** Downloads one tile; false if it failed. Sea tiles do not exist on the server (404): not an error. */
    private fun fetch(name: String): Boolean {
        val tmp = File(dir, "$name.part.gz")
        return try {
            val conn = URL(HgtTile.url(name)).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            when (conn.responseCode) {
                200 -> {
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    HgtTile.read(tmp) // check it decodes before keeping it
                    tmp.renameTo(file(name))
                }
                403, 404 -> true
                else -> false
            }
        } catch (_: Exception) {
            file(name).delete()
            false
        } finally {
            tmp.delete()
        }
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        loaded.clear()
        refreshList()
        _download.value = null
    }
}
