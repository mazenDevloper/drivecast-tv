import re,json,urllib.request,urllib.parse
UA={'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124 Safari/537.36','Accept-Language':'ar'}
def get(u):
  try: return urllib.request.urlopen(urllib.request.Request(u,headers=UA),timeout=40).read().decode('utf-8','ignore')
  except Exception as e: return 'ERR '+str(e)
u='https://www.bein.com/ar/epg-ajax-template/?action=epg_fetch&offset=%2B4&category=sports&serviceidentity=bein.net&mins=00&cdate=2026-11-19&language=AR&postid=25344&loadindex=0'
s=get(u)
i=s.find('<li'); print(s[i:i+3000])
print('---- imgs'); print(sorted(set(re.findall(r'src="([^"]+?\.(?:png|svg|jpg))"',s)))[:60])
print('---- li count', s.count('<li'))
