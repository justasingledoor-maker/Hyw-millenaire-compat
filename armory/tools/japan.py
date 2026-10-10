"""
The Japanese armours of the Cultures Armory, after the Sengoku-period tosei gusoku (modern equipment) and the ashigaru's
munitions armour (okashi gusoku). Run: python3 tools/build_assets.py

Samurai: kabuto (suji-bachi bowl, flared shikoro, fukigaeshi, kuwagata crest), kabuto with menpo (a red-lacquered face
mask with a moustache, and a throat guard), the do with sode (shoulder boards), kote (armoured sleeves) and kusazuri
(tassets), haidate (thigh apron) and suneate (splinted shin guards over tabi and straw sandals). Black lacquered lames
laced with silk (kebiki odoshi): the lacing is the dyeable part, scarlet by default.

Ashigaru: jingasa (the lacquered conical hat) and okegawa-do (riveted horizontal plates, sparse lacing, kusazuri): the
lacquer is dyeable (clan colours, black by default), the clan's mon is gold.
"""
import math

from armorgen import Cube, Model, Part

# ---------------------------------------------------------------------- palette (sampled to sit beside Epic Knights)

L = [(18, 18, 22), (31, 31, 37), (46, 46, 54), (68, 68, 79), (99, 99, 111)]          # black urushi lacquer
G = [(92, 61, 14), (143, 101, 28), (196, 149, 46), (232, 195, 90), (247, 227, 154)]   # gilt
S = [(91, 91, 91), (125, 125, 125), (163, 163, 163), (200, 200, 200), (230, 230, 230)]  # bright steel (EK)
D = [(138, 138, 138), (185, 185, 185), (214, 214, 214), (236, 236, 236), (255, 255, 255)]  # dyeable greys
C = [(21, 24, 42), (31, 36, 64), (43, 49, 86), (58, 65, 107)]                           # indigo cloth
B = [(46, 29, 16), (74, 48, 26), (107, 70, 39), (140, 96, 56)]                           # leather
W = [(138, 115, 64), (179, 154, 90), (212, 189, 124)]                                    # straw
R = [(61, 15, 12), (94, 26, 21), (130, 39, 31), (168, 57, 45), (199, 84, 70)]           # red lacquer (menpo)
M = [(142, 138, 130), (201, 196, 186), (235, 231, 222)]                                  # white hair, tabi
WHITE_CORD = (232, 226, 210)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


# ---------------------------------------------------------------------- painters

def fill_all(f, rgb_of, dye=False):
    """Every face: rgb_of(face, i, t) for the sides, and the top and bottom from the same function."""
    for s in range(f.strip_w):
        name, i = f.face_of(s)
        for t in range(f.h):
            f.side(s, t, f.noise(rgb_of(name, i, t, s), 3), dye)
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, f.noise(rgb_of("top", i, j, i), 3), dye)
            f.bottom(i, j, f.noise(rgb_of("bottom", i, j, i), 3), dye)


def lacquer(edge_top=True):
    def paint(f):
        def rgb(face, i, t, s):
            if face in ("top", "bottom"):
                return L[2] if face == "top" else L[1]
            if edge_top and t == 0:
                return L[3]
            return L[2] if (s % 4) else L[3]
        fill_all(f, rgb)
    return paint


def lace_cell(s, row_in_lace):
    """Kebiki odoshi: close-set vertical silk cords; from a distance a band of the silk's colour."""
    col = s % 3
    shades = [(D[4], D[3], D[1]), (D[3], D[2], D[1])][row_in_lace % 2]
    return shades[col], True


def lame_edge(s):
    """The lacquered top edge of a lame: a dark line, the rivet heads of its scales catching the light."""
    return (L[3] if s % 3 == 1 else L[1]), False


def hishinui(s):
    """The bottom lame's cross-stitching (hishi-nui), in white."""
    return (WHITE_CORD, False) if s % 4 == 1 else (L[1], False)


def laced_lames(lame=2, plate_rows=0, hishi=True, gilt_plate=False):
    """Rows of lacquered lames, each a lit edge then lacing; a solid plate at the top; cross-stitching at the bottom."""
    def side_px(f, s, t):
        if t < plate_rows:
            if t == 0:
                return (G[3] if gilt_plate and s % 2 == 0 else L[4] if s % 2 == 0 else L[3]), False
            return (G[3] if gilt_plate and s % 5 == 2 else L[2]), False
        if hishi and t == f.h - 1:
            return hishinui(s)
        r = (t - plate_rows) % lame
        if r == 0:
            return lame_edge(s)
        return lace_cell(s, r - 1)

    def paint(f):
        for s in range(f.strip_w):
            for t in range(f.h):
                rgb, dye = side_px(f, s, t)
                f.side(s, t, f.noise(rgb, 3), dye)
        for i in range(f.w):
            for j in range(f.d):
                f.top(i, j, f.noise(L[3] if j in (0, f.d - 1) else L[2], 3))
                f.bottom(i, j, f.noise(L[1], 3))
    return paint


def gold(f):
    def rgb(face, i, t, s):
        return {"front": G[3], "right": G[4], "left": G[2], "back": G[1], "top": G[4], "bottom": G[1]}[face] \
            if not (face in ("front", "right", "left", "back") and t == f.h - 1) else G[2]
    fill_all(f, rgb)


# ------------------------------------------------------------- kabuto

def bowl(f):
    """Suji-bachi: a riveted bowl of standing ridges, its rim (koshimaki) with gilt rivets, a gilt shinodare at the front."""
    cx = (f.w - 1) / 2.0
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if t == f.h - 1:
                rgb = G[2] if s % 4 == 1 else L[3]
            elif face == "front" and abs(i - cx) < 0.6 and t >= 1:
                rgb = G[3] if t % 2 else G[2]
            else:
                ridge = s % 2 == 0
                rgb = [(L[4], L[3]), (L[3], L[2]), (L[3], L[1]), (L[2], L[1])][min(t, 3)][0 if ridge else 1]
            f.side(s, t, f.noise(rgb, 3))
    c = ((f.w - 1) / 2.0, (f.d - 1) / 2.0)
    for i in range(f.w):
        for j in range(f.d):
            ang = math.atan2(j - c[1], i - c[0])
            ridge = int((ang + math.pi) / (2 * math.pi) * 18) % 2 == 0
            f.top(i, j, f.noise(L[3] if ridge else L[1], 3))
            f.bottom(i, j, L[0])


def bowl_cap(f):
    c = ((f.w - 1) / 2.0, (f.d - 1) / 2.0)
    for s in range(f.strip_w):
        for t in range(f.h):
            f.side(s, t, f.noise(L[3] if s % 2 == 0 else L[1], 3))
    for i in range(f.w):
        for j in range(f.d):
            ang = math.atan2(j - c[1], i - c[0])
            ridge = int((ang + math.pi) / (2 * math.pi) * 14) % 2 == 0
            f.top(i, j, f.noise(L[3] if ridge else L[1], 3))
            f.bottom(i, j, L[0])


