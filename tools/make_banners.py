#!/usr/bin/env python3
"""TV banners (320x180 dp, drawn at 640x360) for the Sahl apps: same calm style, name in English and Arabic.
Run once; the PNGs are committed. Needs Pillow with Arabic shaping (raqm)."""
from PIL import Image, ImageDraw, ImageFont

W, H = 640, 360
import os
# Thmanyah Sans when available (FONT_DIR = folder with the .otf files, kept off the repository by its licence);
# only pictures of the text are saved, not the font.
_fd = os.environ.get("FONT_DIR", "")
BOLD = os.path.join(_fd, "thmanyahsans-Bold.otf") if _fd else "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
REG = os.path.join(_fd, "thmanyahsans-Medium.otf") if _fd else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
GOLD, CREAM, CREAM2 = (227, 184, 92), (243, 233, 207), (232, 219, 184)


def base(top, bottom):
    im = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(im)
    for y in range(H):
        k = y / (H - 1)
        d.line([(0, y), (W, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * k) for i in range(3)))
    # thin gold frame line, like the launcher's cards
    d.rounded_rectangle([10, 10, W - 11, H - 11], radius=22, outline=GOLD + (0,), width=2)
    return im, d


def book(d, x, y, s, lines=False):
    """Open book: two cream pages and a gold spine."""
    d.polygon([(x, y + 10 * s), (x + 60 * s, y), (x + 60 * s, y + 110 * s), (x, y + 120 * s)], fill=CREAM)
    d.polygon([(x + 60 * s, y), (x + 120 * s, y + 10 * s), (x + 120 * s, y + 120 * s), (x + 60 * s, y + 110 * s)], fill=CREAM2)
    d.rectangle([x + 58 * s, y, x + 62 * s, y + 110 * s], fill=GOLD)
    if lines:
        for i in range(6):
            yy = y + (28 + i * 14) * s
            d.line([(x + 10 * s, yy + 2 * s), (x + 50 * s, yy - 2 * s)], fill=(160, 150, 120), width=max(1, int(2 * s)))
            d.line([(x + 70 * s, yy - 2 * s), (x + 110 * s, yy + 2 * s)], fill=(160, 150, 120), width=max(1, int(2 * s)))


def folder(d, x, y, s):
    d.rounded_rectangle([x, y, x + 52 * s, y + 24 * s], radius=int(6 * s), fill=(201, 150, 60))
    d.rounded_rectangle([x, y + 14 * s, x + 130 * s, y + 104 * s], radius=int(10 * s), fill=GOLD)
    d.rounded_rectangle([x, y + 30 * s, x + 130 * s, y + 104 * s], radius=int(10 * s), fill=(240, 201, 112))
    # little phone with an arrow: "send from phone"
    px, py = x + 82 * s, y + 46 * s
    d.rounded_rectangle([px, py, px + 34 * s, py + 52 * s], radius=int(6 * s), fill=(14, 38, 31))
    d.polygon([(px + 17 * s, py + 10 * s), (px + 27 * s, py + 24 * s), (px + 7 * s, py + 24 * s)], fill=CREAM)
    d.rectangle([px + 14 * s, py + 24 * s, px + 20 * s, py + 40 * s], fill=CREAM)


def text(d, x, en, ar):
    size = 64
    while ImageFont.truetype(BOLD, size).getlength(en) > W - x - 44:
        size -= 2
    d.text((x, 118 + (64 - size) // 2), en, font=ImageFont.truetype(BOLD, size), fill=(238, 243, 241))
    d.text((x, 200), ar, font=ImageFont.truetype(REG, 38), fill=GOLD, direction="rtl", language="ar", features=["liga"])


def make(path, top, bottom, icon, en, ar):
    im, d = base(top, bottom)
    icon(d)
    text(d, 230, en, ar)
    im.save(path, optimize=True)
    print("wrote", path)


if __name__ == "__main__":
    make("quran/src/main/res/drawable-xhdpi/banner_img.png", (15, 42, 32), (10, 28, 22),
         lambda d: book(d, 52, 112, 1.2), "Sahl Quran", "سهل · القرآن الكريم")
    make("pdf/src/main/res/drawable-xhdpi/banner_img.png", (14, 30, 52), (12, 36, 32),
         lambda d: book(d, 52, 112, 1.2, lines=True), "Sahl PDF", "سهل · كتب على التلفاز")
    make("files/src/main/res/drawable-xhdpi/banner_img.png", (16, 38, 44), (11, 30, 26),
         lambda d: folder(d, 50, 120, 1.25), "Sahl Files", "سهل · الملفات")
