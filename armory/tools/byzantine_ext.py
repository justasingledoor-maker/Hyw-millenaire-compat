"""
More of Byzantium (Cultures Armory 1.5): the frontier, the palace and the levy, 10th-12th centuries.

Helmets: the 'Phrygian' helmet (its crown raised and curving forward, as in the Balkan and Byzantine finds and the
military saints of the 11th-12th centuries), with a mail aventail; the skiadion (the broad-brimmed hat of officials and
of soldiers on the march, against the sun); the mail coif of the Komnenian army, taken up from its Latin neighbours.
Coats: the akrites' lamellar of hardened leather (the frontier warriors of the eastern marches, of the Digenis Akritas
songs); the excubitor's gilded scale over a white tunic with pteruges (the palace guard); the chiton, the belted
knee-length tunic of the levy and the peasant soldier.
Legs: scale kremasmata (a skirt of scales over the hose). Feet: kampagia (the low strapped shoes of officers).
"""
from armorgen import Cube, Model, Part
from byzantine import (LEATHER_RED, brow_band, pteruges, scale_px, shoulder_pteruges, waist_pteruges, with_children)
from japan import B, D, G, L, M, gold
from seljuk import (MAIL, STEEL, arms, cloth, coat_model, faces_fill, mail, mail_px, sash, shalwar_leg, steel_bowl)

HARD = [(70, 40, 24), (98, 58, 34), (128, 80, 48), (156, 104, 66)]     # boiled leather


# ---------------------------------------------------------------------- painters

def hard_lamella_px(s, t, row=3):
    """Lamellae of boiled leather, laced in rows (the lacing dyeable)."""
    r = t % row
    if r == 0:
        return (D[2] if s % 2 == 0 else D[1]), True
    return ((HARD[3] if r == 1 else HARD[2]) if s % 2 == 0 else HARD[0]), False


def hard_lamellar(f):
    faces_fill(f, lambda face, i, t, s: hard_lamella_px(s, t), lambda i, j: (HARD[2], False), lambda i, j: (HARD[0], False), noise=2)


def akrites_body(f):
    faces_fill(f, lambda face, i, t, s: ((D[3], True) if t == 0 else ((G[3] if s % 3 == 1 else B[1]), False) if t == 9
                                         else hard_lamella_px(s, t - 1)),
               lambda i, j: (D[3], True), lambda i, j: (HARD[0], False), noise=2)


def gilt_scale_body(f):
    """Gilded scales, a white collar (the tunic beneath), a belt."""
    faces_fill(f, lambda face, i, t, s: ((M[2], False) if t == 0 else ((G[3] if s % 3 == 1 else B[1]), False) if t == 9
                                         else scale_px(s, t, gilt=True)),
               lambda i, j: (M[2], False), lambda i, j: (G[1], False), noise=2)


def white_tunic(f):
    faces_fill(f, lambda face, i, t, s: ((D[3] if t == f.h - 1 else M[2] if s % 3 else M[1]), t == f.h - 1),
               lambda i, j: (M[2], False), lambda i, j: (M[1], False))


def chiton_body(f):
    """The chiton: plain wool, two clavi (the vertical stripes from the shoulders), a belt."""
    def side(face, i, t, s):
        if t == 8:
            return B[1], False
        if face in ("front", "back") and i in (2, f.w - 3):
            return D[1], True
        return (D[3] if s % 4 else D[2]), True
    faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: (D[2], True))


def scale_skirt(f):
    faces_fill(f, lambda face, i, t, s: ((G[3] if s % 3 == 1 else B[1]), False) if t == 0 else scale_px(s, t),
               lambda i, j: (STEEL[2], False), lambda i, j: (STEEL[0], False), noise=2)


def felt_hat(f):
    faces_fill(f, lambda face, i, t, s: ((D[4] if t == 0 else D[3] if (s * 5 + t) % 7 else D[2]), True),
               lambda i, j: (D[3] if (i + j) % 5 else D[2], True), lambda i, j: (D[1], True), noise=4)


def mail_hood(f):
    """The coif: mail over the head, an opening for the face, a dyed edge round it."""
    def side(face, i, t, s):
        if face == "front" and 2 <= t <= f.h - 2 and 1 <= i <= f.w - 2:
            return None
        if face == "front" and (t == 1 or i in (0, f.w - 1)):
            return D[3], True
        return mail_px(s, t), False
    faces_fill(f, side, lambda i, j: (mail_px(i, j), False), lambda i, j: (MAIL[0], False), noise=2)


