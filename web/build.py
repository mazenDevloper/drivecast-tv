#!/usr/bin/env python3
"""DriveCast TV on the web (GitHub Pages): the TV's own pages, wired to the TV box through DriveCast's hub.
   _site/            the home page (assets/home) — its script runs after web.js has the TV's answers
   _site/remote/     the phone remote (assets/phone) — its requests go to the TV through the hub
Run: python3 web/build.py   (the Web workflow does it on every push)"""
import pathlib, re, shutil

root = pathlib.Path(__file__).resolve().parent.parent
site = root / '_site'
shutil.rmtree(site, ignore_errors=True)
shutil.copytree(root / 'app/src/main/assets/home', site)
shutil.copytree(root / 'app/src/main/assets/fonts', site / 'fonts')   # the page's own fonts (Antonio, Amiri Quran): ../fonts on the TV
for f in ('hub.js', 'web.js', 'remote.js'):
    shutil.copy(root / 'web' / f, site / f)

h = (site / 'index.html').read_text(encoding='utf-8')
h = h.replace('../fonts/', 'fonts/')   # (before the script is found: it moves what comes after)
main = re.search(r'<script>\n', h)
assert main, 'the home page script'
h = h[:main.start()] + '<script type="text/dctv">\n' + h[main.end():]   # waits for web.js
h = h.replace('<head>', '<head>\n<script src="hub.js"></script>', 1)
h = h.replace('<meta name="viewport" content="width=1920">', '<meta name="viewport" content="width=device-width,initial-scale=1">', 1)
h = h.replace('<title>DriveCast TV</title>', '<title>DriveCast TV · ويب</title>', 1)
import subprocess, time
ver = (subprocess.run(['git', 'rev-parse', '--short', 'HEAD'], cwd=root, capture_output=True, text=True).stdout.strip() or str(int(time.time())))
# a browser keeps the page ~10 min (GitHub Pages): the scripts carry the version, and an old page that finds a newer one reloads itself
h = h.replace('<script src="hub.js"></script>', f'<script src="hub.js?v={ver}"></script>', 1)
h = h.replace('</body>', f'<script src="web.js?v={ver}"></script>\n</body>', 1)
h = h.replace('<head>', '<head>\n<script>(function(){var V="' + ver + '";try{fetch("version.txt?t="+Date.now(),{cache:"no-store"}).then(function(r){return r.ok?r.text():""}).then(function(v){v=(v||"").trim();if(v&&v!==V&&sessionStorage.getItem("dctv-v")!==v){sessionStorage.setItem("dctv-v",v);var q=new URLSearchParams(location.search);q.set("v",v);location.replace(location.pathname+"?"+q+location.hash);}})}catch(e){}})();</script>', 1)
(site / 'version.txt').write_text(ver)
(site / 'index.html').write_text(h, encoding='utf-8')

p = (root / 'app/src/main/assets/phone/index.html').read_text(encoding='utf-8')
p = p.replace('<head>', '<head>\n<script src="../hub.js"></script>\n<script src="../remote.js"></script>', 1)
# 🔔 the remote on the Home Screen is an app of its own (iOS 16.4+): it can get the match alerts with the phone locked
p = p.replace('<head>', '<head>\n<link rel="manifest" href="manifest.json">\n<meta name="apple-mobile-web-app-capable" content="yes">\n<meta name="apple-mobile-web-app-title" content="الريموت">\n<link rel="apple-touch-icon" href="icon.png">', 1)
(site / 'remote').mkdir()
(site / 'remote/index.html').write_text(p, encoding='utf-8')
import json
(site / 'remote/manifest.json').write_text(json.dumps({'name': 'ريموت DriveCast TV', 'short_name': 'الريموت', 'start_url': './', 'scope': './', 'display': 'standalone', 'dir': 'rtl', 'lang': 'ar',
  'background_color': '#0b0f0d', 'theme_color': '#0b0f0d', 'icons': [{'src': 'icon.png', 'sizes': '512x512', 'type': 'image/png'}]}, ensure_ascii=False), encoding='utf-8')
# the alerts: a push only wakes this worker, which fetches the latest alerts from DriveCast's server and shows them
(site / 'remote/sw.js').write_text("""self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));
self.addEventListener('push',e=>e.waitUntil((async()=>{
  let list=[];try{list=(await (await fetch('https://studio.c-p.workers.dev/push/latest',{cache:'no-store'})).json()).alerts||[]}catch(x){}
  const mine=list.filter(a=>a.fav||a.kind==='tv'||a.kind==='pre'||/^test/.test(a.id||''));list=mine.length?mine:list.slice(0,1);   // your matches first
  if(!list.length)list=[{id:'x'+Date.now(),title:'⚽ DriveCast TV',body:'تحديث جديد للمباريات'}];
  for(const a of list.slice(0,4))await self.registration.showNotification(a.title,{body:a.body||'',tag:a.id,icon:'icon.png',data:{url:'./'}});
})()));
self.addEventListener('notificationclick',e=>{e.notification.close();e.waitUntil(clients.matchAll({type:'window'}).then(w=>w.length?w[0].focus():clients.openWindow('./')));});
""", encoding='utf-8')
icon = root / 'app/src/main/res/mipmap-xxxhdpi/ic_launcher.png'
for cand in [icon, *sorted((root / 'app/src/main/res').glob('**/ic_launcher*.png'), key=lambda x: -x.stat().st_size), *sorted((root / 'app/src/main/res').glob('**/banner*.png'))]:
    if cand.exists(): shutil.copy(cand, site / 'remote/icon.png'); break
(site / '.nojekyll').write_text('')
print('site:', sum(1 for _ in site.rglob('*')), 'files')
