package app.painmeter.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.painmeter.RideStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Past rides (SPEC §3): open the summary, share the FIT file, use a ride for calibration, delete.
 * [pickForCalibration] shows the list for choosing a coast-down ride.
 */
@Composable
fun HistoryScreen(
    rides: List<Pair<File, RideStore.Summary>>?,
    pickForCalibration: Boolean,
    onOpen: (File) -> Unit,
    onShare: (File) -> Unit,
    onCalibrate: (File) -> Unit,
    onDelete: (File) -> Unit,
    onBack: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.ENGLISH) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    Column(Modifier.fillMaxSize().background(Pm.Background)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.height(48.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Back", onClick = onBack).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("←", color = Pm.Text, fontSize = 24.sp) }
            Text(
                if (pickForCalibration) "Pick the coast-down ride" else "History",
                color = Pm.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Pm.Divider))
        when {
            rides == null -> Text("Loading…", color = Pm.Muted, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
            rides.isEmpty() -> Text("No ride yet.", color = Pm.Muted, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                // A run cannot calibrate the bike's CdA / Crr.
                items(if (pickForCalibration) rides.filter { !it.second.run } else rides, key = { it.first.name }) { (dir, s) ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pm.Surface)
                            .clickable(onClickLabel = if (pickForCalibration) "Calibrate from this ride" else "Open the summary") {
                                if (pickForCalibration) onCalibrate(dir) else onOpen(dir)
                            }
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text((if (s.run) "RUN · " else "") + fmt.format(Date(s.startMs)), color = Pm.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${Format.distanceKm(s.distanceM)} km", color = Pm.Text, fontSize = 15.sp)
                            Text(Format.duration(s.rideTimeMs), color = Pm.Text, fontSize = 15.sp)
                            Text("${Math.round(s.gainM)} m", color = Pm.Text, fontSize = 15.sp)
                            Text(
                                if (s.run) "${Format.pace(s.avgSpeedMps)} /km" else s.avgPower?.let { "${Math.round(it)} W" } ?: "",
                                color = Pm.Power, fontSize = 15.sp,
                            )
                            Text(s.avgHr?.let { "$it bpm" } ?: "", color = Pm.Muted, fontSize = 15.sp)
                        }
                        if (!pickForCalibration) {
                            Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BigButton("SHARE FIT", Modifier.weight(1f), Pm.SurfaceAlt, Pm.Text, onClick = { onShare(dir) })
                                if (!s.run) BigButton("CALIBRATE", Modifier.weight(1f), Pm.SurfaceAlt, Pm.Text, onClick = { onCalibrate(dir) })
                                if (confirmDelete == dir) {
                                    BigButton("SURE?", Modifier.weight(0.8f), Pm.Rec, Color.White, onClick = { onDelete(dir); confirmDelete = null })
                                } else {
                                    BigButton("DELETE", Modifier.weight(0.8f), Pm.SurfaceAlt, Pm.Rec, onClick = { confirmDelete = dir })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