def shoe(f):
    """Kampagia: soft dyed leather, a pearl-studded strap across."""
    faces_fill(f, lambda face, i, t, s: ((M[2] if s % 3 == 1 and t == 0 else D[3]), not (s % 3 == 1 and t == 0)),
               lambda i, j: (D[3] if j < f.d - 1 else D[4], True), lambda i, j: (L[0], False))


def leg_straps(f):
    """The thongs of the kampagia wound up the shin over the hose."""
    def side(face, i, t, s):
        if (s + t) % 4 == 0:
            return D[2], True
        return None
    faces_fill(f, side, None, None, noise=2)


# ---------------------------------------------------------------------- helmets

def phrygian_model():
    """The crown rises and leans forward, ending in a forward-curving point; a gilt brow band; a mail aventail."""
    bowl = steel_bowl(bands=True, gilt_rim=False, rivets=False)
    head = Part("head", cubes=[
        Cube((-4.5, -9, -4.5), (9, 3, 9), paint=bowl),
        Cube((-4.5, -6.5, -4.5), (9, 1, 9), inflate=0.15, paint=brow_band),
        Cube((-3.5, -10.5, -4), (7, 2, 7), paint=bowl),
        Cube((-2.5, -12, -4.5), (5, 2, 5), paint=bowl),
        Cube((-1.5, -13, -5.5), (3, 1, 4), paint=bowl),
        Cube((-0.5, -13, -6.5), (1, 1, 1), paint=gold),
    ], children=[
        Part("aventail_back", pivot=(0, -5.5, 4.6), rot=(0.18, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, 6, 1), paint=mail(1))]),
        Part("aventail_right", pivot=(-4.6, -5.5, 0), rot=(0, 0, 0.12), cubes=[Cube((-0.5, 0, -3.5), (1, 5, 8), paint=mail(1))]),
        Part("aventail_left", pivot=(4.6, -5.5, 0), rot=(0, 0, -0.12), cubes=[Cube((-0.5, 0, -3.5), (1, 5, 8), paint=mail(1))]),
    ])
    return Model("PhrygianHelmetModel", "phrygian_helmet", {"head": head})


def skiadion_model():
    head = Part("head", cubes=[
        Cube((-7, -8, -7), (14, 1, 14), paint=felt_hat),
        Cube((-4.5, -10, -4.5), (9, 2, 9), paint=felt_hat),
        Cube((-3.5, -11, -3.5), (7, 1, 7), paint=felt_hat),
        Cube((-4.5, -8.5, -4.5), (9, 1, 9), inflate=0.1, paint=lambda f: faces_fill(
            f, lambda face, i, t, s: (G[3] if s % 3 == 1 else G[2], False), None, None)),
    ])
    return Model("SkiadionModel", "skiadion", {"head": head}, tex=(128, 64))


def mail_coif_model():
    head = Part("head", cubes=[Cube((-4.5, -8.5, -4.5), (9, 9, 9), paint=mail_hood)], children=[
        Part("coif_cape", pivot=(0, 0, 0), cubes=[Cube((-5, 0, -3.5), (10, 2, 8), paint=mail(1))]),
    ])
    return Model("MailCoifModel", "mail_coif", {"head": head})


# ---------------------------------------------------------------------- coats

def akrites_model():
    return coat_model("AkritesLamellarModel", "akrites_lamellar", akrites_body,
                      with_children(arms(cloth(pleats=2, edge_rows=(-1,)), cap=hard_lamellar), {}), skirt_paint=hard_lamellar,
                      skirt_len=6)


def excubitor_model():
    return coat_model("ExcubitorArmourModel", "excubitor_armour", gilt_scale_body,
                      with_children(arms(white_tunic), shoulder_pteruges()), extra=waist_pteruges(5))


def chiton_model():
    return coat_model("ChitonModel", "chiton", chiton_body, arms(cloth(pleats=2, edge_rows=(-1,)), split=6,
                      paint_lower=lambda f: None), skirt_paint=cloth(pleats=3, edge_rows=(-1,)), skirt_len=5)


# ---------------------------------------------------------------------- legs and feet

