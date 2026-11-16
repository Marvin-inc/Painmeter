package app.painmeter.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.painmeter.RideController
import app.painmeter.core.RideSnapshot
import java.util.Locale

private fun f3(x: Double) = String.format(Locale.ROOT, "%.3f", x)
private fun f4(x: Double) = String.format(Locale.ROOT, "%.4f", x)

@Composable
private fun Header(title: String, step: String?, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            BigButton("←", Modifier.padding(4.dp).height(44.dp).fillMaxWidth(0.14f), Pm.Background, Pm.Text, onClick = onBack)
        }
        Text(title, color = Pm.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp))
        step?.let { Text(it, color = Pm.Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp)) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Pm.Divider))
}

/** Step 1: the protocol, then start the test or pick a past ride (SPEC §9.7). */
@Composable
fun CalibrationIntroScreen(cda: Double, crr: Double, onStartTest: () -> Unit, onFromHistory: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Pm.Background)) {
        Header("Calibration", "Step 1 of 3", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "While you coast, your power is zero: how fast you slow down gives your CdA (air drag, at high speed) and " +
                    "Crr (rolling resistance, at low speed). No power meter needed.",
                color = Pm.Text, fontSize = 15.sp, lineHeight = 21.sp,
            )
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pm.SurfaceAlt).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "Pick a straight, sheltered road, flat or with a steady gentle slope (1–2 % is fine), on a calm dry day.",
                    "Get up to 35–40 km/h, press volume −, stop pedalling and hold your normal position.",
                    "Coast down to 12–15 km/h without braking, then press volume − again.",
                    "Turn around and do the same the other way.",
                    "Do 5–6 out-and-back pairs. Wind and slope are solved from the runs in both directions.",
                ).forEachIndexed { i, t -> Text("${i + 1}. $t", color = Pm.Text, fontSize = 15.sp, lineHeight = 21.sp) }
            }
            Text("Now: CdA ${f3(cda)} m² · Crr ${f4(crr)}. Weight and bike weight must be right first (Parameters).", color = Pm.Muted, fontSize = 14.sp)
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().height(64.dp)) {
                BigButton("START THE TEST", Modifier.weight(1f), Pm.Green, Color(0xFF0B0D10), onClick = onStartTest)
            }
            Row(Modifier.fillMaxWidth().height(52.dp)) {
                BigButton("USE A PAST RIDE", Modifier.weight(1f), Pm.Surface, Pm.Text, onClick = onFromHistory)
            }
        }
    }
}

/** Step 2: the test page shown instead of the ride pages while riding the test. */
@Composable
fun CalibrationRidePage(snap: RideSnapshot, runs: RideController.CalibRuns, onMark: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (runs.coasting) "Coast without braking, hold your position. Press volume − before 12–15 km/h or before braking."
            else "Reach 35–40 km/h, then press volume − and stop pedalling. Then turn and do the same the other way.",
            color = Pm.Text, fontSize = 14.sp, lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pm.SurfaceAlt).padding(12.dp),
        )
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pm.Surface)
                .then(if (runs.coasting) Modifier.background(Color(0x223BC77A)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (runs.coasting) "COASTING · RUN ${runs.out + runs.back + 1}" else "PEDAL UP TO SPEED",
                    color = if (runs.coasting) Pm.Green else Pm.Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
                Text(Format.speedKmh(snap.speedMps), color = Pm.Text, fontFamily = Pm.Numbers, fontWeight = FontWeight.Bold, fontSize = 150.sp, lineHeight = 150.sp)
                Text("km/h", color = Pm.Muted, fontSize = 14.sp)
            }
        }
        Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataField("OUT", "${runs.out} runs", Modifier.weight(1f).fillMaxHeight(), valueSize = 40.sp)
            DataField("BACK", "${runs.back} runs", Modifier.weight(1f).fillMaxHeight(), valueSize = 40.sp)
        }
        Row(Modifier.fillMaxWidth().height(60.dp)) {
            BigButton(if (runs.coasting) "END RUN" else "START RUN", Modifier.weight(1f), Pm.Blue, Color.White, onClick = onMark)
        }
    }
}