def visor(f):
    """Mabizashi: the peak, lacquered, with a gilt edge at the front."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            f.side(s, t, f.noise(G[2] if face == "front" else L[3], 3))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, f.noise(L[4] if j == f.d - 1 else L[3], 3))
            f.bottom(i, j, L[1])


def fukigaeshi(f):
    """The turn-backs: lacquer with a gilt rim and a gilt mon in the middle (facing forwards)."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face == "front":
                rgb = G[3] if t == 0 else (L[3] if t == 1 else L[2])
            else:
                rgb = L[2]
            f.side(s, t, f.noise(rgb, 3))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, G[2])
            f.bottom(i, j, L[1])


def crest_base(f):
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            f.side(s, t, G[4] if face == "front" and t == 0 else G[3] if face == "front" else G[2])
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, G[3])
            f.bottom(i, j, G[1])


def kabuto_parts(mask=False):
    """The kabuto on the head part (children rotate the shikoro, turn-backs, visor and crest)."""
    head = Part("head", cubes=[
        Cube((-4.5, -9, -4.5), (9, 4, 9), paint=bowl),                   # hachi
        Cube((-3.5, -10, -3.5), (7, 1, 7), paint=bowl_cap),              # crown
        Cube((-1, -11, -1), (2, 1, 2), paint=gold),                       # tehen kanamono (crown rosette)
    ], children=[
        Part("mabizashi", pivot=(0, -5, -4.5), rot=(0.3, 0, 0), cubes=[Cube((-4.5, 0, -2), (9, 1, 2), paint=visor)]),
        Part("shikoro_back", pivot=(0, -5, 4.5), rot=(0.55, 0, 0), cubes=[
            Cube((-5.5, 0, -0.5), (11, 5, 1), paint=laced_lames(lame=2))]),
        Part("shikoro_right", pivot=(-4.5, -5, 0), rot=(0, 0, 0.55), cubes=[
            Cube((-0.5, 0, -3), (1, 5, 8), paint=laced_lames(lame=2))]),
        Part("shikoro_left", pivot=(4.5, -5, 0), rot=(0, 0, -0.55), cubes=[
            Cube((-0.5, 0, -3), (1, 5, 8), paint=laced_lames(lame=2))]),
        Part("fukigaeshi_right", pivot=(-5.2, -5, -2.8), rot=(0, 0.65, 0.3), cubes=[Cube((-2, 0, -0.5), (2, 3, 1), paint=fukigaeshi)]),
        Part("fukigaeshi_left", pivot=(5.2, -5, -2.8), rot=(0, -0.65, -0.3), cubes=[Cube((0, 0, -0.5), (2, 3, 1), paint=fukigaeshi)]),
        Part("haraidate", pivot=(0, -6.5, -4.6), cubes=[Cube((-1, -1, -1), (2, 2, 1), paint=crest_base)]),
        Part("kuwagata_right", pivot=(-0.6, -7, -5.3), rot=(-0.15, 0, -0.32), cubes=[Cube((-0.5, -6, -0.5), (1, 6, 1), paint=gold)]),
        Part("kuwagata_left", pivot=(0.6, -7, -5.3), rot=(-0.15, 0, 0.32), cubes=[Cube((-0.5, -6, -0.5), (1, 6, 1), paint=gold)]),
    ])
    if mask:
        head.children += [
            Part("menpo", cubes=[Cube((-4.5, -3, -5.5), (9, 4, 1), paint=menpo_face)]),
            Part("menpo_nose", cubes=[Cube((-1, -4, -6.5), (2, 2, 1), paint=menpo_nose)]),
            Part("yodarekake", pivot=(0, 1, -5), rot=(-0.25, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, 3, 1), paint=laced_lames(lame=2))]),
        ]
    return head


def menpo_face(f):
    """A red-lacquered menpo (cheeks to chin) with a white moustache and chin tuft, and a dark mouth with teeth."""
    rows = [
        "123333321",
        "1mmw3wmm1",
        "12R000R21",
        "0123w3210",
    ]
    key = {"0": R[0], "1": R[1], "2": R[2], "3": R[3], "R": R[2], "m": M[2], "w": M[1]}
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face == "front":
                rgb = key[rows[t][i]]
            else:
                rgb = R[1]
            f.side(s, t, f.noise(rgb, 2))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, R[2])
            f.bottom(i, j, R[1])


def menpo_nose(f):
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            rgb = {"front": R[3] if t == 0 else R[2], "right": R[3], "left": R[2], "back": R[1]}[face]
            f.side(s, t, rgb)
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, R[4])
            f.bottom(i, j, R[0])


def kabuto_model(mask=False):
    return Model("MenpoKabutoModel" if mask else "KabutoModel", "menpo_kabuto" if mask else "kabuto",
                 {"head": kabuto_parts(mask)})


# ------------------------------------------------------------- samurai do

def do_body(f):
    """The do: a solid breastplate (munaita) with gilt rivets, then laced lames; an agemaki bow on the back."""
    cx = f.w // 2
    bow = {(cx - 2, 4): D[2], (cx - 1, 4): D[4], (cx, 4): D[4], (cx + 1, 4): D[2],
           (cx - 1, 5): D[3], (cx, 5): D[3],
           (cx - 3, 6): D[2], (cx - 2, 6): D[3], (cx + 1, 6): D[3], (cx + 2, 6): D[2],
           (cx - 1, 7): D[2], (cx, 7): D[2]}
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face == "back" and (i, t) in bow:
                f.side(s, t, bow[(i, t)], True)
                continue
            if t == 0:
                rgb, dye = (L[4] if s % 2 == 0 else L[3]), False
            elif t == 1:
                rgb, dye = (G[3] if (face == "front" and i in (1, f.w - 2)) else L[2]), False
            elif t == f.h - 1:
                rgb, dye = hishinui(s)
            else:
                r = (t - 2) % 3
                if r == 0:
                    rgb, dye = lame_edge(s)
                else:
                    rgb, dye = lace_cell(s, r - 1)
            f.side(s, t, f.noise(rgb, 3), dye)
    for i in range(f.w):
        for j in range(f.d):
            edge = i in (0, f.w - 1) or j in (0, f.d - 1)
            f.top(i, j, f.noise(L[3] if edge else L[2], 3))
            f.bottom(i, j, L[1])


def sode(f):
    """The shoulder board: a gilt-edged top plate (kanmuri-ita), laced lames, cross-stitching at the bottom."""
    laced_lames(lame=2, plate_rows=1, gilt_plate=True)(f)


def kote(f):
    """The armoured sleeve: indigo cloth, small hexagonal plates (kikko) on the upper arm, lacquered splints (shino)
    and mail on the forearm, a cuff at the wrist."""
    for s in range(f.strip_w):
        for t in range(f.h):
            if t <= 4:
                plate = t in (1, 3) and s % 3 == (1 if t == 1 else 2)
                rgb = L[3] if plate else (C[2] if s % 3 else C[1])
            elif t <= f.h - 2:
                if s % 3 == 0:
                    rgb = L[4] if t == 5 else L[2]
                else:
                    rgb = C[1] if s % 3 == 1 else C[2]
            else:
                rgb = C[3]
            f.side(s, t, f.noise(rgb, 3))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, f.noise(C[2], 3))
            f.bottom(i, j, C[1])


