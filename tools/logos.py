#!/usr/bin/env python3
"""🏷️ A channel-logo library, found by the channel's name: tv-logo/tv-logos (logos drawn on a transparent background, by country)
first, then iptv-org's database (names and other names, Arabic ones too). Kept: the Arab region's channels and every sports
channel, so the file stays small. The TV matches a channel to it by the channel's words (the same as the guide's names).
→ logos.json {name: url}"""
import json, re, sys, urllib.request

def get(u, t=60): return urllib.request.urlopen(urllib.request.Request(u, headers={'User-Agent': 'Mozilla/5.0'}), timeout=t).read()

ARAB = {'AE', 'SA', 'OM', 'QA', 'KW', 'BH', 'EG', 'JO', 'LB', 'IQ', 'SY', 'MA', 'DZ', 'TN', 'LY', 'YE', 'PS', 'SD'}
TVL = {'united-arab-emirates': 'ae', 'world-middle-east': '', 'lebanon': 'lb', 'international': '', 'united-kingdom': 'uk', 'spain': 'es',
       'portugal': 'pt', 'france': 'fr', 'germany': 'de', 'italy': 'it', 'turkey': 'tr', 'united-states': 'us'}
SPORT = re.compile(r'sport|bein|kass|ssc|espn|dazn|eurosport|sky sports|tnt sports|arena|setanta|supersport|match|football|golf|racing', re.I)

def main(out):
    lib = {}
    # tv-logos: «mbc-1-ae.png» → «mbc 1»
    for country, cc in TVL.items():
        try: files = json.loads(get(f'https://api.github.com/repos/tv-logo/tv-logos/contents/countries/{country}'))
        except Exception as e: print('  tv-logos', country, e, flush=True); continue
        for f in files:
            n = f.get('name', '')
            if not n.endswith('.png'): continue
            name = re.sub(r'-(%s|mea|int|me)$' % (cc or 'xx'), '', n[:-4]).replace('-', ' ')
            if country in ('united-arab-emirates', 'world-middle-east', 'lebanon') or SPORT.search(name):
                lib.setdefault(name, f'https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/{country}/{n}')
    n1 = len(lib)
    # iptv-org: the channels' names and other names → the widest PNG
    ch = {c['id']: c for c in json.loads(get('https://iptv-org.github.io/api/channels.json'))}
    best = {}
    for l in json.loads(get('https://iptv-org.github.io/api/logos.json')):
        c = ch.get(l.get('channel'))
        if not c or l.get('feed') or not l.get('url'): continue
        if not (c.get('country') in ARAB or SPORT.search(c.get('name', '')) or 'sports' in (c.get('categories') or [])): continue
        if (l.get('width') or 0) > best.get(c['id'], (0, ''))[0]: best[c['id']] = (l.get('width') or 0, l['url'])
    for cid, (_, url) in best.items():
        c = ch[cid]
        for nm in [c['name'], *(c.get('alt_names') or [])]:
            if nm and len(nm) < 60: lib.setdefault(nm, url)
    json.dump(lib, open(out, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
    print(f'🏷️ {len(lib)} logos ({n1} transparent from tv-logos) → {out}', flush=True)

if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'logos.json')
