const { chromium } = require('playwright'); const path = require('path');
(async () => {
  const b = await chromium.launch({ args: ['--disable-web-security', '--allow-file-access-from-files'] });
  const p = await (await b.newContext({ viewport: { width: 1920, height: 1080 } })).newPage();
  p.on('pageerror', e => console.log('PAGEERR', e.message));
  await p.goto('file://' + path.resolve(__dirname, '../app/src/main/assets/home/index.html')); await p.waitForTimeout(6000);
  const r = await p.evaluate(async () => {
    const om = t => new Date(t * 1000 + 4 * 3600e3).toISOString().replace('T', ' ').slice(5, 16), out = [];
    const b = await s365Day(0); out.push('s365 today: ' + b.length + ' · far: ' + b.filter(x => /الياباني الممتاز|الكوري|الأسترالي/.test(x.league.name)).length);
    const all = await mFetch('all'); out.push('mFetch all: ' + all.matches.length);
    for (const m of all.matches.filter(m => /الياباني|الكوري|الأسترالي|سيدات/.test(m.league?.name || ''))) out.push('FAR ' + om(m.timestamp) + ' ' + m.status + ' | ' + m.league.name + ' | ' + m.home?.name + ' - ' + m.away?.name);
    MTAB = 'all'; await loadMatches(); out.push('MLIST: ' + MLIST.length);
    for (const m of MLIST.filter(m => /الياباني|الكوري|الأسترالي/.test(m.league?.name || ''))) out.push('IN LIST ' + om(m.timestamp) + ' ' + m.status + ' | ' + m.home?.name);
    return out;
  });
  console.log(r.join('\n')); await b.close();
})();
