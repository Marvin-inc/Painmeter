package app.painmeter.ui

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.painmeter.HrState
import app.painmeter.RideController
import app.painmeter.core.RideSnapshot
import app.painmeter.core.RideStatus
import app.painmeter.core.run.Sport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The Data page (SPEC §9.2). Power and gradient arrive in phase 3. */
@Composable
fun RideScreen(
    snap: RideSnapshot,
    hr: HrState,
    onLap: () -> Unit,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onDraft: (Boolean) -> Unit = {},
    draftReminder: Boolean = true,
    nav: RideController.Nav? = null,
    track: RideController.Track = RideController.Track(emptyList(), null, null),
    maps: List<java.io.File> = emptyList(),
    initialPage: Int = 0,
    climb: RideController.ClimbUi = RideController.ClimbUi(),
    climbAutoSwitch: Boolean = true,
    zones: app.painmeter.core.HrZones? = null,
    /** Set while riding a coast-down test: the test page replaces the ride pages. */
    calibrationRuns: RideController.CalibRuns? = null,
    theme: app.painmeter.MapThemeFile? = null,
) {
    val run = snap.sport == Sport.RUN
    val context = LocalContext.current
    val drafting = snap.power.drafting
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var lapFlash by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        RideController.laps.collect { n ->
            vibrate(context)
            lapFlash = n
        }
    }
    LaunchedEffect(lapFlash) {
        if (lapFlash != null) { delay(2000); lapFlash = null }
    }

    // SPEC §6: a reminder after 20 min On a wheel, in case the rider forgot to switch back.
    var stillOnWheel by remember { mutableStateOf(false) }
    LaunchedEffect(drafting, draftReminder) {
        stillOnWheel = false
        if (drafting && draftReminder) {
            delay(20 * 60_000L)
            vibrate(context); delay(300); vibrate(context)
            stillOnWheel = true
            delay(5000)
            stillOnWheel = false
        }
    }

    val battery = remember(snap.rideTimeMs / 60_000) {
        context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 } // Some phones report Int.MIN_VALUE when they cannot tell.
    }
    val clock = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date())
    val paused = snap.status == RideStatus.PAUSED
    // Full-screen map you can move, offered while paused; closes by itself when the ride goes on.
    var fullMap by remember { mutableStateOf(false) }
    LaunchedEffect(paused) { if (!paused) fullMap = false }

    Box(Modifier.fillMaxSize().background(Pm.Background)) {
        Column(Modifier.fillMaxSize()) {
            StatusBar(
                listOf(
                    (if (paused) "❚❚ PAUSED" else "● REC") to (if (paused) Pm.Amber else Pm.Rec),
                    (snap.gpsAccuracyM?.let { "GPS ${it.toInt()} m" } ?: "GPS --") to
                        (if (snap.gpsAccuracyM == null) Pm.Rec else Pm.Muted),
                    hrStatus(hr),
                    (battery?.let { "$it %" } ?: "") to Pm.Muted,
                )
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Pm.Divider))
            if (drafting) {
                Box(
                    Modifier.fillMaxWidth().height(34.dp).background(Pm.Amber),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("ON A WHEEL · power uncertain", color = Color(0xFF111111), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            if (paused) {
                Box(
                    Modifier.fillMaxWidth().height(34.dp).background(Pm.SurfaceAlt),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (!snap.autoPaused) "PAUSED" else if (run) "AUTO-PAUSED · starts again when you move" else "AUTO-PAUSED · starts again when you ride",
                        color = Pm.Amber, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    )
                }
            }

            // Swipe between pages (SPEC §9.1): Data, Climb, Lap, Map.
            // The pages go round: swiping on from the last page comes back to the first, both ways.
            // The pager has a very large number of pages; each shows page % PAGES.
            val pager = rememberPagerState(initialPage = LOOP_START + initialPage) { LOOP_PAGES }
            // SPEC §7.4: show the Climb page when a climb starts, go back 10 s after the top.
            LaunchedEffect(climbAutoSwitch) {
                if (!climbAutoSwitch) return@LaunchedEffect
                var before: Int? = null
                RideController.climbEvents.collect { e ->
                    when (e) {
                        RideController.ClimbEvent.STARTED -> if (pager.currentPage % PAGES != CLIMB_PAGE) {
                            before = pager.currentPage
                            pager.animateScrollToPage(nearest(pager.currentPage, CLIMB_PAGE))
                        }
                        RideController.ClimbEvent.FINISHED -> launch {
                            delay(10_000)
                            val back = before
                            if (back != null && pager.currentPage % PAGES == CLIMB_PAGE) {
                                pager.animateScrollToPage(nearest(pager.currentPage, back % PAGES))
                            }
                            before = null
                        }
                    }
                }
            }
            if (calibrationRuns != null) {
                CalibrationRidePage(snap, calibrationRuns, onLap, Modifier.weight(1f).padding(10.dp))
            } else HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                when (page % PAGES) {
                    0 -> if (run) RunDataPage(snap, clock, Modifier.fillMaxSize().padding(10.dp)) else Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val p10 = snap.power.powerShort
                DataField(
                    "POWER ${snap.power.windowS} s · W", p10?.let { Math.round(it).toString() } ?: "--", Modifier.fillMaxWidth().weight(1.35f),
                    valueSize = 150.sp,
                    valueColor = when {
                        p10 == null -> Pm.Disabled
                        snap.power.reliable -> Pm.Power
                        else -> Pm.Uncertain
                    },
                )
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataField("SPEED · km/h", Format.speedKmh(snap.speedMps), Modifier.weight(1f).fillMaxHeight(), valueSize = 88.sp)
                    HrField(snap.hrBpm, snap.hrZone, Modifier.weight(1f).fillMaxHeight(), valueSize = 88.sp)
                }
                Row(Modifier.fillMaxWidth().weight(0.62f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataField("CLOCK", clock, Modifier.weight(1f).fillMaxHeight())
                    val grade = snap.power.gradePercent
                    DataField(
                        "GRADE · %", grade?.let { Format.grade(it) } ?: "--", Modifier.weight(1f).fillMaxHeight(),
                        valueColor = grade?.let { Pm.gradeColor(it) } ?: Pm.Disabled,
                    )
                    DataField("AVG km/h", Format.speedKmh(snap.avgSpeedMps), Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.fillMaxWidth().weight(0.62f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataField("RIDE TIME", Format.duration(snap.rideTimeMs), Modifier.weight(1f).fillMaxHeight(), valueSize = 40.sp)
                    DataField("DIST · km", Format.distanceKm(snap.distanceM), Modifier.weight(1f).fillMaxHeight())
                    DataField("ELEV GAIN · m", Format.metres(snap.elevationGainM), Modifier.weight(1f).fillMaxHeight())
                }
                                }
                    CLIMB_PAGE -> ClimbPage(snap, climb, Modifier.fillMaxSize().padding(10.dp))
                    LAP_PAGE -> LapPage(snap, zones, onLap, Modifier.fillMaxSize().padding(10.dp))
                    else -> MapPage(
                        snap, nav, track, maps, Modifier.fillMaxSize().padding(10.dp), theme,
                        onFullMap = if (paused) ({ fullMap = true }) else null,
                    )
                }
            }
            if (calibrationRuns == null) PageDots(pager.currentPage % PAGES, PAGES)

            if (calibrationRuns != null) {
                // Coast-down test: only pause and finish; runs are marked with volume − or END RUN.
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 12.dp).height(60.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BigButton(if (paused) "RESUME" else "PAUSE", Modifier.weight(1f), Pm.Surface, Pm.Text, onClick = onTogglePause)
                    BigButton("HOLD TO FINISH\nTHE TEST", Modifier.weight(1.4f), Pm.Surface, Pm.Rec, onLongClick = onStop)
                }
            } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(56.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BigButton(if (paused) "RESUME" else "PAUSE", Modifier.weight(1f), Pm.Surface, Pm.Text, onClick = onTogglePause)
                BigButton("LAP ${snap.lapNumber}", Modifier.weight(1.2f), Pm.Blue, Color.White, onClick = onLap)
                BigButton("HOLD\nTO STOP", Modifier.weight(1f), Pm.Surface, Pm.Rec, onLongClick = onStop)
            }
            // Running has no drafting: the buttons sit lower, and are easier to hit on the move.
            if (run) androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp)) else Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 12.dp).height(60.dp),
            ) {
                BigButton(
                    if (drafting) "ON A WHEEL · tap for SOLO" else "SOLO · tap when on a wheel",
                    Modifier.weight(1f),
                    if (drafting) Pm.Amber else Pm.Surface,
                    if (drafting) Color(0xFF111111) else Pm.Text,
                    onClick = { onDraft(!drafting) },
                )
            }
            }
        }

        if (stillOnWheel) {
            Box(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(20.dp)).background(Pm.Amber)
                    .padding(horizontal = 28.dp, vertical = 20.dp)
            ) {
                Text("STILL ON A WHEEL?", color = Color(0xFF111111), fontWeight = FontWeight.Bold, fontSize = 30.sp)
            }
        }
        if (fullMap) FullScreenMap(maps, nav, track, onClose = { fullMap = false }, theme = theme, sport = snap.sport)
        lapFlash?.let { n ->
            Box(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(20.dp)).background(Pm.Blue)
                    .padding(horizontal = 40.dp, vertical = 20.dp)
            ) {
                Text("LAP $n", color = Color.White, fontFamily = Pm.Numbers, fontWeight = FontWeight.Bold, fontSize = 72.sp)
            }
        }
    }
}

