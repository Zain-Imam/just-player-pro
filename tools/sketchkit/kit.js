// Drawing kit for the Settings and introduction pictures: the app's own icons
// (read from its drawables and Media3's), lettering in Roboto turned into
// shapes, and one picture written both as an Android vector and as SVG.
const fs = require("fs");
const path = require("path");
const opentype = require("opentype.js");

const APP_RES = path.join(__dirname, "..", "..", "app", "src", "main", "res", "drawable");
const LIB_RES = path.join(__dirname, ".libui", "res", "drawable-anydpi-v21");
const FONTS = {
  400: opentype.loadSync(path.join(__dirname, "roboto-400.ttf")),
  500: opentype.loadSync(path.join(__dirname, "roboto-500.ttf")),
};
const ACCENT = "ACCENT";

const iconCache = {};
function icon(name) {
  if (!iconCache[name]) {
    const file = [path.join(APP_RES, name + ".xml"), path.join(LIB_RES, name + ".xml")].find((f) => fs.existsSync(f));
    if (!file) {
      throw new Error("no icon " + name);
    }
    const xml = fs.readFileSync(file, "utf8");
    const size = Number((xml.match(/android:viewportWidth="([\d.]+)"/) || [])[1] || 24);
    const paths = [...xml.matchAll(/<path\b([\s\S]*?)\/>/g)]
      .filter((m) => /android:fillColor="(?!#00)/.test(m[1]))
      .map((m) => ({
        d: (m[1].match(/android:pathData="([^"]+)"/) || [])[1],
        evenOdd: /fillType="evenOdd"/.test(m[1]),
      })).filter((p) => p.d);
    iconCache[name] = { size, paths };
  }
  return iconCache[name];
}

const rect = (x, y, w, h, fill, alpha) => ({ d: `M${x},${y}h${w}v${h}h${-w}z`, fill, alpha });
const round = (x, y, w, h, r, fill, alpha) => {
  r = Math.min(r, w / 2, h / 2);
  return { d: `M${x + r},${y}h${w - 2 * r}a${r},${r} 0 0 1 ${r},${r}v${h - 2 * r}a${r},${r} 0 0 1 ${-r},${r}h${-(w - 2 * r)}a${r},${r} 0 0 1 ${-r},${-r}v${-(h - 2 * r)}a${r},${r} 0 0 1 ${r},${-r}z`, fill, alpha };
};
const circle = (cx, cy, r, fill, alpha) => ({ d: `M${cx - r},${cy}a${r},${r} 0 1 1 ${2 * r},0a${r},${r} 0 1 1 ${-2 * r},0z`, fill, alpha });
const ring = (cx, cy, r, stroke, width, alpha) => ({ d: `M${cx - r},${cy}a${r},${r} 0 1 1 ${2 * r},0a${r},${r} 0 1 1 ${-2 * r},0z`, stroke, width, alpha });
const shape = (d, fill, alpha) => ({ d, fill, alpha });
const line = (d, stroke, width, alpha) => ({ d, stroke, width, alpha, cap: true });

function ic(name, x, y, size, fill, alpha) {
  const i = icon(name);
  return { group: { tx: x, ty: y, s: size / i.size }, items: i.paths.map((p) => ({ d: p.d, fill, alpha, evenOdd: p.evenOdd })) };
}

function text(str, x, y, size, opts = {}) {
  const font = FONTS[opts.weight || 400];
  const width = font.getAdvanceWidth(str, size);
  const left = opts.anchor === "end" ? x - width : opts.anchor === "middle" ? x - width / 2 : x;
  const d = font.getPath(str, left, y, size).toPathData(2);
  return { d, fill: opts.fill || "#FFFFFF", alpha: opts.alpha };
}
const textWidth = (str, size, weight = 400) => FONTS[weight].getAdvanceWidth(str, size);

const flat = (items) => items.flat(Infinity).filter(Boolean);

function androidColor(c) {
  if (c === ACCENT) {
    return "?attr/accentColor";
  }
  return c.length === 7 ? "#FF" + c.slice(1) : c;
}
function androidItem(p, indent) {
  if (p.group) {
    const g = p.group;
    return `${indent}<group\n${indent}    android:translateX="${+g.tx.toFixed(3)}"\n${indent}    android:translateY="${+g.ty.toFixed(3)}"\n${indent}    android:scaleX="${+g.s.toFixed(4)}"\n${indent}    android:scaleY="${+g.s.toFixed(4)}">\n`
      + p.items.map((q) => androidItem(q, indent + "    ")).join("\n") + `\n${indent}</group>`;
  }
  let s = `${indent}<path\n${indent}    android:pathData="${p.d}"`;
  if (p.fill) {
    s += `\n${indent}    android:fillColor="${androidColor(p.fill)}"`;
    if (p.alpha !== undefined) {
      s += `\n${indent}    android:fillAlpha="${p.alpha}"`;
    }
    if (p.evenOdd) {
      s += `\n${indent}    android:fillType="evenOdd"`;
    }
  }
  if (p.stroke) {
    s += `\n${indent}    android:strokeColor="${androidColor(p.stroke)}"\n${indent}    android:strokeWidth="${p.width}"`;
    if (p.alpha !== undefined) {
      s += `\n${indent}    android:strokeAlpha="${p.alpha}"`;
    }
    if (p.cap) {
      s += `\n${indent}    android:strokeLineCap="round"\n${indent}    android:strokeLineJoin="round"`;
    }
  }
  return s + " />";
}
function android(w, h, items, opts = {}) {
  const clip = opts.radius ? round(0, 0, w, h, opts.radius).d : `M0,0h${w}v${h}h${-w}z`;
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- drawn by the Settings sketch kit from the app's own icons; edit the kit, not this file -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${w}dp"
    android:height="${h}dp"
    android:viewportWidth="${w}"
    android:viewportHeight="${h}">
    <group>
        <clip-path android:pathData="${clip}" />
${flat(items).map((p) => androidItem(p, "        ")).join("\n")}
    </group>
</vector>
`;
}

function svgItem(p) {
  if (p.group) {
    const g = p.group;
    return `<g transform="translate(${+g.tx.toFixed(3)} ${+g.ty.toFixed(3)}) scale(${+g.s.toFixed(4)})">${p.items.map(svgItem).join("")}</g>`;
  }
  let a = ` d="${p.d}"`;
  if (p.fill) {
    a += p.fill === ACCENT ? ` class="acc"` : ` fill="${p.fill}"`;
    if (p.alpha !== undefined) {
      a += ` fill-opacity="${p.alpha}"`;
    }
    if (p.evenOdd) {
      a += ` fill-rule="evenodd"`;
    }
  } else {
    a += ` fill="none"`;
  }
  if (p.stroke) {
    a += p.stroke === ACCENT ? ` class="acc-line"` : ` stroke="${p.stroke}"`;
    a += ` stroke-width="${p.width}"`;
    if (p.alpha !== undefined) {
      a += ` stroke-opacity="${p.alpha}"`;
    }
    if (p.cap) {
      a += ` stroke-linecap="round" stroke-linejoin="round"`;
    }
  }
  return `<path${a}/>`;
}
function svg(w, h, items, opts = {}) {
  const id = "clip-" + (opts.id || "x");
  const clip = opts.radius ? round(0, 0, w, h, opts.radius).d : `M0,0h${w}v${h}h${-w}z`;
  return `<svg viewBox="0 0 ${w} ${h}" xmlns="http://www.w3.org/2000/svg" aria-hidden="true"><defs><clipPath id="${id}"><path d="${clip}"/></clipPath></defs><g clip-path="url(#${id})">${flat(items).map(svgItem).join("")}</g></svg>`;
}

module.exports = { ACCENT, icon, rect, round, circle, ring, shape, line, ic, text, textWidth, flat, android, svg };
