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
import androidx.compose.foundation.layout.width
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

/** Parameters (SPEC §9.6). Every change is saved at once. */
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
    elevation: ElevationPanelState = ElevationPanelState(),
    onDownloadElevation: () -> Unit = {},
    onDeleteElevation: () -> Unit = {},
    route: RouteInfo? = null,
    maps: List<java.io.File> = emptyList(),
    message: String? = null,
    onImportRoute: () -> Unit = {},
    onClearRoute: () -> Unit = {},
    onImportMap: () -> Unit = {},
    onDeleteMap: (java.io.File) -> Unit = {},
    onOpenMapDownloads: () -> Unit = {},
    onCalibrate: () -> Unit = {},
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
            message?.let { Text(it, color = Pm.Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Section("ROUTE") {
                if (route == null) {
                    Text("No route: the map shows only your track.", color = Pm.Muted, fontSize = 15.sp)
                } else {
                    Text(route.name, color = Pm.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(route.summary, color = Pm.Muted, fontSize = 14.sp)
                }
                Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton("IMPORT GPX", Modifier.weight(1.4f), Pm.Blue, Color.White, onClick = onImportRoute)
                    if (route != null) BigButton("CLEAR", Modifier.weight(1f), Pm.Surface, Pm.Text, onClick = onClearRoute)
                }
                Text("Or open a .gpx from Komoot, Strava, Files… with Painmeter.", color = Pm.Muted, fontSize = 13.sp)
            }
            Section("MAPS") {
                if (maps.isEmpty()) {
                    Text("No offline map yet.", color = Pm.Amber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    maps.forEach { f ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(f.name.removeSuffix(".map"), color = Pm.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text("${f.length() / 1_000_000} MB", color = Pm.Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                            Box(
                                Modifier.height(40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Delete ${f.name}") { onDeleteMap(f) }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("Delete", color = Pm.Rec, fontSize = 14.sp) }
                        }
                    }
                }
                Text(
                    "1. Download your region's .map file (Mapsforge format, e.g. europe/france/rhone-alpes.map) on the phone.\n" +
                        "2. Import it here.",
                    color = Pm.Muted, fontSize = 13.sp,
                )
                Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton("GET MAPS", Modifier.weight(1f), Pm.Surface, Pm.Text, onClick = onOpenMapDownloads)
                    BigButton("IMPORT .MAP", Modifier.weight(1f), Pm.Blue, Color.White, onClick = onImportMap)
                }
            }
            Section("DISPLAY & LOCATION") {
                Text("Power shown on screen: average over", color = Pm.Text, fontSize = 16.sp)
                Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 5, 10, 20, 30).forEach { w ->
                        val on = settings.powerWindowS == w
                        BigButton(
                            "$w s", Modifier.weight(1f), if (on) Pm.Blue else Pm.SurfaceAlt, if (on) Color.White else Pm.Text,
                            onClick = { onChange(settings.copy(powerWindowS = w)) },
                        )
                    }
                }
                Text(
                    if (settings.powerWindowS <= 3) "⚠ 3 s is very noisy with estimated power (about ±30–40 % on the flat): it shows the trend of a sprint, not a number to trust."
                    else "Shorter reacts faster but is noisier; 10 s is a good start. The FIT file keeps its own 5 s value.",
                    color = if (settings.powerWindowS <= 3) Pm.Amber else Pm.Muted, fontSize = 13.sp,
                )
                Text("Location source", color = Pm.Text, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton(
                        "GOOGLE\nfast start", Modifier.weight(1f),
                        if (!settings.rawGps) Pm.Blue else Pm.SurfaceAlt, if (!settings.rawGps) Color.White else Pm.Text,
                        onClick = { onChange(settings.copy(rawGps = false)) },
                    )
                    BigButton(
                        "GPS CHIP\nless lag", Modifier.weight(1f),
                        if (settings.rawGps) Pm.Blue else Pm.SurfaceAlt, if (settings.rawGps) Color.White else Pm.Text,
                        onClick = { onChange(settings.copy(rawGps = true)) },
                    )
                }
                Text(
                    if (settings.rawGps) "Raw GPS chip: about 1 s less lag on speed, but a slower first fix (wait for \"GPS: ready\" outside) and weaker under trees and in tunnels."
                    else "Google's location service: quick first fix (Wi-Fi and cell help), but it filters positions and can add about 1 s of lag on speed.",
                    color = Pm.Muted, fontSize = 13.sp,
                )
                ToggleRow(
                    "Compass when stopped",
                    "Turns the map with the phone's compass below 4 km/h. Turn off if the map points wrong (a motor or magnet near the phone).",
                    settings.compassWhenStopped,
                ) { onChange(settings.copy(compassWhenStopped = it)) }
            }
            Section("RIDER") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField("Weight kg", settings.riderKg, 30.0..200.0, Modifier.weight(1f)) { onChange(settings.copy(riderKg = it)) }
                    DecimalField("FTP W", settings.ftpW.toDouble(), 50.0..600.0, Modifier.weight(1f), decimals = 0) { onChange(settings.copy(ftpW = it.toInt())) }
                }
            }
            Section("BIKE & AERO") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField("Bike weight kg", settings.bikeKg, 3.0..30.0, Modifier.weight(1f)) { onChange(settings.copy(bikeKg = it)) }
                    DecimalField("Drivetrain loss %", settings.lossPercent, 0.0..10.0, Modifier.weight(1f)) { onChange(settings.copy(lossPercent = it)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField("CdA m²", settings.cda, 0.10..0.80, Modifier.weight(1f), decimals = 3) { onChange(settings.copy(cda = it)) }
                    DecimalField("Crr", settings.crr, 0.0015..0.015, Modifier.weight(1f), decimals = 4) { onChange(settings.copy(crr = it)) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        settings.calibDateMs?.let { d ->
                            "Calibrated " + java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH).format(java.util.Date(d)) +
                                (settings.calibSeCda?.let { " · CdA ±" + String.format(java.util.Locale.ROOT, "%.3f", it) } ?: "") +
                                (settings.calibSeCrr?.let { " · Crr ±" + String.format(java.util.Locale.ROOT, "%.4f", it) } ?: "")
                        } ?: "Not calibrated yet",
                        color = Pm.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f),
                    )
                    BigButton("CALIBRATE →", Modifier.height(44.dp).width(140.dp), Pm.SurfaceAlt, Pm.Power, onClick = onCalibrate)
                }
            }
            Section("ON A WHEEL") {
                DecimalField("CdA factor on a wheel", settings.draftFactor, 0.3..1.0, Modifier.fillMaxWidth(), decimals = 2) { onChange(settings.copy(draftFactor = it)) }
                ToggleRow("Reminder after 20 min", "Vibrates in case you forgot to switch back to Solo", settings.draftReminder) {
                    onChange(settings.copy(draftReminder = it))
                }
            }
            Section("WEATHER & WIND") {
                ToggleRow("Live weather (Open-Meteo)", "Wind and air density, refreshed every 20 min or 10 km", settings.weatherOn) {
                    onChange(settings.copy(weatherOn = it))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField("Wind factor", settings.windFactor, 0.0..1.0, Modifier.weight(1f), decimals = 2) { onChange(settings.copy(windFactor = it)) }
                    DecimalField("ρ without weather", settings.rhoFallback, 0.8..1.4, Modifier.weight(1f), decimals = 3) { onChange(settings.copy(rhoFallback = it)) }
                }
            }
            Section("ELEVATION DATA") {
                ElevationPanel(elevation, onDownloadElevation, onDeleteElevation)
                ToggleRow(
                    "Download missing tiles during the ride",
                    "When you ride off the downloaded area (about 10–25 MB per tile, may use mobile data)",
                    settings.autoElevation,
                ) { onChange(settings.copy(autoElevation = it)) }
            }
            Section("CLIMBS") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField("Min grade %", settings.climbMinGrade, 1.0..10.0, Modifier.weight(1f)) { onChange(settings.copy(climbMinGrade = it)) }
                    DecimalField("Min length m", settings.climbMinLength, 100.0..5000.0, Modifier.weight(1f), decimals = 0) { onChange(settings.copy(climbMinLength = it)) }
                    DecimalField("Min gain m", settings.climbMinGain, 5.0..500.0, Modifier.weight(1f), decimals = 0) { onChange(settings.copy(climbMinGain = it)) }
                }
                ToggleRow("Show the Climb page automatically", "When a climb starts; back to the previous page 10 s after the top", settings.climbAutoSwitch) {
                    onChange(settings.copy(climbAutoSwitch = it))
                }
            }
            Section("HEART RATE") {
                HrZonesEditor(settings, onChange)
            }
            Section("HR STRAP") {
                StrapPanel(hr, found, scanning, onScan, onPair, onForget)
            }
            Section("RECORDING") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ToggleRow("Auto-pause", "Below 2 km/h for 3 s, restarts above 4 km/h", settings.autoPause) {
                        onChange(settings.copy(autoPause = it))
                    }
                }
            }
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

