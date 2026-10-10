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

The full samurai set gives 17 armour and 1.0 toughness, beside Epic Knights' 14th-century knight (17, 1.25). The
ashigaru pieces stand beside the kettle hat and the brigandine.

**Dyeing.** The samurai pieces' silk lacing (*odoshi*) is dyeable, scarlet by default. The ashigaru's lacquer is
dyeable (clan colours), black by default. Dye them like leather armour.

**Recipes** (Epic Knights' parts):
* kabuto: steel ingots, steel plates, woolen fabric, lamellar rows;
* menpō kabuto: a kabuto, a steel plate, red dye;
* dō: lamellar rows, string, leather strips;
* haidate: lamellar rows, woolen fabric, string;
* suneate: steel plates, woolen fabric, leather strips;
* jingasa: steel plates, a steel ingot, string;
* okegawa-dō: steel plates, string, leather strips.

## Building

```
../gradlew build          # build/libs/cultures-armory-<version>.jar
```

`libs/` must hold the Epic Knights and Architectury jars (compile-only).

The models (Java), armour textures, icons, item models, recipes and translations are generated from
`tools/japan.py` (the armour) and `tools/icons.py` (the 16x16 icons):

```
python3 tools/build_assets.py      # needs Pillow
```

* `tools/preview`: a three.js preview that renders the pieces on a player with Minecraft's exact box texture mapping.
  Run `node tools/preview/shoot.js out.png kabuto,samurai_do,samurai_haidate,samurai_suneate [walk]` (Playwright).
* `tools/client-test`: a datapack that sets armour stands up in a flat world, for `../gradlew runClient`, which opens
  the world `run/saves/armorytest`.
