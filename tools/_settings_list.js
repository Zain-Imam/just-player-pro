// Every setting a search can find, read from the same file Settings is built
// from: page key, page title, setting key, setting title, tab-separated.
// node tools/_settings_list.js <res dir>
const fs = require("fs");
const path = require("path");
const res = process.argv[2];
const xml = fs.readFileSync(path.join(res, "xml", "root_preferences.xml"), "utf8");
const stringsXml = fs.readFileSync(path.join(res, "values", "strings.xml"), "utf8");

const strings = {};
for (const m of stringsXml.matchAll(/<string name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g)) {
  strings[m[1]] = m[2].replace(/\\'/g, "'").replace(/\\"/g, '"').replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">");
}
const text = (v) => (v && v.startsWith("@string/") ? strings[v.slice(8)] : v);

let screen = null;
let screenTitle = null;
for (const m of xml.matchAll(/<([A-Za-z.]+)\b([^>]*?)(\/?)>/g)) {
  const [, tag, attrs] = m;
  const attr = (name) => (attrs.match(new RegExp(`app:${name}="([^"]*)"`)) || [])[1];
  if (tag === "PreferenceScreen") {
    if (attr("key")) {
      screen = attr("key");
      screenTitle = text(attr("title"));
    }
    continue;
  }
  if (tag === "PreferenceCategory" || !screen) {
    continue;
  }
  const key = attr("key");
  const title = text(attr("title"));
  if (key && title) {
    console.log([screen, screenTitle, key, title].join("\t"));
  }
}
