// Every picture in Settings and the introduction, drawn from the real screens:
// the player's bars and icons per style, the home list and wall, the lock,
// the seek preview, the skip button, the resume question and the rest.
// node pictures.js <drawable dir> <svg json>
const fs = require("fs");
const k = require("./kit");
const { ACCENT: A, rect, round, circle, ring, shape, line, ic, text, textWidth } = k;

const WHITE = "#FFFFFF";
const GREY = "#9E9E9E";

function film(w, h, tint = 0) {
  const sky = ["#62788F", "#6F6A86", "#5E7F86"][tint];
  return [
    rect(0, 0, w, h, sky),
    circle(w * 0.7, h * 0.3, h * 0.1, "#E2C07E", 0.9),
    shape(`M0,${h * 0.64}L${w * 0.22},${h * 0.5}L${w * 0.42},${h * 0.6}L${w * 0.66},${h * 0.46}L${w},${h * 0.58}V${h}H0Z`, "#4E6B5E"),
    shape(`M0,${h * 0.8}C${w * 0.3},${h * 0.72} ${w * 0.6},${h * 0.76} ${w},${h * 0.7}V${h}H0Z`, "#3E5944"),
  ];
}

function centrePlay(style, w, h, playing) {
  const r = h * 0.095;
  const cx = w / 2;
  const cy = h * 0.5;
  const glyph = playing ? "ic_pause_24dp" : "ic_play_arrow_24dp";
  const s = r * 1.25;
  if (style === "classic") {
    return [circle(cx, cy, r, WHITE), ic(glyph, cx - s / 2 + (playing ? 0 : r * 0.08), cy - s / 2, s, "#9A9A9A")];
  }
  if (style === "glass") {
    return [circle(cx, cy, r, "#1E2228", 0.55), ring(cx, cy, r, WHITE, 0.4, 0.35),
      ic(glyph, cx - s / 2 + (playing ? 0 : r * 0.08), cy - s / 2, s, WHITE)];
  }
  return [circle(cx, cy, r, A), circle(cx, cy, r, "#000000", 0.45),
    ic(glyph, cx - s / 2 + (playing ? 0 : r * 0.08), cy - s / 2, s, WHITE)];
}

const BUTTONS = ["ic_picture_in_picture_alt_24dp", "ic_screen_lock_landscape_24dp", "ic_lock_open_24dp",
  "ic_settings_24dp", "ic_fit_screen_24dp", "ic_audio_track_24dp", "exo_ic_subtitle_on", "ic_folder_open_24dp"];

