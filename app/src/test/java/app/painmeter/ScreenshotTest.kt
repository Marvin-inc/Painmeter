package app.painmeter

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.painmeter.core.HrZones
import app.painmeter.core.LapSummary
import app.painmeter.core.RideSnapshot
import app.painmeter.core.RideStatus
import app.painmeter.core.climb.ClimbProgress
import app.painmeter.core.climb.RouteClimb
import app.painmeter.core.power.PowerState
import app.painmeter.core.calibration.CoastDown
import app.painmeter.ui.CLIMB_PAGE
import app.painmeter.ui.CalibrationIntroScreen
import app.painmeter.ui.CalibrationResultScreen
import app.painmeter.ui.HistoryScreen
import app.painmeter.ui.LAP_PAGE
import app.painmeter.ui.MAP_PAGE
import app.painmeter.core.route.NavState
import app.painmeter.core.route.Route
import app.painmeter.core.route.RoutePoint
import app.painmeter.core.route.TurnDetector
import app.painmeter.ui.ElevationPanelState
import app.painmeter.ui.HomeScreen
import app.painmeter.ui.RouteInfo
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
    private val lap3 = LapSummary(2, 0, 0, 240_000, 2010.0, 12.0, false, avgHr = 158, maxHr = 166, avgPower = 287.0)
    private val snap = RideSnapshot(
        status = RideStatus.RECORDING, autoPaused = false, startTimeMs = 0, rideTimeMs = 5_283_000,
        distanceM = 42_700.0, speedMps = 31.4 / 3.6, avgSpeedMps = 28.9 / 3.6, maxSpeedMps = 54.2 / 3.6,
        elevationGainM = 612.0, lapNumber = 4, lapTimeMs = 222_000, lapDistanceM = 1900.0,
        lastLap = lap3, gpsAccuracyM = 4.0, gpsAgeMs = 400,
        hrBpm = 152, hrZone = 3, avgHr = 146, maxHr = 178, lapAvgHr = 150,
        hrZoneMs = listOf(600_000L, 1_500_000L, 2_100_000L, 900_000L, 183_000L),
        power = PowerState(power10s = 238.0, power30s = 231.0, power1Hz = 240.0, gradePercent = 4.2, reliable = true),
        avgPower = 196.0, maxPower = 612.0, normalizedPower = 221.0, lapAvgPower = 214.0,
    )

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    @Test fun ride() {
        compose.setContent { RideScreen(snap, HrState.Connected("Polar H10", 152, true), {}, {}, {}) }
        shot("ride")
    }

    /** A route north 1 km then east 1 km, rider 750 m along it. */
    private fun navAt(alongM: Double, offsetM: Double, offRoute: Boolean): Pair<RideController.Nav, RideController.Track> {
        val pts = mutableListOf<RoutePoint>()
        var lat = 45.18; var lon = 5.72
        repeat(100) { lat += 10 / 111_195.0; pts += RoutePoint(lat, lon) }
        repeat(100) { lon += 10 / (111_195.0 * Math.cos(Math.toRadians(lat))); pts += RoutePoint(lat, lon) }
        val route = Route("Vercors loop", pts)
        val turns = TurnDetector.find(route)
        val st = NavState(alongM, offsetM, route.lengthM - alongM, offRoute, turns.first(), turns.first().distanceM - alongM)
        val p = route.pointAt(alongM)
        val track = (0..75).map { route.pointAt(it * 10.0).let { q -> q.lat to q.lon } }
        return RideController.Nav(st, route) to RideController.Track(track, 0.0, p.lat to p.lon)
    }

    @Test fun rideMapNextTurn() {
        val (nav, track) = navAt(750.0, 4.0, false)
        compose.setContent { RideScreen(snap, HrState.Connected("Polar H10", 152, true), {}, {}, {}, nav = nav, track = track, initialPage = MAP_PAGE) }
        shot("ride-map")
    }

    @Test fun rideMapOffRoute() {
        val (nav, track) = navAt(750.0, 120.0, true)
        compose.setContent { RideScreen(snap, HrState.Connected("Polar H10", 152, true), {}, {}, {}, nav = nav, track = track, initialPage = MAP_PAGE) }
        shot("ride-map-offroute")
    }

    private fun climbUi(fromRoute: Boolean, finished: Boolean = false): RideController.ClimbUi {
        val rc = RouteClimb(1, 10_000.0, 18_700.0, 400.0, 1012.0)
        // A climb averaging 7 %: gradient between 4 and 10 %, integrated every 20 m.
        fun profile(n: Int): List<Pair<Double, Double>> {
            var e = 400.0
            return (0..n).map { i -> (i * 20.0 to e).also { e += 20 * (0.07 + 0.03 * Math.sin(i / 40.0)) } }
        }
        val ridden = profile(245)
        val p = ClimbProgress(
            fromRoute = if (fromRoute) rc else null, startTimeMs = 0, startDistanceM = 10_000.0, ridden = ridden,
            elapsedMs = 1_310_000, distanceM = 4_900.0, gainM = 338.0, avgPower = 276.0, avgHr = 164, maxGradePercent = 11.2,
            finished = finished,
        )
        return if (fromRoute) RideController.ClimbUi(
            progress = p,
            routeProfile = profile(435),
            riderOnClimbM = 4_900.0, remainingM = 3_800.0, remainingGainM = 274.0, climbNumber = 2, climbCount = 3,
        ) else RideController.ClimbUi(progress = p)
    }

    @Test fun rideClimbOnRoute() {
        val climbSnap = snap.copy(speedMps = 13.6 / 3.6, hrBpm = 168, hrZone = 4,
            power = snap.power.copy(power10s = 291.0, gradePercent = 7.8))
        compose.setContent { RideScreen(climbSnap, HrState.Connected("Polar H10", 168, true), {}, {}, {}, initialPage = CLIMB_PAGE, climb = climbUi(true)) }
        shot("ride-climb")
    }

    @Test fun rideClimbLive() {
        compose.setContent { RideScreen(snap, HrState.Connected("Polar H10", 152, true), {}, {}, {}, initialPage = CLIMB_PAGE, climb = climbUi(false)) }
        shot("ride-climb-live")
    }

    @Test fun rideNoClimb() {
        val next = RouteClimb(2, 30_000.0, 36_100.0, 500.0, 830.0)
        compose.setContent {
            RideScreen(snap, HrState.Connected("Polar H10", 152, true), {}, {}, {}, initialPage = CLIMB_PAGE,
                climb = RideController.ClimbUi(next = next, distanceToNextM = 4_200.0, climbCount = 3))
        }
        shot("ride-climb-next")
    }

    @Test fun ridePageLap() {
        val s = snap.copy(lapNumber = 4, lapTimeMs = 222_000, lapAvgPower = 298.0, lapAvgHr = 164, ftpW = 280,
            power = snap.power.copy(power10s = 305.0), speedMps = 36.2 / 3.6, hrBpm = 171, hrZone = 4,
            lastLap = LapSummary(2, 0, 0, 240_000, 2010.0, 12.0, false, avgHr = 158, avgPower = 291.0))
        compose.setContent { RideScreen(s, HrState.Connected("Polar H10", 171, true), {}, {}, {}, initialPage = LAP_PAGE, zones = zones) }
        shot("ride-lap")
    }

    @Test fun calibrationIntro() {
        compose.setContent { CalibrationIntroScreen(0.312, 0.0042, {}, {}, {}) }
        shot("calibration-intro")
    }

    @Test fun calibrationTestRide() {
        compose.setContent {
            RideScreen(snap.copy(speedMps = 27.3 / 3.6), HrState.Connected("Polar H10", 140, true), {}, {}, {},
                calibrationRuns = RideController.CalibRuns(out = 3, back = 2, coasting = true))
        }
        shot("calibration-test")
    }

    @Test fun calibrationResult() {
        // Windows on the line F = 26 + 0.30·q, both directions, a few left out.
        val wins = (0 until 40).map { i ->
            val q = 8.0 + i * 1.6
            val f = 26 + 0.30 * q + (if (i % 3 == 0) 1.5 else -1.0)
            CoastDown.Window(i / 5, q, f, used = i % 13 != 7, q0 = q, rho = 1.2, vm = 8.0, fi = f, dir = if (i % 2 == 0) 1 else -1)
                .apply { px = q; py = f }
        }
        val segs = (0 until 12).map { CoastDown.Segment(it * 10, it * 10 + 9).apply { dir = if (it % 2 == 0) 1 else -1 } }
        val fit = CoastDown.Fit(0.304, 0.0045, 1.1, 37, 0.016, 0.0005, 0.94, wind = -1.8, slope = 0.004, res = emptyList())
        val r = CoastDown.Result(segs, false, "laps", true, true, 20.0, wins, fit, "free", "paired", null, null)
        val c = RideController.Calibration(File("x"), r, listOf("Only 6 runs one way and 6 the other: do 5–6 out-and-back pairs."), false)
        compose.setContent { CalibrationResultScreen(c, 0.312, 0.0042, {}, {}, {}) }
        shot("calibration-result")
    }

    @Test fun history() {
        val rides = listOf(
            File("20261116-084512") to RideStore.Summary(1_763_282_712_000, 5_283_000, 82_400.0, 1450.0, 196.0, 221.0, 146),
            File("20261114-171002") to RideStore.Summary(1_763_140_202_000, 2_710_000, 21_300.0, 160.0, 171.0, 180.0, 138),
        )
        compose.setContent { HistoryScreen(rides, false, {}, {}, {}, {}, {}) }
        shot("history")
    }

    @Test fun rideOnAWheel() {
        compose.setContent {
            RideScreen(snap.copy(power = snap.power.copy(drafting = true, reliable = false, power10s = 171.0, gradePercent = -0.4)),
                HrState.Connected("Polar H10", 152, true), {}, {}, {})
        }
        shot("ride-wheel")
    }

    @Test fun rideAutoPaused() {
        compose.setContent { RideScreen(snap.copy(status = RideStatus.PAUSED, autoPaused = true, speedMps = 0.0, hrBpm = null, hrZone = null), HrState.Connecting("Polar H10"), {}, {}, {}) }
        shot("ride-paused")
    }

    @Test fun home() {
        compose.setContent { HomeScreen(true, 4.0, HrState.Connected("Polar H10", 64, true), true, RouteInfo("Vercors loop", "82.4 km · 1450 m"), File("20261116-084512"), {}, {}, {}, {}, {}) }
        shot("home")
    }

    @Test fun parameters() {
        compose.setContent {
            ParametersScreen(
                UserSettings(maxHr = 186, hrZoneBounds = listOf(120, 140, 158, 172), hrDeviceAddress = "x", hrDeviceName = "Polar H10"),
                HrState.Connected("Polar H10", 64, true),
                listOf(FoundStrap("a", "Polar H10 A1B2", -48), FoundStrap("b", "Wahoo TICKR", -71)),
                scanning = true, onChange = {}, onScan = {}, onPair = {}, onForget = {}, onBack = {},
                elevation = ElevationPanelState(listOf("N44E005", "N45E005"), 24, TileDownload(1, 7), hasPosition = true, hereCovered = true),
            )
        }
        shot("parameters")
    }

    @Test fun summary() {
        val laps = listOf(LapSummary(0, 0, 0, 1_800_000, 15_000.0, 200.0, false, avgHr = 138, avgPower = 182.0), lap3,
            LapSummary(3, 0, 0, 3_243_000, 25_690.0, 400.0, true, avgHr = 147, avgPower = 201.0))
        compose.setContent { SummaryScreen(FinishedRide(File("x"), snap.copy(status = RideStatus.FINISHED), laps, File("x.fit"),
            climbs = listOf(climbUi(false, finished = true).progress!!, climbUi(true, finished = true).progress!!.copy(distanceM = 8_700.0, gainM = 612.0, elapsedMs = 2_640_000))), zones, {}, {}) }
        shot("summary")
    }
}
