#!/usr/bin/env node
// 🪶 DriveCast TV Lite, zero-bloat pages: the home and the phone remote as the APK carries them — comments and
// whitespace out, the scripts and styles minified (terser, clean-css), and what Lite never runs left out (the widgets cw/,
// the sports glass-clock mode's font). Written to app/src/lite/assets/…, which the lite build takes over the main files.
// The full edition and the web keep the readable source (app/src/main/assets). Run before the Lite build:
//   npm i --no-save html-minifier-terser@7.2.0 && node tools/lite.js
// Top-level names are never renamed: the Java side calls the page's functions by name (loadSet, remindLoop, onRemote…).
const fs = require('fs'), path = require('path');
const { minify } = require('html-minifier-terser');
const root = path.resolve(__dirname, '..');
const OPT = {
  collapseWhitespace: true, conservativeCollapse: true, removeComments: true, minifyCSS: { level: 1 },
  minifyJS: { compress: { passes: 2, drop_debugger: true, toplevel: false }, mangle: { toplevel: false }, format: { comments: false } },
  keepClosingSlash: true, caseSensitive: true
};
(async () => {
  const jobs = [['home/index.html', h => h
      .replace(/^<link rel="stylesheet" href="cw\/[^"]+">\n/gm, '')
      .replace(/^<script src="cw\/[^"]+"><\/script>\n/gm, '')
      .replace(/@font-face\{font-family:"Antonio"[^}]*\}/, '')],
    ['phone/index.html', h => h]];
  for (const [f, prep] of jobs) {
    const src = fs.readFileSync(path.join(root, 'app/src/main/assets', f), 'utf8');
    const out = await minify(prep(src), OPT);
    const to = path.join(root, 'app/src/lite/assets', f);
    fs.mkdirSync(path.dirname(to), { recursive: true });
    fs.writeFileSync(to, out);
    console.log(`${f}: ${(src.length / 1024).toFixed(0)} KB → ${(out.length / 1024).toFixed(0)} KB`);
  }
})().catch(e => { console.error(e); process.exit(1); });