/** What the elevation section shows. */
data class ElevationPanelState(
    val tiles: List<String> = emptyList(),
    val storageMb: Int = 0,
    val download: app.painmeter.TileDownload? = null,
    val hasPosition: Boolean = false,
    val hereCovered: Boolean = false,
)

@Composable
private fun ElevationPanel(state: ElevationPanelState, onDownload: () -> Unit, onDelete: () -> Unit) {
    val (line, color) = when {
        state.hereCovered -> "Ready here · ${state.tiles.size} tiles · ${state.storageMb} MB" to Pm.Green
        state.tiles.isEmpty() -> "None on the phone: gradient falls back to GPS altitude" to Pm.Amber
        else -> "${state.tiles.size} tiles · ${state.storageMb} MB · not covering your position" to Pm.Amber
    }
    Text(line, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "Download at home on Wi-Fi: the 3×3 tiles around you cover about 300 × 200 km (≈ 100 MB).",
        color = Pm.Muted, fontSize = 13.sp,
    )
    state.download?.let { d ->
        val text = if (d.running) "Downloading ${d.done} / ${d.total}…"
        else if (d.failed.isNotEmpty()) "Done, ${d.failed.size} failed (${d.failed.joinToString()}): try again"
        else "Done"
        Text(text, color = if (d.failed.isNotEmpty()) Pm.Amber else Pm.Muted, fontSize = 14.sp)
    }
    Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigButton(
            if (state.hasPosition) "DOWNLOAD AROUND ME" else "WAITING FOR GPS",
            Modifier.weight(1.6f),
            if (state.hasPosition) Pm.Blue else Pm.Surface,
            if (state.hasPosition) Color.White else Pm.Muted,
            onClick = { if (state.hasPosition && state.download?.running != true) onDownload() },
        )
        if (state.tiles.isNotEmpty()) BigButton("DELETE", Modifier.weight(1f), Pm.Surface, Pm.Text, onClick = onDelete)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Pm.Text, fontSize = 16.sp)
            Text(subtitle, color = Pm.Muted, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Pm.Blue))
    }
}

