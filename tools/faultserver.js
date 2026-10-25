#!/usr/bin/env node
// Test HTTP server: serves .smoke-media with byte ranges and can be told to
// misbehave the way a busy debrid or torrent server does.
//
//   /ctl?rate=3000000        throttle every response to this many bytes a second
//   /ctl?holdFrom=0.8        hold requests that start past this share of the file
//   /ctl?holdMs=25000        ...for this long before sending anything
//   /ctl?holdCount=1         ...only this many of them, then serve normally
//   /named/<id>              the test clip (or namedFile), named by the server (Content-Disposition)
//   /ctl?dispositionName=... ...under this file name
//   /ctl?expireNamed=1       ...or gone, as an expired debrid link is (404)
//   /ctl?namedDelayMs=6000   ...or slow to answer at all, as a busy host is
//   /ctl?srtDelayMs=300      every .srt answers after this long, as a subtitle site does
//   /ctl?slowRate=20000      ...or answer them at once but at this trickle
//   /ctl?refuseWhileHeld=1   reset any new connection while a request is held
//   /ctl?needHeader=Referer  refuse files under /secure/ without that header
//   /ctl?needValue=...       ...or without exactly this value in it
//   /ctl?cutAt=0.5           cut the connection once a response reaches this share of the file
//   /ctl?stallAt=0.5         ...or stop sending there and leave it open, as a stalled host does
//   /ctl?downMs=45000        ...then refuse every connection for this long, the host down
//   /ctl?dropAfterBytes=4e6  every reply from dropFrom on gives out after this many bytes, every time
//   /ctl?dropFrom=0.5        ...(then down for downMs, as above), a host that keeps cutting near the end
//   /ctl?dropGrid=5e6        ...cut on the next multiple of this past those bytes, so each reopen gets a bit further
//   /ctl?reset=1             all faults off
//   /redirect/<name>?to=URL  a 302 to that address (http to https, as debrid links do)
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
  dispositionName: 'Batman.2005.1080p.BluRay.x264.mkv', namedFile: 'clip.ts', expireNamed: false, namedDelayMs: 0, srtDelayMs: 0,
  cutAt: 0, stallAt: 0, downMs: 0, dropAfterBytes: 0, dropFrom: 0, dropGrid: 0});
config = defaults();
let held = 0;
let downUntil = 0;
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
      else if (k === 'needHeader' || k === 'needValue' || k === 'dispositionName' || k === 'namedFile') config[k] = v;
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
  if (url.pathname.startsWith('/redirect/') && url.searchParams.has('to')) {
    note('302 ' + url.pathname + ' -> ' + url.searchParams.get('to'));
    res.writeHead(302, { Location: url.searchParams.get('to') });
    res.end();
    return;
  }

  if (Date.now() < downUntil) {
    note('DOWN ' + req.method + ' ' + url.pathname + ' ' + (req.headers.range || ''));
    req.socket.destroy();
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
  if (/^side\/[^/]+\.ts$/.test(rel) && !fs.existsSync(file)) file = path.join(ROOT, 'clip.ts');
  const named = /^named\/[^/]+$/.test(rel);
  const lookup = req.method === 'HEAD' || req.headers.range === 'bytes=0-0';
  const delay = named && lookup ? config.namedDelayMs : rel.endsWith('.srt') ? config.srtDelayMs : 0;
  if (delay > 0 && !req.delayed) {
    req.delayed = true;
    note('DELAY ' + req.method + ' ' + url.pathname + ' ' + delay + 'ms');
    setTimeout(() => server.emit('request', req, res), delay);
    return;
  }
  if (named) {
    if (config.expireNamed) {
      res.statusCode = 404;
      res.end('expired\n');
      note('404 expired ' + url.pathname);
      return;
    }
    file = path.join(ROOT, config.namedFile);
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
    const breakAt = config.cutAt > 0 ? config.cutAt : config.stallAt;
    const breakByte = breakAt > 0 ? Math.floor(size * breakAt) : -1;
    if (breakByte > start && breakByte <= end) {
      const cut = config.cutAt > 0;
      const part = fs.createReadStream(file, {start, end: breakByte - 1, highWaterMark: 64 * 1024});
      part.pipe(res, {end: false});
      part.on('end', () => {
        config.cutAt = 0;
        config.stallAt = 0;
        note((cut ? 'CUT ' : 'STALL ') + url.pathname + ' at ' + breakByte + ` (${(breakAt * 100).toFixed(1)}%)`
            + (config.downMs ? `, down for ${config.downMs}ms` : ''));
        downUntil = Date.now() + config.downMs;
        if (cut) res.socket.destroy();
      });
      res.on('close', () => part.destroy());
      return;
    }
    if (config.dropAfterBytes > 0 && start >= size * config.dropFrom && end - start + 1 > config.dropAfterBytes) {
      const least = start + Math.floor(config.dropAfterBytes);
      const stop = Math.min(end, config.dropGrid > 0
          ? Math.ceil(least / config.dropGrid) * config.dropGrid - 1 : least - 1);
      const part = fs.createReadStream(file, {start, end: stop, highWaterMark: 64 * 1024});
      part.pipe(res, {end: false});
      part.on('end', () => {
        note(`DROP ${url.pathname} at ${stop + 1} (${((stop + 1) / size * 100).toFixed(1)}%) from ${start} (${(start / size * 100).toFixed(1)}%)`
            + (config.downMs ? `, down for ${config.downMs}ms` : ''));
        downUntil = Date.now() + config.downMs;
        res.socket.destroy();
      });
      res.on('close', () => part.destroy());
      return;
    }
    const stream = fs.createReadStream(file, {start, end, highWaterMark: 64 * 1024});
    if (!rate) {
      stream.pipe(res);
      return;
    }
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
