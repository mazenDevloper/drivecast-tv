#!/usr/bin/env python3
"""Which channel carries which match — from the TV guides of the world's sports channels.
The guides: epgshare01's XMLTV files (beIN MENA — the channels of TOD —, the Gulf, Egypt, Sky / TNT UK, Sport TV PT, DAZN ES,
Sky DE / IT, Canal+, beIN TR, Star / Sony / Ten IN, Fox / Kayo AU, the US sports networks…): of each, only its sports channels
and only the programmes that are a match («A v B», «A vs B», «A x B», «A – B»), from three hours ago to a day and a half ahead.
The matches: 365Scores' day (in English and Arabic: the same ids), today and tomorrow. A match is carried by a channel when one
of its programmes, starting around the kick-off, names both sides (or one side and the competition's word «v»).
Out: sports.json = { t, ch: [[name, logo, cc]], g: [[ar home, ar away, en home, en away, kick-off s, [channel index…]]] }
"""
import gzip, io, json, re, sys, time, datetime, urllib.request
import xml.etree.ElementTree as ET

FILES = {   # epgshare01 file → the country its channels are watched in (beIN's MENA guide: the Arab region)
  'BEIN1': 'qa', 'AE1': 'ae', 'SA1': 'sa', 'SA2': 'sa', 'EG1': 'eg', 'ALJAZEERA1': 'qa', 'UK1': 'gb', 'IE1': 'ie', 'PT1': 'pt', 'ES1': 'es', 'DE1': 'de',
  'IT1': 'it', 'FR1': 'fr', 'NL1': 'nl', 'BE2': 'be', 'TR1': 'tr', 'TR3': 'tr', 'GR1': 'gr', 'PL1': 'pl', 'RO1': 'ro', 'HR1': 'hr', 'RS1': 'rs', 'SPORTKLUB1': 'rs',
  'IN1': 'in', 'IN2': 'in', 'IN4': 'in', 'PK1': 'pk', 'AU1': 'au', 'NZ1': 'nz', 'JP1': 'jp', 'KR1': 'kr', 'MY1': 'my', 'SG1': 'sg', 'ID1': 'id', 'TH1': 'th',
  'US_SPORTS1': 'us', 'US2': 'us', 'DIRECTVSPORTS1': 'ar', 'CA2': 'ca', 'MX1': 'mx', 'BR1': 'br', 'BR2': 'br', 'AR1': 'ar', 'CL1': 'cl', 'CO1': 'co',
  'ZA1': 'za', 'NG1': 'ng', 'KE1': 'ke', 'SSPORTPLUS1': 'tr', 'THESPORTPLUS1': 'us'}
BASE = 'https://epgshare01.online/epgshare01/epg_ripper_%s.xml.gz'
UA = {'User-Agent': 'Mozilla/5.0 (DriveCastTV guide)'}
SPORTY = re.compile(r'sport|رياض|espn|dazn|\bsky\b|fox ?sport|star ?sports|sony ?(ten|sports)|\bten ?\d|supersport|bein|eleven|tyc|tnt|optus|kayo|setanta|arena ?sport|digi ?sport|canal\+ ?(sport|foot)|movistar|eurosport|\btsn\b|kass|ssc|thmanyah|ad ?sport|dubai ?(sport|racing)|sharjah|premier ?sport|laliga|serie ?a|bundesliga|football|soccer|fútbol|futebol|calcio|stan sport|paramount\+|peacock|fubo|cbs sport|nbc ?sport|bt sport|viaplay|ziggo ?sport|sport ?tv|setanta|polsat ?sport|nova ?sport|cosmote|ert ?sport|s ?sport|tivibu|exxen|astro ?(arena|supersport)|true ?sport|hub ?premier|sportv|premiere|band ?sports|claro ?sport|win sports|directv',
                    re.I)
MATCH = re.compile(r'\S\s+(?:v|vs\.?|x|×|-|–|—|ضد)\s+\S', re.I)

