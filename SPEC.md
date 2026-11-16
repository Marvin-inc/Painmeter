# Painmeter — Specification v0.1

Android bike computer that shows **estimated live power without a power meter**, from phone GPS,
offline elevation data, weather and user-supplied CdA / Crr.

Status: **draft for review**. Items marked **[OPEN]** need a decision. Items marked **[v2]** are
explicitly out of the first version.

---

## 1. Scope

| | |
|---|---|
| Users | One rider (personal use), road cycling only |
| Platform | Android 10+ (API 29), installed by APK (no Play Store) |
| Language / units | English, metric (km/h, km, m, W, bpm, °C) |
| Sensors | Phone GPS; Bluetooth LE heart-rate strap. No barometer assumed. No ANT+. |
| Network | Optional. Needed only for weather (wind, air density) and for downloading maps / elevation tiles at home. A ride works fully offline with a fallback ρ and zero wind. |

**Out of v1:** route calculation / rerouting, power meter support, speed/cadence sensors, ANT+,
iOS, multiple rider profiles. The Strava connection (§14) comes in phase 8, after the core app.

---

## 2. Architecture

```
UI (Jetpack Compose)  ── ride pages · parameters · calibration · summary
      ▲  StateFlow<RideState>, 1 Hz
RideService (foreground service, keeps running with screen off)
  ├─ GpsSource          FusedLocationProvider, 1 Hz, high accuracy, Doppler speed
  ├─ HrSource           BLE Heart Rate service 0x180D / 0x2A37, auto-reconnect
  ├─ ElevationProvider  offline Copernicus GLO-30 tiles; route profile when on route
  ├─ WeatherProvider    Open-Meteo forecast, refreshed every 20 min / 10 km
  ├─ PowerEngine        port of Power-calcualtor physics, causal (live) filters
  ├─ ClimbDetector      route-based + live detection
  ├─ LapTracker         volume-key / on-screen lap button
  ├─ Navigator          GPX route following, off-route, next turn (no routing)
  └─ Recorder           1 Hz samples → append-only ride journal (crash-safe) → FIT on stop
```

- Language: Kotlin. UI: Jetpack Compose. Storage: one folder per ride with an append-only journal
  file (one line per sample or event, flushed every second) and the exported FIT. No database in
  v1: the journal is simpler and survives a crash by construction.
- Ride logic (session, auto-pause, laps, elevation gain, journal, FIT export), `PowerEngine` and the
  calibration code live in a pure-Kotlin module (`:core`) with no Android dependencies, so they are
  unit-tested on the JVM. FIT files are written with the Garmin FIT SDK (`com.garmin:fit`).
- Maps: Mapsforge vector maps (offline `.map` files), rendered with `mapsforge-map-android`.

---

## 3. Ride lifecycle

1. **Home** (ride not started): GPS status, HR status, route loaded (name, length, elevation gain),
   draft state, buttons **Start**, **Parameters**, **Calibration**, **History**.
2. **Start** → recording begins, ride pages are shown.
3. **Auto-pause** when speed < 2 km/h for 3 s; auto-resume above 4 km/h. Timer stops while paused.
   Manual pause / resume from a long-press on the status bar. Auto-pause can be turned off.
4. **Stop** → confirm dialog (long-press, to avoid accidental stops) → **Summary**.
5. **Summary**: time, distance, elevation gain, avg speed, avg / NP estimated power, avg / max HR,
   time in HR zones, laps table, climbs table, FIT export (share sheet / Download folder).
6. **History**: list of past rides, open summary, re-export FIT, delete.

**Crash safety:** every 1 Hz sample is appended to the ride journal immediately. If the app or phone dies,
the next launch offers to resume or finish the interrupted ride.

---

## 4. Elevation and gradient (no barometer)

Priority of elevation sources:
1. **Route profile**, when a route is loaded and the rider is on it (≤ 50 m off): the route is
   sampled every 10 m against the elevation tiles when it is loaded, smoothed over 100 m
   (centred, allowed since the whole profile is known). Gradient is read **at the rider's
   distance along the route**, so it has no lag.
