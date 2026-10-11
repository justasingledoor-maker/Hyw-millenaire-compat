"""
The Maya armours of the Cultures Armory (1.5): the Postclassic Maya of Yucatan (13th-16th centuries), after the murals
of Chichen Itza and Bonampak, the Madrid and Dresden codices and the conquistadors' accounts of the holkan warriors.

Headdresses: the warrior inside a jaguar's head (its upper jaw over his brow, the pelt down his back); the feathered
serpent's head with quetzal plumes; a tall quetzal-plume crest on a jade band (the nacom's); a quilted cotton cap; the
holkan's cloth band with a flint; a deer's head with antlers. Coats: the quilted cotton armour hardened in brine (the
Spaniards' escaupil, short-sleeved, to the hip); the same under a jaguar pelt; the feathered war suit with its back rack
of plumes; cotton armour with a jade collar and a shell pectoral; the manta cape over a bare chest. Legs: the ex
(loincloth) with its apron, a quilted cotton kilt, a jaguar-skin kilt. Feet: sandals with heel guards, sandals with jade
anklets and knee bands, jaguar-skin leg wraps. Cotton, cloth and feathers are dyeable.
"""
import math

from armorgen import Cube, Model, Part
from japan import B, D, G
from seljuk import arms, coat_model, faces_fill, skirt

JAGUAR = [(196, 140, 62), (172, 116, 46), (120, 76, 30), (34, 26, 20)]       # tawny, deeper, rosette heart, black
QUETZAL = [(22, 96, 64), (34, 132, 84), (70, 172, 104), (120, 206, 130)]
JADE = [(30, 110, 84), (52, 146, 110), (96, 186, 146)]
SHELL = [(226, 216, 196), (244, 238, 222), (200, 186, 160)]
FLINT = [(70, 66, 62), (110, 104, 98), (150, 144, 136)]
DEER = [(122, 86, 52), (150, 110, 70), (176, 140, 98), (226, 214, 196)]
ANTLER = [(200, 186, 160), (226, 214, 190)]
RED = (168, 44, 36)


# ---------------------------------------------------------------------- painters

def jaguar_px(s, t):
    """Jaguar pelt: rosettes (a ring of black spots round a deeper heart) on tawny, in offset rows."""
    cs, ct = 5, 5
    ox = (t // ct) % 2 * 2
    ds, dt = (s + ox) % cs - 2, t % ct - 2
    r2 = ds * ds + dt * dt
    if r2 <= 1:
        return JAGUAR[2]
    if r2 <= 4 and (ds + dt) % 2 == 0:
        return JAGUAR[3]
    return JAGUAR[0] if (s + 3 * t) % 7 else JAGUAR[1]


def jaguar(f):
    faces_fill(f, lambda face, i, t, s: (jaguar_px(s, t), False), lambda i, j: (jaguar_px(i, j), False),
               lambda i, j: (JAGUAR[1], False), noise=3)


def quetzal(f):
    """Quetzal plumes: long green feathers, each with a lit spine."""
    faces_fill(f, lambda face, i, t, s: (QUETZAL[3] if s % 3 == 1 and t % 3 == 0 else QUETZAL[2] if s % 3 == 1 else QUETZAL[1], False),
               lambda i, j: (QUETZAL[2], False), lambda i, j: (QUETZAL[0], False), noise=3)


def dyed_feathers(f):
    """Feathers in rows, each lit at the top (dyeable: the war suit's colour)."""
    faces_fill(f, lambda face, i, t, s: ((D[4] if t % 2 == 0 and s % 2 == 0 else D[3] if t % 2 == 0 else D[2]), True),
               lambda i, j: (D[3], True), lambda i, j: (D[1], True), noise=2)


def cotton(f, hem=True):
    """Quilted cotton armour (dyeable, white by default): thick channels, a dark stitched hem."""
    def side(face, i, t, s):
        if hem and t == f.h - 1:
            return D[1], True
        return ((D[2] if s % 3 == 0 or t % 4 == 0 else D[4] if t % 4 == 1 else D[3]), True)
    faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: (D[2], True))


