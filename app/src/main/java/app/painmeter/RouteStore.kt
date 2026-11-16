package app.painmeter

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.painmeter.core.route.GpxParser
import app.painmeter.core.route.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The route to follow (one at a time, SPEC §9.6) and the offline map files. Both are copied into
 * the app's storage when imported, so they stay available if the original file is moved.
 */
object RouteStore {
    private lateinit var app: Context
    private lateinit var routeFile: File
    private lateinit var mapsDir: File

    private val _route = MutableStateFlow<Route?>(null)
    val route: StateFlow<Route?> = _route

    private val _maps = MutableStateFlow<List<File>>(emptyList())
    val maps: StateFlow<List<File>> = _maps

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        routeFile = File(app.filesDir, "route.gpx")
        mapsDir = File(app.filesDir, "maps").apply { mkdirs() }
        _route.value = try { if (routeFile.exists()) GpxParser.parse(routeFile.readText(), "Route") else null } catch (_: Exception) { null }
        refreshMaps()
    }

    /** Imports a GPX file; returns an error message, or null when it worked. */
    fun importRoute(uri: Uri): String? = runCatching { importRouteOrThrow(uri) }.getOrElse { e ->
        if (e is IllegalArgumentException) e.message ?: "Not a GPX route" else "Import failed: ${e.message}"
    }

    private fun importRouteOrThrow(uri: Uri): String? {
        val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return "Cannot open the file"
        val route = GpxParser.parse(text, displayName(uri)?.removeSuffix(".gpx") ?: "Route")
        routeFile.writeText(text)
        _route.value = route
        return null
    }

    fun clearRoute() {
        routeFile.delete()
        _route.value = null
    }

    /** Imports a Mapsforge .map file (can take a few seconds for a big region). */
    fun importMap(uri: Uri): String? = runCatching { importMapOrThrow(uri) }.getOrElse { "Import failed: ${it.message}" }

    private fun importMapOrThrow(uri: Uri): String? {
        val name = displayName(uri)?.takeIf { it.endsWith(".map") } ?: return "Choose a .map file (Mapsforge format)"
        val target = File(mapsDir, name)
        val tmp = File(mapsDir, "$name.part")
        app.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: return "Cannot open the file"
        tmp.renameTo(target)
        refreshMaps()
        return null
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
