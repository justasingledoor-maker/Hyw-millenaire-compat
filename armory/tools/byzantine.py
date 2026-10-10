"""
The Byzantine armours of the Cultures Armory: the Middle Byzantine army of the 10th-12th centuries (the Praecepta
Militaria of Nikephoros Phokas, the tagmata, the Varangian Guard), and the kataphraktoi with their barded horses
(horse.py).

Helmets: the segmented kassis with a gilt brow band and a padded neck guard; the kataphraktos' helmet, its mail veil over
the face, a dyed tuft (touphion) on top; the Varangian's tall nasal helmet with a mail aventail; the strategos' gilded,
crested helmet; the light troops' wrapped head cloth (phakeolion) and felt cap (pilos).
Coats: the klibanion (lamellar, with leather pteruges at shoulders and waist, over a dyed kabadion); the kataphraktos'
armour (klibanion with lamellar shoulders, splinted arm guards (manikelia) and a long lamellar skirt); the klibanion
under a dyed padded surcoat (epilorikion); the lorikion (mail) over a dyed tunic; scale armour (folidoton); the padded
kabadion; the Varangian's long mail with a dyed cloak; the strategos' gilded klibanion with a sash and a cloak (chlamys).
Legs: padded kremasmata over hose; lamellar kremasmata for the kataphraktos; hose; mail chausses.
Feet: tzangia (tall boots); the kataphraktos' splinted greaves over the boots.
"""
from armorgen import Cube, Model, Part
from japan import B, D, G, L, gold
from seljuk import (MAIL, STEEL, arms, boots_model, cloth, coat_model, faces_fill, lamella_px, lamellar, legs_model, mail,
                    mail_px, plume, shalwar_leg, steel_bowl, turban_wrap, sash)

LEATHER_RED = [(60, 22, 18), (92, 34, 26), (124, 50, 36), (152, 70, 50)]


# ---------------------------------------------------------------------- painters

def pteruges(f):
    """Pteruges: strips of studded leather, a gap between each, a gilt stud near the top."""
    def side(face, i, t, s):
        if s % 2 == 1:
            return None                                   # the gap between strips
        if t == 1:
            return G[3], False
        return (LEATHER_RED[3] if t == 0 else LEATHER_RED[2] if t < f.h - 1 else LEATHER_RED[1]), False
    faces_fill(f, side, lambda i, j: (LEATHER_RED[2], False), lambda i, j: (LEATHER_RED[0], False), noise=3)


def klibanion_body(gilt=False, collar=True):
    """The klibanion: lamellae in rows, a lit (or gilt) edge on each row, the dyed kabadion at the neck, a belt."""
    def paint(f):
        def side(face, i, t, s):
            if collar and t == 0:
                return D[3], True
            if t == 9:
                return (G[3] if s % 3 == 1 else B[1]), False
            r = (t - 1) % 3
            if gilt and r == 1:
                return (G[3] if s % 2 == 0 else G[2]), False
            return lamella_px(s, t - 1)
        faces_fill(f, side, lambda i, j: (D[3], True) if collar else (STEEL[2], False), lambda i, j: (STEEL[0], False), noise=2)
    return paint


def scale_px(s, t, gilt=False):
    """Scales, each two pixels square, in offset rows: lit at the top left, its edge dark at the bottom right."""
    off = (t // 2) % 2
    c = (s + off) % 2
    pal = [G[1], G[2], G[3], G[4]] if gilt else [STEEL[0], STEEL[1], STEEL[2], STEEL[3]]
    if t % 2 == 0:
        return (pal[3] if c == 0 else pal[2]), False
    return (pal[2] if c == 0 else pal[0]), False


def scale_body(f):
    def side(face, i, t, s):
        if t == 0:
            return D[3], True
        if t == 9:
            return (G[3] if s % 3 == 1 else B[1]), False
        return scale_px(s, t)
    faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: (STEEL[0], False), noise=2)


