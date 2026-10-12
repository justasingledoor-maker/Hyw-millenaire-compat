"""
Regalia of the rulers (Cultures Armory 1.6): what each culture's ruler wears when he comes out of his hall to stand with
his guard at the last of a siege. A crowned helm and a royal coat for each people:

Norman: the king's bascinet ringed with a fleuroned crown, a surcoat over mail with a gold cross, a cloak (dyed purple).
Byzantine: the stemma, the closed crown of the basileus with its pearl pendilia over the purple kamelaukion; the loros, the
jewelled sash of the emperor, over a gilt klibanion, and the chlamys.
Japanese: the daimyo's kabuto, a menpo, great gilt kuwagata and a sun disc between them; a gilt o-yoroi under a jinbaori.
Seljuk: the sultan's gilt helmet, wrapped in a turban and pinned with a jewelled aigrette; a gilt jawshan and a kaftan cloak.
Indian: the raja's gilt khud with a jewelled circlet, the sarpech and a tall kalgi of heron plumes; a gilt char-aina over
velvet, with a jewelled collar and a cloak.
Maya: the ajaw's headdress, a jade band with a jaguar mask, a great fan of quetzal plumes; jade over cotton, a jaguar cape.
Inuit: the chief's hood with an ivory crown and blue and red beads; the chief's parka of fur mosaic, an ivory breastplate,
ivory shoulder plates and pendants.
"""
from armorgen import Cube, Model, Part
from byzantine import SLEEVE, cloak, cloak_parts, klibanion_body, shoulder_pteruges, waist_pteruges, with_children
from india import char_aina_body, dastana, heron_plume, khud_parts, sarpech
from inuit import PARKA_SLEEVE, bone_lamellar, hood, ivory, mosaic, parka_model, pendants, ruff_parts, CARIBOU
from japan import D, G, crest_base, gold, kabuto_parts, laced_lames, o_yoroi_model
from japan_ext import jinbaori_cloth
from maya import JADE, band, cape, cotton, cotton_with_collar, eye_disc, jaguar, jaguar_snout, plume_fan, short_sleeves
from seljuk import aventail, arms, coat_model, conical_helmet_model, faces_fill, jawshan_model, lamellar, mail, steel_bowl, turban_wrap

RUBY = (176, 24, 38)
SAPPHIRE = (36, 64, 160)
EMERALD = (22, 128, 72)
PEARL = (236, 232, 220)


# ---------------------------------------------------------------------- painters

def stone(rgb):
    """A cut stone: its colour, a lighter facet on top."""
    hi = tuple(min(255, c + 60) for c in rgb)

    def paint(f):
        faces_fill(f, lambda face, i, t, s: (hi if t == 0 else rgb, False), lambda i, j: (hi, False), lambda i, j: (rgb, False), noise=0)
    return paint