/**
 * The Data page when running (SPEC §16.5): pace big, then GAP and HR, then the clock, gradient
 * and cadence, then time, distance and climb.
 */
@Composable
private fun RunDataPage(snap: RideSnapshot, clock: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DataField(
            "PACE · min/km", Format.pace(snap.paceSpeedMps), Modifier.fillMaxWidth().weight(1.35f),
            valueSize = 150.sp, valueColor = if (snap.paceSpeedMps == null) Pm.Disabled else Pm.Power,
        )
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataField("GAP · grade-adjusted", Format.pace(snap.gapSpeedMps), Modifier.weight(1f).fillMaxHeight(), valueSize = 88.sp)
            HrField(snap.hrBpm, snap.hrZone, Modifier.weight(1f).fillMaxHeight(), valueSize = 88.sp)
        }
        Row(Modifier.fillMaxWidth().weight(0.62f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataField("CLOCK", clock, Modifier.weight(1f).fillMaxHeight())
            val grade = snap.power.gradePercent
            DataField(
                "GRADE · %", grade?.let { Format.grade(it) } ?: "--", Modifier.weight(1f).fillMaxHeight(),
                valueColor = grade?.let { Pm.gradeColor(it) } ?: Pm.Disabled,
            )
            DataField("CADENCE · spm", Format.cadence(snap.cadenceSpm), Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().weight(0.62f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataField("TIME", Format.duration(snap.rideTimeMs), Modifier.weight(1f).fillMaxHeight(), valueSize = 40.sp)
            DataField("DIST · km", Format.distanceKm(snap.distanceM), Modifier.weight(1f).fillMaxHeight())
            DataField("D+ · m", Format.metres(snap.elevationGainM), Modifier.weight(1f).fillMaxHeight())
        }
    }
}

private const val PAGES = 4
const val CLIMB_PAGE = 1
private const val LOOP_PAGES = 4_000
private const val LOOP_START = LOOP_PAGES / 2 // a multiple of PAGES, so it shows the Data page

/** The page closest to [from] that shows [target] (0..PAGES-1), going whichever way is shorter. */
private fun nearest(from: Int, target: Int): Int {
    var d = (target - from % PAGES) % PAGES
    if (d > PAGES / 2) d -= PAGES
    if (d < -PAGES / 2) d += PAGES
    return from + d
}
const val LAP_PAGE = 2
const val MAP_PAGE = 3

@Composable
private fun PageDots(current: Int, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            Box(
                Modifier.padding(horizontal = 4.dp).height(6.dp).width(if (i == current) 18.dp else 6.dp)
                    .clip(RoundedCornerShape(3.dp)).background(if (i == current) Pm.Text else Color(0xFF4A5260))
            )
        }
    }
}

