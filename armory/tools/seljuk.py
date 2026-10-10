"""
The Seljuk armours of the Cultures Armory (the Great Seljuks and the Sultanate of Rum, 11th-13th centuries), after the
helmets, kazaghands and jawshans of the ghulams and the felt and kaftans of the Turkmen horse archers.

Helmets: tall conical helmets with mail aventails (one with a mail veil over the face, one gilded for an atabeg, one
wound with a turban), a segmented helmet with cheek guards (Daylamite foot), the Turkmen felt bork with a fur brim, the
plain turban. Coats: the kazaghand (mail between layers of cloth, the outer one dyed, with gilt tiraz bands on the
sleeves), the jawshan (a lamellar cuirass over a kaftan) and its gilded form with a breast disc, a mail shirt over a long
kaftan, the quilted kaftan, the plain kaftan. Legs: shalwar (baggy trousers), lamellar thigh guards, mail leggings.
Feet: Turkic riding boots, splinted greaves. The cloth (and the boots' leather) is dyeable.
"""
from armorgen import Cube, Model, Part
from japan import B, D, G, L, M, S, gold

MAIL = [(46, 46, 50), (70, 70, 74), (98, 98, 102), (128, 128, 132), (156, 156, 160)]
STEEL = [(84, 86, 88), (116, 118, 120), (150, 152, 154), (184, 186, 188), (214, 216, 218)]
FUR = [(48, 34, 22), (74, 54, 36), (102, 78, 54), (132, 104, 74)]
RED_LEATHER = [(70, 26, 20), (102, 40, 30), (134, 58, 42), (162, 80, 60)]


