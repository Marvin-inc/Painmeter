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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.painmeter.RideController
import app.painmeter.core.RideSnapshot
import kotlin.math.max

/** The Climb page (SPEC §9.3). */
@Composable
fun ClimbPage(snap: RideSnapshot, climb: RideController.ClimbUi, modifier: Modifier = Modifier) {
    val p = climb.progress
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Profile card.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pm.Surface).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val title = when {
                p == null -> "NO CLIMB"
                p.finished -> "TOP · climb done"
                climb.climbNumber != null -> "CLIMB ${climb.climbNumber} of ${climb.climbCount} · on route"
                else -> "CLIMB · detected"
            }
            val info = when {
                p == null -> ""
                p.fromRoute != null -> p.fromRoute!!.let {
                    "${Format.distanceKm(it.lengthM)} km · ${Format.grade(it.avgGradePercent)} % · ${Math.round(it.gainM)} m"
                }
                else -> "so far ${Format.distanceKm(p.distanceM)} km · ${Math.round(p.gainM)} m"
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Pm.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(info, color = Pm.Muted, fontSize = 13.sp)
            }
            if (p == null) {
                Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    val next = climb.next
                    Text(
                        if (next != null && climb.distanceToNextM != null) {
                            "Next climb in ${Format.distanceKm(climb.distanceToNextM)} km\n" +
                                "${Format.distanceKm(next.lengthM)} km at ${Format.grade(next.avgGradePercent)} % · ${Math.round(next.gainM)} m"
                        } else "No climb right now.\nThis page opens by itself when one starts.",
                        color = Pm.Muted, fontSize = 17.sp, lineHeight = 24.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                val points = climb.routeProfile ?: p.ridden
                Profile(points, climb.riderOnClimbM ?: points.lastOrNull()?.first, Modifier.fillMaxWidth().height(150.dp))
                GradeLegend()
            }
        }

        if (p != null && climb.remainingM != null) {
            Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataField("TO TOP · km", Format.distanceKm(climb.remainingM), Modifier.weight(1f).fillMaxHeight(), valueSize = 40.sp, background = Pm.SurfaceAlt)
                DataField("TO TOP · m", Math.round(climb.remainingGainM ?: 0.0).toString(), Modifier.weight(1f).fillMaxHeight(), valueSize = 40.sp, background = Pm.SurfaceAlt)
                val g = climb.remainingGradePercent
                DataField(
                    "AVG LEFT · %", g?.let { Format.grade(it) } ?: "--", Modifier.weight(1f).fillMaxHeight(), valueSize = 40.sp,
                    background = Pm.SurfaceAlt, valueColor = g?.let { Pm.gradeColor(it) } ?: Pm.Disabled,
                )
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataField(
                    "POWER ${snap.power.windowS} s · W", Format.watts(snap.power.powerShort), Modifier.weight(1f).fillMaxHeight(), valueSize = 80.sp,
                    valueColor = if (snap.power.powerShort == null) Pm.Disabled else if (snap.power.reliable) Pm.Power else Pm.Uncertain,
                )
                DataField(
                    if (p?.finished == true) "CLIMB AVG · W" else "AVG CLIMB · W", Format.watts(p?.avgPower),
                    Modifier.weight(1f).fillMaxHeight(), valueSize = 80.sp, valueColor = if (p == null) Pm.Disabled else Pm.Text,
                )
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val g = snap.power.gradePercent
                DataField(
                    "GRADE · %", g?.let { Format.grade(it) } ?: "--", Modifier.weight(1f).fillMaxHeight(), valueSize = 80.sp,
                    valueColor = g?.let { Pm.gradeColor(it) } ?: Pm.Disabled,
                )
                DataField("SPEED · km/h", Format.speedKmh(snap.speedMps), Modifier.weight(1f).fillMaxHeight(), valueSize = 80.sp)
            }
            Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataField(
                    "VAM · m/h", p?.vamMph?.let { Math.round(it).toString() } ?: "--", Modifier.weight(1f).fillMaxHeight(),
                    valueSize = 52.sp, valueColor = if (p?.vamMph == null) Pm.Disabled else Pm.Text,
                )
                HrField(snap.hrBpm, snap.hrZone, Modifier.weight(1f).fillMaxHeight(), valueSize = 52.sp)
            }
        }
    }
}

/** Elevation profile as bars coloured by gradient; climbed part solid, the rest faded. */
@Composable
private fun Profile(points: List<Pair<Double, Double>>, riderM: Double?, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "Climb profile coloured by gradient" }) {
        if (points.size < 2) return@Canvas
        val total = points.last().first.coerceAtLeast(1.0)
        val minE = points.minOf { it.second }
        val maxE = points.maxOf { it.second }
        val span = max(maxE - minE, 20.0)
        val bars = 40
        val w = size.width / bars
        for (b in 0 until bars) {
            val d0 = total * b / bars
            val d1 = total * (b + 1) / bars
            val e0 = interp(points, d0)
            val e1 = interp(points, d1)
            val grade = (e1 - e0) / (d1 - d0) * 100
            val h = ((max(e0, e1) - minE) / span * size.height * 0.9 + size.height * 0.08).toFloat()
            val done = riderM == null || d0 <= riderM
            drawRoundRect(
                Pm.gradeColor(grade.coerceAtLeast(3.0)).let { if (grade < 3) Color(0xFF6FCF97) else it }.copy(alpha = if (done) 1f else 0.45f),
                topLeft = Offset(b * w + 1, size.height - h), size = Size(w - 2, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
            )
        }
        riderM?.let {
            val x = (it / total * size.width).toFloat().coerceIn(0f, size.width - 4)
            drawRect(Pm.Text, topLeft = Offset(x, 0f), size = Size(4f, size.height))
        }
    }
}

private fun interp(points: List<Pair<Double, Double>>, d: Double): Double {
    val i = points.indexOfLast { it.first <= d }.coerceIn(0, points.size - 2)
    val (da, ea) = points[i]
    val (db, eb) = points[i + 1]
    return if (db > da) ea + (eb - ea) * ((d - da) / (db - da)).coerceIn(0.0, 1.0) else ea
}

@Composable
private fun GradeLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("<3" to Color(0xFF6FCF97), "3–6" to Pm.gradeColor(4.0), "6–9" to Pm.gradeColor(7.0), "9–12" to Pm.gradeColor(10.0), ">12 %" to Pm.gradeColor(13.0))
            .forEach { (label, c) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("■", color = c, fontSize = 11.sp)
                    Text(" $label", color = Pm.Muted, fontSize = 11.sp)
                }
            }
    }
}
