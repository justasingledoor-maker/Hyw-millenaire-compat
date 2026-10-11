"""
More of Japan (Cultures Armory 1.5): the tōsei gusoku, the 'modern armour' of the Sengoku period (c. 1540-1615), when
armies of tens of thousands needed armour made quickly, and the gear of the ashigaru and the commanders who led them.

Helmets: the momonari kabuto (the 'peach' bowl of few plates, ridged front to back); the eboshi kabuto (raised in the
shape of the court cap); the nanban kabuto (a European cabasset of bright steel fitted with a Japanese neck guard); the
sugegasa (the conical straw hat of the levy and the porter).
Dō: the nimai-dō (two-piece, of riveted horizontal plates, lacquered black); the hotoke-dō (the plates lacquered over
smooth, in the clan's colour); the nanban-dō (a European peascod breastplate of bright steel, Japanese tassets); the
jinbaori (the commander's sleeveless surcoat, the clan's mon on its back, over the dō); the haramaki (the light
wrap-round armour of laced lames, opening at the back); the ashigaru's dō with a sashimono, the banner on its pole.
Legs: the ikada haidate ('raft' thigh guards of small plates in a grid). Feet: tsutsu suneate (tubular shin guards of
hinged plates with large knee guards); waraji (straw sandals over tabi). The lacing, lacquer and cloth are dyeable.
"""
import math

from armorgen import Cube, Model, Part
from japan import (B, C, D, G, L, M, S, W, crest_base, dyed_cloth, dyed_lacquer, fill_all, foot, fukigaeshi, gold,
                   hakama, kabuto_variant, kote, lacquer, lame_edge, laced_lames, sode, waist, watagami, zunari_bowl,
                   hishinui, lace_cell, chin_cords)
from seljuk import STEEL, faces_fill, legs_model

MON = [".###.",
       "#.#.#",
       "###.#",
       "#.#.#",
       ".###."]          # a large mon (a wheel in a ring) for the jinbaori's back and the sashimono


# ---------------------------------------------------------------------- painters

def plate_rows(rows=2, mon=False, rivets=3):
    """Riveted horizontal plates (yokohagi): each plate's lit top edge, a rivet every few pixels, black lacquer."""
    def paint(f):
        cx = (f.w - 5) // 2
        for s in range(f.strip_w):
            face, i = f.face_of(s)
            for t in range(f.h):
                if mon and face == "front" and 2 <= t < 7 and 0 <= i - cx < 5 and MON[t - 2][i - cx] == "#":
                    f.side(s, t, G[3] if (t + i) % 2 else G[4])
                    continue
                if t == f.h - 1:
                    rgb, dye = hishinui(s)
                    f.side(s, t, rgb, dye)
                    continue
                r = t % rows
                if r == 0:
                    rgb = S[3] if s % rivets == 1 else L[4]
                else:
                    rgb = L[2] if s % 4 else L[3]
                f.side(s, t, f.noise(rgb, 3))
        for i in range(f.w):
            for j in range(f.d):
                f.top(i, j, f.noise(L[3] if i in (0, f.w - 1) or j in (0, f.d - 1) else L[2], 3))
                f.bottom(i, j, L[1])
    return paint


def smooth_lacquer(f):
    """Hotoke-dō: the plates lacquered over smooth in the clan's colour; a gilt-capped top edge, a sheen down the front."""
    cx = f.w // 2
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if t == 0:
                f.side(s, t, G[3] if s % 2 else G[2])
            elif t == f.h - 1:
                rgb, dye = hishinui(s)
                f.side(s, t, rgb, dye)
            elif face == "front" and i in (cx - 2, cx - 3) and 1 < t < f.h - 3:
                f.side(s, t, D[4], True)
            else:
                f.side(s, t, f.noise(D[3] if t < f.h // 2 else D[2], 2), True)
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, D[3], True)
            f.bottom(i, j, D[1], True)


def peascod(f):
    """The nanban-dō: a European breastplate of bright steel, its ridge down the middle swelling to a point (the peascod),
    a rolled neck edge, rivets at the sides."""
    cx = (f.w - 1) / 2.0
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if t == 0:
                rgb = STEEL[4] if s % 2 else STEEL[3]
            elif face == "front" and abs(i - cx) < 0.6:
                rgb = STEEL[4]
            elif face == "front":
                d = abs(i - cx)
                rgb = STEEL[3] if d < 2.5 else STEEL[2] if d < 4 else STEEL[1]
                if t > 6 and d < 1.6:
                    rgb = STEEL[4]                                       # the swelling of the peascod
            elif t == f.h - 1:
                rgb = L[1]
            else:
                rgb = STEEL[1] if s % 5 else STEEL[3]
            f.side(s, t, f.noise(rgb, 2))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, STEEL[3])
            f.bottom(i, j, STEEL[0])


