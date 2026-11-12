#!/usr/bin/env python3
"""The programme guide, made small for the TV.
Free-TV/IPTV names its guide sources itself (the playlist's x-tvg-url: epgshare01's XMLTV files, by country). Those are hundreds
of MB — far too much for a TV — so this runs on GitHub every few hours: reads them as a stream, keeps only the channels the
playlist has and only the next hours, and writes one small file:  epg.json = { "t": made (s), "c": { tvg-id: [[start (s), stop (s), title], …] } }
"""
import gzip, io, json, re, sys, time, urllib.request
import xml.etree.ElementTree as ET

PLAYLIST = 'https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8'
HOURS_BACK, HOURS_AHEAD, MAX_PER = 3, 20, 14
UA = {'User-Agent': 'Mozilla/5.0 (DriveCastTV guide)'}

def get(u, timeout=120):
    return urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=timeout)

def when(s):   # 20261111180000 +0300 → epoch seconds
    m = re.match(r'(\d{4})(\d\d)(\d\d)(\d\d)(\d\d)(\d\d)?\s*([+-]\d{4})?', s or '')
    if not m: return 0
    y, mo, d, h, mi, se, tz = m.groups()
    import calendar
    t = calendar.timegm((int(y), int(mo), int(d), int(h), int(mi), int(se or 0)))
    if tz: t -= (1 if tz[0] == '+' else -1) * (int(tz[1:3]) * 3600 + int(tz[3:5]) * 60)
    return t

# generic English titles of the guides, in Arabic (a programme's own name stays as it is)
GLOSS = [(r'^(the )?news$|^news (bulletin|update)$|^headlines$', 'نشرة الأخبار'), (r'^live$|^live:? ', 'مباشر: '), (r'^movie$|^film$', 'فيلم'), (r'^documentary$', 'وثائقي'),
  (r'^(sports? )?news$', 'أخبار الرياضة'), (r'^weather$', 'النشرة الجوية'), (r'^cartoons?$', 'رسوم متحركة'), (r'^series$', 'مسلسل'), (r'^talk show$', 'برنامج حواري'),
  (r'^quran$|^holy quran$', 'القرآن الكريم'), (r'^(friday )?prayer$', 'الصلاة'), (r'^highlights$', 'ملخصات'), (r'^football$', 'كرة القدم'), (r'^replay$', 'إعادة'), (r'^to be announced$|^tba$|^tbc$', 'يُعلن لاحقاً'),
  (r'^closedown$|^off air$', 'توقف البث'), (r'^music$', 'موسيقى'), (r'^kids$', 'أطفال'), (r'^cooking$', 'طبخ')]
def arabic(t):
    for pat, ar in GLOSS:
        if re.search(pat, t, re.I):
            return re.sub(pat, ar, t, flags=re.I).strip() if ar.endswith(': ') else ar
    t = re.sub(r'\bLive\b:?', 'مباشر:', t)
    t = re.sub(r'\s+vs?\.?\s+', ' × ', t)
    return t
norm = lambda s: re.sub(r'[^a-z0-9]', '', (s or '').lower())

# 📺 more channels, found by their name: every channel of the Arab region's guides (beIN MENA — TOD's channels and Al Kass —,
# the Emirates, Saudi Arabia, Egypt, Al Jazeera) and the sports channels of the big countries' guides. The TV finds its own
# channels in «n» (a guide's channel name → its id) by the channel's words. Clocks a guide writes wrong (beIN MENA marks Doha's
# time +0100) are corrected by the shift the sports guide measured against the day's kick-offs (sports.json «sh»).
EXTRA_ALL = ['BEIN1', 'AE1', 'SA1', 'SA2', 'EG1', 'ALJAZEERA1']
EXTRA_SPORT = ['UK1', 'PT1', 'ES1', 'FR1', 'IT1', 'DE1', 'TR1', 'US_SPORTS1', 'US2', 'AU1', 'IN4', 'BR1', 'MX1']
def extra(guide, lo, hi, out):
    import os
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from sports_epg import SPORTY, nice
    try: shift = json.load(open(os.path.join(os.path.dirname(out), 'sports.json'))).get('sh', {})
    except Exception: shift = {}
    names, low = {}, {}
    for f in EXTRA_ALL + EXTRA_SPORT:
        try: raw = get(f'https://epgshare01.online/epgshare01/epg_ripper_{f}.xml.gz', 150).read()
        except Exception as e: print(f'  FAILED  {f}: {e}', flush=True); continue
        ids, n, sh = {}, 0, shift.get(f, 0)
        for ev, el in ET.iterparse(gzip.open(io.BytesIO(raw)), events=('end',)):
            if el.tag == 'channel':
                cid, nm = el.get('id', ''), el.findtext('display-name') or ''
                if f in EXTRA_ALL or SPORTY.search(nm + ' ' + cid):
                    nn = nice(nm, cid); k = nn.lower()
                    if k and k not in low: low[k] = 'x.' + f + '.' + cid; names[nn] = low[k]
                    if k: ids[cid] = low[k]
                el.clear()
            elif el.tag == 'programme':
                i = ids.get(el.get('channel'))
                if i:
                    a, b = when(el.get('start')) + sh, when(el.get('stop')) + sh
                    if b > lo and a < hi:
                        ts = el.findall('title')
                        ar = next((x.text for x in ts if (x.get('lang') or '').lower().startswith('ar') and x.text), None)
                        t = arabic((ar or (ts[0].text if ts else '') or '').strip())
                        if t: ic = el.find('icon'); guide.setdefault(i, {})[a] = [a, b, t[:70], ic.get('src', '') if ic is not None else '']; n += 1
                el.clear()
        print(f'  {n:6d}  {f} (by name: {len(ids)} channels{", clock %+d h" % (sh // 3600) if sh else ""})', flush=True)
    return names