def get(u, t=150): return urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=t).read()
def when(s):
    m = re.match(r'(\d{4})(\d\d)(\d\d)(\d\d)(\d\d)(\d\d)?\s*([+-]\d{4})?', s or '')
    if not m: return 0
    import calendar
    y, mo, d, h, mi, se, tz = m.groups(); t = calendar.timegm((int(y), int(mo), int(d), int(h), int(mi), int(se or 0)))
    if tz: t -= (1 if tz[0] == '+' else -1) * (int(tz[1:3]) * 3600 + int(tz[3:5]) * 60)
    return t
def nice(n, cid):   # some guides name channels by an internal id («4k_DIGITAL_Mono_AR»): the id's readable part then
    if cid.endswith('.bein'):   # beIN MENA's guide (the channels of TOD, Al Kass among them)
        b = cid[:-5]; m = re.search(r'beIN[_-]SPORTS[_-]?(MAX[_-]?)?0?(\d+)', b, re.I)
        if m: return 'beIN SPORTS ' + ('MAX ' if m.group(1) else '') + m.group(2) + (' English' if 'ENGLISH' in b else ' French' if 'FRENCH' in b else '')
        m = re.search(r'Alkass[_-]?(\d+)', b, re.I)
        if m: return 'Alkass ' + m.group(1)
        if re.search(r'^4k', b, re.I): return 'beIN SPORTS 4K'
        if re.search(r'^NEWS', b, re.I): return 'beIN SPORTS News'
    n = (n or '').strip()
    if not n or re.search(r'_|^\d|digital|mono', n, re.I): n = re.sub(r'\.[a-z]{2,3}$', '', cid or n).replace('_', ' ').replace('.', ' ')
    return re.sub(r'\s+', ' ', n).strip()[:48]
STOP = {'fc', 'cf', 'sc', 'ac', 'afc', 'cd', 'club', 'de', 'the', 'united', 'city', 'real', 'sporting', 'athletic', 'w', 'women', 'u21', 'u23', 'u20', 'u19', 'res', 'ii'}
import unicodedata
SYN = [(r'\bturkey\b', 'turkiye'), (r'\bczech republic\b', 'czechia'), (r'\bcote d ?ivoire\b', 'ivory coast'), (r'\bkorea republic\b|\brepublic of korea\b', 'south korea'),
       (r'\bunited states\b|\busmnt\b', 'usa'), (r'\bcongo dr\b|\bdemocratic republic of (the )?congo\b', 'dr congo'), (r'\bbosnia( and)? herzegovina\b', 'bosnia'),
       (r'\bman(chester)? utd\b|\bman united\b', 'manchester united'), (r'\bman city\b', 'manchester city'), (r'\bpsg\b', 'paris saint germain'),
       (r'\bspurs\b', 'tottenham'), (r'\binter milan\b', 'inter'), (r'\bbayern munich\b|\bbayern munchen\b|\bfc bayern\b', 'bayern'), (r'\batletico madrid\b', 'atletico')]
def fold(s):
    s = unicodedata.normalize('NFKD', s or '').encode('ascii', 'ignore').decode().lower().replace('-', ' ').replace("'", ' ')
    for p, r in SYN: s = re.sub(p, r, s)
    return s
def words(n): return [w for w in re.findall(r'[a-z0-9]+', fold(n).replace('&', ' ')) if w not in STOP and len(w) > 2]
def names_in(title, n):   # the side's name in a programme title: its whole name, or its longest proper word
    t = ' ' + re.sub(r'[^a-z0-9]+', ' ', fold(title)) + ' '
    full = ' ' + ' '.join(re.findall(r'[a-z0-9]+', fold(n))) + ' '
    if full.strip() and full in t: return 2
    w = sorted(words(n), key=len, reverse=True)
    return 1 if w and len(w[0]) >= 5 and (' ' + w[0] + ' ') in t else 0

