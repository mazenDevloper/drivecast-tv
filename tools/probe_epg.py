import re,json,urllib.request,urllib.parse
UA={'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124 Safari/537.36','Accept-Language':'ar'}
def get(u,h=None):
  try: return urllib.request.urlopen(urllib.request.Request(u,headers={**UA,**(h or {})}),timeout=40).read().decode('utf-8','ignore')
  except Exception as e: return 'ERR '+str(e)
print('== bein.com ajax')
for cat in ['sports']:
  u=f'https://www.bein.com/ar/epg-ajax-template/?action=epg_fetch&offset=%2B4&category={cat}&serviceidentity=bein.net&mins=00&cdate=2026-11-19&language=AR&postid=25344&loadindex=0'
  s=get(u); print(len(s), s[:300].replace('\n',' '))
  print(set(re.findall(r'data-img="[^"]*?/([^/"]+?)(?:\.(?:png|svg))', s))[:0] if False else sorted(set(re.findall(r'channel_id=?"?(\w+)',s)))[:40])
  for m in re.findall(r'<li[^>]*parent="slider_(\d+)"[^>]*>.*?<p class=title>([^<]*)</p>.*?<p class=format>([^<]*)</p>.*?<p class=time>([^<]*)</p>',s,re.S)[:15]: print(m)
print('== shahid live channels')
f=json.dumps({"pageNumber":0,"pageSize":200,"productType":"LIVESTREAM","productSubType":"LIVE_CHANNEL"},separators=(',',':'))
s=get('https://api2.shahid.net/proxy/v2.1/product/filter?filter='+urllib.parse.quote(f)+'&country=SA&language=ar')
try:
  j=json.loads(s)
  for p in (j.get('productList') or {}).get('products',[]): print(p.get('id'), p.get('title'))
except Exception as e: print('shahid',e,s[:300])