def jinbaori_cloth(f):
    """The jinbaori: dyed wool (often imported European broadcloth), a white collar, the clan's mon in gilt on the back."""
    cx = (f.w - 5) // 2
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face == "back" and 2 <= t < 7 and 0 <= i - cx < 5 and MON[t - 2][i - cx] == "#":
                f.side(s, t, G[3] if (t + i) % 2 else G[4])
                continue
            if t == 0 or (face == "front" and i in (f.w // 2 - 1, f.w // 2)):
                f.side(s, t, M[2] if s % 2 else M[1])                   # the white collar and front edges
                continue
            f.side(s, t, f.noise(D[3] if s % 5 else D[2], 2), True)
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, M[2])
            f.bottom(i, j, D[2], True)


def banner(f):
    """The sashimono: a dyed silk banner, a white mon in its upper half."""
    cx = (f.w - 5) // 2
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face in ("front", "back") and 2 <= t < 7 and 0 <= i - cx < 5:
                k = i - cx if face == "front" else 4 - (i - cx)
                if MON[t - 2][k] == "#":
                    f.side(s, t, M[2])
                    continue
            f.side(s, t, f.noise(D[3] if t else D[4], 2), True)
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, D[3], True)
            f.bottom(i, j, D[2], True)


def bamboo(f):
    fill_all(f, lambda face, i, t, s: W[2] if face in ("top", "bottom") else (W[0] if t % 6 == 0 else W[1] if s % 2 else W[2]))


def holder(f):
    """The uke-zutsu: the lacquered socket on the back that holds the banner pole."""
    fill_all(f, lambda face, i, t, s: L[3] if t == 0 else L[2])


def ikada(f):
    """Ikada haidate: small black-lacquered plates set in a grid on the cloth, like the logs of a raft."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face == "back":
                rgb, dye = C[1], False
            elif t == 0:
                rgb, dye = C[3], False
            elif t % 3 == 0 or s % 3 == 2:
                rgb, dye = (D[2], True) if (s + t) % 2 else (C[1], False)  # the lacing between the plates
            else:
                rgb, dye = (L[4] if t % 3 == 1 else L[2]), False
            f.side(s, t, f.noise(rgb, 3), dye)
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, C[3])
            f.bottom(i, j, L[1])


def tsutsu_shin(f):
    """Tsutsu suneate: three long plates hinged into a tube, lacquered, a gilt hinge line, tied twice behind."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            dye = False
            if face == "back":
                rgb, dye = ((D[2], True) if t in (1, 4) else (C[1], False))
            elif face in ("right", "left") and i == f.d // 2:
                rgb = G[2] if t % 2 else G[3]                            # the hinges
            else:
                rgb = L[4] if t == 0 else L[3] if s % 3 == 0 else L[2]
            f.side(s, t, f.noise(rgb, 3), dye)
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, L[3])
            f.bottom(i, j, L[1])


def big_knee(f):
    """The tateage: a large knee and lower-thigh guard of small plates."""
    fill_all(f, lambda face, i, t, s: G[2] if t == 0 else (L[4] if t % 2 == 1 else L[2]) if face == "front" else L[2])


def tabi(f):
    """White tabi, the straw cords of the waraji tied round the ankle."""
    fill_all(f, lambda face, i, t, s: (W[1] if t == 1 and s % 3 else M[2] if (s + t) % 4 else M[1]))


def straw(f):
    fill_all(f, lambda face, i, t, s: W[2] if face == "top" and (i + t) % 3 == 0 else W[1] if (s + t) % 2 else W[0])