def kusazuri(f):
    laced_lames(lame=2)(f)


def samurai_do_model():
    body = Part("body", cubes=[Cube((-5, -1, -3), (10, 11, 6), paint=do_body)], children=[
        Part("kusazuri_front_right", pivot=(-2.5, 10, -3), rot=(-0.18, 0, 0), cubes=[Cube((-2.5, 0, -0.5), (5, 6, 1), paint=kusazuri)]),
        Part("kusazuri_front_left", pivot=(2.5, 10, -3), rot=(-0.18, 0, 0), cubes=[Cube((-2.5, 0, -0.5), (5, 6, 1), paint=kusazuri)]),
        Part("kusazuri_back_right", pivot=(-2.5, 10, 3), rot=(0.18, 0, 0), cubes=[Cube((-2.5, 0, -0.5), (5, 6, 1), paint=kusazuri)]),
        Part("kusazuri_back_left", pivot=(2.5, 10, 3), rot=(0.18, 0, 0), cubes=[Cube((-2.5, 0, -0.5), (5, 6, 1), paint=kusazuri)]),
        Part("kusazuri_right", pivot=(-5, 10, 0), rot=(0, 0, 0.18), cubes=[Cube((-0.5, 0, -3), (1, 6, 6), paint=kusazuri)]),
        Part("kusazuri_left", pivot=(5, 10, 0), rot=(0, 0, -0.18), cubes=[Cube((-0.5, 0, -3), (1, 6, 6), paint=kusazuri)]),
    ])
    right_arm = Part("right_arm", cubes=[Cube((-3.5, -2.5, -2.5), (5, 12, 5), paint=kote)], children=[
        Part("sode_right", pivot=(-3.5, -3, 0), rot=(0, 0, 0.18), cubes=[Cube((-1, 0, -3), (1, 7, 6), paint=sode)]),
    ])
    left_arm = Part("left_arm", cubes=[Cube((-1.5, -2.5, -2.5), (5, 12, 5), paint=kote)], children=[
        Part("sode_left", pivot=(3.5, -3, 0), rot=(0, 0, -0.18), cubes=[Cube((0, 0, -3), (1, 7, 6), paint=sode)]),
    ])
    return Model("SamuraiDoModel", "samurai_do", {"body": body, "right_arm": right_arm, "left_arm": left_arm})


# ------------------------------------------------------------- haidate and suneate

def hakama(f):
    """The hakama under the haidate: indigo, pleated, tied in at the shin."""
    for s in range(f.strip_w):
        for t in range(f.h):
            rgb = C[1] if s % 3 == 0 else C[2]
            if t >= f.h - 3:
                rgb = C[0] if t == f.h - 3 else C[1]
            f.side(s, t, f.noise(rgb, 3))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, C[2])
            f.bottom(i, j, C[0])


def waist(f):
    for s in range(f.strip_w):
        for t in range(f.h):
            f.side(s, t, f.noise(C[3] if t == 1 else C[1], 3))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, C[1])
            f.bottom(i, j, C[0])


def haidate_apron(f):
    """Iyo-zane haidate: rows of small lacquered plates laced together, hung from a cloth band."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            dye = False
            if face == "back":
                rgb = C[1]
            elif t == 0:
                rgb = C[3]
            elif t % 2 == 1:
                rgb, dye = lame_edge(s)
            else:
                rgb, dye = lace_cell(s, 0)
            f.side(s, t, f.noise(rgb, 3), dye)
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, C[3])
            f.bottom(i, j, L[1])


def haidate_model():
    def leg(name):
        return Part(name, cubes=[Cube((-2.5, -0.5, -2.5), (5, 13, 5), paint=hakama)], children=[
            Part(name.replace("leg", "haidate"), pivot=(0, 0, -2.6), rot=(-0.12, 0, 0),
                 cubes=[Cube((-3, 0, -1.25), (6, 7, 1), paint=haidate_apron)]),
        ])
    return Model("HaidateModel", "samurai_haidate", {
        "body": Part("body", cubes=[Cube((-4.5, 9.5, -2.5), (9, 3, 5), paint=waist)]),
        "right_leg": leg("right_leg"), "left_leg": leg("left_leg"),
    })


def shin(f):
    """Shino-suneate: lacquered splints over (dyeable) cloth, tied behind the calf."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            dye = False
            if t == 0:
                rgb, dye = D[3], True                                   # the tie at the top (dyeable)
            elif face == "back":
                rgb, dye = (D[2], True) if t in (2, 4) else (C[1] if s % 2 else C[2], False)   # cloth, tied twice
            elif s % 2 == 0:
                rgb = L[4] if t == 1 else L[2]
            else:
                rgb = C[1]
            f.side(s, t, f.noise(rgb, 3), dye)
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, C[2])
            f.bottom(i, j, C[1])


def knee(f):
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            rgb = (G[2] if t == 0 else L[3] if i in (0, f.w - 1) else L[2]) if face == "front" else L[2]
            f.side(s, t, f.noise(rgb, 3))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, G[2])
            f.bottom(i, j, L[1])


def foot(f):
    """Tabi socks in straw waraji sandals."""
    for s in range(f.strip_w):
        for t in range(f.h):
            f.side(s, t, f.noise(W[1] if s % 2 else W[0], 4))
    for i in range(f.w):
        for j in range(f.d):
            rgb = M[2] if (i + j) % 3 else M[1]
            if j == f.d - 1 and i == 2:
                rgb = M[0]
            if j in (f.d - 3, f.d - 2) and i in (2, 3):
                rgb = W[2]
            f.top(i, j, f.noise(rgb, 3))
            f.bottom(i, j, W[0])


