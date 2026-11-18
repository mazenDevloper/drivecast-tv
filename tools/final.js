// ✅ «the finished product» check, on every change of the home page (the study's nine criteria, the ones a machine can see):
// no dead links, no test pages, no developer words or broken values on screen, no emoji in the tabs, at most 6 tabs,
// every list says something when empty, and nothing technical outside «وضع المطوّر». Fails the build when one breaks.
const { chromium } = require('playwright');
const path = require('path');
const JARGON = /\b(HEVC|H\.?264|H\.?265|bit-?10|Sovereign|Premium|Hub|undefined|NaN|null|\[object|TODO|FIXME|debug|test)\b/i;
const EMOJI = /\p{Extended_Pictographic}/u;
(async () => {
  const b = await chromium.launch(), bad = [];
  const p = await (await b.newContext({ viewport: { width: 1920, height: 1080 } })).newPage();
  p.on('pageerror', e => bad.push('page error: ' + e.message));
  await p.route('**/*', r => r.request().url().startsWith('file:') ? r.continue() : r.abort());
  await p.goto('file://' + path.resolve(__dirname, '../app/src/main/assets/home/index.html')); await p.waitForTimeout(4000);
  for (const pg of ['home', 'iptv', 'matches', 'quran', 'subs', 'apps']) {
    await p.evaluate(x => window.show && show(x), pg).catch(() => {}); await p.waitForTimeout(1200);
    const r = await p.evaluate(({ J, E }) => {
      const J2 = new RegExp(J, 'i'), E2 = new RegExp(E, 'u'), out = [];
      const vis = el => { if (el.closest('.dev,[hidden]')) return false; const s = getComputedStyle(el), r = el.getBoundingClientRect(); return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0; };
      for (const a of document.querySelectorAll('a[href="#"],a[href=""]')) if (vis(a)) out.push('dead link: ' + a.textContent.trim().slice(0, 40));
      const tabs = [...document.querySelectorAll('#tabs > button, #tabs .tab')].filter(vis);
      if (tabs.length > 6) out.push('too many tabs: ' + tabs.length);
      for (const t of tabs) if (E2.test(t.textContent)) out.push('emoji in a tab: ' + t.textContent.trim());
      const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      for (let n; (n = w.nextNode());) { const t = n.textContent.trim(); if (!t || !n.parentElement || /^(SCRIPT|STYLE)$/.test(n.parentElement.tagName) || !vis(n.parentElement)) continue; const m = t.match(J2); if (m) out.push(`developer word «${m[0]}» in: ${t.slice(0, 60)}`); }
      for (const s of document.querySelectorAll('.shelf,.grid,.appgrid')) if (vis(s) && !s.children.length) out.push('an empty list with no message: #' + (s.id || s.className));
      return out;
    }, { J: JARGON.source, E: EMOJI.source });
    for (const x of r) bad.push(`[${pg}] ${x}`);
  }
  const uniq = [...new Set(bad)];
  console.log(uniq.length ? uniq.join('\n') : 'all good ✅');
  await b.close(); if (uniq.length) process.exit(1);
})();
