// The phone remote on the web: the same page the TV serves on its Wi-Fi (port 8080), its requests ('/key', '/board', '/quran'…)
// carried to the TV through DriveCast's hub instead — so it works from anywhere, not only at home.
(function () {
  const L = window.DCTV, real = window.fetch.bind(window);
  let gate = null;
  window.fetch = async (u, o) => {
    if (typeof u !== 'string' || !u.startsWith('/') || u.startsWith('//')) return real(u, o);
    o = o || {};
    if (!gate) gate = L.gate().then(async ok => {
      if (!ok && !L.paired() && !L.asked) { L.asked = true; ok = await L.pair(); }   // the remote needs the TV: pair once, when opened
      if (!ok) setTimeout(() => { gate = null; }, 15000);
      return ok;
    });
    if (!(await gate)) throw new Error('offline');
    const method = (o.method || 'GET').toUpperCase();
    let body = o.body == null ? '' : o.body, b64 = false;
    if (typeof body !== 'string') {   // a file (the font): sent as base64
      const buf = new Uint8Array(await new Response(body).arrayBuffer());
      let s = ''; for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode.apply(null, buf.subarray(i, i + 0x8000));
      body = btoa(s); b64 = true;
    }
    const v = await L.http(method, u, body, b64);
    return new Response(v == null ? '' : String(v), { status: 200 });
  };
  // the line at the top says where the TV is
  L.on('state', () => {
    const st = document.getElementById('st'); if (!st) return;
    st.textContent = L.open && L.tv ? 'متصل عن بعد' : 'التلفزيون مو متصل';
  });
})();