def manikelia(f):
    """Splinted arm guards over the sleeve: vertical splints on straps."""
    def side(face, i, t, s):
        if t in (0, f.h - 1):
            return (B[1] if s % 3 else STEEL[4]), False
        return (STEEL[3] if s % 2 == 0 else STEEL[1]), False
    faces_fill(f, side, lambda i, j: (STEEL[2], False), lambda i, j: (STEEL[0], False), noise=2)


def epilorikion_body(f):
    """The epilorikion: a dyed, quilted surcoat over the armour, slit at the neck, belted."""
    cx = f.w // 2

    def side(face, i, t, s):
        if t == 0:
            return mail_px(s, t), False
        if t == 9:
            return (G[3] if s % 3 == 1 else B[1]), False
        if face == "front" and i == cx and t < 4:
            return D[1], True
        return (D[2] if (s + t) % 3 == 0 or (s - t) % 3 == 0 else D[3]), True
    faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: (D[1], True))


def lorikion_body(f):
    """The lorikion: mail to below the hips, the dyed tunic at the neck, a belt."""
    faces_fill(f, lambda face, i, t, s: (D[3], True) if t == 0 else (((G[3] if s % 3 == 1 else B[1]), False) if t == 9
               else (mail_px(s, t), False)), lambda i, j: (D[3], True), lambda i, j: (MAIL[0], False), noise=2)


