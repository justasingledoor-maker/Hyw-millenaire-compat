"""
The item icons (16x16), drawn as character grids in the armours' palette. Dyeable pixels (a-d) go to the tinted base
layer, the rest to the overlay, as in Epic Knights' dyeable items.
"""
import os

from PIL import Image

from japan import B, C, D, G, L, LEATHER, M, R, S, W, WHITE_CORD

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
    "z": (S[4], False), "Z": (S[1], False),
    "k": (LEATHER[0], False), "K": (LEATHER[2], False),
    "f": ((196, 144, 112), False),
    # 1.2: Seljuk and Inuit
    "i": ((70, 70, 74), False), "I": ((128, 128, 132), False),          # mail
    "j": ((116, 118, 120), False), "J": ((184, 186, 188), False), "p": ((214, 216, 218), False),   # steel
    "u": ((74, 54, 36), False), "U": ((132, 104, 74), False),          # sable fur
    "t": ((116, 88, 58), False), "T": ((178, 148, 106), False), "H": ((200, 192, 176), False),   # caribou, pale fur
    "A": ((64, 60, 54), False), "B": ((118, 110, 96), False),          # sealskin
    "C": ((180, 168, 136), False), "F": ((230, 222, 196), False),      # ivory, bone
    "M": ((108, 74, 42), False), "O": ((166, 124, 78), False),         # wood
    "P": ((102, 96, 88), False), "V": ((186, 180, 170), False),        # wolf fur
    "Y": ((84, 62, 46), False), "5": ((138, 108, 80), False),          # walrus hide
    "6": ((54, 92, 140), False), "7": ((150, 60, 40), False),          # blue bead, red ochre
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
    "ashigaru_hachigane": [
        "................",
        "................",
        "................",
        "................",
        "....oooooooo....",
        "..oocbbbbbbcoo..",
        ".obcoooooooocbo.",
        "obo..........obo",
        "odoooooooooooodo",
        "odddozzzzzzodddo",
        "occdoXXXXXXodcco",
        "obcco1xxxx1occbo",
        ".ooccooooooccoo.",
        "...oooooooooobo.",
        "............obco",
        ".............oo.",
    ],
    "tatami_do": [
        "..ooo......ooo..",
        ".oLlLo....oLlLo.",
        "odcd1o....o1dcdo",
        "ox1x1o....o1x1xo",
        "o1dcdoooooodcd1o",
        "ox1x1x1x1x1x1x1o",
        "odcd1dcd1dcd1dco",
        "ox1x1x1x1x1x1x1o",
        "o1dcd1dcd1dcd1do",
        ".o1x1x1x1x1x1xo.",
        ".odcd1dcd1dcd1o.",
        ".ox1x1x1x1x1x1o.",
        ".oooooooooooooo.",
        "..odcd1oo1dcdo..",
        "..obbbboobbbbo..",
        "..oooooooooooo..",
    ],
    "ashigaru_hakama": [
        "................",
        "..oooooooooooo..",
        "..odddddddddddo.",
        "..oaaaaaaaaaaao.",
        "..ocbccbccbccco.",
        "..ocbccbccbccco.",
        "..ocbccboocbcco.",
        ".ocbccbo.ocbccbo",
        ".ocbccbo.ocbccbo",
        ".ocbccbo.ocbccbo",
        "ocbccbco.occbccbo"[:16],
        "ocbccbo...ocbcco",
        "oaaaaao...oaaaao",
        "ooooooo...oooooo",
        "................",
        "................",
    ],
    "ashigaru_kyahan": [
        "................",
        "..oooo....oooo..",
        "..oddo....oddo..",
        "..oWwo....owWo..",
        "..occo....occo..",
        "..obco....ocbo..",
        "..owWo....oWwo..",
        "..occo....occo..",
        "..obco....ocbo..",
        "..occo....occo..",
        "..owwoo..oowwo..",
        ".owWwwo..owwWwo.",
        ".oSsSso..osSsSo.",
        "..oooo....oooo..",
        "................",
        "................",
    ],
    "zunari_kabuto": [
        "................",
        "................",
        "................",
        "......oooo......",
        "....oo4433oo....",
        "...o34433222o...",
        "..o3443322221o..",
        "..o3443322221o..",
        ".o44444444443o..",
        "o3o1313131313o3o",
        "o2occbccbccbco2o",
        ".oo1313131313oo.",
        ".occbccbccbccbo.",
        "o13131313131313o",
        "ov1v1v1v1v1v1v1o",
        ".oooooooooooooo.",
    ],
    "do_maru": [
        "..ooo......ooo..",
        ".o343o....o343o.",
        "ovvWvo....ovWvvo",
        "o1313o4334o3131o",
        "occbco3223ocbcco",
        "o1313ovvWvo3131o",
        "occbco1313ocbcco",
        "ovvvvovWvvovvvvo",
        "....occbcbco....",
        "....o131313o....",
        "....occbccbo....",
        "...o13131313o...",
        "...occbccbcco...",
        "..o1313131313o..",
        "..o1v1v1v1v1vo..",
        "..oooooooooooo..",
    ],
    "hoshi_kabuto": [
        ".h............h.",
        ".Gh..........hG.",
        "..Gh........hG..",
        "...Gh.oooo.hG...",
        "...oGhX33XhGo...",
        "..o3X3X33X3X3o..",
        ".o3X3X3X3X3X3Xo.",
        ".ogGGGGGGGGGGGgo",
        "okKo131313131oKk",
        "okkoccbccbccbokk",
        "oGooo313131313oG",
        "o3ocbccbccbccbco",
        "o131313131313131",
        "ocbccbccbccbccbo",
        "o1v1v1v1v1v1v1vo",
        ".oooooooooooooo.",
    ],
    "o_yoroi": [
        ".oooo......oooo.",
        "oGgGgo....oGgGgo",
        "okNkko....okkNko",
        "o1313oooooo3131o",
        "occbcoGkkkkocbco"[:16],
        "o1313oGkNkkG3131"[:16],
        "occbcoGkkkkGcbco"[:16],
        "o1313oGkkNkG3131"[:16],
        "occbcoGkkkkGcbco"[:16],
        "ov1v1oGGGGGGov1v"[:16],
        "oooooo131313oooo",
        "...occbccbccbo..",
        "...o131313131o..",
        "...occbccbccbo..",
        "...o1v1v1v1v1o..",
        "...ooooooooooo..",
    ],
    "mikazuki_kabuto": [
        "................",
        "Gh............hG",
        ".Gh..........hG.",
        "..GGh......hGG..",
        "....oGGhhGGo....",
        "....ohG33Gho....",
        "...o3424G4243o..",
        "..o34242G42423o.",
        "..ogGGGGGGGGGGo.",
        ".oGo131313131oGo",
        "oGgoccbccbccboGg",
        ".oo31313131313o.",
        "o31313131313131o",
        "ocbccbccbccbccbo",
        "o1v1v1v1v1v1v1vo",
        ".oooooooooooooo.",
    ],
    "sohei_zukin": [
        "................",
        ".....oooooo.....",
        "...oodddddcoo...",
        "..odddddcccbbo..",
        ".odddddcccccbbo.",
        ".oddddccccccbbo.",
        ".odcccccccccbbo.",
        ".ooooooooooobbo.",
        ".offWnffWnffobo.",
        ".ooooooooooobbo.",
        ".occcccccccbbbo.",
        "..occcccccbbbbo.",
        "..obcccccbbbbbo.",
        ".obbbbbbbbbbbbbo",
        ".ooooooooooooooo",
        "................",
    ],
}