def suneate_model():
    def leg(name):
        return Part(name, cubes=[
            Cube((-3, 5.5, -3), (6, 6, 6), paint=shin),
            Cube((-3, 11.5, -3.5), (6, 1, 7), paint=foot),
        ], children=[
            Part(name.replace("leg", "tateage"), pivot=(0, 5.5, -3), rot=(0.15, 0, 0), cubes=[Cube((-2, -1, -0.75), (4, 3, 1), paint=knee)]),
        ])
    return Model("SuneateModel", "samurai_suneate", {"right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


# ------------------------------------------------------------- ashigaru

def dyed_lacquer(edge=True):
    """Clan-coloured lacquer (dyeable), its edges lit."""
    def paint(f):
        for s in range(f.strip_w):
            for t in range(f.h):
                rgb = D[4] if (edge and t == 0) else (D[3] if s % 4 else D[4])
                f.side(s, t, f.noise(rgb, 3), True)
        for i in range(f.w):
            for j in range(f.d):
                f.top(i, j, f.noise(D[2] if (i + j) % 4 else D[3], 3), True)
                f.bottom(i, j, D[1], True)
    return paint


MON = ["..#..",
       ".#.#.",
       "#.#.#",
       ".#.#.",
       "..#.."]   # a gilt hanabishi-like lozenge
MON_SMALL = [".##.",
             "#..#"]   # the same, on the jingasa's front (all that shows between its tiers)


def jingasa_tier(mon_rows=None):
    """A tier of the jingasa: dyed lacquer with bamboo ribs on top, red-lacquered underneath (shu-nuri), the gilt mon
    on the front of the top."""
    def paint(f):
        for s in range(f.strip_w):
            for t in range(f.h):
                f.side(s, t, f.noise(D[4] if t == 0 else D[3], 3), True)
        c = ((f.w - 1) / 2.0, (f.d - 1) / 2.0)
        for i in range(f.w):
            for j in range(f.d):
                ang = math.atan2(j - c[1], i - c[0])
                rib = int((ang + math.pi) / (2 * math.pi) * 24) % 2 == 0
                f.top(i, j, f.noise(D[2] if rib else D[3], 3), True)
                f.bottom(i, j, f.noise(R[2] if (i + j) % 3 else R[1], 3))
        if isinstance(mon_rows, tuple):
            j0, i0 = mon_rows
            for r, row in enumerate(MON_SMALL):
                for k, ch in enumerate(row):
                    if ch == "#":
                        f.top(i0 + k, j0 + r, G[3] if (r + k) % 2 else G[4])
    return paint


def chin_cords(f):
    """Only the hat's cords: down the cheeks and under the chin (the rest of this box is transparent)."""
    for t in range(3, f.h):
        f.face("right", f.d - 3, t, B[1])
        f.face("left", 2, t, B[1])
    for i in range(f.w):
        f.bottom(i, f.d - 3, B[1])


def jingasa_model():
    head = Part("head", cubes=[
        Cube((-7, -8.5, -7), (14, 1, 14), paint=jingasa_tier()),
        Cube((-5, -9.5, -5), (10, 1, 10), paint=jingasa_tier((8, 3))),
        Cube((-3, -10.5, -3), (6, 1, 6), paint=jingasa_tier()),
        Cube((-1, -11.5, -1), (2, 1, 2), paint=gold),
        Cube((-4, -8, -4), (8, 8, 8), inflate=0.3, paint=chin_cords),
    ])
    return Model("JingasaModel", "jingasa", {"head": head})


def okegawa_body(f):
    """Okegawa-do: horizontal plates riveted together (dyed lacquer), steel rivets, the gilt mon on the chest."""
    cx = (f.w - 5) // 2
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face == "front" and 2 <= t < 7 and 0 <= i - cx < 5 and MON[t - 2][i - cx] == "#":
                f.side(s, t, G[3] if (t + i) % 2 else G[4])
                continue
            if t % 2 == 0:
                if s % 5 == 2:
                    f.side(s, t, S[1])
                else:
                    f.side(s, t, f.noise(D[4], 3), True)
            else:
                f.side(s, t, f.noise(D[2] if s % 5 else D[3], 3), True)
    for i in range(f.w):
        for j in range(f.d):
            edge = i in (0, f.w - 1) or j in (0, f.d - 1)
            f.top(i, j, f.noise(D[4] if edge else D[3], 3), True)
            f.bottom(i, j, D[1], True)


def sugake_kusazuri(f):
    """Ashigaru tassets: dyed plates with sparse paired cords (sugake odoshi) in indigo."""
    for s in range(f.strip_w):
        for t in range(f.h):
            if t % 2 == 1 and s % 4 in (1, 2):
                f.side(s, t, C[3] if s % 4 == 1 else C[2])
            else:
                f.side(s, t, f.noise(D[4] if t % 2 == 0 else D[2], 3), True)
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, D[3], True)
            f.bottom(i, j, D[1], True)


def watagami(f):
    """Leather shoulder straps with steel rivets."""
    for s in range(f.strip_w):
        for t in range(f.h):
            f.side(s, t, f.noise(S[3] if (s % 3 == 1 and t == 0) else B[2] if t == 0 else B[1], 3))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, f.noise(B[3] if i in (1, f.w - 2) else B[2], 3))
            f.bottom(i, j, B[0])


def tsutsu_gote(f):
    """Ashigaru forearm guards: dyed lacquer splints on indigo cloth."""
    for s in range(f.strip_w):
        for t in range(f.h):
            if s % 2 == 0 and 0 < t < f.h - 1:
                f.side(s, t, f.noise(D[4] if t == 1 else D[3], 3), True)
            else:
                f.side(s, t, f.noise(C[2] if t in (0, f.h - 1) else C[1], 3))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, C[2])
            f.bottom(i, j, C[1])


def okegawa_do_model():
    def panel(name, pivot, rot, origin, size):
        return Part(name, pivot=pivot, rot=rot, cubes=[Cube(origin, size, paint=sugake_kusazuri)])
    body = Part("body", cubes=[Cube((-5, -1, -3), (10, 11, 6), paint=okegawa_body)], children=[
        panel("kusazuri_front_right", (-2.5, 10, -3), (-0.15, 0, 0), (-2.5, 0, -0.5), (5, 5, 1)),
        panel("kusazuri_front_left", (2.5, 10, -3), (-0.15, 0, 0), (-2.5, 0, -0.5), (5, 5, 1)),
        panel("kusazuri_back_right", (-2.5, 10, 3), (0.15, 0, 0), (-2.5, 0, -0.5), (5, 5, 1)),
        panel("kusazuri_back_left", (2.5, 10, 3), (0.15, 0, 0), (-2.5, 0, -0.5), (5, 5, 1)),
        panel("kusazuri_right", (-5, 10, 0), (0, 0, 0.15), (-0.5, 0, -3), (1, 5, 6)),
        panel("kusazuri_left", (5, 10, 0), (0, 0, -0.15), (-0.5, 0, -3), (1, 5, 6)),
    ])
    right_arm = Part("right_arm", cubes=[Cube((-3.5, -2.5, -2.5), (5, 1, 5), paint=watagami),
                                         Cube((-3.5, 3.5, -2.5), (5, 6, 5), paint=tsutsu_gote)])
    left_arm = Part("left_arm", cubes=[Cube((-1.5, -2.5, -2.5), (5, 1, 5), paint=watagami),
                                       Cube((-1.5, 3.5, -2.5), (5, 6, 5), paint=tsutsu_gote)])
    return Model("OkegawaDoModel", "okegawa_do", {"body": body, "right_arm": right_arm, "left_arm": left_arm})


# ------------------------------------------------------------- 1.1: more of Japan's armour