def faces_fill(f, side_fn, top_fn=None, bottom_fn=None, noise=3):
    """side_fn(face, i, t, s) -> (rgb, dye) for every side pixel; top/bottom_fn(i, j) -> (rgb, dye)."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            px = side_fn(face, i, t, s)
            if px is None:
                continue
            rgb, dye = px
            f.side(s, t, f.noise(rgb, noise) if noise else rgb, dye)
    for i in range(f.w):
        for j in range(f.d):
            if top_fn:
                px = top_fn(i, j)
                if px:
                    f.top(i, j, f.noise(px[0], noise), px[1])
            if bottom_fn:
                px = bottom_fn(i, j)
                if px:
                    f.bottom(i, j, px[0], px[1])


# ---------------------------------------------------------------------- painters

def mail_px(s, t):
    """Riveted mail as Epic Knights draws it: a fine checker of rings, every other row a shade darker."""
    lit = (s + t) % 2 == 0
    return (MAIL[3] if lit else MAIL[1]) if t % 2 == 0 else (MAIL[2] if lit else MAIL[0])


def mail(trim_rows=0):
    """Mail; the bottom rows (if any) a dyed silk edging."""
    def paint(f):
        faces_fill(f, lambda face, i, t, s: (D[3], True) if t >= f.h - trim_rows else (mail_px(s, t), False),
                   lambda i, j: (mail_px(i, j), False), lambda i, j: (MAIL[0], False), noise=2)
    return paint


def lamella_px(s, t, gilt=False, row=3):
    """Steel lamellae: narrow vertical plates in rows, each row laced to the next with leather (a dark lacing row)."""
    r = t % row
    if r == 0:
        return (B[1] if s % 2 == 0 else L[1]), False
    plate = s % 2 == 0
    if gilt and (t // row) % 2 == 1:
        return (G[3] if r == 1 else G[2]) if plate else G[1], False
    return (STEEL[3] if r == 1 else STEEL[2]) if plate else STEEL[0], False


def lamellar(gilt=False, row=3):
    def paint(f):
        faces_fill(f, lambda face, i, t, s: lamella_px(s, t, gilt, row), lambda i, j: (STEEL[2], False),
                   lambda i, j: (STEEL[0], False), noise=2)
    return paint


def cloth(pleats=0, dark_rows=(), tiraz_rows=(), quilt=0, edge_rows=()):
    """Dyed cloth: optional pleats, darker rows (belts, gathers), gilt tiraz bands (inscribed embroidery) and quilting."""
    def paint(f):
        def side(face, i, t, s):
            if t in tiraz_rows or (t - f.h) in tiraz_rows:
                return (G[3] if s % 3 else G[1]), False
            g = D[3]
            if pleats and s % pleats == 0:
                g = D[2]
            if quilt and (s % quilt == 0 or t % quilt == 0):
                g = D[2]
            if t in dark_rows or (t - f.h) in dark_rows:
                g = D[1]
            if t in edge_rows or (t - f.h) in edge_rows:
                g = D[4]
            return g, True
        faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: (D[2], True))
    return paint


def steel_bowl(bands=False, gilt_rim=True, rivets=True):
    """A forged steel bowl: lit at the top, a gilt (brass) rim at the bottom, riveted vertical bands if segmented."""
    def side(face, i, t, s):
        if gilt_rim and t == f_h[0] - 1:
            return (G[3] if s % 3 else G[2]), False
        if bands and s % 4 == 0:
            return (G[2] if t % 2 else G[3]) if bands == "gilt" else (STEEL[1] if t % 2 else STEEL[4]), False
        if rivets and t == f_h[0] - 2 and s % 3 == 1:
            return STEEL[4], False
        return [STEEL[3], STEEL[2], STEEL[2], STEEL[1]][min(t, 3)], False
    f_h = [0]

    def paint(f):
        f_h[0] = f.h
        faces_fill(f, side, lambda i, j: (STEEL[4] if (i + j) % 3 == 0 else STEEL[3], False), lambda i, j: (STEEL[0], False))
    return paint


def gilt_steel(f):
    """Gilded steel: alternating gilt and steel (an atabeg's helmet)."""
    faces_fill(f, lambda face, i, t, s: ((G[3] if t == 0 else G[2]) if s % 3 else STEEL[3], False),
               lambda i, j: (G[3], False), lambda i, j: (G[1], False))


def nasal(f):
    faces_fill(f, lambda face, i, t, s: (STEEL[3] if face == "front" else STEEL[1], False), lambda i, j: (STEEL[3], False),
               lambda i, j: (STEEL[1], False))


def plume(f):
    """A dyed horse-hair plume (the dyeable crest of a helmet)."""
    faces_fill(f, lambda face, i, t, s: (D[4] if t == 0 else D[3] if s % 2 else D[2], True), lambda i, j: (D[4], True),
               lambda i, j: (D[1], True))


def turban_wrap(f):
    """A turban: dyed cloth wound round in diagonal folds, each fold lit on top and shadowed below."""
    def side(face, i, t, s):
        fold = (s + t * 2) % 6
        return (D[4] if fold in (0, 1) else D[1] if fold == 5 else D[2] if fold == 4 else D[3]), True
    faces_fill(f, side, lambda i, j: (D[4] if (i + 2 * j) % 6 < 2 else D[2] if (i + 2 * j) % 6 == 5 else D[3], True),
               lambda i, j: (D[2], True))


def red_cap(f):
    """The red felt cap (taj) that shows in the top of a turban."""
    faces_fill(f, lambda face, i, t, s: (RED_LEATHER[3] if t == 0 else RED_LEATHER[2], False),
               lambda i, j: (RED_LEATHER[3] if (i + j) % 3 else RED_LEATHER[2], False), lambda i, j: (RED_LEATHER[1], False))


def felt(f):
    """The bork's felt: dyed, softly mottled, a seam up the front."""
    faces_fill(f, lambda face, i, t, s: ((D[2] if face == "front" and i == f.w // 2 else D[4] if t == 0 else D[3]), True),
               lambda i, j: (D[3], True), lambda i, j: (D[1], True), noise=5)


def fur_brim(f):
    """An upturned fur brim: dark sable, the tips of the hairs lighter."""
    faces_fill(f, lambda face, i, t, s: (FUR[3] if t == 0 and s % 2 else FUR[2] if (s + t) % 3 == 0 else FUR[1], False),
               lambda i, j: (FUR[2] if (i * 3 + j) % 4 else FUR[3], False), lambda i, j: (FUR[0], False), noise=6)


def leather_belt(f):
    """A leather belt with gilt plaques (the Turkic belt of rank)."""
    faces_fill(f, lambda face, i, t, s: ((G[3] if s % 3 == 1 else B[1]) if t == 0 else B[0], False),
               lambda i, j: (B[1], False), lambda i, j: (B[0], False))


# ---------------------------------------------------------------------- helmets

def aventail(head_children, front=False, back_h=6, side_h=5, trim=1):
    """The mail aventail hung from the rim round the sides and back; with front=True a mail veil over the face too."""
    head_children += [
        Part("aventail_back", pivot=(0, -5, 4.6), rot=(0.18, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, back_h, 1), paint=mail(trim))]),
        Part("aventail_right", pivot=(-4.6, -5, 0), rot=(0, 0, 0.12), cubes=[Cube((-0.5, 0, -3.5), (1, side_h, 8), paint=mail(trim))]),
        Part("aventail_left", pivot=(4.6, -5, 0), rot=(0, 0, -0.12), cubes=[Cube((-0.5, 0, -3.5), (1, side_h, 8), paint=mail(trim))]),
    ]
    if front:
        head_children.append(Part("mail_veil", pivot=(0, -4.5, -4.7), rot=(-0.06, 0, 0),
                                  cubes=[Cube((-4.5, 0, -0.5), (9, 5, 1), paint=mail(trim))]))
    return head_children


def conical_helmet_model(java_name, location, kind="plain"):
    """A tall Turkic conical helmet in tiers to a point, a gilt rim, a nasal, a mail aventail."""
    bowl = gilt_steel if kind == "gilded" else steel_bowl(gilt_rim=False, rivets=False)
    cubes = [
        Cube((-4.5, -9, -4.5), (9, 3, 9), paint=steel_bowl(rivets=True)),
        Cube((-3.5, -11, -3.5), (7, 2, 7), paint=bowl),
        Cube((-2.5, -13, -2.5), (5, 2, 5), paint=bowl),
        Cube((-1.5, -14, -1.5), (3, 1, 3), paint=bowl),
        Cube((-0.5, -16, -0.5), (1, 2, 1), paint=gold if kind == "gilded" else steel_bowl(gilt_rim=False, rivets=False)),
    ]
    children = [Part("nasal", cubes=[Cube((-0.5, -6.5, -5.2), (1, 3, 1), paint=nasal)])]
    aventail(children, front=(kind == "veil"))
    if kind == "gilded":
        children.append(Part("plume", pivot=(0, -15, 0), rot=(0.5, 0, 0), cubes=[Cube((-0.5, -1, -0.5), (1, 1, 4), paint=plume)]))
    if kind == "turban":
        cubes = [Cube((-5, -9.5, -5), (10, 3, 10), paint=turban_wrap)] + [c for c in cubes[1:]]
        children = [ch for ch in children if ch.name not in ("aventail_right", "aventail_left")]
    return Model(java_name, location, {"head": Part("head", cubes=cubes, children=children)})


def spangenhelm_model():
    """A segmented helmet of riveted plates, rounded, with cheek guards and a mail neck guard (Daylamite infantry)."""
    head = Part("head", cubes=[
        Cube((-4.5, -9, -4.5), (9, 4, 9), paint=steel_bowl(bands=True)),
        Cube((-3.5, -10, -3.5), (7, 1, 7), paint=steel_bowl(bands=True, gilt_rim=False)),
        Cube((-1.5, -11, -1.5), (3, 1, 3), paint=gold),
    ], children=[
        Part("cheek_right", pivot=(-4.6, -5, -2), rot=(0, 0.2, 0.08), cubes=[Cube((-0.5, 0, -2.5), (1, 4, 4), paint=steel_bowl(rivets=False))]),
        Part("cheek_left", pivot=(4.6, -5, -2), rot=(0, -0.2, -0.08), cubes=[Cube((-0.5, 0, -2.5), (1, 4, 4), paint=steel_bowl(rivets=False))]),
        Part("aventail_back", pivot=(0, -5, 4.6), rot=(0.18, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, 5, 1), paint=mail(1))]),
    ])
    return Model("SeljukSpangenhelmModel", "seljuk_spangenhelm", {"head": head})