def strategos_body(f):
    """The strategos' klibanion: lamellae in gilt rows, a dyed sash (the cingulum) knotted round the chest."""
    def side(face, i, t, s):
        if t == 0:
            return D[3], True
        if t in (4, 5) or (face == "front" and t == 6 and i in (f.w // 2 - 1, f.w // 2)):
            return (D[4] if t == 4 else D[2]), True             # the sash and its knot
        if t == 9:
            return (G[3] if s % 3 == 1 else G[1]), False
        r = (t - 1) % 3
        if r == 0:
            return (G[1] if s % 2 == 0 else L[1]), False
        return ((G[3] if r == 1 else G[2]) if s % 2 == 0 else G[1]), False
    faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: (G[1], False), noise=2)


def cloak(f):
    """A cloak (chlamys, or the Varangian's), dyed, falling in folds."""
    faces_fill(f, lambda face, i, t, s: ((D[4] if t == 0 else D[2] if s % 3 == 0 else D[3]), True),
               lambda i, j: (D[3], True), lambda i, j: (D[1], True))


def fibula(f):
    gold(f)


def padded_px(s, t):
    return (D[2] if s % 2 == 0 else D[3]), True


def kabadion_body(f):
    """The kabadion: a thick padded coat, quilted in vertical lines, closed down the front, belted."""
    cx = f.w // 2

    def side(face, i, t, s):
        if t == 9:
            return B[1], False
        if face == "front" and i == cx:
            return D[1], True
        return padded_px(s, t)
    faces_fill(f, side, lambda i, j: (D[3], True), lambda i, j: (D[2], True))


def padded(f):
    faces_fill(f, lambda face, i, t, s: ((D[4], True) if t == f.h - 1 else padded_px(s, t)), lambda i, j: (D[3], True),
               lambda i, j: (D[2], True))


# ---------------------------------------------------------------------- helmets

def brow_band(f):
    """The gilt brow band riveted round the bowl."""
    faces_fill(f, lambda face, i, t, s: ((G[4] if s % 3 == 1 else G[3]) if t == 0 else G[2], False), lambda i, j: (G[3], False),
               lambda i, j: (G[1], False))


def touphion(f):
    """The tuft of dyed horsehair on the helmet's top."""
    faces_fill(f, lambda face, i, t, s: ((D[4] if t == 0 else D[3] if (s + t) % 2 else D[2]), True), lambda i, j: (D[4], True),
               lambda i, j: (D[1], True))


def padded_neck(f):
    faces_fill(f, lambda face, i, t, s: ((D[4] if t == f.h - 1 else D[2] if s % 2 == 0 else D[3]), True),
               lambda i, j: (D[3], True), lambda i, j: (D[2], True))


def kassis_parts(veil=False, tuft=False, nasal=False, tall=False, gilt=False, crest=False, padded_guard=True):
    bowl = steel_bowl(bands="gilt" if gilt else True, gilt_rim=False, rivets=False)
    cubes = [
        Cube((-4.5, -9, -4.5), (9, 3, 9), paint=bowl),
        Cube((-4.5, -6.5, -4.5), (9, 1, 9), inflate=0.15, paint=brow_band),
        Cube((-3.5, -10, -3.5), (7, 1, 7), paint=bowl),
        Cube((-2.5, -11, -2.5), (5, 1, 5), paint=bowl),
    ]
    if tall:
        cubes += [Cube((-1.5, -12, -1.5), (3, 1, 3), paint=bowl), Cube((-0.5, -13, -0.5), (1, 1, 1), paint=gold)]
    else:
        cubes.append(Cube((-0.5, -12, -0.5), (1, 1, 1), paint=gold))
    children = []
    if padded_guard:
        children += [
            Part("neck_guard", pivot=(0, -5.5, 4.6), rot=(0.18, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, 5, 1), paint=padded_neck)]),
            Part("guard_right", pivot=(-4.6, -5.5, 0), rot=(0, 0, 0.12), cubes=[Cube((-0.5, 0, -3), (1, 4, 7), paint=padded_neck)]),
            Part("guard_left", pivot=(4.6, -5.5, 0), rot=(0, 0, -0.12), cubes=[Cube((-0.5, 0, -3), (1, 4, 7), paint=padded_neck)]),
        ]
    else:
        children += [
            Part("aventail_back", pivot=(0, -5.5, 4.6), rot=(0.18, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, 6, 1), paint=mail(1))]),
            Part("aventail_right", pivot=(-4.6, -5.5, 0), rot=(0, 0, 0.12), cubes=[Cube((-0.5, 0, -3.5), (1, 5, 8), paint=mail(1))]),
            Part("aventail_left", pivot=(4.6, -5.5, 0), rot=(0, 0, -0.12), cubes=[Cube((-0.5, 0, -3.5), (1, 5, 8), paint=mail(1))]),
        ]
    if veil:
        children.append(Part("mail_veil", pivot=(0, -4.6, -4.7), rot=(-0.06, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, 5, 1), paint=mail(1))]))
    if nasal:
        children.append(Part("nasal", cubes=[Cube((-0.5, -6.5, -5.2), (1, 3, 1), paint=steel_bowl(gilt_rim=False, rivets=False))]))
    if tuft:
        children.append(Part("touphion", pivot=(0, -12, 0), cubes=[Cube((-1, -2, -1), (2, 2, 2), paint=touphion)]))
    if crest:
        children.append(Part("crest", pivot=(0, -11, 0), cubes=[Cube((-0.5, -2, -4), (1, 2, 8), paint=plume)]))
    return Part("head", cubes=cubes, children=children)


def helmet_model(java_name, location, **kw):
    return Model(java_name, location, {"head": kassis_parts(**kw)})


def pilos_model():
    """The pilos: a rounded felt cap with its brim turned up."""
    from seljuk import felt
    head = Part("head", cubes=[
        Cube((-4.5, -8.5, -4.5), (9, 2, 9), paint=felt),
        Cube((-4, -10.5, -4), (8, 2, 8), paint=felt),
        Cube((-3, -11.5, -3), (6, 1, 6), paint=felt),
    ])
    return Model("PilosModel", "pilos", {"head": head})