CLOTH_WHITE = 0xE6E1D3
INDIGO = 0x3A4668
KON = 0x2C3E78          # kon-ito: dark blue silk
MURASAKI = 0x5B3C88     # murasaki-ito: purple silk, for the great armour
KURO = 0x2E2E34         # kuro-ito: black silk
LEATHER = [(232, 214, 176), (214, 190, 150), (186, 160, 118)]   # white-tanned deer leather (egawa)


def dyed_cloth(rows_dark=(), pleats=0, edge_rows=()):
    """Plain dyeable cloth: pleats every few columns, darker rows (ties), lit rows (hems)."""
    def paint(f):
        for s in range(f.strip_w):
            for t in range(f.h):
                g = D[3]
                if pleats and s % pleats == 0:
                    g = D[2]
                if t in rows_dark or (f.h + t) in rows_dark:
                    g = D[1]
                if t in edge_rows:
                    g = D[4]
                f.side(s, t, f.noise(g, 3), True)
        for i in range(f.w):
            for j in range(f.d):
                f.top(i, j, f.noise(D[3], 3), True)
                f.bottom(i, j, D[2], True)
    return paint


# hachigane: a cloth headband with an iron forehead plate

def headband(f):
    for s in range(f.strip_w):
        for t in range(f.h):
            f.side(s, t, f.noise(D[4] if t == 0 else D[3], 2), True)


def forehead_plate(f):
    """A smooth steel plate: lit top edge, shaded bottom, a rivet at each end."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face == "front":
                rgb = L[1] if i in (0, f.w - 1) and t == 1 else S[4] if t == 0 else S[3] if t < f.h - 1 else S[2]
            else:
                rgb = S[1]
            f.side(s, t, f.noise(rgb, 2))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, S[3])
            f.bottom(i, j, S[1])


def hachigane_model():
    head = Part("head", cubes=[Cube((-4, -6.5, -4), (8, 2, 8), inflate=0.3, paint=headband)], children=[
        Part("hachigane_plate", cubes=[Cube((-3, -6.75, -4.9), (6, 2, 1), paint=forehead_plate)]),
        Part("hachimaki_knot", cubes=[Cube((-1, -6.5, 4.2), (2, 2, 1), paint=dyed_cloth())]),
        Part("hachimaki_tail_left", pivot=(0.5, -5, 4.7), rot=(0.3, 0, -0.25), cubes=[Cube((0, 0, -0.5), (1, 3, 1), paint=dyed_cloth())]),
        Part("hachimaki_tail_right", pivot=(-0.5, -5, 4.7), rot=(0.3, 0, 0.25), cubes=[Cube((-1, 0, -0.5), (1, 3, 1), paint=dyed_cloth())]),
    ])
    return Model("HachiganeModel", "ashigaru_hachigane", {"head": head})


# sohei zukin: the warrior monk's hood, wrapped over the head and the lower face

def zukin_hood(f):
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face == "front" and t == 4 and 1 <= i <= f.w - 2:
                continue                                     # the slit for the eyes
            g = D[3]
            if face == "front" and t == 5:
                g = D[2]                                     # the fold over the nose
            elif (s + t) % 5 == 0:
                g = D[2]
            f.side(s, t, f.noise(g, 3), True)
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, f.noise(D[3] if (i + j) % 4 else D[2], 3), True)
            f.bottom(i, j, D[2], True)


def zukin_model():
    head = Part("head", cubes=[Cube((-4, -8, -4), (8, 8, 8), inflate=0.45, paint=zukin_hood)], children=[
        Part("zukin_drape", pivot=(0, -0.5, 4.3), rot=(0.25, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, 4, 1), paint=dyed_cloth(pleats=3))]),
    ])
    return Model("SoheiZukinModel", "sohei_zukin", {"head": head})


# tatami-do: folding armour of small square plates (karuta) on mail

def karuta(f):
    """Small dyed-lacquer plates in staggered rows, joined by steel mail."""
    for s in range(f.strip_w):
        for t in range(f.h):
            row = t // 3
            col = (s + 2 * (row % 2)) % 4
            if t % 3 == 2:
                f.side(s, t, S[0] if s % 2 else L[2])          # mail between the rows
            elif col == 3:
                f.side(s, t, L[1])                             # the seam between plates
            else:
                f.side(s, t, f.noise(D[4] if t % 3 == 0 else D[3], 2), True)
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, f.noise(D[3], 3), True)
            f.bottom(i, j, D[1], True)


def tatami_do_model():
    def panel(name, pivot, rot, origin, size):
        return Part(name, pivot=pivot, rot=rot, cubes=[Cube(origin, size, paint=karuta)])
    body = Part("body", cubes=[Cube((-5, -1, -3), (10, 11, 6), paint=karuta)], children=[
        panel("kusazuri_front_right", (-2.5, 10, -3), (-0.15, 0, 0), (-2.5, 0, -0.5), (5, 4, 1)),
        panel("kusazuri_front_left", (2.5, 10, -3), (-0.15, 0, 0), (-2.5, 0, -0.5), (5, 4, 1)),
        panel("kusazuri_back_right", (-2.5, 10, 3), (0.15, 0, 0), (-2.5, 0, -0.5), (5, 4, 1)),
        panel("kusazuri_back_left", (2.5, 10, 3), (0.15, 0, 0), (-2.5, 0, -0.5), (5, 4, 1)),
    ])
    right_arm = Part("right_arm", cubes=[Cube((-3.5, -2.5, -2.5), (5, 1, 5), paint=watagami)])
    left_arm = Part("left_arm", cubes=[Cube((-1.5, -2.5, -2.5), (5, 1, 5), paint=watagami)])
    return Model("TatamiDoModel", "tatami_do", {"body": body, "right_arm": right_arm, "left_arm": left_arm})


# ashigaru hakama and kyahan

def ashigaru_hakama_model():
    def leg(name):
        return Part(name, cubes=[Cube((-2.5, -0.5, -2.5), (5, 13, 5), paint=dyed_cloth(rows_dark=(10, 11), pleats=3))])
    return Model("AshigaruHakamaModel", "ashigaru_hakama", {
        "body": Part("body", cubes=[Cube((-4.5, 9.5, -2.5), (9, 3, 5), paint=dyed_cloth(rows_dark=(1,)))]),
        "right_leg": leg("right_leg"), "left_leg": leg("left_leg"),
    })


def kyahan(f):
    """Cloth gaiters, tied at the top and twice down the shin."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if t in (1, 3) and face != "back":
                f.side(s, t, M[1] if s % 2 else M[0])           # the white cords
            else:
                f.side(s, t, f.noise(D[4] if t == 0 else D[3], 3), True)
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, D[3], True)
            f.bottom(i, j, D[2], True)