def bork_model():
    """The Turkmen bork: a tall felt cap with an upturned fur brim."""
    head = Part("head", cubes=[
        Cube((-5, -8.5, -5), (10, 2, 10), paint=fur_brim),
        Cube((-4, -11.5, -4), (8, 3, 8), paint=felt),
        Cube((-3, -13.5, -3), (6, 2, 6), paint=felt),
        Cube((-2, -14.5, -2), (4, 1, 4), paint=felt),
    ])
    return Model("BorkModel", "bork", {"head": head})


def turban_model():
    """A cloth turban over a small cap, its tail hanging down the back."""
    head = Part("head", cubes=[
        Cube((-4.5, -9.5, -4.5), (9, 4, 9), paint=turban_wrap),
        Cube((-3.5, -10.5, -3.5), (7, 1, 7), paint=turban_wrap),
        Cube((-2, -11.5, -2), (4, 1, 4), paint=red_cap),
    ], children=[
        Part("turban_tail", pivot=(1.5, -6, 4.6), rot=(0.12, 0, -0.05), cubes=[Cube((-1, 0, -0.5), (2, 6, 1), paint=cloth(pleats=2))]),
    ])
    return Model("TurbanModel", "turban", {"head": head})


# ---------------------------------------------------------------------- coats

def arms(paint_upper, paint_lower=None, split=5, cap=None):
    """The two sleeves: one box (or upper and lower boxes), and a shoulder piece if given."""
    out = {}
    for name, x in (("right_arm", -3.5), ("left_arm", -1.5)):
        if paint_lower:
            cubes = [Cube((x, -2.5, -2.5), (5, split, 5), paint=paint_upper),
                     Cube((x, -2.5 + split, -2.5), (5, 12 - split, 5), paint=paint_lower)]
        else:
            cubes = [Cube((x, -2.5, -2.5), (5, 12, 5), paint=paint_upper)]
        children = []
        if cap:
            sgn = -1 if name == "right_arm" else 1
            children.append(Part(name.replace("arm", "pauldron"), pivot=(sgn * 1.0, -3.2, 0), rot=(0, 0, sgn * -0.2),
                                 cubes=[Cube((-3, 0, -3), (6, 4, 6), paint=cap)]))
        out[name] = Part(name, cubes=cubes, children=children)
    return out


