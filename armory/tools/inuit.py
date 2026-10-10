"""
The Inuit armours of the Cultures Armory, after the clothing and the war gear of the Arctic peoples (Inuit, Inupiat and
Yup'ik, with the bone and slat armour of the Bering Strait, where the feuds of the whale-hunting villages were fought).

Hoods and helmets: the caribou parka hood with its wolf-fur ruff, the same with wooden snow goggles (slit to cut the
glare), the Yup'ik bentwood hunting visor (painted, with ivory plaques and whiskers), a hide hood sewn over with bone
plates, a helmet of walrus-ivory lamellae. Coats: the caribou parka (atigi) with its long back flap, the sealskin parka,
a ceremonial parka of fur mosaic with ivory pendants, armour of bone lamellae laced over the parka, slat armour of bone
lashed with sinew with the high board that guards the head, walrus-hide armour. Legs: caribou trousers in light and dark
strips, sealskin trousers, bone-plated leggings. Feet: sealskin kamiks with crimped soles, caribou fur kamiks.
The decorative trim strips and the lacing (sinew, rawhide) are dyeable, as are the visor's painted bands.
"""
from armorgen import Cube, Model, Part
from japan import D, L
from seljuk import faces_fill

CARIBOU = [(84, 60, 38), (116, 88, 58), (148, 116, 80), (178, 148, 106), (204, 178, 136)]
PALE = [(150, 140, 120), (176, 168, 150), (200, 192, 176), (224, 218, 204)]          # caribou belly fur
SEAL = [(42, 40, 38), (64, 60, 54), (90, 84, 74), (118, 110, 96), (146, 138, 120)]
WOLF = [(64, 60, 56), (102, 96, 88), (142, 136, 126), (186, 180, 170), (218, 214, 206)]
IVORY = [(146, 134, 104), (180, 168, 136), (206, 196, 166), (230, 222, 196)]
WOOD = [(78, 52, 30), (108, 74, 42), (138, 98, 58), (166, 124, 78)]
HIDE = [(58, 42, 32), (84, 62, 46), (110, 84, 62), (138, 108, 80)]
BEAD = [(54, 92, 140), (196, 60, 46), (228, 224, 212)]