def kyahan_model():
    def leg(name):
        return Part(name, cubes=[
            Cube((-3, 6.5, -3), (6, 5, 6), paint=kyahan),
            Cube((-3, 11.5, -3.5), (6, 1, 7), paint=foot),
        ])
    return Model("KyahanModel", "ashigaru_kyahan", {"right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


# helmets: zunari (plain, head-shaped), hoshi (riveted, of the great armour), mikazuki (the crescent of a commander)

def zunari_bowl(f):
    """A plain bowl of few plates: smooth lacquer, a raised centre ridge, a row of rivets, a lit rim."""
    cx = (f.w - 1) / 2.0
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if t == f.h - 1:
                rgb = L[3]
            elif face in ("front", "back") and abs(i - cx) < 0.6:
                rgb = L[4] if t < 2 else L[3]
            elif t == 1 and s % 3 == 1:
                rgb = L[4]
            else:
                rgb = [L[3], L[2], L[2], L[1]][min(t, 3)]
            f.side(s, t, f.noise(rgb, 3))
    for i in range(f.w):
        for j in range(f.d):
            ridge = abs(i - cx) < 0.6
            f.top(i, j, f.noise(L[4] if ridge else L[3] if (i + j) % 3 == 0 else L[2], 3))
            f.bottom(i, j, L[0])


def hoshi_bowl(f):
    """Hoshi-bachi: a bowl of riveted plates, every plate studded with prominent rivet heads; a gilt rim."""
    for s in range(f.strip_w):
        for t in range(f.h):
            if t == f.h - 1:
                rgb = G[3] if s % 3 == 1 else G[2]
            elif s % 3 == 2:
                rgb = L[0]                                       # the seam between plates
            elif s % 3 == 0 and t % 2 == 0:
                rgb = S[3] if t == 0 else S[2]                   # rivet heads (the "stars")
            else:
                rgb = L[3] if t == 0 else L[2]
            f.side(s, t, f.noise(rgb, 3))
    c = ((f.w - 1) / 2.0, (f.d - 1) / 2.0)
    for i in range(f.w):
        for j in range(f.d):
            ang = math.atan2(j - c[1], i - c[0])
            k = int((ang + math.pi) / (2 * math.pi) * 24)
            rgb = L[0] if k % 3 == 2 else (S[2] if (k % 3 == 0 and (i + j) % 2 == 0) else L[2])
            f.top(i, j, f.noise(rgb, 3))
            f.bottom(i, j, L[0])


def flower(i, t):
    """The stencilled ground: plain white leather, an indigo flower in every other 4x4 cell, staggered."""
    ci, ct = i % 4, t % 4
    on = (i // 4 + t // 4) % 2 == 0
    if on and (ci, ct) == (2, 2):
        return C[1]
    if on and (abs(ci - 2) + abs(ct - 2)) == 1:
        return C[3]
    return LEATHER[0] if (i * 7 + t * 3) % 11 else LEATHER[1]


def stenciled_leather(f, face_only="front"):
    """Egawa: white-tanned leather stencilled in indigo (a lion-and-peony ground, here a repeating flower)."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face_only and face != face_only:
                f.side(s, t, f.noise(L[2], 3))
                continue
            f.side(s, t, f.noise(flower(i, t), 2))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, LEATHER[1])
            f.bottom(i, j, L[1])


def leather_fukigaeshi(f):
    """The great helmet's turn-backs: stencilled leather with a gilt rim."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face == "front":
                if i in (0, f.w - 1) or t in (0, f.h - 1):
                    rgb = G[3] if (i + t) % 2 else G[2]
                else:
                    rgb = C[2] if (i, t) == (f.w // 2, f.h // 2) else LEATHER[0]
            else:
                rgb = L[2]
            f.side(s, t, f.noise(rgb, 3))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, G[2])
            f.bottom(i, j, L[1])


def kabuto_variant(java_name, location, bowl_paint, bowl_h=4, shikoro=(5, 0.55, 11, 8), fuki=(2, 3, fukigaeshi), crest="kuwagata",
                   visor_w=9):
    """A kabuto from the common parts: bowl, crown, visor, shikoro (height, flare, back width, side depth), turn-backs, crest."""
    sh, flare, bw, sd = shikoro
    fw, fh, fpaint = fuki
    top = -5 - bowl_h
    children = [
        Part("mabizashi", pivot=(0, -5, -4.5), rot=(0.3, 0, 0), cubes=[Cube((-visor_w / 2.0, 0, -2), (visor_w, 1, 2), paint=visor)]),
        Part("shikoro_back", pivot=(0, -5, 4.5), rot=(flare, 0, 0), cubes=[Cube((-bw / 2.0, 0, -0.5), (bw, sh, 1), paint=laced_lames(lame=2))]),
        Part("shikoro_right", pivot=(-4.5, -5, 0), rot=(0, 0, flare), cubes=[Cube((-0.5, 0, -3), (1, sh, sd), paint=laced_lames(lame=2))]),
        Part("shikoro_left", pivot=(4.5, -5, 0), rot=(0, 0, -flare), cubes=[Cube((-0.5, 0, -3), (1, sh, sd), paint=laced_lames(lame=2))]),
    ]
    if fw:
        children += [
            Part("fukigaeshi_right", pivot=(-5.2, -5, -2.8), rot=(0, 0.65, 0.3), cubes=[Cube((-fw, 0, -0.5), (fw, fh, 1), paint=fpaint)]),
            Part("fukigaeshi_left", pivot=(5.2, -5, -2.8), rot=(0, -0.65, -0.3), cubes=[Cube((0, 0, -0.5), (fw, fh, 1), paint=fpaint)]),
        ]
    if crest == "kuwagata":
        children += [
            Part("haraidate", pivot=(0, -6.5, -4.6), cubes=[Cube((-1, -1, -1), (2, 2, 1), paint=crest_base)]),
            Part("kuwagata_right", pivot=(-0.6, -7, -5.3), rot=(-0.15, 0, -0.32), cubes=[Cube((-0.5, -6, -0.5), (1, 6, 1), paint=gold)]),
            Part("kuwagata_left", pivot=(0.6, -7, -5.3), rot=(-0.15, 0, 0.32), cubes=[Cube((-0.5, -6, -0.5), (1, 6, 1), paint=gold)]),
        ]
    elif crest == "great_kuwagata":
        children += [
            Part("haraidate", pivot=(0, -6.5, -4.6), cubes=[Cube((-1.5, -1.5, -1), (3, 3, 1), paint=crest_base)]),
            Part("kuwagata_right", pivot=(-1, -7.5, -5.3), rot=(-0.2, 0, -0.42), cubes=[Cube((-0.5, -8, -0.5), (1, 8, 1), paint=gold)]),
            Part("kuwagata_left", pivot=(1, -7.5, -5.3), rot=(-0.2, 0, 0.42), cubes=[Cube((-0.5, -8, -0.5), (1, 8, 1), paint=gold)]),
        ]
    elif crest == "mikazuki":
        def arm(side):
            sgn = -1 if side == "right" else 1
            return Part(f"mikazuki_{side}_1", pivot=(sgn * 0.8, -7, -5.3), rot=(-0.1, 0, sgn * 1.25),
                        cubes=[Cube((-0.5, -4, -0.5), (1, 4, 1), paint=gold)], children=[
                            Part(f"mikazuki_{side}_2", pivot=(0, -4, 0), rot=(0, 0, -sgn * 0.55),
                                 cubes=[Cube((-0.5, -4, -0.5), (1, 4, 1), paint=gold)], children=[
                                     Part(f"mikazuki_{side}_3", pivot=(0, -4, 0), rot=(0, 0, -sgn * 0.5),
                                          cubes=[Cube((-0.5, -3, -0.5), (1, 3, 1), paint=gold)])])])
        children += [Part("haraidate", pivot=(0, -6.5, -4.6), cubes=[Cube((-1, -1, -1), (2, 2, 1), paint=crest_base)]),
                     arm("right"), arm("left")]
    head = Part("head", cubes=[
        Cube((-4.5, top, -4.5), (9, bowl_h, 9), paint=bowl_paint),
        Cube((-3.5, top - 1, -3.5), (7, 1, 7), paint=bowl_cap if bowl_paint is not zunari_bowl else lacquer()),
        Cube((-1, top - 2, -1), (2, 1, 2), paint=gold),
    ], children=children)
    return Model(java_name, location, {"head": head})


def zunari_kabuto_model():
    return kabuto_variant("ZunariKabutoModel", "zunari_kabuto", zunari_bowl, shikoro=(4, 0.35, 10, 7), fuki=(2, 2, fukigaeshi), crest=None)


def hoshi_kabuto_model():
    return kabuto_variant("HoshiKabutoModel", "hoshi_kabuto", hoshi_bowl, bowl_h=5, shikoro=(6, 0.85, 13, 9),
                          fuki=(3, 4, leather_fukigaeshi), crest="great_kuwagata", visor_w=11)


def mikazuki_kabuto_model():
    return kabuto_variant("MikazukiKabutoModel", "mikazuki_kabuto", bowl, crest="mikazuki")


# do-maru: lighter wrap-round lamellar of the mid-ranking samurai, white-laced at the shoulders (kata-jiro)

def katajiro(plate_rows=0, white_lames=1, lame=2):
    """Laced lames whose upper lames are laced in white (kata-jiro odoshi), the rest in the (dyeable) silk."""
    def paint(f):
        for s in range(f.strip_w):
            for t in range(f.h):
                if t < plate_rows:
                    rgb, dye = ((L[4] if s % 2 == 0 else L[3]) if t == 0 else L[2]), False
                elif t == f.h - 1:
                    rgb, dye = hishinui(s)
                else:
                    r = (t - plate_rows) % lame
                    lame_no = (t - plate_rows) // lame
                    if r == 0:
                        rgb, dye = lame_edge(s)
                    elif lame_no < white_lames:
                        rgb, dye = (WHITE_CORD if s % 3 != 2 else M[0]), False
                    else:
                        rgb, dye = lace_cell(s, r - 1)
                f.side(s, t, f.noise(rgb, 3), dye)
        for i in range(f.w):
            for j in range(f.d):
                f.top(i, j, f.noise(L[3] if j in (0, f.d - 1) else L[2], 3))
                f.bottom(i, j, L[1])
    return paint


def do_maru_model():
    body = Part("body", cubes=[Cube((-5, -1, -3), (10, 11, 6), paint=katajiro(plate_rows=1, white_lames=2))], children=[
        Part("kusazuri_front_right", pivot=(-2.5, 10, -3), rot=(-0.15, 0, 0), cubes=[Cube((-2.5, 0, -0.5), (5, 5, 1), paint=kusazuri)]),
        Part("kusazuri_front_left", pivot=(2.5, 10, -3), rot=(-0.15, 0, 0), cubes=[Cube((-2.5, 0, -0.5), (5, 5, 1), paint=kusazuri)]),
        Part("kusazuri_back_right", pivot=(-2.5, 10, 3), rot=(0.15, 0, 0), cubes=[Cube((-2.5, 0, -0.5), (5, 5, 1), paint=kusazuri)]),
        Part("kusazuri_back_left", pivot=(2.5, 10, 3), rot=(0.15, 0, 0), cubes=[Cube((-2.5, 0, -0.5), (5, 5, 1), paint=kusazuri)]),
        Part("kusazuri_right", pivot=(-5, 10, 0), rot=(0, 0, 0.15), cubes=[Cube((-0.5, 0, -3), (1, 5, 6), paint=kusazuri)]),
        Part("kusazuri_left", pivot=(5, 10, 0), rot=(0, 0, -0.15), cubes=[Cube((-0.5, 0, -3), (1, 5, 6), paint=kusazuri)]),
    ])
    right_arm = Part("right_arm", cubes=[Cube((-3.5, -2.5, -2.5), (5, 12, 5), paint=kote)], children=[
        Part("sode_right", pivot=(-3.5, -3, 0), rot=(0, 0, 0.14), cubes=[Cube((-1, 0, -2.5), (1, 5, 5), paint=katajiro(plate_rows=1))]),
    ])
    left_arm = Part("left_arm", cubes=[Cube((-1.5, -2.5, -2.5), (5, 12, 5), paint=kote)], children=[
        Part("sode_left", pivot=(3.5, -3, 0), rot=(0, 0, -0.14), cubes=[Cube((0, 0, -2.5), (1, 5, 5), paint=katajiro(plate_rows=1))]),
    ])
    return Model("DoMaruModel", "do_maru", {"body": body, "right_arm": right_arm, "left_arm": left_arm})


# o-yoroi: the great armour of the mounted archer

def o_yoroi_body(f):
    """The box-like do: a stencilled leather panel (tsurubashiri) over the front, wide laced lames elsewhere."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            dye = False
            if face == "front" and t < f.h - 3 and 1 <= i <= f.w - 2:
                rgb = flower(i, t)
            elif face == "front" and t < f.h - 3:
                rgb = G[2] if t % 2 else G[3]                    # gilt edging of the leather
            elif t == f.h - 1:
                rgb, dye = hishinui(s)
            elif t == 0:
                rgb = L[4] if s % 2 == 0 else L[3]
            else:
                r = (t - 1) % 3
                rgb, dye = lame_edge(s) if r == 0 else lace_cell(s, r - 1)
            f.side(s, t, f.noise(rgb, 3), dye)
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, f.noise(LEATHER[1] if j in (0, f.d - 1) else L[2], 3))
            f.bottom(i, j, L[1])


def kyubi_plate(f):
    """Kyubi-no-ita: the solid plate over the left breast, leather-covered with a gilt rim."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face == "front":
                rim = i in (0, f.w - 1) or t in (0, f.h - 1)
                rgb = G[3] if rim else (C[2] if (i + t) % 3 == 0 else LEATHER[1])
            else:
                rgb = L[2]
            f.side(s, t, f.noise(rgb, 3))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, G[2])
            f.bottom(i, j, L[1])


def o_sode(f):
    """O-sode: the great shoulder boards, a leather-covered top plate then wide laced lames."""
    for s in range(f.strip_w):
        for t in range(f.h):
            dye = False
            if t == 0:
                rgb = G[3] if s % 2 else G[2]
            elif t == 1:
                rgb = LEATHER[0] if s % 3 else C[2]
            elif t == f.h - 1:
                rgb, dye = hishinui(s)
            else:
                r = (t - 2) % 3
                rgb, dye = lame_edge(s) if r == 0 else lace_cell(s, r - 1)
            f.side(s, t, f.noise(rgb, 3), dye)
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, G[2])
            f.bottom(i, j, L[1])


def hitatare_sleeve(f):
    """The bare right sleeve of the archer: a brocade hitatare (the left arm wears the kote)."""
    for s in range(f.strip_w):
        for t in range(f.h):
            motif = (s % 4 == 1 and t % 4 == 1) or (s % 4 == 3 and t % 4 == 3)
            rgb = G[2] if motif else (C[2] if (s + t) % 2 else C[1])
            if t == f.h - 1:
                rgb = C[3]
            f.side(s, t, f.noise(rgb, 3))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, C[2])
            f.bottom(i, j, C[1])


