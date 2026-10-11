"""
The Indian armours of the Cultures Armory (1.5): the Rajput clans, the Delhi Sultanate and Vijayanagara, 13th-16th
centuries, after the surviving Indo-Persian arms and the painted records of the Deccan and Rajasthan courts.

Helmets: the Rajput pagri (a tall turban with a gilt sarpech and plume); the kulah khud (a domed steel bowl with a spike,
a sliding nasal, two plume holders and a mail aventail), plain and gilt-damascened; the top (a rounder bowl with a
mail curtain to the shoulders); a steel cap wound in a turban with a mail neck guard; the Bhil archer's cloth band with a
feather. Coats: the angarkha (a long padded cotton coat tied at the side, flared to the knee, a patka sash); the
chilta hazar masha ("coat of a thousand nails": quilted velvet studded with gilt nails); the zirah bagtar (mail set
with rows of small plates); the char aina ("four mirrors": four plates over a mail coat, with dastana vambraces); the
kavacha of the Vijayanagara nobles (gilt-edged steel scales). Legs: paijama, dhoti, zanupush (knee and thigh plates),
mail paijama. Feet: jutti (curled-toe slippers), moza (riding boots), chappal (sandals). Cloth and leather are dyeable.
"""
from armorgen import Cube, Model, Part
from japan import B, D, G, S, gold
from seljuk import (MAIL, STEEL, arms, aventail, boots_model, coat_model, faces_fill, legs_model, mail, mail_px, plume,
                    sash, skirt, steel_bowl, turban_wrap)
from byzantine import scale_px

VELVET = [(70, 20, 40), (96, 30, 56), (122, 44, 72)]


# ---------------------------------------------------------------------- painters

def sarpech(f):
    """The sarpech: a gilt turban jewel, a ruby at its heart."""
    faces_fill(f, lambda face, i, t, s: ((168, 28, 40) if face == "front" and t == 1 and i == f.w // 2 else G[3] if t == 0 else G[2], False),
               lambda i, j: (G[4], False), lambda i, j: (G[1], False), noise=1)


def damascened(f):
    """Gilt koftgari damascening on blued steel: gold scrolls on dark steel."""
    def side(face, i, t, s):
        scroll = (s + 2 * t) % 5 == 0 or (s - t) % 7 == 0
        return (G[3] if scroll else STEEL[1] if t % 3 else STEEL[2]), False
    faces_fill(f, side, lambda i, j: (G[3] if (i + j) % 4 == 0 else STEEL[2], False), lambda i, j: (STEEL[0], False), noise=1)


def plume_holder(f):
    faces_fill(f, lambda face, i, t, s: (G[3] if t == 0 else STEEL[3], False), lambda i, j: (G[3], False),
               lambda i, j: (STEEL[1], False))


def heron_plume(f):
    """Black heron (kalgi) feathers."""
    faces_fill(f, lambda face, i, t, s: ((30, 30, 34) if (s + t) % 3 else (70, 70, 78), False),
               lambda i, j: ((50, 50, 56), False), lambda i, j: ((24, 24, 28), False), noise=3)


def feather(f):
    """A peacock (or kite) feather: green-blue with its eye."""
    faces_fill(f, lambda face, i, t, s: (((36, 92, 74) if t < f.h - 2 else (40, 70, 140)) if s % 2 else (60, 120, 90), False),
               lambda i, j: ((36, 92, 74), False), lambda i, j: ((24, 60, 50), False), noise=3)


def nasal_bar(f):
    """The sliding nasal, its ends beaten into leaves."""
    faces_fill(f, lambda face, i, t, s: ((STEEL[4] if face == "front" else STEEL[2]) if 0 < t < f.h - 1 else G[3], False),
               lambda i, j: (G[3], False), lambda i, j: (G[2], False), noise=1)


def cotton_quilt(f, rows=(), skirt_rows=False):
    """Padded cotton (dyeable): stitched in vertical channels, a lighter border at the hem."""
    def side(face, i, t, s):
        if t >= f.h - 1:
            return D[4], True
        return (D[2] if s % 3 == 0 else D[3]), True
    faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: (D[2], True))


