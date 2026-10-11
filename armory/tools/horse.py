"""
Horse armour in 3D, the way Epic Knights draws its caparison (a model of its own over the horse) but built up further:
each barding is the vanilla horse's part tree (HorseModel.createBodyMesh, 1.21.1, so its parts move with the horse) with
boxes of its own on the body, neck, head and saddle, drawn by the armory's horse layer (client/BardingLayer) over every
horse the game draws with the horse renderer (vanilla horses and HYW's). The item's vanilla armour texture is blank.

Textures are drawn at twice the vanilla density (SCALE texels per model unit): the 128x128 layout is a 256x256 picture.

  cataphract_barding:      three overlapping tiers of lamellae from the back to the knees (red leather lacing, a fringed
                           hem), a two-band lamellar crinet and crest, a steel chamfron with a keel, a gilt boss and pierced
                           eye guards, a nose plate; the kataphraktos' horse after the Praecepta Militaria (covered to
                           the knees, only eyes and nostrils free)
  cataphract_half_barding: a scale peytral in two tiers over the chest and shoulders, a scale crinet, the chamfron
  felt_barding:            a quilted felt trapper in two tiers (dyed), a gold border and a roundel on each flank, a dagged
                           hem, a felt crinet
  All three: a saddle and a red saddle cloth (felt: the trapper is the cloth) when the horse is saddled.
"""
from armorgen import Canvas, Cube, Faces, Model, Part
from japan import B, G
from seljuk import STEEL
from byzantine import LEATHER_RED

SCALE = 2
TEX = (128, 128)
CLOTH = [(78, 16, 20), (112, 26, 30), (146, 38, 40), (178, 58, 54)]     # red saddle cloth (kermes)
LACE = LEATHER_RED


def horse_parts(extra):
    """The vanilla horse's parts (all of them: HorseModel looks each up), the barding's boxes added by part name."""
    def p(name, pivot=(0, 0, 0), rot=(0, 0, 0), children=()):
        return Part(name, pivot, rot, cubes=extra.get(name, []), children=children)
    legs = [("left_hind_leg", (4, 14, 7)), ("right_hind_leg", (-4, 14, 7)),
            ("left_front_leg", (4, 14, -12)), ("right_front_leg", (-4, 14, -12))]
    return ([p("body", (0, 11, 5), children=[p("tail", (0, -5, 2), (0.5236, 0, 0)), p("saddle")]),
             p("head_parts", (0, 4, -12), (0.5236, 0, 0), children=[
                 p(n) for n in ("head", "mane", "upper_mouth", "left_saddle_mouth", "right_saddle_mouth", "left_saddle_line",
                                "right_saddle_line", "head_saddle", "mouth_saddle_wrap")])]
            + [p(n, pv) for n, pv in legs] + [p(n.replace("_leg", "_baby_leg"), pv) for n, pv in legs])


class BardingModel(Model):
    def __init__(self, java_name, location, extra):
        self.java_name, self.location, self.tex = java_name, location, TEX
        self.roots = horse_parts(extra)

    def paint(self):
        canvas = Canvas((self.tex[0] * SCALE, self.tex[1] * SCALE))
        for _, c in self.cubes():
            if c.paint:
                c.paint(Faces(canvas, c, SCALE))
        return canvas