def o_yoroi_model():
    def skirt(name, pivot, rot, origin, size):
        return Part(name, pivot=pivot, rot=rot, cubes=[Cube(origin, size, paint=laced_lames(lame=3))])
    body = Part("body", cubes=[Cube((-5, -1, -3), (10, 11, 6), paint=o_yoroi_body)], children=[
        Part("sendan_no_ita", pivot=(-3.5, -0.5, -3.3), rot=(-0.05, 0, 0), cubes=[Cube((-1.5, 0, -0.5), (3, 5, 1), paint=laced_lames(lame=2))]),
        Part("kyubi_no_ita", pivot=(3.5, -0.5, -3.3), rot=(-0.05, 0, 0), cubes=[Cube((-1.5, 0, -0.5), (3, 5, 1), paint=kyubi_plate)]),
        skirt("kusazuri_front", (0, 10, -3.2), (-0.12, 0, 0), (-5, 0, -0.5), (10, 7, 1)),
        skirt("kusazuri_back", (0, 10, 3.2), (0.12, 0, 0), (-5, 0, -0.5), (10, 7, 1)),
        skirt("kusazuri_right", (-5.2, 10, 0), (0, 0, 0.12), (-0.5, 0, -3), (1, 7, 6)),
        skirt("kusazuri_left", (5.2, 10, 0), (0, 0, -0.12), (-0.5, 0, -3), (1, 7, 6)),
    ])
    right_arm = Part("right_arm", cubes=[Cube((-3.5, -2.5, -2.5), (5, 12, 5), paint=hitatare_sleeve)], children=[
        Part("o_sode_right", pivot=(-3.6, -3.5, 0), rot=(0, 0, 0.25), cubes=[Cube((-1, 0, -4), (1, 9, 8), paint=o_sode)]),
    ])
    left_arm = Part("left_arm", cubes=[Cube((-1.5, -2.5, -2.5), (5, 12, 5), paint=kote)], children=[
        Part("o_sode_left", pivot=(3.6, -3.5, 0), rot=(0, 0, -0.25), cubes=[Cube((0, 0, -4), (1, 9, 8), paint=o_sode)]),
    ])
    return Model("OYoroiModel", "o_yoroi", {"body": body, "right_arm": right_arm, "left_arm": left_arm}, tex=(128, 64))


