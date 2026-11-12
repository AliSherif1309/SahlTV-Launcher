"""
Downloads the home-screen background photos from Wikimedia Commons at build time.

For every photo it reads the licence from Commons itself and keeps it only if it is
CC BY, CC BY-SA, CC0 or public domain. Each kept photo is fitted to 1920x1080,
the lower part (behind the apps) is softly blurred to keep the screen calm, and
the credits are written to backgrounds.json, which the app shows in About.
"""
import io, json, re, sys, urllib.parse, urllib.request
from PIL import Image, ImageFilter

UA = "SahlTV-build/1.0 (https://github.com/AliSherif1309/SahlTV-Launcher)"
OUT = "app/src/main/assets"
W, H = 1920, 1080

# id, Commons file, English title, Arabic title, vertical focus (0 = top, 0.5 = centre, 1 = bottom), required
PHOTOS = [
    ("kaaba_day", "The_Kaaba_during_Hajj.jpg", "The Kaaba (day)", "الكعبة (نهارًا)", 0.5, True),
    ("kaaba_night", "Mosquée_Masjid_el_Haram_à_la_Mecque.jpg", "The Kaaba (night)", "الكعبة (ليلًا)", 0.15, False),
    ("madinah", "Al-Masjid_an_Nabawi.jpg", "The Prophet's Mosque, Madinah", "المسجد النبوي، المدينة", 0.85, False),
    ("aqsa", "Jerusalem-2013-Temple_Mount-Dome_of_the_Rock_&_Chain_02.jpg", "Al-Aqsa: Dome of the Rock", "الأقصى: قبة الصخرة", 0.22, False),
]
OK = re.compile(r"^(CC BY(-SA)? ?[0-9.]*|CC0|Public domain|PD.*)$", re.I)

def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60).read()

def strip(html):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", html or "")).strip()

credits = []
for pid, fname, en, ar, focus, required in PHOTOS:
    try:
        q = "https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode({
            "action": "query", "format": "json", "prop": "imageinfo", "titles": "File:" + fname,
            "iiprop": "url|size|extmetadata", "iiurlwidth": 2560})
        page = next(iter(json.loads(get(q))["query"]["pages"].values()))
        info = page["imageinfo"][0]; meta = info.get("extmetadata", {})
        lic = strip(meta.get("LicenseShortName", {}).get("value", ""))
        artist = strip(meta.get("Artist", {}).get("value", "")) or "unknown"
        print(f"{pid}: {fname}  {info['width']}x{info['height']}  licence={lic!r}  author={artist!r}")
        if not OK.match(lic):
            raise RuntimeError("licence not allowed: " + lic)
        im = Image.open(io.BytesIO(get(info.get("thumburl") or info["url"]))).convert("RGB")
        s = max(W / im.width, H / im.height)
        im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
        l = (im.width - W) // 2; t = int((im.height - H) * focus)
        im = im.crop((l, t, l + W, t + H))
        mask = Image.linear_gradient("L").resize((W, H)).point(lambda v: 0 if v < 180 else min(255, (v - 180) * 255 // 34))
        im = Image.composite(im.filter(ImageFilter.GaussianBlur(7)), im, mask)
        im.save(f"{OUT}/bg_{pid}.jpg", quality=80, optimize=True, progressive=False)   # baseline: decodes faster on the TV
        credits.append({"id": pid, "en": en, "ar": ar, "author": artist, "licence": lic,
                        "source": "https://commons.wikimedia.org/wiki/File:" + urllib.parse.quote(fname)})
    except Exception as e:
        print(f"  skipped {pid}: {e}")
        if required:
            sys.exit(1)

json.dump(credits, open(f"{OUT}/backgrounds.json", "w"), ensure_ascii=False, indent=1)
print("kept:", [c["id"] for c in credits])