def vanilla_preview():
    """The bare vanilla horse at its texture offsets (the preview draws it under the barding)."""
    def box(origin, size, uv):
        return {"origin": origin, "size": size, "inflate": 0, "uv": uv}

    def part(name, pivot, cubes, rot=(0, 0, 0), children=()):
        return {"name": name, "pivot": pivot, "rot": rot, "cubes": cubes, "children": list(children)}
    legs = [part(n, pv, [box((x0, -1.01, z0), (4, 11, 4), (48, 21))])
            for n, pv, x0, z0 in (("left_hind_leg", (4, 14, 7), -3, -1), ("right_hind_leg", (-4, 14, 7), -1, -1),
                                  ("left_front_leg", (4, 14, -12), -3, -1.9), ("right_front_leg", (-4, 14, -12), -1, -1.9))]
    return {"tex": [64, 64], "parts": [
        part("body", (0, 11, 5), [box((-5, -8, -17), (10, 10, 22), (0, 32))],
             children=[part("tail", (0, -5, 2), [box((-1.5, 0, 0), (3, 14, 4), (42, 36))], rot=(0.5236, 0, 0)),
                       part("saddle", (0, 0, 0), [box((-5, -8, -9), (10, 9, 9), (26, 0))])]),
        part("head_parts", (0, 4, -12), [box((-2.05, -6, -2), (4, 12, 7), (0, 35))], rot=(0.5236, 0, 0), children=[
            part("head", (0, 0, 0), [box((-3, -11, -2), (6, 5, 7), (0, 13))]),
            part("mane", (0, 0, 0), [box((-1, -11, 5.01), (2, 16, 2), (56, 36))]),
            part("upper_mouth", (0, 0, 0), [box((-2, -11, -7), (4, 5, 5), (0, 25))])]),
    ] + legs}


# ---------------------------------------------------------------------- materials (texel coordinates, at SCALE)

