// DriveCast TV on the web — the link to the TV box, wherever both are.
// The TV keeps a WebSocket open to DriveCast's hub (studio.c-p.workers.dev/hub, app «dctv»); this page joins it (app «dctv-web»)
// and talks to the TV through it. Every request carries the TV's 6-digit code (TV: settings → 🌐 الويب); the code is asked once
// and kept in this browser. Remote.java on the TV is the other end.
(function () {
  const HUB = 'wss://studio.c-p.workers.dev/hub/ws';
  const ls = { get: k => { try { return localStorage.getItem(k); } catch (e) { return null; } }, set: (k, v) => { try { localStorage.setItem(k, v); } catch (e) {} } };
  let me = ls.get('dctv-me');
  if (!me) { me = 'dctv-web-' + Math.random().toString(36).slice(2, 10); ls.set('dctv-me', me); }
  const q = new URLSearchParams(location.search);
  if (q.get('pin')) { ls.set('dctv-pin', q.get('pin').replace(/\D/g, '')); history.replaceState(null, '', location.pathname); }

  const L = window.DCTV = { me, pin: ls.get('dctv-pin') || '', tv: null, tvName: '', ws: null, open: false, wait: new Map(), fns: { ev: [], state: [] }, n: 0, ls };
  L.on = (k, f) => L.fns[k].push(f);
  const fire = (k, x) => L.fns[k].forEach(f => { try { f(x); } catch (e) {} });

  function pickTv(devs) {
    const tvs = (devs || []).filter(d => d && d.app === 'dctv');
    const want = ls.get('dctv-tv'), t = tvs.find(d => d.id === want) || tvs.sort((a, b) => (b.since || 0) - (a.since || 0))[0];
    L.tv = t ? t.id : null; L.tvName = t ? t.name : '';
    if (t) ls.set('dctv-tv', t.id);
    fire('state');
  }
  function connect() {
    let ws;
    try { ws = L.ws = new WebSocket(HUB + '?id=' + me + '&name=' + encodeURIComponent('DriveCast TV · ويب') + '&kind=web&app=dctv-web'); }
    catch (e) { setTimeout(connect, 5000); return; }
    ws.onopen = () => { L.open = true; fire('state'); };
    ws.onmessage = e => {
      let d; try { d = JSON.parse(e.data); } catch (x) { return; }
      if (d.type === 'hello' || d.type === 'devices') return pickTv(d.devices);
      if (d.type !== 'msg' || !d.msg) return;
      const m = d.msg;
      if (m.type === 'dctv-r') {
        const w = L.wait.get(m.rid); if (!w) return;
        L.wait.delete(m.rid); clearTimeout(w.t);
        m.ok ? w.ok(m.value) : w.no(new Error(String(m.value)));
      } else if (m.type === 'dctv-ev') fire('ev', m.what);
    };
    ws.onclose = () => { if (L.ws === ws) { L.open = false; L.ws = null; L.tv = null; fire('state'); setTimeout(connect, 3000); } };
    ws.onerror = () => { try { ws.close(); } catch (e) {} };
  }
  setInterval(() => { if (L.ws && L.open) { try { L.ws.send('{"op":"ping"}'); } catch (e) {} } }, 45000);

  /** true once the hub answered and the TV is among its devices (waits up to 8 s) */
  L.ready = () => new Promise(res => {
    if (L.open && L.tv) return res(true);
    const t0 = Date.now(), iv = setInterval(() => { if ((L.open && L.tv) || Date.now() - t0 > 8000) { clearInterval(iv); res(!!(L.open && L.tv)); } }, 150);
  });
  L.send = (msg, ms = 20000) => new Promise((ok, no) => {
    if (!L.ws || !L.open || !L.tv) return no(new Error('offline'));
    const rid = me + ':' + (++L.n), t = setTimeout(() => { L.wait.delete(rid); no(new Error('timeout')); }, ms);
    L.wait.set(rid, { ok, no, t });
    L.ws.send(JSON.stringify({ op: 'send', to: L.tv, msg: Object.assign({ type: 'dctv', rid, pin: L.pin }, msg) }));
  });
  L.call = (call, args, ms) => L.send({ call, args: args || [] }, ms);
  L.http = (method, path, body, b64) => L.send({ http: method, path, body: body || '', b64: !!b64 }, 40000);
  L.setPin = p => { L.pin = String(p || '').replace(/\D/g, ''); ls.set('dctv-pin', L.pin); };

  /** the TV's code, asked on a card (once per browser) */
  L.askPin = why => new Promise(res => {
    const box = document.createElement('div');
    box.setAttribute('dir', 'rtl');
    box.style.cssText = 'position:fixed;inset:0;z-index:2147483000;display:grid;place-items:center;background:rgba(6,10,14,.82);backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px);font-family:system-ui,-apple-system,"SF Arabic","Segoe UI",Tahoma,sans-serif;color:#f4f1ea';
    box.innerHTML = `<form style="width:min(92vw,420px);background:#141b22;border:1px solid rgba(255,255,255,.12);border-radius:28px;padding:28px;box-shadow:0 30px 80px rgba(0,0,0,.5)">
      <div style="font-size:22px;font-weight:700;margin-bottom:6px">📺 رمز التلفزيون</div>
      <div style="font-size:15px;opacity:.75;line-height:1.7;margin-bottom:18px">${why === 'pin' && L.pin ? 'الرمز غلط — جرّب مرة ثانية. ' : ''}${why === 'locked' ? 'محاولات كثيرة غلط، التلفزيون مقفّل ١٠ دقايق. ' : ''}تلقاه في التلفزيون: الإعدادات (زر ٠) ← 🌐 الويب ← الرمز.</div>
      <input inputmode="numeric" autocomplete="one-time-code" maxlength="7" placeholder="000 000" style="width:100%;box-sizing:border-box;font-size:34px;letter-spacing:.3em;text-align:center;direction:ltr;padding:14px;border-radius:18px;border:1px solid rgba(255,255,255,.2);background:#0b1116;color:#fff;outline:none">
      <button style="margin-top:16px;width:100%;height:56px;border:0;border-radius:999px;background:#3ddc97;color:#06281b;font-size:19px;font-weight:700;font-family:inherit">اتصل</button>
    </form>`;
    const f = box.querySelector('form'), inp = box.querySelector('input');
    f.onsubmit = e => { e.preventDefault(); const v = inp.value.replace(/\D/g, ''); if (v.length !== 6) { inp.focus(); return; } L.setPin(v); box.remove(); res(); };
    document.documentElement.appendChild(box); setTimeout(() => inp.focus(), 50);
  });

  /** connected and the code accepted: true; the TV is not online: false */
  L.gate = async () => {
    if (L.okd) return true;
    if (!(await L.ready())) return false;
    for (;;) {
      try { await L.call('ping', [], 12000); L.okd = true; return true; }
      catch (e) { if (e.message === 'pin' || e.message === 'locked') await L.askPin(e.message); else return false; }
    }
  };
  connect();
})();
