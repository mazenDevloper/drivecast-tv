import json,urllib.request,datetime as dt
UA={'User-Agent':'Mozilla/5.0 Chrome/124','Accept':'application/json'}
def J(u):
  try: return json.loads(urllib.request.urlopen(urllib.request.Request(u,headers=UA),timeout=40).read().decode())
  except Exception as e: return {'ERR':str(e)}
om=lambda t: (dt.datetime.utcfromtimestamp(t)+dt.timedelta(hours=4)).strftime('%a %d %H:%M') if t else '?'
print('== DriveCast /api/matches (today, all)')
d=J('https://drivecast.c-p.workers.dev/api/matches?limit=30&all=1')
for m in (d if isinstance(d,list) else d.get('matches',[d]))[:40]:
  if isinstance(m,dict) and 'home' in m: print(' ',om(m.get('timestamp')),'|',m.get('status'),'|',(m.get('league') or {}).get('name'),'|',(m.get('home') or {}).get('name'),'-',(m.get('away') or {}).get('name'))
  else: print(' ',str(m)[:300])
print('== 365 allscores 20/11/2026 Muscat (selected)')
g=J('https://webws.365scores.com/web/games/allscores/?appTypeId=5&langId=27&timezoneName=Asia/Muscat&userCountryId=1&sports=1&startDate=20/11/2026&endDate=20/11/2026&showOdds=false')
gs=g.get('games',[]); print(' total',len(gs))
import re
for x in gs:
  n=x['homeCompetitor']['name']+' '+x['awayCompetitor']['name']+' '+x.get('competitionDisplayName','')
  if re.search('مانشستر سيتي|فولهام|الطلبة|الزوراء|كوان|شنغهاي|الإنجليزي|السعودي|الإسباني|الإيطالي|الألماني',n):
    print(' ',x['startTime'],'|',x.get('statusText'),'|',x.get('competitionDisplayName'),'|',x['homeCompetitor']['name'],'-',x['awayCompetitor']['name'])
print('== winwin 20261120 (selected)')
w=J('https://www.winwin.com/api/component_data/match_landing_section/82053?path=%2Fmatches&v=01&date=20261120')
def walk(o,out):
  if isinstance(o,dict):
    if 'team1' in o and 'team2' in o: out.append(o)
    else: [walk(v,out) for v in o.values()]
  elif isinstance(o,list): [walk(v,out) for v in o]
ms=[]; walk(w,ms); print(' total',len(ms))
for m in ms:
  n=str(m.get('team1',{}).get('name'))+' '+str(m.get('team2',{}).get('name'))
  if re.search('مانشستر سيتي|فولهام|الطلبة|الزوراء|كوان|شنغهاي',n): print(' ',m.get('date'),m.get('kickoffTime'),m.get('status'),'|',n,'|',list((m.get('channels') or {}).keys())[:3] if isinstance(m.get('channels'),dict) else '')
print('== YouTube leagues live now?')
for h in ['@BarclaysWSL2','@BarclaysWSL','@ligabbvamxfemenil','@kingsleague']:
  try:
    t=urllib.request.urlopen(urllib.request.Request('https://www.youtube.com/'+h+'/live',headers={'User-Agent':'Mozilla/5.0 Chrome/124','Accept-Language':'en'}),timeout=30).read().decode('utf-8','ignore')
    print(' ',h,len(t),'isLiveNow' in t and '"isLiveNow":true' in t, re.findall(r'"scheduledStartTime":"(\d+)"',t)[:1], re.search(r'<title>([^<]*)',t).group(1)[:80] if re.search(r'<title>',t) else '')
  except Exception as e: print(' ',h,'ERR',e)