/** Step 3: the result (SPEC §9.7). */
@Composable
fun CalibrationResultScreen(
    c: RideController.Calibration,
    currentCda: Double,
    currentCrr: Double,
    onKeepCrrFixed: (Boolean) -> Unit,
    onApply: () -> Unit,
    onDone: () -> Unit,
) {
    val r = c.result
    val f = r.fit
    Column(Modifier.fillMaxSize().background(Pm.Background)) {
        Header("Calibration result", "Step 3 of 3", null)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (f != null) {
                Row(Modifier.fillMaxWidth().height(104.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("CdA · m²", f3(f.cda), "± ${f3(f.seCda)} · was ${f3(currentCda)}", Pm.Power, Modifier.weight(1f))
                    Tile("Crr", f4(f.crr), (f.seCrr?.let { "± ${f4(it)}" } ?: "kept fixed") + " · was ${f4(currentCrr)}", Pm.Power, Modifier.weight(1f))
                }
                if (f.wind != null && f.slope != null) {
                    Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tile("Wind along the road", String.format(Locale.ROOT, "%.1f m/s", kotlin.math.abs(f.wind!!)), "solved from both directions", Pm.Text, Modifier.weight(1f))
                        Tile("Road slope", String.format(Locale.ROOT, "%.1f %%", kotlin.math.abs(f.slope!!) * 100), "solved from both directions", Pm.Text, Modifier.weight(1f))
                    }
                }
                Text(
                    "R² ${String.format(Locale.ROOT, "%.2f", f.r2)} · ${r.segs.count { it.dir == 1 }} runs one way, ${r.segs.count { it.dir == -1 }} the other" +
                        " · ${if (r.method == "paired") "out-and-back fit" else "one-direction fit"} · runs from ${
                            when (r.source) { "laps" -> "lap marks"; "cadence" -> "cadence"; else -> "slow-downs" }
                        }",
                    color = Pm.Muted, fontSize = 13.sp,
                )
                Scatter(r, Modifier.fillMaxWidth().height(200.dp))
            }
            c.warnings.forEach { w ->
                Text("⚠ $w", color = Pm.Amber, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pm.Surface).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Keep Crr fixed (${f4(currentCrr)})", color = Pm.Text, fontSize = 16.sp)
                    Text("Fit CdA only: tighter CdA when the runs are few or the speed range narrow", color = Pm.Muted, fontSize = 13.sp)
                }
                Switch(checked = c.keepCrrFixed, onCheckedChange = onKeepCrrFixed, colors = SwitchDefaults.colors(checkedTrackColor = Pm.Blue))
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp).height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("DONE", Modifier.weight(1f), Pm.Surface, Pm.Text, onClick = onDone)
            if (f != null) BigButton("APPLY TO PARAMETERS", Modifier.weight(1.6f), Pm.Blue, Color.White, onClick = onApply)
        }
    }
}

@Composable
private fun Tile(label: String, value: String, sub: String, color: Color, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Pm.Surface).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Pm.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = color, fontFamily = Pm.Numbers, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 40.sp)
        Text(sub, color = Pm.Muted, fontSize = 12.sp)
    }
}

/** Drag force against air pressure; both directions fall on one line once wind and slope are removed. */
@Composable
private fun Scatter(r: app.painmeter.core.calibration.CoastDown.Result, modifier: Modifier) {
    val f = r.fit ?: return
    val wins = r.wins
    if (wins.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pm.Surface).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Drag force vs air pressure", color = Pm.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Canvas(modifier.semantics { contentDescription = "Scatter plot of drag force against air pressure, with the fitted line" }) {
            val xs = wins.map { it.px }
            val ys = wins.map { it.py }
            val x0 = 0.0; val x1 = xs.max() * 1.05
            val used0 = wins.filter { it.used }.ifEmpty { wins }
            val icpt = used0.map { it.py }.average() - f.cda * used0.map { it.px }.average()
            val lo = minOf(icpt, ys.min())
            val y0 = lo - 0.05 * (ys.max() - lo); val y1 = ys.max() + 0.05 * (ys.max() - lo)
            fun px(x: Double) = ((x - x0) / (x1 - x0) * size.width).toFloat()
            fun py(y: Double) = (size.height - (y - y0) / (y1 - y0) * size.height).toFloat()
            drawLine(Pm.Border, Offset(0f, py(y0)), Offset(size.width, py(y0)))
            // Fitted line, slope CdA, through the mean of the windows used in the fit.
            val used = wins.filter { it.used }.ifEmpty { wins }
            val intercept = used.map { it.py }.average() - f.cda * used.map { it.px }.average()
            drawLine(Pm.Power, Offset(px(x0), py(intercept + f.cda * x0)), Offset(px(x1), py(intercept + f.cda * x1)), strokeWidth = 4f)
            wins.forEach { w ->
                val c = when { !w.used -> Pm.Disabled; w.dir == -1 -> Color(0xFFFF9F43); else -> Color(0xFF4C9AFF) }
                drawCircle(c, radius = 7f, center = Offset(px(w.px), py(w.py)))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("one way" to Color(0xFF4C9AFF), "the other way" to Color(0xFFFF9F43), "left out" to Pm.Disabled).forEach { (t, c) ->
                Text("●", color = c, fontSize = 12.sp)
                Text(t, color = Pm.Muted, fontSize = 12.sp, textAlign = TextAlign.Start)
            }
        }
    }
}