function controls(style, w, h, opts = {}) {
  const out = [];
  const topH = h * 0.22;
  const barTop = h * 0.79;
  if (style === "classic") {
    out.push(rect(0, 0, w, topH, "#000000", 0.42), rect(0, barTop - h * 0.05, w, h - barTop + h * 0.05, "#000000", 0.5));
  } else if (style === "glass") {
    out.push(rect(0, 0, w, topH, "#D8E0E8", 0.34), rect(0, barTop - h * 0.04, w, h - barTop + h * 0.04, "#D8E0E8", 0.34),
      rect(0, topH, w, 0.4, WHITE, 0.4), rect(0, barTop - h * 0.04, w, 0.4, WHITE, 0.4));
  } else {
    out.push(rect(0, 0, w, topH, "#121212", 0.6), rect(0, 0, w, topH, A, 0.14),
      rect(0, barTop - h * 0.04, w, h - barTop + h * 0.04, "#121212", 0.7), rect(0, barTop - h * 0.04, w, h - barTop + h * 0.04, A, 0.16));
  }
  const backS = h * 0.085;
  out.push(ic("ic_arrow_back_24dp", w * 0.03, h * 0.055, backS, WHITE));
  out.push(text(opts.title || "Sintel", w * 0.03 + backS * 1.5, h * 0.11, h * 0.072, { weight: 500 }));
  out.push(text("1080p · H.264 · AAC 2.0", w * 0.03 + backS * 1.5, h * 0.18, h * 0.05, { fill: WHITE, alpha: 0.7 }));
  const x0 = w * 0.045;
  const x1 = w * 0.955;
  const y = h * 0.835;
  const at = x0 + (x1 - x0) * (opts.progress === undefined ? 0.28 : opts.progress);
  if (style === "material") {
    out.push(round(x0, y - h * 0.016, x1 - x0, h * 0.032, h * 0.016, A, 0.35),
      round(x0, y - h * 0.016, at - x0, h * 0.032, h * 0.016, A),
      round(at - h * 0.008, y - h * 0.045, h * 0.016, h * 0.09, h * 0.008, A));
  } else {
    out.push(rect(x0, y - h * 0.006, x1 - x0, h * 0.012, WHITE, style === "glass" ? 0.7 : 0.85),
      rect(x0, y - h * 0.006, at - x0, h * 0.012, A));
    if (style === "glass") {
      out.push(circle(at, y, h * 0.036, WHITE, 0.95), circle(at, y, h * 0.022, A));
    } else {
      out.push(circle(at, y, h * 0.03, A));
    }
  }
  const rowY = h * 0.915;
  const s = h * 0.075;
  out.push(ic(opts.playing ? "ic_pause_24dp" : "ic_play_arrow_24dp", w * 0.035, rowY - s / 2, s, WHITE));
  const time = style === "material" ? "00:10 / 03:20" : "00:10 · 03:20";
  out.push(text(time, w * 0.035 + s * 1.4, rowY + h * 0.022, h * 0.058, { weight: 500 }));
  const buttons = opts.buttons || BUTTONS.slice(0, 7);
  let x = w * 0.965 - s;
  for (const name of buttons) {
    const accent = opts.accentButton === name;
    out.push(ic(name, x, rowY - s / 2, s, accent ? A : WHITE));
    x -= s * 1.75;
  }
  return out;
}

function player(style, w, h, opts = {}) {
  return [film(w, h, opts.tint), centrePlay(style, w, h, opts.playing), controls(style, w, h, opts)];
}

function homeTop(w, h, s) {
  const out = [rect(0, 0, w, h, "#2F2F2F")];
  out.push(text("Just Player Pro", w * 0.05, s * 1.5, s * 0.95, { weight: 500 }));
  let x = w * 0.95 - s;
  for (const name of ["ic_settings_24dp", "ic_link_24dp", "ic_sort_24dp", "ic_search_24dp"]) {
    out.push(ic(name, x, s * 0.75, s, WHITE));
    x -= s * 1.6;
  }
  return out;
}

function folderRow(w, y, s, name, meta) {
  return [
    ic("ic_folder_24dp", w * 0.05, y, s * 1.15, A),
    text(name, w * 0.05 + s * 1.9, y + s * 0.45, s * 0.8),
    text(meta, w * 0.05 + s * 1.9, y + s * 1.25, s * 0.62, { fill: GREY }),
    ic("ic_pin_24dp", w * 0.95 - s, y + s * 0.1, s, "#BDBDBD"),
  ];
}

function homeList(w, h) {
  const s = h * 0.085;
  const out = [homeTop(w, h, s)];
  out.push(text("FOLDERS", w * 0.05, s * 3.2, s * 0.62, { weight: 500, fill: A }));
  const rows = [["Camera", "50 videos · 946 MB"], ["Download", "40 videos · 132 MB"], ["Movies", "4 videos · 306 MB"]];
  rows.forEach(([name, meta], i) => out.push(folderRow(w, s * 4.1 + i * s * 2.45, s, name, meta)));
  return out;
}

