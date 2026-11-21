import urllib.request,re
for h in ['@JLEAGUEInternational','@jleagueintl','@J.LEAGUE.International','@jleague','@JLEAGUEch','channel/UCzRogd_oK3bzKvAW-4aLuPQ','@aleagues','@kleague','@KLEAGUE','@kleague_official','@KLEAGUEInternational','@BarclaysWSL']:
  for suf in ['', '/live']:
    try:
      t=urllib.request.urlopen(urllib.request.Request('https://www.youtube.com/'+h+suf,headers={'User-Agent':'Mozilla/5.0 Chrome/124','Accept-Language':'en'}),timeout=25).read().decode('utf-8','ignore')
      title=(re.search(r'<title>([^<]*)',t) or [0,''])[1][:70]; cid=(re.search(r'"channelId":"(UC[\w-]{22})"',t) or [0,''])[1]
      print(h+suf,'|',title,'|',cid,'| live' if '"isLiveNow":true' in t else '', re.findall(r'"scheduledStartTime":"(\d+)"',t)[:1])
    except Exception as e: print(h+suf,'ERR',str(e)[:60])
