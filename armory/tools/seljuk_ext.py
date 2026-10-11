"""
More of the Seljuks (Cultures Armory 1.5): the peoples who fought in and beside the Seljuk armies of the 11th-13th
centuries: the Kipchak (Cuman) horsemen of the steppe, the Arab Bedouin auxiliaries, the Turkmen nomads of the
Anatolian frontier (the uj) and its peasant levies, and the court.

Helmets: the Kipchak helmet (tall and pointed, a steel face mask with brows and moustache, as the masks from the
steppe kurgans); the sharbush (the tall, flaring court cap with a fur band and a gilt plaque at the front, seen in
Seljuk painted ceramics); the Bedouin's head cloth (kufiya) held by a black cord.
Coats: the Kipchak's leather lamellar (hardened leather, lacquered, laced in dyed thongs); the Bedouin's robe with the
striped aba cloak; the khalat (the robe of honour, of brocade with gilt roundels and tiraz bands, given by the
sultan); the kepenek (the Turkmen shepherd's coat of thick felt, square-shouldered).
Legs: shalwar wrapped below the knee in woollen leg wraps (dolak), as the Anatolian peasant wore them.
Feet: çarık (rawhide moccasins laced round the ankle); felt boots. Cloth, felt and leather are dyeable.
"""
from armorgen import Cube, Model, Part
from japan import B, D, G, L, M, gold
from seljuk import (FUR, MAIL, STEEL, arms, coat_model, conical_helmet_model, faces_fill, fur_brim, legs_model, sash,
                    shalwar_leg, skirt, steel_bowl)

RAW = [(150, 120, 84), (178, 146, 106), (200, 172, 130)]          # rawhide
LACQ_LEATHER = [(58, 26, 18), (86, 40, 26), (116, 58, 36), (146, 82, 52)]


# ---------------------------------------------------------------------- painters

def face_mask(f):
    """The Kipchak mask: steel, eye holes, raised brows, a long moustache; the mouth a slit."""
    rows = ["bbb.bb.bbb",
            "s..sss..s.",
            "sssslsssss",
            "smmmsmmmss",
            "ssshhhssss",
            "ssssssssss"]
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face == "front":
                ch = rows[t][i] if t < len(rows) and i < len(rows[0]) else "s"
                if ch == ".":
                    continue
                rgb = {"b": STEEL[4], "s": STEEL[3] if i < f.w // 2 else STEEL[2], "l": STEEL[4], "m": STEEL[1],
                       "h": L[1]}[ch]
            else:
                rgb = STEEL[1]
            f.side(s, t, f.noise(rgb, 2))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, STEEL[3])
            f.bottom(i, j, STEEL[1])


def leather_lamella_px(s, t, row=3):
    """Hardened, lacquered leather lamellae in rows, laced to the next row with dyed thongs."""
    r = t % row
    if r == 0:
        return (D[2] if s % 2 == 0 else D[1]), True
    plate = s % 2 == 0
    return ((LACQ_LEATHER[3] if r == 1 else LACQ_LEATHER[2]) if plate else LACQ_LEATHER[0]), False


def leather_lamellar(f):
    faces_fill(f, lambda face, i, t, s: leather_lamella_px(s, t), lambda i, j: (LACQ_LEATHER[2], False),
               lambda i, j: (LACQ_LEATHER[0], False), noise=2)


def leather_lamellar_body(f):
    faces_fill(f, lambda face, i, t, s: ((G[3] if s % 3 == 1 else B[1]), False) if t == 9 else leather_lamella_px(s, t),
               lambda i, j: (LACQ_LEATHER[2], False), lambda i, j: (LACQ_LEATHER[0], False), noise=2)


def kufiya(f):
    """The head cloth: white, a few faint (dyeable) checks."""
    faces_fill(f, lambda face, i, t, s: ((D[2], True) if (s % 4 == 0 and t % 4 == 0) else (M[2] if (s + t) % 5 else M[1], False)),
               lambda i, j: ((D[2], True) if (i % 4 == 0 and j % 4 == 0) else (M[2], False)), lambda i, j: (M[1], False))


def agal(f):
    faces_fill(f, lambda face, i, t, s: ((40, 36, 34) if t == 0 else (24, 22, 22), False), lambda i, j: ((44, 40, 38), False),
               lambda i, j: ((20, 18, 18), False))


