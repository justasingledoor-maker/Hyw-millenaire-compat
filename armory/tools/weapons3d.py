"""
Weapons in 3D, as Epic Knights makes its lances and maces: an item model of boxes (Minecraft's block-model elements) on a
texture of its own, every face given its own area of the texture at PX texels per unit (Epic Knights' lances: 2), so
it is painted to its size like the armour.

  steel_kontos:      the kataphraktos' cavalry lance (kontos): an ash shaft, a leather grip, a leaf-shaped head on a
                     socket, a butt spike, and a red bandon pennon with a gold cross; an Epic Knights lance (couched on
                     horseback, raised otherwise), with its display transforms
  steel_bardoukion:  the heavy mace of the Praecepta Militaria: eight flanges round an iron head, a knob and a grip on an
                     iron-shod haft, with Epic Knights' heavy mace transforms
"""
from PIL import Image

from japan import B, G
from seljuk import STEEL
from byzantine import LEATHER_RED

PX = 3
WOOD = [(92, 64, 38), (118, 84, 52), (142, 104, 66), (166, 126, 84)]
RED = [(96, 20, 22), (134, 30, 32), (168, 44, 42), (196, 70, 60)]

FACES = ("north", "south", "east", "west", "up", "down")


class Box:
    def __init__(self, frm, to, mat, rot=None):
        """mat(face, x, y, w, h) -> rgb or None (cut away); rot: (angle, axis, origin) as in a block model."""
        self.frm, self.to, self.mat, self.rot = frm, to, mat, rot
        self.uv = {}

    def face_size(self, face):
        dx, dy, dz = (self.to[i] - self.frm[i] for i in range(3))
        w, h = {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy), "up": (dx, dz), "down": (dx, dz)}[face]
        return max(1, round(w * PX)) if w > 1e-6 else 0, max(1, round(h * PX)) if h > 1e-6 else 0


class Item3D:
    def __init__(self, boxes, size=128):
        self.boxes, self.size = boxes, size

    def build(self):
        """Packs every face onto the texture (shelves, tallest first), paints it; returns (image, elements)."""
        faces = [(b, f) + b.face_size(f) for b in self.boxes for f in FACES]
        faces = [x for x in faces if x[2] and x[3]]
        while True:                                          # the smallest square texture they fit on
            x = y = shelf = 0
            placed = {}
            for b, f, w, h in sorted(faces, key=lambda q: (-q[3], -q[2])):
                if x + w > self.size:
                    x, y, shelf = 0, y + shelf, 0
                placed[(id(b), f)] = (x, y, w, h)
                x += w
                shelf = max(shelf, h)
            if y + shelf <= self.size:
                break
            self.size *= 2
        img = Image.new("RGBA", (self.size, self.size), (0, 0, 0, 0))
        for b, f, w, h in faces:
            x, y, w, h = placed[(id(b), f)]
            b.uv[f] = (x, y, w, h)
            for i in range(w):
                for j in range(h):
                    rgb = b.mat(f, i, j, w, h)
                    if rgb is not None:
                        img.putpixel((x + i, y + j), tuple(rgb) + (255,))
        k = 16 / self.size
        elements = []
        for b in self.boxes:
            e = {"from": list(b.frm), "to": list(b.to), "faces": {}}
            if b.rot:
                e["rotation"] = {"angle": b.rot[0], "axis": b.rot[1], "origin": list(b.rot[2])}
            for f, (u, v, w, h) in b.uv.items():
                e["faces"][f] = {"uv": [round(u * k, 4), round(v * k, 4), round((u + w) * k, 4), round((v + h) * k, 4)], "texture": "#0"}
            elements.append(e)
        return img, elements


# ---------------------------------------------------------------------- materials (face, x, y, w, h) -> rgb

def wood(face, x, y, w, h):
    """Ash, the grain along the haft."""
    if face in ("up", "down"):
        return WOOD[2] if (x + y) % 2 else WOOD[1]
    return WOOD[3] if x == 0 else WOOD[0] if x == w - 1 else (WOOD[2] if (y * 7 + x * 3) % 9 else WOOD[1])


def steel(face, x, y, w, h):
    if face in ("up", "down"):
        return STEEL[3]
    return STEEL[4] if x == 0 else STEEL[1] if x == w - 1 else STEEL[3] if x < w // 2 else STEEL[2]