def main(out):
    pl = get(PLAYLIST).read().decode('utf-8', 'replace')
    head = pl.split('\n', 1)[0]
    sources = [u.strip() for u in re.search(r'x-tvg-url="([^"]*)"', head).group(1).split(',') if u.strip()]
    want = {}                                # normalised id → the playlist's id
    for i in re.findall(r'tvg-id="([^"]+)"', pl): want.setdefault(norm(i), i)
    byname = {}                              # a channel's name → its id (a guide that names the channel differently still finds it)
    for nm, i in re.findall(r'tvg-name="([^"]+)"[^\n]*?tvg-id="([^"]+)"', pl): byname.setdefault(norm(re.sub(r'\b(hd|sd|fhd|tv)\b', '', nm.lower())), i)
    key = lambda s: norm(re.sub(r'\b(hd|sd|fhd|tv)\b', '', (s or '').lower()))
    print(f'{len(want)} channels with an id, {len(sources)} guide files', flush=True)
    now = time.time(); lo, hi = now - HOURS_BACK * 3600, now + HOURS_AHEAD * 3600
    guide, seen_files = {}, 0
    for u in sources:
        try:
            raw = get(u).read()
            f = gzip.open(io.BytesIO(raw)) if u.endswith('.gz') else io.BytesIO(raw)
            n = 0
            for ev, el in ET.iterparse(f, events=('end',)):
                if el.tag == 'programme':
                    k = norm(el.get('channel'))
                    if k in want:
                        a, b = when(el.get('start')), when(el.get('stop'))
                        if b > lo and a < hi:
                            ts = el.findall('title')
                            ar = next((x.text for x in ts if (x.get('lang') or '').lower().startswith('ar') and x.text), None)
                            t = arabic((ar or (ts[0].text if ts else '') or '').strip())
                            if t: ic = el.find('icon'); guide.setdefault(want[k], {})[a] = [a, b, t[:70], ic.get('src', '') if ic is not None else '']; n += 1
                    el.clear()
                elif el.tag == 'channel':
                    k = norm(el.get('id'))
                    if k not in want:
                        for d in el.findall('display-name'):
                            i = byname.get(key(d.text))
                            if i and i not in guide: want[k] = i; break
                    el.clear()
            seen_files += 1
            print(f'  {n:6d}  {u.rsplit("/", 1)[-1]}', flush=True)
        except Exception as e:
            print(f'  FAILED  {u.rsplit("/", 1)[-1]}: {e}', flush=True)
    names = extra(guide, lo, hi, out)
    c = {}
    for i, progs in guide.items():
        rows = [progs[a] for a in sorted(progs)]
        rows = [r for r in rows if r[1] > now - 600][:MAX_PER]
        rows = [r if k < 3 and len(r) > 3 and r[3] and len(r[3]) < 300 else r[:3] for k, r in enumerate(rows)]   # the programme's picture: the next three only (the file stays small)
        if rows: c[i] = rows
    json.dump({'t': int(now), 'c': c, 'n': {k: v for k, v in names.items() if v in c}}, open(out, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
    import os
    print(f'{len(c)} channels have a guide ({seen_files}/{len(sources)} files read) → {os.path.getsize(out) // 1024} KB')
    by = {}
    for i in c: by[i.rsplit('.', 1)[-1]] = by.get(i.rsplit('.', 1)[-1], 0) + 1
    print('arab:', ' '.join(sorted(i for i in c if i.rsplit('.', 1)[-1] in ('om', 'ae', 'sa', 'qa', 'kw', 'bh', 'eg', 'iq', 'jo', 'lb', 'sy', 'ma', 'dz', 'tn'))))
    print('by country:', ' '.join(f'{k}:{v}' for k, v in sorted(by.items(), key=lambda x: -x[1])[:40]))
    if len(c) < 50: sys.exit('too few channels — the old file is kept')

if __name__ == '__main__': main(sys.argv[1] if len(sys.argv) > 1 else 'epg.json')