/** A number field that saves as soon as the text is a valid value inside [range]. */
@Composable
private fun DecimalField(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    modifier: Modifier,
    decimals: Int = 1,
    onValue: (Double) -> Unit,
) {
    fun show(v: Double) = if (decimals == 0) Math.round(v).toString() else java.math.BigDecimal(v).setScale(decimals, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    var text by remember { mutableStateOf(show(value)) }
    // Follow outside changes, but never rewrite what the rider is typing ("0." stays "0.").
    androidx.compose.runtime.LaunchedEffect(value) {
        if (text.replace(',', '.').toDoubleOrNull() != value) text = show(value)
    }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it.isDigit() || it == '.' || it == ',' }.take(8)
            text.replace(',', '.').toDoubleOrNull()?.takeIf { it in range }?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        isError = parsed == null || parsed !in range,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimals == 0) KeyboardType.Number else KeyboardType.Decimal),
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Pm.Text, unfocusedTextColor = Pm.Text,
            focusedBorderColor = Pm.Power, unfocusedBorderColor = Pm.Border,
            focusedLabelColor = Pm.Power, unfocusedLabelColor = Pm.Muted, cursorColor = Pm.Power,
        ),
    )
}

/** Route shown in Parameters and on Home. */
data class RouteInfo(val name: String, val summary: String)