# ---------------------------------------------------------------------- helmets

def _head(model):
    return next(r for r in model.roots if r.name == "head")


def momonari_model():
    """The momonari kabuto: a bowl of few plates rising to a ridge front to back (the 'peach'), a close neck guard."""
    m = kabuto_variant("MomonariKabutoModel", "momonari_kabuto", zunari_bowl, shikoro=(4, 0.3, 10, 7), fuki=(1, 2, fukigaeshi),
                       crest=None)
    head = _head(m)
    head.cubes = [
        Cube((-4.5, -9, -4.5), (9, 4, 9), paint=zunari_bowl),
        Cube((-3.5, -11, -4), (7, 2, 8), paint=zunari_bowl),
        Cube((-2, -12, -3.5), (4, 1, 7), paint=lacquer()),
        Cube((-0.5, -13, -3), (1, 1, 6), paint=lacquer()),                      # the ridge
    ]
    return m


def eboshi_model():
    """The eboshi kabuto: raised in lacquered papier-mâché over the bowl in the shape of the court noble's tall cap,
    leaning back, a gilt mon at its front."""
    m = kabuto_variant("EboshiKabutoModel", "eboshi_kabuto", zunari_bowl, bowl_h=3, shikoro=(4, 0.4, 10, 7), fuki=(2, 2, fukigaeshi),
                       crest=None)
    head = _head(m)
    head.cubes = [Cube((-4.5, -8, -4.5), (9, 3, 9), paint=zunari_bowl)]
    head.children.append(Part("eboshi", pivot=(0, -8, 0.5), rot=(-0.3, 0, 0), cubes=[
        Cube((-3, -7, -4), (6, 7, 8), paint=lacquer()),
        Cube((-2, -9, -2), (4, 2, 5), paint=lacquer()),
    ], children=[Part("eboshi_mon", cubes=[Cube((-1, -4, -4.6), (2, 2, 1), paint=crest_base)])]))
    return m


def nanban_model():
    """The nanban kabuto: a European cabasset of bright steel, pear-shaped with a stalk at the top and a narrow brim,
    fitted with a Japanese peak and a neck guard of laced lames."""
    m = kabuto_variant("NanbanKabutoModel", "nanban_kabuto", zunari_bowl, shikoro=(5, 0.5, 11, 8), fuki=(0, 0, None),
                       crest=None)
    head = _head(m)
    bright = steel_shell
    head.cubes = [
        Cube((-4.5, -8, -4.5), (9, 3, 9), paint=bright),
        Cube((-4, -9, -4), (8, 1, 8), paint=bright),
        Cube((-3.5, -10, -3.5), (7, 1, 7), paint=bright),
        Cube((-2.5, -11, -2.5), (5, 1, 5), paint=bright),
        Cube((-1.5, -12, -1.5), (3, 1, 3), paint=bright),
        Cube((-0.5, -13, -0.5), (1, 1, 1), paint=gold),                         # the stalk
        Cube((-5, -5.5, -5), (10, 1, 10), paint=bright),                         # the narrow brim
    ]
    head.children = [c for c in head.children if not c.name.startswith("fukigaeshi")]
    return m


def steel_shell(f):
    faces_fill(f, lambda face, i, t, s: (STEEL[4] if t == 0 else STEEL[3] if s % 4 else STEEL[2], False),
               lambda i, j: (STEEL[4] if (i + j) % 3 == 0 else STEEL[3], False), lambda i, j: (STEEL[1], False), noise=2)


def sugegasa_model():
    """The sugegasa: a broad cone of plaited sedge, tied under the chin."""
    def tier(f):
        c = ((f.w - 1) / 2.0, (f.d - 1) / 2.0)
        for s in range(f.strip_w):
            for t in range(f.h):
                f.side(s, t, f.noise(W[0], 3))
        for i in range(f.w):
            for j in range(f.d):
                ang = math.atan2(j - c[1], i - c[0])
                rib = int((ang + math.pi) / (2 * math.pi) * 28) % 2 == 0
                f.top(i, j, f.noise(W[2] if rib else W[1], 3))
                f.bottom(i, j, W[0])
    head = Part("head", cubes=[
        Cube((-8, -8.5, -8), (16, 1, 16), paint=tier),
        Cube((-6, -9.5, -6), (12, 1, 12), paint=tier),
        Cube((-4, -10.5, -4), (8, 1, 8), paint=tier),
        Cube((-2, -11.5, -2), (4, 1, 4), paint=tier),
        Cube((-4, -8, -4), (8, 8, 8), inflate=0.3, paint=chin_cords),
    ])
    return Model("SugegasaModel", "sugegasa", {"head": head}, tex=(128, 64))


