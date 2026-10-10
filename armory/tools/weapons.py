"""
The Japanese weapons: Epic Knights-style item sprites, drawn along the diagonal (the butt at the bottom left, the tip at
the top right), one horizontal run of pixels per step along the axis, as Epic Knights draws its blades and shafts.
Each weapon also names the Epic Knights item whose display transforms (and blocking pose) it borrows, its stats, its
recipe and its Better Combat preset.
"""
import math

from PIL import Image

from japan import G, L, R

# Epic Knights' steel, wood and leather
SA, SB = (59, 59, 59), (41, 41, 41)             # outline: upper-left, lower-right
ST = {"d": (78, 78, 78), "m": (135, 135, 135), "n": (165, 165, 165), "l": (198, 198, 198), "g": (217, 217, 217),
      "w": (236, 236, 236), "i": (98, 96, 94), "j": (64, 62, 60)}
WOOD = [(73, 54, 21), (104, 78, 30), (137, 103, 39), (40, 30, 11)]
LEATH = [(86, 49, 32), (162, 94, 58), (123, 71, 45), (47, 27, 17)]
ITO = (34, 36, 58)                                # the dark silk cord of a sword's grip
SAME = (226, 220, 200)                            # the white rayskin under it
BOWSTRING = (70, 70, 70)
FEATHER = (226, 222, 210)


class Sprite:
    def __init__(self, n):
        self.n = n
        self.px = {}

    def put(self, x, y, rgb):
        if 0 <= x < self.n and 0 <= y < self.n:
            self.px[(x, y)] = rgb

    def run(self, k, cols, center, off=0):
        """Step k along the axis (0 = the butt): a horizontal run of colours, cols[center] on the axis."""
        y = self.n - 1 - k
        x0 = k + off - center
        for i, c in enumerate(cols):
            if c is not None:
                self.put(x0 + i, y, c)

    def perp(self, k, js, off=0, along=0):
        """Pixels across the axis at step k: j steps toward the lower right (negative: upper left)."""
        y0 = self.n - 1 - k
        x0 = k + off
        for j, c in js:
            self.put(x0 + j + along, y0 + j, c)

    def image(self):
        img = Image.new("RGBA", (self.n, self.n), (0, 0, 0, 0))
        for (x, y), c in self.px.items():
            img.putpixel((x, y), tuple(c) + (255,))
        return img


def field(s, colour):
    """Every pixel by its place along the axis (u, in steps from the butt) and across it (w, in pixels, + = lower right)."""
    n = s.n
    for y in range(n):
        for x in range(n):
            u = (x + n - 1 - y) / 2.0
            w = (x - (n - 1 - y)) / math.sqrt(2)
            c = colour(u, w)
            if c is not None:
                s.put(x, y, c)


def edge_px(v, lo, hi, inner):
    """Outline within a pixel of either side (dark on both, as Epic Knights outlines), else the inner colour."""
    if v < lo or v > hi:
        return None
    if v < lo + 0.7:
        return SA
    if v > hi - 0.7:
        return SB
    return inner


def katana_like(n, kashira, tsuka, blade, curve, guard_r, half=1.45):
    """A Japanese sword: kashira (butt cap), tsuka (grip), tsuba (guard), habaki (collar), a curved blade, the kissaki.
    The blade curves toward the edge (lower right), white with the temper line (hamon); the flat by the spine is darker."""
    s = Sprite(n)
    a = kashira + tsuka
    tip = a + 1.5 + blade

    def colour(u, w):
        if u < 0 or u > tip:
            return None
        v = w - curve * math.sin(math.pi * u / tip)
        if u < kashira:
            return edge_px(v, -half, half, ST["i"] if v < 0 else ST["d"])
        if u < a:
            return edge_px(v, -half, half, ITO if int(round(u * 2)) % 2 else SAME)
        if u < a + 1:
            if abs(v) > guard_r:
                return None
            if u < a + 0.5:
                return G[2] if abs(v) > guard_r - 0.75 else ST["j"]
            return SB
        if u < a + 1.5:
            return edge_px(v, -half, half - 0.25, G[3] if v < 0 else G[2])
        hi = half - 0.25
        if u > tip - 2.5:
            hi = hi - (u - (tip - 2.5)) * 1.1
        if hi < -0.6:
            return None
        band = int((u - a) / 2.0) % 3 == 1
        inner = ST["n"] if v < -0.3 else (ST["l"] if band else ST["w"])
        return edge_px(v, -half, hi, inner)
    field(s, colour)
    return s


def shaft(s, k0, k1, cols3, off_of=lambda k: 0):
    for k in range(k0, k1):
        a, b, c, d = cols3
        s.run(k, [a, b if k % 2 else c, d], 1, off_of(k))


LACQ = [L[1], L[3], L[2], L[0]]                   # black-lacquered shaft
SHU = [R[0], R[3], R[2], (40, 10, 8)]             # red-lacquered shaft


def butt_cap(s, k0, k1):
    for k in range(k0, k1):
        s.run(k, [SA, ST["l"] if k % 2 else ST["m"], SB], 1)


