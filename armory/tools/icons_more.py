"""
Icons of the Seljuk and Inuit pieces (16x16 grids in icons.KEY's characters). Coats, trousers and boots share a silhouette
per slot, filled per piece (so a set reads as a set); helmets are drawn one by one.
"""

COAT = [
    ".ooo........ooo.",
    "oXXXoo....ooXXXo",
    "oXXXXXooooXXXXXo",
    "oXXXXXXXXXXXXXXo",
    "oXXXoXXXXXXoXXXo",
    "oXXXoXXXXXXoXXXo",
    "oXXXoXXXXXXoXXXo",
    "oXXXoXXXXXXoXXXo",
    "oXXXoXXXXXXoXXXo",
    "oooooXXXXXXooooo",
    "....oXXXXXXo....",
    "...oXXXXXXXXo...",
    "...oXXXXXXXXo...",
    "..oXXXXXXXXXXo..",
    "..oXXXXXXXXXXo..",
    "..oooooooooooo..",
]
TROUSERS = [
    "..oooooooooooo..",
    "..oXXXXXXXXXXo..",
    "..oXXXXXXXXXXo..",
    "..oXXXXooXXXXo..",
    "..oXXXXooXXXXo..",
    "..oXXXXooXXXXo..",
    "..oXXXXooXXXXo..",
    "..oXXXXooXXXXo..",
    "..oXXXXooXXXXo..",
    "..oXXXXooXXXXo..",
    "..oXXXXooXXXXo..",
    "..oXXXXooXXXXo..",
    "..oXXXXooXXXXo..",
    "..oXXXXooXXXXo..",
    "..oooooooooooo..",
    "................",
]
BOOTS = [
    "................",
    "..oooo....oooo..",
    "..oXXo....oXXo..",
    "..oXXo....oXXo..",
    "..oXXo....oXXo..",
    "..oXXo....oXXo..",
    "..oXXo....oXXo..",
    "..oXXo....oXXo..",
    "..oXXo....oXXo..",
    "..oXXo....oXXo..",
    "..oXXoo..ooXXo..",
    ".oXXXXo..oXXXXo.",
    ".oXXXXo..oXXXXo.",
    "..oooo....oooo..",
    "................",
    "................",
]


def fill(template, fn, overrides=None):
    """Fill a silhouette: fn(x, y, region) for every 'X' (region: 'sleeve', 'body', 'skirt', 'waist', 'leg', 'shaft', 'foot')."""
    out = []
    for y, row in enumerate(template):
        r = ""
        for x, ch in enumerate(row):
            if ch != "X":
                r += ch
                continue
            if template is COAT:
                region = "sleeve" if (x < 4 or x > 11) and y <= 8 else "body" if y <= 8 else "skirt"
            elif template is TROUSERS:
                region = "waist" if y <= 2 else "leg"
            else:
                region = "foot" if y >= 10 else "shaft"
            r += fn(x, y, region)
        out.append(r)
    for (x, y), ch in (overrides or {}).items():
        out[y] = out[y][:x] + ch + out[y][x + 1:]
    return out


def mail(x, y):
    return "I" if (x + y) % 2 == 0 else "i"


