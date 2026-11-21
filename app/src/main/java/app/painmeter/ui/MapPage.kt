package app.painmeter.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.painmeter.RideController
import app.painmeter.core.RideSnapshot
import app.painmeter.core.route.TurnKind
import org.mapsforge.core.graphics.Cap
import org.mapsforge.core.graphics.Join
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Rotation
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback
import org.mapsforge.map.rendertheme.XmlRenderThemeStyleMenu
import org.mapsforge.map.rendertheme.ZipRenderTheme
import org.mapsforge.map.rendertheme.ZipXmlThemeResourceProvider
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import app.painmeter.MapThemeFile
import app.painmeter.core.run.Sport
import java.io.File
import java.util.Locale

/** The Map page (SPEC §9.5): heading-up offline map, route, ridden track, next turn, off route. */
@Composable
fun MapPage(
    snap: RideSnapshot,
    nav: RideController.Nav?,
    track: RideController.Track,
    maps: List<File>,
    modifier: Modifier = Modifier,
    /** Imported theme to draw with (null: built-in Biker theme). */
    theme: MapThemeFile? = null,
    /** Opens the full-screen map you can move (only while paused); null hides the button. */
    onFullMap: (() -> Unit)? = null,
) {
    val run = snap.sport == Sport.RUN
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val st = nav?.state
        when {
            st?.offRoute == true -> Banner(Color(0xFFD93B3F)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("OFF ROUTE · ${Math.round(st.offsetM)} m", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Get back to the blue line", color = Color.White, fontSize = 13.sp)
                }
            }
            st?.nextTurn != null && (st.distanceToTurnM ?: Double.MAX_VALUE) <= 300 -> Banner(Pm.Surface) {
                val turn = st.nextTurn!!
                val dist = st.distanceToTurnM!!
                TurnArrow(turn.kind, Modifier.size(44.dp))
                Text(
                    "${Math.round(dist / 10) * 10} m", color = Pm.Text, fontFamily = Pm.Numbers,
                    fontWeight = FontWeight.Bold, fontSize = 44.sp, modifier = Modifier.padding(start = 14.dp),
                )
                Text(turnLabel(turn.kind), color = Pm.Muted, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
            nav != null -> Banner(Pm.Surface) {
                Text(nav.route.name, color = Pm.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                Text(
                    st?.nextTurn?.let { "next turn in ${Format.distanceKm(st.distanceToTurnM ?: 0.0)} km" } ?: "no more turns",
                    color = Pm.Muted, fontSize = 14.sp,
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF161B22))) {
            if (LocalInspectionMode.current || maps.isEmpty() && track.position == null) {
                Text(
                    if (maps.isEmpty()) "No offline map yet.\nImport a .map file in Parameters → Maps." else "Waiting for GPS…",
                    color = Pm.Muted, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                MapsforgeMap(maps, nav, track, Modifier.fillMaxSize(), theme, snap.sport)
            }
            if (maps.isEmpty() && track.position != null) {
                Text(
                    "No offline map: import one in Parameters", color = Pm.Text, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC14181E)).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            // The rider, always in the middle and pointing up (the map turns underneath).
            RiderArrow(Modifier.align(Alignment.Center).size(40.dp))
            if (onFullMap != null) {
                BigButton(
                    "⤢ FULL MAP", Modifier.align(Alignment.BottomStart).padding(10.dp).width(150.dp).height(52.dp),
                    Pm.Blue, Color.White, onClick = onFullMap,
                )
            }
        }

        Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataField(
                if (nav != null) "TO FINISH · km" else "DIST · km",
                Format.distanceKm(nav?.state?.remainingM ?: snap.distanceM), Modifier.weight(1f).fillMaxHeight(), valueSize = 40.sp,
            )
            if (run) {
                DataField("PACE · /km", Format.pace(snap.paceSpeedMps), Modifier.weight(1f).fillMaxHeight(), valueSize = 40.sp, valueColor = Pm.Power)
                DataField("TIME", Format.duration(snap.rideTimeMs), Modifier.weight(1f).fillMaxHeight(), valueSize = 40.sp)
            } else {
                DataField("SPEED", Format.speedKmh(snap.speedMps), Modifier.weight(1f).fillMaxHeight(), valueSize = 40.sp)
                DataField(
                    "POWER ${snap.power.windowS} s", Format.watts(snap.power.powerShort), Modifier.weight(1f).fillMaxHeight(), valueSize = 40.sp,
                    valueColor = if (snap.power.reliable) Pm.Power else Pm.Uncertain,
                )
            }
        }
    }
}

@Composable
private fun Banner(color: Color, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp)).background(color).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private fun turnLabel(k: TurnKind) = when (k) {
    TurnKind.SLIGHT_LEFT -> "slight left"
    TurnKind.LEFT -> "left"
    TurnKind.SHARP_LEFT -> "sharp left"
    TurnKind.SLIGHT_RIGHT -> "slight right"
    TurnKind.RIGHT -> "right"
    TurnKind.SHARP_RIGHT -> "sharp right"
    TurnKind.U_TURN -> "U-turn"
}

/** A turn arrow drawn as a stroke: straight up, then bent by the turn's angle. */
@Composable
fun TurnArrow(kind: TurnKind, modifier: Modifier) {
    val angle = when (kind) {
        TurnKind.SLIGHT_LEFT -> -40f; TurnKind.LEFT -> -90f; TurnKind.SHARP_LEFT -> -135f
        TurnKind.SLIGHT_RIGHT -> 40f; TurnKind.RIGHT -> 90f; TurnKind.SHARP_RIGHT -> 135f
        TurnKind.U_TURN -> 180f
    }
    androidx.compose.foundation.Canvas(modifier.semantics { contentDescription = "Turn ${turnLabel(kind)}" }) {
        val w = size.width
        val base = Offset(w * 0.5f, w * 0.95f)
        val corner = Offset(w * 0.5f, w * 0.5f)
        val rad = Math.toRadians(angle.toDouble())
        val len = w * 0.38f
        val tip = Offset(corner.x + (len * kotlin.math.sin(rad)).toFloat(), corner.y - (len * kotlin.math.cos(rad)).toFloat())
        val stroke = Stroke(width = w * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        drawPath(Path().apply { moveTo(base.x, base.y); lineTo(corner.x, corner.y); lineTo(tip.x, tip.y) }, Pm.Text, style = stroke)
        // Arrow head.
        val back = Math.toRadians(angle.toDouble() + 180)
        val h = w * 0.18f
        fun wing(off: Double) = Offset(tip.x + (h * kotlin.math.sin(back + off)).toFloat(), tip.y - (h * kotlin.math.cos(back + off)).toFloat())
        drawPath(Path().apply { moveTo(wing(0.6).x, wing(0.6).y); lineTo(tip.x, tip.y); lineTo(wing(-0.6).x, wing(-0.6).y) }, Pm.Text, style = stroke)
    }
}

@Composable
private fun RiderArrow(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier.semantics { contentDescription = "Your position, heading up" }) {
        val w = size.width
        drawCircle(Color(0x990B0D10), radius = w / 2)
        val p = Path().apply {
            moveTo(w * 0.5f, w * 0.12f); lineTo(w * 0.8f, w * 0.85f); lineTo(w * 0.5f, w * 0.68f); lineTo(w * 0.2f, w * 0.85f); close()
        }
        drawPath(p, Pm.Power)
        drawPath(p, Color(0xFF0B0D10), style = Stroke(width = 3f))
    }
}

/** Mapsforge view: map files, route (blue), ridden track (cyan), centred on the rider, heading up. */
@Composable
private fun MapsforgeMap(
    maps: List<File>, nav: RideController.Nav?, track: RideController.Track, modifier: Modifier,
    theme: MapThemeFile? = null, sport: Sport = Sport.BIKE,
) {
    val context = LocalContext.current
    var zoom by remember { mutableIntStateOf(MapPrefs.zoom(context)) }
    val holder = remember { MapHolder(context) }
    DisposableEffect(Unit) { onDispose { holder.destroy() } }

    // Glide to each new heading over half a second, always the short way round (350° → 10° turns
    // 20°, not 340°). The animated value is unbounded; only its angle matters.
    val turn = remember { androidx.compose.animation.core.Animatable((track.bearingDeg ?: 0.0).toFloat()) }
    androidx.compose.runtime.LaunchedEffect(track.bearingDeg) {
        val target = (track.bearingDeg ?: return@LaunchedEffect).toFloat()
        var delta = (target - turn.value) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        turn.animateTo(turn.value + delta, androidx.compose.animation.core.tween(500))
    }
    val heading = turn.value

    Box(modifier) {
        AndroidView(factory = { holder.view }, modifier = Modifier.fillMaxSize(), update = { v ->
            // Every change makes Mapsforge redraw: only pass what really changed, or a map turned by
            // the compass many times a second (when stopped) never gets time to draw its tiles.
            holder.setMaps(maps, theme, sport)
            holder.setRoute(nav?.route)
            holder.setTrack(track.points)
            holder.setView(track.position, zoom, heading)
        })
        // Catches touches so swipes go to the pages, not to the map (it follows the rider anyway).
        Box(Modifier.fillMaxSize().pointerInput(Unit) { })
        Column(Modifier.align(Alignment.TopEnd).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ZoomButton("+", "Zoom in") { zoom = (zoom + 1).coerceAtMost(19); MapPrefs.setZoom(context, zoom) }
            ZoomButton("−", "Zoom out") { zoom = (zoom - 1).coerceAtLeast(10); MapPrefs.setZoom(context, zoom) }
        }
        Text(
            "N ${String.format(Locale.ROOT, "%.0f", ((heading % 360) + 360) % 360)}° · heading up",
            color = Pm.Muted, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(8.dp))
                .background(Color(0xCC14181E)).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ZoomButton(label: String, description: String, onClick: () -> Unit) {
    BigButton(label, Modifier.width(52.dp).height(52.dp).semantics { contentDescription = description }, Pm.Surface, Pm.Text, onClick = onClick)
}

/** Zoom kept between rides (SPEC §9.5). */
private object MapPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("map", Context.MODE_PRIVATE)
    fun zoom(c: Context) = prefs(c).getInt("zoom", 16)
    fun setZoom(c: Context, z: Int) = prefs(c).edit().putInt("zoom", z).apply()
}

/** Owns the MapView and its layers, rebuilt only when the inputs really change. */
private class MapHolder(context: Context, interactive: Boolean = false) {
    init {
        AndroidGraphicFactory.createInstance(context.applicationContext as android.app.Application)
    }

    val view = MapView(context).apply {
        // Interactive (full-screen map): Mapsforge handles drag, pinch zoom and double tap itself.
        isClickable = interactive
        mapScaleBar.isVisible = true
        setBuiltInZoomControls(false)
    }
    private val cache = AndroidUtil.createTileCache(context, "painmeter", view.model.displayModel.tileSize, 1f, view.model.frameBufferModel.overdrawFactor)
    private var mapsKey: Triple<List<File>, MapThemeFile?, Sport>? = null
    private var tiles: TileRendererLayer? = null
    private var routeKey: app.painmeter.core.route.Route? = null
    private var center: Pair<Double, Double>? = null
    private var zoomShown = -1
    private var rotationShown = Float.NaN

    fun setView(position: Pair<Double, Double>?, zoom: Int, headingDeg: Float) {
        val pos = view.model.mapViewPosition
        if (position != null && position != center) {
            center = position
            pos.center = LatLong(position.first, position.second)
        }
        if (zoom != zoomShown) {
            zoomShown = zoom
            pos.setZoomLevel(zoom.toByte(), false)
        }
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val angle = -headingDeg
        if (w > 0 && h > 0 && (rotationShown.isNaN() || kotlin.math.abs(angle - rotationShown) >= 0.8f)) {
            rotationShown = angle
            view.rotate(Rotation(angle, w / 2, h / 2))
        }
    }
    private var routeLine: Polyline? = null
    private var trackLine: Polyline? = null
    private var trackSize = -1

    fun setMaps(maps: List<File>, theme: MapThemeFile? = null, sport: Sport = Sport.BIKE) {
        val key = Triple(maps, theme, sport)
        if (key == mapsKey) return
        mapsKey = key
        tiles?.let { view.layerManager.layers.remove(it); it.onDestroy() }
        tiles = null
        if (maps.isEmpty()) return
        val store = MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL)
        maps.forEach { f -> runCatching { store.addMapDataStore(MapFile(f), false, false) } }
        tiles = TileRendererLayer(cache, store, view.model.mapViewPosition, AndroidGraphicFactory.INSTANCE).apply {
            setXmlRenderTheme(renderTheme(theme, sport))
        }
        view.layerManager.layers.add(0, tiles)
    }

    fun setRoute(route: app.painmeter.core.route.Route?) {
        if (route === routeKey) return
        routeKey = route
        routeLine?.let { view.layerManager.layers.remove(it) }
        routeLine = null
        if (route == null) return
        routeLine = line(0xFF2F6FED.toInt(), 9f).apply { addPoints(route.points.map { LatLong(it.lat, it.lon) }) }
        view.layerManager.layers.add(routeLine)
    }

    fun setTrack(points: List<Pair<Double, Double>>) {
        if (points.size == trackSize) return
        trackSize = points.size
        val t = trackLine ?: line(0xFF7FD8F0.toInt(), 8f).also { trackLine = it; view.layerManager.layers.add(it) }
        t.setPoints(points.map { LatLong(it.first, it.second) })
        t.requestRedraw()
    }

    private fun line(color: Int, width: Float) = Polyline(
        AndroidGraphicFactory.INSTANCE.createPaint().apply {
            this.color = color
            strokeWidth = width * view.model.displayModel.scaleFactor
            setStyle(Style.STROKE)
            setStrokeCap(Cap.ROUND)
            setStrokeJoin(Join.ROUND)
        },
        AndroidGraphicFactory.INSTANCE,
    )

    private var marker: org.mapsforge.map.layer.overlay.FixedPixelCircle? = null

    /** A dot where the rider is (the full-screen map is north up and moves freely). */
    fun setMarker(position: Pair<Double, Double>?) {
        if (position == null) return
        val ll = LatLong(position.first, position.second)
        val m = marker ?: org.mapsforge.map.layer.overlay.FixedPixelCircle(
            ll, 10f,
            AndroidGraphicFactory.INSTANCE.createPaint().apply { color = 0xFFFFC233.toInt(); setStyle(Style.FILL) },
            AndroidGraphicFactory.INSTANCE.createPaint().apply {
                color = 0xFF0B0D10.toInt(); strokeWidth = 3 * view.model.displayModel.scaleFactor; setStyle(Style.STROKE)
            },
        ).also { marker = it; view.layerManager.layers.add(it) }
        if (m.position != ll) { m.setLatLong(ll); m.requestRedraw() }
    }

    fun center(position: Pair<Double, Double>, zoom: Int? = null) {
        val pos = view.model.mapViewPosition
        pos.center = LatLong(position.first, position.second)
        zoom?.let { pos.setZoomLevel(it.toByte(), false) }
    }

    fun zoomBy(d: Int) {
        val pos = view.model.mapViewPosition
        if (d > 0) pos.zoomIn() else pos.zoomOut()
    }

    fun destroy() {
        view.destroyAll()
    }
}

/**
 * The full-screen map (SPEC §9.5), offered while paused: north up, moved with a finger and zoomed
 * with two (or + / −), like a phone map app. Route, ridden track and a dot for the rider; ◎ comes
 * back to the rider. Closed with ✕ or the back key.
 */
@Composable
fun FullScreenMap(
    maps: List<File>, nav: RideController.Nav?, track: RideController.Track, onClose: () -> Unit,
    theme: MapThemeFile? = null, sport: Sport = Sport.BIKE,
) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    val context = LocalContext.current
    val holder = remember { MapHolder(context, interactive = true) }
    DisposableEffect(Unit) { onDispose { holder.destroy() } }
    var centred by remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xFF161B22))) {
        if (LocalInspectionMode.current || maps.isEmpty()) {
            Text(
                if (maps.isEmpty()) "No offline map yet.\nImport one in Parameters → Maps." else "Map",
                color = Pm.Muted, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        } else {
            AndroidView(factory = { holder.view }, modifier = Modifier.fillMaxSize(), update = {
                holder.setMaps(maps, theme, sport)
                holder.setRoute(nav?.route)
                holder.setTrack(track.points)
                holder.setMarker(track.position)
                // Centre on the rider once; after that the map stays where the finger leaves it.
                val start = track.position ?: nav?.route?.points?.firstOrNull()?.let { it.lat to it.lon }
                if (!centred && start != null) { holder.center(start, MapPrefs.zoom(context)); centred = true }
            })
        }
        Column(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("✕", Modifier.width(60.dp).height(60.dp).semantics { contentDescription = "Close the full map" }, Pm.Surface, Pm.Text, onClick = onClose)
            BigButton("◎", Modifier.width(60.dp).height(60.dp).semantics { contentDescription = "Back to my position" }, Pm.Surface, Pm.Power, onClick = {
                track.position?.let { holder.center(it) }
            })
            BigButton("+", Modifier.width(60.dp).height(60.dp).semantics { contentDescription = "Zoom in" }, Pm.Surface, Pm.Text, onClick = { holder.zoomBy(1) })
            BigButton("−", Modifier.width(60.dp).height(60.dp).semantics { contentDescription = "Zoom out" }, Pm.Surface, Pm.Text, onClick = { holder.zoomBy(-1) })
        }
        Text(
            "North up · drag to move, pinch to zoom",
            color = Pm.Muted, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).clip(RoundedCornerShape(8.dp))
                .background(Color(0xCC14181E)).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * The render theme: the imported one (OpenAndroMaps Elevate) when there is one, else the built-in
 * Biker theme. A theme that cannot be read falls back to Biker rather than leaving the map blank.
 */
private fun renderTheme(theme: MapThemeFile?, sport: Sport): XmlRenderTheme {
    if (theme == null) return MapsforgeThemes.BIKER
    return runCatching {
        val provider = java.util.zip.ZipInputStream(theme.zip.inputStream().buffered()).use { ZipXmlThemeResourceProvider(it) }
        ZipRenderTheme(theme.xml, provider, StyleChooser(sport))
    }.getOrElse { MapsforgeThemes.BIKER }
}

/**
 * Picks the style of a theme with several (Elevate has hiking, cycling, MTB, city…): for a run
 * the first whose id or title speaks of hiking or trails, for a ride of cycling; otherwise the
 * theme's default. Overlays turned on by default in that style are kept.
 */
internal class StyleChooser(private val sport: Sport) : XmlRenderThemeMenuCallback {
    override fun getCategories(menu: XmlRenderThemeStyleMenu): Set<String> {
        val visible = menu.layers.values.filter { it.isVisible }
        val words = if (sport == Sport.RUN) listOf("hik", "wander", "trail", "walk", "foot", "rando")
        else listOf("cycl", "bike", "road", "velo", "rad")
        val layer = words.firstNotNullOfOrNull { w ->
            visible.firstOrNull { l -> l.id.lowercase().contains(w) || l.titles.values.any { it.lowercase().contains(w) } }
        } ?: menu.getLayer(menu.defaultValue) ?: return emptySet()
        val cats = layer.categories.toMutableSet()
        layer.overlays.filter { it.isEnabled }.forEach { cats += it.categories }
        return cats
    }
}

/**
 * A still map of a whole route (north up), for the route preview. Without offline map files
 * it shows the route line on a plain background.
 */
@Composable
fun RouteMapPreview(route: app.painmeter.core.route.Route, maps: List<File>, modifier: Modifier, theme: MapThemeFile? = null, sport: Sport = Sport.BIKE) {
    if (LocalInspectionMode.current || maps.isEmpty()) {
        RouteShape(route, modifier.background(Color(0xFF161B22)).padding(16.dp), Pm.Blue, 6f)
        return
    }
    val context = LocalContext.current
    val holder = remember { MapHolder(context) }
    DisposableEffect(Unit) { onDispose { holder.destroy() } }
    AndroidView(factory = { holder.view }, modifier = modifier, update = { v ->
        holder.setMaps(maps, theme, sport)
        holder.setRoute(route)
        if (v.width > 0 && v.height > 0) {
            val lats = route.points.map { it.lat }
            val lons = route.points.map { it.lon }
            val box = org.mapsforge.core.model.BoundingBox(lats.min(), lons.min(), lats.max(), lons.max())
            val zoom = org.mapsforge.core.util.LatLongUtils.zoomForBounds(
                org.mapsforge.core.model.Dimension((v.width * 0.85).toInt(), (v.height * 0.85).toInt()), box, v.model.displayModel.tileSize,
            ).toInt()
            holder.setView(box.centerPoint.latitude to box.centerPoint.longitude, zoom, 0f)
        }
    })
}

/** The route's shape drawn alone (no map): the list thumbnails, and the preview without a map. */
@Composable
fun RouteShape(route: app.painmeter.core.route.Route, modifier: Modifier, color: Color = Pm.Power, width: Float = 4f) {
    val pts = remember(route) {
        val step = maxOf(1, route.points.size / 300)
        route.points.filterIndexed { i, _ -> i % step == 0 } + route.points.last()
    }
    androidx.compose.foundation.Canvas(modifier.semantics { contentDescription = "Route shape" }) {
        val lat0 = pts.minOf { it.lat }; val lat1 = pts.maxOf { it.lat }
        val k = kotlin.math.cos(Math.toRadians((lat0 + lat1) / 2))
        val lon0 = pts.minOf { it.lon }; val lon1 = pts.maxOf { it.lon }
        val spanX = ((lon1 - lon0) * k).coerceAtLeast(1e-6)
        val spanY = (lat1 - lat0).coerceAtLeast(1e-6)
        val scale = minOf(size.width / spanX, size.height / spanY).toFloat()
        val ox = (size.width - spanX.toFloat() * scale) / 2
        val oy = (size.height - spanY.toFloat() * scale) / 2
        fun xy(p: app.painmeter.core.route.RoutePoint) =
            Offset(ox + ((p.lon - lon0) * k).toFloat() * scale, oy + ((lat1 - p.lat)).toFloat() * scale)
        val path = Path().apply { pts.forEachIndexed { i, p -> val o = xy(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
        drawPath(path, color, style = Stroke(width = width, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(Pm.Green, radius = width * 1.6f, center = xy(pts.first()))
        drawCircle(Pm.Rec, radius = width * 1.6f, center = xy(pts.last()))
    }
}
