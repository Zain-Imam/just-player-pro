#!/usr/bin/env node
// Test HTTP server: serves .smoke-media with byte ranges and can be told to
// misbehave the way a busy debrid or torrent server does.
//
//   /ctl?rate=3000000        throttle every response to this many bytes a second
//   /ctl?holdFrom=0.8        hold requests that start past this share of the file
//   /ctl?holdMs=25000        ...for this long before sending anything
//   /ctl?holdCount=1         ...only this many of them, then serve normally
//   /named/<id>              the test clip, named by the server (Content-Disposition)
//   /ctl?dispositionName=... ...under this file name
//   /ctl?expireNamed=1       ...or gone, as an expired debrid link is (404)
//   /ctl?slowRate=20000      ...or answer them at once but at this trickle
//   /ctl?refuseWhileHeld=1   reset any new connection while a request is held
//   /ctl?needHeader=Referer  refuse files under /secure/ without that header
//   /ctl?needValue=...       ...or without exactly this value in it
//   /ctl?reset=1             all faults off
//   /log                     what has been asked for, newest last
//
// Run: node tools/faultserver.js [port]   then   adb reverse tcp:PORT tcp:PORT
const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = parseInt(process.argv[2] || '8090', 10);
const ROOT = path.join(__dirname, '..', '.smoke-media');

let config = {};
const defaults = () => ({rate: 0, holdFrom: 0, holdMs: 0, slowRate: 0, refuseWhileHeld: false, needHeader: '', needValue: '', holdCount: 0,
  dispositionName: 'Batman.2005.1080p.BluRay.x264.mkv', expireNamed: false});
config = defaults();
let held = 0;
const log = [];

