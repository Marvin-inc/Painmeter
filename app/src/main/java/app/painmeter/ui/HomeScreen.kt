package app.painmeter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.painmeter.HrState
import java.io.File

/**
 * Before the ride: GPS status, start, and the offer to resume a ride left open by a crash.
 * [gpsAccuracyM] is null while there is no fix; [hasPermission] false until location is granted.
 */
@Composable
fun HomeScreen(
    hasPermission: Boolean,
    gpsAccuracyM: Double?,
    hr: HrState,
    unfinished: File?,
    onStart: () -> Unit,
    onGrant: () -> Unit,
    onResume: (File) -> Unit,
    onSaveUnfinished: (File) -> Unit,
    onParameters: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Pm.Background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Painmeter", color = Pm.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))

        val (gpsText, gpsColor) = when {
            !hasPermission -> "Location permission needed" to Pm.Rec
            gpsAccuracyM == null -> "GPS: searching…" to Pm.Amber
            gpsAccuracyM > 15 -> "GPS: weak (${gpsAccuracyM.toInt()} m)" to Pm.Amber
            else -> "GPS: ready (${gpsAccuracyM.toInt()} m)" to Pm.Green
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pm.Surface).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(gpsText, color = gpsColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            val (hrText, hrColor) = when (hr) {
                HrState.NotPaired -> "Heart rate: no strap paired (Parameters)" to Pm.Muted
                HrState.NoPermission -> "Heart rate: Bluetooth permission needed" to Pm.Amber
                HrState.BluetoothOff -> "Heart rate: Bluetooth is off" to Pm.Amber
                is HrState.Connecting -> "Heart rate: connecting to ${hr.name}…" to Pm.Amber
                is HrState.Connected -> "Heart rate: ${hr.bpm?.let { "$it bpm" } ?: "no skin contact"}" to
                    (if (hr.bpm != null) Pm.Green else Pm.Amber)
            }
            Text(hrText, color = hrColor, fontSize = 16.sp)
            Text("Power: phase 3", color = Pm.Muted, fontSize = 14.sp)
        }

        if (unfinished != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pm.SurfaceAlt).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("A ride was not stopped (${unfinished.name}).", color = Pm.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Resume it, or save it as it is.", color = Pm.Muted, fontSize = 14.sp)
                Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton("RESUME", Modifier.weight(1f), Pm.Blue, Color.White, onClick = { onResume(unfinished) })
                    BigButton("SAVE", Modifier.weight(1f), Pm.Surface, Pm.Text, onClick = { onSaveUnfinished(unfinished) })
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth().height(56.dp)) {
            BigButton("PARAMETERS", Modifier.weight(1f), Pm.Surface, Pm.Text, onClick = onParameters)
        }

        Row(Modifier.fillMaxWidth().height(96.dp)) {
            if (hasPermission) {
                BigButton("START", Modifier.weight(1f), Pm.Green, Color(0xFF0B0D10), onClick = onStart)
            } else {
                BigButton("ALLOW LOCATION", Modifier.weight(1f), Pm.Amber, Color(0xFF111111), onClick = onGrant)
            }
        }
        Text(
            "During a ride: volume − = lap. Hold STOP to end the ride.",
            color = Pm.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}