def fur_px(pal, s, t, h, rng_n=0):
    """Fur: hairs in vertical streaks, the tips lighter at the top of each tuft."""
    k = (s * 7 + (t // 2) * 3) % 5
    return pal[[1, 2, 2, 3, 1][k]] if t % 2 else pal[[2, 3, 2, 3, 2][k]]


def seal_px(s, t):
    """Ringed-seal skin: grey with darker rings."""
    k = (s * 5 + t * 3) % 11
    return SEAL[1] if k == 0 else SEAL[2] if k in (1, 6) else SEAL[3]


def lamella_px(s, t, row=3, pal=IVORY):
    """Bone or ivory lamellae in rows, laced to the next row (the lacing dyeable)."""
    r = t % row
    if r == 0:
        return (D[2] if s % 2 == 0 else D[1]), True
    plate = s % 2 == 0
    return ((pal[3] if r == 1 else pal[2]) if plate else pal[0]), False


# ---------------------------------------------------------------------- painters

def fur(pal, trim_rows=(), cuff_rows=()):
    """Fur or hide with dyeable trim strips (rows; negative from the bottom) and pale fur cuffs."""
    def paint(f):
        def side(face, i, t, s):
            if t in trim_rows or (t - f.h) in trim_rows:
                return (D[3] if s % 2 else D[4]), True
            if t in cuff_rows or (t - f.h) in cuff_rows:
                return fur_px(PALE, s, t, f.h), False
            return fur_px(pal, s, t, f.h), False
        faces_fill(f, side, lambda i, j: (fur_px(pal, i, j, f.d), False), lambda i, j: (pal[0], False), noise=4)
    return paint


def sealskin(trim_rows=()):
    def paint(f):
        faces_fill(f, lambda face, i, t, s: ((D[3] if s % 2 else D[4]), True) if (t in trim_rows or (t - f.h) in trim_rows)
                   else (seal_px(s, t), False), lambda i, j: (seal_px(i, j), False), lambda i, j: (SEAL[0], False), noise=3)
    return paint


def hood(pal, open_face=True, plates=False):
    """A hood round the head; its front open on the face, a dyeable trim strip round the neck."""
    def paint(f):
        def side(face, i, t, s):
            if open_face and face == "front" and 2 <= t <= f.h - 2 and 1 <= i <= f.w - 2:
                return None
            if t == f.h - 1:
                return (D[3] if s % 2 else D[4]), True
            if plates and t < f.h - 1:
                return lamella_px(s, t)
            return fur_px(pal, s, t, f.h), False
        faces_fill(f, side, lambda i, j: lamella_px(i, j) if plates else (fur_px(pal, i, j, f.d), False),
                   lambda i, j: (pal[0], False), noise=4)
    return paint


def ruff(f):
    """The wolf-fur ruff round the face: long grizzled hairs, dark at the roots, pale at the tips."""
    def side(face, i, t, s):
        k = (s * 7 + t * 3) % 9
        return WOLF[[3, 3, 2, 4, 3, 1, 3, 2, 4][k]], False
    faces_fill(f, side, lambda i, j: (WOLF[[4, 3, 3, 2][(i * 5 + j * 3) % 4]], False), lambda i, j: (WOLF[1], False), noise=8)


def goggles(f):
    """Wooden snow goggles: a narrow slit for each eye, a sinew strap."""
    def side(face, i, t, s):
        if face == "front" and t == 1 and i in (1, 2, 5, 6):
            return L[0], False
        return (WOOD[2] if t == 0 else WOOD[1]), False
    faces_fill(f, side, lambda i, j: (WOOD[3], False), lambda i, j: (WOOD[0], False), noise=3)


def strap(f):
    faces_fill(f, lambda face, i, t, s: ((140, 112, 72) if s % 2 else (112, 86, 52), False), None, None, noise=2)


def painted_wood(f):
    """Bentwood painted in bands (the paint dyeable), the natural wood between them."""
    def side(face, i, t, s):
        if t == 0:
            return D[4], True
        return ((D[3], True) if (s // 3) % 2 == 0 else (WOOD[2], False))
    faces_fill(f, side, lambda i, j: ((D[3], True) if (j // 2) % 2 == 0 else (WOOD[3], False)), lambda i, j: (WOOD[1], False))


def ivory(f):
    faces_fill(f, lambda face, i, t, s: (IVORY[3] if t == 0 else IVORY[2] if s % 2 else IVORY[1], False),
               lambda i, j: (IVORY[3], False), lambda i, j: (IVORY[0], False), noise=3)


def bead(f):
    faces_fill(f, lambda face, i, t, s: (BEAD[0] if (s + t) % 2 else BEAD[2], False), lambda i, j: (BEAD[2], False),
               lambda i, j: (BEAD[0], False), noise=0)


def bone_lamellar(f):
    faces_fill(f, lambda face, i, t, s: lamella_px(s, t), lambda i, j: (IVORY[2], False), lambda i, j: (IVORY[0], False), noise=2)


def slats(f):
    """Slat armour: horizontal slats of bone, lashed together with sinew (dyeable) at intervals."""
    def side(face, i, t, s):
        if s % 4 == 1:
            return (D[3] if t % 2 else D[2]), True
        if t % 2 == 0:
            return (IVORY[3] if s % 4 == 2 else IVORY[2]), False
        return IVORY[0], False
    faces_fill(f, side, lambda i, j: (IVORY[2], False), lambda i, j: (IVORY[0], False), noise=2)


def walrus_hide(f):
    """Walrus-hide armour: overlapping bands of thick hide, scalloped, lashed with rawhide (dyeable)."""
    def side(face, i, t, s):
        if s % 5 == 2 and t % 3 == 1:
            return D[3], True
        r = t % 3
        if r == 2:
            return (HIDE[0] if s % 2 else HIDE[1]), False
        return (HIDE[3] if r == 0 else HIDE[2]), False
    faces_fill(f, side, lambda i, j: (HIDE[2], False), lambda i, j: (HIDE[0], False), noise=4)


def mosaic(f):
    """The ceremonial parka: caribou fur with insets of pale and dark fur in a zigzag, dyeable trim, ivory pendants."""
    def side(face, i, t, s):
        if t in (2, f.h - 2):
            return (D[3] if s % 2 else D[4]), True
        if t in (3, 4) or t in (f.h - 4, f.h - 3):
            z = (s + (t % 2)) % 4
            return (PALE[2] if z < 2 else CARIBOU[0]), False
        if t == f.h - 1:
            return (IVORY[3] if s % 3 == 0 else CARIBOU[1]), False
        return fur_px(CARIBOU, s, t, f.h), False
    faces_fill(f, side, lambda i, j: (fur_px(CARIBOU, i, j, f.d), False), lambda i, j: (CARIBOU[0], False), noise=3)


def crimped_sole(f):
    """A kamik's sole of bearded-seal skin, its edge crimped into fine pleats."""
    faces_fill(f, lambda face, i, t, s: ((IVORY[1] if s % 2 else HIDE[2]), False),
               lambda i, j: (seal_px(i, j), False), lambda i, j: (HIDE[1], False), noise=2)


# ---------------------------------------------------------------------- hoods and helmets

def ruff_parts():
    return [
        Part("ruff_top", cubes=[Cube((-5, -9.5, -5.5), (10, 2, 2), paint=ruff)]),
        Part("ruff_right", cubes=[Cube((-5.5, -7.5, -5.5), (2, 8, 2), paint=ruff)]),
        Part("ruff_left", cubes=[Cube((3.5, -7.5, -5.5), (2, 8, 2), paint=ruff)]),
    ]


def hood_model(java_name, location, goggle=False):
    children = ruff_parts()
    if goggle:
        children += [Part("goggles", cubes=[Cube((-4, -5.5, -5.2), (8, 2, 1), paint=goggles)]),
                     Part("goggle_strap", cubes=[Cube((-4.5, -5.5, -4.5), (9, 1, 9), inflate=0.1, paint=strap)])]
    head = Part("head", cubes=[Cube((-4.5, -8.5, -4.5), (9, 9, 9), paint=hood(CARIBOU))], children=children)
    return Model(java_name, location, {"head": head})


def visor_model():
    """The Yup'ik bentwood hunting visor: a long painted peak, ivory side plaques, whiskers with beads at the back."""
    head = Part("head", cubes=[Cube((-4.5, -7.5, -4.5), (9, 2, 9), paint=painted_wood)], children=[
        Part("visor_peak", pivot=(0, -6.5, -4.5), rot=(0.32, 0, 0), cubes=[Cube((-4.5, -0.5, -6), (9, 1, 6), paint=painted_wood)]),
        Part("plaque_right", cubes=[Cube((-5.2, -8, 0), (1, 3, 3), paint=ivory)]),
        Part("plaque_left", cubes=[Cube((4.2, -8, 0), (1, 3, 3), paint=ivory)]),
        Part("whisker_right", pivot=(-3.5, -7.5, 4), rot=(-0.6, 0, -0.2), cubes=[Cube((-0.5, -7, -0.5), (1, 7, 1), paint=ivory)],
             children=[Part("bead_right", cubes=[Cube((-0.5, -8, -0.5), (1, 1, 1), paint=bead)])]),
        Part("whisker_left", pivot=(3.5, -7.5, 4), rot=(-0.6, 0, 0.2), cubes=[Cube((-0.5, -7, -0.5), (1, 7, 1), paint=ivory)],
             children=[Part("bead_left", cubes=[Cube((-0.5, -8, -0.5), (1, 1, 1), paint=bead)])]),
    ])
    return Model("BentwoodVisorModel", "bentwood_visor", {"head": head})


def bone_hood_model():
    head = Part("head", cubes=[Cube((-4.5, -8.5, -4.5), (9, 9, 9), paint=hood(HIDE, plates=True))], children=[
        Part("ruff_top", cubes=[Cube((-5, -9.5, -5.5), (10, 1, 2), paint=ruff)]),
    ])
    return Model("BonePlateHoodModel", "bone_plate_hood", {"head": head})


def ivory_helmet_model():
    """A helmet of walrus-ivory lamellae, rounded, with a lamellar neck guard and cheek flaps, fur at the rim."""
    head = Part("head", cubes=[
        Cube((-4.5, -9, -4.5), (9, 4, 9), paint=bone_lamellar),
        Cube((-3.5, -10, -3.5), (7, 1, 7), paint=ivory),
        Cube((-4.5, -5.5, -4.5), (9, 1, 9), inflate=0.15, paint=fur(WOLF)),
    ], children=[
        Part("neck_guard", pivot=(0, -5, 4.6), rot=(0.2, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, 5, 1), paint=bone_lamellar)]),
        Part("cheek_right", pivot=(-4.6, -5, -1.5), rot=(0, 0.15, 0.1), cubes=[Cube((-0.5, 0, -2.5), (1, 4, 5), paint=bone_lamellar)]),
        Part("cheek_left", pivot=(4.6, -5, -1.5), rot=(0, -0.15, -0.1), cubes=[Cube((-0.5, 0, -2.5), (1, 4, 5), paint=bone_lamellar)]),
    ])
    return Model("IvoryLamellarHelmetModel", "ivory_lamellar_helmet", {"head": head})


# ---------------------------------------------------------------------- parkas and armour

def sleeves(paint, cap=None):
    out = {}
    for name, x in (("right_arm", -3.5), ("left_arm", -1.5)):
        children = []
        if cap:
            sgn = -1 if name == "right_arm" else 1
            children.append(Part(name.replace("arm", "shoulder"), pivot=(sgn * 1.0, -3.2, 0), rot=(0, 0, sgn * -0.2),
                                 cubes=[Cube((-3, 0, -3), (6, 4, 6), paint=cap)]))
        out[name] = Part(name, cubes=[Cube((x, -2.5, -2.5), (5, 12, 5), paint=paint)], children=children)
    return out


def flaps(paint, front=3, back=7, sides=False):
    """The parka's flaps: a short one in front, the long tail at the back."""
    out = [Part("flap_front", pivot=(0, 10, -3), rot=(-0.1, 0, 0), cubes=[Cube((-5, 0, -0.5), (10, front, 1), paint=paint)]),
           Part("flap_back", pivot=(0, 10, 3), rot=(0.12, 0, 0), cubes=[Cube((-5, 0, -0.5), (10, back, 1), paint=paint)],
                children=[Part("flap_tail", cubes=[Cube((-2, back, -0.5), (4, 2, 1), paint=paint)])])]
    if sides:
        out += [Part("flap_right", pivot=(-5, 10, 0), rot=(0, 0, 0.12), cubes=[Cube((-0.5, 0, -3), (1, front + 1, 6), paint=paint)]),
                Part("flap_left", pivot=(5, 10, 0), rot=(0, 0, -0.12), cubes=[Cube((-0.5, 0, -3), (1, front + 1, 6), paint=paint)])]
    return out


def parka_model(java_name, location, body_paint, sleeve_paint, flap_paint, extra=(), cap=None, front=3, back=7, sides=False):
    body = Part("body", cubes=[Cube((-5, -1, -3), (10, 11, 6), paint=body_paint)],
                children=flaps(flap_paint, front, back, sides) + list(extra))
    return Model(java_name, location, dict(sleeves(sleeve_paint, cap), body=body))


def pendants():
    """Ivory pendants hanging from the ceremonial parka's hem."""
    out = []
    for k, x in enumerate((-4, -2, 0, 2, 4)):
        out.append(Part(f"pendant_{k}", pivot=(x - 0.5, 10.5, -3.6), cubes=[Cube((0, 0, -0.5), (1, 3, 1), paint=ivory)]))
    return out


def chukchi_wing():
    """The high board over the left shoulder that guards the head (Bering Strait slat armour)."""
    return [Part("head_board", pivot=(5.2, -1, 0), rot=(0, 0, 0.12), cubes=[Cube((-0.5, -9, -3), (1, 11, 6), paint=slats)])]


PARKA = fur(CARIBOU, trim_rows=(2, -2))
PARKA_SLEEVE = fur(CARIBOU, trim_rows=(-3,), cuff_rows=(-1, -2))
PARKA_FLAP = fur(CARIBOU, trim_rows=(-2,))

ARMOURS = [
    ("parka_hood", lambda: hood_model("ParkaHoodModel", "parka_hood"), "HELMET", (0.0, 0.0, 100, 1, 9), 0x8B3A22, "ARMOR_EQUIP_LEATHER"),
    ("snow_goggle_hood", lambda: hood_model("SnowGoggleHoodModel", "snow_goggle_hood", True), "HELMET", (0.0, 0.0, 110, 1, 9),
     0x8B3A22, "ARMOR_EQUIP_LEATHER"),
    ("bentwood_visor", visor_model, "HELMET", (0.0, 0.0, 120, 1, 10), 0x9C3B2A, "ARMOR_EQUIP_LEATHER"),
    ("bone_plate_hood", bone_hood_model, "HELMET", (0.25, 0.0, 180, 2, 9), 0x6E4A2A, "ARMOR_EQUIP_LEATHER"),
    ("ivory_lamellar_helmet", ivory_helmet_model, "HELMET", (0.5, 0.0, 200, 2, 10), 0x6E4A2A, "ARMOR_EQUIP_CHAIN"),
    ("caribou_parka", lambda: parka_model("CaribouParkaModel", "caribou_parka", PARKA, PARKA_SLEEVE, PARKA_FLAP), "CHESTPLATE",
     (0.0, 0.0, 140, 2, 9), 0x8B3A22, "ARMOR_EQUIP_LEATHER"),
    ("sealskin_parka", lambda: parka_model("SealskinParkaModel", "sealskin_parka", sealskin(trim_rows=(2, -2)),
                                           sealskin(trim_rows=(-2,)), sealskin(trim_rows=(-2,))), "CHESTPLATE",
     (0.0, 0.0, 150, 2, 9), 0xD8D0BE, "ARMOR_EQUIP_LEATHER"),
    ("ceremonial_parka", lambda: parka_model("CeremonialParkaModel", "ceremonial_parka", mosaic, PARKA_SLEEVE, mosaic,
                                             extra=pendants()), "CHESTPLATE", (0.0, 0.0, 150, 2, 12), 0x2E5B8A, "ARMOR_EQUIP_LEATHER"),
    ("bone_plate_armour", lambda: parka_model("BonePlateArmourModel", "bone_plate_armour", bone_lamellar, PARKA_SLEEVE,
                                              bone_lamellar, cap=bone_lamellar, front=5, back=5, sides=True),
     "CHESTPLATE", (0.25, 0.0, 220, 5, 9), 0x6E4A2A, "ARMOR_EQUIP_CHAIN"),
    ("slat_armour", lambda: parka_model("SlatArmourModel", "slat_armour", slats, PARKA_SLEEVE, slats, extra=chukchi_wing(),
                                        front=5, back=5, sides=True), "CHESTPLATE", (0.5, 0.0, 240, 5, 9), 0x6E4A2A, "ARMOR_EQUIP_CHAIN"),
    ("walrus_hide_armour", lambda: parka_model("WalrusHideArmourModel", "walrus_hide_armour", walrus_hide, PARKA_SLEEVE,
                                               walrus_hide, cap=walrus_hide, front=5, back=5, sides=True),
     "CHESTPLATE", (0.25, 0.0, 200, 4, 9), 0x6E4A2A, "ARMOR_EQUIP_LEATHER"),
]


def trousers(paint, guard=None):
    def leg(name):
        children = []
        if guard:
            children.append(Part(name.replace("leg", "guard"), pivot=(0, 0, -2.6), rot=(-0.1, 0, 0),
                                 cubes=[Cube((-3, 0, -1.25), (6, 7, 1), paint=guard)]))
        return Part(name, cubes=[Cube((-2.5, -0.5, -2.5), (5, 13, 5), paint=paint)], children=children)
    return {"body": Part("body", cubes=[Cube((-4.5, 9.5, -2.5), (9, 3, 5), paint=paint)]),
            "right_leg": leg("right_leg"), "left_leg": leg("left_leg")}


def strip_fur(f):
    """Caribou trousers sewn of light and dark strips; a dyeable drawstring strip at the top."""
    def side(face, i, t, s):
        if t == 0:
            return (D[3] if s % 2 else D[4]), True
        return fur_px(PALE if (s // 2) % 3 == 0 else CARIBOU, s, t, f.h), False
    faces_fill(f, side, lambda i, j: (CARIBOU[2], False), lambda i, j: (CARIBOU[0], False), noise=3)


def kamik_leg(pal):
    def paint(f):
        def side(face, i, t, s):
            if t == 0:
                return (D[3] if s % 2 else D[4]), True
            if pal == "seal":
                return seal_px(s, t), False
            return fur_px(CARIBOU if (s // 2) % 3 else PALE, s, t, f.h), False
        faces_fill(f, side, lambda i, j: (SEAL[2], False), lambda i, j: (SEAL[0], False), noise=3)
    return paint


def kamik_model(java_name, location, pal, cuff=False):
    def leg(name):
        children = [Part(name.replace("leg", "toe"), cubes=[Cube((-1.5, 11.5, -4.5), (3, 1, 1), paint=crimped_sole)])]
        if cuff:
            children.append(Part(name.replace("leg", "cuff"), cubes=[Cube((-3.5, 4.5, -3.5), (7, 2, 7), paint=ruff)]))
        return Part(name, cubes=[Cube((-3, 5.5, -3), (6, 6, 6), paint=kamik_leg(pal)),
                                 Cube((-3, 11.5, -3.5), (6, 1, 7), paint=crimped_sole)], children=children)
    return Model(java_name, location, {"right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


ARMOURS += [
    ("caribou_trousers", lambda: Model("CaribouTrousersModel", "caribou_trousers", trousers(strip_fur)), "LEGGINGS",
     (0.0, 0.0, 120, 2, 9), 0x8B3A22, "ARMOR_EQUIP_LEATHER"),
    ("sealskin_trousers", lambda: Model("SealskinTrousersModel", "sealskin_trousers", trousers(sealskin(trim_rows=(0,)))), "LEGGINGS",
     (0.0, 0.0, 130, 2, 9), 0xD8D0BE, "ARMOR_EQUIP_LEATHER"),
    ("bone_plate_leggings", lambda: Model("BonePlateLeggingsModel", "bone_plate_leggings", trousers(strip_fur, bone_lamellar)),
     "LEGGINGS", (0.25, 0.0, 180, 3, 9), 0x6E4A2A, "ARMOR_EQUIP_CHAIN"),
    ("sealskin_kamik", lambda: kamik_model("SealskinKamikModel", "sealskin_kamik", "seal"), "BOOTS", (0.0, 0.0, 110, 1, 9),
     0xD8D0BE, "ARMOR_EQUIP_LEATHER"),
    ("caribou_kamik", lambda: kamik_model("CaribouKamikModel", "caribou_kamik", "caribou", cuff=True), "BOOTS",
     (0.0, 0.0, 120, 1, 9), 0x8B3A22, "ARMOR_EQUIP_LEATHER"),
]

NAMES = {
    "parka_hood": "Parka Hood",
    "snow_goggle_hood": "Hood with Snow Goggles",
    "bentwood_visor": "Bentwood Hunting Visor",
    "bone_plate_hood": "Bone-Plated Hood",
    "ivory_lamellar_helmet": "Ivory Lamellar Helmet",
    "caribou_parka": "Caribou Parka",
    "sealskin_parka": "Sealskin Parka",
    "ceremonial_parka": "Ceremonial Parka",
    "bone_plate_armour": "Bone Plate Armour",
    "slat_armour": "Slat Armour",
    "walrus_hide_armour": "Walrus-Hide Armour",
    "caribou_trousers": "Caribou Trousers",
    "sealskin_trousers": "Sealskin Trousers",
    "bone_plate_leggings": "Bone-Plated Leggings",
    "sealskin_kamik": "Sealskin Kamik",
    "caribou_kamik": "Caribou Kamik",
}

_H, _B, _S, _L = "minecraft:rabbit_hide", "minecraft:bone", "minecraft:string", "minecraft:leather"
RECIPES = {
    "parka_hood": (["lll", "h h"], {"l": _L, "h": _H}),
    "snow_goggle_hood": (["k", "w", "s"], {"k": "culturesarmory:parka_hood", "w": "#minecraft:planks", "s": _S}),
    "bentwood_visor": (["ppp", "bsb"], {"p": "#minecraft:planks", "b": _B, "s": _S}),
    "bone_plate_hood": (["bbb", "bkb"], {"b": _B, "k": "culturesarmory:parka_hood"}),
    "ivory_lamellar_helmet": (["bbb", "bsb", "h h"], {"b": "minecraft:bone_block", "s": _S, "h": _H}),
    "caribou_parka": (["l l", "lhl", "lll"], {"l": _L, "h": _H}),
    "sealskin_parka": (["l l", "lkl", "lll"], {"l": _L, "k": "minecraft:cod"}),
    "ceremonial_parka": (["bpb", " d "], {"b": _B, "p": "culturesarmory:caribou_parka", "d": "minecraft:blue_dye"}),
    "bone_plate_armour": (["b b", "bpb", "bbb"], {"b": "minecraft:bone_block", "p": "culturesarmory:caribou_parka"}),
    "slat_armour": (["b b", "sps", "bbb"], {"b": _B, "s": _S, "p": "culturesarmory:caribou_parka"}),
    "walrus_hide_armour": (["l l", "lpl", "lll"], {"l": _L, "p": "culturesarmory:caribou_parka"}),
    "caribou_trousers": (["lhl", "l l", "l l"], {"l": _L, "h": _H}),
    "sealskin_trousers": (["lkl", "l l", "l l"], {"l": _L, "k": "minecraft:cod"}),
    "bone_plate_leggings": (["b b", "btb", "b b"], {"b": _B, "t": "culturesarmory:caribou_trousers"}),
    "sealskin_kamik": (["l l", "k k"], {"l": _L, "k": "minecraft:cod"}),
    "caribou_kamik": (["h h", "l l"], {"l": _L, "h": _H}),
}