const FRAMES = ["#7A5A3C", "#3E5873", "#5C6E4A", "#6A4A5E", "#4A6870", "#8A6A4A", "#55607A", "#6E5A48"];
function homeWall(w, h) {
  const s = h * 0.085;
  const out = [homeTop(w, h, s)];
  out.push(text("FOLDERS", w * 0.05, s * 3.2, s * 0.62, { weight: 500, fill: A }));
  const tileW = w * 0.43;
  const tileH = tileW * 0.56;
  [["Camera", 0], ["Download", 4]].forEach(([name, f], i) => {
    const x = w * 0.05 + i * (tileW + w * 0.04);
    const y = s * 3.9;
    out.push(round(x, y, tileW, tileH, s * 0.25, "#1E1E1E"));
    for (let j = 0; j < 4; j++) {
      out.push(rect(x + (j % 2) * tileW / 2 + 0.4, y + Math.floor(j / 2) * tileH / 2 + 0.4, tileW / 2 - 0.8, tileH / 2 - 0.8, FRAMES[f + j]));
    }
    out.push(text(name, x, y + tileH + s * 1.05, s * 0.8, { weight: 500 }));
    out.push(text(i === 0 ? "50 videos · 946 MB" : "40 videos · 132 MB", x, y + tileH + s * 1.9, s * 0.6, { fill: GREY }));
  });
  return out;
}

function homeRecent(w, h, s, opts = {}) {
  const out = [homeTop(w, h, s)];
  out.push(text("RECENT", w * 0.05, s * 3.2, s * 0.62, { weight: 500, fill: A }));
  const films = [["Sintel (2010)", "Sintel.2010.1080p.mkv", "#7A5A3C"], ["Big Buck Bunny (2008)", "Big.Buck.Bunny.mkv", "#3E5873"]];
  films.forEach(([title, file, colour], i) => {
    const y = s * 3.9 + i * s * 2.6;
    out.push(round(w * 0.05, y, s * 1.5, s * 2.2, s * 0.15, colour));
    out.push(text(title, w * 0.05 + s * 2.1, y + s * 0.95, s * 0.8));
    out.push(text(file, w * 0.05 + s * 2.1, y + s * 1.75, s * 0.6, { fill: GREY }));
    if (i === 0 && opts.pinned) {
      out.push(ic("ic_pin_filled_24dp", w * 0.95 - s, y + s * 0.5, s, "#F2B233"));
    }
  });
  out.push(text("FOLDERS", w * 0.05, s * 9.6, s * 0.62, { weight: 500, fill: A }));
  out.push(folderRow(w, s * 10.4, s, "Camera", "50 videos · 946 MB"));
  return out;
}

function lockBadge(cx, cy, size) {
  return [round(cx - size / 2, cy - size / 2, size, size, size * 0.28, "#2A2A2A", 0.85),
    ic("ic_lock_24dp", cx - size * 0.3, cy - size * 0.3, size * 0.6, WHITE)];
}

function dialog(x, y, w, h, s, title, body, buttons) {
  const out = [round(x, y, w, h, s * 0.2, "#424242")];
  out.push(text(title, x + s * 1.2, y + s * 1.9, s * 1.05, { weight: 500 }));
  out.push(text(body, x + s * 1.2, y + s * 3.4, s * 0.85, { fill: "#D6D6D6" }));
  let bx = x + w - s * 1.1;
  for (const label of buttons.slice().reverse()) {
    out.push(text(label, bx, y + h - s * 1.0, s * 0.8, { weight: 500, fill: A, anchor: "end" }));
    bx -= textWidth(label, s * 0.8, 500) + s * 1.3;
  }
  return out;
}

function seekPill(cx, y, s, time, delta) {
  const tw = textWidth(time, s, 500);
  const dw = delta ? textWidth(delta, s * 0.85) + s * 0.5 : 0;
  const pw = tw + dw + s * 1.4;
  return [round(cx - pw / 2, y, pw, s * 1.7, s * 0.85, "#000000", 0.7),
    text(time, cx - pw / 2 + s * 0.7, y + s * 1.2, s, { weight: 500 }),
    delta ? text(delta, cx - pw / 2 + s * 0.7 + tw + s * 0.5, y + s * 1.2, s * 0.85, { fill: "#CFCFCF" }) : null];
}