# ---------------------------------------------------------------------- the items

SCARLET = 0xB0302A   # hi-odoshi: scarlet silk lacing
LACQUER_BLACK = 0x34343C

# id, model factory, slot, (toughness, knockback, durability, defense, enchantability), default dye, sound
ARMOURS = [
    ("kabuto", lambda: kabuto_model(False), "HELMET", (1.0, 0.0, 260, 3, 10), SCARLET, "ARMOR_EQUIP_IRON"),
    ("menpo_kabuto", lambda: kabuto_model(True), "HELMET", (1.25, 0.0, 280, 3, 10), SCARLET, "ARMOR_EQUIP_IRON"),
    ("samurai_do", samurai_do_model, "CHESTPLATE", (1.0, 0.0, 320, 7, 10), SCARLET, "ARMOR_EQUIP_CHAIN"),
    ("samurai_haidate", haidate_model, "LEGGINGS", (1.0, 0.0, 280, 5, 10), SCARLET, "ARMOR_EQUIP_CHAIN"),
    ("samurai_suneate", suneate_model, "BOOTS", (1.0, 0.0, 210, 2, 10), SCARLET, "ARMOR_EQUIP_IRON"),
    ("jingasa", jingasa_model, "HELMET", (0.25, 0.0, 190, 2, 9), LACQUER_BLACK, "ARMOR_EQUIP_IRON"),
    ("okegawa_do", okegawa_do_model, "CHESTPLATE", (0.25, 0.0, 250, 5, 9), LACQUER_BLACK, "ARMOR_EQUIP_IRON"),
    # 1.1: ashigaru and militia
    ("ashigaru_hachigane", hachigane_model, "HELMET", (0.0, 0.0, 120, 1, 8), CLOTH_WHITE, "ARMOR_EQUIP_LEATHER"),
    ("tatami_do", tatami_do_model, "CHESTPLATE", (0.0, 0.0, 200, 4, 9), LACQUER_BLACK, "ARMOR_EQUIP_CHAIN"),
    ("ashigaru_hakama", ashigaru_hakama_model, "LEGGINGS", (0.0, 0.0, 120, 2, 9), INDIGO, "ARMOR_EQUIP_LEATHER"),
    ("ashigaru_kyahan", kyahan_model, "BOOTS", (0.0, 0.0, 110, 1, 9), INDIGO, "ARMOR_EQUIP_LEATHER"),
    # 1.1: samurai of middle rank, light cavalry and archers
    ("zunari_kabuto", zunari_kabuto_model, "HELMET", (0.5, 0.0, 230, 2, 9), KON, "ARMOR_EQUIP_IRON"),
    ("do_maru", do_maru_model, "CHESTPLATE", (0.5, 0.0, 290, 6, 10), KON, "ARMOR_EQUIP_CHAIN"),
    # 1.1: the great armour of the mounted archer (heavy cavalry)
    ("hoshi_kabuto", hoshi_kabuto_model, "HELMET", (1.0, 0.0, 270, 3, 10), MURASAKI, "ARMOR_EQUIP_IRON"),
    ("o_yoroi", o_yoroi_model, "CHESTPLATE", (1.25, 0.25, 340, 8, 10), MURASAKI, "ARMOR_EQUIP_CHAIN"),
    # 1.1: a commander's helmet, and the warrior monk's hood
    ("mikazuki_kabuto", mikazuki_kabuto_model, "HELMET", (1.25, 0.0, 280, 3, 12), KURO, "ARMOR_EQUIP_IRON"),
    ("sohei_zukin", zukin_model, "HELMET", (0.0, 0.0, 100, 1, 9), CLOTH_WHITE, "ARMOR_EQUIP_LEATHER"),
]
