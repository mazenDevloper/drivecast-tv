// The web version's path, end to end: this machine joins DriveCast's hub as a web page and drives the TV through it.
//   node tools/hub-test.mjs <tv id> <pin>      (tv-test.sh reads both from the TV's /web)
// Prints one line per check: "ok …" or "bad …"; then "zap" when the TV is playing the test channels.
const [tv, pin] = process.argv.slice(2);
const ws = new WebSocket('wss://studio.c-p.workers.dev/hub/ws?id=dctv-test-' + Date.now() + '&name=test&kind=web&app=dctv-web');
const wait = new Map(); let n = 0, devices = [];
ws.onmessage = e => {
  const d = JSON.parse(e.data);
  if (d.type === 'hello' || d.type === 'devices') devices = d.devices || [];
  if (d.type === 'msg' && d.msg?.type === 'dctv-r') { const w = wait.get(d.msg.rid); if (w) { wait.delete(d.msg.rid); w(d.msg); } }
  if (d.type === 'msg' && d.msg?.type === 'dctv-ev') console.log('ok event from the TV: ' + d.msg.what);
};
const send = (msg, p = pin) => new Promise(res => { const rid = 't' + (++n); wait.set(rid, res); ws.send(JSON.stringify({ op: 'send', to: tv, msg: { type: 'dctv', rid, pin: p, ...msg } })); setTimeout(() => { if (wait.delete(rid)) res({ ok: false, value: 'timeout' }); }, 30000); });
const check = (ok, what) => console.log((ok ? 'ok ' : 'bad ') + what);
ws.onopen = async () => {
  await new Promise(r => setTimeout(r, 1500));
  check(devices.some(d => d.id === tv && d.app === 'dctv'), 'the TV is on the hub (' + devices.filter(d => d.app === 'dctv').map(d => d.id).join(',') + ')');
  let r = await send({ call: 'ping' }, '000000'); check(!r.ok && r.value === 'pin', 'a wrong code is refused');
  r = await send({ call: 'ping' }); check(r.ok && r.value === 'pong', 'ping with the code');
  r = await send({ call: 'snapshot' });
  const apps = r.ok ? JSON.parse(r.value.apps || '[]').length : -1;
  check(r.ok && apps > 0 && r.value.prefs, 'snapshot: ' + apps + ' apps, board ' + String(r.value?.board || '').length + ' chars, prayers ' + (r.value?.prayers ? 'yes' : 'no'));
  r = await send({ http: 'GET', path: '/status' }); check(r.ok && String(r.value).includes('"home"'), 'web remote: /status ' + r.value);
  r = await send({ call: 'setPref', args: ['clock', '24'] }); check(r.ok, 'a setting from the web');
  r = await send({ call: 'fetch', args: ['https://iptv-org.github.io/iptv/countries/om.m3u'] }); check(r.ok && String(r.value).includes('#EXTM3U'), 'a page fetched by the TV');
  await new Promise(r => setTimeout(r, 2000));
  r = await send({ call: 'setPref', args: ['clock', '12'] });
  // channels for the zapping test (public test streams + Oman TV)
  const urls = ['https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8', 'https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8', 'https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8'];
  r = await send({ call: 'watch', args: [JSON.stringify(urls), JSON.stringify(['test 1', 'test 2', 'test 3']), 0] });
  check(r.ok, 'a channel started from the web');
  console.log('zap');
  setTimeout(() => process.exit(0), 1500);
};
ws.onerror = e => { console.log('bad hub: ' + (e.message || 'error')); process.exit(0); };
setTimeout(() => { console.log('bad timeout'); process.exit(0); }, 120000);
