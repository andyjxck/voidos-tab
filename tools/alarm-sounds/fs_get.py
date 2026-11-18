import re, sys, html, json, time, os, urllib.request

UA = {'User-Agent': 'Mozilla/5.0'}

def get(url, binary=False):
    for i in range(4):
        try:
            r = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=40).read()
            return r if binary else r.decode('utf-8', 'replace')
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(15 * (i + 1)); continue
            raise
    raise RuntimeError('rate limited ' + url)

meta = json.load(open('meta.json')) if os.path.exists('meta.json') else {}
for arg in sys.argv[1:]:
    user, sid = arg.split('/')
    if sid in meta:
        continue
    url = f'https://freesound.org/people/{user}/sounds/{sid}/'
    h = get(url)
    title = html.unescape(re.search(r'<title>([^<]*)</title>', h).group(1)).strip()
    lic = re.findall(r'(creativecommons\.org/[a-z/.0-9]+)', h)
    lic_text = re.findall(r'(Creative Commons 0|Attribution NonCommercial|Attribution 4\.0|Attribution 3\.0|Sampling\+|Attribution)', h)
    mp3 = re.search(r'(https://cdn\.freesound\.org/previews/[^"]*-hq\.mp3)', h)
    ogg = re.search(r'(https://cdn\.freesound\.org/previews/[^"]*-hq\.ogg)', h)
    dur = re.search(r'data-duration="([0-9.]+)"', h)
    m = {'id': sid, 'user': user, 'title': title, 'url': url, 'licence_links': sorted(set(lic)), 'licence_words': sorted(set(lic_text)),
         'duration': float(dur.group(1)) if dur else None, 'mp3': mp3.group(1) if mp3 else None, 'ogg': ogg.group(1) if ogg else None}
    src = m['ogg'] or m['mp3']
    if src:
        fn = f'raw/{sid}.' + src.rsplit('.', 1)[1]
        os.makedirs('raw', exist_ok=True)
        open(fn, 'wb').write(get(src, True))
        m['file'] = fn
    meta[sid] = m
    json.dump(meta, open('meta.json', 'w'), indent=1)
    print(sid, title, m['licence_links'], m['duration'], m.get('file'))
    time.sleep(3)
