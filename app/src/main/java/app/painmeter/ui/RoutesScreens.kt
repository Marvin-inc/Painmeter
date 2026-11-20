package app.painmeter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.painmeter.SavedRoute
import app.painmeter.core.climb.RouteClimb
import app.painmeter.core.climb.RouteProfile
import java.io.File
import java.util.Locale

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.height(48.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Back", onClick = onBack).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text("←", color = Pm.Text, fontSize = 24.sp) }
        Text(title, color = Pm.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Pm.Divider))
}

private fun ago(ms: Long?): String {
    ms ?: return "never used"
    val d = (System.currentTimeMillis() - ms) / 86_400_000
    return when {
        d < 1 -> "used today"
        d < 2 -> "used yesterday"
        d < 30 -> "used $d days ago"
        else -> "used " + java.text.SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(java.util.Date(ms))
    }
}

/** The route library: most recently used first, free ride on top. */
@Composable
fun RoutesScreen(
    routes: List<SavedRoute>,
    activeId: String?,
    message: String?,
    onOpen: (SavedRoute) -> Unit,
    onFreeRide: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Pm.Background)) {
        TopBar("Routes", onBack)
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            message?.let { m -> item { Text(m, color = Pm.Amber, fontSize = 14.sp, modifier = Modifier.padding(4.dp)) } }
            item {
                RouteRow(
                    active = activeId == null, onClick = onFreeRide,
                    thumb = { Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Pm.SurfaceAlt), contentAlignment = Alignment.Center) { Text("∞", color = Pm.Muted, fontSize = 28.sp) } },
                    title = "Free ride", line1 = "No route: the map shows your track", line2 = null,
                )
            }
            items(routes, key = { it.id }) { r ->
                RouteRow(
                    active = r.id == activeId, onClick = { onOpen(r) },
                    thumb = {
                        RouteShape(r.route, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF161B22)).padding(6.dp), width = 3f)
                    },
                    title = r.name,
                    line1 = "${Format.distanceKm(r.route.lengthM)} km" + (r.route.gainM?.let { " · ${Math.round(it)} m" } ?: ""),
                    line2 = ago(r.lastUsedMs),
                )
            }
            if (routes.isEmpty()) item {
                Text(
                    "No route yet. Import GPX files from Komoot, Strava, RideWithGPS… or open a .gpx with Painmeter.",
                    color = Pm.Muted, fontSize = 14.sp, modifier = Modifier.padding(8.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp).height(60.dp)) {
            BigButton("IMPORT GPX FILES", Modifier.weight(1f), Pm.Blue, Color.White, onClick = onImport)
        }
    }
}

