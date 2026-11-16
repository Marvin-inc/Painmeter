package app.painmeter

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.painmeter.core.LapSummary
import app.painmeter.core.RideSnapshot
import app.painmeter.core.RideStatus
import app.painmeter.ui.HomeScreen
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

    private val lap3 = LapSummary(2, 0, 0, 240_000, 2010.0, 12.0, false)
    private val snap = RideSnapshot(
        status = RideStatus.RECORDING, autoPaused = false, startTimeMs = 0, rideTimeMs = 5_283_000,
        distanceM = 42_700.0, speedMps = 31.4 / 3.6, avgSpeedMps = 28.9 / 3.6, maxSpeedMps = 54.2 / 3.6,
        elevationGainM = 612.0, lapNumber = 4, lapTimeMs = 222_000, lapDistanceM = 1900.0,
        lastLap = lap3, gpsAccuracyM = 4.0, gpsAgeMs = 400,
    )

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    @Test fun ride() {
        compose.setContent { RideScreen(snap, {}, {}, {}) }
        shot("ride")
    }

    @Test fun rideAutoPaused() {
        compose.setContent { RideScreen(snap.copy(status = RideStatus.PAUSED, autoPaused = true, speedMps = 0.0), {}, {}, {}) }
        shot("ride-paused")
    }

    @Test fun home() {
        compose.setContent { HomeScreen(true, 4.0, File("20261116-084512"), {}, {}, {}, {}) }
        shot("home")
    }

    @Test fun summary() {
        val laps = listOf(LapSummary(0, 0, 0, 1_800_000, 15_000.0, 200.0, false), lap3,
            LapSummary(3, 0, 0, 3_243_000, 25_690.0, 400.0, true))
        compose.setContent { SummaryScreen(FinishedRide(File("x"), snap.copy(status = RideStatus.FINISHED), laps, File("x.fit")), {}, {}) }
        shot("summary")
    }
}
