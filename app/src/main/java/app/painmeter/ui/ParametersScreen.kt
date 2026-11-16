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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.painmeter.FoundStrap
import app.painmeter.HrState
import app.painmeter.UserSettings
import app.painmeter.core.HrZones

/**
 * Parameters (SPEC §9.6), phase 2 part: rider max HR and zones, the HR strap, auto-pause.
 * Weight, FTP, bike and aero settings arrive with the power model in phase 3.
 */
@Composable
fun ParametersScreen(
    settings: UserSettings,
    hr: HrState,
    found: List<FoundStrap>,
    scanning: Boolean,
    onChange: (UserSettings) -> Unit,
    onScan: () -> Unit,
    onPair: (FoundStrap) -> Unit,
    onForget: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Pm.Background)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.height(48.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Back", onClick = onBack)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("←", color = Pm.Text, fontSize = 24.sp) }
            Text("Parameters", color = Pm.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Pm.Divider))

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("HEART RATE") {
                HrZonesEditor(settings, onChange)
            }
            Section("HR STRAP") {
                StrapPanel(hr, found, scanning, onScan, onPair, onForget)
            }
            Section("RECORDING") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-pause", color = Pm.Text, fontSize = 16.sp)
                        Text("Below 2 km/h for 3 s, restarts above 4 km/h", color = Pm.Muted, fontSize = 13.sp)
                    }
                    Switch(
                        checked = settings.autoPause,
                        onCheckedChange = { onChange(settings.copy(autoPause = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Pm.Blue),
                    )
                }
            }
            Text(
                "Weight, FTP, CdA and Crr come with the power model (phase 3).",
                color = Pm.Muted, fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Pm.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pm.Surface).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

@Composable
private fun HrZonesEditor(settings: UserSettings, onChange: (UserSettings) -> Unit) {
    var maxText by remember(settings.maxHr) { mutableStateOf(settings.maxHr.toString()) }
    var boundTexts by remember(settings.hrZoneBounds) { mutableStateOf(settings.hrZoneBounds.map { it.toString() }) }
    val bounds = boundTexts.map { it.toIntOrNull() }
    val valid = bounds.all { it != null && it in 40..240 } && bounds.filterNotNull().zipWithNext().all { (a, b) -> a < b }

    NumberField("Max HR", maxText, Modifier.fillMaxWidth()) { t ->
        maxText = t
        t.toIntOrNull()?.takeIf { it in 100..240 }?.let { onChange(settings.copy(maxHr = it)) }
    }
    Box(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(Pm.SurfaceAlt)
            .clickable { onChange(settings.copy(hrZoneBounds = HrZones.fromMaxHr(settings.maxHr).upperBounds)) },
        contentAlignment = Alignment.Center,
    ) { Text("Set zones from max HR (60 / 70 / 80 / 90 %)", color = Pm.Power, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }

    Text("Top of each zone (bpm)", color = Pm.Muted, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (0 until 4).forEach { i ->
            NumberField("Z${i + 1}", boundTexts[i], Modifier.weight(1f)) { t ->
                val next = boundTexts.toMutableList().also { it[i] = t }
                boundTexts = next
                val nb = next.map { it.toIntOrNull() }
                if (nb.all { it != null && it in 40..240 } && nb.filterNotNull().zipWithNext().all { (a, b) -> a < b }) {
                    onChange(settings.copy(hrZoneBounds = nb.filterNotNull()))
                }
            }
        }
    }
    if (!valid) Text("Each zone must end above the previous one.", color = Pm.Rec, fontSize = 13.sp)

    // Preview bar, as in the mockup.
    val b = settings.hrZoneBounds
    val labels = listOf("Z1 ≤${b[0]}", "Z2 ≤${b[1]}", "Z3 ≤${b[2]}", "Z4 ≤${b[3]}", "Z5 >${b[3]}")
    Row(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(6.dp))) {
        labels.forEachIndexed { i, label ->
            Box(Modifier.weight(1f).fillMaxSize().background(Pm.Zones[i]), contentAlignment = Alignment.Center) {
                Text(label, color = Pm.Background, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StrapPanel(
    hr: HrState,
    found: List<FoundStrap>,
    scanning: Boolean,
    onScan: () -> Unit,
    onPair: (FoundStrap) -> Unit,
    onForget: () -> Unit,
) {
    val (line, color) = when (hr) {
        HrState.NotPaired -> "No strap paired" to Pm.Muted
        HrState.NoPermission -> "Bluetooth permission needed" to Pm.Amber
        HrState.BluetoothOff -> "Bluetooth is off" to Pm.Amber
        is HrState.Connecting -> "${hr.name} · connecting…" to Pm.Amber
        is HrState.Connected -> "${hr.name} · " + (hr.bpm?.let { "$it bpm" } ?: "no skin contact") to
            (if (hr.bpm != null) Pm.Green else Pm.Amber)
    }
    Text(line, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigButton(if (scanning) "SEARCHING…" else "SCAN", Modifier.weight(1f), Pm.Blue, Color.White, onClick = onScan)
        if (hr != HrState.NotPaired) BigButton("FORGET", Modifier.weight(1f), Pm.Surface, Pm.Text, onClick = onForget)
    }
    if (scanning || found.isNotEmpty()) {
        Text(
            if (found.isEmpty()) "Wear the strap (wet the electrodes) so it wakes up…" else "Tap a strap to pair it:",
            color = Pm.Muted, fontSize = 13.sp,
        )
    }
    found.forEach { strap ->
        Row(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).background(Pm.SurfaceAlt)
                .clickable(onClickLabel = "Pair ${strap.name}") { onPair(strap) }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(strap.name, color = Pm.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("${strap.rssi} dBm", color = Pm.Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { t -> onValue(t.filter(Char::isDigit).take(3)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Pm.Text, unfocusedTextColor = Pm.Text,
            focusedBorderColor = Pm.Power, unfocusedBorderColor = Pm.Border,
            focusedLabelColor = Pm.Power, unfocusedLabelColor = Pm.Muted, cursorColor = Pm.Power,
        ),
    )
}
