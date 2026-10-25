// Checks a translation against the English strings: every text there, no
// extras, the same placeholders, the plural forms the language needs, and
// the escaping Android's resource compiler insists on.
// Usage: node tools/l10n-check.js <folder suffix, e.g. de, pt-rBR, in>  (or "all")
const fs = require('fs');
const path = require('path');

const RES = path.join(__dirname, '..', 'app', 'src', 'main', 'res');
const LANGS = ['ar', 'de', 'es', 'fa', 'fr', 'hi', 'in', 'it', 'ja', 'ko', 'pl', 'pt-rBR',
  'ru', 'tr', 'uk', 'vi', 'zh-rCN', 'zh-rTW'];

const PLURALS = {
  ar: ['zero', 'one', 'two', 'few', 'many', 'other'],
  ru: ['one', 'few', 'many', 'other'], uk: ['one', 'few', 'many', 'other'],
  pl: ['one', 'few', 'many', 'other'],
  ja: ['other'], ko: ['other'], vi: ['other'], in: ['other'], 'zh-rCN': ['other'], 'zh-rTW': ['other'],
};
const pluralsFor = (lang) => PLURALS[lang] || ['one', 'other'];

function parse(file) {
  const xml = fs.readFileSync(file, 'utf8').replace(/<!--[^]*?-->/g, '');
  const strings = new Map();
  const plurals = new Map();
  const noTranslate = new Set();
  for (const m of xml.matchAll(/<string\s+name="([^"]+)"([^>]*?)(?:\/>|>([^]*?)<\/string>)/g)) {
    if (/translatable="false"/.test(m[2])) noTranslate.add(m[1]);
    strings.set(m[1], m[3] === undefined ? '' : m[3]);
  }
  for (const m of xml.matchAll(/<plurals\s+name="([^"]+)"[^>]*>([^]*?)<\/plurals>/g)) {
    const items = new Map();
    for (const i of m[2].matchAll(/<item\s+quantity="([^"]+)"\s*>([^]*?)<\/item>/g)) items.set(i[1], i[2]);
    plurals.set(m[1], items);
  }
  return { strings, plurals, noTranslate };
}

const holders = (text) => (text.replace(/%%/g, '').match(/%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc]/g) || [])
  .map((h) => h.replace(/^%(?!\d+\$)/, '%?$')).sort().join(' ');

function width(text) {
  const plain = text.replace(/<[^>]+>/g, '').replace(/\\[nt'"@?]/g, 'x')
    .replace(/%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc]/g, 'xxxx')
    .replace(/&(amp|lt|gt|quot|apos);/g, 'x');
  let w = 0;
  for (const ch of plain) {
    if (/\p{M}/u.test(ch)) continue;
    w += /[ᄀ-ᅟ⺀-꓏가-힣豈-﫿︰-﹏＀-｠￠-￦]/.test(ch) ? 1.8 : 1;
  }
  return w;
}

const MAIN = path.join(__dirname, '..', 'app', 'src', 'main');
const ROW_CODE = /(SetupServer|KeyCheck|SettingsActivity|SettingsSearch|SettingsHeaders|FoldersFragment|HistoryFragment|SubtitleAddonsFragment|ControlOrderActivity|ControlOrder|LicensesActivity|PictureListPreference|SketchSwitchPreference)\.(java|kt)$/;
const ROW_LAYOUT = /(recent_menu|pref_picture_card|item_control_order|settings_activity|AndroidManifest)\.xml$/;
const ROW_NAMES = new Set(['recent_pin', 'recent_unpin', 'recent_info', 'film_find', 'film_play_start', 'copy_link', 'intro_back', 'online_back',
  'recent_remove', 'recent_undo']);
let rooms = null;
function roomOf(name) {
  if (!rooms) {
    rooms = new Map();
    const rank = { free: 0, row: 1, tight: 2 };
    const note = (n, room) => { if (!rooms.has(n) || rank[room] > rank[rooms.get(n)]) rooms.set(n, room); };
    const walk = (dir) => {
      for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) { if (!/^values-|^drawable|^mipmap|^font|^raw/.test(e.name)) walk(p); continue; }
        if (!/\.(java|kt|xml)$/.test(e.name) || /strings\.xml$/.test(p)) continue;
        let previous = '';
        for (const line of fs.readFileSync(p, 'utf8').split('\n')) {
          const menuRow = /SubtitleChoice|AudioAction|AudioChoice|VideoChoice|ListPicker\.show/.test(previous + line);
          previous = line;
          for (const m of line.matchAll(/(?:@string\/|@plurals\/|R\.string\.|R\.plurals\.)(\w+)/g)) {
            if (menuRow && !/contentDescription|setContentDescription/.test(line)) { note(m[1], 'row'); continue; }
            let room = 'tight';
            if (/contentDescription|setContentDescription|announceForAccessibility/.test(line)) room = 'free';
            else if (/[\\/]xml[\\/]/.test(p) || /arrays\.xml$/.test(p) || ROW_CODE.test(p) || ROW_LAYOUT.test(p)) room = 'row';
            else if (/[\\/]osd[\\/]/.test(p) || /showText\(|Toast\.makeText/.test(line)) room = 'row';
            note(m[1], room);
          }
        }
      }
    };
    walk(MAIN);
  }
  if (ROW_NAMES.has(name) && rooms.get(name) !== 'free') return 'row';
  return rooms.get(name) || 'tight';
}

