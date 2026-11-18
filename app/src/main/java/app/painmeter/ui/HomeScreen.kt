package app.painmeter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.painmeter.HrState
import app.painmeter.RideStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The aero settings line on the home screen. */
data class AeroInfo(val cda: Double, val crr: Double, val calibratedMs: Long?)

/**
 * The home screen: a check-list of what the ride needs (GPS, heart rate, elevation data, map,
 * aero settings), the route and the last ride, then Start.
 */
@Composable
fun HomeScreen(
    hasPermission: Boolean,
    gpsAccuracyM: Double?,
    hr: HrState,
    elevationHere: Boolean?,
    route: RouteInfo? = null,
    unfinished: File?,
    onStart: () -> Unit,
    onGrant: () -> Unit,
    onResume: (File) -> Unit,
    onSaveUnfinished: (File) -> Unit,
    onParameters: () -> Unit,
    onHistory: () -> Unit = {},
    mapCount: Int = 0,
    aero: AeroInfo? = null,
    lastRide: RideStore.Summary? = null,
    routeProfile: List<Double>? = null,
    onCalibrate: () -> Unit = {},
) {
    val gps = when {
        !hasPermission -> Check(Level.BAD, "Location permission needed")
        gpsAccuracyM == null -> Check(Level.WAIT, "Searching…")
        gpsAccuracyM > 15 -> Check(Level.WAIT, "Weak · ${gpsAccuracyM.toInt()} m")
        else -> Check(Level.OK, "Ready · ${gpsAccuracyM.toInt()} m")
    }
    val heart = when (hr) {
        HrState.NotPaired -> Check(Level.OFF, "No strap paired")
        HrState.NoPermission -> Check(Level.WAIT, "Bluetooth permission needed")
        HrState.BluetoothOff -> Check(Level.WAIT, "Bluetooth is off")
        is HrState.Connecting -> Check(Level.WAIT, "Connecting to ${hr.name}…")
        is HrState.Connected -> if (hr.bpm != null) Check(Level.OK, "${hr.name} · ${hr.bpm} bpm") else Check(Level.WAIT, "${hr.name} · no skin contact")
    }
    val elevation = when (elevationHere) {
        true -> Check(Level.OK, "Ready here")
        false -> Check(Level.WAIT, "Missing here · downloads during the ride")
        null -> Check(Level.OFF, "Waiting for GPS")
    }
    val map = if (mapCount > 0) Check(Level.OK, "$mapCount offline map${if (mapCount > 1) "s" else ""}") else Check(Level.OFF, "No offline map")
    val aeroCheck = aero?.let {
        val values = "CdA ${String.format(Locale.ROOT, "%.3f", it.cda)} · Crr ${String.format(Locale.ROOT, "%.4f", it.crr)}"
        if (it.calibratedMs != null) Check(Level.OK, values, "calibrated " + SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(it.calibratedMs)))
        else Check(Level.WAIT, values, "not calibrated yet · tap to calibrate")
    }
    val ready = gps.level == Level.OK && elevation.level != Level.BAD

    Column(Modifier.fillMaxSize().background(Pm.Background)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Logo(Modifier.size(44.dp))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("Painmeter", color = Pm.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Estimated power, no power meter", color = Pm.Muted, fontSize = 13.sp)
                }
            }

            // Ready check-list.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pm.Surface),
            ) {
                Row(
                    Modifier.fillMaxWidth().background(if (ready) Color(0x223BC77A) else Color(0x22F5A524)).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (ready) "READY TO RIDE" else "GETTING READY", color = if (ready) Pm.Green else Pm.Amber,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    )
                    Text("tap a line to fix it", color = Pm.Muted, fontSize = 12.sp)
                }
                CheckRow("GPS", gps, if (hasPermission) null else onGrant)
                CheckRow("Heart rate", heart, onParameters)
                CheckRow("Elevation", elevation, onParameters)
                CheckRow("Map", map, onParameters)
                aeroCheck?.let { CheckRow("Aero", it, if (it.level == Level.OK) onParameters else onCalibrate) }
            }

            unfinished?.let { dir ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pm.SurfaceAlt).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("A ride was not stopped (${dir.name})", color = Pm.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Resume it, or save it as it is.", color = Pm.Muted, fontSize = 14.sp)
                    Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigButton("RESUME", Modifier.weight(1f), Pm.Blue, Color.White, onClick = { onResume(dir) })
                        BigButton("SAVE", Modifier.weight(1f), Pm.Surface, Pm.Text, onClick = { onSaveUnfinished(dir) })
                    }
                }
            }

            // Route.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pm.Surface)
                    .clickable(onClickLabel = "Route settings", onClick = onParameters).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("ROUTE", color = Pm.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (route == null) {
                    Text("Free ride", color = Pm.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Import a GPX to get turns, climbs ahead and the gradient from the route.", color = Pm.Muted, fontSize = 13.sp)
                } else {
                    Text(route.name, color = Pm.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(route.summary, color = Pm.Muted, fontSize = 14.sp)
                    routeProfile?.takeIf { it.size > 2 }?.let { Sparkline(it, Modifier.fillMaxWidth().height(48.dp).padding(top = 4.dp)) }
                }
            }

            // Last ride.
            lastRide?.let { r ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pm.Surface)
                        .clickable(onClickLabel = "History", onClick = onHistory).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("LAST RIDE · " + SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(Date(r.startMs)).uppercase(), color = Pm.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${Format.distanceKm(r.distanceM)} km · ${Format.duration(r.rideTimeMs)} · ${Math.round(r.gainM)} m",
                            color = Pm.Text, fontSize = 16.sp,
                        )
                    }
                    r.avgPower?.let {
                        Text("${Math.round(it)}", color = Pm.Power, fontFamily = Pm.Numbers, fontWeight = FontWeight.Bold, fontSize = 34.sp)
                        Text(" W", color = Pm.Muted, fontSize = 14.sp)
                    }
                }
            }
        }

        // Start and shortcuts, always visible.
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val startLabel = if (!hasPermission) "ALLOW LOCATION" else "START"
            Box(
                Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(20.dp))
                    .background(
                        if (!hasPermission) Brush.horizontalGradient(listOf(Pm.Amber, Pm.Amber))
                        else Brush.horizontalGradient(listOf(Color(0xFF3BC77A), Color(0xFF2FB36B)))
                    )
                    .clickable(onClickLabel = startLabel) { if (hasPermission) onStart() else onGrant() },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(startLabel, color = Color(0xFF0B0D10), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    if (hasPermission) Text(
                        if (gps.level == Level.OK) "GPS ready" else "You can start now: recording begins with the first fix",
                        color = Color(0xCC0B0D10), fontSize = 12.sp,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("PARAMETERS", Modifier.weight(1.2f), Pm.Surface, Pm.Text, onClick = onParameters)
                BigButton("HISTORY", Modifier.weight(1f), Pm.Surface, Pm.Text, onClick = onHistory)
                BigButton("CALIBRATE", Modifier.weight(1.1f), Pm.Surface, Pm.Text, onClick = onCalibrate)
            }
            Text("During a ride: volume − = lap · swipe for pages · hold STOP to end", color = Pm.Muted, fontSize = 12.sp)
        }
    }
}

