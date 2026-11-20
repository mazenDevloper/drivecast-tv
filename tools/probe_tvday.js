// what the TV lists as today's matches, from where, and when they really are (run on GitHub's servers)
const { chromium } = require('playwright'); const path = require('path');
(async () => {
  const b = await chromium.launch({ args: ['--disable-web-security', '--allow-file-access-from-files'] });
  const p = await (await b.newContext({ viewport: { width: 1920, height: 1080 } })).newPage();
  p.on('console', m => { if (/^PROBE/.test(m.text())) console.log(m.text()); });
  await p.goto('file://' + path.resolve(__dirname, '../app/src/main/assets/home/index.html')); await p.waitForTimeout(6000);
  const r = await p.evaluate(async () => {
    const om = t => new Date(t * 1000 + 4 * 3600e3).toISOString().replace('T', ' ').slice(5, 16);
    const out = [];
    const ww = await winwinDay(0); out.push('winwin raw today: ' + ww.matches.length);
    const all = await mFetch('all'); const mine = await mFetch('mine');
    out.push('after dayFix: ' + all.matches.length + ' · mine (server): ' + ((mine && mine.matches) || []).length);
    for (const m of ((mine && mine.matches) || [])) out.push('MINE  ' + om(m.timestamp) + ' | ' + (m.league?.name || '') + ' | ' + m.home?.name + ' - ' + m.away?.name);
    MTAB = 'all'; await loadMatches();
    for (const m of MLIST) out.push(`LIST  ${om(m.timestamp)} | ${m.ww ? 'ww' : ''}${m.s365 ? '+365' : ''}${m.src365 ? '365only' : ''}${m.mine ? ' mine' : ''} | ${m.league?.name || ''} | ${m.home?.name} - ${m.away?.name}`);
    return out;
  });
  console.log(r.join('\n'));
  // the raw winwin rows for the English league
  const raw = await p.evaluate(async () => { const t = new Date(Date.now() + 4 * 3600e3 - 5 * 3600e3), ymd = t.toISOString().slice(0, 10).replace(/-/g, '');
    const j = await getJ(`https://www.winwin.com/api/component_data/match_landing_section/82053?path=%2Fmatches&v=01&date=${ymd}`); const o = [];
    (function walk(x) { if (Array.isArray(x)) return x.forEach(walk); if (x && typeof x === 'object') { if (x.team1 && x.team2) o.push(x); else Object.values(x).forEach(walk); } })(j);
    return o.filter(m => /الإنجليزي|إنجلترا|البريميرليج/.test(JSON.stringify(m.league || m.tournament || ''))).map(m => `WWRAW ${m.date} ${m.kickoffTime} ${m.status} | ${m.team1?.name} - ${m.team2?.name}`); });
  console.log(raw.join('\n'));
  await b.close();
})();
