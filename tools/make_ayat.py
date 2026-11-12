"""
Builds app/src/main/assets/ayat.json for the home-screen "Ayah of the day".
The verse text is copied character-for-character from the verified Tanzil Uthmani file
(downloaded and checked earlier in the build); nothing is typed by hand.
Only short verses are kept so they fit the card.
"""
import json, re, sys, unicodedata

TEXT = "quran/src/main/assets/quran-uthmani.txt"
META = "quran/src/main/assets/quran-data.js"
OUT = "app/src/main/assets/ayat.json"

# hopeful, gentle verses (surah, ayah) — the card shows one a day
REFS = [(94,5),(94,6),(2,152),(2,153),(2,186),(3,139),(3,173),(13,28),(20,114),(65,3),(3,8),(2,201),(25,74),(14,7),
        (40,60),(2,45),(29,69),(33,41),(12,87),(15,99),(16,128),(18,10),(20,25),(20,26),(20,27),(20,28),(21,87),(21,89),
        (23,118),(28,24),(55,13),(67,2),(73,8),(87,1),(93,3),(93,5),(93,11),(94,7),(94,8),(97,3),(112,1),(112,2),(112,3),
        (112,4),(1,5),(1,6),(3,200),(6,162),(10,62),(11,115),(20,46),(26,80),(51,56),(52,48),(53,39),(55,60),(71,10),
        (89,27),(89,28),(95,4),(96,1),(99,7),(108,1),(2,155),(3,159),(4,28),(7,56),(17,80),(25,63),(27,62),(29,45),
        (33,56),(39,10),(40,44),(42,19),(47,7),(50,16),(57,4),(64,11),(65,7),(66,8),(76,9),(90,10),(94,1),(2,186),(3,191),
        (14,40),(14,41),(17,24),(19,4),(21,83),(27,19),(46,15),(60,4),(2,250),(3,147),(7,23),(7,126),(10,85),(12,101),
        (23,97),(23,98),(23,109),(25,65),(66,11),(71,28),(113,1),(114,1),(9,51),(9,129),(13,11),(16,97),(17,82),(24,35)]
MAX = 150

ayat = {}
for line in open(TEXT, encoding="utf-8"):
    if not line.strip() or line.startswith("#"): continue
    s, a, txt = line.rstrip("\n").split("|", 2)
    ayat[(int(s), int(a))] = txt
if len(ayat) != 6236: sys.exit("Quran text not complete")

names = {}
try:
    js = open(META, encoding="utf-8").read()
    block = js[js.index("QuranData.Sura"):]
    for i, m in enumerate(re.finditer(r"\[\s*\d+\s*,\s*\d+\s*,\s*\d+\s*,\s*\d+\s*,\s*'([^']*)'\s*,\s*\"([^\"]*)\"", block)):
        names[i + 1] = (m.group(1), m.group(2))
        if i + 1 == 114: break
except Exception as e:
    print("no surah names:", e)

out, seen = [], set()
for s, a in REFS:
    if (s, a) in seen: continue
    seen.add((s, a))
    txt = ayat[(s, a)]
    if s != 1 and s != 9 and a == 1:                    # verse 1 of a surah carries the basmala in this file
        w = txt.split(" ")
        if "".join(c for c in w[0] if not unicodedata.combining(c)) == "بسم" and len(w) > 4: txt = " ".join(w[4:])
    if len(txt) > MAX: continue
    ar_name, en_name = names.get(s, ("", ""))
    out.append({"s": s, "a": a, "text": txt, "ar": ar_name, "en": en_name})
json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False)
print(len(out), "verses kept; e.g.", out[0])
