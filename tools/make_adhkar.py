"""
Builds app/src/main/assets/adhkar.json (morning and evening adhkar) from tools/adhkar_src.json.
Quran parts are copied character-for-character from the verified Tanzil file downloaded earlier in the build
(the basmala that this file puts on verse 1 is removed); nothing Quranic is typed by hand.
"""
import json, sys, unicodedata
TEXT = "quran/src/main/assets/quran-uthmani.txt"
SRC = "tools/adhkar_src.json"
OUT = "app/src/main/assets/adhkar.json"
AR = "٠١٢٣٤٥٦٧٨٩"

ayat = {}
for line in open(TEXT, encoding="utf-8"):
    if not line.strip() or line.startswith("#"): continue
    s, a, txt = line.rstrip("\n").split("|", 2)
    ayat[(int(s), int(a))] = txt
if len(ayat) != 6236: sys.exit("Quran text not complete")

def verse(s, a):
    t = ayat[(s, a)]
    if s != 1 and s != 9 and a == 1:
        w = t.split(" ")
        if "".join(c for c in w[0] if not unicodedata.combining(c)) == "بسم" and len(w) > 4: t = " ".join(w[4:])
    return t + " ﴿" + "".join(AR[int(d)] for d in str(a)) + "﴾"

src = json.load(open(SRC, encoding="utf-8"))["items"]
out = {"m": [], "e": []}
for it in src:
    for kind in ("m", "e"):
        if it.get("when") and it["when"] != kind: continue
        if "q" in it:
            s, a1, a2 = it["q"]
            item = {"text": " ".join(verse(s, a) for a in range(a1, a2 + 1)), "quran": True}
        else:
            item = {"text": it.get(kind) or it["t"], "quran": False}
        item["n"] = it["n"]; item["src"] = it["src"]
        out[kind].append(item)
json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False)
print("adhkar:", len(out["m"]), "morning,", len(out["e"]), "evening")