def bare(f):
    """Nothing painted: the skin shows (a bare forearm or chest)."""
    return None


def jade_collar(f):
    """A collar of jade beads, a white shell pectoral at its heart."""
    cx = (f.w - 1) / 2.0

    def side(face, i, t, s):
        if t == 0:
            return (JADE[2] if s % 2 else JADE[1]), False
        if face == "front" and 1 <= t <= 4 and abs(i - cx) <= 2.6 - abs(t - 2.5) * 0.4:
            return (SHELL[1] if (i + t) % 3 else SHELL[2]), False
        if t in (1, 2) and s % 3 == 0:
            return JADE[0], False
        return None
    faces_fill(f, side, None, None, noise=2)


def cotton_with_collar(f):
    cx = (f.w - 1) / 2.0

    def side(face, i, t, s):
        if t == 0:
            return (JADE[2] if s % 2 else JADE[1]), False
        if face == "front" and 1 <= t <= 4 and abs(i - cx) <= 2.6 - abs(t - 2.5) * 0.4:
            return (SHELL[1] if (i + t) % 3 else SHELL[2]), False
        if t == f.h - 1:
            return D[1], True
        return ((D[2] if s % 3 == 0 or t % 4 == 0 else D[3]), True)
    faces_fill(f, side, lambda i, j: (JADE[1], False), lambda i, j: (D[2], True))


def cape_body(f):
    """The manta cape: it covers the shoulders and back; the chest is bare."""
    def side(face, i, t, s):
        if face == "front" and t > 1:
            return None
        if face in ("right", "left") and t > 4:
            return None
        return ((D[4] if t == 0 else D[2] if s % 4 == 0 else D[3]), True)
    faces_fill(f, side, lambda i, j: (D[3], True), None)


def cape(f):
    faces_fill(f, lambda face, i, t, s: ((D[1] if t == f.h - 1 else D[2] if s % 4 == 0 else D[3]), True),
               lambda i, j: (D[3], True), lambda i, j: (D[2], True))


def band(rgb=None):
    """A cloth band (dyeable), or a jade one."""
    def paint(f):
        if rgb:
            faces_fill(f, lambda face, i, t, s: ((JADE[2] if s % 2 else JADE[1]) if t == 0 else JADE[0], False),
                       lambda i, j: (JADE[1], False), lambda i, j: (JADE[0], False))
        else:
            faces_fill(f, lambda face, i, t, s: ((D[4] if t == 0 else D[2] if s % 3 == 0 else D[3]), True),
                       lambda i, j: (D[3], True), lambda i, j: (D[2], True))
    return paint


def flint(f):
    faces_fill(f, lambda face, i, t, s: (FLINT[2] if (s + t) % 2 else FLINT[1], False), lambda i, j: (FLINT[2], False),
               lambda i, j: (FLINT[0], False), noise=3)


def fangs(f):
    faces_fill(f, lambda face, i, t, s: (SHELL[1], False), lambda i, j: (SHELL[1], False), lambda i, j: (SHELL[2], False), noise=1)


def jaguar_snout(f):
    """The jaguar's upper jaw: pelt above, the black nose at the front, red gums under."""
    def side(face, i, t, s):
        if face == "front" and t == 0 and abs(i - (f.w - 1) / 2.0) < 1.1:
            return JAGUAR[3], False
        return jaguar_px(s, t), False
    faces_fill(f, side, lambda i, j: (jaguar_px(i, j), False), lambda i, j: (RED, False), noise=3)


def eye_disc(f):
    faces_fill(f, lambda face, i, t, s: ((240, 200, 60) if face == "front" else JAGUAR[1], False),
               lambda i, j: (JAGUAR[1], False), lambda i, j: (JAGUAR[1], False), noise=1)