def robe(f):
    """The thawb: undyed white wool, a belt."""
    faces_fill(f, lambda face, i, t, s: (B[1] if t == 8 else M[2] if s % 4 else M[1], False), lambda i, j: (M[2], False),
               lambda i, j: (M[1], False))


def aba(f):
    """The aba: a cloak of camel and goat hair woven in broad vertical stripes (the dark stripes dyeable)."""
    faces_fill(f, lambda face, i, t, s: ((D[2], True) if (s // 3) % 2 == 0 else ((196, 172, 128), False)),
               lambda i, j: ((196, 172, 128), False), lambda i, j: ((150, 128, 92), False))


def brocade(tiraz_rows=(), roundels=True):
    """Silk brocade (dyeable) with gilt roundels in staggered rows; gilt tiraz bands."""
    def paint(f):
        def side(face, i, t, s):
            if t in tiraz_rows or (t - f.h) in tiraz_rows:
                return (G[3] if s % 3 else G[1]), False
            if roundels:
                ci, ct = (s + 2 * ((t // 4) % 2)) % 4, t % 4
                if (ci, ct) in ((1, 1), (2, 1), (1, 2), (2, 2)):
                    return (G[3] if (ci, ct) == (1, 1) else G[2]), False
            return (D[3] if (s + t) % 5 else D[2]), True
        faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: (D[2], True), noise=2)
    return paint


def khalat_body(f):
    """The khalat: crossed over to the right, a gilt border on the edge, roundels, a sash."""
    cx = f.w // 2

    def side(face, i, t, s):
        if t in (8, 9):
            return (D[1] if t == 8 else D[2]), True
        if face == "front" and i == cx + 2 - t // 3:
            return G[3], False
        ci, ct = (s + 2 * ((t // 4) % 2)) % 4, t % 4
        if (ci, ct) in ((1, 1), (2, 1), (1, 2), (2, 2)):
            return (G[3] if (ci, ct) == (1, 1) else G[2]), False
        return (D[3] if (s + t) % 5 else D[2]), True
    faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: (D[2], True), noise=2)


def thick_felt(f):
    """Thick pressed felt (dyeable), mottled, the edge bound."""
    faces_fill(f, lambda face, i, t, s: ((D[2] if t == f.h - 1 else D[4] if (s * 7 + t * 3) % 11 == 0 else D[3]), True),
               lambda i, j: (D[3], True), lambda i, j: (D[2], True), noise=6)


def dolak(f):
    """Shalwar above, dark woollen wraps wound round the shin below, a cord criss-crossed over them."""
    def side(face, i, t, s):
        if t < 7:
            return (D[2] if s % 3 == 0 else D[3]), True
        if (s + t) % 4 == 0 or (s - t) % 4 == 0:
            return RAW[1], False
        return ((52, 46, 42) if (t + s // 2) % 2 else (68, 60, 54)), False
    faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: ((52, 46, 42), False))


def carik_foot(f):
    """The çarık: rawhide, gathered round the foot and laced."""
    faces_fill(f, lambda face, i, t, s: (RAW[2] if t == 0 else RAW[1] if s % 2 else RAW[0], False),
               lambda i, j: (RAW[0] if i in (0, f.w - 1) or j in (0, f.d - 1) else RAW[1], False), lambda i, j: (B[0], False))


def carik_lace(f):
    faces_fill(f, lambda face, i, t, s: (B[1] if (s + t) % 2 else RAW[0], False), lambda i, j: (B[1], False), None)


def felt_boot(f):
    faces_fill(f, lambda face, i, t, s: ((D[4] if t == 0 else D[3] if (s * 5 + t) % 7 else D[2]), True),
               lambda i, j: (D[3], True), lambda i, j: (B[0], False), noise=5)


# ---------------------------------------------------------------------- helmets

def kipchak_model():
    m = conical_helmet_model("KipchakMaskHelmetModel", "kipchak_mask_helmet")
    head = next(r for r in m.roots if r.name == "head")
    head.children = [c for c in head.children if c.name != "nasal"]
    head.children.append(Part("face_mask", cubes=[Cube((-5, -8, -5.3), (10, 6, 1), paint=face_mask)]))
    return m


def sharbush_model():
    """The sharbush: a fur band, the felt crown flaring out above it, a pointed gilt plaque at the front."""
    from seljuk import felt
    head = Part("head", cubes=[
        Cube((-5, -8.5, -5), (10, 2, 10), paint=fur_brim),
        Cube((-4.5, -10.5, -4.5), (9, 2, 9), paint=felt),
        Cube((-5, -12.5, -5), (10, 2, 10), paint=felt),
    ], children=[Part("plaque", cubes=[Cube((-1, -12, -5.6), (2, 3, 1), paint=gold)])])
    return Model("SharbushModel", "sharbush", {"head": head})


def kufiya_model():
    head = Part("head", cubes=[Cube((-4.5, -8.5, -4.5), (9, 4, 9), paint=kufiya),
                               Cube((-4.5, -7, -4.5), (9, 1, 9), inflate=0.25, paint=agal)], children=[
        Part("kufiya_back", pivot=(0, -5, 4.6), rot=(0.2, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, 7, 1), paint=kufiya)]),
        Part("kufiya_right", pivot=(-4.6, -5, 0), rot=(0, 0, 0.15), cubes=[Cube((-0.5, 0, -3.5), (1, 7, 8), paint=kufiya)]),
        Part("kufiya_left", pivot=(4.6, -5, 0), rot=(0, 0, -0.15), cubes=[Cube((-0.5, 0, -3.5), (1, 7, 8), paint=kufiya)]),
    ])
    return Model("KufiyaModel", "kufiya", {"head": head})


# ---------------------------------------------------------------------- coats

def kipchak_lamellar_model():
    return coat_model("KipchakLamellarModel", "kipchak_lamellar", leather_lamellar_body, arms(leather_lamellar, split=5,
                      paint_lower=lambda f: brocade(roundels=False)(f)), skirt_paint=leather_lamellar, skirt_len=6)


def bedouin_model():
    cloak = [Part("aba_back", pivot=(0, -0.5, 3.3), rot=(0.06, 0, 0), cubes=[Cube((-5.5, 0, -0.5), (11, 17, 1), paint=aba)]),
             Part("aba_right", pivot=(-5.3, -0.5, 0), rot=(0, 0, 0.05), cubes=[Cube((-0.5, 0, -3.5), (1, 17, 7), paint=aba)]),
             Part("aba_left", pivot=(5.3, -0.5, 0), rot=(0, 0, -0.05), cubes=[Cube((-0.5, 0, -3.5), (1, 17, 7), paint=aba)])]
    return coat_model("BedouinRobeModel", "bedouin_robe", robe, arms(robe), skirt_paint=robe, skirt_len=7, extra=cloak, tex=(128, 64))


def khalat_model():
    return coat_model("KhalatModel", "khalat", khalat_body, arms(brocade(tiraz_rows=(2, 3))), brocade(tiraz_rows=(-1,)), 7)


def kepenek_model():
    """The kepenek: one piece of thick felt, the shoulders standing out square."""
    out = arms(thick_felt)
    for name, sgn in (("right_arm", -1), ("left_arm", 1)):
        out[name].children.append(Part(name.replace("arm", "felt_shoulder"), pivot=(sgn * 1.0, -3, 0),
                                       cubes=[Cube((-3.5, 0, -3.5), (7, 2, 7), paint=thick_felt)]))
    return coat_model("KepenekModel", "kepenek", thick_felt, out, skirt_paint=thick_felt, skirt_len=8)


# ---------------------------------------------------------------------- legs and feet

def carik_model():
    def leg(name):
        return Part(name, cubes=[Cube((-3, 10.5, -3), (6, 1, 6), paint=carik_lace), Cube((-3, 11.5, -3.5), (6, 1, 7), paint=carik_foot)],
                    children=[Part(name.replace("leg", "toe"), cubes=[Cube((-1, 11.5, -4.5), (2, 1, 1), paint=carik_foot)])])
    return Model("CarikModel", "carik", {"right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


def felt_boots_model():
    def leg(name):
        return Part(name, cubes=[Cube((-3, 5.5, -3), (6, 6, 6), paint=felt_boot), Cube((-3, 11.5, -3.5), (6, 1, 7), paint=felt_boot)])
    return Model("FeltBootsModel", "felt_boots", {"right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


# ---------------------------------------------------------------------- the items

CRIMSON = 0x8E2A2A
WHITE = 0xE8E4D8
FELT = 0xD8CCB0         # natural felt
BROWN = 0x6A4A30
INDIGO = 0x2F3E6E
GREEN = 0x2F6B3A
RED = 0xA0402E

ARMOURS = [
    ("kipchak_mask_helmet", kipchak_model, "HELMET", (1.0, 0.0, 260, 3, 10), RED, "ARMOR_EQUIP_IRON"),
    ("sharbush", sharbush_model, "HELMET", (0.0, 0.0, 110, 1, 12), CRIMSON, "ARMOR_EQUIP_LEATHER"),
    ("kufiya", kufiya_model, "HELMET", (0.0, 0.0, 80, 1, 8), WHITE, "ARMOR_EQUIP_LEATHER"),
    ("kipchak_lamellar", kipchak_lamellar_model, "CHESTPLATE", (0.5, 0.0, 250, 5, 9), RED, "ARMOR_EQUIP_LEATHER"),
    ("bedouin_robe", bedouin_model, "CHESTPLATE", (0.0, 0.0, 130, 2, 9), BROWN, "ARMOR_EQUIP_LEATHER"),
    ("khalat", khalat_model, "CHESTPLATE", (0.0, 0.0, 170, 3, 14), GREEN, "ARMOR_EQUIP_LEATHER"),
    ("kepenek", kepenek_model, "CHESTPLATE", (0.0, 0.0, 160, 3, 8), FELT, "ARMOR_EQUIP_LEATHER"),
    ("dolak_shalwar", lambda: legs_model("DolakShalwarModel", "dolak_shalwar", dolak), "LEGGINGS", (0.0, 0.0, 110, 2, 8), WHITE,
     "ARMOR_EQUIP_LEATHER"),
    ("carik", carik_model, "BOOTS", (0.0, 0.0, 70, 1, 8), BROWN, "ARMOR_EQUIP_LEATHER"),
    ("felt_boots", felt_boots_model, "BOOTS", (0.0, 0.0, 110, 1, 8), FELT, "ARMOR_EQUIP_LEATHER"),
]

NAMES = {
    "kipchak_mask_helmet": "Kipchak Masked Helmet", "sharbush": "Sharbush", "kufiya": "Bedouin Kufiya",
    "kipchak_lamellar": "Kipchak Leather Lamellar", "bedouin_robe": "Bedouin Robe and Aba", "khalat": "Khalat",
    "kepenek": "Kepenek", "dolak_shalwar": "Shalwar with Dolak", "carik": "Çarık", "felt_boots": "Felt Boots",
}

_I, _P, _F, _L = "#c:ingots/steel", "#c:plates/steel", "#magistuarmory:woolen_fabrics", "#magistuarmory:leather_strips"
RECIPES = {
    "kipchak_mask_helmet": (["p", "k"], {"p": _P, "k": "culturesarmory:seljuk_conical_helmet"}),
    "sharbush": (["fgf", "fff", "r r"], {"f": _F, "g": "minecraft:gold_nugget", "r": "minecraft:rabbit_hide"}),
    "kufiya": (["www", "wsw"], {"w": "minecraft:white_wool", "s": "minecraft:black_dye"}),
    "kipchak_lamellar": (["l l", "lsl", "lll"], {"l": "minecraft:leather", "s": _L}),
    "bedouin_robe": (["w w", "wbw", "www"], {"w": "minecraft:white_wool", "b": "minecraft:brown_wool"}),
    "khalat": (["f f", "fgf", "fgf"], {"f": _F, "g": "minecraft:gold_ingot"}),
    "kepenek": (["w w", "www", "w w"], {"w": "minecraft:white_wool"}),
    "dolak_shalwar": (["fff", "f f", "b b"], {"f": _F, "b": "minecraft:black_wool"}),
    "carik": (["h h", "l l"], {"h": "minecraft:rabbit_hide", "l": _L}),
    "felt_boots": (["w w", "w w", "l l"], {"w": "minecraft:white_wool", "l": "minecraft:leather"}),
}
