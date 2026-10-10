"""
The item icons (16x16), drawn as character grids in the armours' palette. Dyeable pixels (a-d) go to the tinted base
layer, the rest to the overlay, as in Epic Knights' dyeable items.
"""
import os

from PIL import Image

from japan import B, C, D, G, L, M, R, S, W, WHITE_CORD, ARMOURS

OUT = (24, 21, 26)
KEY = {
    "o": (OUT, False),
    "1": (L[1], False), "2": (L[2], False), "3": (L[3], False), "4": (L[4], False),
    "y": (G[1], False), "g": (G[2], False), "G": (G[3], False), "h": (G[4], False),
    "a": (D[1], True), "b": (D[2], True), "c": (D[3], True), "d": (D[4], True),
    "n": (C[1], False), "N": (C[2], False), "m": (C[3], False),
    "r": (R[1], False), "R": (R[2], False), "e": (R[3], False), "E": (R[4], False),
    "w": (M[2], False), "W": (M[1], False), "v": (WHITE_CORD, False),
    "s": (W[0], False), "S": (W[1], False),
    "x": (S[2], False), "X": (S[3], False),
    "l": (B[1], False), "L": (B[2], False),
}

ICONS = {
    "kabuto": [
        "..h..........h..",
        "..Gh........hG..",
        "...Gh......hG...",
        "....Gh.oo.hG....",
        "....ohG33Gho....",
        "...o3424G4243o..",
        "..o34242G42423o.",
        "..ogGGGGGGGGGGo.",
        ".oGo131313131oGo",
        "oGgoccbccbccboGg",
        ".oo31313131313o.",
        ".ocbccbccbccbco.",
        "o31313131313131o",
        "ocbccbccbccbccbo",
        "o1v1v1v1v1v1v1vo",
        ".oooooooooooooo.",
    ],
    "menpo_kabuto": [
        "..h..........h..",
        "..Gh........hG..",
        "...Gh......hG...",
        "....Gh.oo.hG....",
        "....ohG33Gho....",
        "...o3424G4243o..",
        "..o34242G42423o.",
        "..ogGGGGGGGGGGo.",
        ".oGo3oeRReo3oGo.",
        "oGgoreREEReroGgo",
        "o3ocwwWrrWwwco3o",
        "o1ocrRooooRrco1o",
        "o3ocrrRwwRrrco3o",
        "ocbccbccbccbccbo",
        "o1v1v1v1v1v1v1vo",
        ".oooooooooooooo.",
    ],
    "samurai_do": [
        "..ooo......ooo..",
        ".o3G3o....o3G3o.",
        "o1313o2oo2o3131o",
        "occbco4334ocbcco",
        "o1313o2GG2o3131o",
        "occbco3131ocbcco",
        "o1313occbco3131o",
        "ovvvvo1313ovvvvo",
        "....occbcbco....",
        "....o131313o....",
        "....occbccbo....",
        "...o13131313o...",
        "...occbccbcco...",
        "..o1313131313o..",
        "..o1v1v1v1v1vo..",
        "..oooooooooooo..",
    ],
    "samurai_haidate": [
        "..oooooooooooo..",
        "..ommmmmmmmmmo..",
        "..o1313oo3131o..",
        "..occbcoocbcco..",
        "..o1313oo3131o..",
        "..occbcoocbcco..",
        "..o1313oo3131o..",
        "..occbcoocbcco..",
        "..ov1v1oo1v1vo..",
        "..oNnNnoonNnNo..",
        "..onNnNoonNnNo..",
        "..oNnNnoonNnNo..",
        "..onNnNoonNnNo..",
        "..ommmmoommmmo..",
        "..oooooooooooo..",
        "................",
    ],
    "samurai_suneate": [
        "................",
        "..oooo....oooo..",
        "..occo....occo..",
        "..o2Go....oG2o..",
        "..o4no....on4o..",
        "..o3no....on3o..",
        "..o4no....on4o..",
        "..o3no....on3o..",
        "..o3no....on3o..",
        "..occo....occo..",
        "..owwoo..oowwo..",
        ".owWwwo..owwWwo.",
        ".oSsSso..osSsSo.",
        "..oooo....oooo..",
        "................",
        "................",
    ],
    "jingasa": [
        "................",
        "................",
        "................",
        ".......oo.......",
        "......oGGo......",
        ".....ocddco.....",
        "....ocdbbdco....",
        "...ocdbbbbdco...",
        "..ocdbdGGdbdco..",
        ".ocbdbdbbdbdbco.",
        "oddddddddddddddo",
        "oRRRRRRRRRRRRRRo",
        ".oooooooooooooo.",
        "......l..l......",
        ".......ll.......",
        "................",
    ],
    "okegawa_do": [
        "..ooo......ooo..",
        ".oLlLo....oLlLo.",
        "odddco....ocdddo",
        "obbbbo....obbbbo",
        "oxddddoooodddxdo",
        "obbbbbbGbbbbbbbo",
        "odxdddGhGdddxddo",
        "obbbbbbGbbbbbbbo",
        "oddxddddddddxddo",
        ".obbbbbbbbbbbbo.",
        ".oddmdddmdddmdo.",
        ".obbbbbbbbbbbbo.",
        ".oddmdddmdddmdo.",
        ".obbbbbbbbbbbbo.",
        ".oooooooooooooo.",
        "................",
    ],
}


def layers(grid):
    base = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    over = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(grid):
        assert len(row) == 16, (y, row)
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            rgb, dye = KEY[ch]
            if dye:
                base.putpixel((x, y), (rgb[0], rgb[0], rgb[0], 255))
            else:
                over.putpixel((x, y), rgb + (255,))
    return base, over


def composite(grid, tint):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(grid):
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            rgb, dye = KEY[ch]
            if dye:
                g = rgb[0] / 255.0
                rgb = (int(tint[0] * g), int(tint[1] * g), int(tint[2] * g))
            img.putpixel((x, y), rgb + (255,))
    return img


def write_all(item_dir, preview_dir):
    os.makedirs(item_dir, exist_ok=True)
    os.makedirs(preview_dir, exist_ok=True)
    sheet = Image.new("RGBA", (len(ARMOURS) * 136, 136), (96, 110, 96, 255))
    for n, (item_id, _, _, _, dye, _) in enumerate(ARMOURS):
        assert len(ICONS[item_id]) == 16, item_id
        base, over = layers(ICONS[item_id])
        base.save(os.path.join(item_dir, f"{item_id}.png"))
        over.save(os.path.join(item_dir, f"{item_id}_overlay.png"))
        tint = ((dye >> 16) & 255, (dye >> 8) & 255, dye & 255)
        sheet.paste(composite(ICONS[item_id], tint).resize((128, 128), Image.NEAREST), (n * 136 + 4, 4))
    sheet.save(os.path.join(preview_dir, "icons.png"))