def serpent_scales(f):
    """The feathered serpent's head: green scales, a red mouth, a gold eye."""
    def side(face, i, t, s):
        if face == "front" and t == f.h - 1:
            return RED, False
        if face in ("right", "left") and t == 1 and i in (1, 2):
            return G[3], False
        return (QUETZAL[2] if (s + t) % 2 else QUETZAL[1]), False
    faces_fill(f, side, lambda i, j: ((QUETZAL[3] if (i + j) % 3 == 0 else QUETZAL[2]), False), lambda i, j: (RED, False), noise=2)


def deer_head(f):
    def side(face, i, t, s):
        if face == "front" and t == f.h - 1 and abs(i - (f.w - 1) / 2.0) < 1.1:
            return JAGUAR[3], False
        if face in ("right", "left") and t == 1 and i == 1:
            return JAGUAR[3], False
        return (DEER[1] if t < f.h - 1 else DEER[3]), False
    faces_fill(f, side, lambda i, j: (DEER[2] if (i + j) % 3 else DEER[1], False), lambda i, j: (DEER[3], False), noise=3)


def antler(f):
    faces_fill(f, lambda face, i, t, s: (ANTLER[1] if t == 0 else ANTLER[0], False), lambda i, j: (ANTLER[1], False),
               lambda i, j: (ANTLER[0], False), noise=2)


def wood_frame(f):
    faces_fill(f, lambda face, i, t, s: ((96, 66, 38) if s % 2 else (122, 86, 52), False), lambda i, j: ((122, 86, 52), False),
               lambda i, j: ((96, 66, 38), False))


def sandal_sole(f):
    faces_fill(f, lambda face, i, t, s: (B[2] if t == 0 else B[1], False), lambda i, j: (B[2], False), lambda i, j: (B[0], False))


def heel_guard(f):
    faces_fill(f, lambda face, i, t, s: ((B[2] if face == "back" else B[1]) if t else (RED if s % 3 == 0 else B[2]), False),
               lambda i, j: (B[2], False), lambda i, j: (B[1], False))


def ankle_ties(f):
    """The sandal's ties wound round the ankle (dyeable cotton)."""
    def side(face, i, t, s):
        return ((D[4] if (s + t) % 3 == 0 else D[3]), True) if (s + t) % 2 == 0 else None
    faces_fill(f, side, None, None)


def jade_cuff(f):
    faces_fill(f, lambda face, i, t, s: ((JADE[2] if s % 2 else JADE[1]) if t != 1 else (SHELL[1] if s % 3 == 0 else JADE[0]), False),
               lambda i, j: (JADE[1], False), lambda i, j: (JADE[0], False), noise=2)


# ---------------------------------------------------------------------- headdresses

def jaguar_helmet_model():
    """The warrior's face in the jaguar's jaws: the skull over his head, the upper jaw and fangs over his brow, the pelt
    hanging down his back."""
    head = Part("head", cubes=[
        Cube((-4.5, -9, -4.5), (9, 3, 9), paint=jaguar),
        Cube((-3.5, -9.5, -3.5), (7, 1, 7), paint=jaguar),
    ], children=[
        Part("snout", cubes=[Cube((-3, -9, -7.2), (6, 2, 3), paint=jaguar_snout)]),
        Part("fang_right", cubes=[Cube((-2.5, -7, -7), (1, 1, 1), paint=fangs)]),
        Part("fang_left", cubes=[Cube((1.5, -7, -7), (1, 1, 1), paint=fangs)]),
        Part("eye_right", cubes=[Cube((-3.5, -9.6, -4.8), (2, 1, 1), paint=eye_disc)]),
        Part("eye_left", cubes=[Cube((1.5, -9.6, -4.8), (2, 1, 1), paint=eye_disc)]),
        Part("ear_right", pivot=(-3.2, -9.5, -1), rot=(0, 0, 0.25), cubes=[Cube((-1, -2, -0.5), (2, 2, 1), paint=jaguar)]),
        Part("ear_left", pivot=(3.2, -9.5, -1), rot=(0, 0, -0.25), cubes=[Cube((-1, -2, -0.5), (2, 2, 1), paint=jaguar)]),
        Part("pelt_back", pivot=(0, -6, 4.7), rot=(0.12, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, 8, 1), paint=jaguar)]),
        Part("pelt_right", pivot=(-4.7, -6, 1), rot=(0, 0, 0.08), cubes=[Cube((-0.5, 0, -3.5), (1, 5, 6), paint=jaguar)]),
        Part("pelt_left", pivot=(4.7, -6, 1), rot=(0, 0, -0.08), cubes=[Cube((-0.5, 0, -3.5), (1, 5, 6), paint=jaguar)]),
    ])
    return Model("JaguarHelmetModel", "jaguar_helmet", {"head": head})