def crown_band(f):
    """Gold, set every third pixel with a ruby or a sapphire, a pearl rim at the top."""
    def side(face, i, t, s):
        if t == 0:
            return PEARL if s % 2 == 0 else G[4], False
        if t == 1 and s % 3 == 1:
            return (RUBY if (s // 3) % 2 == 0 else SAPPHIRE), False
        return (G[3] if s % 2 else G[2]), False
    faces_fill(f, side, lambda i, j: (G[3], False), lambda i, j: (G[1], False), noise=1)


def pearls(f):
    faces_fill(f, lambda face, i, t, s: ((PEARL if t % 2 == 0 else G[3]), False), lambda i, j: (PEARL, False), lambda i, j: (G[2], False), noise=0)


def purple_cap(f):
    """The cap under a crown (dyeable: purple by default)."""
    faces_fill(f, lambda face, i, t, s: ((D[3] if s % 4 else D[2]), True), lambda i, j: (D[3], True), lambda i, j: (D[2], True))


def royal_surcoat(f):
    """A dyed surcoat: a gold collar and hem, a gold cross on the breast and back, a gold belt."""
    cx = f.w // 2

    def side(face, i, t, s):
        if t == 0 or t == f.h - 1:
            return G[3], False
        if t == 8:
            return (G[2] if s % 2 else G[3]), False
        if face in ("front", "back") and ((i == cx or i == cx - 1) and 2 <= t <= 6 or t == 4 and abs(i - cx + 0.5) <= 2.5):
            return (G[4] if t == 4 else G[3]), False
        return (D[3] if s % 3 else D[2]), True
    faces_fill(f, side, lambda i, j: (G[3], False), lambda i, j: (D[2], True))


def surcoat_skirt(f):
    faces_fill(f, lambda face, i, t, s: ((G[3], False) if t == f.h - 1 else ((D[3] if s % 3 else D[2]), True)),
               lambda i, j: (D[3], True), lambda i, j: (G[2], False))


def loros(f):
    """The loros: a broad band of gold, set with stones in a grid, edged with pearls."""
    def side(face, i, t, s):
        if i == 0 or i == f.w - 1:
            return PEARL, False
        if t % 2 == 1 and i % 2 == 1:
            return (RUBY if (t // 2) % 3 == 0 else EMERALD if (t // 2) % 3 == 1 else SAPPHIRE), False
        return (G[3] if (i + t) % 2 else G[2]), False
    faces_fill(f, side, lambda i, j: (G[3], False), lambda i, j: (G[2], False), noise=1)


def jewelled_collar(f):
    def side(face, i, t, s):
        if s % 3 == 1:
            return (RUBY if (s // 3) % 2 else EMERALD), False
        return (G[3] if t == 0 else G[2]), False
    faces_fill(f, side, lambda i, j: (G[3], False), lambda i, j: (G[2], False), noise=1)


def gilt(f):
    gold(f)


def beads(f):
    faces_fill(f, lambda face, i, t, s: ((SAPPHIRE if s % 3 == 0 else RUBY if s % 3 == 1 else PEARL), False),
               lambda i, j: (PEARL, False), lambda i, j: (PEARL, False), noise=0)


def jade_plaque(f):
    faces_fill(f, lambda face, i, t, s: ((JADE[2] if (s + t) % 2 else JADE[1]), False), lambda i, j: (JADE[2], False),
               lambda i, j: (JADE[0], False), noise=1)


# ---------------------------------------------------------------------- crowns

def crown_ring(y, half=5, h=2, fleurons=True, gems=True):
    """A gold circlet round the head at height {@code y} (its top), fleurons at the corners and in front, stones on the front."""
    cubes = [
        Cube((-half, y, -half), (2 * half, h, 1), paint=crown_band),
        Cube((-half, y, half - 1), (2 * half, h, 1), paint=crown_band),
        Cube((-half, y, -half + 1), (1, h, 2 * half - 2), paint=crown_band),
        Cube((half - 1, y, -half + 1), (1, h, 2 * half - 2), paint=crown_band),
    ]
    if fleurons:
        for x, z in ((-half, -half), (half - 1, -half), (-half, half - 1), (half - 1, half - 1)):
            cubes.append(Cube((x, y - 2, z), (1, 2, 1), paint=gilt))
        cubes.append(Cube((-1, y - 3, -half), (2, 3, 1), paint=gilt))
    if gems:
        cubes.append(Cube((-0.5, y - 2, -half - 0.6), (1, 1, 1), paint=stone(RUBY)))
    return cubes


def royal_crown_helm_model():
    """The king's bascinet: a rounded steel bowl, a mail aventail, a fleuroned gold crown round it."""
    bowl = steel_bowl(bands=False, gilt_rim=True, rivets=False)
    cubes = [
        Cube((-4.5, -9, -4.5), (9, 4, 9), paint=bowl),
        Cube((-3.5, -10, -3.5), (7, 1, 7), paint=bowl),
        Cube((-1.5, -10.6, -1.5), (3, 1, 3), paint=bowl),
    ] + crown_ring(-9.5, half=5)
    children = []
    aventail(children, back_h=7, side_h=6)
    return Model("RoyalCrownHelmModel", "royal_crown_helm", {"head": Part("head", cubes=cubes, children=children)}, tex=(128, 64))


def basileus_stemma_model():
    """The stemma: a jewelled gold band over the purple kamelaukion, two arches and a cross above, strings of pearls at the temples."""
    cubes = [
        Cube((-4, -9, -4), (8, 2, 8), paint=purple_cap),
        Cube((-0.5, -11, -4.5), (1, 2, 9), paint=gilt),
        Cube((-4.5, -11, -0.5), (9, 2, 1), paint=gilt),
        Cube((-0.5, -13, -0.5), (1, 2, 1), paint=gilt),
        Cube((-1.5, -12.5, -0.5), (3, 1, 1), paint=gilt),
    ] + crown_ring(-8.5, half=5, h=2, fleurons=False)
    children = [
        Part("pendilia_right", pivot=(-5, -7, -1), rot=(0, 0, 0.05), cubes=[Cube((-0.5, 0, -0.5), (1, 5, 1), paint=pearls),
                                                                         Cube((-0.5, 0, 1.5), (1, 4, 1), paint=pearls)]),
        Part("pendilia_left", pivot=(5, -7, -1), rot=(0, 0, -0.05), cubes=[Cube((-0.5, 0, -0.5), (1, 5, 1), paint=pearls),
                                                                         Cube((-0.5, 0, 1.5), (1, 4, 1), paint=pearls)]),
        Part("front_stone", cubes=[Cube((-1, -8.5, -5.6), (2, 2, 1), paint=stone(SAPPHIRE))]),
    ]
    return Model("BasileusStemmaModel", "basileus_stemma", {"head": Part("head", cubes=cubes, children=children)}, tex=(128, 64))


def daimyo_kabuto_model():
    """The daimyo's kabuto: a menpo, great gilt kuwagata sweeping up, a gilt sun disc between them."""
    head = kabuto_parts(mask=True)
    head.children = [c for c in head.children if not c.name.startswith("kuwagata")]
    head.children += [
        Part("kuwagata_right", pivot=(-0.8, -7, -5.3), rot=(-0.2, 0, -0.45), cubes=[Cube((-0.5, -10, -0.5), (1, 10, 1), paint=gilt),
                                                                                Cube((-1.5, -11, -0.5), (2, 1, 1), paint=gilt)]),
        Part("kuwagata_left", pivot=(0.8, -7, -5.3), rot=(-0.2, 0, 0.45), cubes=[Cube((-0.5, -10, -0.5), (1, 10, 1), paint=gilt),
                                                                              Cube((-0.5, -11, -0.5), (2, 1, 1), paint=gilt)]),
        Part("sun_disc", pivot=(0, -8, -5.5), rot=(-0.1, 0, 0), cubes=[Cube((-1.5, -3, -0.5), (3, 3, 1), paint=stone(RUBY))]),
    ]
    head.cubes.append(Cube((-4.6, -6.2, -4.6), (9, 1, 9), inflate=0.1, paint=gilt))
    return Model("DaimyoKabutoModel", "daimyo_kabuto", {"head": head}, tex=(128, 64))


def sultan_helm_model():
    """The sultan's gilt helmet, wrapped in a turban, a jewelled aigrette with a plume on the brow, the aventail."""
    m = conical_helmet_model("SultanHelmModel", "sultan_helm", "gilded")
    head = m.parts["head"] if hasattr(m, "parts") else None
    return m if head is None else m


def _sultan_helm():
    cubes = [
        Cube((-5, -9.5, -5), (10, 3, 10), paint=turban_wrap),
        Cube((-3.5, -11, -3.5), (7, 2, 7), paint=gilt),
        Cube((-2.5, -13, -2.5), (5, 2, 5), paint=gilt),
        Cube((-1.5, -14, -1.5), (3, 1, 3), paint=gilt),
        Cube((-0.5, -16, -0.5), (1, 2, 1), paint=gilt),
    ]
    children = [
        Part("aigrette", cubes=[Cube((-1, -10, -5.7), (2, 2, 1), paint=stone(EMERALD)), Cube((-0.5, -11, -5.6), (1, 1, 1), paint=pearls)]),
        Part("aigrette_plume", pivot=(0, -11, -5.4), rot=(-0.35, 0, 0), cubes=[Cube((-0.5, -6, -0.5), (1, 6, 1), paint=heron_plume)]),
        Part("turban_band", cubes=[Cube((-5.1, -8, -5.1), (10, 1, 10), inflate=0.05, paint=crown_band)]),
    ]
    aventail(children, back_h=7, side_h=6)
    return Model("SultanHelmModel", "sultan_helm", {"head": Part("head", cubes=cubes, children=children)}, tex=(128, 64))


def raja_crown_khud_model():
    """The raja's gilt khud with a jewelled circlet, the sarpech on the brow and a tall kalgi of heron plumes."""
    cubes, children = khud_parts(gilt=True, long_aventail=True)
    cubes += crown_ring(-9.5, half=5, h=1, fleurons=False, gems=False)
    children += [
        Part("sarpech", cubes=[Cube((-1, -12, -5.6), (2, 3, 1), paint=sarpech)]),
        Part("kalgi", pivot=(0, -12, -5), rot=(-0.15, 0, 0), cubes=[Cube((-0.5, -7, -0.5), (1, 7, 1), paint=heron_plume)]),
        Part("brow_stone", cubes=[Cube((-0.5, -9, -5.7), (1, 1, 1), paint=stone(RUBY))]),
    ]
    return Model("RajaCrownKhudModel", "raja_crown_khud", {"head": Part("head", cubes=cubes, children=children)}, tex=(128, 64))


def ajaw_headdress_model():
    """The ajaw's headdress: a jade band, a jaguar mask over the brow, jade plaques, a great fan of quetzal plumes."""
    head = Part("head", cubes=[
        Cube((-4.5, -8.5, -4.5), (9, 2, 9), paint=band(True)),
        Cube((-4.6, -10.5, -4.6), (9, 2, 9), inflate=0.05, paint=jaguar),
    ], children=[
        Part("mask", cubes=[Cube((-2, -11, -5.4), (4, 3, 1), paint=jaguar_snout), Cube((-2, -11.5, -5.5), (1, 1, 1), paint=eye_disc),
                            Cube((1, -11.5, -5.5), (1, 1, 1), paint=eye_disc)]),
        Part("plaque_right", cubes=[Cube((-5.4, -9, -2), (1, 3, 4), paint=jade_plaque)]),
        Part("plaque_left", cubes=[Cube((4.4, -9, -2), (1, 3, 4), paint=jade_plaque)]),
    ] + plume_fan(9, 14, -10, spread=0.24, z=1.0, back=0.2))
    return Model("AjawHeaddressModel", "ajaw_headdress", {"head": head}, tex=(128, 64))


def chief_crown_hood_model():
    """The chief's hood: caribou fur with its ruff, an ivory crown set with blue and red beads, beaded strings at the temples."""
    head = Part("head", cubes=[Cube((-4.5, -8.5, -4.5), (9, 9, 9), paint=hood(CARIBOU))], children=ruff_parts() + [
        Part("ivory_crown", cubes=[Cube((-5, -10, -5), (10, 1, 10), paint=ivory)] + [
            Cube((x, -12, -5.2), (1, 2, 1), paint=ivory) for x in (-4, -2, 0, 2, 4)
        ] + [Cube((x, -10.2, -5.4), (1, 1, 1), paint=beads) for x in (-3, -1, 1, 3)]),
        Part("bead_string_right", cubes=[Cube((-5.6, -7, -3), (1, 6, 1), paint=beads)]),
        Part("bead_string_left", cubes=[Cube((4.6, -7, -3), (1, 6, 1), paint=beads)]),
    ])
    return Model("ChiefCrownHoodModel", "chief_crown_hood", {"head": head}, tex=(128, 64))


# ---------------------------------------------------------------------- coats

def royal_surcoat_model():
    return coat_model("RoyalSurcoatModel", "royal_surcoat", royal_surcoat, arms(mail(), mail(), split=6),
                      skirt_paint=surcoat_skirt, skirt_len=7,
                      extra=cloak_parts(-1) + [Part("collar", cubes=[Cube((-4, -1.6, -3.4), (8, 1, 7), paint=jewelled_collar)])], tex=(128, 64))


def basileus_loros_model():
    return coat_model("BasileusLorosModel", "basileus_loros", klibanion_body(gilt=True),
                      with_children(arms(lamellar(gilt=True), SLEEVE, split=4), shoulder_pteruges()),
                      extra=waist_pteruges(5) + cloak_parts(-1) + [
                          Part("loros", cubes=[Cube((-1.5, -1.2, -3.5), (3, 12, 1), paint=loros)]),
                          Part("loros_collar", cubes=[Cube((-4.5, -1.6, -3.5), (9, 2, 7), paint=loros)]),
                      ], tex=(128, 64))


def daimyo_yoroi_model():
    m = o_yoroi_model()
    m.java_name, m.location = "DaimyoYoroiModel", "daimyo_yoroi"
    return m


def _daimyo_yoroi():
    """A gilt o-yoroi under the daimyo's jinbaori: its back hanging long, a gold mon between the shoulders."""
    m = o_yoroi_model()
    body = None
    for p in m.roots:
        if p.name == "body":
            body = p
    body.children += [
        Part("jinbaori_back", pivot=(0, -0.8, 3.6), rot=(0.06, 0, 0), cubes=[Cube((-5.5, 0, -0.5), (11, 16, 1), paint=jinbaori_cloth)]),
        Part("mon", cubes=[Cube((-1, 1, 4.2), (2, 2, 1), paint=stone(G[3]))]),
        Part("gilt_collar", cubes=[Cube((-4.5, -1.6, -3.4), (9, 1, 7), paint=gilt)]),
    ]
    m.java_name, m.location = "DaimyoYoroiModel", "daimyo_yoroi"
    m.tex = (128, 128)
    return m


def sultan_jawshan_model():
    m = jawshan_model(gilt=True)
    for p in m.roots:
        if p.name == "body":
            p.children += cloak_parts(1) + [Part("collar", cubes=[Cube((-4.5, -1.6, -3.4), (9, 1, 7), paint=jewelled_collar)])]
    m.java_name, m.location = "SultanJawshanModel", "sultan_jawshan"
    m.tex = (128, 128)
    return m


def raja_regalia_model():
    return coat_model("RajaRegaliaModel", "raja_regalia", char_aina_body, arms(mail(), dastana, split=5), mail(1), 6,
                      extra=cloak_parts(-1) + [Part("collar", cubes=[Cube((-4.5, -1.6, -3.4), (9, 2, 7), paint=jewelled_collar)])], tex=(128, 64))


def ajaw_regalia_model():
    back = Part("jaguar_cape", pivot=(0, -0.5, 3.4), rot=(0.08, 0, 0), cubes=[Cube((-5.5, 0, -0.5), (11, 15, 1), paint=jaguar)])
    plaques = Part("jade_plaques", cubes=[Cube((-3, 2, -3.4), (6, 4, 1), paint=jade_plaque)])
    return coat_model("AjawRegaliaModel", "ajaw_regalia", cotton_with_collar, short_sleeves(cotton), cotton, 5, extra=[back, plaques],
                      tex=(128, 64))


def chief_parka_model():
    breast = Part("ivory_breastplate", cubes=[Cube((-4, 0, -3.6), (8, 7, 1), paint=bone_lamellar)])
    collar = Part("bead_collar", cubes=[Cube((-4.5, -1.6, -3.5), (9, 1, 7), paint=beads)])
    m = parka_model("ChiefParkaModel", "chief_parka", mosaic, PARKA_SLEEVE, mosaic, extra=pendants() + [breast, collar], cap=bone_lamellar)
    m.tex = (128, 64)
    return m


# ---------------------------------------------------------------------- the items

PURPLE = 0x5B2A86
SCARLET = 0xB0302A
CRIMSON = 0x8E2A2A
SAFFRON = 0xD9822B
BLUE = 0x2E5B8A
WHITE = 0xE6E1D3

# id, model factory, slot, (toughness, knockback, durability, defense, enchantability), default dye, sound
ARMOURS = [
    ("royal_crown_helm", royal_crown_helm_model, "HELMET", (2.0, 0.05, 420, 4, 22), PURPLE, "ARMOR_EQUIP_GOLD"),
    ("royal_surcoat", royal_surcoat_model, "CHESTPLATE", (2.0, 0.05, 460, 8, 22), PURPLE, "ARMOR_EQUIP_GOLD"),
    ("basileus_stemma", basileus_stemma_model, "HELMET", (1.5, 0.0, 380, 3, 25), PURPLE, "ARMOR_EQUIP_GOLD"),
    ("basileus_loros", basileus_loros_model, "CHESTPLATE", (2.0, 0.05, 460, 8, 25), PURPLE, "ARMOR_EQUIP_GOLD"),
    ("daimyo_kabuto", daimyo_kabuto_model, "HELMET", (2.0, 0.05, 420, 4, 22), SCARLET, "ARMOR_EQUIP_GOLD"),
    ("daimyo_yoroi", _daimyo_yoroi, "CHESTPLATE", (2.0, 0.05, 460, 8, 22), SCARLET, "ARMOR_EQUIP_GOLD"),
    ("sultan_helm", _sultan_helm, "HELMET", (2.0, 0.05, 420, 4, 22), WHITE, "ARMOR_EQUIP_GOLD"),
    ("sultan_jawshan", sultan_jawshan_model, "CHESTPLATE", (2.0, 0.05, 460, 8, 22), CRIMSON, "ARMOR_EQUIP_GOLD"),
    ("raja_crown_khud", raja_crown_khud_model, "HELMET", (2.0, 0.05, 420, 4, 22), SAFFRON, "ARMOR_EQUIP_GOLD"),
    ("raja_regalia", raja_regalia_model, "CHESTPLATE", (2.0, 0.05, 460, 8, 22), SAFFRON, "ARMOR_EQUIP_GOLD"),
    ("ajaw_headdress", ajaw_headdress_model, "HELMET", (0.5, 0.0, 300, 3, 22), WHITE, "ARMOR_EQUIP_LEATHER"),
    ("ajaw_regalia", ajaw_regalia_model, "CHESTPLATE", (1.0, 0.0, 360, 6, 22), WHITE, "ARMOR_EQUIP_LEATHER"),
    ("chief_crown_hood", chief_crown_hood_model, "HELMET", (0.5, 0.0, 300, 3, 20), BLUE, "ARMOR_EQUIP_LEATHER"),
    ("chief_parka", chief_parka_model, "CHESTPLATE", (1.0, 0.0, 360, 6, 20), BLUE, "ARMOR_EQUIP_LEATHER"),
]

NAMES = {
    "royal_crown_helm": "King's Crowned Bascinet", "royal_surcoat": "King's Surcoat",
    "basileus_stemma": "Stemma of the Basileus", "basileus_loros": "Loros of the Basileus",
    "daimyo_kabuto": "Daimyo's Kabuto", "daimyo_yoroi": "Daimyo's Ō-yoroi and Jinbaori",
    "sultan_helm": "Sultan's Helmet", "sultan_jawshan": "Sultan's Jawshan",
    "raja_crown_khud": "Raja's Crowned Khud", "raja_regalia": "Raja's Regalia",
    "ajaw_headdress": "Headdress of the Ajaw", "ajaw_regalia": "Regalia of the Ajaw",
    "chief_crown_hood": "Chief's Crowned Hood", "chief_parka": "Chief's Parka",
}

_G, _D, _E = "minecraft:gold_ingot", "minecraft:diamond", "minecraft:emerald"
RECIPES = {
    "royal_crown_helm": (["gdg", " h "], {"g": _G, "d": _D, "h": "magistuarmory:bascinet"}),
    "royal_surcoat": (["gdg", " c "], {"g": _G, "d": _D, "c": "magistuarmory:chainmail_chestplate"}),
    "basileus_stemma": (["gdg", "geg"], {"g": _G, "d": _D, "e": _E}),
    "basileus_loros": (["gdg", " k "], {"g": _G, "d": _D, "k": "culturesarmory:klibanion"}),
    "daimyo_kabuto": (["gdg", " k "], {"g": _G, "d": _D, "k": "culturesarmory:menpo_kabuto"}),
    "daimyo_yoroi": (["gdg", " o "], {"g": _G, "d": _D, "o": "culturesarmory:o_yoroi"}),
    "sultan_helm": (["gdg", " t "], {"g": _G, "d": _D, "t": "culturesarmory:turban"}),
    "sultan_jawshan": (["gdg", " j "], {"g": _G, "d": _D, "j": "culturesarmory:gilded_jawshan"}),
    "raja_crown_khud": (["gdg", " k "], {"g": _G, "d": _D, "k": "culturesarmory:gilded_kulah_khud"}),
    "raja_regalia": (["gdg", " c "], {"g": _G, "d": _D, "c": "culturesarmory:char_aina"}),
    "ajaw_headdress": (["eee", " q "], {"e": _E, "q": "culturesarmory:quetzal_headdress"}),
    "ajaw_regalia": (["eee", " j "], {"e": _E, "j": "culturesarmory:jade_pectoral_armour"}),
    "chief_crown_hood": (["bgb", " h "], {"b": "minecraft:bone", "g": _G, "h": "culturesarmory:wolverine_hood"}),
    "chief_parka": (["bgb", " p "], {"b": "minecraft:bone", "g": _G, "p": "culturesarmory:ceremonial_parka"}),
}