2. **Elevation tiles under the track**: the elevation at each 1 Hz position, smoothed over the
   last 100 m travelled. Gradient = slope over that window (lags by about 50 m).
3. **GPS altitude**: recorded, used only as a fallback when no tile covers the area.

- Elevation tiles: AWS Terrain Tiles open dataset, "skadi" layout: 1°×1° tiles in the SRTM HGT
  format at 1 arc-second (~30 m), gzip, about 10–25 MB each
  (`https://s3.amazonaws.com/elevation-tiles-prod/skadi/N45/N45E005.hgt.gz`). Downloaded from
  Parameters ("around me" = the 3×3 tiles around the current position). Chosen over Copernicus
  GeoTIFF because HGT needs no TIFF decoder.
- **Elevation gain**: from the chosen elevation, counting a rise only once it exceeds 4 m since the
  last low point (hysteresis), to reject noise.
- Recorded every second: GPS altitude, tile elevation, gradient used, source used.

---

## 5. Power model

Port of `processRide()` from
[Marvin-inc/Power-calcualtor](https://github.com/Marvin-inc/Power-calcualtor), same physics:

```
F = m·g·sin θ  +  m·g·cos θ·Crr  +  ½·ρ·CdA·k_draft·v_air·|v_air|  +  m_eff·a
P = F · v / η
v_air = v + w_head        w_head = k_wind · wind_10m · cos(wind_from − bearing)
```

| Input | Live source |
|---|---|
| v | GPS Doppler speed, light causal filter (Kalman, 1 Hz) |
| θ | Gradient from §4 |
| a | From filtered speed (see energy method below) |
| ρ | Open-Meteo temperature, pressure, humidity → same `airDensity()` as the web tool; fallback = Parameters value |
| wind | Open-Meteo `wind_speed_10m` / `wind_direction_10m`, current hour |
| m | rider + bike weight |
| m_eff | m + 1.5 kg (rotating wheels), same value as the calibration (web tool aligned, branch `claude/zen-pascal-vwu1j5`) |
| CdA, Crr, η | Parameters |
| k_draft | 1.0 when **Solo**, 0.65 (editable) when **On a wheel** |
| k_wind | 0.6 by default (editable). Wind at rider height (~1.2 m) is weaker than the 10 m forecast value; a log wind profile over open country gives about 0.6. Web tool updated to the same factor. |

### 5.1 Windowed power by energy balance
Displayed averages are computed over the window as energy / time, not as a mean of noisy
instantaneous values:

```
P̄(T) = [ ½·m_eff·(v_end² − v_start²) + Σ (F_grav + F_roll + F_aero)·v·dt ] / (T·η)
```

The kinetic term only uses the speeds at both ends of the window, so speed noise is not
differentiated. Values below 0 are shown as 0.

### 5.2 Rates
- Computation and recording: **1 Hz**. Screen refresh: **1 Hz**.
- Displayed: **10 s power** (main), **30 s power** (optional field), lap / climb **averages**.
- No 3 s or instantaneous power on screen (too noisy: ±30–40 % on the flat).
- FIT `power` field: the 1 Hz value with a 5 s trailing filter (so Strava charts look sane).
  The precise analysis is done after the ride in the web tool.

### 5.3 Quality flag
Power is shown **greyed out** when any of these is true:
- GPS horizontal accuracy > 12 m, or no fix in the last 3 s
- On a wheel (CdA is a guess)
- No elevation tile at the position (gradient from GPS altitude)
- Speed < 5 km/h

The flag is recorded per sample.

### 5.4 Expected accuracy (to be checked against a real power meter)
| Window | Flat / rolling | Steady climb |
|---|---|---|
| 10 s | ±15–25 % | ±10–15 % |
| 30 s | ±10–20 % | ±5–10 % |
| Lap / climb > 3 min | ±10–20 % (wind bias) | ±5–10 % |

### 5.5 Derived metrics
- **NP**: 30 s rolling average, 4th-power mean (as the web tool).
- **%FTP** = power / FTP.
- **Power zones** (7, Coggan, from FTP) — recorded; not on the 6 screens in v1.

---

## 6. Drafting state

- Two states: **Solo** (default) / **On a wheel**. Toggled by a large button present on **every ride
  page** (data, climb, lap, map).
- When On a wheel: an amber banner "ON A WHEEL" across the top of every page, power greyed.
- Optional reminder vibration after 20 min On a wheel (on by default).
- Recorded per sample in the FIT file (developer field `draft_state`), and as lap-independent
  segments in the summary.

---

## 7. Climbs

### 7.1 Definition (defaults, all editable in Parameters → Climbs)
- Average gradient ≥ **3 %**, length ≥ **500 m**, elevation gain ≥ **25 m**.
- Two climbs separated by < **300 m** of flat or descent are merged.

### 7.2 With a route
- On route load: climbs are found on the route profile with the rules above, and listed in Home and
  on the Climb page ("Next climb in 4.2 km — 6.1 km at 5.4 %").
- Start and top are known in advance. The Climb page shows the full climb profile, the rider's
  position on it, distance and elevation **remaining** to the top, and average gradient remaining.

### 7.3 Without a route (or off the route)
- **Live detection**: a climb is **confirmed** when the gradient has stayed ≥ 3 % over the last
  **150 m** with ≥ **10 m** gained.
- On confirmation, the climb **start is moved back** to the point where the gradient first went
  above 3 % (from the recorded samples). All climb averages (power, VAM, time, speed) are computed
  from that start.
- **End**: 200 m below 3 % or a 10 m descent from the highest point. The top is set back to the
  highest point.
- The profile shows only what has been climbed so far. No remaining distance (unknown).

### 7.4 Auto-switch
- When a climb starts (route: at the start point; live: at confirmation), the screen switches to the
  Climb page. At the top, a climb summary stays for 10 s, then the previous page returns.
- Setting: on (default) / off.

### 7.5 Climb record
Each climb is stored with: start / end distance, length, gain, avg gradient, max gradient
(100 m), time, avg speed, VAM, avg estimated power, avg HR. Listed in the Summary.

---

## 8. Laps

- **Lap button**: phone **volume-down key** (works by feel, with gloves) + on-screen button on the
  Lap page. Volume-up is left alone. Volume-key handling is only active while a ride is recording.
- A lap press gives a short vibration and a "LAP 4" toast.
- Laps are recorded as FIT lap messages (also used by the calibration).
- Auto-lap: **[v2]**.

---

## 9. Screens

### 9.1 Common to all ride pages
- **Swipe left / right** between ride pages. Order: Data → Climb → Lap → Map (editable in
  Parameters).
- **Status bar** (top, thin): recording state (● REC / ❚❚ PAUSED), GPS accuracy, HR connected,
  phone battery %, weather status (live / fallback).
- **Draft toggle** (bottom, large).
- Dark theme by default, high contrast, large figures. Light theme option for strong sun.
- Screen kept on while recording. Brightness: system.
- **HR field (used everywhere)**: one field showing bpm and zone together ("171 Z4", zone in a
  smaller size), figures in the zone colour and a zone-coloured bar along the bottom edge. There is
  no separate HR-zone field. Same pattern for average HR ("164 Z4").

### 9.2 Data
3 × 3 grid, power and speed in larger cells:

| | | |
|---|---|---|
| **Power 10 s** (W), full width | | |
| **Speed** (km/h) | **HR + zone** | |
| Clock | Gradient (%) | Avg speed |
| Ride time | Distance | Elevation gain |

Merging HR and HR zone into one field frees a cell, used for the clock; current gradient is added.
Optional extra fields to swap in: 30 s power, wind (head/tail, km/h), NP.

### 9.3 Climb
- Top half: **profile** coloured by gradient (5 bands: <3, 3–6, 6–9, 9–12, >12 %), rider marker.
  With a route: whole climb. Without: climbed so far.
- Fields: **Power 10 s** · **Avg power since climb start** · **Gradient** · **Speed** ·
  **VAM** (climb average, m/h) · **HR + zone**.
- With a route only: **distance remaining**, **elevation remaining**, avg gradient remaining.
- When not climbing: next climb on the route (distance to it, length, gradient), or "No climb".

### 9.4 Lap
- Large **LAP** button.
- Current lap: **Lap time** · **Power 10 s** · **%FTP (10 s)** · **Speed** · **HR + zone** ·
  **Avg power** · **Avg %FTP** · **Avg HR + zone** (zone of the average HR).
- Strip at the bottom: **last lap** — time, avg power, avg %FTP, avg HR + zone.

### 9.5 Map
- Offline Mapsforge map, heading-up, zoom kept between rides.
- Route line if a route is loaded; ridden track in another colour.
- With a route: **distance to destination**, **next-turn arrow + distance**, **off-route alert**.
- Two small fields: speed, power 10 s.

**Next-turn detection** (no routing engine): when the route is loaded, points where the route
heading changes by > 35° within 30 m are marked as turns (left / right / sharp / U-turn).
Arrow shown from 300 m before, vibration at 100 m. Limitation: slight forks are not detected.

**Off-route**: > 50 m from the route for > 10 s → vibration + red banner "OFF ROUTE — 120 m".
Banner clears when back within 30 m. No rerouting.

### 9.6 Parameters (not during a ride)
- **Rider**: weight (kg), FTP (W), max HR, HR zones (5 zones; bounds editable, default from max HR).
- **Bike**: weight (kg), CdA (m²), Crr, drivetrain loss (%). Last calibration result shown next to
  CdA / Crr with its date and ± values.
- **Drafting**: k_draft for On a wheel (0.65), reminder on/off.
- **Weather**: on/off, k_wind (0.6), fallback ρ (1.225).
- **Climbs**: thresholds of §7.1, auto-switch on/off.
- **Route**: import GPX (file picker / share-to-app), clear route.
- **Sensors**: scan and pair HR strap, forget.
- **Offline data**: download map and elevation tiles for a region (pick on a world map / list),
  storage used, delete.
- **Display**: page order, light/dark, optional data fields.
- **Recording**: auto-pause on/off, FIT export folder.

### 9.7 Calibration (not during a normal ride)
Port of the coast-down calibration from Power-calcualtor (`findLapSegments`, `coastWindows`,
`fitCoastDown`, `fitCoastDownPaired`, `runCoastDown`).

1. **Instructions** screen: straight sheltered road, coast from **35–40 km/h down to 12–15 km/h**,
   **5–6 out-and-back pairs**, no braking, normal position. A gentle steady slope (1–2 %) is fine and
   helps reach the start speed: the paired fit solves the slope. Calm day.
2. **Test ride**: a recording mode with a simplified page: speed (huge), run counter per direction,
   heading. **Volume-down** at the start and end of each coast marks the run (lap marks).
3. **Result**: CdA ± , Crr ± , wind along the road, road slope, R², number of runs per direction,
   scatter plot, runs table. **Weak result warning** when: fewer than 3 runs in a direction, CdA
   uncertainty > ±0.025, or speed range too narrow (q max / q min < 2, same rule as the web tool).
   The warning suggests more runs or **keeping Crr fixed** (from tyre tables, about 0.0035–0.0045
   for good road tyres) and fitting CdA only.
4. **Apply** writes CdA / Crr into Parameters and keeps the result in a calibration history.

The calibration test is also saved as a normal ride (FIT with laps), so it can be checked in the
web tool.

---

## 10. FIT file

- Activity file, 1 Hz `record` messages: timestamp, position, distance, speed (`enhanced_speed`),
  altitude (`enhanced_altitude`, from the chosen elevation source), heart rate, power (estimated),
  grade.
- Developer fields: `gps_altitude`, `dem_altitude`, `draft_state`, `power_quality`,
  `wind_head` (m/s), `air_density`.
- `lap` messages for every lap press, `session` and `activity` at the end.
- Device / product: generic "Painmeter". Whether Strava then shows the power as estimated or as
  measured is not known — **[OPEN]**, see §14.

---

## 11. Non-functional

- **Battery**: target ≤ 15 %/h with screen on at medium brightness (to be measured).
- GPS and recording keep running with the screen off (foreground service + notification).
- Permissions: location (fine, background), Bluetooth (scan / connect), notifications.
- No account, no analytics. Network only to Open-Meteo and the map / elevation tile servers.

---

## 12. Acceptance tests

1. **Model port**: the `:core` module run in post-ride mode on a reference GPX/FIT gives the same
   power as `processRide()` in the web tool within ±1 W per point (golden test files in
   `core/src/test/resources`).
2. **Live vs post-ride**: on 3 recorded rides, live 30 s power vs post-ride (web tool) differs by
   < 10 % on average.
3. **Calibration port**: same CdA / Crr as the web tool (±0.001 / ±0.0001) on the same file.
4. **Climb detection**: on a route with known climbs, every climb ≥ 3 %/500 m/25 m is found,
   start and end within 100 m.
5. **Crash**: kill the app mid-ride → ride is resumed with no gap beyond the kill time.
6. **Field test with a real power meter** (borrowed): report error per window as in §5.4.

---

## 13. Phases

1. Project skeleton, foreground service, GPS, ride lifecycle, Data page (without power), ride journal
   recording, FIT export, lap button.
2. HR sensor (pairing, reconnect, zones).
3. Power model port in `:core` with golden tests; elevation tiles; weather; live power; draft toggle.
4. Map page (offline map, GPX route, next turn, off-route).
5. Climb detection and Climb page.
6. Lap page, Calibration (port + flow), Summary, History.
7. Polish: themes, page order, battery tuning, field tests.
8. Strava: connection, import of zones / FTP / weight, upload, starred segments with live display.

---

## 14. Strava and live segments (phase 8)

### 14.1 Connection
- The rider registers a personal Strava API application (strava.com/settings/api, callback domain
  `localhost`) and enters the **Client ID** and **Client Secret** once in Parameters → Strava.
- "Connect Strava" opens the browser for OAuth; the app receives the code through a custom-scheme
  redirect and keeps the refresh token. Scopes: `read`, `profile:read_all`, `activity:write`.
- "Disconnect" deletes the tokens.

### 14.2 Import
- On demand: **HR zones**, **FTP** and **weight** from the athlete profile and zones, shown for
  confirmation before they replace the values in Parameters.

### 14.3 Upload
- At the end of a ride (or from History): upload the FIT file. Automatic upload on/off. Waits for
  a network connection and retries.

### 14.4 Live segments
- **Sync** (manual, or automatic on Wi-Fi): the athlete's **starred segments** with their
  polyline, distance, average gradient, elevation, KOM time and the rider's PR time.
  Strava limits apply (about 100 requests / 15 min): sync only what changed.
- **Start detection**: within 25 m of the segment start, heading within 45° of the segment's
  first 50 m. **Following**: position snapped to the segment polyline; abandoned if > 50 m off
  for > 10 s. **End**: within 25 m of the end point.
- **Display** while on a segment: a segment banner on the current ride page (climbing segments
  use the Climb page): name, **distance remaining**, **time vs PR** in seconds (ahead / behind,
  comparing with the PR at constant pace, since the API gives no PR stream), power 10 s and
  segment average power, HR + zone.
- **At the end**: time, vs PR, vs KOM, average estimated power, shown for 10 s.
- The live time is a guide; Strava times the segment again after upload.
- Not available from the API: full leaderboards, the PR effort second by second.

---

## 15. Open points

- **[OPEN]** How Strava labels power from a non-Garmin device (estimated vs measured).
- **[OPEN]** Screen design: layouts, fonts and colours to be fixed from the mockups.
