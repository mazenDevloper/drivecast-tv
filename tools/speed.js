// ⚡ the remote's speed, checked on every change of the home page: the time from an arrow key to the next drawn frame,
// on a CPU slowed 4× (about a TV box). Fails above SPEED_MS (default 80 ms; Apple's rule is 100 ms for any press).
const { chromium } = require('playwright');
const path = require('path');
const LIMIT = +(process.env.SPEED_MS || 80);
(async () => {
  const b = await chromium.launch();
  const p = await (await b.newContext({ viewport: { width: 1920, height: 1080 } })).newPage();
  const errs = []; p.on('pageerror', e => errs.push(e.message));
  await p.route('**/*', r => r.request().url().startsWith('file:') ? r.continue() : r.abort());
  await p.addInitScript(() => { window.__k = []; document.addEventListener('keydown', () => { const t0 = performance.now(); requestAnimationFrame(() => requestAnimationFrame(() => window.__k.push(performance.now() - t0))); }, true); });
  const cdp = await p.context().newCDPSession(p); await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
  await p.goto('file://' + path.resolve(__dirname, '../app/src/main/assets/home/index.html')); await p.waitForTimeout(5000);
  const keys = ['ArrowDown', 'ArrowRight', 'ArrowRight', 'ArrowLeft', 'ArrowDown', 'ArrowUp', 'ArrowLeft', 'ArrowRight'];   // (▲ twice opens the photo view: a mode change, not a step)
  for (const page of ['home', 'iptv', 'matches']) {
    await p.evaluate(pg => window.show && show(pg), page); await p.waitForTimeout(2000);
    for (const k of keys) { await p.keyboard.press(k); await p.waitForTimeout(250); }
  }
  await p.waitForTimeout(500);
  const t = (await p.evaluate(() => window.__k)).sort((a, b) => a - b), p95 = t[Math.floor(t.length * 0.95)] || 0;
  console.log('all', (await p.evaluate(() => window.__k.map(Math.round))).join(','));
  console.log(`keys ${t.length} · median ${Math.round(t[t.length >> 1])} ms · p95 ${Math.round(p95)} ms · limit ${LIMIT} ms`);
  if (errs.length) console.log('page errors:', errs.slice(0, 5).join(' | '));
  await b.close();
  if (errs.length || p95 > LIMIT) process.exit(1);
})();