def collar(s, k):
    s.run(k, [SA, G[2], G[3], SB], 1)


def naginata():
    s = Sprite(48)
    butt_cap(s, 0, 2)
    shaft(s, 2, 30, SHU)
    for k in (12, 20):
        s.run(k, [SA, G[3], SB], 1)                # lacquer rings
    for k in range(27, 31):                       # the cord binding below the blade
        s.run(k, [SB, LEATH[1] if k % 2 else LEATH[2], SB], 1)
    collar(s, 31)
    b0, tip = 33.0, 46.5

    def colour(u, w):
        if u < 32 or u > tip:
            return None
        if u < 33:                                 # a small guard
            if abs(w) > 1.6:
                return None
            return ST["j"] if u < 32.5 else SB
        t = (u - b0) / (tip - b0)
        v = w + 2.6 * t ** 2                       # the blade sweeps up to the point
        hi = 1.0 + 1.0 * math.sin(math.pi * min(t / 0.8, 1.0) * 0.75)
        if t > 0.8:
            hi = hi - (t - 0.8) / 0.2 * (hi + 1.2)
        if u < b0 + 1:
            return edge_px(v, -1.45, 1.2, G[3] if v < 0 else G[2])
        band = int(u / 2.0) % 3 == 1
        inner = ST["m"] if v < -0.3 else ST["n"] if v < 0.5 else (ST["l"] if band else ST["w"])
        return edge_px(v, -1.45, hi, inner)
    field(s, colour)
    return s


def yari(cross=False):
    s = Sprite(48)
    butt_cap(s, 0, 2)
    shaft(s, 2, 33, LACQ)
    for k in range(29, 33):                       # the cord wrap at the head
        s.run(k, [SB, R[3] if k % 2 else R[2], SB], 1)
    collar(s, 33)
    collar(s, 34)
    head = range(35, 46)
    for k in head:
        i = k - 35
        if i < 8:
            s.run(k, [SA, ST["g"], ST["m"], SB], 1)   # the ridged straight blade (su-yari)
        else:
            s.run(k, [SA, ST["g"], SB], 1)
    s.run(46, [SA], 0)
    if cross:
        # jumonji: two side blades straight across, their points turned a little forward
        k0 = 37
        for j in range(2, 6):
            for sgn in (-1, 1):
                s.perp(k0, [(sgn * j, ST["g"] if sgn < 0 else ST["l"])])
                s.perp(k0, [(sgn * j, SA if sgn < 0 else SB)], along=1)
                s.perp(k0, [(sgn * j, SA if sgn < 0 else SB)], along=-1)
        for sgn in (-1, 1):
            s.perp(k0 + 1, [(sgn * 6, ST["g"])], along=0)
            s.perp(k0 + 2, [(sgn * 6, SA)], along=0)
    return s