def plume_fan(n, length, base_y, spread=0.28, z=3.5, back=0.35):
    """A fan of n long quetzal plumes rising from behind the head."""
    out = []
    for k in range(n):
        a = (k - (n - 1) / 2) * spread
        out.append(Part(f"plume{k}", pivot=(0, base_y, z), rot=(-back, 0, a),
                        cubes=[Cube((-0.5, -length, -0.5), (1, length, 1), paint=quetzal)]))
    return out


def serpent_headdress_model():
    """The feathered serpent's head over the brow, its jaws open, a fan of quetzal plumes behind."""
    head = Part("head", cubes=[Cube((-4.5, -8.5, -4.5), (9, 2, 9), paint=band())], children=[
        Part("serpent", cubes=[Cube((-2.5, -12, -6), (5, 3, 6), paint=serpent_scales)]),
        Part("serpent_jaw", pivot=(0, -9, -5), rot=(0.3, 0, 0), cubes=[Cube((-2, 0, -1.5), (4, 1, 3), paint=serpent_scales)]),
    ] + plume_fan(5, 8, -9, z=2.5))
    return Model("SerpentHeaddressModel", "serpent_headdress", {"head": head})


def quetzal_headdress_model():
    """The nacom's crest: a jade band, a shell disc on the brow, a tall fan of quetzal plumes."""
    head = Part("head", cubes=[Cube((-4.5, -8.5, -4.5), (9, 2, 9), paint=band(True))], children=[
        Part("disc", cubes=[Cube((-1, -9.5, -5.2), (2, 2, 1), paint=fangs)]),
    ] + plume_fan(7, 11, -8.5, spread=0.22, z=0.5, back=0.15))
    return Model("QuetzalHeaddressModel", "quetzal_headdress", {"head": head})


def cotton_cap_model():
    """A quilted cotton cap over the skull, its flaps over the ears and nape."""
    head = Part("head", cubes=[Cube((-4.5, -9, -4.5), (9, 4, 9), paint=lambda f: cotton(f, hem=False))], children=[
        Part("flap_back", pivot=(0, -5, 4.6), rot=(0.1, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, 3, 1), paint=cotton)]),
        Part("flap_right", pivot=(-4.6, -5, 0.5), cubes=[Cube((-0.5, 0, -2), (1, 3, 4), paint=cotton)]),
        Part("flap_left", pivot=(4.6, -5, 0.5), cubes=[Cube((-0.5, 0, -2), (1, 3, 4), paint=cotton)]),
    ])
    return Model("CottonWarCapModel", "cotton_war_cap", {"head": head})


def holkan_headband_model():
    """The holkan's band, a flint blade standing at the brow, a feather at the back."""
    head = Part("head", cubes=[Cube((-4.5, -8, -4.5), (9, 2, 9), paint=band())], children=[
        Part("flint", pivot=(0, -8, -4.8), cubes=[Cube((-0.5, -3, -0.5), (1, 3, 1), paint=flint)]),
        Part("feather", pivot=(2, -8, 4), rot=(-0.4, 0, -0.2), cubes=[Cube((-0.5, -5, -0.5), (1, 5, 1), paint=quetzal)]),
    ])
    return Model("HolkanHeadbandModel", "holkan_headband", {"head": head})