function seekBubble(w, h, kind) {
  const out = [player("classic", w, h, { progress: 0.62 })];
  const thumbX = w * 0.045 + w * 0.91 * 0.62;
  const s = h * 0.06;
  if (kind === "off") {
    out.push(seekPill(thumbX, h * 0.55, s, "12:40", "+8:20"));
  } else {
    const fw = w * 0.36;
    const fh = fw * 0.5625;
    const fx = Math.min(w - fw - w * 0.03, thumbX - fw / 2);
    const fy = h * 0.27;
    out.push(round(fx - 0.8, fy - 0.8, fw + 1.6, fh + 1.6, s * 0.35, WHITE, 0.95));
    out.push(film(fw, fh, 1).map((p) => ({ group: { tx: fx, ty: fy, s: 1 }, items: [p] })));
    out.push(seekPill(fx + fw / 2, fy + fh + s * 0.4, s, "12:40", "+8:20"));
    const badge = kind === "local" ? "ic_folder_24dp" : "ic_settings_online";
    out.push(circle(w * 0.07, h * 0.34, h * 0.075, "#000000", 0.6), ic(badge, w * 0.07 - h * 0.05, h * 0.29, h * 0.1, WHITE));
  }
  return out;
}

const CW = 120, CH = 68;     // a choice's picture
const SW = 132, SH = 64;     // a switch's picture
const IW = 240, IH = 120;    // an introduction card's picture

function battery(x, y, h, level, charging, colour = WHITE) {
  const w = h * 1.9;
  const out = [{ d: round(x, y, w, h, h * 0.22).d, stroke: colour, width: h * 0.11 },
    round(x + w, y + h * 0.3, h * 0.16, h * 0.4, h * 0.06, colour),
    round(x + h * 0.16, y + h * 0.16, (w - h * 0.32) * level, h * 0.68, h * 0.1, colour)];
  if (charging) {
    out.push(shape(`M${x + w * 0.55},${y - h * 0.05}l${-h * 0.45},${h * 0.62}h${h * 0.32}l${-h * 0.12},${h * 0.5}l${h * 0.45},${-h * 0.62}h${-h * 0.32}z`, "#FFD54A"));
  }
  return out;
}

function corner(w, h, what, size) {
  const out = [];
  let x = w * 0.965;
  if (what.includes("clock")) {
    out.push(text("9:41 PM", x, h * 0.11, size, { anchor: "end" }));
    x -= textWidth("9:41 PM", size) + size * 0.9;
  }
  if (what.includes("battery")) {
    out.push(text("81%", x, h * 0.11, size, { anchor: "end" }));
    x -= textWidth("81%", size) + size * 0.35;
    out.push(battery(x - size * 1.45, h * 0.11 - size * 0.72, size * 0.72, 0.81, false));
  }
  return out;
}

function statusBar(w, h) {
  const s = h * 0.045;
  return [rect(0, 0, w, h * 0.075, "#000000", 0.35),
    text("9:41", w * 0.03, h * 0.055, s, { weight: 500 }),
    battery(w * 0.965 - s * 1.5, h * 0.022, s * 0.75, 0.81, false)];
}

function clockScene(w, h, mode, what, opts = {}) {
  const size = h * 0.062;
  if (mode === "off") {
    return [film(w, h), opts.controls ? [centrePlay("classic", w, h), controls("classic", w, h)] : []];
  }
  if (mode === "controls") {
    if (opts.statusBar) {
      const dy = h * 0.075;
      return [film(w, h), centrePlay("classic", w, h), statusBar(w, h),
        { group: { tx: 0, ty: dy, s: 1 }, items: k.flat([controls("classic", w, h - dy), corner(w, h - dy, what, size)]) }];
    }
    return [film(w, h), centrePlay("classic", w, h), controls("classic", w, h), corner(w, h, what, size)];
  }
  return [film(w, h), corner(w, h, what, size * 1.05)];
}

