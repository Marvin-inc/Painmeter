package app.painmeter

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.painmeter.core.route.GpxParser
import app.painmeter.core.route.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** An imported map theme (OpenAndroMaps Elevate): the zip, the theme file inside it, its name. */
data class MapThemeFile(val zip: File, val xml: String, val name: String)

/** A route in the library. */
data class SavedRoute(
    val id: String,
    val name: String,
    val route: Route,
    val importedMs: Long,
    val lastUsedMs: Long?,
)

/**
 * The route library (several GPX routes, one active at a time) and the offline map files. Both
 * are copied into the app's storage when imported, so they stay available if the original file is
 * moved. The library lists the most recently used routes first.
 */
object RouteStore {
    private lateinit var app: Context
    private lateinit var routesDir: File
    private lateinit var mapsDir: File
    private lateinit var themesDir: File
    private val prefs by lazy { app.getSharedPreferences("routes", Context.MODE_PRIVATE) }

    private val _library = MutableStateFlow<List<SavedRoute>>(emptyList())
    /** All routes, most recently used first (then most recently imported). */
    val library: StateFlow<List<SavedRoute>> = _library

    private val _route = MutableStateFlow<Route?>(null)
    /** The active route, or null for a free ride. */
    val route: StateFlow<Route?> = _route

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId

    private val _maps = MutableStateFlow<List<File>>(emptyList())
    val maps: StateFlow<List<File>> = _maps