def scale_kremasmata_model():
    def leg(name):
        return Part(name, cubes=[Cube((-2.5, -0.5, -2.5), (5, 13, 5), paint=shalwar_leg)])
    body = Part("body", cubes=[Cube((-4.5, 9.5, -2.5), (9, 3, 5), paint=sash)], children=[
        Part("kremasmata_front", pivot=(0, 12, -2.6), rot=(-0.1, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, 7, 1), paint=scale_skirt)]),
        Part("kremasmata_back", pivot=(0, 12, 2.6), rot=(0.1, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, 7, 1), paint=scale_skirt)]),
        Part("kremasmata_right", pivot=(-4.6, 12, 0), rot=(0, 0, 0.1), cubes=[Cube((-0.5, 0, -2.5), (1, 7, 5), paint=scale_skirt)]),
        Part("kremasmata_left", pivot=(4.6, 12, 0), rot=(0, 0, -0.1), cubes=[Cube((-0.5, 0, -2.5), (1, 7, 5), paint=scale_skirt)]),
    ])
    return Model("ScaleKremasmataModel", "scale_kremasmata", {"body": body, "right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


def kampagia_model():
    def leg(name):
        return Part(name, cubes=[Cube((-3, 5.5, -3), (6, 5, 6), paint=leg_straps), Cube((-3, 10.5, -3.5), (6, 2, 7), paint=shoe)],
                    children=[Part(name.replace("leg", "toe"), cubes=[Cube((-1, 11.5, -4.5), (2, 1, 1), paint=shoe)])])
    return Model("KampagiaModel", "kampagia", {"right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


# ---------------------------------------------------------------------- the items

CRIMSON = 0x8E2A2A
WHITE = 0xE6E1D3
OCHRE = 0xA8782E
BLUE = 0x2F4B7A
UNDYED = 0xB8A888
SCARLET = 0xB0302A

ARMOURS = [
    ("phrygian_helmet", phrygian_model, "HELMET", (0.75, 0.0, 250, 3, 10), CRIMSON, "ARMOR_EQUIP_IRON"),
    ("skiadion", skiadion_model, "HELMET", (0.0, 0.0, 80, 1, 9), OCHRE, "ARMOR_EQUIP_LEATHER"),
    ("mail_coif", mail_coif_model, "HELMET", (0.5, 0.0, 230, 2, 10), CRIMSON, "ARMOR_EQUIP_CHAIN"),
    ("akrites_lamellar", akrites_model, "CHESTPLATE", (0.5, 0.0, 260, 5, 9), BLUE, "ARMOR_EQUIP_LEATHER"),
    ("excubitor_armour", excubitor_model, "CHESTPLATE", (1.25, 0.0, 330, 7, 14), SCARLET, "ARMOR_EQUIP_GOLD"),
    ("chiton", chiton_model, "CHESTPLATE", (0.0, 0.0, 100, 2, 8), UNDYED, "ARMOR_EQUIP_LEATHER"),
    ("scale_kremasmata", scale_kremasmata_model, "LEGGINGS", (0.75, 0.0, 270, 5, 10), CRIMSON, "ARMOR_EQUIP_CHAIN"),
    ("kampagia", kampagia_model, "BOOTS", (0.0, 0.0, 110, 1, 11), SCARLET, "ARMOR_EQUIP_LEATHER"),
]

NAMES = {
    "phrygian_helmet": "Phrygian Helmet", "skiadion": "Skiadion", "mail_coif": "Mail Coif",
    "akrites_lamellar": "Akritic Leather Lamellar", "excubitor_armour": "Excubitor's Gilded Scale", "chiton": "Chiton",
    "scale_kremasmata": "Scale Kremasmata", "kampagia": "Kampagia",
}

_I, _P, _C, _F = "#c:ingots/steel", "#c:plates/steel", "#magistuarmory:chainmails/steel", "#magistuarmory:woolen_fabrics"
RECIPES = {
    "phrygian_helmet": (["ii ", "pip", "c c"], {"i": _I, "p": _P, "c": _C}),
    "skiadion": ([" f ", "fff"], {"f": _F}),
    "mail_coif": (["ccc", "cfc"], {"c": _C, "f": _F}),
    "akrites_lamellar": (["l l", "lfl", "lll"], {"l": "minecraft:leather", "f": _F}),
    "excubitor_armour": (["g g", "gsg", "ggg"], {"g": "minecraft:gold_nugget", "s": "culturesarmory:scale_armour"}),
    "chiton": (["w w", "www", "w w"], {"w": "minecraft:brown_wool"}),
    "scale_kremasmata": (["fff", "n n", "n n"], {"f": _F, "n": "#c:nuggets/iron"}),
    "kampagia": (["f f", "l l"], {"f": _F, "l": "minecraft:leather"}),
}