def blade(face, x, y, w, h):
    """The blade: a bright edge each side, the midrib darker."""
    if face in ("up", "down"):
        return STEEL[2]
    if x == 0 or x == w - 1:
        return STEEL[4]
    return STEEL[1] if face in ("north", "south") and abs(x - (w - 1) / 2) < 1 else STEEL[3]


def gilt(face, x, y, w, h):
    return G[4] if x == 0 else G[1] if x == w - 1 else G[3] if (x + y) % 3 else G[2]


def grip(face, x, y, w, h):
    """Red leather wound round the haft."""
    if face in ("up", "down"):
        return LEATHER_RED[1]
    return LEATHER_RED[0] if (x + y) % 4 == 0 else LEATHER_RED[2] if x < w - 1 else LEATHER_RED[1]


def pennon(face, x, y, w, h):
    """The bandon: red, a gold border and cross, a swallowtail cut into its fly (x away from the staff)."""
    if face not in ("east", "west"):
        return RED[1]
    if face == "east":
        x = w - 1 - x                                       # both sides: the staff at x = 0
    tail = w * 0.72
    if x > tail and abs(y - (h - 1) / 2) < (x - tail) * (h / 2) / (w - tail):
        return None
    if y in (0, h - 1) or x == w - 1:
        return G[2]
    cx, cy = w * 0.36, (h - 1) / 2
    if (abs(x - cx) < 1 and 2 <= y <= h - 3) or (abs(y - cy) < 1 and cx - 4 <= x <= cx + 4):
        return G[3]
    return RED[2] if (x + 2 * y) % 7 else RED[1]