/** The combined HR field: "152 Z3", in the zone colour, with a zone bar along the bottom. */
@Composable
fun HrField(bpm: Int?, zone: Int?, modifier: Modifier, valueSize: androidx.compose.ui.unit.TextUnit, label: String = "HR · bpm · zone") {
    if (bpm == null) {
        DataField(label, "--", modifier, valueSize = valueSize, valueColor = Pm.Disabled)
    } else {
        DataField(label, bpm.toString(), modifier, valueSize = valueSize, suffix = zone?.let { "Z$it" }, accent = Pm.zoneColor(zone))
    }
}

fun hrStatus(hr: HrState): Pair<String, Color> = when (hr) {
    is HrState.Connected -> (if (hr.bpm != null) "HR ✓" else "HR no contact") to (if (hr.bpm != null) Pm.Muted else Pm.Amber)
    is HrState.Connecting -> "HR …" to Pm.Amber
    HrState.NotPaired -> "HR --" to Pm.Disabled
    HrState.BluetoothOff -> "BT off" to Pm.Amber
    HrState.NoPermission -> "HR ✕" to Pm.Amber
}

/** A large touch target. With [onLongClick] only, a short tap does nothing (stop needs a hold). */
@Composable
fun BigButton(
    label: String,
    modifier: Modifier = Modifier,
    background: Color,
    textColor: Color,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(background)
            .border(2.dp, if (background == Pm.Surface) Pm.Border else background, shape)
            .semantics {
                role = Role.Button
                onClick?.let { f -> onClick(label = label) { f(); true } }
                onLongClick?.let { f -> onLongClick(label = label) { f(); true } }
            }
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onTap = { onClick?.invoke() },
                    onLongPress = { onLongClick?.invoke() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, fontSize = 17.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

private fun vibrate(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    vibrator?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
}
