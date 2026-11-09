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
for f in ('hub.js', 'web.js', 'remote.js'):
    shutil.copy(root / 'web' / f, site / f)

h = (site / 'index.html').read_text(encoding='utf-8')
main = re.search(r'<script>\n', h)
assert main, 'the home page script'
h = h[:main.start()] + '<script type="text/dctv">\n' + h[main.end():]   # waits for web.js
h = h.replace('<head>', '<head>\n<script src="hub.js"></script>', 1)
h = h.replace('<meta name="viewport" content="width=1920">', '<meta name="viewport" content="width=device-width,initial-scale=1">', 1)
h = h.replace('<title>DriveCast TV</title>', '<title>DriveCast TV · ويب</title>', 1)
h = h.replace('</body>', '<script src="web.js"></script>\n</body>', 1)
(site / 'index.html').write_text(h, encoding='utf-8')

p = (root / 'app/src/main/assets/phone/index.html').read_text(encoding='utf-8')
p = p.replace('<head>', '<head>\n<script src="../hub.js"></script>\n<script src="../remote.js"></script>', 1)
(site / 'remote').mkdir()
(site / 'remote/index.html').write_text(p, encoding='utf-8')
(site / '.nojekyll').write_text('')
print('site:', sum(1 for _ in site.rglob('*')), 'files')
