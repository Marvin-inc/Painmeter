package app.painmeter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.painmeter.core.HrZones
import app.painmeter.core.RideSnapshot

/** The Lap page (SPEC §9.4): current lap now and on average, and the last lap. */
@Composable
fun LapPage(snap: RideSnapshot, zones: HrZones?, onLap: () -> Unit, modifier: Modifier = Modifier) {
    if (snap.sport == app.painmeter.core.run.Sport.RUN) {
        RunLapPage(snap, zones, onLap, modifier)
        return
    }
    val ftp = snap.ftpW.coerceAtLeast(1)
    fun pct(w: Double?) = w?.let { Math.round(it / ftp * 100).toString() } ?: "--"
    val powerColor = if (snap.power.powerShort == null) Pm.Disabled else if (snap.power.reliable) Pm.Power else Pm.Uncertain

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataField("LAP ${snap.lapNumber} · TIME", Format.duration(snap.lapTimeMs), Modifier.weight(1f).fillMaxHeight(), valueSize = 64.sp)
            BigButton("LAP\nor volume −", Modifier.width(120.dp), Pm.Blue, Color.White, onClick = onLap)
        }

        Label("NOW")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataField("POWER ${snap.power.windowS} s · W", Format.watts(snap.power.powerShort), Modifier.weight(1f).fillMaxHeight(), valueSize = 72.sp, valueColor = powerColor)
                DataField("% FTP · ${snap.power.windowS} s", pct(snap.power.powerShort), Modifier.weight(1f).fillMaxHeight(), valueSize = 72.sp, valueColor = powerColor)
            }
            Row(Modifier.fillMaxWidth().weight(0.8f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataField("SPEED · km/h", Format.speedKmh(snap.speedMps), Modifier.weight(1f).fillMaxHeight(), valueSize = 56.sp)
                HrField(snap.hrBpm, snap.hrZone, Modifier.weight(1f).fillMaxHeight(), valueSize = 56.sp)
            }
        }

        Label("LAP AVERAGE")
        Column(Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataField("AVG POWER · W", Format.watts(snap.lapAvgPower), Modifier.weight(1f).fillMaxHeight(), valueSize = 60.sp, background = Pm.SurfaceAlt)
                DataField("AVG % FTP", pct(snap.lapAvgPower), Modifier.weight(1f).fillMaxHeight(), valueSize = 60.sp, background = Pm.SurfaceAlt)
            }
            HrField(
                snap.lapAvgHr, snap.lapAvgHr?.let { zones?.zoneOf(it) }, Modifier.fillMaxWidth().weight(0.9f),
                valueSize = 56.sp, label = "AVG HR · bpm · zone",
            )
        }

        snap.lastLap?.takeIf { !it.endedBySession }?.let { l ->
            val z = l.avgHr?.let { zones?.zoneOf(it) }
            Row(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(Pm.Surface).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("LAP ${l.index + 1}", color = Pm.Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(Format.duration(l.timerMs), color = Pm.Text, fontSize = 15.sp)
                Text(l.avgPower?.let { "${Math.round(it)} W" } ?: "-- W", color = Pm.Power, fontSize = 15.sp)
                Text("${pct(l.avgPower)} %", color = Pm.Text, fontSize = 15.sp)
                Text(l.avgHr?.let { "$it" + (z?.let { " Z$it" } ?: "") } ?: "--", color = Pm.zoneColor(z) ?: Pm.Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Pm.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
}

/**
 * The Lap page when running (SPEC §16.5): lap time and distance (to the next km with the auto
 * lap), pace now and on the lap, GAP of the lap, HR, and the last lap (the km just run).
 */
@Composable
private fun RunLapPage(snap: RideSnapshot, zones: HrZones?, onLap: () -> Unit, modifier: Modifier) {
    val lapPace = if (snap.lapDistanceM > 20) snap.lapAvgSpeedMps else null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataField("LAP ${snap.lapNumber} · TIME", Format.duration(snap.lapTimeMs), Modifier.weight(1f).fillMaxHeight(), valueSize = 64.sp)
            BigButton("LAP\nor volume −", Modifier.width(120.dp), Pm.Blue, Color.White, onClick = onLap)
        }

        Label("NOW")
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataField("PACE · /km", Format.pace(snap.paceSpeedMps), Modifier.weight(1f).fillMaxHeight(), valueSize = 72.sp, valueColor = Pm.Power)
            HrField(snap.hrBpm, snap.hrZone, Modifier.weight(1f).fillMaxHeight(), valueSize = 72.sp)
        }

        Label("LAP AVERAGE")
        Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataField("LAP PACE · /km", Format.pace(lapPace), Modifier.weight(1f).fillMaxHeight(), valueSize = 60.sp, background = Pm.SurfaceAlt, valueColor = Pm.Power)
                DataField("LAP GAP · /km", Format.pace(snap.lapGapSpeedMps), Modifier.weight(1f).fillMaxHeight(), valueSize = 60.sp, background = Pm.SurfaceAlt)
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val toNext = snap.autoLapM?.let { (it - snap.distanceM).coerceAtLeast(0.0) }
                DataField(
                    if (toNext != null) "NEXT KM IN · m" else "LAP DIST · km",
                    if (toNext != null) Format.metres(toNext) else Format.distanceKm(snap.lapDistanceM),
                    Modifier.weight(1f).fillMaxHeight(), valueSize = 56.sp, background = Pm.SurfaceAlt,
                )
                HrField(
                    snap.lapAvgHr, snap.lapAvgHr?.let { zones?.zoneOf(it) }, Modifier.weight(1f).fillMaxHeight(),
                    valueSize = 56.sp, label = "AVG HR · zone",
                )
            }
        }

        snap.lastLap?.takeIf { !it.endedBySession }?.let { l ->
            val z = l.avgHr?.let { zones?.zoneOf(it) }
            Row(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(Pm.Surface).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(if (l.auto) "KM ${l.index + 1}" else "LAP ${l.index + 1}", color = Pm.Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(Format.duration(l.timerMs), color = Pm.Text, fontSize = 15.sp)
                Text("${Format.pace(l.avgSpeedMps)} /km", color = Pm.Power, fontSize = 15.sp)
                Text("GAP ${Format.pace(l.gapSpeedMps)}", color = Pm.Text, fontSize = 15.sp)
                Text(l.avgHr?.let { "$it" + (z?.let { " Z$it" } ?: "") } ?: "--", color = Pm.zoneColor(z) ?: Pm.Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
