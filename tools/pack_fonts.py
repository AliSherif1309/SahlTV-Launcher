#!/usr/bin/env python3
"""Cloud build only: unlock the Thmanyah font (key = FONT_KEY secret) and put it scrambled into each app.
The font licence allows using it inside apps but not sharing the font files, so:
 - the repository holds only the locked copy (fonts-locked/*.enc, AES-256);
 - each app gets it scrambled (assets/f/*.bin) and unpacks it into its private folder (see Fonts.kt).
Without FONT_KEY nothing happens and the apps use the TV's normal font."""
import os, subprocess, sys

KEY = b"Sahl-TV/thmanyah-font/scramble-v1"   # must match Fonts.KEY
APPS = ["app", "quran", "pdf", "files"]

secret = os.environ.get("FONT_KEY", "")
if not secret:
    print("FONT_KEY not set: building with the TV's normal font")
    sys.exit(0)
for name in ["l", "r", "m", "b", "dm", "db", "tr", "tb"]:
    data = subprocess.run(["openssl", "enc", "-d", "-aes-256-cbc", "-pbkdf2", "-iter", "200000", "-pass", "env:FONT_KEY",
                           "-in", f"fonts-locked/{name}.enc"], check=True, capture_output=True).stdout
    assert data[:4] in (b"OTTO", b"\x00\x01\x00\x00"), "not a font: wrong FONT_KEY?"
    scrambled = bytes(b ^ KEY[i % len(KEY)] for i, b in enumerate(data))
    for app in APPS:
        d = f"{app}/src/main/assets/f"
        os.makedirs(d, exist_ok=True)
        open(f"{d}/{name}.bin", "wb").write(scrambled)
    print(f"font {name}: {len(data)} bytes, packed into {len(APPS)} apps")
