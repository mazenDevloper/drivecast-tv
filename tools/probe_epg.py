import re,gzip,io,urllib.request,concurrent.futures as cf
UA={'User-Agent':'Mozilla/5.0'}
idx=urllib.request.urlopen(urllib.request.Request('https://epgshare01.online/epgshare01/',headers=UA),timeout=60).read().decode('utf-8','ignore')
files=sorted(set(re.findall(r'epg_ripper_([A-Z0-9_]+)\.xml\.gz',idx)))
print(len(files),'files')
PAT=re.compile(r'bein ?sports? ?[4-9]\b|bein.*(max|xtra|türk|turk)|\bssc\b|thmanyah|ثمانية|ksa ?sport|saudi ?sport|on ?time ?sport|\bon ?sport|ktv ?sport|kuwait ?sport|bahrain ?sport|qatar ?sport|arryadia|sky ?sports? ?(main|premier|f1|action|mix)|movistar ?(laliga|liga|champ)|laliga tv|sport ?tv ?\d|supersport|ziggo|starzplay|tod\b|shahid ?sport|nile ?sport|time ?sport|yas\b|alkass|al kass|iraq ?sport|jordan ?sport|lebanon|syria',re.I)
def scan(f):
  try:
    raw=urllib.request.urlopen(urllib.request.Request(f'https://epgshare01.online/epgshare01/epg_ripper_{f}.xml.gz',headers=UA),timeout=120).read()
    s=gzip.decompress(raw).decode('utf-8','ignore')
    out=[]
    for m in re.finditer(r'<channel id="([^"]+)">\s*<display-name[^>]*>([^<]*)<',s):
      if PAT.search(m.group(1)+' '+m.group(2)):
        n=s.count('channel="%s"'%m.group(1)); out.append(f'{f}\t{m.group(1)}\t{m.group(2)}\t{n}')
    return out
  except Exception as e: return [f'{f}\tERR {e}']
with cf.ThreadPoolExecutor(12) as ex:
  for r in ex.map(scan,files):
    for x in r: print(x)
