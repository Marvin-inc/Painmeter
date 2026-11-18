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
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
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
) {
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
                MapsforgeMap(maps, nav, track, Modifier.fillMaxSize())
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
        }

        Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataField(
                if (nav != null) "TO FINISH · km" else "DIST · km",
                Format.distanceKm(nav?.state?.remainingM ?: snap.distanceM), Modifier.weight(1f).fillMaxHeight(), valueSize = 40.sp,
            )
            DataField("SPEED", Format.speedKmh(snap.speedMps), Modifier.weight(1f).fillMaxHeight(), valueSize = 40.sp)
            DataField(
                "POWER ${snap.power.windowS} s", Format.watts(snap.power.powerShort), Modifier.weight(1f).fillMaxHeight(), valueSize = 40.sp,
                valueColor = if (snap.power.reliable) Pm.Power else Pm.Uncertain,
            )
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
private fun MapsforgeMap(maps: List<File>, nav: RideController.Nav?, track: RideController.Track, modifier: Modifier) {
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
            holder.setMaps(maps)
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
private class MapHolder(context: Context) {
    init {
        AndroidGraphicFactory.createInstance(context.applicationContext as android.app.Application)
    }

    val view = MapView(context).apply {
        isClickable = false
        mapScaleBar.isVisible = true
        setBuiltInZoomControls(false)
    }
    private val cache = AndroidUtil.createTileCache(context, "painmeter", view.model.displayModel.tileSize, 1f, view.model.frameBufferModel.overdrawFactor)
    private var mapsKey: List<File>? = null
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

    fun setMaps(maps: List<File>) {
        if (maps == mapsKey) return
        mapsKey = maps
        tiles?.let { view.layerManager.layers.remove(it); it.onDestroy() }
        tiles = null
        if (maps.isEmpty()) return
        val store = MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL)
        maps.forEach { f -> runCatching { store.addMapDataStore(MapFile(f), false, false) } }
        tiles = TileRendererLayer(cache, store, view.model.mapViewPosition, AndroidGraphicFactory.INSTANCE).apply {
            setXmlRenderTheme(MapsforgeThemes.BIKER)
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

    fun destroy() {
        view.destroyAll()
    }
}
