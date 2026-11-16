package app.painmeter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.painmeter.FinishedRide
import app.painmeter.core.HrZones

@Composable
fun SummaryScreen(ride: FinishedRide, zones: HrZones, onShare: () -> Unit, onDone: () -> Unit) {
    val s = ride.snapshot
    Column(Modifier.fillMaxSize().background(Pm.Background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ride summary", color = Pm.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(4.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataField("RIDE TIME", Format.duration(s.rideTimeMs), Modifier.weight(1f), valueSize = 44.sp)
                DataField("DIST · km", Format.distanceKm(s.distanceM), Modifier.weight(1f), valueSize = 44.sp)
            }
            Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataField("AVG km/h", Format.speedKmh(s.avgSpeedMps), Modifier.weight(1f), valueSize = 44.sp)
                DataField("MAX km/h", Format.speedKmh(s.maxSpeedMps), Modifier.weight(1f), valueSize = 44.sp)
                DataField("GAIN · m", Format.metres(s.elevationGainM), Modifier.weight(1f), valueSize = 44.sp)
            }
            if (s.avgPower != null) {
                Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataField("AVG POWER · W", Format.watts(s.avgPower), Modifier.weight(1f), valueSize = 44.sp, valueColor = Pm.Power)
                    DataField("NP · W", Format.watts(s.normalizedPower), Modifier.weight(1f), valueSize = 44.sp, valueColor = Pm.Power)
                    DataField("MAX · W", Format.watts(s.maxPower), Modifier.weight(1f), valueSize = 44.sp)
                }
                Text("Estimated power, not measured: see SPEC §5.4 for the expected accuracy.", color = Pm.Muted, fontSize = 13.sp)
            }
            if (s.avgHr != null) {
                Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HrField(s.avgHr, s.avgHr?.let(zones::zoneOf), Modifier.weight(1f), 44.sp, label = "AVG HR · zone")
                    HrField(s.maxHr, s.maxHr?.let(zones::zoneOf), Modifier.weight(1f), 44.sp, label = "MAX HR · zone")
                }
                ZoneTimes(s.hrZoneMs)
            }
            if (ride.laps.size > 1) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pm.Surface).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("LAPS", color = Pm.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    ride.laps.forEach { lap ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Lap ${lap.index + 1}", color = Pm.Muted, fontSize = 15.sp)
                            Text(Format.duration(lap.timerMs), color = Pm.Text, fontSize = 15.sp)
                            Text("${Format.distanceKm(lap.distanceM)} km", color = Pm.Text, fontSize = 15.sp)
                            Text("${Format.speedKmh(lap.avgSpeedMps)} km/h", color = Pm.Text, fontSize = 15.sp)
                            Text(lap.avgPower?.let { "${Math.round(it)} W" } ?: "", color = Pm.Power, fontSize = 15.sp)
                            Text(lap.avgHr?.let { "$it bpm" } ?: "", color = Pm.zoneColor(lap.avgHr?.let(zones::zoneOf)) ?: Pm.Muted, fontSize = 15.sp)
                        }
                    }
                }
            }
            ride.error?.let { Text(it, color = Pm.Rec, fontSize = 14.sp) }
        }
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ride.fitFile != null) BigButton("SHARE FIT", Modifier.weight(1.3f), Pm.Blue, Color.White, onClick = onShare)
            BigButton("DONE", Modifier.weight(1f), Pm.Surface, Pm.Text, onClick = onDone)
        }
    }
}

/** Time in each HR zone, as a bar of proportional widths with the minutes below. */
@Composable
private fun ZoneTimes(zoneMs: List<Long>) {
    val total = zoneMs.sum()
    if (total <= 0) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pm.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("TIME IN HR ZONES", color = Pm.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp))) {
            zoneMs.forEachIndexed { i, ms ->
                if (ms > 0) Box(Modifier.weight(ms.toFloat()).fillMaxHeight().background(Pm.Zones[i]))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            zoneMs.forEachIndexed { i, ms ->
                Text("Z${i + 1} ${ms / 60_000}′", color = Pm.Zones[i], fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