def skirt(paint, length=6, flare=0.15):
    """Split skirts, front and back, of a riding coat (and the sides)."""
    return [
        Part("skirt_front", pivot=(0, 10, -3), rot=(-flare, 0, 0), cubes=[Cube((-5, 0, -0.5), (10, length, 1), paint=paint)]),
        Part("skirt_back", pivot=(0, 10, 3), rot=(flare, 0, 0), cubes=[Cube((-5, 0, -0.5), (10, length, 1), paint=paint)]),
        Part("skirt_right", pivot=(-5, 10, 0), rot=(0, 0, flare), cubes=[Cube((-0.5, 0, -3), (1, length, 6), paint=paint)]),
        Part("skirt_left", pivot=(5, 10, 0), rot=(0, 0, -flare), cubes=[Cube((-0.5, 0, -3), (1, length, 6), paint=paint)]),
    ]


def coat_model(java_name, location, body_paint, arm_parts, skirt_paint=None, skirt_len=6, extra=(), tex=(64, 64)):
    body = Part("body", cubes=[Cube((-5, -1, -3), (10, 11, 6), paint=body_paint)],
                children=(skirt(skirt_paint, skirt_len) if skirt_paint else []) + list(extra))
    return Model(java_name, location, dict(arm_parts, body=body), tex=tex)


