// 🖼️ the guide's programme pictures, checked the way the TV shows them: each picture host is tried in a real browser
// (Playwright's Chromium, a page with <img> tags and no referrer, like the TV's WebView) with a few of its pictures; a host whose
// pictures don't load (blocked, gone, a login page) is dropped from epg.json, and so are placeholders («no-img», «default»)
// and broken addresses (sat.tv writes «https://sat.tvhttps://…»). Usage: node tools/imgcheck.js out/epg.json
const fs = require('fs');
const { chromium } = require('playwright');
const file = process.argv[2] || 'epg.json';
const d = JSON.parse(fs.readFileSync(file, 'utf8'));
const PLACE = /no-?img|placeholder|default[_-]?(image|img|poster)|blank[_-]photo|prog-\d+\.svg|\/imgs\/site\//i;
const fix = u => {
  if (!u) return '';
  u = String(u).trim();
  const m = u.match(/^https?:\/\/[^/]+(https?:\/\/.+)$/); if (m) u = m[1];   // «https://sat.tvhttps://www.sat.tv/…»
  if (u.startsWith('//')) u = 'https:' + u;
  if (!/^https?:\/\//.test(u) || PLACE.test(u)) return '';
  return u;
};
const host = u => { try { return new URL(u).host; } catch (e) { return ''; } };
const byHost = new Map();
for (const rows of Object.values(d.c)) for (const r of rows) {
  if (r.length < 4) continue;
  r[3] = fix(r[3]);
  if (!r[3]) { r.length = 3; continue; }
  const h = host(r[3]); if (!byHost.has(h)) byHost.set(h, []); byHost.get(h).push(r[3]);
}
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage();
  const bad = new Set(); let tried = 0;
  const hosts = [...byHost.entries()];
  for (let i = 0; i < hosts.length; i += 25) {   // 25 hosts a page, up to 5 pictures each
    const chunk = hosts.slice(i, i + 25).map(([h, us]) => [h, [...new Set(us)].sort(() => Math.random() - .5).slice(0, 5)]);
    tried += chunk.length;
    await p.setContent('<meta name="referrer" content="no-referrer"><body></body>');
    const res = await p.evaluate(async chunk => {
      const one = u => new Promise(ok => { const im = new Image(); const t = setTimeout(() => ok(false), 12000); im.onload = () => { clearTimeout(t); ok(im.naturalWidth > 16); }; im.onerror = () => { clearTimeout(t); ok(false); }; im.referrerPolicy = 'no-referrer'; im.src = u; });
      return Promise.all(chunk.map(async ([h, us]) => [h, (await Promise.all(us.map(one))).filter(Boolean).length, us.length]));
    }, chunk);
    for (const [h, okN, n] of res) if (okN * 2 < n) bad.add(h);
  }
  await b.close();
  let dropped = 0, kept = 0;
  for (const rows of Object.values(d.c)) for (const r of rows) if (r.length > 3) { if (bad.has(host(r[3]))) { r.length = 3; dropped++; } else kept++; }
  fs.writeFileSync(file, JSON.stringify(d));
  console.log(`🖼️ ${tried} picture hosts checked in Chromium: ${bad.size} don't load (${[...bad].slice(0, 20).join(', ')}) — ${dropped} pictures dropped, ${kept} kept`);
})().catch(e => { console.log('::warning::picture check failed: ' + e.message); process.exit(0); });