def angarkha_body(f):
    """The angarkha: the front crossed over and tied under the arm, a round opening at the breast, a patka sash."""
    cx = f.w // 2

    def side(face, i, t, s):
        if t in (8, 9):
            return ((G[3] if s % 4 == 0 else D[1]), t == 9 or s % 4 != 0)
        if face == "front" and t < 8 and abs(i - cx) + t // 2 == 3:
            return D[4], True                                         # the curved opening of the breast
        if face == "front" and i == cx - 3 and t in (2, 3):
            return G[2], False                                        # the ties
        return (D[3] if s % 4 else D[2]), True
    faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: (D[2], True))


def angarkha_skirt(f):
    """The flared skirt: gathered in many pleats, a gilt border at the hem."""
    faces_fill(f, lambda face, i, t, s: ((G[3] if s % 2 else G[2]), False) if t == f.h - 1 else ((D[2] if s % 2 == 0 else D[3]), True),
               lambda i, j: (D[3], True), lambda i, j: (D[2], True))


def chilta_px(s, t, f_h=99):
    """Chilta: quilted velvet (dyeable) in diamonds, a gilt nail head at each crossing."""
    if (s + t) % 4 == 0 and (s - t) % 4 == 0:
        return G[3], False
    if (s + t) % 4 == 0 or (s - t) % 4 == 0:
        return D[2], True
    return D[3], True


def chilta(collar=False):
    def paint(f):
        def side(face, i, t, s):
            if collar and t == 0:
                return G[2], False
            if t == f.h - 1:
                return G[3] if s % 2 else G[1], False
            return chilta_px(s, t)
        faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: (D[2], True), noise=2)
    return paint