    private val _theme = MutableStateFlow<MapThemeFile?>(null)
    /** The imported map theme, null when none (the built-in Biker theme is used). */
    val theme: StateFlow<MapThemeFile?> = _theme

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        routesDir = File(app.filesDir, "routes").apply { mkdirs() }
        mapsDir = File(app.filesDir, "maps").apply { mkdirs() }
        themesDir = File(app.filesDir, "themes").apply { mkdirs() }
        // Before the library there was a single route.gpx: keep it, as the active route.
        val old = File(app.filesDir, "route.gpx")
        if (old.exists()) {
            runCatching { add(old.readText(), "Route", activate = true) }
            old.delete()
        }
        _activeId.value = prefs.getString("active", null)
        reload()
        refreshMaps()
        refreshTheme()
    }

    private fun gpx(id: String) = File(routesDir, "$id.gpx")
    private fun meta(id: String) = File(routesDir, "$id.meta")

    private fun reload() {
        val list = routesDir.listFiles { f -> f.name.endsWith(".gpx") }?.mapNotNull { f ->
            val id = f.name.removeSuffix(".gpx")
            val m = readMeta(id)
            runCatching {
                SavedRoute(id, m["name"] ?: "Route", GpxParser.parse(f.readText(), m["name"] ?: "Route"),
                    m["imported"]?.toLongOrNull() ?: f.lastModified(), m["used"]?.toLongOrNull())
            }.getOrNull()
        } ?: emptyList()
        _library.value = list.sortedWith(compareByDescending<SavedRoute> { it.lastUsedMs ?: 0 }.thenByDescending { it.importedMs })
        val active = list.firstOrNull { it.id == _activeId.value }
        if (active == null && _activeId.value != null) setActive(null)
        // Keep the same Route object while it does not change (screens compare it by identity).
        if (_route.value?.let { r -> active?.route?.points == r.points } != true) _route.value = active?.route
    }

    private fun readMeta(id: String): Map<String, String> = runCatching {
        meta(id).readLines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeMeta(id: String, m: Map<String, String>) =
        meta(id).writeText(m.entries.joinToString("\n") { "${it.key}=${it.value.replace("\n", " ")}" })

    private fun setActive(id: String?) {
        _activeId.value = id
        prefs.edit().putString("active", id).apply()
    }

    /** Adds a GPX to the library; returns its id. Throws IllegalArgumentException if it is not a route. */
    private fun add(text: String, fallbackName: String, activate: Boolean): String {
        val parsed = GpxParser.parse(text, fallbackName)
        val id = System.currentTimeMillis().toString(36) + (0..999).random().toString(36)
        gpx(id).writeText(text)
        val now = System.currentTimeMillis()
        writeMeta(id, buildMap {
            put("name", parsed.name.ifBlank { fallbackName })
            put("imported", now.toString())
            if (activate) put("used", now.toString())
        })
        if (activate) setActive(id)
        return id
    }

    /** Imports a GPX opened or shared from another app and makes it the active route. */
    fun importRoute(uri: Uri): String? = importRoutes(listOf(uri), activate = true)

    /** Imports GPX files into the library; returns an error message, or null when all worked. */
    fun importRoutes(uris: List<Uri>, activate: Boolean = false): String? {
        val errors = mutableListOf<String>()
        for (uri in uris) {
            val name = displayName(uri)?.removeSuffix(".gpx") ?: "Route"
            runCatching {
                val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error("cannot open the file")
                add(text, name, activate && uris.size == 1)
            }.onFailure { e -> errors += "$name: ${if (e is IllegalArgumentException) e.message else "import failed (${e.message})"}" }
        }
        reload()
        return errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Makes a route the active one (null: free ride). */
    fun select(id: String?) {
        if (id != null) writeMeta(id, readMeta(id) + ("used" to System.currentTimeMillis().toString()))
        setActive(id)
        reload()
    }

    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        writeMeta(id, readMeta(id) + ("name" to name.trim()))
        reload()
    }

    fun delete(id: String) {
        gpx(id).delete()
        meta(id).delete()
        if (_activeId.value == id) setActive(null)
        reload()
    }

    /** Back to a free ride (the route stays in the library). */
    fun clearRoute() = select(null)

    /**
     * Imports a map or a map theme (can take a minute for a big region):
     * - a Mapsforge `.map` file (download.mapsforge.org or OpenAndroMaps);
     * - a `.zip` holding a `.map` (OpenAndroMaps downloads): the map is taken out of it;
     * - a `.zip` holding a render theme (`.xml` + its symbols, e.g. OpenAndroMaps Elevate): kept as
     *   the map theme, replacing the previous one.
     * Returns an error message, or null when it worked.
     */
    fun importMap(uri: Uri): String? = runCatching { importMapOrThrow(uri) }.getOrElse { "Import failed: ${it.message}" }

    /** What [importMap] imported last: "map" or "theme". */
    var lastImportKind: String = "map"
        private set

    private fun importMapOrThrow(uri: Uri): String? {
        val name = displayName(uri) ?: return "Cannot read the file name"
        if (name.endsWith(".map", ignoreCase = true)) {
            copyTo(uri, File(mapsDir, name)) ?: return "Cannot open the file"
            lastImportKind = "map"
            refreshMaps()
            return null
        }
        if (!name.endsWith(".zip", ignoreCase = true)) return "Choose a .map file, or a .zip from OpenAndroMaps (map or Elevate theme)"

        // First pass: a map inside? Take it out. Otherwise look for a theme (.xml at any level).
        val themes = mutableListOf<String>()
        val input = app.contentResolver.openInputStream(uri) ?: return "Cannot open the file"
        java.util.zip.ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val entry = e.name
                if (!e.isDirectory && entry.endsWith(".map", ignoreCase = true)) {
                    val target = File(mapsDir, entry.substringAfterLast('/'))
                    val tmp = File(mapsDir, "${target.name}.part")
                    tmp.outputStream().use { zip.copyTo(it) }
                    tmp.renameTo(target)
                    lastImportKind = "map"
                    refreshMaps()
                    return null
                }
                if (!e.isDirectory && entry.endsWith(".xml", ignoreCase = true)) themes += entry
            }
        }
        val xml = pickThemeXml(themes) ?: return "No .map file and no map theme found in this zip"
        val target = File(themesDir, "theme.zip")
        copyTo(uri, target) ?: return "Cannot open the file"
        File(themesDir, "theme.txt").writeText("$xml\n${name.removeSuffix(".zip").removeSuffix(".ZIP")}")
        lastImportKind = "theme"
        refreshTheme()
        return null
    }

    /**
     * The main theme file of a theme zip: the shallowest .xml, the shortest name first, so
     * "Elevate.xml" wins over "Elevate_hike.xml" and over files in sub-folders.
     */
    internal fun pickThemeXml(entries: List<String>): String? =
        entries.filter { !it.substringAfterLast('/').startsWith(".") }
            .minWithOrNull(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it.length })

    private fun copyTo(uri: Uri, target: File): Unit? {
        val tmp = File(target.parentFile, "${target.name}.part")
        app.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return null
        tmp.renameTo(target)
        return Unit
    }

    /** Where the Elevate theme is downloaded from (OpenAndroMaps' manual-install zip). */
    const val ELEVATE_URL = "https://www.openandromaps.org/wp-content/users/tobias/Elevate.zip"

    /**
     * Downloads the Elevate theme and installs it, without the browser (the "Install in Android"
     * links of the OpenAndroMaps page only work for other map apps). Blocking: call off the main
     * thread. Returns an error message, or null when it worked.
     */
    fun downloadTheme(url: String = ELEVATE_URL): String? = runCatching {
        val tmp = File(themesDir, "download.part")
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) return "Download failed (HTTP ${conn.responseCode})"
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        val xml = java.util.zip.ZipFile(tmp).use { z ->
            pickThemeXml(z.entries().toList().filter { !it.isDirectory && it.name.endsWith(".xml", ignoreCase = true) }.map { it.name })
        }
        if (xml == null) { tmp.delete(); return "The downloaded file is not a map theme" }
        tmp.renameTo(File(themesDir, "theme.zip"))
        File(themesDir, "theme.txt").writeText("$xml\nElevate")
        lastImportKind = "theme"
        refreshTheme()
        null
    }.getOrElse { "Download failed: ${it.message}" }

    fun deleteTheme() {
        File(themesDir, "theme.zip").delete()
        File(themesDir, "theme.txt").delete()
        refreshTheme()
    }

    private fun refreshTheme() {
        val zip = File(themesDir, "theme.zip")
        val info = runCatching { File(themesDir, "theme.txt").readLines() }.getOrNull()
        _theme.value = if (zip.exists() && info != null && info.isNotEmpty()) MapThemeFile(zip, info[0], info.getOrNull(1) ?: "Theme") else null
    }

    fun deleteMap(file: File) {
        file.delete()
        refreshMaps()
    }

    private fun refreshMaps() {
        mapsDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        _maps.value = mapsDir.listFiles()?.filter { it.name.endsWith(".map") }?.sortedBy { it.name } ?: emptyList()
    }

    private fun displayName(uri: Uri): String? =
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
}