from icons_more import MORE  # noqa: E402

ICONS.update(MORE)


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


def write_all(item_dir, preview_dir, ARMOURS):
    os.makedirs(item_dir, exist_ok=True)
    os.makedirs(preview_dir, exist_ok=True)
    sheet = Image.new("RGBA", (len(ARMOURS) * 136, 136), (96, 110, 96, 255))
    for n, (item_id, factory, slot, _, dye, _) in enumerate(ARMOURS):
        tint = ((dye >> 16) & 255, (dye >> 8) & 255, dye & 255)
        if item_id in ICONS:
            assert len(ICONS[item_id]) == 16, item_id
            base, over = layers(ICONS[item_id])
            shown = composite(ICONS[item_id], tint)
        elif factory is not None:
            # 1.5: drawn from the piece itself (autoicon)
            import autoicon
            from armorgen import paint_model
            model = factory()
            model.pack()
            base, over = autoicon.render(model, paint_model(model), slot)
            shown = autoicon.composite(base, over, tint)
        else:
            print("no icon yet:", item_id)
            continue
        base.save(os.path.join(item_dir, f"{item_id}.png"))
        over.save(os.path.join(item_dir, f"{item_id}_overlay.png"))
        sheet.paste(shown.resize((128, 128), Image.NEAREST), (n * 136 + 4, 4))
    sheet.save(os.path.join(preview_dir, "icons.png"))