def main(out):
    now = time.time(); lo, hi = now - 3 * 3600, now + 36 * 3600
    chans, progs, seen = [], [], {}              # channels [name, logo, cc] · programmes (channel index, start, stop, title)
    for f, cc in FILES.items():
        try: raw = get(BASE % f)
        except Exception as e: print(f'  FAILED {f}: {e}', flush=True); continue
        ids, n0 = {}, len(progs)
        for ev, el in ET.iterparse(gzip.open(io.BytesIO(raw)), events=('end',)):
            if el.tag == 'channel':
                cid, nm = el.get('id', ''), el.findtext('display-name') or ''
                if SPORTY.search(nm + ' ' + cid):
                    ic = el.find('icon'); nn = nice(nm, cid); key = (nn.lower(), cc)
                    if key not in seen: seen[key] = len(chans); chans.append([nn, ic.get('src') if ic is not None else '', cc])
                    ids[cid] = seen[key]
                el.clear()
            elif el.tag == 'programme':
                k = ids.get(el.get('channel'))
                if k is not None:
                    a, b = when(el.get('start')), when(el.get('stop'))
                    if b > lo and a < hi:
                        ti = ' '.join((x.text or '') for x in el.findall('title')[:1])
                        if MATCH.search(ti): progs.append((k, a, b, ti.strip()[:120]))
                el.clear()
        print(f'  {len(ids):4d} sports channels  {len(progs) - n0:5d} matches  {f}', flush=True)
    # the matches of today and tomorrow, in English and Arabic
    games = {}
    for day in (0, 1):
        d = (datetime.datetime.utcnow() + datetime.timedelta(hours=4, days=day)).strftime('%d/%m/%Y')
        for lang in (1, 27):
            try: j = json.loads(get(f'https://webws.365scores.com/web/games/allscores/?appTypeId=5&langId={lang}&timezoneName=Asia/Muscat&userCountryId=1&startDate={d}&endDate={d}&sports=1&showOdds=false', 60))
            except Exception as e: print('  365 FAILED', e); continue
            for g in j.get('games', []):
                r = games.setdefault(g['id'], {'t': when(re.sub(r'[-:T]', '', g.get('startTime', ''))[:14] + ' ' + (g.get('startTime', '')[19:].replace(':', '') or '+0000')) or 0})
                r['en' if lang == 1 else 'ar'] = (g['homeCompetitor']['name'], g['awayCompetitor']['name'])
    print(f'{len(chans)} sports channels, {len(progs)} match programmes, {len(games)} matches', flush=True)
    used, out_g = {}, []
    by_time = sorted(progs, key=lambda p: p[1])
    for gid, g in games.items():
        if 'en' not in g or 'ar' not in g or not g['t']: continue
        (h, a), t = g['en'], g['t']
        hit = []
        for k, s, e, ti in by_time:
            if s > t + 1200: break
            if s < t - 2700 or e < t + 1800: continue
            sc = names_in(ti, h) + names_in(ti, a)
            if sc >= 2 and k not in hit: hit.append(k)
        if not hit: continue
        idx = [used.setdefault(k, len(used)) for k in hit[:12]]
        out_g.append([g['ar'][0], g['ar'][1], h, a, t, idx])
    ch = [None] * len(used)
    for k, i in used.items(): ch[i] = chans[k]
    json.dump({'t': int(now), 'ch': ch, 'g': out_g}, open(out, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
    import os
    print(f'{len(out_g)} matches with their channels, {len(ch)} channels → {os.path.getsize(out) // 1024} KB')
    for g in out_g[:25]: print('  ', g[2], '×', g[3], '→', ', '.join(ch[i][0] + ' (' + ch[i][2] + ')' for i in g[5][:6]))

if __name__ == '__main__': main(sys.argv[1] if len(sys.argv) > 1 else 'sports.json')