# ---------------------------------------------------------------------- dō

def kusazuri_parts(paint, length=5, rot=0.15, split=True):
    """The tassets: two in front, two behind, one at each side."""
    out = []
    for name, pz, r in (("front", -3, -rot), ("back", 3, rot)):
        if split:
            out += [Part(f"kusazuri_{name}_right", pivot=(-2.5, 10, pz), rot=(r, 0, 0), cubes=[Cube((-2.5, 0, -0.5), (5, length, 1), paint=paint)]),
                    Part(f"kusazuri_{name}_left", pivot=(2.5, 10, pz), rot=(r, 0, 0), cubes=[Cube((-2.5, 0, -0.5), (5, length, 1), paint=paint)])]
        else:
            out.append(Part(f"kusazuri_{name}", pivot=(0, 10, pz), rot=(r, 0, 0), cubes=[Cube((-5, 0, -0.5), (10, length, 1), paint=paint)]))
    out += [Part("kusazuri_right", pivot=(-5, 10, 0), rot=(0, 0, rot), cubes=[Cube((-0.5, 0, -3), (1, length, 6), paint=paint)]),
            Part("kusazuri_left", pivot=(5, 10, 0), rot=(0, 0, -rot), cubes=[Cube((-0.5, 0, -3), (1, length, 6), paint=paint)])]
    return out


def gusoku_arms(sode_paint=sode, sode_h=5):
    """Kote on both arms, a small tōsei sode on each shoulder."""
    out = {}
    for name, x, sgn in (("right_arm", -3.5, -1), ("left_arm", -1.5, 1)):
        ox = -1 if sgn < 0 else 0
        out[name] = Part(name, cubes=[Cube((x, -2.5, -2.5), (5, 12, 5), paint=kote)], children=[
            Part(name.replace("arm", "sode"), pivot=(sgn * 3.5, -3, 0), rot=(0, 0, -sgn * 0.16),
                 cubes=[Cube((ox, 0, -2.5), (1, sode_h, 5), paint=sode_paint)])])
    return out


def do_model(java_name, location, body_paint, kusazuri_paint, extra=(), arm_parts=None, length=5, tex=(64, 64)):
    body = Part("body", cubes=[Cube((-5, -1, -3), (10, 11, 6), paint=body_paint)],
                children=kusazuri_parts(kusazuri_paint, length) + list(extra))
    return Model(java_name, location, dict(arm_parts or gusoku_arms(), body=body), tex=tex)


def nimai_do_model():
    return do_model("NimaiDoModel", "nimai_do", plate_rows(2), laced_lames(lame=2))


def hotoke_do_model():
    return do_model("HotokeDoModel", "hotoke_do", smooth_lacquer, laced_lames(lame=2), arm_parts=gusoku_arms(dyed_lacquer()))


def nanban_do_model():
    return do_model("NanbanDoModel", "nanban_do", peascod, laced_lames(lame=2))


def jinbaori_model():
    """The jinbaori over a dō: the surcoat's body, its long back hanging past the tassets, the dō's lacing below."""
    extra = [Part("jinbaori_tail", pivot=(0, 9.5, 3.4), rot=(0.12, 0, 0), cubes=[Cube((-5, 0, -0.5), (10, 7, 1), paint=dyed_cloth(pleats=3))])]
    return do_model("JinbaoriModel", "jinbaori", jinbaori_cloth, laced_lames(lame=2), extra=extra)


