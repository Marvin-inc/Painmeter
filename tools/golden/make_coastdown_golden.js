// Builds the golden files for CoastDownTest: simulates a coast-down test (out-and-back runs on a
// steady slope with a steady wind, known CdA and Crr), then runs the web tool's own processRide()
// and runCoastDown() from cyclingPowerGenerator.html on it, for several options.
//
//   node tools/golden/make_coastdown_golden.js <path/to/cyclingPowerGenerator.html> core/src/test/resources/golden
'use strict';
const fs = require('fs');
const path = require('path');
const [, , htmlPath, outDir] = process.argv;
const html = fs.readFileSync(htmlPath, 'utf8');

function grabFunction(name) {
  const start = html.indexOf(`function ${name}(`);
  if (start < 0) throw new Error('missing ' + name);
  let i = html.indexOf('{', start), depth = 0;
  for (; i < html.length; i++) {
    if (html[i] === '{') depth++;
    else if (html[i] === '}' && --depth === 0) break;
  }
  return html.slice(start, i + 1);
}
const names = ['haversine', 'bearingDeg', 'smooth', 'avg', 'smoothByTime', 'normalisedPower', 'processRide',
  'findLapSegments', 'findCoastSegments', 'lineSlope', 'coastWindows', 'solveLS', 'r2Of', 'robustFit',
  'fitCoastDown', 'fitCoastDownPaired', 'assignDirections', 'runCoastDown'];
const src = names.map(grabFunction).join('\n') + '\nconst G = 9.8067;\nconst WHEEL_INERTIA_MASS = 1.5;\n';
const lib = new Function(src + '\nreturn { processRide, runCoastDown };')();

let seed = 7;
const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
const gauss = () => Math.sqrt(-2 * Math.log(rnd() + 1e-12)) * Math.cos(2 * Math.PI * rnd());

// Truth.
const CDA = 0.30, CRR = 0.005, SLOPE = 0.005, WIND = 2.0, RHO = 1.2, M = 80, MEFF = M + 1.5, g = 9.8067;
const HEADING = 20 * Math.PI / 180, LAT0 = 45.2, LON0 = 5.7;

const pts = [];
const laps = [];
let t = Date.UTC(2026, 10, 16, 7, 0, 0), x = 0, v = 5, dir = 1;
let lapStart = t;
function push() {
  const lat = LAT0 + x * Math.cos(HEADING) / 111195;
  const lon = LON0 + x * Math.sin(HEADING) / (111195 * Math.cos(LAT0 * Math.PI / 180));
  pts.push({
    lat: lat + 0.3 / 111195 * gauss(), lon: lon + 0.3 / 111195 * gauss(),
    ele: 300 + SLOPE * x + 0.3 * gauss(), time: new Date(t), speed: Math.max(0, v + 0.05 * gauss()), cad: null,
  });
}
function step(pedal) {
  const sub = 10;
  for (let k = 0; k < sub; k++) {
    const va = v + dir * WIND;
    const resist = CRR * M * g + dir * M * g * SLOPE + 0.5 * RHO * CDA * va * Math.abs(va);
    const a = pedal ? 0.5 : -resist / MEFF;
    v = Math.max(0, v + a / sub);
    x += dir * v / sub;
  }
  t += 1000;
  push();
}
function lap() { laps.push({ start: new Date(lapStart), end: new Date(t) }); lapStart = t; }

push();
for (let pair = 0; pair < 7; pair++) {
  for (const d of [1, -1]) {
    dir = d;
    while (v < 11) step(true);     // pedal up
    for (let s = 0; s < 3; s++) step(true);
    lap();                          // press: coasting starts
    while (v > 4) step(false);      // coast down
    lap();                          // press: coasting ends
    for (let s = 0; s < 4; s++) step(true);
  }
}
pts.laps = laps;

const params = { rider: 72, bike: 8, cda: CDA, crr: CRR, rho: RHO, loss: 2, smooth: 15 };
const d = lib.processRide(pts, params, null);

const lines = [`#truth cda=${CDA} crr=${CRR} slope=${SLOPE} wind=${WIND}`, 'lat,lon,ele,time,speed'];
pts.forEach(p => lines.push([p.lat, p.lon, p.ele, p.time.getTime(), p.speed].join(',')));
laps.forEach(l => lines.push(`#lap ${(l.start - pts[0].time) / 1000} ${(l.end - pts[0].time) / 1000}`));
fs.writeFileSync(path.join(outDir, 'coastdown.in.csv'), lines.join('\n') + '\n');

const variants = {
  paired: [M, 0.004, false, true, true],
  single: [M, 0.004, false, false, true],
  slowdown: [M, 0.004, false, true, false],
  fixed: [M, 0.0045, true, true, true],
};
const out = [];
for (const [name, args] of Object.entries(variants)) {
  const r = lib.runCoastDown(d, ...args);
  const f = r.fit || {};
  const used = (r.wins || []).filter(w => w.used).length;
  out.push([name, r.error || '', r.source, r.segs.length, r.method || '', r.mode || '', r.fallback || '', r.pairedAvailable,
    f.cda, f.crr, f.seCda, f.seCrr == null ? '' : f.seCrr, f.r2, f.n, f.wind == null ? '' : f.wind,
    f.slope == null ? '' : f.slope, used].join(','));
  console.log(name, r.method, r.mode, r.fallback, 'CdA', f.cda && f.cda.toFixed(4), 'Crr', f.crr && f.crr.toFixed(5),
    'wind', f.wind && f.wind.toFixed(2), 'slope', f.slope && f.slope.toFixed(4), 'segs', r.segs.length);
}
fs.writeFileSync(path.join(outDir, 'coastdown.expected.csv'),
  'variant,error,source,segs,method,mode,fallback,paired,cda,crr,seCda,seCrr,r2,n,wind,slope,used\n' + out.join('\n') + '\n');
