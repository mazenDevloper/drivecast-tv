// DriveCast TV on the web: the TV's own home page (the same file), its bridge `TV` answered by the TV box through the hub.
// What it shows is the TV's: its board, its phrase, its settings, its city, its apps, its font. Changes made here are saved on
// the TV, and changes made on the TV show here at once. Playing (a channel, a surah, YouTube, an app) happens on the TV —
// or, with «هنا», in this browser.
(async function () {
  const L = window.DCTV, ls = L.ls;
  const $ = s => document.querySelector(s);

  // the page is drawn for a 1920×1080 screen: scaled to the window
  const fit = () => { document.body.style.zoom = Math.min(innerWidth / 1920, innerHeight / 1080); };
  fit(); addEventListener('resize', fit);

  // ---- the bar: the TV's state, where things play, the remote
  const bar = document.createElement('div');
  bar.setAttribute('dir', 'rtl');
  bar.style.cssText = 'position:fixed;left:14px;bottom:14px;z-index:2147482000;display:flex;gap:8px;align-items:center;padding:8px;border-radius:999px;background:rgba(12,17,22,.78);border:1px solid rgba(255,255,255,.14);backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);font:600 14px system-ui,-apple-system,"SF Arabic",Tahoma,sans-serif;color:#f4f1ea;box-shadow:0 10px 30px rgba(0,0,0,.35)';
  const pill = 'border:0;border-radius:999px;padding:8px 14px;font:inherit;cursor:pointer;background:rgba(255,255,255,.08);color:inherit';
  bar.innerHTML = `<span id="wState" style="padding:0 8px">…</span>
    <span style="display:flex;background:rgba(255,255,255,.06);border-radius:999px;padding:3px">
      <button id="wTv" style="${pill}">📺 على التلفزيون</button><button id="wHere" style="${pill}">💻 هنا</button></span>
    <a id="wRemote" href="remote/" style="${pill};text-decoration:none">🎮 الريموت</a>
    <button id="wPin" style="${pill}" title="رمز التلفزيون">🔑</button>`;
  document.documentElement.appendChild(bar);
  let MODE = ls.get('dctv-mode') || (L.paired() ? 'tv' : 'here'), CLOUD = false;
  const paint = () => {
    const live = L.open && L.tv && L.okd;
    bar.querySelector('#wState').textContent = !L.paired() ? '💻 بدون تلفزيون' : live ? '🟢 ' + (L.tvName || 'التلفزيون') : L.open ? (CLOUD ? '☁️ من السحابة · التلفزيون مطفّي' : '⚪ التلفزيون مو متصل') : '⚪ يتصل…';
    bar.querySelector('#wPin').textContent = L.paired() && !L.bad ? '🔗' : '🔗 اربط بالتلفزيون';
    bar.querySelector('#wTv').style.background = MODE === 'tv' ? '#3ddc97' : 'transparent';
    bar.querySelector('#wTv').style.color = MODE === 'tv' ? '#06281b' : 'inherit';
    bar.querySelector('#wHere').style.background = MODE === 'here' ? '#3ddc97' : 'transparent';
    bar.querySelector('#wHere').style.color = MODE === 'here' ? '#06281b' : 'inherit';
  };
  bar.querySelector('#wTv').onclick = () => { MODE = 'tv'; ls.set('dctv-mode', MODE); paint(); };
  bar.querySelector('#wHere').onclick = () => { MODE = 'here'; ls.set('dctv-mode', MODE); paint(); };
  bar.querySelector('#wPin').onclick = async () => {
    await L.pair();   // goes to the TV and comes back paired (or was cancelled)
  };
  L.on('state', paint); paint();

  const toast = t => {
    const d = document.createElement('div'); d.textContent = t; d.setAttribute('dir', 'rtl');
    d.style.cssText = 'position:fixed;left:50%;bottom:80px;transform:translateX(-50%);z-index:2147482500;padding:12px 22px;border-radius:999px;background:rgba(12,17,22,.9);color:#fff;font:600 16px system-ui,"SF Arabic",Tahoma,sans-serif;border:1px solid rgba(255,255,255,.14)';
    document.documentElement.appendChild(d); setTimeout(() => d.remove(), 2600);
  };

  // ---- what the TV knows, in one answer (kept for the next visit, so the page shows even when the TV is off)
  const cover = document.createElement('div');
  cover.style.cssText = 'position:fixed;inset:0;z-index:2147481000;display:grid;place-items:center;background:#0a0e13;color:#cfd8d2;font:600 20px system-ui,"SF Arabic",Tahoma,sans-serif';
  cover.textContent = 'DriveCast TV · يتصل بالتلفزيون…';
  document.documentElement.appendChild(cover);
  let S = null;
  try { S = JSON.parse(ls.get('dctv-snap') || 'null'); } catch (e) {}
  // the TV's copy in the cloud: when the TV is off, the page works from it and saves to it
  async function cloudLoad() {
    if (!L.paired() || !(await L.hubReady())) return null;
    let r; try { r = await L.store('dctv-get'); } catch (e) { return null; }
    if (!r.ok) return null;   // a changed code: «🔗 اربط» again when the TV is on
    try { return r.data ? Object.assign(JSON.parse(r.data), { at: r.at }) : null; } catch (e) { return null; }
  }
  if (await L.gate()) {
    try {
      S = await L.call('snapshot', [], 30000);
      try { ls.set('dctv-snap', JSON.stringify(Object.assign({}, S, { font: '' }))); } catch (e) {}
    } catch (e) { toast('التلفزيون ما ردّ — أعرض آخر نسخة'); }
  } else {
    const c = await cloudLoad();
    if (c) { S = Object.assign(S || {}, { board: c.board, phrase: c.phrase, prefs: JSON.stringify(c.prefs || {}), prayers: c.prayers || (S && S.prayers), apps: c.apps || (S && S.apps) }); CLOUD = true; toast('☁️ التلفزيون مطفّي — تشتغل من السحابة، وتعديلاتك توصله لما يشتغل'); }
    else if (S && L.paired()) toast('التلفزيون مو متصل — أعرض آخر نسخة');
  }
  S = Object.assign({ apps: '[]', prayers: '', prefs: '{}', board: '', phrase: '', font: '', mouse: '{}', canOverlay: false }, S || {});
  paint();
  if (ls.get('dctv-justpaired')) { ls.set('dctv-justpaired', ''); toast(L.okd ? '✅ انربط بالتلفزيون' : '✅ انربط — يتصل بالتلفزيون…'); }
  if (L.bad) toast('الربط القديم ما عاد يشتغل — اضغط «🔗 اربط بالتلفزيون» وأنت في البيت');
  // no TV and no copy yet: today's prayer times from the Internet (Salalah, or the city in the saved settings)
  if (!S.prayers || S.prayers === '{}') {
    try {
      const d = new Date(), f = x => `${String(x.getDate()).padStart(2, '0')}-${String(x.getMonth() + 1).padStart(2, '0')}-${x.getFullYear()}`, t = new Date(d.getTime() + 864e5);
      const get = async x => { const j = await (await fetch(`https://api.aladhan.com/v1/timings/${f(x)}?latitude=17.0158&longitude=54.0885&method=8`)).json(); const m = j.data.timings; return ['Fajr', 'Dhuhr', 'Asr', 'Maghrib', 'Isha'].map(k => m[k].slice(0, 5)); };
      S.prayers = JSON.stringify({ today: await get(d), tomorrow: await get(t), city: 'صلالة', lat: 17.0158, lon: 54.0885, method: 8 });
    } catch (e) {}
  }
  const keep = () => { try { ls.set('dctv-snap', JSON.stringify(Object.assign({}, S, { font: '' }))); } catch (e) {} };

  const onTv = () => MODE === 'tv' && L.open && L.tv && L.okd;
  // with the TV off, a change goes to the cloud copy (the TV takes it when it is back)
  let putT = 0;
  const cloudSave = () => {
    keep();
    if (L.tv && L.okd || !L.paired()) return;   // the TV saves its own; not paired: this browser only   // the TV is on: it saves its own copy
    clearTimeout(putT);
    putT = setTimeout(() => {
      let prefs = {}; try { prefs = JSON.parse(S.prefs || '{}'); } catch (e) {}
      L.store('dctv-put', { data: JSON.stringify({ board: S.board, phrase: S.phrase, prefs, prayers: S.prayers, apps: S.apps }) })
        .then(r => toast(r.ok ? '☁️ انحفظ — يوصل التلفزيون لما يشتغل' : 'ما انحفظ')).catch(() => toast('ما انحفظ'));
    }, 1200);
  };
  const send = (name, args, note) => L.call(name, args || []).then(v => { if (note) toast(note); return v; }).catch(e => toast(e.message === 'offline' ? 'التلفزيون مو متصل' : 'ما وصل للتلفزيون'));

  // ---- the browser's own players («هنا»)
  let player = null;
  function overlay(inner) {
    closePlayer();
    player = document.createElement('div');
    player.style.cssText = 'position:fixed;inset:0;z-index:2147481500;background:#000;display:grid;place-items:center';
    player.innerHTML = inner + '<button id="wClose" style="position:absolute;top:16px;left:16px;border:0;border-radius:999px;padding:10px 16px;background:rgba(255,255,255,.14);color:#fff;font:600 16px system-ui;cursor:pointer">✕</button><div id="wName" dir="rtl" style="position:absolute;top:18px;right:20px;color:#fff;font:700 20px system-ui,\'SF Arabic\',Tahoma;text-shadow:0 2px 8px #000"></div>';
    player.querySelector('#wClose').onclick = closePlayer;
    document.documentElement.appendChild(player);
    return player;
  }
  function closePlayer() { if (!player) return; const v = player.querySelector('video'); if (v) { v.pause(); v.removeAttribute('src'); v.load(); } if (player.hls) player.hls.destroy(); player.remove(); player = null; }
  const hlsReady = () => window.Hls ? Promise.resolve() : new Promise((ok, no) => { const s = document.createElement('script'); s.src = 'https://cdnjs.cloudflare.com/ajax/libs/hls.js/1.5.15/hls.min.js'; s.onload = ok; s.onerror = no; document.head.appendChild(s); });
  async function playHere(urls, names, i) {
    const p = overlay('<video controls autoplay playsinline style="width:100%;height:100%;background:#000"></video>');
    p.urls = urls; p.names = names; p.i = i;
    const go = async () => {
      const u = p.urls[p.i], v = p.querySelector('video');
      p.querySelector('#wName').textContent = (p.i + 1) + ' · ' + (p.names[p.i] || '');
      if (p.hls) { p.hls.destroy(); p.hls = null; }
      if (location.protocol === 'https:' && u.startsWith('http:')) { toast('هالبث ما يشتغل في المتصفح — شغّله على التلفزيون'); return; }
      if (/\.m3u8(\?|$)/i.test(u) && !v.canPlayType('application/vnd.apple.mpegurl')) {
        try { await hlsReady(); p.hls = new Hls(); p.hls.loadSource(u); p.hls.attachMedia(v); p.hls.on(Hls.Events.ERROR, (e, d) => { if (d.fatal) toast('القناة ما اشتغلت هنا — جرّبها على التلفزيون'); }); }
        catch (e) { v.src = u; }
      } else v.src = u;
      v.play().catch(() => {});
    };
    p.zap = d => { p.i = (p.i + d + p.urls.length) % p.urls.length; go(); };
    go();
  }
  function playYt(id) { overlay(`<iframe src="https://www.youtube.com/embed/${encodeURIComponent(id)}?autoplay=1" allow="autoplay; fullscreen; encrypted-media" allowfullscreen style="width:100%;height:100%;border:0"></iframe>`); }
  addEventListener('keydown', e => {
    if (!player) return;
    if (e.key === 'Escape') closePlayer();
    else if (player.zap && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) player.zap(e.key === 'ArrowUp' ? 1 : -1);
    else return;
    e.preventDefault(); e.stopImmediatePropagation();
  }, true);

  // a page from the Internet: straight from here when the site allows it, else fetched by the TV
  async function getAny(url) {
    try {
      const c = new AbortController(), t = setTimeout(() => c.abort(), 9000);
      const r = await fetch(url, { signal: c.signal }); clearTimeout(t);
      if (r.ok) return await r.text();
    } catch (e) {}
    try { return await L.call('fetch', [url], 45000); } catch (e) { return null; }
  }

  // ---- the bridge
  const prefs = () => { try { return JSON.parse(S.prefs || '{}'); } catch (e) { return {}; } };
  window.TV = {
    apps: () => S.apps || '[]',
    open: pkg => { send('open', [pkg], '📺 يفتح على التلفزيون'); return true; },
    youtube: id => onTv() ? send('youtube', [id], '📺 يشتغل على التلفزيون') : playYt(id),
    link: url => onTv() ? send('link', [url], '📺 على التلفزيون') : open(url, '_blank'),
    channel: id => onTv() ? send('channel', [id], '📺 على التلفزيون') : open('https://www.youtube.com/channel/' + id, '_blank'),
    watch: (u, n, i) => onTv() ? send('watch', [u, n, i], '📺 يشتغل على التلفزيون') : playHere(JSON.parse(u), JSON.parse(n), i),
    external: url => open(url, '_blank'),
    prayers: () => S.prayers || '',
    setCity: (a, b, c, d) => send('setCity', [a, b, c, d]).then(() => setTimeout(async () => { try { S.prayers = await L.call('prayers'); window.loadPrayers && loadPrayers(); } catch (e) {} }, 1500)),
    phoneUrl: () => new URL('remote/', location.href).href,
    canOverlay: () => !!S.canOverlay,
    settings: () => send('settings', [], '📺 فتحت إعدادات التلفزيون'),
    board: () => S.board || '',
    saveBoard: j => { S.board = j; if (L.tv && L.okd) send('saveBoard', [j]); else cloudSave(); },
    phrase: () => S.phrase || '',
    font: () => S.font || '',
    fetch: (url, cb) => { getAny(url).then(t => { const f = window[cb]; if (f) f(t); }); },
    prefs: () => S.prefs || '{}',
    setPref: (k, v) => { const p = prefs(); p[k] = v; S.prefs = JSON.stringify(p); if (L.tv && L.okd) send('setPref', [k, String(v)]); else cloudSave(); },
    mouse: () => S.mouse || '{}',
    mouseOn: () => { send('mouseOn', [], 'يشغّل الماوس على التلفزيون'); return true; },
    accessSettings: () => send('accessSettings', [], '📺 فتحت تسهيل الوصول'),
    tryAdhan: w => send('tryAdhan', [w], w === 'stop' ? '⏹' : '🕌 يشتغل على التلفزيون'),
    webInfo: () => JSON.stringify({ pin: L.pin, online: !!(L.open && L.tv && L.okd), url: location.href.split('?')[0].split('#')[0] }),
    webNewPin: () => { send('webNewPin', [], 'فكّيت ربط كل المتصفحات — اربط من جديد من البيت'); return ''; },
    lastProblem: () => '',
  };

  // ---- the TV changed something: show it here too
  L.on('ev', async what => {
    try {
      if (what === 'board') { S.board = await L.call('board'); window.loadBoard && loadBoard(); }
      else if (what === 'phrase') { S.phrase = await L.call('phrase'); window.loadPhrase && loadPhrase(); }
      else if (what === 'prefs') { S.prefs = await L.call('prefs'); window.loadSet && loadSet(); window.homeLayout && homeLayout(); window.applyWall && applyWall(); }
      else if (what === 'city') { S.prayers = await L.call('prayers'); window.loadPrayers && loadPrayers(); }
      else if (what === 'cloud' && !(L.tv && L.okd)) { const c = await cloudLoad(); if (c) { S.board = c.board; S.phrase = c.phrase; S.prefs = JSON.stringify(c.prefs || {}); window.loadBoard && loadBoard(); window.loadPhrase && loadPhrase(); window.loadSet && loadSet(); window.homeLayout && homeLayout(); } }
    } catch (e) {}
  });
  // back online after a drop: everything again
  let wasLive = !!L.okd;
  L.on('state', async () => {
    const live = !!(L.open && L.tv);
    if (live && !wasLive) { L.okd = false; if (await L.gate()) { try { Object.assign(S, await L.call('snapshot', [], 30000)); window.loadBoard && loadBoard(); window.loadPhrase && loadPhrase(); window.loadPrayers && loadPrayers(); } catch (e) {} } paint(); }
    wasLive = live;
  });

  // ---- now the page itself
  for (const old of document.querySelectorAll('script[type="text/dctv"]')) {
    const s = document.createElement('script'); s.textContent = old.textContent; document.body.appendChild(s);
  }
  cover.remove();
})();