private enum class Level { OK, WAIT, BAD, OFF }

private data class Check(val level: Level, val text: String, val sub: String? = null)

@Composable
private fun CheckRow(label: String, c: Check, onClick: (() -> Unit)?) {
    val color = when (c.level) { Level.OK -> Pm.Green; Level.WAIT -> Pm.Amber; Level.BAD -> Pm.Rec; Level.OFF -> Pm.Disabled }
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Fix $label", onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, color = Pm.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp).width(92.dp))
        Column {
            Text(c.text, color = if (c.level == Level.OK) Pm.Text else color.takeIf { c.level != Level.OFF } ?: Pm.Muted, fontSize = 14.sp, maxLines = 1)
            c.sub?.let { Text(it, color = Pm.Muted, fontSize = 12.sp, maxLines = 1) }
        }
    }
}

/** The lightning bolt of the launcher icon. */
@Composable
private fun Logo(modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Pm.Surface).semantics { contentDescription = "Painmeter" }) {
        val w = size.width
        val p = Path().apply {
            moveTo(w * 0.56f, w * 0.14f); lineTo(w * 0.28f, w * 0.56f); lineTo(w * 0.46f, w * 0.56f)
            lineTo(w * 0.40f, w * 0.86f); lineTo(w * 0.72f, w * 0.42f); lineTo(w * 0.53f, w * 0.42f); close()
        }
        drawPath(p, Pm.Power)
    }
}

/** The route's elevation profile, as a filled line. */
@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "Route elevation profile" }) {
        val lo = values.min()
        val hi = maxOf(values.max(), lo + 20)
        fun x(i: Int) = i / (values.size - 1f) * size.width
        fun y(v: Double) = (size.height - (v - lo) / (hi - lo) * size.height * 0.9 - size.height * 0.05).toFloat()
        val line = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(area, Brush.verticalGradient(listOf(Pm.Power.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, Pm.Power, style = Stroke(width = 3f, cap = StrokeCap.Round))
        drawLine(Pm.Border, Offset(0f, size.height), Offset(size.width, size.height))
    }
}
