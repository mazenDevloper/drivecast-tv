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

# 🌐 the titles in Arabic: a guide that writes no Arabic title gets one by machine translation (Google's free endpoint), for
# the programmes the TV shows most (the next six of each channel). Translations are kept in tr.json beside the guide, so each
# title is translated once; names that don't translate (a match «A vs B» does: «A × B») come back as they were.
TR_URL = 'https://raw.githubusercontent.com/mazenDevloper/drivecast-tv/epg/tr.json'
AR = re.compile('[\u0600-\u06FF]')
def translate(c, out):
    import os, urllib.parse
    try: tr = json.loads(get(TR_URL, 60).read().decode('utf-8'))
    except Exception: tr = {}
    want = []
    for rows in c.values():
        for r in rows[:6]:
            t = r[2]
            if t and not AR.search(t) and re.search('[A-Za-z]{3}', t) and t not in tr and t not in want: want.append(t)
    done, i = 0, 0
    while i < len(want) and done < 9000:
        batch, size = [], 0
        while i < len(want) and size + len(want[i]) < 3500 and len(batch) < 60: batch.append(want[i]); size += len(want[i]) + 1; i += 1
        try:
            q = urllib.parse.urlencode({'client': 'gtx', 'sl': 'auto', 'tl': 'ar', 'dt': 't', 'q': '\n'.join(batch)})
            d = json.loads(get('https://translate.googleapis.com/translate_a/single?' + q, 30).read().decode('utf-8'))
            got = ''.join(x[0] for x in d[0] if x[0]).split('\n')
            if len(got) == len(batch):
                for a, b in zip(batch, got): tr[a] = b.strip()[:70]; done += 1
        except Exception as e:
            print('  translate:', e, flush=True); time.sleep(5)
        time.sleep(0.4)
    n = 0
    for rows in c.values():
        for r in rows:
            t = tr.get(r[2])
            if t and AR.search(t): r[2] = t; n += 1
    if len(tr) > 40000: tr = dict(list(tr.items())[-40000:])
    json.dump(tr, open(os.path.join(os.path.dirname(out), 'tr.json'), 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
    print(f'🌐 {done} new titles translated, {n} programmes shown in Arabic ({len(tr)} kept)', flush=True)

# 📺 MBC and the Saudi channels in their own words: Shahid's guide (MBC's) in Arabic — the other guides carry MBC 1 only in an
# English transliteration («Hamdiyya», «Leba: El Classico»). [site id, guide id (Free-TV's tvg-id), names the TV may call it]
SHAHID = [
    (387238, 'MBC1.ae', ['MBC 1', 'MBC1', 'ام بي سي 1']), (400917, 'MBC2.ae', ['MBC 2', 'MBC2']), (409385, 'MBC3.ae', ['MBC 3', 'MBC3']),
    (400919, 'MBC4.ae', ['MBC 4', 'MBC4']), (387937, 'MBC5.ae', ['MBC 5', 'MBC5']), (400921, 'MBCAction.ae', ['MBC Action']),
    (409387, 'MBCBollywood.ae', ['MBC Bollywood']), (387251, 'MBCDrama.ae', ['MBC Drama', 'ام بي سي دراما']), (387296, 'MBCPlusDrama.sa', ['MBC Plus Drama', 'MBC+ Drama']),
    (387294, 'MBCIraq.iq', ['MBC Iraq', 'MBC العراق']), (387290, 'MBCMasr.eg', ['MBC Masr', 'MBC مصر']), (387293, 'MBCMasr2.eg', ['MBC Masr 2', 'MBC Masr2']),
    (49923122575716, 'MBCMasrDrama.sa', ['MBC Masr Drama']), (400924, 'MBCMax.ae', ['MBC Max']), (418308, 'MBCPersia.ae', ['MBC Persia']),
    (387286, 'Alarabiya.ae', ['Al Arabiya', 'العربية']), (387288, 'AlHadath.sa', ['Al Hadath', 'الحدث']), (946938, 'AlSaudiya.sa', ['Al Saudiya', 'السعودية']),
    (946946, 'AlQuranAlKareemTV.sa', ['Al Quran Al Kareem', 'القرآن الكريم', 'Saudi Quran']), (946942, 'AlSunnahAlNabawiyahTV.sa', ['Al Sunnah Al Nabawiyah', 'السنة النبوية']),
    (946948, 'AlEkhbariya.sa', ['Al Ekhbariya', 'الإخبارية']), (946940, 'SBC.sa', ['SBC']), (414449, 'Wanasah.ae', ['Wanasah', 'وناسه']),
    (409390, 'SpacetoonArabic.ae', ['Spacetoon', 'سبيستون']), (995495, 'SaudiThaqafiyaTV.sa', ['Al Thaqafiya', 'الثقافية']), (946945, 'ThikrayatTV.sa', ['Thikrayat', 'ذكريات']),
    (975435, 'BabAlHara.sa', ['Bab Al Hara', 'باب الحارة']), (988045, 'Maraya.sa', ['Maraya', 'مرايا']), (862837, 'AsharqNews.sa', ['Asharq News', 'الشرق']),
]
# 🇴🇲 Oman's channels from AYN (ayn.om, Oman TV's own platform): each channel's schedule page — the days from today, each
# programme's Muscat time — and the pictures of its recent programmes (the catch-up row on the same page) matched by title.
AYN = [(158, 'OmanTV.om', ['Oman TV', 'Oman TV General', 'عمان', 'عُمان', 'قناة عمان العامة', 'عمان العامة']),
       (161, 'OmanTVMubashir.om', ['Oman TV Mubashir', 'Oman Mubashir', 'Oman TV Live', 'Oman TV Mubasher', 'عمان مباشر', 'عُمان مباشر']),
       (159, 'OmanSports.om', ['Oman Sports', 'Oman TV Sports', 'Oman Sport TV', 'عمان الرياضية', 'عُمان الرياضية']),
       (160, 'OmanCultural.om', ['Oman Cultural', 'Oman TV Cultural', 'Oman TV Culture', 'عمان الثقافية', 'عُمان الثقافية'])]
def ayn(guide, lo, hi):
    from datetime import datetime, timedelta, timezone
    MCT = timezone(timedelta(hours=4))
    names, n = {}, 0
    base = lambda s: re.sub(r'\s*[-–/]\s*(ج\s*\d+|الحلقة\s*\d+).*$', '', re.sub(r'^(برنامج|المسلسل العماني|المسلسل الكرتوني)\s*[-–/]?\s*', '', s)).strip()
    for cid, gid, nm in AYN:
        try: h = get(f'https://ayn.om/schedule/{cid}', 60).read().decode('utf-8', 'ignore')
        except Exception as e: print(f'  ayn {gid}: {e}', flush=True); continue
        pics = {}
        for img, alt in re.findall(r'data-src="(https://[^"]+\.jpg)"\s+alt="([^"]+)"', h):
            pics.setdefault(base(alt), img)
        b0 = h[h.find('epg_bottom_sec'):]
        day0 = datetime.now(MCT).replace(hour=0, minute=0, second=0, microsecond=0)
        mine, rows = {}, []
        for d, part in enumerate(b0.split('class="epg_timeline_box')[1:8]):
            for title, tm in re.findall(r'title="([^"]*)"[^>]*>\s*<div class="epg_item_box">\s*<div class="title">\s*<h2>[^<]*</h2>\s*<p>([^<]*)</p>', part):
                m = re.match(r'(\d{1,2}):(\d{2})(?:\s*-\s*(\d{1,2}):(\d{2}))?', tm.strip())
                if not m: continue
                a = int((day0 + timedelta(days=d, hours=int(m.group(1)), minutes=int(m.group(2)))).timestamp())
                rows.append([a, title.strip()])
        rows.sort()
        for k, (a, t) in enumerate(rows):
            b = rows[k + 1][0] if k + 1 < len(rows) else a + 1800
            if not t or 'لا يوجد جدول' in t or b <= lo or a >= hi or b <= a: continue
            mine[a] = [a, b, t[:70], pics.get(base(t), '')]
        if mine:
            guide[gid] = mine; n += len(mine)
            for x in nm: names.setdefault(x, gid)
    print(f'  {n:6d}  AYN (Oman TV, in Arabic)', flush=True)
    return names

def shahid(guide, lo, hi):
    from datetime import datetime, timezone
    names, n = {}, 0
    days = sorted({datetime.fromtimestamp(t, timezone.utc).strftime('%Y-%m-%d') for t in (lo, time.time(), hi)})
    for sid, gid, nm in SHAHID:
        got, mine = 0, {}
        for d in days:
            u = (f'https://api2.shahid.net/proxy/v2.1/shahid-epg-api/?csvChannelIds={sid}&from={d}T00:00:00.000Z&to={d}T23:59:59.999Z'
                 f'&country=SA&language=ar&Accept-Language=ar')
            try: j = json.loads(get(u, 30).read().decode('utf-8'))
            except Exception as e: print(f'  shahid {gid}: {e}', flush=True); continue
            for ch in j.get('items') or []:
                for it in ch.get('items') or []:
                    if it.get('emptySlot'): continue
                    a = when(re.sub(r'[-:T]', '', (it.get('actualFrom') or it.get('from') or ''))[:14] + ' +0000')
                    b = when(re.sub(r'[-:T]', '', (it.get('actualTo') or it.get('to') or ''))[:14] + ' +0000')
                    t = re.sub(r'\s+', ' ', it.get('title') or '').strip()
                    t = re.sub(r'\s*موسم\s*(\d+)\s*حلقة\s*(\d+)$', lambda m: (f' · الموسم {m.group(1)} الحلقة {m.group(2)}' if m.group(1) != '1' else f' · الحلقة {m.group(2)}'), t)   # «مناعة موسم 1 حلقة 14» → «مناعة · الحلقة 14»
                    if not t or not a or not b or b <= lo or a >= hi: continue
                    img = (it.get('productPoster') or '').replace('{height}', '360').replace('{width}', '640').replace('{croppingPoint}', 'mc')
                    mine[a] = [a, b, t[:70], img]; got += 1
        if got:
            guide[gid] = mine; n += got   # MBC's own guide replaces the others' for this channel
            for x in nm: names.setdefault(x, gid)
    print(f'  {n:6d}  Shahid (MBC, in Arabic)', flush=True)
    return names

OWN = ('MBC', 'Oman', 'Al', 'AsharqNews', 'SBC', 'Wanasah', 'Spacetoon', 'Saudi', 'Thikrayat', 'BabAlHara', 'Maraya')   # Shahid's and AYN's ids
PRIO_RE = re.compile(r'mbc|oman|abu.?dhabi|ad.?sport|bein|alkass|al.?kass|quran|dubai|sharjah|saudi|ssc|thmanyah', re.I)
def prio(i): return bool(PRIO_RE.search(i))

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
    sh = {**shahid(guide, lo, hi), **ayn(guide, lo, hi)}; names = {**sh, **{k: v for k, v in names.items() if k not in sh}}   # MBC's own guide first and winning: its channels are found by name before any other guide's
    c = {}
    for i, progs in guide.items():
        rows = [progs[a] for a in sorted(progs)]
        rows = [r for r in rows if r[1] > now - 600][:MAX_PER]
        keep = len(rows) if prio(i) else 6   # the programme's picture: every one on the channels watched most, the next six elsewhere
        rows = [r if k < keep and len(r) > 3 and r[3] and len(r[3]) < 300 else r[:3] for k, r in enumerate(rows)]
        if rows: c[i] = rows
    # a guide that gives several unrelated channels the same programmes (sat.tv's Quran channels show «بن 10» and «الفخ») is wrong
    # for all of them: a run of the same four titles on 3+ channels drops those channels' guides (MBC's and Oman's own never)
    sig = {}
    for i, rows in c.items():
        if len(rows) >= 4 and not i.startswith(OWN): sig.setdefault(tuple(r[2] for r in rows[:4]), []).append(i)
    junk = [i for ids in sig.values() if len(ids) >= 3 and len({re.sub(r'[^a-z]', '', i.split('.')[-2].lower())[:5] for i in ids}) >= 3 for i in ids]
    for i in junk: c.pop(i, None)
    print(f'🧹 {len(junk)} channels with another channel\'s guide dropped', flush=True)
    translate(c, out)
    json.dump({'t': int(now), 'c': c, 'n': {k: v for k, v in names.items() if v in c}}, open(out, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
    import os
    print(f'{len(c)} channels have a guide ({seen_files}/{len(sources)} files read) → {os.path.getsize(out) // 1024} KB')
    by = {}
    for i in c: by[i.rsplit('.', 1)[-1]] = by.get(i.rsplit('.', 1)[-1], 0) + 1
    print('arab:', ' '.join(sorted(i for i in c if i.rsplit('.', 1)[-1] in ('om', 'ae', 'sa', 'qa', 'kw', 'bh', 'eg', 'iq', 'jo', 'lb', 'sy', 'ma', 'dz', 'tn'))))
    print('by country:', ' '.join(f'{k}:{v}' for k, v in sorted(by.items(), key=lambda x: -x[1])[:40]))
    if len(c) < 50: sys.exit('too few channels — the old file is kept')

if __name__ == '__main__': main(sys.argv[1] if len(sys.argv) > 1 else 'epg.json')