def kanabo():
    s = Sprite(32)
    s.run(0, [SA, ST["i"], ST["d"], SB], 1)
    for k in range(1, 8):
        s.run(k, [SB, LEATH[1] if k % 2 else LEATH[2], LEATH[2] if k % 2 else LEATH[1], SB], 1)
    s.run(8, [SA, ST["l"], ST["m"], ST["d"], SB], 2)
    top = 28
    for k in range(9, top):
        w = 4 if k < 12 else 5 if k < 16 else 6
        body = []
        for i in range(w):
            stud = (i % 2 == (k // 2) % 2) and k % 2 == 0 and 0 < i < w - 1
            body.append(ST["l"] if stud else (L[3] if i < w // 2 else L[2]))
        s.run(k, [SA] + body + [SB], (w + 1) // 2)
    s.run(top, [SA, L[3], L[3], L[2], L[2], SB], 3)
    s.run(top + 1, [SA, L[2], SB], 1)
    return s


def wakizashi():
    return katana_like(16, 1, 3, 9.5, 0.5, 2.2)


def katana():
    return katana_like(32, 1.5, 6.5, 17, 1.0, 2.9)


def nodachi():
    return katana_like(32, 1.5, 8.5, 19.5, 1.2, 2.9)


def yumi(pull=None):
    """The asymmetric bow: the grip a third of the way up, black lacquered bamboo, rattan bindings, recurved tips."""
    s = Sprite(32)
    a, b = (1.5, 29.5), (29.5, 1.5)
    draw = [0.0, 1.0, 2.2, 3.4][0 if pull is None else pull + 1]
    h = 5.6 + draw * 0.5

    def bow(t):
        bulge = math.sin(math.pi * t ** 0.85) - 0.18 * math.sin(math.pi * t) ** 8
        x = a[0] + (b[0] - a[0]) * t - h * bulge / math.sqrt(2)
        y = a[1] + (b[1] - a[1]) * t - h * bulge / math.sqrt(2)
        return x, y
    pts = []
    for i in range(400):
        t = i / 399
        x, y = bow(t)
        pts.append((t, int(round(x)), int(round(y))))
    grip_t = (0.30, 0.40)
    for t, x, y in pts:
        if grip_t[0] <= t <= grip_t[1]:
            c = LEATH[1] if int(t * 100) % 2 else LEATH[2]
        elif any(abs(t - w) < 0.012 for w in (0.12, 0.2, 0.52, 0.66, 0.8, 0.9)):
            c = R[3]
        else:
            c = L[3]
        s.put(x, y, c)
        s.put(x - 1, y, SA if c == L[3] else c)
    # the string, from nock to nock, drawn back to the grip when pulled
    ends = [pts[3][1:], pts[-4][1:]]
    gx, gy = bow(0.35)
    if pull is None:
        mids = []
    else:
        d = 2 + 2.2 * draw
        mids = [(gx + d / math.sqrt(2) + 4, gy + d / math.sqrt(2) + 4)]
    path = [ends[0]] + mids + [ends[1]]
    for (x0, y0), (x1, y1) in zip(path, path[1:]):
        steps = int(max(abs(x1 - x0), abs(y1 - y0))) * 2 + 1
        for i in range(steps + 1):
            x = x0 + (x1 - x0) * i / steps
            y = y0 + (y1 - y0) * i / steps
            if (x, y) and (int(round(x)), int(round(y))) not in s.px:
                s.put(int(round(x)), int(round(y)), BOWSTRING)
    if mids:
        # the arrow, nocked on the string, its head past the grip
        mx, my = mids[0]
        for i in range(0, 15):
            x, y = mx - i / math.sqrt(2) * 1.4, my - i / math.sqrt(2) * 1.4
            c = FEATHER if i < 3 else (ST["l"] if i > 12 else WOOD[2])
            s.put(int(round(x)), int(round(y)), c)
    return s


# id: (sprite factory, EK model it borrows the display from, blocks, WeaponType.of(...) args, Better Combat preset,
#      Better Combat range, recipe)
STEEL = "#c:ingots/steel"
WEAPONS = {
    "steel_wakizashi": (wakizashi, "iron_shortsword", False, "3.0F, 1.65F, 0.0F, 0.0F, 1.0F, 0, true",
                        "sword", None, (["  m", " m ", "hl "], {"m": STEEL, "h": "#magistuarmory:hilts",
                                                                 "l": "#magistuarmory:leather_strips"})),
    "steel_katana": (katana, "iron_bastardsword", True, "4.0F, 1.5F, 0.0F, 0.02F, 1.4F, 0, true, 1, 4.0F, true, false, false",
                     "katana", None, (["m", "w"], {"m": STEEL, "w": "culturesarmory:steel_wakizashi"})),
    "steel_nodachi": (nodachi, "iron_claymore", True, "5.2F, 1.2F, 0.5F, 0.08F, 2.4F, 0, true, 2, 5.5F, true, false, false",
                      "claymore", None, (["mk"], {"m": STEEL, "k": "culturesarmory:steel_katana"})),
    "steel_naginata": (naginata, "iron_lochaberaxe", True, "5.5F, 1.1F, 1.5F, 0.05F, 2.8F, 0, true, 2, 3.0F, true, false, false",
                       "glaive", 3.4, (["  w", " s ", "p  "], {"w": "culturesarmory:steel_wakizashi", "s": "#c:rods/wooden",
                                                                "p": "#magistuarmory:poles"})),
    "steel_yari": (lambda: yari(False), "iron_pike", False, "3.0F, 1.2F, 2.5F, 0.0F, 2.4F, 3, true, 2, 0.0F, false, false, false",
                   "spear", 4.0, (["  m", " l ", "p  "], {"m": STEEL, "l": "#magistuarmory:leather_strips",
                                                          "p": "#magistuarmory:poles"})),
    "steel_jumonji_yari": (lambda: yari(True), "iron_ahlspiess", True,
                           "3.2F, 1.15F, 2.0F, 0.02F, 2.8F, 8, true, 2, 3.0F, true, false, false",
                           "spear", 3.8, ([" mm", " lm", "p  "], {"m": STEEL, "l": "#magistuarmory:leather_strips",
                                                                   "p": "#magistuarmory:poles"})),
    "steel_kanabo": (kanabo, "iron_claymore", False, "5.0F, 1.0F, 0.0F, 0.08F, 3.4F, 6, true, 2, 0.0F, false, false, false",
                     "hammer", None, ([" ni", " wn", "h  "], {"n": "#c:nuggets/iron", "i": "#c:ingots/iron",
                                                              "w": "#minecraft:logs", "h": "#magistuarmory:hilts"})),
}
BOW = ("yumi", "400, 34, 4.4F, true", (["bbs", "l s", "bbs"], {"b": "minecraft:bamboo", "s": "minecraft:string",
                                                               "l": "#magistuarmory:leather_strips"}))

WEAPON_NAMES = {
    "steel_wakizashi": "Wakizashi",
    "steel_katana": "Katana",
    "steel_nodachi": "Nodachi",
    "steel_naginata": "Naginata",
    "steel_yari": "Yari",
    "steel_jumonji_yari": "Jūmonji Yari",
    "steel_kanabo": "Kanabō",
    "yumi": "Yumi",
}
