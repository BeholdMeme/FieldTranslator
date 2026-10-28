"""Draws the app icon (two speech bubbles: red = Russian, blue = English) as PNGs.
Usage: python make_icons.py OUT_DIR"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

BG = (14, 17, 22)
RU = (229, 72, 77)
EN = (62, 140, 255)


def font(size):
    for f in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "DejaVuSans-Bold.ttf", "arialbd.ttf"):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            pass
    return ImageFont.load_default()


def draw(size):
    s = 4  # supersample
    n = size * s
    im = Image.new("RGB", (n, n), BG)
    d = ImageDraw.Draw(im)
    u = n / 100.0

    def bubble(x0, y0, x1, y1, color, tail_left, text):
        d.rounded_rectangle([x0 * u, y0 * u, x1 * u, y1 * u], radius=9 * u, fill=color)
        if tail_left:
            tail = [(x0 + 7) * u, (y1 - 1) * u], [(x0 + 4) * u, (y1 + 9) * u], [(x0 + 17) * u, (y1 - 1) * u]
        else:
            tail = [(x1 - 7) * u, (y1 - 1) * u], [(x1 - 4) * u, (y1 + 9) * u], [(x1 - 17) * u, (y1 - 1) * u]
        d.polygon(tail, fill=color)
        f = font(int(17 * u))
        cx, cy = (x0 + x1) / 2 * u, (y0 + y1) / 2 * u
        d.text((cx, cy), text, font=f, fill=(255, 255, 255), anchor="mm")

    # content kept inside the central 80% so the maskable crop doesn't cut it
    bubble(14, 17, 66, 47, RU, True, "Ру")
    bubble(34, 53, 86, 83, EN, False, "En")
    return im.resize((size, size), Image.LANCZOS)


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    draw(192).save(os.path.join(out, "icon-192.png"))
    draw(512).save(os.path.join(out, "icon-512.png"))
    draw(180).save(os.path.join(out, "apple-touch-icon.png"))


if __name__ == "__main__":
    main()