let titles = null;
function isTitle(name) {
  if (!titles) {
    titles = new Set();
    for (const dir of ['xml', 'layout']) {
      for (const f of fs.readdirSync(path.join(MAIN, 'res', dir))) {
        const xml = fs.readFileSync(path.join(MAIN, 'res', dir, f), 'utf8');
        for (const m of xml.matchAll(/(?:app|android):title="@string\/(\w+)"/g)) titles.add(m[1]);
      }
    }
  }
  return titles.has(name);
}

function budget(english, room, name) {
  const w = width(english);
  if (name && /^intro_\w+_title$/.test(name)) return Math.max(w, 24);
  if (name && room === 'row' && isTitle(name)) return Math.min(38, Math.max(Math.ceil(w * 1.15), 28, w));
  const sentence = w > 30 || /[.!?…:](\s|$)/.test(english.replace(/<[^>]+>/g, '').trim().slice(0, -1));
  if (room === 'free') return Math.max(Math.ceil(w * 2.5), 40);
  if (room === 'row') return sentence ? Math.ceil(w * 1.6) : Math.max(Math.ceil(w * 1.15), 28);
  return sentence ? Math.ceil(w * 1.35) : Math.max(Math.ceil(w * 1.15), w + 3);
}

function lengthProblem(name, english, translated) {
  const room = roomOf(name);
  const limit = budget(english, room, name);
  const w = width(translated);
  return w > limit ? `too long: ${w} wide, fits ${limit} (shown ${room === 'row' ? 'in a Settings row, list or menu' : room === 'free' ? 'to screen readers only' : 'in a tight spot'}; English "${english.slice(0, 60)}" is ${width(english)}); find a shorter way to say it` : null;
}

function escapingProblems(text) {
  const problems = [];
  const bare = text.replace(/<[^>]+>/g, '');
  const quoted = /^\s*".*"\s*$/s.test(bare);
  if (!quoted && /(^|[^\\])'/.test(bare)) problems.push("unescaped ' (write \\')");
  if (/^\s*[@?]/.test(bare) && !/^\s*@string\/\w+\s*$/.test(bare)) problems.push('starts with @ or ? (write \\@ or \\?)');
  if (/&(?!(amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)/.test(text)) problems.push('bare & (write &amp;)');
  if (/(^|[^\\])\\(?![nt'"\\@?u])/.test(bare)) problems.push('stray backslash');
  return problems;
}

function check(lang) {
  const en = parse(path.join(RES, 'values', 'strings.xml'));
  const file = path.join(RES, 'values-' + lang, 'strings.xml');
  if (!fs.existsSync(file)) return [`${lang}: no file at ${file}`];
  const tr = parse(file);
  const out = [];
  for (const [name, text] of en.strings) {
    if (en.noTranslate.has(name)) {
      if (tr.strings.has(name)) out.push(`${name}: marked not to translate in English; leave it out`);
      continue;
    }
    if (!tr.strings.has(name)) { out.push(`${name}: missing`); continue; }
    const t = tr.strings.get(name);
    if (holders(t) !== holders(text)) out.push(`${name}: placeholders "${holders(t)}" should be "${holders(text)}"`);
    for (const p of escapingProblems(t)) out.push(`${name}: ${p}`);
    if (!t.trim()) out.push(`${name}: empty`);
    const breaks = (s) => (s.match(/\\n/g) || []).length;
    if (!t.trim().startsWith('@string/') && breaks(t) !== breaks(text)) {
      out.push(`${name}: ${breaks(t)} line breaks (\\n) where the English has ${breaks(text)}; a real line break in the file shows as a space`);
    }
    const long = lengthProblem(name, text, t);
    if (long) out.push(`${name}: ${long}`);
  }
  for (const name of tr.strings.keys()) {
    if (!en.strings.has(name)) out.push(`${name}: not in the English file; remove it`);
  }
  for (const [name, items] of en.plurals) {
    const t = tr.plurals.get(name);
    if (!t) { out.push(`plurals ${name}: missing`); continue; }
    const want = holders(items.get('other'));
    for (const q of pluralsFor(lang)) {
      if (!t.has(q)) { out.push(`plurals ${name}: needs quantity "${q}"`); continue; }
      const h = holders(t.get(q));
      if (h !== want && !(['one', 'zero', 'two'].includes(q) && h === '')) {
        out.push(`plurals ${name} ${q}: placeholders "${h}" should be "${want}"`);
      }
      for (const p of escapingProblems(t.get(q))) out.push(`plurals ${name} ${q}: ${p}`);
      const long = lengthProblem(name, items.get("other"), t.get(q));
      if (long) out.push(`plurals ${name} ${q}: ${long}`);
    }
  }
  for (const name of tr.plurals.keys()) {
    if (!en.plurals.has(name)) out.push(`plurals ${name}: not in the English file; remove it`);
  }
  return out.map((line) => `${lang}: ${line}`);
}

if (process.argv[3] === '--rooms') {
  const en = parse(path.join(RES, 'values', 'strings.xml'));
  const tr = parse(path.join(RES, 'values-' + process.argv[2], 'strings.xml'));
  for (const [name, text] of en.strings) {
    if (en.noTranslate.has(name)) continue;
    const t = tr.strings.get(name) || '';
    console.log(`${name} | ${roomOf(name)} | limit ${budget(text, roomOf(name), name)} | now ${width(t)} | ${t}`);
  }
  process.exit(0);
}

const which = process.argv[2] === 'all' || !process.argv[2] ? LANGS : [process.argv[2]];
let problems = 0;
for (const lang of which) {
  const out = check(lang);
  problems += out.length;
  out.forEach((line) => console.log(line));
  if (!out.length) console.log(`${lang}: OK`);
}
process.exit(problems ? 1 : 0);