def haramaki_model():
    """The haramaki: a light wrap of laced lames closing at the back, seven narrow tassets, shoulder straps only."""
    arm_parts = {"right_arm": Part("right_arm", cubes=[Cube((-3.5, -2.5, -2.5), (5, 1, 5), paint=watagami)]),
                 "left_arm": Part("left_arm", cubes=[Cube((-1.5, -2.5, -2.5), (5, 1, 5), paint=watagami)])}
    return do_model("HaramakiModel", "haramaki", laced_lames(lame=2, plate_rows=1), laced_lames(lame=2), arm_parts=arm_parts, length=6)


def sashimono_do_model():
    """The ashigaru's dō of riveted plates with the sashimono: the clan's banner on a bamboo pole in a socket on the back."""
    extra = [
        Part("uke_zutsu", cubes=[Cube((-1, 6, 3), (2, 3, 1), paint=holder)]),
        Part("sashimono_pole", cubes=[Cube((-0.5, -20, 3.5), (1, 28, 1), paint=bamboo)]),
        Part("sashimono_bar", cubes=[Cube((-0.5, -20, 3.5), (6, 1, 1), paint=bamboo)]),
        Part("sashimono", cubes=[Cube((0.5, -19, 3.5), (5, 10, 1), paint=banner)]),
    ]
    arm_parts = {"right_arm": Part("right_arm", cubes=[Cube((-3.5, -2.5, -2.5), (5, 1, 5), paint=watagami)]),
                 "left_arm": Part("left_arm", cubes=[Cube((-1.5, -2.5, -2.5), (5, 1, 5), paint=watagami)])}
    return do_model("SashimonoDoModel", "sashimono_do", plate_rows(2, mon=True, rivets=5), dyed_lacquer(), extra=extra,
                     arm_parts=arm_parts, tex=(64, 64))


# ---------------------------------------------------------------------- legs and feet

