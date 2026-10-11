"""Draws the Assault Crossbow's two 32x32 item textures (standby and loaded): a repeating crossbow in the vanilla
crossbow's pose (butt bottom right, prod top left), its stock under a long magazine box with iron bands.

    python3 tools/texture.py   (from assault-crossbow/)
"""
import math
from PIL import Image

N = 32
OUT = "src/main/resources/assets/assaultcrossbow/textures/item/"
BUTT = (28.5, 28.5)          # pixel centre of the butt; the stock runs up and left from it
AX = (-1 / math.sqrt(2), -1 / math.sqrt(2))   # along the stock, butt -> nose
PV = (1 / math.sqrt(2), -1 / math.sqrt(2))    # across it (towards the top right)

WOOD = [(0x3E, 0x26, 0x12), (0x5C, 0x3B, 0x1E), (0x7A, 0x52, 0x30)]       # shadow, base, light
BOX = [(0x5E, 0x3C, 0x1C), (0x8F, 0x60, 0x33), (0xB0, 0x7C, 0x46)]
IRON = [(0x4A, 0x4E, 0x55), (0x82, 0x88, 0x90), (0xC4, 0xC9, 0xCF)]
HORN = [(0x24, 0x1E, 0x1C), (0x3E, 0x33, 0x2E), (0x6B, 0x5A, 0x4A)]
STRING = (0xE2, 0xDA, 0xC8)
REST = (0xB4, 0xAA, 0x94)
FLETCH = (0xD8, 0xD8, 0xD8)


def uv(x, y):
    dx, dy = x - BUTT[0], y - BUTT[1]
    return dx * AX[0] + dy * AX[1], dx * PV[0] + dy * PV[1]


def shade(pal, v, half):
    """Light on the top-right edge, shadow on the bottom-left one, base between."""
    t = v / half if half else 0
    return pal[2] if t > 0.45 else pal[0] if t < -0.45 else pal[1]


def draw(loaded):
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    px = img.load()

    def put(x, y, c):
        if 0 <= x < N and 0 <= y < N:
            px[x, y] = c + (255,)

    for y in range(N):
        for x in range(N):
            u, v = uv(x, y)
            c = None
            # stock: a wide butt, then the forestock out to the nose
            if 0 <= u <= 7 and abs(v) <= 2.3:
                c = shade(WOOD, v, 2.3)
            elif 7 < u <= 34 and abs(v) <= 1.5:
                c = shade(WOOD, v, 1.5)
            # the magazine box on top of it, with three iron bands and the feed slot
            if 12 <= u <= 28 and abs(v) <= 2.6:
                c = shade(BOX, v, 2.6)
                if any(abs(u - b) < 0.75 for b in (13.2, 20.0, 26.8)):
                    c = shade(IRON, v, 2.6)
                elif abs(v) < 0.5 and 14 < u < 26:
                    c = BOX[0]
            # the prod across the nose, its tips swept back
            pu = 30.0 - 0.018 * v * v
            if abs(v) <= 13.5 and abs(u - pu) <= 1.0:
                c = HORN[2] if u - pu > 0.3 else HORN[1] if u - pu > -0.4 else HORN[0]
                if abs(v) > 12.3:
                    c = IRON[1]
            # the lever: an iron arm from the box's rear along its side to the prod
            if 10.5 <= u <= 29 and 2.6 < v <= 3.6:
                c = IRON[0] if u < 12 or u > 27.5 else IRON[1]
            if c:
                put(x, y, c)

    # the string: straight behind the prod at rest, drawn back to the latch when loaded
    tips = [(13.0, 30.0 - 0.018 * 169 - 0.9), (-13.0, 30.0 - 0.018 * 169 - 0.9)]
    latch = (0.0, 12.6) if loaded else None
    for tv, tu in tips:
        if loaded:
            a, b = (tu, tv), (latch[1], latch[0])
        else:
            a, b = (tu, tv), (28.6, 0.0)
        steps = 60
        for i in range(steps + 1):
            u = a[0] + (b[0] - a[0]) * i / steps
            v = a[1] + (b[1] - a[1]) * i / steps
            x = BUTT[0] + u * AX[0] + v * PV[0]
            y = BUTT[1] + u * AX[1] + v * PV[1]
            xi, yi = int(round(x)), int(round(y))
            if 0 <= xi < N and 0 <= yi < N:
                uu, vv = uv(xi, yi)
                if loaded:
                    put(xi, yi, STRING)
                elif not (abs(vv) <= 2.6 and 12 <= uu <= 28):   # at rest it runs under the box
                    put(xi, yi, REST)
    if loaded:
        # the next bolt in the groove: steel head past the box, fletching at the latch
        for u in [x * 0.5 for x in range(25, 70)]:
            x = BUTT[0] + u * AX[0]
            y = BUTT[1] + u * AX[1]
            xi, yi = int(round(x)), int(round(y))
            col = IRON[2] if u > 30.5 else IRON[1] if u > 28.2 else FLETCH if u < 15 else None
            if col:
                put(xi, yi, col)
    # a dark rim round the whole shape, inside its edge, as vanilla items have
    a = img.copy().load()
    for y in range(N):
        for x in range(N):
            if a[x, y][3] and any(not (0 <= x + dx < N and 0 <= y + dy < N) or a[x + dx, y + dy][3] == 0
                                  for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                r, g, b, _ = a[x, y]
                if (r, g, b) not in (STRING, REST, FLETCH, IRON[2]):
                    px[x, y] = (int(r * 0.62), int(g * 0.62), int(b * 0.62), 255)
    return img


if __name__ == "__main__":
    draw(False).save(OUT + "assault_crossbow.png")
    draw(True).save(OUT + "assault_crossbow_loaded.png")
    print("wrote", OUT)
