// Builds the golden files for PostRideModelTest: runs processRide() from the Power-calcualtor
// web tool (the real HTML, not a copy) on synthetic rides and writes inputs + expected outputs.
//
//   node tools/golden/make_golden.js <path/to/cyclingPowerGenerator.html> core/src/test/resources/golden
'use strict';
const fs = require('fs');
const path = require('path');

const [, , htmlPath, outDir] = process.argv;
const html = fs.readFileSync(htmlPath, 'utf8');

// Pull the needed top-level functions and constants out of the page's script.
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
const src = ['haversine', 'smooth', 'avg', 'smoothByTime', 'normalisedPower', 'processRide'].map(grabFunction).join('\n')
  + '\nconst G = 9.8067;\nconst WHEEL_INERTIA_MASS = 1.5;\n';
const processRide = new Function(src + '\nreturn processRide;')();

// Deterministic pseudo-random numbers.
let seed = 42;
const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
const gauss = () => Math.sqrt(-2 * Math.log(rnd() + 1e-12)) * Math.cos(2 * Math.PI * rnd());

function makeRide({ n, withTime, withSpeed, eleMissing }) {
  const pts = [];
  let lat = 45.1, lon = 5.7, heading = 0.3, ele = 300, t = Date.UTC(2026, 10, 16, 8, 0, 0), v = 8;
  for (let i = 0; i < n; i++) {
    const grade = 0.06 * Math.sin(i / 180) + 0.02 * Math.sin(i / 37);
    v = Math.max(0, Math.min(16, v + 0.25 * gauss() - 0.4 * grade * 10 * 0.05));
    if (i > 600 && i < 640) v = Math.max(0, v - 0.4);          // a stop
    heading += 0.01 * gauss();
    const d = v * 1;
    lat += d * Math.cos(heading) / 111195;
    lon += d * Math.sin(heading) / (111195 * Math.cos(lat * Math.PI / 180));
    ele += grade * d;
    const gap = (i % 400 === 399) ? 2000 : 1000;              // an occasional 2 s gap
    t += gap;
    pts.push({
      lat: lat + 3e-6 * gauss(), lon: lon + 3e-6 * gauss(),
      ele: (eleMissing && i % 7 === 0) ? null : ele + 1.5 * gauss(),
      time: withTime ? new Date(t) : null,
      speed: withSpeed ? ((i % 13 === 0) ? null : Math.max(0, v + 0.1 * gauss())) : null,
    });
  }
  return pts;
}

const fmt = (x) => (x === null || x === undefined || Number.isNaN(x)) ? '' : String(x);

function write(name, pts, params, weather) {
  const res = processRide(pts, params, weather);
  const inLines = [`#params ${params.rider} ${params.bike} ${params.cda} ${params.crr} ${params.rho} ${params.loss} ${params.smooth}`,
    'lat,lon,ele,time,speed,wind,rho'];
  pts.forEach((p, i) => inLines.push([p.lat, p.lon, fmt(p.ele), p.time ? p.time.getTime() : '', fmt(p.speed),
    weather ? fmt(weather.wind[i]) : '', weather ? fmt(weather.rho[i]) : ''].join(',')));
  fs.writeFileSync(path.join(outDir, name + '.in.csv'), inLines.join('\n') + '\n');
  const outLines = [`#summary ${res.avgPower} ${res.maxPower} ${res.np} ${res.work} ${res.distKm}`, 'power,grade,speed'];
  res.power.forEach((p, i) => outLines.push([p, res.grade[i], res.speed[i]].join(',')));
  fs.writeFileSync(path.join(outDir, name + '.expected.csv'), outLines.join('\n') + '\n');
  console.log(name, 'points', pts.length, 'avg', res.avgPower.toFixed(1), 'NP', res.np.toFixed(1));
}

const params = { rider: 72.5, bike: 8.2, cda: 0.31, crr: 0.0042, rho: 1.2, loss: 2, smooth: 15 };
write('hilly_gps_speed', makeRide({ n: 1800, withTime: true, withSpeed: false }), params, null);

const ride2 = makeRide({ n: 1500, withTime: true, withSpeed: true, eleMissing: true });
const weather = {
  wind: ride2.map((_, i) => 0.6 * 4 * Math.cos(i / 300)),
  rho: ride2.map((_, i) => (i % 50 === 0 ? null : 1.18 + 0.01 * Math.sin(i / 200))),
};
write('device_speed_weather', ride2, { ...params, smooth: 10 }, weather);

write('no_time', makeRide({ n: 900, withTime: false, withSpeed: false }), { ...params, smooth: 0 }, null);
