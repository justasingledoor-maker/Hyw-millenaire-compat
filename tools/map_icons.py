"""Draws the map icons (1.8, JourneyMap markers): 32x32 white glyphs with a dark outline, tinted on the map.

    python3 tools/map_icons.py   ->  src/main/resources/assets/hywmill/textures/map/*.png
"""
import os
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "hywmill", "textures", "map")
S = 32


def canvas():
    return Image.new("RGBA", (S, S), (0, 0, 0, 0))


def outlined(draw_fn):
    """Draws the glyph in dark at small offsets (the outline), then in white on top."""
    img = canvas()
    d = ImageDraw.Draw(img)
    for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (1, 1), (-1, 1), (1, -1)):
        draw_fn(d, dx, dy, (30, 24, 20, 255))
    draw_fn(d, 0, 0, (255, 255, 255, 255))
    return img


def house(d, x, y, c):
    d.polygon([(6 + x, 15 + y), (16 + x, 6 + y), (26 + x, 15 + y)], fill=c)
    d.rectangle([9 + x, 15 + y, 23 + x, 26 + y], fill=c)


def crown(d, x, y, c):
    d.polygon([(5 + x, 24 + y), (5 + x, 10 + y), (11 + x, 16 + y), (16 + x, 7 + y), (21 + x, 16 + y), (27 + x, 10 + y), (27 + x, 24 + y)], fill=c)


def tower(d, x, y, c):
    d.rectangle([10 + x, 10 + y, 22 + x, 27 + y], fill=c)
    for i in range(3):
        d.rectangle([10 + i * 5 + x, 5 + y, 12 + i * 5 + x, 10 + y], fill=c)


def banner(d, x, y, c):
    d.rectangle([8 + x, 4 + y, 10 + x, 28 + y], fill=c)
    d.polygon([(10 + x, 5 + y), (26 + x, 9 + y), (10 + x, 15 + y)], fill=c)


def swords(d, x, y, c):
    d.line([(6 + x, 6 + y), (26 + x, 26 + y)], fill=c, width=4)
    d.line([(26 + x, 6 + y), (6 + x, 26 + y)], fill=c, width=4)
    d.line([(4 + x, 20 + y), (12 + x, 28 + y)], fill=c, width=3)
    d.line([(28 + x, 20 + y), (20 + x, 28 + y)], fill=c, width=3)


def castle(d, x, y, c):
    d.rectangle([5 + x, 14 + y, 27 + x, 27 + y], fill=c)
    for i in range(4):
        d.rectangle([5 + i * 6 + x, 9 + y, 8 + i * 6 + x, 14 + y], fill=c)


def arrow(d, x, y, c):
    d.polygon([(4 + x, 13 + y), (18 + x, 13 + y), (18 + x, 6 + y), (29 + x, 16 + y), (18 + x, 26 + y), (18 + x, 19 + y), (4 + x, 19 + y)], fill=c)


def star(d, x, y, c):
    import math
    pts = []
    for i in range(10):
        r = 13 if i % 2 == 0 else 5.5
        a = -math.pi / 2 + i * math.pi / 5
        pts.append((16 + x + r * math.cos(a), 16 + y + r * math.sin(a)))
    d.polygon(pts, fill=c)


def shield(d, x, y, c):
    d.polygon([(7 + x, 6 + y), (25 + x, 6 + y), (25 + x, 16 + y), (16 + x, 28 + y), (7 + x, 16 + y)], fill=c)


ICONS = {"village": house, "capital": crown, "province": tower, "vassal": banner, "host": arrow, "siege": castle,
         "battle": swords, "order": star, "relief": shield}

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, fn in ICONS.items():
        outlined(fn).save(os.path.join(OUT, name + ".png"))
        print("wrote", name)