def kazaghand_body(f):
    """Kazaghand: the outer cloth (dyed, quilted in vertical lines), a slit collar, a belt with gilt plaques, mail at the neck."""
    cx = f.w // 2

    def side(face, i, t, s):
        if t == 0:
            return mail_px(s, t), False
        if t == 8:
            return (G[3] if s % 3 == 1 else B[1]), False
        if face == "front" and i == cx and t < 8:
            return D[1], True
        return (D[2] if s % 2 == 0 else D[3]), True
    faces_fill(f, side, lambda i, j: (mail_px(i, j), False), lambda i, j: (D[1], True))


def kazaghand_skirt(f):
    """The kazaghand's skirt: dyed, quilted, the mail lining showing at the hem."""
    faces_fill(f, lambda face, i, t, s: (mail_px(s, t), False) if t == f.h - 1 else ((D[2] if s % 2 == 0 else D[3]), True),
               lambda i, j: (D[3], True), lambda i, j: (MAIL[1], False))


def kazaghand_sleeve(f):
    """Quilted sleeve with a gilt tiraz band on the upper arm, mail at the cuff."""
    def side(face, i, t, s):
        if t in (2, 3):
            return (G[3] if (s + t) % 3 else G[1]), False
        if t >= f.h - 2:
            return mail_px(s, t), False
        return (D[2] if s % 2 == 0 else D[3]), True
    faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: (MAIL[1], False))


def jawshan_body(gilt=False):
    """Jawshan: the lamellar cuirass; the dyed kaftan at the collar; the gilded one has a round gilt breast disc."""
    def paint(f):
        cx, cy = (f.w - 1) / 2.0, 4.0

        def side(face, i, t, s):
            if t == 0:
                return D[3], True
            if gilt and face == "front" and (i - cx) ** 2 + (t - cy) ** 2 <= 5.5:
                d = (i - cx) ** 2 + (t - cy) ** 2
                return (G[1] if d > 3.5 else G[4] if (i < cx and t < cy) else G[3]), False
            if t == 9:
                return (G[3] if s % 3 == 1 else B[1]), False
            return lamella_px(s, t - 1, gilt)
        faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: (STEEL[0], False), noise=2)
    return paint


def jawshan_model(gilt=False):
    sleeve = cloth(pleats=2, edge_rows=(-1,)) if not gilt else mail()
    return coat_model("GildedJawshanModel" if gilt else "JawshanModel", "gilded_jawshan" if gilt else "jawshan",
                      jawshan_body(gilt), arms(sleeve, cap=lamellar(gilt)),
                      skirt_paint=lamellar(gilt), skirt_len=5)


def mail_shirt_body(f):
    """A mail shirt (zirh) to the hips over the kaftan, the kaftan's dyed collar showing."""
    faces_fill(f, lambda face, i, t, s: (D[3], True) if t == 0 else ((G[3] if s % 3 == 1 else B[1]), False) if t == 8
               else (mail_px(s, t), False), lambda i, j: (D[3], True), lambda i, j: (MAIL[0], False), noise=2)


def quilted_body(f):
    """The quilted kaftan: crossed over to the wearer's right, diamond-quilted, belted."""
    cx = f.w // 2

    def side(face, i, t, s):
        if t == 8:
            return (B[2] if s % 4 else G[2]), False
        if face == "front" and i == cx + 2 - t // 3:
            return D[4], True                                     # the crossed-over edge
        return (D[2] if (s + t) % 3 == 0 or (s - t) % 3 == 0 else D[3]), True
    faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: (D[2], True))


def kaftan_body(f):
    """A kaftan: crossed over, a contrasting (lighter) border on the edge, a sash."""
    cx = f.w // 2

    def side(face, i, t, s):
        if t in (8, 9):
            return D[1], True
        if face == "front" and i == cx + 2 - t // 3:
            return G[2], False
        return (D[3] if s % 3 else D[2]), True
    faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: (D[2], True))


# ---------------------------------------------------------------------- legs and feet