def phakeolion_model():
    """The phakeolion: a cloth wound round the head like a turban, close and low."""
    head = Part("head", cubes=[
        Cube((-4.5, -9, -4.5), (9, 3, 9), paint=turban_wrap),
        Cube((-3.5, -10, -3.5), (7, 1, 7), paint=turban_wrap),
    ], children=[
        Part("phakeolion_tail", pivot=(-1.5, -6, 4.6), rot=(0.1, 0, 0.05), cubes=[Cube((-1, 0, -0.5), (2, 4, 1), paint=cloth(pleats=2))]),
    ])
    return Model("PhakeolionModel", "phakeolion", {"head": head})


# ---------------------------------------------------------------------- coats

def strips(name, pivot, rot, origin, size):
    return Part(name, pivot=pivot, rot=rot, cubes=[Cube(origin, size, paint=pteruges)])


def shoulder_pteruges():
    """Pteruges hanging from the shoulders (children of the arms)."""
    out = {}
    for name, x in (("right_arm", -3.5), ("left_arm", -1.5)):
        out[name] = Part(name.replace("arm", "pteruges"), pivot=(0, 1.5, 0), cubes=[Cube((x - 0.2, 0, -2.7), (5, 3, 5), inflate=0.25, paint=pteruges)])
    return out


def with_children(arm_parts, extra):
    for name, part in arm_parts.items():
        if name in extra:
            part.children.append(extra[name])
    return arm_parts


def waist_pteruges(length=4):
    return [
        strips("pteruges_front", (0, 10, -3), (-0.12, 0, 0), (-5, 0, -0.5), (10, length, 1)),
        strips("pteruges_back", (0, 10, 3), (0.12, 0, 0), (-5, 0, -0.5), (10, length, 1)),
        strips("pteruges_right", (-5, 10, 0), (0, 0, 0.12), (-0.5, 0, -3), (1, length, 6)),
        strips("pteruges_left", (5, 10, 0), (0, 0, -0.12), (-0.5, 0, -3), (1, length, 6)),
    ]


def cloak_parts(fibula_side=-1):
    return [
        Part("chlamys", pivot=(0, -0.5, 3.3), rot=(0.1, 0, 0), cubes=[Cube((-5, 0, -0.5), (10, 15, 1), paint=cloak)]),
        Part("fibula", cubes=[Cube((fibula_side * 4 - 1, -1.2, -3.4), (2, 2, 1), paint=fibula)]),
    ]


SLEEVE = cloth(pleats=2, edge_rows=(-1,))


def klibanion_model():
    return coat_model("KlibanionModel", "klibanion", klibanion_body(),
                      with_children(arms(SLEEVE), shoulder_pteruges()), extra=waist_pteruges(4))


def kataphract_model():
    """The kataphraktos: klibanion, lamellar shoulders, manikelia on the forearms, a long lamellar skirt."""
    return coat_model("KataphractArmourModel", "kataphract_armour", klibanion_body(),
                      arms(SLEEVE, manikelia, split=6, cap=lamellar()), skirt_paint=lamellar(), skirt_len=7)


def epilorikion_model():
    return coat_model("EpilorikionModel", "epilorikion", epilorikion_body, arms(mail()), skirt_paint=cloth(pleats=3, edge_rows=(-1,)),
                      skirt_len=6)


def lorikion_model():
    return coat_model("LorikionModel", "lorikion", lorikion_body, arms(mail(), SLEEVE, split=5), skirt_paint=mail(), skirt_len=5)


def scale_model():
    return coat_model("ScaleArmourModel", "scale_armour", scale_body, with_children(arms(SLEEVE), shoulder_pteruges()),
                      extra=waist_pteruges(4))


def kabadion_model():
    return coat_model("KabadionModel", "kabadion", kabadion_body, arms(padded), skirt_paint=padded, skirt_len=6)


def varangian_model():
    return coat_model("VarangianHauberkModel", "varangian_hauberk", lorikion_body, arms(mail()), skirt_paint=mail(), skirt_len=7,
                      extra=cloak_parts(1))


