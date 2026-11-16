package app.painmeter

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.painmeter.core.HrZones
import app.painmeter.core.LapSummary
import app.painmeter.core.RideSnapshot
import app.painmeter.core.RideStatus
import app.painmeter.ui.HomeScreen
import app.painmeter.ui.ParametersScreen
import app.painmeter.ui.RideScreen
import app.painmeter.ui.SummaryScreen
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders each screen on the JVM to a PNG, to compare with the mockups. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xxhdpi", sdk = [34])
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val zones = HrZones(listOf(120, 140, 158, 172))
    private val lap3 = LapSummary(2, 0, 0, 240_000, 2010.0, 12.0, false, avgHr = 158, maxHr = 166)
    private val snap = RideSnapshot(
        status = RideStatus.RECORDING, autoPaused = false, startTimeMs = 0, rideTimeMs = 5_283_000,
        distanceM = 42_700.0, speedMps = 31.4 / 3.6, avgSpeedMps = 28.9 / 3.6, maxSpeedMps = 54.2 / 3.6,
        elevationGainM = 612.0, lapNumber = 4, lapTimeMs = 222_000, lapDistanceM = 1900.0,
        lastLap = lap3, gpsAccuracyM = 4.0, gpsAgeMs = 400,
        hrBpm = 152, hrZone = 3, avgHr = 146, maxHr = 178, lapAvgHr = 150,
        hrZoneMs = listOf(600_000L, 1_500_000L, 2_100_000L, 900_000L, 183_000L),
    )

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    @Test fun ride() {
        compose.setContent { RideScreen(snap, HrState.Connected("Polar H10", 152, true), {}, {}, {}) }
        shot("ride")
    }

    @Test fun rideAutoPaused() {
        compose.setContent { RideScreen(snap.copy(status = RideStatus.PAUSED, autoPaused = true, speedMps = 0.0, hrBpm = null, hrZone = null), HrState.Connecting("Polar H10"), {}, {}, {}) }
        shot("ride-paused")
    }

    @Test fun home() {
        compose.setContent { HomeScreen(true, 4.0, HrState.Connected("Polar H10", 64, true), File("20261116-084512"), {}, {}, {}, {}, {}) }
        shot("home")
    }

    @Test fun parameters() {
        compose.setContent {
            ParametersScreen(
                UserSettings(maxHr = 186, hrZoneBounds = listOf(120, 140, 158, 172), hrDeviceAddress = "x", hrDeviceName = "Polar H10"),
                HrState.Connected("Polar H10", 64, true),
                listOf(FoundStrap("a", "Polar H10 A1B2", -48), FoundStrap("b", "Wahoo TICKR", -71)),
                scanning = true, onChange = {}, onScan = {}, onPair = {}, onForget = {}, onBack = {},
            )
        }
        shot("parameters")
    }

    @Test fun summary() {
        val laps = listOf(LapSummary(0, 0, 0, 1_800_000, 15_000.0, 200.0, false, avgHr = 138), lap3,
            LapSummary(3, 0, 0, 3_243_000, 25_690.0, 400.0, true, avgHr = 147))
        compose.setContent { SummaryScreen(FinishedRide(File("x"), snap.copy(status = RideStatus.FINISHED), laps, File("x.fit")), zones, {}, {}) }
        shot("summary")
    }
}