def shalwar_leg(f):
    """Baggy trousers, gathered (darker) at the ankle."""
    faces_fill(f, lambda face, i, t, s: ((D[1] if t >= f.h - 2 else D[2] if s % 3 == 0 else D[3]), True),
               lambda i, j: (D[3], True), lambda i, j: (D[1], True))


def sash(f):
    faces_fill(f, lambda face, i, t, s: ((D[4] if t == 1 else D[2]), True), lambda i, j: (D[2], True), lambda i, j: (D[1], True))


def legs_model(java_name, location, leg_paint, front=None, front_h=7, waist=sash):
    def leg(name):
        children = []
        if front:
            children.append(Part(name.replace("leg", "guard"), pivot=(0, 0, -2.6), rot=(-0.1, 0, 0),
                                 cubes=[Cube((-3, 0, -1.25), (6, front_h, 1), paint=front)]))
        return Part(name, cubes=[Cube((-2.5, -0.5, -2.5), (5, 13, 5), paint=leg_paint)], children=children)
    return Model(java_name, location, {"body": Part("body", cubes=[Cube((-4.5, 9.5, -2.5), (9, 3, 5), paint=waist)]),
                                       "right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


def boot_leg(f):
    """Tall soft riding boots of dyed leather, a darker band at the top."""
    faces_fill(f, lambda face, i, t, s: ((D[2] if t == 0 else D[3] if s % 4 else D[4]), True),
               lambda i, j: (D[2], True), lambda i, j: (D[1], True))


def boot_foot(f):
    faces_fill(f, lambda face, i, t, s: (D[2], True), lambda i, j: (D[3] if j < f.d - 1 else D[4], True),
               lambda i, j: (B[0], False))


def greave(f):
    """Splinted greaves: vertical steel splints riveted to straps, over the boot."""
    def side(face, i, t, s):
        if face == "back":
            return (D[2], True)
        if t in (0, f.h - 1):
            return (B[1] if s % 3 else STEEL[4]), False
        return (STEEL[3] if s % 2 == 0 else STEEL[1]), False
    faces_fill(f, side, lambda i, j: (STEEL[2], False), lambda i, j: (STEEL[0], False), noise=2)


def knee_cop(f):
    faces_fill(f, lambda face, i, t, s: ((STEEL[4] if t == 0 else STEEL[2]) if face == "front" else STEEL[1], False),
               lambda i, j: (STEEL[3], False), lambda i, j: (STEEL[0], False))


def boots_model(java_name, location, splints=False):
    def leg(name):
        cubes = [Cube((-3, 4.5, -3), (6, 7, 6), paint=greave if splints else boot_leg),
                 Cube((-3, 11.5, -3.5), (6, 1, 7), paint=boot_foot)]
        children = [Part(name.replace("leg", "toe"), cubes=[Cube((-1, 11.5, -4.5), (2, 1, 1), paint=boot_foot)])]
        if splints:
            children.append(Part(name.replace("leg", "knee"), pivot=(0, 4.5, -3), rot=(0.15, 0, 0),
                                 cubes=[Cube((-2, -1, -0.75), (4, 3, 1), paint=knee_cop)]))
        return Part(name, cubes=cubes, children=children)
    return Model(java_name, location, {"right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


# ---------------------------------------------------------------------- the items

CRIMSON = 0x8E2A2A
TURQUOISE = 0x2E7D7A
WHITE = 0xE8E4D8
INDIGO_BLUE = 0x2F3E6E
FELT_RED = 0xA0402E
SAFFRON = 0xC08A2E
GREEN = 0x2F6B3A
RED_BOOT = 0x8A2C24

ARMOURS = [
    ("seljuk_conical_helmet", lambda: conical_helmet_model("SeljukConicalHelmetModel", "seljuk_conical_helmet"), "HELMET",
     (0.5, 0.0, 240, 3, 10), CRIMSON, "ARMOR_EQUIP_IRON"),
    ("seljuk_veiled_helmet", lambda: conical_helmet_model("SeljukVeiledHelmetModel", "seljuk_veiled_helmet", "veil"), "HELMET",
     (1.0, 0.0, 260, 3, 10), CRIMSON, "ARMOR_EQUIP_CHAIN"),
    ("seljuk_turbaned_helmet", lambda: conical_helmet_model("SeljukTurbanedHelmetModel", "seljuk_turbaned_helmet", "turban"), "HELMET",
     (0.5, 0.0, 240, 3, 10), WHITE, "ARMOR_EQUIP_IRON"),
    ("atabeg_helmet", lambda: conical_helmet_model("AtabegHelmetModel", "atabeg_helmet", "gilded"), "HELMET",
     (1.0, 0.0, 270, 3, 14), CRIMSON, "ARMOR_EQUIP_GOLD"),
    ("seljuk_spangenhelm", spangenhelm_model, "HELMET", (0.5, 0.0, 220, 2, 10), CRIMSON, "ARMOR_EQUIP_IRON"),
    ("bork", bork_model, "HELMET", (0.0, 0.0, 100, 1, 9), FELT_RED, "ARMOR_EQUIP_LEATHER"),
    ("turban", turban_model, "HELMET", (0.0, 0.0, 90, 1, 9), WHITE, "ARMOR_EQUIP_LEATHER"),
    ("kazaghand", lambda: coat_model("KazaghandModel", "kazaghand", kazaghand_body, arms(kazaghand_sleeve), kazaghand_skirt, 7),
     "CHESTPLATE", (0.5, 0.0, 300, 6, 10), CRIMSON, "ARMOR_EQUIP_CHAIN"),
    ("jawshan", lambda: jawshan_model(False), "CHESTPLATE", (1.0, 0.0, 320, 7, 10), INDIGO_BLUE, "ARMOR_EQUIP_CHAIN"),
    ("gilded_jawshan", lambda: jawshan_model(True), "CHESTPLATE", (1.25, 0.0, 330, 7, 14), GREEN, "ARMOR_EQUIP_GOLD"),
    ("mail_kaftan", lambda: coat_model("MailKaftanModel", "mail_kaftan", mail_shirt_body,
                                       arms(mail(), cloth(pleats=2, edge_rows=(-1,)), split=5),
                                       cloth(pleats=3, edge_rows=(-1,)), 7),
     "CHESTPLATE", (0.25, 0.0, 280, 5, 10), TURQUOISE, "ARMOR_EQUIP_CHAIN"),
    ("quilted_kaftan", lambda: coat_model("QuiltedKaftanModel", "quilted_kaftan", quilted_body,
                                          arms(cloth(quilt=3, tiraz_rows=(2,), edge_rows=(-1,))), cloth(quilt=3, edge_rows=(-1,)), 6),
     "CHESTPLATE", (0.0, 0.0, 180, 3, 9), SAFFRON, "ARMOR_EQUIP_LEATHER"),
    ("kaftan", lambda: coat_model("KaftanModel", "kaftan", kaftan_body, arms(cloth(pleats=3, edge_rows=(-1,))),
                                  cloth(pleats=3, edge_rows=(-1,)), 6),
     "CHESTPLATE", (0.0, 0.0, 120, 2, 9), TURQUOISE, "ARMOR_EQUIP_LEATHER"),
    ("shalwar", lambda: legs_model("ShalwarModel", "shalwar", shalwar_leg), "LEGGINGS", (0.0, 0.0, 120, 2, 9), WHITE,
     "ARMOR_EQUIP_LEATHER"),
    ("lamellar_thigh_guards", lambda: legs_model("LamellarThighGuardsModel", "lamellar_thigh_guards", shalwar_leg, lamellar()),
     "LEGGINGS", (0.5, 0.0, 260, 4, 10), INDIGO_BLUE, "ARMOR_EQUIP_CHAIN"),
    ("mail_leggings", lambda: legs_model("MailLeggingsModel", "mail_leggings", mail(1)), "LEGGINGS", (0.25, 0.0, 260, 4, 10),
     CRIMSON, "ARMOR_EQUIP_CHAIN"),
    ("turkic_riding_boots", lambda: boots_model("TurkicRidingBootsModel", "turkic_riding_boots"), "BOOTS", (0.0, 0.0, 130, 1, 9),
     RED_BOOT, "ARMOR_EQUIP_LEATHER"),
    ("splinted_greaves", lambda: boots_model("SplintedGreavesModel", "splinted_greaves", True), "BOOTS", (0.5, 0.0, 220, 2, 10),
     RED_BOOT, "ARMOR_EQUIP_IRON"),
]

NAMES = {
    "seljuk_conical_helmet": "Seljuk Conical Helmet",
    "seljuk_veiled_helmet": "Seljuk Helmet with Mail Veil",
    "seljuk_turbaned_helmet": "Turbaned Helmet",
    "atabeg_helmet": "Atabeg's Gilded Helmet",
    "seljuk_spangenhelm": "Seljuk Segmented Helmet",
    "bork": "Turkmen Börk",
    "turban": "Turban",
    "kazaghand": "Kazaghand",
    "jawshan": "Jawshan",
    "gilded_jawshan": "Gilded Jawshan",
    "mail_kaftan": "Mail Shirt over Kaftan",
    "quilted_kaftan": "Quilted Kaftan",
    "kaftan": "Kaftan",
    "shalwar": "Shalwar",
    "lamellar_thigh_guards": "Lamellar Thigh Guards",
    "mail_leggings": "Mail Leggings",
    "turkic_riding_boots": "Turkic Riding Boots",
    "splinted_greaves": "Splinted Greaves",
}

_I, _P, _C = "#c:ingots/steel", "#c:plates/steel", "#magistuarmory:chainmails/steel"
_R, _F, _L = "#magistuarmory:lamellar_rows/steel", "#magistuarmory:woolen_fabrics", "#magistuarmory:leather_strips"
RECIPES = {
    "seljuk_conical_helmet": ([" i ", "ipi", "c c"], {"i": _I, "p": _P, "c": _C}),
    "seljuk_veiled_helmet": (["k", "c"], {"k": "culturesarmory:seljuk_conical_helmet", "c": _C}),
    "seljuk_turbaned_helmet": (["f", "k"], {"k": "culturesarmory:seljuk_conical_helmet", "f": "culturesarmory:turban"}),
    "atabeg_helmet": (["ggg", "gkg", " s "], {"g": "minecraft:gold_ingot", "k": "culturesarmory:seljuk_conical_helmet", "s": "minecraft:string"}),
    "seljuk_spangenhelm": (["pip", "p p", " c "], {"i": _I, "p": _P, "c": _C}),
    "bork": ([" f ", "fff", "lll"], {"f": _F, "l": "minecraft:rabbit_hide"}),
    "turban": (["wfw", "w w"], {"w": "minecraft:white_wool", "f": _F}),
    "kazaghand": (["f f", "fcf", "fcf"], {"f": _F, "c": _C}),
    "jawshan": (["r r", "rfr", "rlr"], {"r": _R, "f": _F, "l": _L}),
    "gilded_jawshan": (["g g", "gjg", "ggg"], {"g": "minecraft:gold_nugget", "j": "culturesarmory:jawshan"}),
    "mail_kaftan": (["c c", "ccc", "fff"], {"c": _C, "f": _F}),
    "quilted_kaftan": (["f f", "fwf", "fwf"], {"f": _F, "w": "minecraft:white_wool"}),
    "kaftan": (["f f", "flf", "fff"], {"f": _F, "l": _L}),
    "shalwar": (["fff", "f f", "w w"], {"f": _F, "w": "minecraft:white_wool"}),
    "lamellar_thigh_guards": (["lll", "r r", "r r"], {"l": _L, "r": _R}),
    "mail_leggings": (["fff", "c c", "c c"], {"f": _F, "c": _C}),
    "turkic_riding_boots": (["l l", "l l", "lsl"], {"l": "minecraft:leather", "s": _L}),
    "splinted_greaves": (["p p", "pbp"], {"p": _P, "b": "culturesarmory:turkic_riding_boots"}),
}