def lamella(s, t, tone=STEEL):
    """Lamellae two units wide in rows three units deep, every other row offset by a plate, laced with red leather."""
    r, c = t % 6, (s + 2 * ((t // 6) % 2)) % 4
    if r == 0:
        return LACE[2] if c in (1, 2) else LACE[0]
    if c == 0:
        return tone[0]
    if r == 5:
        return tone[0] if c == 3 else tone[1]
    return [None, tone[4] if r == 1 else tone[3], tone[3] if r == 1 else tone[2], tone[1]][c]


SCALE_SHAPE = [[1, 3, 3, 2], [1, 4, 3, 2], [1, 3, 2, 1], [0, 1, 1, 0]]


def scale_px(s, t, tone=STEEL):
    """Scales two units across in offset rows: lit at the top left, rounded and dark at the foot."""
    return tone[SCALE_SHAPE[t % 4][(s + 2 * ((t // 4) % 2)) % 4]]


def felt_px(s, t):
    """Quilted felt in greys (the dye tints it): stitched lozenges."""
    if (s + t) % 8 == 0 or (s - t) % 8 == 0:
        return (188, 188, 188)
    return (226, 226, 226) if (s * 7 + t * 3) % 5 else (214, 214, 214)


def cloth_px(s, t):
    return CLOTH[2] if (s * 5 + t * 3) % 7 else CLOTH[1]


def gold_band(i, row):
    """A woven gold border, three texels deep: dark edges, lozenges in the middle."""
    if row in (0, 2):
        return CLOTH[0]
    return G[3] if i % 4 in (0, 1) else G[2]


# ---------------------------------------------------------------------- painters

def tier(px, top="full", front=("full", 0), back=("full", 0), hem=None, wrap=SCALE):
    """A tier of the barding: a box round the body, its sides in `px(s, t)`.
    top: "full" (it is the back), "rim" (only its outer ring, the ledge left by the tier above) or None.
    front, back: ("full", _), ("open", rows): only the top `rows` and the corners (`wrap` texels) are drawn, so the legs
    swing through; ("rim", _): an inside wall, only its outer ring. hem(face, i, k, s) -> (rgb, dye) or None (cut away)
    or False (no hem: the material) for the bottom rows, k counted from the bottom; dye: the material is dyed."""
    def paint(f):
        for s in range(f.strip_w):
            face, i = f.face_of(s)
            width = f.d if face in ("right", "left") else f.w
            mode, rows = {"front": front, "back": back}.get(face, ("full", 0))
            for t in range(f.h):
                if mode != "full":
                    corner = i < (1 if mode == "rim" else wrap) or i >= width - (1 if mode == "rim" else wrap)
                    if not corner and t >= (1 if mode == "rim" else rows):
                        continue
                out = hem(face, i, f.h - 1 - t, s) if hem else False
                if out is None:
                    continue
                rgb, dye = out if out is not False else px(s, t)
                f.side(s, t, f.noise(rgb, 2), dye)
        for i in range(f.w):
            for j in range(f.d):
                edge = i == 0 or i == f.w - 1 or j == 0 or j == f.d - 1
                if top == "full" or (top == "rim" and edge):
                    rgb, dye = px(i, j)
                    f.top(i, j, f.noise(rgb, 2), dye)
    return paint


def steel(fn):
    return lambda s, t: (fn(s, t), False)


def fringed(rows=2):
    """A red leather edging `rows` deep, a fringe of thongs below it."""
    def hem(face, i, k, s):
        if k == 0:
            return (LACE[1], False) if s % 2 == 0 else None
        if k <= rows:
            return (LACE[2] if k == rows else LACE[1]), False
        return False
    return hem


def felt_hem(face, i, k, s):
    """Dagged felt below a woven gold border."""
    if k == 0:
        return (felt_px(s, 0), True) if s % 6 in (2, 3) else None
    if k == 1:
        return None if s % 6 in (0, 5) else (felt_px(s, 1), True)
    if 3 <= k <= 5:
        return gold_band(s, k - 3), False
    return False


def chamfron():
    """The steel chamfron over the head: a riveted plate over the forehead, wrapping one unit down the sides, a red
    leather cheek strap; the eyes (and the pierced guards over them) left free."""
    def paint(f):
        for s in range(f.strip_w):
            face, i = f.face_of(s)
            for t in range(f.h):
                if face in ("right", "left"):
                    z = 5 - (i + 0.5) / SCALE if face == "right" else -2 + (i + 0.5) / SCALE
                    if t < SCALE:
                        rgb = STEEL[3] if t == 0 else (STEEL[4] if i % 3 == 1 else STEEL[2])
                    elif -1.5 <= z <= -0.5 and t < f.h - 2:
                        rgb = LACE[2] if i % 2 else LACE[1]                      # cheek strap
                    else:
                        continue
                elif face == "front":
                    if t >= 4 or 2 * SCALE <= i < f.w - 2 * SCALE:
                        continue                                                 # under the muzzle plate
                    rgb = STEEL[3] if t == 0 else STEEL[2]
                else:
                    continue
                f.side(s, t, f.noise(rgb, 2))
        for i in range(f.w):
            for j in range(f.d):
                edge = i in (0, f.w - 1) or j == 0
                rgb = (STEEL[4] if j % 3 == 1 else STEEL[1]) if edge else (STEEL[3] if 3 <= i < f.w - 3 else STEEL[2])
                f.top(i, j, f.noise(rgb, 2))
    return paint


def nose_plate(f):
    """The chamfron's lower plate over the nose: the bridge and the upper sides, rivets along the edge; nostrils free."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face == "front" and (t < 3 or (t == 3 and 1 <= i < f.w - 1)):
                rgb = STEEL[1] if t == 3 else (STEEL[3] if i % 3 else STEEL[4])
            elif face in ("right", "left") and t < SCALE:
                rgb = STEEL[2] if t == 0 else STEEL[1]
            else:
                continue
            f.side(s, t, f.noise(rgb, 2))
    for i in range(f.w):
        for j in range(f.d):
            edge = i in (0, f.w - 1)
            f.top(i, j, f.noise((STEEL[4] if j % 3 == 1 else STEEL[1]) if edge else STEEL[3], 2))


def keel(f):
    """The chamfron's keel down the face, a gilt boss on the brow."""
    for s in range(f.strip_w):
        for t in range(f.h):
            f.side(s, t, f.noise(STEEL[2] if t == 0 else STEEL[1], 2))
    for i in range(f.w):
        for j in range(f.d):
            boss = 5 <= j <= 8
            f.top(i, j, (G[3] if (i + j) % 2 else G[2]) if boss else f.noise(STEEL[4] if i == 0 else STEEL[3], 2))


def eye_guard(f):
    """A pierced eye guard: a steel frame, the eye seen through holes in its face."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face in ("right", "left"):
                inner = 0 < i < f.d - 1 and 0 < t < f.h - 1
                if inner and (i + t) % 2 == 0:
                    continue
                rgb = STEEL[1] if inner else (STEEL[4] if t == 0 else STEEL[2])
            else:
                rgb = STEEL[3] if t == 0 else STEEL[1]
            f.side(s, t, f.noise(rgb, 2))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, STEEL[3])


def leather(f, tone=B):
    for s in range(f.strip_w):
        for t in range(f.h):
            f.side(s, t, f.noise(tone[1] if t < f.h - 1 else tone[0], 3))
    for i in range(f.w):
        for j in range(f.d):
            edge = i in (0, f.w - 1) or j in (0, f.d - 1)
            stitch = edge or ((i == 1 or i == f.w - 2 or j == 1 or j == f.d - 2) and (i + j) % 2 == 0)
            f.top(i, j, f.noise(tone[3] if stitch and not edge else tone[2] if not edge else tone[1], 3))


def saddle_cloth(f):
    """The red saddle cloth, a woven gold border round its edge and along its hem; its ends are inside walls."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        width = f.d if face in ("right", "left") else f.w
        for t in range(f.h):
            if face in ("front", "back") and 0 < i < width - 1 and t > 0:
                continue
            k = f.h - 1 - t
            rgb = gold_band(s, k - 1) if 1 <= k <= 3 else (CLOTH[0] if k == 0 else cloth_px(s, t))
            f.side(s, t, f.noise(rgb, 2))
    for i in range(f.w):
        for j in range(f.d):
            border = min(i, f.w - 1 - i, j, f.d - 1 - j)
            f.top(i, j, f.noise(gold_band(i + j, border - 1) if 1 <= border <= 3 else CLOTH[0] if border == 0 else cloth_px(i, j), 2))


def saddle(cloth=True):
    """The saddle (shown when the horse is saddled, as the vanilla one is): seat, pommel, cantle, and a saddle cloth."""
    cubes = [Cube((-4, -10.2, -10), (8, 1, 9), paint=leather, tag="seat"),
             Cube((-3.5, -11.7, -1.5), (7, 2, 1), paint=leather, tag="cantle"),
             Cube((-2, -11.2, -10.5), (4, 1, 1), paint=lambda f: leather(f, [G[1], G[2], G[3], G[4]]), tag="pommel")]
    if cloth:
        cubes.append(Cube((-6, -9.2, -11), (12, 6, 12), paint=saddle_cloth, tag="saddle_cloth"))
    return cubes


def head_armour(material, eyes=True):
    """Chamfron, keel, eye guards and nose plate; a crinet of `material` in two bands down the neck and a crest."""
    head = [Cube((-3, -11, -2), (6, 5, 7), inflate=0.3, paint=chamfron(), tag="chamfron"),
            Cube((-0.5, -11.8, -7.6), (1, 1, 12), paint=keel, tag="keel")]
    if eyes:
        head += [Cube((2.95, -10.6, 0.2), (1, 2, 2), paint=eye_guard, tag="eye_guard_l"),
                 Cube((-3.95, -10.6, 0.2), (1, 2, 2), paint=eye_guard, tag="eye_guard_r")]
    return {
        "head": head,
        "upper_mouth": [Cube((-2, -11, -7), (4, 5, 5), inflate=0.3, paint=nose_plate, tag="nose_plate")],
        "head_parts": [Cube((-2.05, -6.5, -2), (4, 7, 7), inflate=0.35, paint=tier(material, top="rim"), tag="crinet_upper"),
                       Cube((-2.55, -0.5, -2.5), (5, 7, 8), inflate=0.25, paint=tier(material, top="rim"), tag="crinet_lower")],
        "mane": [Cube((-1, -11, 5.01), (2, 16, 2), inflate=0.45, paint=tier(material), tag="crest")],
    }


# ---------------------------------------------------------------------- the bardings

def cataphract():
    lam = steel(lamella)
    extra = head_armour(lam)
    extra["body"] = [
        Cube((-5.5, -8.6, -18), (11, 7, 24), inflate=0.1, paint=tier(lam), tag="tier1"),
        Cube((-6, -2, -18.5), (12, 6, 25), paint=tier(lam, top="rim", front=("open", 8), back=("open", 8)), tag="tier2"),
        Cube((-6.5, 3.5, -19), (13, 6, 26), paint=tier(lam, top="rim", front=("open", 0), back=("open", 0), hem=fringed()),
             tag="tier3"),
    ]
    extra["saddle"] = saddle()
    return BardingModel("CataphractBardingModel", "cataphract_barding", extra)


def half_barding():
    sc = steel(scale_px)
    extra = head_armour(sc, eyes=False)
    extra["body"] = [
        Cube((-5.5, -8.6, -18), (11, 7, 9), inflate=0.1, paint=tier(sc, back=("rim", 0)), tag="peytral1"),
        Cube((-6, -2, -18.5), (12, 7, 9), paint=tier(sc, top="rim", front=("open", 8), back=("open", 0), hem=fringed()),
             tag="peytral2"),
    ]
    extra["saddle"] = saddle()
    return BardingModel("CataphractHalfBardingModel", "cataphract_half_barding", extra)


def roundel_tier(f_px):
    """The trapper's lower tier: felt, a gold roundel with a cross on each flank, the gold border and the dagged hem."""
    base = tier(f_px, top="rim", front=("open", 8), back=("open", 8), hem=felt_hem)

    def paint(f):
        base(f)
        cy = 7
        for s in range(f.strip_w):
            face, i = f.face_of(s)
            if face not in ("right", "left"):
                continue
            cx = f.d / 2 - 0.5
            for t in range(f.h):
                r2 = (i - cx) ** 2 + (t - cy) ** 2
                ring = 16 <= r2 <= 30
                cross = r2 < 16 and (abs(i - cx) < 1 or abs(t - cy) < 1)
                if ring or cross:
                    f.side(s, t, G[3] if ring and (i + t) % 3 else G[2])
    return paint


def felt():
    fp = lambda s, t: (felt_px(s, t), True)
    neck = {"head_parts": [Cube((-2.05, -6, -2), (4, 12, 7), inflate=0.35, paint=tier(fp, top="rim"), tag="felt_neck")],
            "mane": [Cube((-1, -11, 5.01), (2, 16, 2), inflate=0.45, paint=tier(fp), tag="felt_crest")]}
    neck["body"] = [
        Cube((-5.5, -8.6, -18), (11, 7, 24), inflate=0.1, paint=tier(fp), tag="trapper1"),
        Cube((-6, -2, -18.5), (12, 9, 25), paint=roundel_tier(fp), tag="trapper2"),
    ]
    neck["saddle"] = saddle(cloth=False)
    return BardingModel("FeltBardingModel", "felt_barding", neck)


# id, model, ArmorMaterials holder (the horse's armour value: LEATHER 3, IRON 5, DIAMOND 11), dyeable, default dye
BARDINGS = [
    ("cataphract_barding", cataphract, "DIAMOND", False, None),
    ("cataphract_half_barding", half_barding, "IRON", False, None),
    ("felt_barding", felt, "LEATHER", True, 0x8E2A2A),
]

NAMES = {
    "cataphract_barding": "Kataphraktos Barding",
    "cataphract_half_barding": "Scale Peytral and Chamfron",
    "felt_barding": "Quilted Felt Barding",
}

RECIPES = {
    "cataphract_barding": (["  r", "rrr", "r r"], {"r": "#magistuarmory:lamellar_rows/steel"}),
    "cataphract_half_barding": (["  p", "ppp", "l l"], {"p": "#c:plates/steel", "l": "#magistuarmory:leather_strips"}),
    "felt_barding": (["  f", "fff", "f f"], {"f": "#magistuarmory:woolen_fabrics"}),
}