def bagtar_px(s, t):
    """Zirah bagtar: mail with rows of small rectangular plates (two texels wide), riveted."""
    if t % 4 in (1, 2):
        plate = (s // 2 + t // 4) % 2 == 0
        if plate:
            return (STEEL[4] if t % 4 == 1 else STEEL[2]), False
    return mail_px(s, t), False


def bagtar(f):
    faces_fill(f, lambda face, i, t, s: bagtar_px(s, t), lambda i, j: (mail_px(i, j), False), lambda i, j: (MAIL[0], False), noise=2)


def char_aina_body(f):
    """Char aina: a plate on the breast (and back and sides) over the mail coat, brass-edged, a boss at its heart."""
    cx = (f.w - 1) / 2.0

    def side(face, i, t, s):
        width = f.d if face in ("right", "left") else f.w
        plate = 1 <= t <= 7 and 1 <= i <= width - 2
        if plate:
            edge = t in (1, 7) or i in (1, width - 2)
            if edge:
                return (G[3] if (i + t) % 2 else G[2]), False
            if face == "front" and abs(i - cx) < 1.1 and abs(t - 4) < 1.1:
                return G[4], False
            return (STEEL[4] if t == 2 else STEEL[3] if t < 5 else STEEL[2]), False
        return mail_px(s, t), False
    faces_fill(f, side, lambda i, j: (mail_px(i, j), False), lambda i, j: (MAIL[0], False), noise=1)


def dastana(f):
    """Dastana: the forearm guard, a long curved plate, its edge gilt; mail above it."""
    def side(face, i, t, s):
        if t < f.h // 2:
            return mail_px(s, t), False
        if face == "back":
            return (B[1], False)
        return ((G[3] if t == f.h // 2 else STEEL[3] if s % 3 else STEEL[4]), False)
    faces_fill(f, side, lambda i, j: (MAIL[2], False), lambda i, j: (STEEL[1], False), noise=1)


def kavacha_body(f):
    """The kavacha: gilt-edged steel scales, a gilt collar."""
    faces_fill(f, lambda face, i, t, s: (G[3] if t == 0 else scale_px(s, t)[0], False),
               lambda i, j: (G[3], False), lambda i, j: (STEEL[0], False), noise=2)


def scales(f):
    faces_fill(f, lambda face, i, t, s: ((G[2] if t == f.h - 1 else scale_px(s, t)[0]), False),
               lambda i, j: (STEEL[3], False), lambda i, j: (STEEL[0], False), noise=2)


def paijama_leg(f):
    """Fitted paijama: close at the shin, gathered in creases above the ankle (churidar)."""
    faces_fill(f, lambda face, i, t, s: ((D[2] if t >= f.h - 5 and (t + s) % 2 == 0 else D[3] if s % 4 else D[2]), True),
               lambda i, j: (D[3], True), lambda i, j: (D[1], True))


def dhoti_leg(f):
    """The dhoti: draped to the knee, folds running down, the leg bare below."""
    def side(face, i, t, s):
        if t > f.h - 5:
            return None
        return ((D[4] if t == f.h - 5 else D[2] if (s + t // 2) % 3 == 0 else D[3]), True)
    faces_fill(f, side, lambda i, j: (D[3], True), None)


def zanupush(f):
    """Zanupush: a steel knee cop and thigh plate, gilt-edged."""
    faces_fill(f, lambda face, i, t, s: ((G[3] if t in (0, f.h - 1) else STEEL[4] if i == 1 else STEEL[3]) if face == "front"
                                         else STEEL[2], False),
               lambda i, j: (STEEL[3], False), lambda i, j: (STEEL[1], False), noise=1)


def jutti_foot(f):
    """Jutti: dyed leather, gilt zari embroidery across the vamp, the toe curling up."""
    faces_fill(f, lambda face, i, t, s: ((G[3] if (s + t) % 3 == 0 else D[3]), (s + t) % 3 != 0),
               lambda i, j: ((G[3] if (i + j) % 3 == 0 else D[3]), (i + j) % 3 != 0), lambda i, j: (B[0], False))


def skin(f):
    """A strip of the wearer's own leg (sandals, dhoti): left unpainted, so the skin shows."""
    return None


def sandal(f):
    faces_fill(f, lambda face, i, t, s: ((B[2] if t == 0 else B[1]), False), lambda i, j: (B[2] if i == f.w // 2 else B[1], False),
               lambda i, j: (B[0], False))


def strap(f):
    faces_fill(f, lambda face, i, t, s: (B[2] if s % 3 else B[1], False), lambda i, j: (B[2], False), lambda i, j: (B[1], False))


def leather_cloth(f):
    faces_fill(f, lambda face, i, t, s: (((210, 196, 168) if (s + t) % 3 else (190, 176, 150)), False),
               lambda i, j: ((210, 196, 168), False), lambda i, j: ((180, 166, 140), False))


# ---------------------------------------------------------------------- helmets

def pagri_model():
    """The Rajput pagri: a tall turban wound in folds, a gilt sarpech and a heron plume at the front."""
    head = Part("head", cubes=[
        Cube((-5, -9.5, -5), (10, 4, 10), paint=turban_wrap),
        Cube((-4.5, -11.5, -4.5), (9, 2, 9), paint=turban_wrap),
        Cube((-3.5, -12.5, -3), (7, 1, 7), paint=turban_wrap),
    ], children=[
        Part("sarpech", pivot=(0, -10, -5.1), cubes=[Cube((-1, -1, -0.5), (2, 3, 1), paint=sarpech)]),
        Part("kalgi", pivot=(0, -11, -5.2), rot=(-0.25, 0, 0), cubes=[Cube((-0.5, -4, -0.5), (1, 4, 1), paint=heron_plume)]),
        Part("turban_tail", pivot=(2, -6, 5), rot=(0.1, 0, -0.08), cubes=[Cube((-1, 0, -0.5), (2, 5, 1), paint=turban_wrap)]),
    ])
    return Model("PagriModel", "pagri", {"head": head})


def khud_parts(gilt=False, long_aventail=False):
    """The kulah khud: a shallow domed bowl, a spike, a sliding nasal, two plume holders with feathers, a mail aventail."""
    bowl = damascened if gilt else steel_bowl(gilt_rim=True, rivets=False)
    cubes = [
        Cube((-4.5, -9, -4.5), (9, 3, 9), paint=bowl),
        Cube((-3.5, -10, -3.5), (7, 1, 7), paint=bowl),
        Cube((-2, -10.6, -2), (4, 1, 4), paint=bowl),
        Cube((-0.5, -13.6, -0.5), (1, 3, 1), paint=gold if gilt else steel_bowl(gilt_rim=False, rivets=False)),
    ]
    children = [
        Part("nasal", cubes=[Cube((-0.5, -10, -5.3), (1, 7, 1), paint=nasal_bar)]),
        Part("plume_right", pivot=(-1.8, -9, -4.7), rot=(-0.15, 0, 0.2), cubes=[
            Cube((-0.5, -2, -0.5), (1, 2, 1), paint=plume_holder), Cube((-0.5, -6, -0.5), (1, 4, 1), paint=feather)]),
        Part("plume_left", pivot=(1.8, -9, -4.7), rot=(-0.15, 0, -0.2), cubes=[
            Cube((-0.5, -2, -0.5), (1, 2, 1), paint=plume_holder), Cube((-0.5, -6, -0.5), (1, 4, 1), paint=feather)]),
    ]
    aventail(children, back_h=8 if long_aventail else 6, side_h=7 if long_aventail else 5)
    return cubes, children


def kulah_khud_model(gilt=False):
    cubes, children = khud_parts(gilt, long_aventail=gilt)
    name, loc = ("GildedKulahKhudModel", "gilded_kulah_khud") if gilt else ("KulahKhudModel", "kulah_khud")
    return Model(name, loc, {"head": Part("head", cubes=cubes, children=children)})


def top_model():
    """The top: a deeper domed bowl with a short spike, a mail curtain to the shoulders all round but the face."""
    cubes = [
        Cube((-4.5, -9, -4.5), (9, 4, 9), paint=steel_bowl(bands=False, gilt_rim=True)),
        Cube((-3.5, -10.5, -3.5), (7, 2, 7), paint=steel_bowl(gilt_rim=False, rivets=False)),
        Cube((-1, -11.5, -1), (2, 1, 2), paint=gold),
    ]
    children = []
    aventail(children, back_h=8, side_h=8)
    return Model("TopModel", "top_khud", {"head": Part("head", cubes=cubes, children=children)})


def turbaned_khud_model():
    """A steel cap wound round with a pagri, its spike through the crown, a mail neck guard."""
    cubes = [
        Cube((-5, -9.5, -5), (10, 3, 10), paint=turban_wrap),
        Cube((-4, -11, -4), (8, 2, 8), paint=turban_wrap),
        Cube((-2.5, -11.6, -2.5), (5, 1, 5), paint=steel_bowl(gilt_rim=False, rivets=False)),
        Cube((-0.5, -13.6, -0.5), (1, 2, 1), paint=steel_bowl(gilt_rim=False, rivets=False)),
    ]
    children = [Part("aventail_back", pivot=(0, -6, 4.8), rot=(0.18, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, 6, 1), paint=mail(0))])]
    return Model("TurbanedKhudModel", "turbaned_khud", {"head": Part("head", cubes=cubes, children=children)})


def bhil_headband_model():
    """The Bhil archer's cloth band, a kite feather stuck in it."""
    head = Part("head", cubes=[Cube((-4.5, -8.5, -4.5), (9, 2, 9), paint=turban_wrap)], children=[
        Part("feather", pivot=(2.5, -8.5, 2), rot=(-0.3, 0, -0.3), cubes=[Cube((-0.5, -5, -0.5), (1, 5, 1), paint=feather)]),
    ])
    return Model("BhilHeadbandModel", "bhil_headband", {"head": head})


# ---------------------------------------------------------------------- coats

def angarkha_model():
    return coat_model("AngarkhaModel", "angarkha", angarkha_body,
                      arms(lambda f: cotton_quilt(f)), angarkha_skirt, 8)


def chilta_model():
    return coat_model("ChiltaHazarMashaModel", "chilta_hazar_masha", chilta(collar=True), arms(chilta()), chilta(), 8)


def bagtar_model():
    return coat_model("ZirahBagtarModel", "zirah_bagtar", bagtar, arms(mail()), bagtar, 5)


def char_aina_model():
    return coat_model("CharAinaModel", "char_aina", char_aina_body, arms(mail(), dastana, split=5), mail(1), 6)


def kavacha_model():
    return coat_model("KavachaModel", "kavacha", kavacha_body, arms(scales, lambda f: cotton_quilt(f), split=4), scales, 6)


# ---------------------------------------------------------------------- legs and feet

def patka(f):
    faces_fill(f, lambda face, i, t, s: ((G[3] if t == 2 and s % 3 == 0 else D[4] if t == 1 else D[2]), not (t == 2 and s % 3 == 0)),
               lambda i, j: (D[2], True), lambda i, j: (D[1], True))


def jutti_model():
    """Jutti: the slipper, its toe curling up (no shaft: the paijama or the bare leg shows above)."""
    def leg(name):
        return Part(name, cubes=[Cube((-2.5, 10.5, -3), (5, 2, 6), paint=jutti_foot)], children=[
            Part(name.replace("leg", "toe"), pivot=(0, 11, -3), rot=(0.5, 0, 0), cubes=[Cube((-1, -1.5, -1), (2, 2, 1), paint=jutti_foot)])])
    return Model("JuttiModel", "jutti", {"right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


def chappal_model():
    """Chappal: a leather sole, a toe ring and a strap over the instep."""
    def leg(name):
        return Part(name, cubes=[Cube((-2.5, 11.5, -3), (5, 1, 6), paint=sandal), Cube((-2.5, 10.5, -1.5), (5, 1, 1), paint=strap)])
    return Model("ChappalModel", "chappal", {"right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


# ---------------------------------------------------------------------- the items

SAFFRON = 0xD08A28
WHITE = 0xEAE4D4
INDIGO = 0x2E3E78
MADDER = 0x9E2A2A
GREEN = 0x2F6B3A
VELVET_RED = 0x7A1E3A
INDIGO_DARK = 0x283058
OCHRE = 0xB07A3A

ARMOURS = [
    ("pagri", pagri_model, "HELMET", (0.0, 0.0, 110, 1, 10), SAFFRON, "ARMOR_EQUIP_LEATHER"),
    ("kulah_khud", lambda: kulah_khud_model(False), "HELMET", (0.75, 0.0, 250, 3, 10), MADDER, "ARMOR_EQUIP_IRON"),
    ("gilded_kulah_khud", lambda: kulah_khud_model(True), "HELMET", (1.0, 0.0, 270, 3, 14), MADDER, "ARMOR_EQUIP_GOLD"),
    ("top_khud", top_model, "HELMET", (1.0, 0.0, 260, 3, 10), MADDER, "ARMOR_EQUIP_CHAIN"),
    ("turbaned_khud", turbaned_khud_model, "HELMET", (0.5, 0.0, 230, 2, 10), WHITE, "ARMOR_EQUIP_IRON"),
    ("bhil_headband", bhil_headband_model, "HELMET", (0.0, 0.0, 70, 1, 8), MADDER, "ARMOR_EQUIP_LEATHER"),
    ("angarkha", angarkha_model, "CHESTPLATE", (0.0, 0.0, 170, 3, 10), WHITE, "ARMOR_EQUIP_LEATHER"),
    ("chilta_hazar_masha", chilta_model, "CHESTPLATE", (0.5, 0.0, 260, 5, 12), VELVET_RED, "ARMOR_EQUIP_LEATHER"),
    ("zirah_bagtar", bagtar_model, "CHESTPLATE", (0.75, 0.0, 300, 6, 10), MADDER, "ARMOR_EQUIP_CHAIN"),
    ("char_aina", char_aina_model, "CHESTPLATE", (1.25, 0.0, 330, 7, 10), MADDER, "ARMOR_EQUIP_IRON"),
    ("kavacha", kavacha_model, "CHESTPLATE", (1.0, 0.0, 320, 7, 13), SAFFRON, "ARMOR_EQUIP_CHAIN"),
    ("paijama", lambda: legs_model("PaijamaModel", "paijama", paijama_leg, waist=patka), "LEGGINGS", (0.0, 0.0, 120, 2, 9), WHITE,
     "ARMOR_EQUIP_LEATHER"),
    ("dhoti", lambda: legs_model("DhotiModel", "dhoti", dhoti_leg, waist=patka), "LEGGINGS", (0.0, 0.0, 90, 1, 8), WHITE,
     "ARMOR_EQUIP_LEATHER"),
    ("zanupush", lambda: legs_model("ZanupushModel", "zanupush", paijama_leg, zanupush, front_h=7, waist=patka), "LEGGINGS",
     (0.5, 0.0, 250, 4, 10), INDIGO, "ARMOR_EQUIP_IRON"),
    ("mail_paijama", lambda: legs_model("MailPaijamaModel", "mail_paijama", mail(1), waist=patka), "LEGGINGS",
     (0.25, 0.0, 260, 4, 10), MADDER, "ARMOR_EQUIP_CHAIN"),
    ("jutti", jutti_model, "BOOTS", (0.0, 0.0, 100, 1, 10), MADDER, "ARMOR_EQUIP_LEATHER"),
    ("moza_boots", lambda: boots_model("MozaBootsModel", "moza_boots"), "BOOTS", (0.0, 0.0, 140, 1, 9), OCHRE, "ARMOR_EQUIP_LEATHER"),
    ("chappal", chappal_model, "BOOTS", (0.0, 0.0, 70, 1, 8), OCHRE, "ARMOR_EQUIP_LEATHER"),
]

NAMES = {
    "pagri": "Rajput Pagri", "kulah_khud": "Kulah Khud", "gilded_kulah_khud": "Gilded Kulah Khud", "top_khud": "Top",
    "turbaned_khud": "Turbaned Khud", "bhil_headband": "Bhil Archer's Headband", "angarkha": "Angarkha",
    "chilta_hazar_masha": "Chilta Hazar Masha", "zirah_bagtar": "Zirah Bagtar", "char_aina": "Char Aina",
    "kavacha": "Kavacha", "paijama": "Paijama", "dhoti": "Dhoti", "zanupush": "Zanupush", "mail_paijama": "Mail Paijama",
    "jutti": "Jutti", "moza_boots": "Moza", "chappal": "Chappal",
}

_I, _P, _C = "#c:ingots/steel", "#c:plates/steel", "#magistuarmory:chainmails/steel"
_F, _L, _G = "#magistuarmory:woolen_fabrics", "#magistuarmory:leather_strips", "minecraft:gold_nugget"
RECIPES = {
    "pagri": (["fgf", "f f"], {"f": _F, "g": _G}),
    "kulah_khud": (["eie", "pip", "c c"], {"e": "minecraft:feather", "i": _I, "p": _P, "c": _C}),
    "gilded_kulah_khud": (["ggg", "gkg", "ggg"], {"g": _G, "k": "culturesarmory:kulah_khud"}),
    "top_khud": (["pip", "c c", "c c"], {"i": _I, "p": _P, "c": _C}),
    "turbaned_khud": (["p", "k"], {"p": "culturesarmory:pagri", "k": _P}),
    "bhil_headband": (["fsf"], {"f": _F, "s": "minecraft:feather"}),
    "angarkha": (["f f", "fgf", "fwf"], {"f": _F, "g": _G, "w": "minecraft:white_wool"}),
    "chilta_hazar_masha": (["fgf", "fff", "fgf"], {"f": _F, "g": _G}),
    "zirah_bagtar": (["c c", "cpc", "ccc"], {"c": _C, "p": _P}),
    "char_aina": (["p p", "pzp", "p p"], {"p": _P, "z": "culturesarmory:zirah_bagtar"}),
    "kavacha": (["s s", "sgs", "sss"], {"s": "#c:nuggets/iron", "g": "minecraft:gold_ingot"}),
    "paijama": (["fff", "f f", "f f"], {"f": "minecraft:white_wool"}),
    "dhoti": (["ftf", "w w"], {"f": _F, "t": "minecraft:string", "w": "minecraft:white_wool"}),
    "zanupush": (["p p", "pjp"], {"p": _P, "j": "culturesarmory:paijama"}),
    "mail_paijama": (["ccc", "c c", "f f"], {"c": _C, "f": _F}),
    "jutti": (["lgl", "l l"], {"l": "minecraft:leather", "g": _G}),
    "moza_boots": (["l l", "l l", "f f"], {"l": "minecraft:leather", "f": _F}),
    "chappal": (["l l"], {"l": "minecraft:leather"}),
}