const pictures = {
  sketch_style_classic: [CW, CH, player("classic", CW, CH)],
  sketch_style_glass: [CW, CH, player("glass", CW, CH)],
  sketch_style_material: [CW, CH, player("material", CW, CH)],
  sketch_layout_list: [CW, CH, homeList(CW, CH)],
  sketch_layout_wall: [CW, CH, homeWall(CW, CH)],
  sketch_start_home: [CW, CH, homeList(CW, CH)],
  sketch_start_last: [CW, CH, player("classic", CW, CH, { playing: true, progress: 0.46 })],
  sketch_seek_off: [CW, CH, seekBubble(CW, CH, "off")],
  sketch_seek_local: [CW, CH, seekBubble(CW, CH, "local")],
  sketch_seek_all: [CW, CH, seekBubble(CW, CH, "all")],

  sketch_clock_off: [CW, CH, clockScene(CW, CH, "off", [], { controls: true })],
  sketch_clock_controls: [CW, CH, clockScene(CW, CH, "controls", ["clock"])],
  sketch_clock_always: [CW, CH, clockScene(CW, CH, "always", ["clock"])],
  sketch_battery_off: [CW, CH, clockScene(CW, CH, "off", [], { controls: true })],
  sketch_battery_controls: [CW, CH, clockScene(CW, CH, "controls", ["battery"])],
  sketch_battery_always: [CW, CH, clockScene(CW, CH, "always", ["battery"])],
  mock_controls_phone: [240, 108, clockScene(240, 108, "controls", ["clock", "battery"], { statusBar: true }), true],
  mock_controls_tv: [240, 135, clockScene(240, 135, "controls", ["clock", "battery"]), true],
  mock_always: [240, 108, clockScene(240, 108, "always", ["clock", "battery"]), true],

  sketch_subs_above: [SW, SH, [player("classic", SW, SH),
    text("Where are you going?", SW / 2, SH * 0.71, SH * 0.075, { anchor: "middle" })]],
  sketch_locked_timeline: [SW, SH, [film(SW, SH), lockBadge(SW / 2, SH * 0.32, SH * 0.2),
    rect(0, SH * 0.72, SW, SH * 0.28, "#000000", 0.45),
    text("00:08", SW * 0.05, SH * 0.84, SH * 0.075, { weight: 500 }),
    text("−03:11 / 03:19", SW * 0.95, SH * 0.84, SH * 0.075, { weight: 500, anchor: "end" }),
    rect(SW * 0.05, SH * 0.92, SW * 0.9, SH * 0.014, WHITE, 0.85), rect(SW * 0.05, SH * 0.92, SW * 0.9 * 0.06, SH * 0.014, A),
    circle(SW * 0.05 + SW * 0.9 * 0.06, SH * 0.927, SH * 0.03, WHITE)]],
  sketch_loop: [SW, SH, player("classic", SW, SH, {
    buttons: ["ic_picture_in_picture_alt_24dp", "ic_lock_open_24dp", "ic_settings_24dp", "exo_ic_subtitle_on", "exo_icon_repeat_all"],
    accentButton: "exo_icon_repeat_all" })],
  sketch_skip: [SW, SH, [film(SW, SH), (() => {
    const s = SH * 0.08;
    const label = "Skip intro";
    const pw = s * 1.6 + textWidth(label, s) + s * 2.2;
    const x = SW * 0.95 - pw;
    const y = SH * 0.6;
    return [round(x, y, pw, s * 2.4, s * 1.2, "#1A1A1A", 0.8),
      { d: round(x, y, pw, s * 2.4, s * 1.2).d, stroke: WHITE, width: 0.5, alpha: 0.4 },
      ic("ic_skip_next_24dp", x + s * 0.8, y + s * 0.5, s * 1.4, WHITE), text(label, x + s * 2.6, y + s * 1.55, s)];
  })()]],
  sketch_info_card: [SW, SH, [film(SW, SH), rect(0, 0, SW, SH, "#000000", 0.3), (() => {
    const s = SH * 0.07;
    const x = SW * 0.05;
    const y = SH * 0.14;
    return [round(x, y, SW * 0.66, SH * 0.72, s * 0.5, "#141414", 0.82),
      round(x + s, y + s, s * 4, s * 6, s * 0.2, "#7A5A3C"),
      text("Sintel", x + s * 6, y + s * 2, s * 1.15, { weight: 500 }),
      text("2010 · 15 min · Animation", x + s * 6, y + s * 3.4, s * 0.75, { fill: A }),
      text("A lonely young woman", x + s * 6, y + s * 4.9, s * 0.75, { fill: "#D0D0D0" }),
      text("searches for her dragon.", x + s * 6, y + s * 6.1, s * 0.75, { fill: "#D0D0D0" })];
  })()]],
  sketch_recent_posters: [SW, SH, homeRecent(SW, SH * 1.0, SH * 0.075)],
  sketch_resume: [SW, SH, [homeList(SW, SH), rect(0, 0, SW, SH, "#000000", 0.5),
    dialog(SW * 0.1, SH * 0.2, SW * 0.8, SH * 0.62, SH * 0.07, "Play last video?", "Sintel.2010.1080p.mkv", ["NOT NOW", "PLAY"])]],
  sketch_pip: [SW, SH, (() => {
    const out = [rect(0, 0, SW, SH, "#262626")];
    const tints = ["#C0563B", "#3E7CB1", "#4F9A5E", "#B0478A", "#C99A2E", "#5A6FB0"];
    for (let r = 0; r < 3; r++) {
      for (let c = 0; c < 4; c++) {
        out.push(circle(16 + c * 18, 12 + r * 18, 6, tints[(r * 4 + c) % tints.length], 0.85));
      }
    }
    const x = SW * 0.56;
    const y = SH * 0.42;
    const pw = SW * 0.4;
    const ph = pw * 0.5625;
    out.push(round(x - 0.8, y - 0.8, pw + 1.6, ph + 1.6, 3, "#000000"));
    out.push(film(pw, ph).map((p) => ({ group: { tx: x, ty: y, s: 1 }, items: [p] })));
    return out;
  })()],

  intro_style_classic: [IW, IH, player("classic", IW, IH)],
  intro_style_glass: [IW, IH, player("glass", IW, IH)],
  intro_style_material: [IW, IH, player("material", IW, IH)],
  intro_recent: [IW, IH, [homeRecent(IW, IH, IH * 0.07, { pinned: true }), (() => {
    const s = IH * 0.07;
    const x = IW * 0.5;
    const y = IH * 0.36;
    const items = [["ic_pin_24dp", "Pin to the top"], ["ic_history_24dp", "Play from the start"],
      ["ic_info_card_24dp", "Film info"], ["ic_delete_24dp_", "Remove from Recent"]];
    const out = [round(x, y, IW * 0.46, IH * 0.6, s * 0.5, "#3A3A3A")];
    items.forEach(([name, label], i) => {
      out.push(ic(name, x + s * 0.8, y + s * 0.7 + i * s * 2, s * 1.2, i === 0 ? A : "#E0E0E0"),
        text(label, x + s * 2.7, y + s * 1.65 + i * s * 2, s * 0.9, { fill: "#EDEDED" }));
    });
    return out;
  })()]],
  intro_key: [IW, IH, [film(IW, IH), rect(0, 0, IW, IH, "#000000", 0.3), (() => {
    const s = IH * 0.07;
    const x = IW * 0.05;
    const y = IH * 0.12;
    return [round(x, y, IW * 0.62, IH * 0.76, s * 0.5, "#141414", 0.85),
      round(x + s, y + s, s * 5, s * 7.5, s * 0.2, "#7A5A3C"),
      text("Sintel", x + s * 7, y + s * 2.2, s * 1.25, { weight: 500 }),
      text("2010 · 15 min · Animation", x + s * 7, y + s * 3.7, s * 0.8, { fill: A }),
      text("A lonely young woman", x + s * 7, y + s * 5.4, s * 0.8, { fill: "#D0D0D0" }),
      text("searches for her dragon.", x + s * 7, y + s * 6.7, s * 0.8, { fill: "#D0D0D0" }),
      circle(IW * 0.84, IH * 0.62, s * 2.6, "#000000", 0.55), ic("ic_key_24dp", IW * 0.84 - s * 1.7, IH * 0.62 - s * 1.7, s * 3.4, WHITE)];
  })()]],
  intro_gestures: [IW, IH, [film(IW, IH), rect(0, 0, IW, IH, "#000000", 0.2), (() => {
    const s = IH * 0.07;
    const out = [];
    out.push(shape(`M0,0H${IW * 0.3}C${IW * 0.36},${IH * 0.3} ${IW * 0.36},${IH * 0.7} ${IW * 0.3},${IH}H0Z`, "#FFFFFF", 0.16));
    for (let i = 0; i < 3; i++) {
      const x = IW * 0.1 + i * s * 1.2;
      out.push(shape(`M${x + s},${IH * 0.42}l${-s},${s * 0.8}l${s},${s * 0.8}z`, WHITE, 0.5 + i * 0.25));
    }
    out.push(text("10 seconds", IW * 0.15, IH * 0.68, s * 0.95, { anchor: "middle", weight: 500 }));
    out.push(text("2×", IW * 0.5, IH * 0.2, s * 1.2, { anchor: "middle", weight: 500 }));
    out.push(ic("ic_volume_up_24dp", IW * 0.82 - s * 1.2, IH * 0.4, s * 2.4, WHITE));
    out.push(text("120%", IW * 0.82, IH * 0.74, s * 1.2, { anchor: "middle", weight: 500, fill: "#FF6B6B" }));
    out.push(line(`M${IW * 0.95},${IH * 0.8}V${IH * 0.22}M${IW * 0.95 - 4},${IH * 0.22 + 5}l4,-5l4,5`, WHITE, 1.6));
    return out;
  })()]],
  intro_holds: [IW, IH, [player("classic", IW, IH, { progress: 0.33 }), (() => {
    const s = IH * 0.068;
    const rowY = IH * 0.915;
    const gearX = IW * 0.965 - IH * 0.075 - 3 * (IH * 0.075 * 1.75);
    const out = [ring(gearX + IH * 0.0375, rowY, IH * 0.065, A, 1.6)];
    const x = IW * 0.5;
    const y = IH * 0.25;
    const pw = IW * 0.44;
    out.push(round(x, y, pw, IH * 0.5, s * 0.4, "#2B2B2B", 0.97));
    out.push(text("Quick settings", x + s, y + s * 1.6, s * 0.95, { weight: 500 }));
    [["Speed", "Normal"], ["Audio delay", "0 ms"], ["Sleep timer", "Off"]].forEach(([name, value], i) => {
      out.push(text(name, x + s, y + s * 3.1 + i * s * 1.45, s * 0.85, { fill: "#E6E6E6" }),
        text(value, x + pw - s, y + s * 3.1 + i * s * 1.45, s * 0.85, { fill: A, anchor: "end" }));
    });
    return out;
  })()]],
  intro_settings: [IW, IH, (() => {
    const s = IH * 0.075;
    const out = [rect(0, 0, IW, IH, "#2F2F2F")];
    out.push(ic("ic_arrow_back_24dp", IW * 0.04, s * 0.6, s * 1.2, WHITE));
    out.push(text("Settings", IW * 0.04 + s * 2.4, s * 1.55, s * 1.05, { weight: 500 }));
    out.push(ic("ic_search_24dp", IW * 0.96 - s * 1.2, s * 0.6, s * 1.2, WHITE));
    const rows = [["ic_settings_appearance", "Appearance", "Orange · Classic"], ["ic_settings_home", "Home screen", "List · 10 in Recent"],
      ["ic_settings_playback", "Playback", "Auto engine · previews for all videos"], ["ic_settings_subtitles", "Subtitles", "Search in English"]];
    rows.forEach(([name, title, summary], i) => {
      const y = s * 3 + i * s * 2.35;
      out.push(ic(name, IW * 0.04, y, s * 1.2, A), text(title, IW * 0.04 + s * 2.4, y + s * 0.55, s * 0.9),
        text(summary, IW * 0.04 + s * 2.4, y + s * 1.55, s * 0.72, { fill: GREY }));
    });
    return out;
  })()],
};

const dir = process.argv[2];
const svgOut = {};
for (const [name, [w, h, items, pageOnly]] of Object.entries(pictures)) {
  if (!pageOnly) {
    fs.writeFileSync(`${dir}/${name}.xml`, k.android(w, h, items, { radius: Math.min(w, h) * 0.09 }));
  }
  svgOut[name] = k.svg(w, h, items, { radius: Math.min(w, h) * 0.09, id: name });
}
fs.writeFileSync(process.argv[3], JSON.stringify(svgOut));
console.log(Object.keys(pictures).length, "pictures");