def ikada_haidate_model():
    def leg(name):
        return Part(name, cubes=[Cube((-2.5, -0.5, -2.5), (5, 13, 5), paint=hakama)], children=[
            Part(name.replace("leg", "haidate"), pivot=(0, 0, -2.6), rot=(-0.12, 0, 0), cubes=[Cube((-3, 0, -1.25), (6, 8, 1), paint=ikada)])])
    return Model("IkadaHaidateModel", "ikada_haidate", {"body": Part("body", cubes=[Cube((-4.5, 9.5, -2.5), (9, 3, 5), paint=waist)]),
                                                        "right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


def tsutsu_suneate_model():
    def leg(name):
        return Part(name, cubes=[Cube((-3, 4.5, -3), (6, 7, 6), paint=tsutsu_shin), Cube((-3, 11.5, -3.5), (6, 1, 7), paint=foot)],
                    children=[Part(name.replace("leg", "tateage"), pivot=(0, 4.5, -3), rot=(0.18, 0, 0),
                                   cubes=[Cube((-2.5, -3, -0.75), (5, 4, 1), paint=big_knee)])])
    return Model("TsutsuSuneateModel", "tsutsu_suneate", {"right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


def waraji_model():
    def leg(name):
        return Part(name, cubes=[Cube((-3, 9.5, -3), (6, 2, 6), paint=tabi), Cube((-3, 11.5, -3.5), (6, 1, 7), paint=foot)])
    return Model("WarajiModel", "waraji", {"right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


# ---------------------------------------------------------------------- the items

KURO = 0x2E2E34
SHU = 0xA8302A          # shu-urushi: vermilion lacquer
KON = 0x2C3E78
MOEGI = 0x5A7A3A        # moegi: leek green
KACHI = 0x2A3048        # kachi-iro: the 'victory' dark blue
WHITE = 0xE6E1D3

ARMOURS = [
    ("momonari_kabuto", momonari_model, "HELMET", (0.75, 0.0, 250, 3, 10), KURO, "ARMOR_EQUIP_IRON"),
    ("eboshi_kabuto", eboshi_model, "HELMET", (1.0, 0.0, 270, 3, 12), KURO, "ARMOR_EQUIP_IRON"),
    ("nanban_kabuto", nanban_model, "HELMET", (1.25, 0.0, 290, 3, 10), KON, "ARMOR_EQUIP_IRON"),
    ("sugegasa", sugegasa_model, "HELMET", (0.0, 0.0, 70, 1, 8), WHITE, "ARMOR_EQUIP_LEATHER"),
    ("nimai_do", nimai_do_model, "CHESTPLATE", (1.0, 0.0, 310, 7, 10), KON, "ARMOR_EQUIP_IRON"),
    ("hotoke_do", hotoke_do_model, "CHESTPLATE", (1.0, 0.0, 310, 7, 11), SHU, "ARMOR_EQUIP_IRON"),
    ("nanban_do", nanban_do_model, "CHESTPLATE", (1.5, 0.0, 340, 8, 10), KACHI, "ARMOR_EQUIP_IRON"),
    ("jinbaori", jinbaori_model, "CHESTPLATE", (1.0, 0.0, 300, 7, 13), SHU, "ARMOR_EQUIP_CHAIN"),
    ("haramaki", haramaki_model, "CHESTPLATE", (0.5, 0.0, 260, 5, 10), MOEGI, "ARMOR_EQUIP_CHAIN"),
    ("sashimono_do", sashimono_do_model, "CHESTPLATE", (0.25, 0.0, 250, 5, 9), SHU, "ARMOR_EQUIP_IRON"),
    ("ikada_haidate", ikada_haidate_model, "LEGGINGS", (0.75, 0.0, 270, 5, 10), KON, "ARMOR_EQUIP_CHAIN"),
    ("tsutsu_suneate", tsutsu_suneate_model, "BOOTS", (0.75, 0.0, 220, 2, 10), KON, "ARMOR_EQUIP_IRON"),
    ("waraji", waraji_model, "BOOTS", (0.0, 0.0, 60, 1, 8), WHITE, "ARMOR_EQUIP_LEATHER"),
]

NAMES = {
    "momonari_kabuto": "Momonari Kabuto", "eboshi_kabuto": "Eboshi Kabuto", "nanban_kabuto": "Nanban Kabuto",
    "sugegasa": "Sugegasa", "nimai_do": "Nimai-dō", "hotoke_do": "Hotoke-dō", "nanban_do": "Nanban-dō",
    "jinbaori": "Jinbaori over Dō", "haramaki": "Haramaki", "sashimono_do": "Ashigaru Dō with Sashimono",
    "ikada_haidate": "Ikada Haidate", "tsutsu_suneate": "Tsutsu Suneate", "waraji": "Waraji and Tabi",
}

_I, _P, _R = "#c:ingots/steel", "#c:plates/steel", "#magistuarmory:lamellar_rows/steel"
_F, _L, _S = "#magistuarmory:woolen_fabrics", "#magistuarmory:leather_strips", "minecraft:string"
RECIPES = {
    "momonari_kabuto": (["ppp", "pip", "rsr"], {"p": _P, "i": _I, "r": _R, "s": _S}),
    "eboshi_kabuto": ([" p ", "pkp"], {"p": "minecraft:paper", "k": "culturesarmory:momonari_kabuto"}),
    "nanban_kabuto": (["i i", "pip", "rsr"], {"p": _P, "i": _I, "r": _R, "s": _S}),
    "sugegasa": ([" w ", "www", "s s"], {"w": "minecraft:wheat", "s": _S}),
    "nimai_do": (["p p", "ppp", "rpr"], {"p": _P, "r": _R}),
    "hotoke_do": (["d", "n"], {"d": "minecraft:red_dye", "n": "culturesarmory:nimai_do"}),
    "nanban_do": (["i i", "iii", "rsr"], {"i": _I, "r": _R, "s": _S}),
    "jinbaori": (["fgf", "fnf"], {"f": _F, "g": "minecraft:gold_nugget", "n": "culturesarmory:nimai_do"}),
    "haramaki": (["r r", "rrr", "sss"], {"r": _R, "s": _S}),
    "sashimono_do": (["  b", "fo "], {"b": "minecraft:bamboo", "f": _F, "o": "culturesarmory:okegawa_do"}),
    "ikada_haidate": (["fsf", "p p", "p p"], {"f": _F, "s": _S, "p": _P}),
    "tsutsu_suneate": (["p p", "p p", "l l"], {"p": _P, "l": _L}),
    "waraji": (["w w", "s s"], {"w": "minecraft:wheat", "s": "minecraft:white_wool"}),
}