def strategos_model():
    return coat_model("StrategosKlibanionModel", "strategos_klibanion", strategos_body,
                      with_children(arms(lamellar(gilt=True), SLEEVE, split=4), shoulder_pteruges()),
                      extra=waist_pteruges(5) + cloak_parts(-1))


# ---------------------------------------------------------------------- legs and feet

def padded_skirt_legs():
    """Kremasmata: a padded skirt over the hose."""
    def leg(name):
        return Part(name, cubes=[Cube((-2.5, -0.5, -2.5), (5, 13, 5), paint=shalwar_leg)])
    body = Part("body", cubes=[Cube((-4.5, 9.5, -2.5), (9, 3, 5), paint=sash)], children=[
        Part("kremasmata_front", pivot=(0, 12, -2.6), rot=(-0.1, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, 6, 1), paint=padded)]),
        Part("kremasmata_back", pivot=(0, 12, 2.6), rot=(0.1, 0, 0), cubes=[Cube((-4.5, 0, -0.5), (9, 6, 1), paint=padded)]),
        Part("kremasmata_right", pivot=(-4.6, 12, 0), rot=(0, 0, 0.1), cubes=[Cube((-0.5, 0, -2.5), (1, 6, 5), paint=padded)]),
        Part("kremasmata_left", pivot=(4.6, 12, 0), rot=(0, 0, -0.1), cubes=[Cube((-0.5, 0, -2.5), (1, 6, 5), paint=padded)]),
    ])
    return Model("KremasmataModel", "kremasmata", {"body": body, "right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


def boots_leg(f):
    """Tzangia: tall black leather boots, laced up the front (the dyed lining shows at the top)."""
    def side(face, i, t, s):
        if t == 0:
            return D[3], True
        if face == "front" and i in (2, 3) and t % 2 == 1:
            return B[2], False
        return (L[3] if s % 4 == 0 else L[2]), False
    faces_fill(f, side, lambda i, j: (L[2], False), lambda i, j: (L[0], False), noise=3)


def boot_foot(f):
    faces_fill(f, lambda face, i, t, s: (L[2], False), lambda i, j: (L[3] if j < f.d - 1 else L[4], False), lambda i, j: (L[0], False))


def tzangia_model(greaves=False):
    from seljuk import greave, knee_cop

    def leg(name):
        cubes = [Cube((-3, 4.5, -3), (6, 7, 6), paint=greave if greaves else boots_leg), Cube((-3, 11.5, -3.5), (6, 1, 7), paint=boot_foot)]
        children = [Part(name.replace("leg", "toe"), cubes=[Cube((-1, 11.5, -4.5), (2, 1, 1), paint=boot_foot)])]
        if greaves:
            children.append(Part(name.replace("leg", "knee"), pivot=(0, 4.5, -3), rot=(0.15, 0, 0),
                                 cubes=[Cube((-2, -1, -0.75), (4, 3, 1), paint=knee_cop)]))
        return Part(name, cubes=cubes, children=children)
    return Model("PodopsellaModel" if greaves else "TzangiaModel", "podopsella" if greaves else "tzangia",
                 {"right_leg": leg("right_leg"), "left_leg": leg("left_leg")})


# ---------------------------------------------------------------------- the items

PURPLE = 0x5E2A5C       # the imperial purple
CRIMSON = 0x8E2A2A
BLUE = 0x2F4B7A
GREEN = 0x3E6B3A
OCHRE = 0xA8782E
WHITE = 0xE6E1D3
SCARLET = 0xB0302A
DARK = 0x3A3238

ARMOURS = [
    ("kassis", lambda: helmet_model("KassisModel", "kassis"), "HELMET", (0.5, 0.0, 230, 2, 10), CRIMSON, "ARMOR_EQUIP_IRON"),
    ("kataphract_helmet", lambda: helmet_model("KataphractHelmetModel", "kataphract_helmet", veil=True, tuft=True, padded_guard=False,
                                               tall=True), "HELMET", (1.0, 0.0, 270, 3, 10), CRIMSON, "ARMOR_EQUIP_CHAIN"),
    ("varangian_helmet", lambda: helmet_model("VarangianHelmetModel", "varangian_helmet", nasal=True, tall=True, padded_guard=False),
     "HELMET", (0.5, 0.0, 250, 3, 10), SCARLET, "ARMOR_EQUIP_IRON"),
    ("strategos_helmet", lambda: helmet_model("StrategosHelmetModel", "strategos_helmet", gilt=True, crest=True), "HELMET",
     (1.0, 0.0, 270, 3, 14), PURPLE, "ARMOR_EQUIP_GOLD"),
    ("phakeolion", phakeolion_model, "HELMET", (0.0, 0.0, 90, 1, 9), WHITE, "ARMOR_EQUIP_LEATHER"),
    ("pilos", pilos_model, "HELMET", (0.0, 0.0, 100, 1, 9), OCHRE, "ARMOR_EQUIP_LEATHER"),
    ("klibanion", klibanion_model, "CHESTPLATE", (0.75, 0.0, 300, 6, 10), CRIMSON, "ARMOR_EQUIP_CHAIN"),
    ("kataphract_armour", kataphract_model, "CHESTPLATE", (1.25, 0.25, 340, 8, 10), CRIMSON, "ARMOR_EQUIP_CHAIN"),
    ("epilorikion", epilorikion_model, "CHESTPLATE", (0.75, 0.0, 300, 6, 10), BLUE, "ARMOR_EQUIP_CHAIN"),
    ("lorikion", lorikion_model, "CHESTPLATE", (0.5, 0.0, 280, 5, 10), GREEN, "ARMOR_EQUIP_CHAIN"),
    ("scale_armour", scale_model, "CHESTPLATE", (1.0, 0.0, 300, 6, 10), CRIMSON, "ARMOR_EQUIP_CHAIN"),
    ("kabadion", kabadion_model, "CHESTPLATE", (0.0, 0.0, 180, 3, 9), OCHRE, "ARMOR_EQUIP_LEATHER"),
    ("varangian_hauberk", varangian_model, "CHESTPLATE", (0.75, 0.0, 300, 6, 10), SCARLET, "ARMOR_EQUIP_CHAIN"),
    ("strategos_klibanion", strategos_model, "CHESTPLATE", (1.25, 0.0, 330, 7, 14), PURPLE, "ARMOR_EQUIP_GOLD"),
    ("kremasmata", padded_skirt_legs, "LEGGINGS", (0.25, 0.0, 200, 3, 9), CRIMSON, "ARMOR_EQUIP_LEATHER"),
    ("lamellar_kremasmata", lambda: legs_model("LamellarKremasmataModel", "lamellar_kremasmata", shalwar_leg, lamellar(), 8),
     "LEGGINGS", (0.75, 0.0, 270, 5, 10), CRIMSON, "ARMOR_EQUIP_CHAIN"),
    ("byzantine_hose", lambda: legs_model("ByzantineHoseModel", "byzantine_hose", cloth(pleats=4, dark_rows=(-1,))), "LEGGINGS",
     (0.0, 0.0, 110, 1, 9), CRIMSON, "ARMOR_EQUIP_LEATHER"),
    ("mail_chausses", lambda: legs_model("MailChaussesModel", "mail_chausses", mail(1)), "LEGGINGS", (0.25, 0.0, 260, 4, 10),
     SCARLET, "ARMOR_EQUIP_CHAIN"),
    ("tzangia", lambda: tzangia_model(False), "BOOTS", (0.0, 0.0, 140, 1, 9), CRIMSON, "ARMOR_EQUIP_LEATHER"),
    ("podopsella", lambda: tzangia_model(True), "BOOTS", (0.5, 0.0, 230, 2, 10), CRIMSON, "ARMOR_EQUIP_IRON"),
]

NAMES = {
    "kassis": "Byzantine Kassis",
    "kataphract_helmet": "Kataphraktos Helmet",
    "varangian_helmet": "Varangian Helmet",
    "strategos_helmet": "Strategos' Helmet",
    "phakeolion": "Phakeolion",
    "pilos": "Pilos",
    "klibanion": "Klibanion",
    "kataphract_armour": "Kataphraktos Armour",
    "epilorikion": "Klibanion with Epilorikion",
    "lorikion": "Lorikion",
    "scale_armour": "Scale Armour (Folidoton)",
    "kabadion": "Kabadion",
    "varangian_hauberk": "Varangian Hauberk",
    "strategos_klibanion": "Strategos' Klibanion",
    "kremasmata": "Kremasmata",
    "lamellar_kremasmata": "Lamellar Kremasmata",
    "byzantine_hose": "Byzantine Hose",
    "mail_chausses": "Mail Chausses",
    "tzangia": "Tzangia",
    "podopsella": "Podopsella",
}

_I, _P, _C = "#c:ingots/steel", "#c:plates/steel", "#magistuarmory:chainmails/steel"
_R, _F, _L = "#magistuarmory:lamellar_rows/steel", "#magistuarmory:woolen_fabrics", "#magistuarmory:leather_strips"
RECIPES = {
    "kassis": (["pip", "f f"], {"i": _I, "p": _P, "f": _F}),
    "kataphract_helmet": (["k", "c", "c"], {"k": "culturesarmory:kassis", "c": _C}),
    "varangian_helmet": ([" i ", "pip", "c c"], {"i": _I, "p": _P, "c": _C}),
    "strategos_helmet": (["fgf", "gkg"], {"g": "minecraft:gold_ingot", "k": "culturesarmory:kassis", "f": "minecraft:feather"}),
    "phakeolion": (["fff", "w w"], {"f": _F, "w": "minecraft:white_wool"}),
    "pilos": (["fwf", "f f"], {"f": _F, "w": "minecraft:brown_wool"}),
    "klibanion": (["r r", "rlr", "rfr"], {"r": _R, "l": _L, "f": _F}),
    "kataphract_armour": (["rpr", "rkr", "rrr"], {"r": _R, "p": _P, "k": "culturesarmory:klibanion"}),
    "epilorikion": (["fkf", "fff"], {"f": _F, "k": "culturesarmory:klibanion"}),
    "lorikion": (["c c", "ccc", "cfc"], {"c": _C, "f": _F}),
    "scale_armour": (["p p", "pfp", "plp"], {"p": _P, "f": _F, "l": _L}),
    "kabadion": (["f f", "fwf", "fff"], {"f": _F, "w": "minecraft:white_wool"}),
    "varangian_hauberk": (["c c", "ccc", "crc"], {"c": _C, "r": "minecraft:red_wool"}),
    "strategos_klibanion": (["g g", "gkg", "gpg"], {"g": "minecraft:gold_ingot", "k": "culturesarmory:klibanion",
                                                    "p": "minecraft:purple_wool"}),
    "kremasmata": (["fff", "fwf", "f f"], {"f": _F, "w": "minecraft:white_wool"}),
    "lamellar_kremasmata": (["frf", "r r", "r r"], {"f": _F, "r": _R}),
    "byzantine_hose": (["fwf", "f f", "f f"], {"f": _F, "w": "minecraft:red_wool"}),
    "mail_chausses": (["clc", "c c", "c c"], {"c": _C, "l": _L}),
    "tzangia": (["l l", "lbl"], {"l": "minecraft:leather", "b": "minecraft:black_dye"}),
    "podopsella": (["p p", "ptp"], {"p": _P, "t": "culturesarmory:tzangia"}),
}