function note(line) {
  const entry = new Date().toISOString().substring(11, 23) + ' ' + line;
  log.push(entry);
  if (log.length > 500) log.shift();
  console.log(entry);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/ctl') {
    if (url.searchParams.has('reset')) config = defaults();
    for (const [k, v] of url.searchParams) {
      if (k === 'reset') continue;
      if (k === 'refuseWhileHeld') config[k] = v === '1';
      else if (k === 'needHeader' || k === 'needValue' || k === 'dispositionName') config[k] = v;
      else if (k === 'expireNamed') config[k] = v === '1';
      else config[k] = parseFloat(v);
    }
    res.end(JSON.stringify(config) + '\n');
    note('CTL ' + JSON.stringify(config));
    return;
  }
  if (url.pathname === '/log') {
    res.end(log.join('\n') + '\n');
    return;
  }
  if (url.pathname === '/held') {
    res.end(String(held) + '\n');
    return;
  }

  if (config.refuseWhileHeld && held > 0) {
    note('REFUSED ' + req.method + ' ' + url.pathname + ' ' + (req.headers.range || ''));
    req.socket.destroy();
    return;
  }

  const secure = url.pathname.startsWith('/secure/');
  const rel = decodeURIComponent(url.pathname.replace(/^\/secure\//, '/')).replace(/^\/+/, '');
  let file = path.join(ROOT, rel);
  // /side/<anything>.ts serves the test clip, so subtitles can sit beside it
  if (/^side\/[^/]+\.ts$/.test(rel) && !fs.existsSync(file)) file = path.join(ROOT, 'clip.ts');
  // /named/<id>: the clip, with its name sent in Content-Disposition
  const named = /^named\/[^/]+$/.test(rel);
  if (named) {
    if (config.expireNamed) {
      res.statusCode = 404;
      res.end('expired\n');
      note('404 expired ' + url.pathname);
      return;
    }
    file = path.join(ROOT, 'clip.ts');
  }
  if (!file.startsWith(ROOT) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    res.statusCode = 404;
    res.end('not found\n');
    note('404 ' + url.pathname);
    return;
  }
  if (secure && config.needHeader) {
    const want = config.needHeader.toLowerCase();
    if (!req.headers[want]) {
      res.statusCode = 403;
      res.end('missing ' + config.needHeader + '\n');
      note('403 missing ' + config.needHeader + ' headers=' + JSON.stringify(req.headers));
      return;
    }
    if (config.needValue && req.headers[want] !== config.needValue) {
      res.statusCode = 403;
      res.end('wrong ' + config.needHeader + '\n');
      note('403 wrong ' + config.needHeader + ' value=' + JSON.stringify(req.headers[want]));
      return;
    }
  }

  const size = fs.statSync(file).size;
  let start = 0;
  let end = size - 1;
  const range = req.headers.range;
  if (range) {
    const m = /bytes=(\d*)-(\d*)/.exec(range);
    if (m) {
      if (m[1] !== '') start = parseInt(m[1], 10);
      if (m[2] !== '') end = Math.min(size - 1, parseInt(m[2], 10));
    }
  }
  if (start > end || start >= size) {
    res.statusCode = 416;
    res.setHeader('Content-Range', 'bytes */' + size);
    res.end();
    return;
  }

  const ext = path.extname(file).toLowerCase();
  const types = {'.mkv': 'video/x-matroska', '.mp4': 'video/mp4', '.ts': 'video/mp2t',
    '.srt': 'application/x-subrip', '.vtt': 'text/vtt', '.ass': 'text/x-ssa'};
  let shouldHold = config.holdFrom > 0 && (config.holdMs > 0 || config.slowRate > 0)
      && start >= size * config.holdFrom;
  // holdCount: hold only that many requests
  if (shouldHold && config.holdCount > 0) {
    config.holdCount--;
    if (config.holdCount === 0) config.holdFrom = 0;
  }

  const send = (rate) => {
    res.statusCode = range ? 206 : 200;
    res.setHeader('Accept-Ranges', 'bytes');
    res.setHeader('Content-Type', types[ext] || 'application/octet-stream');
    res.setHeader('Content-Length', String(end - start + 1));
    if (range) res.setHeader('Content-Range', `bytes ${start}-${end}/${size}`);
    if (named) res.setHeader('Content-Disposition', 'attachment; filename="' + config.dispositionName + '"');
    note(`${res.statusCode} ${url.pathname} ${start}-${end} (${(start / size * 100).toFixed(1)}%)`
        + (req.headers['user-agent'] ? ' ua=' + req.headers['user-agent'] : '')
        + (req.headers['referer'] ? ' referer=' + req.headers['referer'] : ''));
    const stream = fs.createReadStream(file, {start, end, highWaterMark: 64 * 1024});
    if (!rate) {
      stream.pipe(res);
      return;
    }
    // Throttled: one chunk at a time, paced to the configured rate.
    stream.on('data', chunk => {
      stream.pause();
      const ok = res.write(chunk);
      const wait = Math.max(1, chunk.length / rate * 1000);
      const resume = () => setTimeout(() => stream.resume(), wait);
      if (ok) resume(); else res.once('drain', resume);
    });
    stream.on('end', () => res.end());
    res.on('close', () => stream.destroy());
  };

  // slowRate: answer at once but trickle; held while the connection is open
  if (shouldHold && config.slowRate > 0) {
    held++;
    note(`TRICKLE ${url.pathname} from ${start} (${(start / size * 100).toFixed(1)}%) at ${config.slowRate}B/s`);
    let released = false;
    res.on('close', () => { if (!released) { released = true; held--; note('TRICKLE closed'); } });
    send(config.slowRate);
    return;
  }

  if (shouldHold) {
    held++;
    note(`HOLD ${url.pathname} from ${start} (${(start / size * 100).toFixed(1)}%) for ${config.holdMs}ms`);
    let released = false;
    const release = () => { if (!released) { released = true; held--; } };
    res.on('close', release);
    setTimeout(() => { release(); if (!res.destroyed) send(config.rate); }, config.holdMs);
    return;
  }
  send(config.rate);
});

server.listen(PORT, () => note('serving ' + ROOT + ' on ' + PORT));
