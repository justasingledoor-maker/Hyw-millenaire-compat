"""
More of the Arctic (Cultures Armory 1.5), after the clothing collected from Greenland to the Bering Strait.

Hood: a hood ruffed with wolverine fur (dark, a pale band; frost does not cling to it), the ruff of the best-dressed
hunters. Parkas: the bird-skin parka of Greenland and the Bering Strait (eider or murre skins sewn feathers inward,
the dark skins outside); the gut-skin parka (kamleika: strips of seal or walrus intestine, translucent and waterproof,
worn over the fur in rain and at sea, the seams trimmed with dyed sinew and tufts). Legs: polar-bear trousers
(nanukkut), worn by the Polar Inuit of north-west Greenland. Feet: polar-bear kamiks, the fur outside.
"""
from armorgen import Cube, Model, Part
from japan import D
from inuit import CARIBOU, crimped_sole, fur, hood, parka_model, trousers
from seljuk import faces_fill

WOLVERINE = [(40, 28, 20), (62, 44, 30), (92, 66, 44), (196, 170, 120)]
POLAR = [(196, 190, 170), (214, 208, 190), (230, 226, 210), (244, 242, 232)]
BIRD = [(44, 40, 38), (66, 60, 54), (92, 84, 72), (226, 222, 210)]
GUT = [(196, 176, 128), (214, 198, 150), (228, 214, 172), (240, 230, 196)]


def wolverine_ruff(f):
    """Wolverine fur: long dark-brown hairs, a pale band across them."""
    def side(face, i, t, s):
        k = (s * 7 + t * 3) % 9
        if (t + s // 3) % 4 == 1:
            return WOLVERINE[3 if k % 2 else 2], False
        return WOLVERINE[[1, 0, 2, 1, 1, 0, 2, 1, 0][k]], False
    faces_fill(f, side, lambda i, j: (WOLVERINE[[2, 1, 3, 1][(i * 5 + j * 3) % 4]], False), lambda i, j: (WOLVERINE[0], False), noise=8)


def bird_skin(trim_rows=()):
    """Bird skins sewn edge to edge, each skin's dark back and the line of its seam; tufts of white down at the seams."""
    def paint(f):
        def side(face, i, t, s):
            if t in trim_rows or (t - f.h) in trim_rows:
                return (D[3] if s % 2 else D[4]), True
            if s % 5 == 0:
                return (BIRD[3] if t % 3 == 0 else BIRD[0]), False
            return (BIRD[2] if (t + s) % 4 == 0 else BIRD[1]), False
        faces_fill(f, side, lambda i, j: (BIRD[1], False), lambda i, j: (BIRD[0], False), noise=4)
    return paint


def gut_skin(trim_rows=()):
    """Gut skin: translucent horizontal strips, each seam sewn with dyed sinew and set with small tufts."""
    def paint(f):
        def side(face, i, t, s):
            if t in trim_rows or (t - f.h) in trim_rows:
                return (D[3] if s % 2 else D[4]), True
            if t % 3 == 2:
                return ((D[2], True) if s % 4 == 0 else (GUT[0], False))
            return (GUT[3] if t % 3 == 0 and s % 3 == 0 else GUT[2] if (s + t) % 2 else GUT[1]), False
        faces_fill(f, side, lambda i, j: (GUT[2], False), lambda i, j: (GUT[0], False), noise=2)
    return paint


def polar_fur(trim_rows=()):
    return fur(POLAR, trim_rows=trim_rows)


def wolverine_hood_model():
    children = [
        Part("ruff_top", cubes=[Cube((-5, -9.5, -5.5), (10, 2, 2), paint=wolverine_ruff)]),
        Part("ruff_right", cubes=[Cube((-5.5, -7.5, -5.5), (2, 8, 2), paint=wolverine_ruff)]),
        Part("ruff_left", cubes=[Cube((3.5, -7.5, -5.5), (2, 8, 2), paint=wolverine_ruff)]),
    ]
    head = Part("head", cubes=[Cube((-4.5, -8.5, -4.5), (9, 9, 9), paint=hood(CARIBOU))], children=children)
    return Model("WolverineHoodModel", "wolverine_hood", {"head": head})


def polar_kamik_model():
    def leg(name):
        return Part(name, cubes=[Cube((-3, 4.5, -3), (6, 7, 6), paint=polar_fur(trim_rows=(0,))),
                                 Cube((-3, 11.5, -3.5), (6, 1, 7), paint=crimped_sole)],
                    children=[Part(name.replace("leg", "toe"), cubes=[Cube((-1.5, 11.5, -4.5), (3, 1, 1), paint=crimped_sole)])])
    return Model("PolarBearKamikModel", "polar_bear_kamik", {"right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


ARMOURS = [
    ("wolverine_hood", wolverine_hood_model, "HELMET", (0.0, 0.0, 120, 1, 9), 0x8B3A22, "ARMOR_EQUIP_LEATHER"),
    ("bird_skin_parka", lambda: parka_model("BirdSkinParkaModel", "bird_skin_parka", bird_skin(trim_rows=(2, -2)),
                                            bird_skin(trim_rows=(-2,)), bird_skin(trim_rows=(-2,))), "CHESTPLATE",
     (0.0, 0.0, 130, 2, 9), 0xD8D0BE, "ARMOR_EQUIP_LEATHER"),
    ("gut_skin_parka", lambda: parka_model("GutSkinParkaModel", "gut_skin_parka", gut_skin(trim_rows=(1,)), gut_skin(trim_rows=(-1,)),
                                           gut_skin(trim_rows=(-1,)), front=5, back=5, sides=True), "CHESTPLATE",
     (0.0, 0.0, 110, 2, 9), 0x9C3B2A, "ARMOR_EQUIP_LEATHER"),
    ("polar_bear_trousers", lambda: Model("PolarBearTrousersModel", "polar_bear_trousers", trousers(polar_fur(trim_rows=(0,)))),
     "LEGGINGS", (0.0, 0.0, 150, 2, 9), 0x8B3A22, "ARMOR_EQUIP_LEATHER"),
    ("polar_bear_kamik", polar_kamik_model, "BOOTS", (0.0, 0.0, 130, 1, 9), 0x8B3A22, "ARMOR_EQUIP_LEATHER"),
]

NAMES = {
    "wolverine_hood": "Wolverine-Ruffed Hood", "bird_skin_parka": "Bird-Skin Parka", "gut_skin_parka": "Gut-Skin Parka",
    "polar_bear_trousers": "Polar-Bear Trousers", "polar_bear_kamik": "Polar-Bear Kamik",
}

_L, _H = "minecraft:leather", "minecraft:rabbit_hide"
RECIPES = {
    "wolverine_hood": (["lll", "hbh"], {"l": _L, "h": _H, "b": "minecraft:brown_dye"}),
    "bird_skin_parka": (["f f", "fff", "fff"], {"f": "minecraft:feather"}),
    "gut_skin_parka": (["k k", "kkk", "kkk"], {"k": "minecraft:kelp"}),
    "polar_bear_trousers": (["hwh", "h h", "h h"], {"h": _H, "w": "minecraft:white_wool"}),
    "polar_bear_kamik": (["w w", "h h"], {"h": _H, "w": "minecraft:white_wool"}),
}