@Composable
private fun RouteRow(active: Boolean, onClick: () -> Unit, thumb: @Composable () -> Unit, title: String, line1: String, line2: String?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pm.Surface)
            .then(if (active) Modifier.border(2.dp, Pm.Green, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(onClickLabel = "Open $title", onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        thumb()
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Pm.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(line1, color = Pm.Muted, fontSize = 14.sp, maxLines = 1)
            line2?.let { Text(it, color = Pm.Muted, fontSize = 12.sp) }
        }
        if (active) Text("ACTIVE", color = Pm.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** One route: map, profile with its climbs, climbs list; use it, rename, delete. */
@Composable
fun RoutePreviewScreen(
    saved: SavedRoute,
    active: Boolean,
    analysis: Pair<RouteProfile, List<RouteClimb>>?,
    maps: List<File>,
    theme: app.painmeter.MapThemeFile? = null,
    sport: app.painmeter.core.run.Sport = app.painmeter.core.run.Sport.BIKE,
    onUse: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var newName by remember(saved.name) { mutableStateOf(saved.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    val r = saved.route
    val climbs = analysis?.second
    Column(Modifier.fillMaxSize().background(Pm.Background)) {
        TopBar(saved.name, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RouteMapPreview(r, maps, Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp)), theme, sport)
            Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataField("DISTANCE · km", Format.distanceKm(r.lengthM), Modifier.weight(1f), valueSize = 36.sp)
                DataField("GAIN · m", (analysis?.first?.let { gainOf(it) } ?: r.gainM)?.let { Math.round(it).toString() } ?: "--", Modifier.weight(1f), valueSize = 36.sp)
                DataField("CLIMBS", climbs?.size?.toString() ?: "…", Modifier.weight(1f), valueSize = 36.sp)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pm.Surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("PROFILE", color = Pm.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (analysis == null) Text("Reading the elevation…", color = Pm.Muted, fontSize = 14.sp)
                else if (!analysis.first.hasElevation) Text("No elevation in this file and no elevation tiles here.", color = Pm.Muted, fontSize = 14.sp)
                else ProfileWithClimbs(analysis.first, analysis.second, Modifier.fillMaxWidth().height(120.dp))
            }
            climbs?.takeIf { it.isNotEmpty() }?.let { list ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pm.Surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("CLIMBS", color = Pm.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    list.forEach { c ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${c.index + 1}", color = Pm.Muted, fontSize = 15.sp)
                            Text("at km ${Format.distanceKm(c.startM)}", color = Pm.Text, fontSize = 15.sp)
                            Text("${Format.distanceKm(c.lengthM)} km", color = Pm.Text, fontSize = 15.sp)
                            Text("${Format.grade(c.avgGradePercent)} %", color = Pm.gradeColor(c.avgGradePercent), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("${Math.round(c.gainM)} m", color = Pm.Text, fontSize = 15.sp)
                        }
                    }
                }
            }
            if (renaming) {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, singleLine = true, label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Pm.Text, unfocusedTextColor = Pm.Text, focusedBorderColor = Pm.Power,
                        unfocusedBorderColor = Pm.Border, focusedLabelColor = Pm.Power, unfocusedLabelColor = Pm.Muted, cursorColor = Pm.Power,
                    ),
                )
            }
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (renaming) {
                    BigButton("SAVE NAME", Modifier.weight(1f), Pm.SurfaceAlt, Pm.Text, onClick = { onRename(newName); renaming = false })
                } else {
                    BigButton("RENAME", Modifier.weight(1f), Pm.SurfaceAlt, Pm.Text, onClick = { renaming = true })
                }
                if (confirmDelete) BigButton("SURE? DELETE", Modifier.weight(1f), Pm.Rec, Color.White, onClick = onDelete)
                else BigButton("DELETE", Modifier.weight(1f), Pm.SurfaceAlt, Pm.Rec, onClick = { confirmDelete = true })
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp).height(64.dp)) {
            if (active) BigButton("ACTIVE ROUTE · BACK", Modifier.weight(1f), Pm.Surface, Pm.Green, onClick = onBack)
            else BigButton("USE THIS ROUTE", Modifier.weight(1f), Pm.Green, Color(0xFF0B0D10), onClick = onUse)
        }
    }
}

private fun gainOf(p: RouteProfile): Double {
    val g = app.painmeter.core.ElevationGain(4.0)
    p.elevations.forEach(g::add)
    return g.totalM
}

/** The route profile in grey, with each climb coloured by its average gradient. */
@Composable
private fun ProfileWithClimbs(profile: RouteProfile, climbs: List<RouteClimb>, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "Route profile with its climbs" }) {
        val e = profile.elevations
        if (e.size < 2) return@Canvas
        val lo = e.min()
        val hi = maxOf(e.max(), lo + 30)
        val total = profile.route.lengthM
        fun x(d: Double) = (d / total * size.width).toFloat()
        fun y(v: Double) = (size.height - (v - lo) / (hi - lo) * size.height * 0.92).toFloat()
        val n = e.size
        val step = RouteProfile.STEP_M
        val area = Path().apply {
            moveTo(0f, size.height)
            for (i in 0 until n) lineTo(x(i * step), y(e[i]))
            lineTo(x((n - 1) * step), size.height); close()
        }
        drawPath(area, Pm.SurfaceAlt)
        for (c in climbs) {
            val a = (c.startM / step).toInt().coerceIn(0, n - 1)
            val b = (c.endM / step).toInt().coerceIn(0, n - 1)
            val p = Path().apply {
                moveTo(x(a * step), size.height)
                for (i in a..b) lineTo(x(i * step), y(e[i]))
                lineTo(x(b * step), size.height); close()
            }
            drawPath(p, Pm.gradeColor(c.avgGradePercent).copy(alpha = 0.85f))
        }
        val line = Path().apply { for (i in 0 until n) if (i == 0) moveTo(x(0.0), y(e[0])) else lineTo(x(i * step), y(e[i])) }
        drawPath(line, Pm.Text, style = Stroke(width = 2.5f))
        drawLine(Pm.Border, Offset(0f, size.height), Offset(size.width, size.height))
    }
}
