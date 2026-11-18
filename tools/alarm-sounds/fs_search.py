import re, sys, html, urllib.request, urllib.parse, json

UA = {'User-Agent': 'Mozilla/5.0'}

def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30).read().decode('utf-8', 'replace')

def search(q, n=12):
    url = 'https://freesound.org/search/?' + urllib.parse.urlencode({'q': q, 'f': 'license:"Creative Commons 0"', 's': 'Downloads (most first)'})
    h = get(url)
    out = []
    seen = set()
    for m in re.finditer(r'href="/people/([^/"]+)/sounds/(\d+)/"[^>]*>([^<]*)<', h):
        user, sid, title = m.group(1), m.group(2), html.unescape(m.group(3)).strip()
        if sid in seen or not title:
            continue
        seen.add(sid)
        out.append((sid, user, title))
    return out[:n]

if __name__ == '__main__':
    for q in sys.argv[1:]:
        print('==', q)
        for r in search(q):
            print('  ', *r)