def iron(face, x, y, w, h):
    if face in ("up", "down"):
        return STEEL[2]
    return STEEL[3] if x == 0 else STEEL[0] if x == w - 1 else STEEL[2] if (y // 2) % 2 else STEEL[1]


def flange(face, x, y, w, h):
    """A flange: lit along its outer edge, its tip and foot bevelled."""
    if face in ("up", "down"):
        return STEEL[3]
    if face in ("north", "south", "east", "west") and min(w, h) <= PX:
        return STEEL[3]
    return STEEL[4] if y == 0 else STEEL[1] if y == h - 1 else STEEL[3] if x < w // 2 else STEEL[2]


# ---------------------------------------------------------------------- the weapons

def kontos():
    c = 8.0                                                  # Epic Knights' lances stand on x = z = 8

    def sq(r, y0, y1, mat):
        return Box((c - r, y0, c - r), (c + r, y1, c + r), mat)
    return Item3D([
        sq(0.4, -16, -13.5, steel),                          # the butt spike
        sq(0.75, -13.5, -12.5, gilt),
        sq(0.6, -12.5, -6, wood),
        sq(0.8, -6, 1, grip),
        sq(0.6, 1, 24.5, wood),
        sq(0.75, 24.5, 26, gilt),                            # the socket's ring
        sq(0.55, 26, 27.5, steel),                           # the socket
        Box((c - 0.35, 27.5, c - 1.3), (c + 0.35, 29.5, c + 1.3), blade),     # the leaf-shaped head
        Box((c - 0.3, 29.5, c - 0.85), (c + 0.3, 31, c + 0.85), blade),
        Box((c - 0.25, 31, c - 0.4), (c + 0.25, 32, c + 0.4), blade),
        Box((c - 0.05, 18.5, c + 0.6), (c + 0.05, 23.5, c + 9.6), pennon),   # the bandon, flying back from the staff
    ])


def bardoukion():
    c = 0.5                                                  # Epic Knights' heavy mace stands on x = z = 0.5
    o = (c, 18, c)

    def fl(rot=None, along_x=True):
        if along_x:
            return Box((c - 2.6, 15.5, c - 0.25), (c + 2.6, 20.5, c + 0.25), flange, rot)
        return Box((c - 0.25, 15.5, c - 2.6), (c + 0.25, 20.5, c + 2.6), flange, rot)
    return Item3D([
        Box((c - 0.6, 0, c - 0.6), (c + 0.6, 1.5, c + 0.6), iron),          # the knob
        Box((c - 0.75, 1.5, c - 0.75), (c + 0.75, 6.5, c + 0.75), grip),
        Box((c - 0.55, 6.5, c - 0.55), (c + 0.55, 15, c + 0.55), iron),     # the iron-shod haft
        Box((c - 1.2, 15, c - 1.2), (c + 1.2, 21, c + 1.2), iron),          # the head
        Box((c - 0.6, 21, c - 0.6), (c + 0.6, 22.5, c + 0.6), iron),        # its finial
        fl(), fl(along_x=False), fl((45, "y", o)), fl((45, "y", o), along_x=False),
    ], size=64)


# Epic Knights' display transforms: its lance (and the lance raised) and its heavy mace
LANCE_DISPLAY = {
    "thirdperson_righthand": {"rotation": [-30, 0, 0], "translation": [-1.75, 17.25, -4.75], "scale": [1.5, 1.5, 1.5]},
    "thirdperson_lefthand": {"rotation": [-30, 0, 0], "translation": [-1.75, 17.25, -4.75], "scale": [1.5, 1.5, 1.5]},
    "firstperson_righthand": {"rotation": [-80, 0, 7], "translation": [-5, 3.75, -4.75]},
    "firstperson_lefthand": {"rotation": [-80, 0, 7], "translation": [-5.25, 3.5, -4.75]},
    "ground": {"translation": [0, 7.25, 0], "scale": [0.5, 0.5, 0.5]},
    "gui": {"rotation": [0, 0, -45], "translation": [0.5, 0.5, 0], "scale": [0.5, 0.5, 0.5]},
    "fixed": {"rotation": [0, 0, 45], "translation": [-0.5, 0.5, -0.25], "scale": [0.6, 0.6, 0.6]},
}
LANCE_RAISED_DISPLAY = dict(LANCE_DISPLAY, **{
    "thirdperson_righthand": {"rotation": [39, 0, 0], "translation": [0, 12, 13], "scale": [1.5, 1.5, 1.5]},
    "thirdperson_lefthand": {"rotation": [39, 0, 0], "translation": [0, 12, 13], "scale": [1.5, 1.5, 1.5]},
    "firstperson_righthand": {"rotation": [-20, 0, 7], "translation": [-5, 11, -4.5]},
    "firstperson_lefthand": {"rotation": [-20, 0, 7], "translation": [-5, 11, -4.5]},
})
MACE_DISPLAY = {
    "thirdperson_righthand": {"translation": [8.25, -0.5, 9.25], "scale": [1.1, 1.1, 1.1]},
    "thirdperson_lefthand": {"translation": [-7.5, 0, 8.5]},
    "firstperson_righthand": {"rotation": [-10, 0, 0], "translation": [8, 2.25, 8.25]},
    "firstperson_lefthand": {"rotation": [-10, 0, 0], "translation": [-8, 2.25, 8.25]},
    "ground": {"translation": [7.75, 0, 7.25]},
    "gui": {"rotation": [0, 0, -45], "translation": [3, -7.75, 0]},
    "fixed": {"translation": [7.5, -3, 6.75]},
}

_P, _H, STEEL_INGOT = "#magistuarmory:poles", "#magistuarmory:hilts", "#c:ingots/steel"

# id: (model, kind ("lance": an Epic Knights lance with its weapon type; else a WeaponType.of), stats, display,
#      Better Combat preset and attributes, recipe)
WEAPONS3D = {
    "steel_kontos": (kontos, "lance", "WeaponTypes.GIANT_LANCE", LANCE_DISPLAY,
                     {"parent": "bettercombat:lance", "attributes": {"pose": "", "attack_range": 3.9, "two_handed": False}},
                     (["  m", " w ", "p  "], {"m": STEEL_INGOT, "w": "minecraft:red_wool", "p": _P})),
    "steel_bardoukion": (bardoukion, "weapon", "WeaponType.of(5.4F, 1.0F, 0.0F, 0.08F, 2.6F, 7, true)", MACE_DISPLAY,
                         {"parent": "bettercombat:mace"},
                         (["mim", " i ", " h "], {"m": STEEL_INGOT, "i": "#c:ingots/iron", "h": _H})),
}

NAMES = {"steel_kontos": "Kontos", "steel_bardoukion": "Bardoukion"}


def item_model(modid, item_id, elements, display, overrides=None):
    m = {"textures": {"0": f"{modid}:item/{item_id}", "particle": f"{modid}:item/{item_id}"},
         "elements": elements, "display": display}
    if overrides:
        m["overrides"] = overrides
    return m

