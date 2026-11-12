#!/usr/bin/env python3
"""🔷 The open 4K channels: every channel of the free lists whose name says 4K / UHD / 2160 / 8K, checked with ffprobe for a
real picture of 2000 lines or more. Free-to-air only: pay brands (Eurosport, Sky, DAZN, beIN, Canal+, Digi…) and the
resellers' panel links (user/password in the path) are left out. Channels whose broadcaster limits them to its own country
(Rai 4K, IRIB UHD, Love Nature 4K…) can't be checked from abroad; they are kept when their name says 2160p and the link is
the broadcaster's own. → k4.json [{n, u, h, logo, geo}]"""
import json, re, subprocess, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor

SRC = ['https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8', 'https://iptv-org.github.io/iptv/index.m3u']
NAME = re.compile(r'\b(4k|uhd|2160p?|8k)\b', re.I)
PAY = re.compile(r'eurosport|\bsky\b|dazn|bein|canal\+|\bdigi\b|\bespn|\bprima\b|\bbbc\b|movistar|\bfox\b|tnt sport|premier|setanta|arena sport', re.I)
PANEL = re.compile(r':(2546\d|8080|8000|25461)/|/[A-Za-z0-9]{6,}/\d{6,}/\d+\.m3u8|username=|password=|token=', re.I)
OFFICIAL = re.compile(r'akamaized\.net|telewebion\.ir|rai\.it|raievent|amagi\.tv|wurl\.com|mgtv\.com|hebtv\.com|sztv\.com\.cn|cctv|rtve\.es', re.I)

def get(u):
    return urllib.request.urlopen(urllib.request.Request(u, headers={'User-Agent': 'Mozilla/5.0'}), timeout=60).read().decode('utf8', 'ignore')

def cands():
    out, seen = [], set()
    for src in SRC:
        try: L = get(src).splitlines()
        except Exception as e: print('  FAILED', src, e, flush=True); continue
        for i, l in enumerate(L):
            if not l.startswith('#EXTINF'): continue
            n = l.split(',')[-1].strip(); u = next((x.strip() for x in L[i + 1:i + 4] if x.startswith('http')), '')
            if not u or u in seen or not NAME.search(n) or PAY.search(n) or PANEL.search(u): continue
            seen.add(u); logo = (re.search(r'tvg-logo="([^"]*)"', l) or [None, ''])[1]
            out.append({'n': re.sub(r'\s*[\(\[][^\)\]]*[\)\]]', '', n).strip(), 'u': u, 'logo': logo, 'geo': bool(re.search(r'geo-?blocked', n, re.I)), 'say': n})
    return out

def height(c):
    try:
        r = subprocess.run(['ffprobe', '-v', 'error', '-rw_timeout', '12000000', '-select_streams', 'v', '-show_entries', 'stream=height', '-of', 'csv=p=0', c['u']],
                           capture_output=True, text=True, timeout=25)
        return max([int(x) for x in re.findall(r'\d+', r.stdout)] or [0])
    except Exception: return 0

def main(out):
    cs = cands(); print(f'{len(cs)} candidates', flush=True)
    with ThreadPoolExecutor(8) as ex: hs = list(ex.map(height, cs))
    keep, names = [], set()
    for c, h in zip(cs, hs):
        ok = h >= 2000 or (h == 0 and c['geo'] and OFFICIAL.search(c['u']) and re.search(r'2160|uhd|4k', c['say'], re.I))
        print(f"  {'✅' if ok else '  '} {h or '—':>5}  {c['say'][:60]}", flush=True)
        if ok and c['n'].lower() not in names:
            names.add(c['n'].lower()); keep.append({'n': c['n'], 'u': c['u'], 'h': h or 2160, 'logo': c['logo'], 'geo': h == 0})
    json.dump(keep, open(out, 'w'), ensure_ascii=False, separators=(',', ':'))
    print(f'{len(keep)} channels in 4K → {out}', flush=True)

if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'k4.json')