def lamellae(x, y, gilt=False):
    if y % 3 == 0:
        return "l"
    if gilt and (y // 3) % 2:
        return "G" if x % 2 == 0 else "g"
    return "J" if x % 2 == 0 else "j"


def cloth(x, y, pleat=3):
    return "b" if x % pleat == 0 else "c"


def ivory_lam(x, y):
    return "b" if y % 3 == 0 else ("F" if x % 2 == 0 else "C")


def fur(x, y, pal="tT"):
    return pal[(x * 3 + y) % 4 < 2]


# ---------------------------------------------------------------------- Seljuk

CONE_TOP = [
    ".......oo.......",
    "......oJjo......",
    "......oJjo......",
    ".....oJJjjo.....",
    ".....oJjjjo.....",
    "....oJJjjjjo....",
    "...oJJJjjjjjo...",
    "..oJJjjjjjjjjo..",
    "..ogGgGgGgGgGo..",
]
AVENTAIL = [
    ".oIiIoooJooiIiIo",
    ".oiIiIo.J.oIiIio",
    ".oIiIio...oiIiIo",
    ".oiIiIo...oIiIio",
    ".oIiIio...oiIiIo",
    ".obcbcbo.obcbcbo",
    "..oooooo.oooooo.",
]
VEIL = [
    ".oIiIiooooiIiIio",
    ".oiIiIiIiIiIiIio",
    ".oIiIiIiIiIiIiIo",
    ".oiIiIiIiIiIiIio",
    ".oIiIiIiIiIiIiIo",
    ".obcbcbcbcbcbcbo",
    ".oooooooooooooo.",
]
ATABEG_TOP = [
    ".......ab.......",
    "......oyho......",
    "......oGgo......",
    ".....oGJgjo.....",
    ".....oJGjgo.....",
    "....oGJgjGgo....",
    "...oJGjgJgjGo...",
    "..oGJgjGjgJgjo..",
    "..ohGhGhGhGhGo..",
]


def coat(fn, overrides=None):
    return fill(COAT, fn, overrides)


def kazaghand(x, y, region):
    if region == "sleeve":
        return "G" if y in (2, 3) else mail(x, y) if y == 8 else cloth(x, y, 2)
    if region == "body":
        return "I" if y == 2 else ("G" if x % 3 == 1 else "l") if y == 8 else "a" if x in (7, 8) and y < 8 else cloth(x, y, 2)
    return mail(x, y) if y == 14 else cloth(x, y, 2)


def jawshan(gilt):
    def fn(x, y, region):
        if region == "sleeve":
            return lamellae(x, y, gilt) if y <= 2 else (mail(x, y) if gilt else cloth(x, y))
        if region == "body":
            if gilt and 3 <= y <= 6 and 6 <= x <= 9:
                return "h" if (x, y) in ((6, 3), (7, 3)) else "G" if 7 <= x <= 8 and 4 <= y <= 5 else "g"
            return ("G" if x % 3 == 1 else "l") if y == 8 else "c" if y == 2 else lamellae(x, y, gilt)
        return lamellae(x, y + 1, gilt)
    return fn


def mail_kaftan(x, y, region):
    if region == "sleeve":
        return mail(x, y) if y <= 4 else cloth(x, y)
    if region == "body":
        return "c" if y == 2 else ("G" if x % 3 == 1 else "l") if y == 8 else mail(x, y)
    return "d" if y == 14 else cloth(x, y)


def quilted(x, y, region):
    if region == "sleeve":
        return "G" if y == 2 else "b" if (x + y) % 3 == 0 else "c"
    if region == "body":
        if y == 8:
            return "L" if x % 4 else "g"
        if x == 9 - (y - 2) // 2:
            return "d"
        return "b" if (x + y) % 3 == 0 or (x - y) % 3 == 0 else "c"
    return "d" if y == 14 else "b" if (x + y) % 3 == 0 else "c"


def kaftan(x, y, region):
    if region == "body" and y == 8:
        return "a"
    if region == "body" and x == 9 - (y - 2) // 2:
        return "g"
    if region == "skirt" and x == 9 - (y - 2) // 2:
        return "g"
    return "d" if y == 14 else cloth(x, y)


def trousers(fn):
    return fill(TROUSERS, fn)


def boots(fn):
    return fill(BOOTS, fn)


SELJUK = {
    "seljuk_conical_helmet": CONE_TOP + AVENTAIL,
    "seljuk_veiled_helmet": CONE_TOP + VEIL,
    "seljuk_turbaned_helmet": CONE_TOP[:7] + [
        ".oddccddccddcco.",
        "occddccddccddcco",
        "oddccddccddccddo",
        ".occddccddccddo.",
        ".oIiIo....oiIiIo",
        ".oiIio....oIiIio",
        ".oIiIo....oiIiIo",
        ".ooooo....ooooo.",
        "................",
    ],
    "atabeg_helmet": ATABEG_TOP + AVENTAIL,
    "seljuk_spangenhelm": [
        "................",
        "................",
        "......oooo......",
        "....ooJyJjoo....",
        "...oJJjyjjjjo...",
        "..oJJjjyjjjjjo..",
        "..oJyjjyjjyjjo..",
        "..oJyjjyjjyjjo..",
        "..ogGgGgGgGgGo..",
        ".oJjoIiIiIioJjo.",
        ".oJjo......oJjo.",
        ".ojjo......ojjo.",
        "..oo........oo..",
        "................",
        "................",
        "................",
    ],
    "bork": [
        "................",
        "......oooo......",
        ".....odddco.....",
        ".....odccco.....",
        "....oddcccbo....",
        "....odccccbo....",
        "....odccccbo....",
        "...oddcccccbo...",
        "...odccccccbo...",
        "..oUuUuUuUuUuo..",
        ".oUuUuUuUuUuUuo.",
        ".ouUuUuUuUuUuUo.",
        "..oooooooooooo..",
        "................",
        "................",
        "................",
    ],
    "turban": [
        "................",
        "................",
        "................",
        "......oooo......",
        "....oodccdoo....",
        "...odcddccddo...",
        "..odccddccddco..",
        "..occddccddcco..",
        ".odccddccddccdo.",
        ".occddccddccddo.",
        ".odccddccddccdo.",
        "..oooooooooooo..",
        "...........ocdo.",
        "...........odco.",
        "...........oooo.",
        "................",
    ],
    "kazaghand": coat(kazaghand),
    "jawshan": coat(jawshan(False)),
    "gilded_jawshan": coat(jawshan(True)),
    "mail_kaftan": coat(mail_kaftan),
    "quilted_kaftan": coat(quilted),
    "kaftan": coat(kaftan),
    "shalwar": trousers(lambda x, y, r: ("d" if y == 1 else "b") if r == "waist" else "a" if y == 13 else cloth(x, y)),
    "lamellar_thigh_guards": trousers(lambda x, y, r: ("d" if y == 1 else "b") if r == "waist"
                                      else lamellae(x, y) if y <= 9 else "a" if y == 13 else cloth(x, y)),
    "mail_leggings": trousers(lambda x, y, r: ("d" if y == 1 else "b") if r == "waist" else mail(x, y)),
    "turkic_riding_boots": boots(lambda x, y, r: "b" if y == 2 else ("c" if x % 3 else "d") if r == "shaft" else "b"),
    "splinted_greaves": boots(lambda x, y, r: ("l" if y in (2, 9) else "J" if x % 2 == 0 else "j") if r == "shaft" else "b"),
}


# ---------------------------------------------------------------------- Inuit

def hood(inner_rows, pal="tT", plates=False):
    top = [
        "................",
        ".....oooooo.....",
        "...oo" + "".join(pal[k % 2] for k in range(6)) + "oo...",
        "..o" + "".join(pal[k % 2] for k in range(10)) + "o..",
    ]
    if plates:
        top[2] = "...ooFCFCFCoo..."
        top[3] = "..oFCFCFCFCFCo.."
    return top + inner_rows + [
        ".o" + "".join(pal[k % 2] for k in range(12)) + "o.",
        "..ocdcdcdcdcdo..",
        "..oooooooooooo..",
        "................",
        "................",
    ]


FACE = [
    ".otTPVPVPVPVtTo.",
    ".otPVffffffVPto.",
    ".oTVfoffffofVTo.",
    ".otPffffffffPto.",
    ".oTVfffoofffVTo.",
    ".otPVffffffVPto.",
    ".oTtPVPVPVPVtTo.",
]
GOGGLES = list(FACE)
GOGGLES[2] = ".oTVMoMMMMoMVTo."


def parka(x, y, region, pal="tT"):
    if region == "sleeve":
        return "H" if y == 8 else "c" if y == 7 else fur(x, y, pal)
    if region == "body":
        return "c" if y == 3 else fur(x, y, pal)
    return "c" if y == 13 else fur(x, y, pal)


def tail(icon):
    """The parka's long back tail: a narrower skirt bottom."""
    out = [row for row in icon]
    out[13] = "...o" + out[13][4:12] + "o..."
    out[14] = "...o" + out[14][4:12] + "o..."
    out[15] = "....oooooooo...."
    return out


def ceremonial(x, y, region):
    if region == "sleeve":
        return "H" if y == 8 else "6" if y == 7 else fur(x, y)
    if y in (4, 5) or y in (11, 12):
        return "H" if (x + (y % 2)) % 4 < 2 else "u"
    if y == 3 or y == 13:
        return "c"
    return fur(x, y)


def bone_armour(x, y, region):
    if region == "sleeve":
        return ivory_lam(x, y) if y <= 3 else "H" if y == 8 else fur(x, y)
    return ivory_lam(x, y)


def slat(x, y, region):
    if region == "sleeve":
        return "H" if y == 8 else fur(x, y)
    if x % 4 == 1:
        return "c"
    return "F" if y % 2 == 0 else "C"


def hide(x, y, region):
    if region == "sleeve" and y > 3:
        return "H" if y == 8 else fur(x, y)
    if x % 5 == 2 and y % 3 == 1:
        return "c"
    return "Y" if y % 3 == 2 else "5"


INUIT = {
    "parka_hood": hood(FACE),
    "snow_goggle_hood": hood(GOGGLES),
    "bentwood_visor": [
        "................",
        "..V.....V.......",
        "..C.....C.......",
        "...C...C........",
        "....oooo........",
        "...oFcdcdcoo....",
        "..oCOMOMOMOMoo..",
        "..oodcdcdcdcdcoo",
        "....ooMOMOMOMOMo",
        "......oocdcdcdco",
        "........oooooooo",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "bone_plate_hood": hood([
        ".oFCbbbbbbbbCFo.",
        ".oCbffffffffbCo.",
        ".oFCfoffffofCFo.",
        ".oCbffffffffbCo.",
        ".oFCfffoofffCFo.",
        ".oCbffffffffbCo.",
        ".oFCFCFCFCFCFCo.",
    ], pal="YY", plates=True),
    "ivory_lamellar_helmet": [
        "................",
        "................",
        "......oooo......",
        "....ooFCFCoo....",
        "...oFCFCFCFCo...",
        "..obbbbbbbbbbo..",
        "..oFCFCFCFCFCo..",
        "..oFCFCFCFCFCo..",
        "..oPVPVPVPVPVo..",
        ".oFCo......oFCo.",
        ".obbo......obbo.",
        ".oFCo......oFCo.",
        "..oo........oo..",
        "................",
        "................",
        "................",
    ],
    "caribou_parka": tail(coat(parka)),
    "sealskin_parka": tail(coat(lambda x, y, r: parka(x, y, r, "AB"))),
    "ceremonial_parka": [r if y < 15 else "....oFoFoFoFo..." for y, r in enumerate(tail(coat(ceremonial)))],
    "bone_plate_armour": coat(bone_armour),
    "slat_armour": coat(slat, {(12, 0): "o", (13, 0): "F", (14, 0): "F", (15, 0): "o"}),
    "walrus_hide_armour": coat(hide),
    "caribou_trousers": trousers(lambda x, y, r: "c" if y == 1 else ("H" if x % 3 == 0 else fur(x, y))),
    "sealskin_trousers": trousers(lambda x, y, r: "c" if y == 1 else ("A" if (x * 2 + y) % 5 == 0 else "B")),
    "bone_plate_leggings": trousers(lambda x, y, r: "c" if y == 1 else ivory_lam(x, y) if 3 <= y <= 9
                                    else ("H" if x % 3 == 0 else fur(x, y))),
    "sealskin_kamik": boots(lambda x, y, r: "c" if y == 2 else ("A" if (x + y) % 4 == 0 else "B") if r == "shaft" else
                            ("C" if y == 12 and x % 2 else "B")),
    "caribou_kamik": boots(lambda x, y, r: ("V" if x % 2 else "P") if y in (2, 3) else ("H" if x % 3 == 0 else fur(x, y))
                           if r == "shaft" else ("C" if y == 12 and x % 2 else "T")),
}

# ---------------------------------------------------------------------- Byzantine (1.3)

def scale(x, y, gilt=False):
    c = (x + (y // 2) % 2) % 2
    if gilt:
        return ("h" if c == 0 else "G") if y % 2 == 0 else ("G" if c == 0 else "y")
    return ("p" if c == 0 else "J") if y % 2 == 0 else ("J" if c == 0 else "j")


def strips(x):
    return "r" if x % 2 == 0 else "."


KASSIS_TOP = [
    "................",
    "................",
    ".......oo.......",
    ".....oojjoo.....",
    "....oJjyJjjo....",
    "...oJJjyjjjjo...",
    "..oJjjjyjjjjjo..",
    "..oJyjjyjjyjjo..",
    "..ogGgGgGgGgGo..",
]
PADDED_GUARD = [
    ".odcdo....odcdo.",
    ".ocdco....ocdco.",
    ".odcdo....odcdo.",
    ".ocdco....ocdco.",
    "..oooo....oooo..",
    "................",
    "................",
]


def klibanion(x, y, region):
    if region == "sleeve":
        if y <= 2:
            return strips(x) if y == 2 else "r"
        return "H" if False else cloth(x, y, 2)
    if region == "body":
        return "c" if y == 2 else ("G" if x % 3 == 1 else "l") if y == 8 else lamellae(x, y)
    return strips(x) if y <= 11 else "."


def kataphract(x, y, region):
    if region == "sleeve":
        return lamellae(x, y) if y <= 3 else ("J" if x % 2 == 0 else "j") if y >= 6 else cloth(x, y)
    if region == "body":
        return "c" if y == 2 else ("G" if x % 3 == 1 else "l") if y == 8 else lamellae(x, y)
    return lamellae(x, y + 1)


def epilorikion(x, y, region):
    if region == "sleeve":
        return mail(x, y)
    if region == "body":
        return "I" if y == 2 else ("G" if x % 3 == 1 else "l") if y == 8 else ("b" if (x + y) % 3 == 0 else "c")
    return "d" if y == 14 else cloth(x, y)


def lorikion(x, y, region):
    if region == "sleeve":
        return mail(x, y) if y <= 4 else cloth(x, y)
    if region == "body":
        return "c" if y == 2 else ("G" if x % 3 == 1 else "l") if y == 8 else mail(x, y)
    return mail(x, y) if y <= 12 else "."


def scale_coat(x, y, region):
    if region == "sleeve":
        return ("r" if y == 2 else "r") if y <= 2 else cloth(x, y, 2)
    if region == "body":
        return "c" if y == 2 else ("G" if x % 3 == 1 else "l") if y == 8 else scale(x, y)
    return strips(x) if y <= 11 else "."


def kabadion(x, y, region):
    if region == "body" and y == 8:
        return "l"
    if region == "body" and x in (7, 8):
        return "a" if x == 7 else "c"
    return "d" if y == 14 else ("b" if x % 2 == 0 else "c")


def varangian(x, y, region):
    if region == "sleeve":
        return mail(x, y)
    if region == "body":
        return ("G" if x % 3 == 1 else "l") if y == 8 else "c" if x in (5, 6) else mail(x, y)
    return mail(x, y)


def strategos(x, y, region):
    if region == "sleeve":
        return lamellae(x, y, True) if y <= 3 else "c"
    if region == "body":
        if y in (4, 5):
            return "d" if y == 4 else "b"
        return ("G" if x % 3 == 1 else "y") if y == 8 else "c" if y == 2 else lamellae(x, y, True)
    return strips(x) if y <= 11 else "."


HORSE = [
    "..........oooo..",
    ".........oXXXXo.",
    "........oXXXXXo.",
    ".......oXXXXoo..",
    "......oXXXXo....",
    "..oooooXXXXo....",
    ".oXXXXXXXXXo....",
    "oXXXXXXXXXXXo...",
    "oXXXXXXXXXXXo...",
    "oXXXXXXXXXXXo...",
    "oXXXXXXXXXXXo...",
    ".oXXoooooXXo....",
    ".oXXo...oXXo....",
    ".oXXo...oXXo....",
    "..oo.....oo.....",
    "................",
]


def horse_icon(fn):
    out = []
    for y, row in enumerate(HORSE):
        out.append("".join(fn(x, y) if ch == "X" else ch for x, ch in enumerate(row)))
    return out


BYZANTINE = {
    "kassis": KASSIS_TOP + PADDED_GUARD,
    "kataphract_helmet": [
        "......dcd.......",
        "......ocdo......",
        ".....oojjoo.....",
        "....oJjyJjjo....",
        "...oJJjyjjjjo...",
        "..oJjjjyjjjjjo..",
        "..oJyjjyjjyjjo..",
        "..ogGgGgGgGgGo..",
    ] + VEIL + [
        "................",
    ],
    "varangian_helmet": [
        ".......oo.......",
        "......oJjo......",
        "......oJjo......",
        ".....oJJjjo.....",
        ".....oJjjjo.....",
        "....oJJjjjjo....",
        "...oJJJjjjjjo...",
        "..oJJjjjjjjjjo..",
        "..ojJjJjJjJjJo..",
    ] + AVENTAIL,
    "strategos_helmet": [
        "................",
        "...occcccccco...",
        "......oooo......",
        "....ooGyGgoo....",
        "...oGGgygggGo...",
        "..oGgggygggggo..",
        "..oGyggyggyggo..",
        "..oGgyGgyGgyGo..",
        "..ohGhGhGhGhGo..",
    ] + PADDED_GUARD,
    "phakeolion": [
        "................",
        "................",
        "................",
        "................",
        "......oooo......",
        "....oodccdoo....",
        "...odcddccddo...",
        "..odccddccddco..",
        "..occddccddcco..",
        "..odccddccddco..",
        "..oooooooooooo..",
        "...ocdo.........",
        "...odco.........",
        "...oooo.........",
        "................",
        "................",
    ],
    "pilos": [
        "................",
        "................",
        "................",
        "......oooo......",
        "....oodddcoo....",
        "...odddcccbbo...",
        "...oddccccbbo...",
        "..odccccccccbo..",
        "..occccccccbbo..",
        ".obbbbbbbbbbbbo.",
        ".odddddddddddco.",
        "..oooooooooooo..",
        "................",
        "................",
        "................",
        "................",
    ],
    "klibanion": coat(klibanion),
    "kataphract_armour": coat(kataphract),
    "epilorikion": coat(epilorikion),
    "lorikion": coat(lorikion),
    "scale_armour": coat(scale_coat),
    "kabadion": coat(kabadion),
    "varangian_hauberk": coat(varangian, {(4, 9): "c", (4, 10): "c", (3, 11): "c", (3, 12): "c"}),
    "strategos_klibanion": coat(strategos),
    "kremasmata": trousers(lambda x, y, r: ("d" if y == 1 else "b") if r == "waist" else ("b" if x % 2 == 0 else "c") if y <= 7
                           else "a" if y == 13 else cloth(x, y)),
    "lamellar_kremasmata": trousers(lambda x, y, r: ("d" if y == 1 else "b") if r == "waist" else lamellae(x, y) if y <= 9
                                    else cloth(x, y)),
    "byzantine_hose": trousers(lambda x, y, r: ("d" if y == 1 else "b") if r == "waist" else "a" if y == 13 else cloth(x, y, 4)),
    "mail_chausses": trousers(lambda x, y, r: ("d" if y == 1 else "b") if r == "waist" else mail(x, y)),
    "tzangia": boots(lambda x, y, r: "c" if y == 2 else ("L" if x == 4 and y % 2 else "3" if x % 3 else "4") if r == "shaft" else "2"),
    "podopsella": boots(lambda x, y, r: ("l" if y in (2, 9) else "J" if x % 2 == 0 else "j") if r == "shaft" else "2"),
    "cataphract_barding": horse_icon(lambda x, y: ("J" if x % 2 == 0 else "j") if y <= 4 else "r" if y == 10 else lamellae(x, y)),
    "cataphract_half_barding": horse_icon(lambda x, y: ("J" if x % 2 == 0 else "j") if y <= 4 else scale(x, y) if (y <= 6 or x >= 8)
                                          else "r" if y == 10 and x >= 8 else "."),
    "felt_barding": horse_icon(lambda x, y: "." if y <= 2 else ("d" if x % 2 else "a") if y == 10 else
                               "b" if (x + y) % 4 == 0 or (x - y) % 4 == 0 else "c"),
}

MORE = dict(SELJUK, **INUIT)
MORE.update(BYZANTINE)