def deer_headdress_model():
    """A deer's head worn over the brow, its antlers branching."""
    head = Part("head", cubes=[Cube((-4.5, -8.5, -4.5), (9, 2, 9), paint=band())], children=[
        Part("deer", cubes=[Cube((-2, -11.5, -5.5), (4, 3, 6), paint=deer_head)]),
        Part("antler_right", pivot=(-1.5, -11.5, -2.5), rot=(0, 0, 0.45), cubes=[
            Cube((-0.5, -5, -0.5), (1, 5, 1), paint=antler), Cube((-2.5, -4, -0.5), (2, 1, 1), paint=antler),
            Cube((-2.0, -6, -0.5), (1, 2, 1), paint=antler)]),
        Part("antler_left", pivot=(1.5, -11.5, -2.5), rot=(0, 0, -0.45), cubes=[
            Cube((-0.5, -5, -0.5), (1, 5, 1), paint=antler), Cube((0.5, -4, -0.5), (2, 1, 1), paint=antler),
            Cube((1.0, -6, -0.5), (1, 2, 1), paint=antler)]),
    ])
    return Model("DeerHeaddressModel", "deer_headdress", {"head": head})


# ---------------------------------------------------------------------- coats

def short_sleeves(paint):
    """Sleeves to the elbow, the forearms bare."""
    return arms(paint, bare, split=5)


def cotton_armour_model():
    return coat_model("QuiltedCottonArmourModel", "quilted_cotton_armour", cotton, short_sleeves(cotton), cotton, 3)


def jaguar_armour_model():
    """Cotton armour under a jaguar pelt: the pelt over the back and shoulders, its tail down the back."""
    tail = Part("pelt_tail", pivot=(0, 9, 3.4), rot=(0.12, 0, 0), cubes=[Cube((-0.5, 0, -0.5), (1, 6, 1), paint=jaguar)])
    return coat_model("JaguarPeltArmourModel", "jaguar_pelt_armour", jaguar, short_sleeves(jaguar), jaguar, 4, extra=[tail])


def feathered_suit_model():
    """The feathered war suit, and its back rack: a wooden frame fanned with quetzal plumes."""
    rack = Part("back_rack", pivot=(0, 1, 3.6), cubes=[Cube((-3, -2, 0), (6, 6, 1), paint=wood_frame)],
                children=plume_fan(5, 9, -2, spread=0.3, z=0.6, back=0.2))
    return coat_model("FeatheredWarSuitModel", "feathered_war_suit", dyed_feathers, arms(dyed_feathers, bare, split=6),
                      dyed_feathers, 4, extra=[rack])


def jade_armour_model():
    return coat_model("JadePectoralArmourModel", "jade_pectoral_armour", cotton_with_collar, short_sleeves(cotton), cotton, 4)


def manta_cape_model():
    """The manta knotted on the shoulder, falling down the back; the chest bare."""
    back = Part("cape_back", pivot=(0, 0, 3.4), rot=(0.08, 0, 0), cubes=[Cube((-5, 0, -0.5), (10, 13, 1), paint=cape)])
    return coat_model("MantaCapeModel", "manta_cape", cape_body, arms(cape_body, bare, split=3), extra=[back])


# ---------------------------------------------------------------------- legs and feet

def loincloth_model():
    """The ex: a cotton band round the waist, its embroidered ends hanging as aprons front and back; the legs bare."""
    body = Part("body", cubes=[Cube((-4.5, 9.5, -2.5), (9, 2, 5), paint=band())], children=[
        Part("apron_front", pivot=(0, 11.5, -2.6), rot=(-0.05, 0, 0), cubes=[Cube((-2, 0, -0.5), (4, 6, 1), paint=cape)]),
        Part("apron_back", pivot=(0, 11.5, 2.6), rot=(0.05, 0, 0), cubes=[Cube((-2, 0, -0.5), (4, 5, 1), paint=cape)]),
    ])
    return Model("ExLoinclothModel", "ex_loincloth", {"body": body})


def kilt_model(java_name, location, paint):
    body = Part("body", cubes=[Cube((-4.5, 9.5, -2.5), (9, 2, 5), paint=band())], children=skirt(paint, length=6, flare=0.12))
    return Model(java_name, location, {"body": body})


