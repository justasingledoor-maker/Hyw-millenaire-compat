# Cultures Armory (an Epic Knights add-on)

The armour of other cultures, made the Epic Knights way. Each piece is an Epic Knights armour type with its own 3D model,
dyeable like Epic Knights' cloth armour. The pieces sit in Epic Knights' armour tab and repair with steel ingots.
Separate from HywMill: it needs only NeoForge 1.21.1, Epic Knights (10.12 or later) and Architectury.

## Japan

The Sengoku period's *tōsei gusoku* (the samurai's "modern equipment") and the ashigaru's munitions armour.

| Item | Slot | Defence | Toughness | Durability | What it is |
|---|---|---|---|---|---|
| Samurai Kabuto | head | 3 | 1.0 | 260 | Riveted ridged bowl (*suji-bachi*), flared laced neck guard (*shikoro*), turn-backs (*fukigaeshi*), gilt *kuwagata* crest |
| Samurai Kabuto with Menpō | head | 3 | 1.25 | 280 | The same, with a red-lacquered face mask (moustache, teeth) and a laced throat guard |
| Samurai Dō | chest | 7 | 1.0 | 320 | Laced lamellar cuirass with breastplate, shoulder boards (*sode*), armoured sleeves (*kote*) and tassets (*kusazuri*) |
| Samurai Haidate | legs | 5 | 1.0 | 280 | Laced thigh apron over indigo *hakama* |
| Samurai Suneate | feet | 2 | 1.0 | 210 | Lacquered splint shin guards with knee plates, over *tabi* in straw *waraji* |
| Ashigaru Jingasa | head | 2 | 0.25 | 190 | Lacquered conical hat, red inside, gilt mon on the front |
| Ashigaru Okegawa-dō | chest | 5 | 0.25 | 250 | Riveted horizontal plates, the clan's gilt mon, sparse-laced tassets, splinted forearm guards |

**1.1: more of Japan.**

| Item | Slot | Defence | Toughness | Durability | What it is |
|---|---|---|---|---|---|
| Ashigaru Hachigane | head | 1 | 0 | 120 | Cloth headband with an iron forehead plate |
| Ashigaru Tatami-dō | chest | 4 | 0 | 200 | Folding armour of small lacquered plates (*karuta*) on mail |
| Ashigaru Hakama | legs | 2 | 0 | 120 | Pleated cloth trousers |
| Ashigaru Kyahan | feet | 1 | 0 | 110 | Cloth gaiters tied with white cords, straw sandals |
| Zunari Kabuto | head | 2 | 0.5 | 230 | Plain head-shaped bowl, short laced neck guard |
| Samurai Dō-maru | chest | 6 | 0.5 | 290 | Wrap-round laced lamellar, white-laced shoulders (*kata-jiro*), small *sode* |
| Hoshi Kabuto | head | 3 | 1.0 | 270 | The great helmet: riveted bowl, wide neck guard, leather turn-backs, great *kuwagata* |
| Ō-yoroi | chest | 8 | 1.25 | 340 | The mounted archer's great armour: stencilled leather front, great shoulder boards, brocade sleeve |
| Mikazuki Kabuto | head | 3 | 1.25 | 280 | A commander's kabuto with a gilt crescent crest |
| Sōhei Zukin | head | 1 | 0 | 100 | The warrior monk's hood, wrapped over the head and face |

The full samurai set gives 17 armour and 1.0 toughness, beside Epic Knights' 14th-century knight (17, 1.25). The
ashigaru pieces stand beside the kettle hat, gambeson and brigandine; the dō-maru beside lamellar; the ō-yoroi with the
hoshi kabuto is the heaviest Japanese armour (with haidate and suneate: 18 armour).

**Dyeing.** Every piece is dyeable like leather armour (and in Minecraft's `dyeable` tag, so HywMill's village livery
dyes it). The samurai pieces' silk lacing (*odoshi*) is scarlet by default, the dō-maru's dark blue, the ō-yoroi's
purple, the mikazuki's black; the ashigaru's lacquer black and their cloth indigo or white.

**Weapons** (steel, Epic Knights weapon items: its display, blocking and two-handed rules):

| Weapon | Like Epic Knights' | Damage | Speed | What it is |
|---|---|---|---|---|
| Wakizashi | shortsword | 3.0 | 1.65 | The short companion sword |
| Katana | bastard sword | 4.0 | 1.5 | Curved two-handed sword; blocks |
| Nodachi | claymore | 5.2 | 1.2 | The great field sword; blocks |
| Naginata | Lochaber axe | 5.5 | 1.1 | Curved blade on a long red-lacquered shaft; reach; blocks |
| Yari | pike | 3.0 | 1.2 | Straight spear on a black shaft; long reach |
| Jūmonji Yari | ahlspiess | 3.2 | 1.15 | Cross-bladed spear; armour piercing; blocks |
| Kanabō | heavy mace | 5.0 | 1.0 | Studded iron club; armour piercing |
| Yumi | longbow | | | The tall asymmetric bow (Epic Knights bow: 400 uses, draw 34, power 4.4) |

With Better Combat, the weapons use its katana, sword, claymore, glaive, spear and hammer movesets.

**Recipes** use Epic Knights' parts (steel ingots and plates, lamellar rows, leather strips, woolen fabric, hilts, poles);
see `src/main/resources/data/culturesarmory/recipe`. The menpō and mikazuki kabuto are made from a kabuto; the katana
from a wakizashi, the nodachi from a katana, the naginata from a wakizashi on a pole; the yumi from bamboo.

## Building

```
../gradlew build          # build/libs/cultures-armory-<version>.jar
```

`libs/` must hold the Epic Knights and Architectury jars (compile-only).

The models (Java), armour textures, icons, item models, recipes, tags and translations are generated from
`tools/japan.py` (the armour), `tools/weapons.py` (the weapon sprites and stats) and `tools/icons.py` (the 16x16 icons):

```
python3 tools/build_assets.py      # needs Pillow
```

* `tools/preview`: a three.js preview that renders the pieces on a player with Minecraft's exact box texture mapping.
  Run `node tools/preview/shoot.js out.png kabuto,samurai_do,samurai_haidate,samurai_suneate [walk]` (Playwright).
* `tools/client-test`: a datapack that sets armour stands up in a flat world, for `../gradlew runClient`, which opens
  the world `run/saves/armorytest`.