def sandals_model(java_name, location, cuffs=False, wraps=False):
    def leg(name):
        cubes = [Cube((-2.5, 11.5, -3), (5, 1, 6), paint=sandal_sole), Cube((-2.5, 9.5, 1.5), (5, 2, 1), paint=heel_guard)]
        if cuffs:
            cubes += [Cube((-2.5, 8.5, -2.5), (5, 2, 5), paint=jade_cuff), Cube((-2.5, 1, -2.5), (5, 1, 5), paint=jade_cuff)]
        elif wraps:
            cubes += [Cube((-2.5, 3.5, -2.5), (5, 8, 5), paint=jaguar)]
        else:
            cubes += [Cube((-2.5, 8.5, -2.5), (5, 3, 5), paint=ankle_ties)]
        return Part(name, cubes=cubes)
    return Model(java_name, location, {"right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


# ---------------------------------------------------------------------- the items

WHITE = 0xEDE8DA
RED_OCHRE = 0xA8402E
BLUE = 0x2E6E9E          # Maya blue
YELLOW = 0xD0A030
GREEN = 0x3E7A4A
BLACK = 0x2C2A28

ARMOURS = [
    ("jaguar_helmet", jaguar_helmet_model, "HELMET", (0.5, 0.0, 200, 2, 12), WHITE, "ARMOR_EQUIP_LEATHER"),
    ("serpent_headdress", serpent_headdress_model, "HELMET", (0.25, 0.0, 170, 2, 13), RED_OCHRE, "ARMOR_EQUIP_LEATHER"),
    ("quetzal_headdress", quetzal_headdress_model, "HELMET", (0.25, 0.0, 160, 2, 14), WHITE, "ARMOR_EQUIP_LEATHER"),
    ("cotton_war_cap", cotton_cap_model, "HELMET", (0.0, 0.0, 130, 2, 9), WHITE, "ARMOR_EQUIP_LEATHER"),
    ("holkan_headband", holkan_headband_model, "HELMET", (0.0, 0.0, 80, 1, 9), RED_OCHRE, "ARMOR_EQUIP_LEATHER"),
    ("deer_headdress", deer_headdress_model, "HELMET", (0.0, 0.0, 110, 1, 10), WHITE, "ARMOR_EQUIP_LEATHER"),
    ("quilted_cotton_armour", cotton_armour_model, "CHESTPLATE", (0.25, 0.0, 220, 5, 10), WHITE, "ARMOR_EQUIP_LEATHER"),
    ("jaguar_pelt_armour", jaguar_armour_model, "CHESTPLATE", (0.5, 0.0, 240, 5, 12), WHITE, "ARMOR_EQUIP_LEATHER"),
    ("feathered_war_suit", feathered_suit_model, "CHESTPLATE", (0.5, 0.0, 230, 5, 14), BLUE, "ARMOR_EQUIP_LEATHER"),
    ("jade_pectoral_armour", jade_armour_model, "CHESTPLATE", (0.5, 0.0, 240, 5, 14), WHITE, "ARMOR_EQUIP_LEATHER"),
    ("manta_cape", manta_cape_model, "CHESTPLATE", (0.0, 0.0, 90, 1, 9), RED_OCHRE, "ARMOR_EQUIP_LEATHER"),
    ("ex_loincloth", loincloth_model, "LEGGINGS", (0.0, 0.0, 80, 1, 9), WHITE, "ARMOR_EQUIP_LEATHER"),
    ("cotton_war_kilt", lambda: kilt_model("CottonWarKiltModel", "cotton_war_kilt", cotton), "LEGGINGS", (0.25, 0.0, 180, 3, 10),
     WHITE, "ARMOR_EQUIP_LEATHER"),
    ("jaguar_kilt", lambda: kilt_model("JaguarKiltModel", "jaguar_kilt", jaguar), "LEGGINGS", (0.25, 0.0, 190, 3, 12), WHITE,
     "ARMOR_EQUIP_LEATHER"),
    ("xanab_sandals", lambda: sandals_model("XanabSandalsModel", "xanab_sandals"), "BOOTS", (0.0, 0.0, 90, 1, 9), WHITE,
     "ARMOR_EQUIP_LEATHER"),
    ("jade_anklets", lambda: sandals_model("JadeAnkletsModel", "jade_anklets", cuffs=True), "BOOTS", (0.0, 0.0, 130, 1, 14), WHITE,
     "ARMOR_EQUIP_GOLD"),
    ("jaguar_leg_wraps", lambda: sandals_model("JaguarLegWrapsModel", "jaguar_leg_wraps", wraps=True), "BOOTS", (0.0, 0.0, 150, 2, 12),
     WHITE, "ARMOR_EQUIP_LEATHER"),
]

NAMES = {
    "jaguar_helmet": "Jaguar Helmet", "serpent_headdress": "Feathered Serpent Headdress", "quetzal_headdress": "Quetzal Headdress",
    "cotton_war_cap": "Quilted Cotton Cap", "holkan_headband": "Holkan Headband", "deer_headdress": "Deer Headdress",
    "quilted_cotton_armour": "Quilted Cotton Armour", "jaguar_pelt_armour": "Jaguar Pelt Armour",
    "feathered_war_suit": "Feathered War Suit", "jade_pectoral_armour": "Cotton Armour with Jade Collar", "manta_cape": "Manta Cape",
    "ex_loincloth": "Ex", "cotton_war_kilt": "Quilted Cotton Kilt", "jaguar_kilt": "Jaguar Skin Kilt",
    "xanab_sandals": "Xanab Sandals", "jade_anklets": "Sandals with Jade Anklets", "jaguar_leg_wraps": "Jaguar Skin Leg Wraps",
}

_W, _F, _L = "minecraft:white_wool", "#magistuarmory:woolen_fabrics", "minecraft:leather"
RECIPES = {
    "jaguar_helmet": (["lol", "l l"], {"l": _L, "o": "minecraft:orange_dye"}),
    "serpent_headdress": (["fgf", "w w"], {"f": "minecraft:feather", "g": "minecraft:green_dye", "w": _W}),
    "quetzal_headdress": (["fff", "geg", " w "], {"f": "minecraft:feather", "g": "minecraft:green_dye", "e": "minecraft:emerald", "w": _W}),
    "cotton_war_cap": (["www", "w w", "s s"], {"w": _W, "s": "minecraft:string"}),
    "holkan_headband": (["wfw"], {"w": _W, "f": "minecraft:flint"}),
    "deer_headdress": (["b b", "lwl"], {"b": "minecraft:bone", "l": _L, "w": _W}),
    "quilted_cotton_armour": (["w w", "wsw", "www"], {"w": _W, "s": "minecraft:string"}),
    "jaguar_pelt_armour": (["l l", "lol", "lcl"], {"l": _L, "o": "minecraft:orange_dye", "c": "culturesarmory:quilted_cotton_armour"}),
    "feathered_war_suit": (["f f", "fcf", "fff"], {"f": "minecraft:feather", "c": "culturesarmory:quilted_cotton_armour"}),
    "jade_pectoral_armour": (["e e", "ece"], {"e": "minecraft:emerald", "c": "culturesarmory:quilted_cotton_armour"}),
    "manta_cape": (["w w", "www"], {"w": _W}),
    "ex_loincloth": (["wsw", " w "], {"w": _F, "s": "minecraft:red_dye"}),
    "cotton_war_kilt": (["www", "wsw", "w w"], {"w": _W, "s": "minecraft:string"}),
    "jaguar_kilt": (["lll", "lol", "l l"], {"l": _L, "o": "minecraft:orange_dye"}),
    "xanab_sandals": (["l l", "s s"], {"l": _L, "s": "minecraft:string"}),
    "jade_anklets": (["e e", "s s"], {"e": "minecraft:emerald", "s": "culturesarmory:xanab_sandals"}),
    "jaguar_leg_wraps": (["lol", "l l"], {"l": _L, "o": "minecraft:brown_dye"}),
}
