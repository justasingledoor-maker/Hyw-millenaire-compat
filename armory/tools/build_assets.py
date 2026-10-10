"""
Generates the Cultures Armory's models (Java), armour textures, item models, translations and recipes from the
armour specs (japan.py), and the preview data (tools/preview/). Item icons are drawn in icons.py.

    python3 tools/build_assets.py
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))

from armorgen import paint_model, write_json, write_png, write_text  # noqa: E402
import icons  # noqa: E402
import inuit  # noqa: E402
import japan  # noqa: E402
import seljuk  # noqa: E402
import weapons  # noqa: E402

MODID = "culturesarmory"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
JAVA = os.path.join(ROOT, "src/main/java/dev/culturesarmory")
ASSETS = os.path.join(ROOT, "src/main/resources/assets", MODID)
DATA = os.path.join(ROOT, "src/main/resources/data", MODID)
PREVIEW = os.path.join(HERE, "preview/out")

NAMES = {
    "kabuto": "Samurai Kabuto",
    "menpo_kabuto": "Samurai Kabuto with Menpō",
    "samurai_do": "Samurai Dō",
    "samurai_haidate": "Samurai Haidate",
    "samurai_suneate": "Samurai Suneate",
    "jingasa": "Ashigaru Jingasa",
    "okegawa_do": "Ashigaru Okegawa-dō",
    "ashigaru_hachigane": "Ashigaru Hachigane",
    "tatami_do": "Ashigaru Tatami-dō",
    "ashigaru_hakama": "Ashigaru Hakama",
    "ashigaru_kyahan": "Ashigaru Kyahan",
    "zunari_kabuto": "Zunari Kabuto",
    "do_maru": "Samurai Dō-maru",
    "hoshi_kabuto": "Hoshi Kabuto",
    "o_yoroi": "Ō-yoroi",
    "mikazuki_kabuto": "Mikazuki Kabuto",
    "sohei_zukin": "Sōhei Zukin",
}

# Epic Knights' parts: steel plates and ingots, lamellar rows, leather strips, woolen fabric; silk is string.
RECIPES = {
    "kabuto": (["iii", "pfp", "r r"], {"i": "#c:ingots/steel", "p": "#c:plates/steel", "f": "#magistuarmory:woolen_fabrics",
                                        "r": "#magistuarmory:lamellar_rows/steel"}),
    "menpo_kabuto": (["dkd", " p ", "   "], {"k": "culturesarmory:kabuto", "p": "#c:plates/steel", "d": "minecraft:red_dye"}),
    "samurai_do": (["rsr", "rrr", "rlr"], {"r": "#magistuarmory:lamellar_rows/steel", "s": "minecraft:string",
                                            "l": "#magistuarmory:leather_strips"}),
    "samurai_haidate": (["fsf", "r r", "r r"], {"r": "#magistuarmory:lamellar_rows/steel", "s": "minecraft:string",
                                                 "f": "#magistuarmory:woolen_fabrics"}),
    "samurai_suneate": (["p p", "f f", "l l"], {"p": "#c:plates/steel", "f": "#magistuarmory:woolen_fabrics",
                                                 "l": "#magistuarmory:leather_strips"}),
    "jingasa": ([" p ", "pip", "s s"], {"p": "#c:plates/steel", "i": "#c:ingots/steel", "s": "minecraft:string"}),
    "okegawa_do": (["p p", "ppp", "lsl"], {"p": "#c:plates/steel", "s": "minecraft:string", "l": "#magistuarmory:leather_strips"}),
    "ashigaru_hachigane": (["fpf"], {"f": "#magistuarmory:woolen_fabrics", "p": "#c:plates/steel"}),
    "tatami_do": (["l l", "cpc", "plp"], {"l": "#magistuarmory:leather_strips", "c": "#magistuarmory:chainmails/steel", "p": "#c:plates/steel"}),
    "ashigaru_hakama": (["fsf", "f f", "f f"], {"f": "#magistuarmory:woolen_fabrics", "s": "minecraft:string"}),
    "ashigaru_kyahan": (["f f", "w w"], {"f": "#magistuarmory:woolen_fabrics", "w": "minecraft:wheat"}),
    "zunari_kabuto": (["ppp", "p p", "rsr"], {"p": "#c:plates/steel", "r": "#magistuarmory:lamellar_rows/steel", "s": "minecraft:string"}),
    "do_maru": (["rsr", "rrr", "rwr"], {"r": "#magistuarmory:lamellar_rows/steel", "s": "minecraft:string", "w": "minecraft:white_wool"}),
    "hoshi_kabuto": (["ngn", "pfp", "rsr"], {"n": "#c:nuggets/iron", "g": "minecraft:gold_ingot", "p": "#c:plates/steel",
                                             "f": "#magistuarmory:woolen_fabrics", "r": "#magistuarmory:lamellar_rows/steel", "s": "minecraft:string"}),
    "o_yoroi": (["rlr", "rrr", "rgr"], {"r": "#magistuarmory:lamellar_rows/steel", "l": "minecraft:leather", "g": "minecraft:gold_ingot"}),
    "mikazuki_kabuto": (["ggg", " k ", "   "], {"g": "minecraft:gold_ingot", "k": "culturesarmory:kabuto"}),
    "sohei_zukin": (["fff", "f f"], {"f": "minecraft:white_wool"}),
}

# the cultures: Japan (above), the Seljuks, the Inuit
ARMOURS = japan.ARMOURS + seljuk.ARMOURS + inuit.ARMOURS
NAMES.update(seljuk.NAMES)
NAMES.update(inuit.NAMES)
RECIPES.update(seljuk.RECIPES)
RECIPES.update(inuit.RECIPES)


def ingredient(ref):
    return {"tag": ref[1:]} if ref.startswith("#") else {"item": ref}


def camel(s):
    return "".join(p.capitalize() for p in s.split("_"))


def recipe(item_id, pattern, keys):
    write_json({"type": "minecraft:crafting_shaped", "category": "equipment", "pattern": pattern,
                "key": {k: ingredient(v) for k, v in keys.items()},
                "result": {"id": f"{MODID}:{item_id}", "count": 1}},
               os.path.join(DATA, f"recipe/{item_id}.json"))


def write_tags():
    """Vanilla item tags, as Epic Knights tags its own: dyeable (dyeing; HywMill's livery), the armour slots (enchanting),
    swords and bows (enchanting)."""
    def tag(name, ids):
        write_json({"replace": False, "values": [{"id": f"{MODID}:{i}", "required": False} for i in ids]},
                   os.path.join(ROOT, "src/main/resources/data/minecraft/tags/item", f"{name}.json"))
    tag("dyeable", [a[0] for a in ARMOURS])
    for name, slot in (("head_armor", "HELMET"), ("chest_armor", "CHESTPLATE"), ("leg_armor", "LEGGINGS"), ("foot_armor", "BOOTS")):
        tag(name, [a[0] for a in ARMOURS if a[2] == slot])
    tag("swords", list(weapons.WEAPONS))
    tag("bows", [b[0] for b in weapons.BOWS])
    tag("enchantable/bow", [b[0] for b in weapons.BOWS])


def build_weapons(lang):
    """The weapons: sprites, item models (Epic Knights' display transforms and blocking poses), recipes, Better Combat
    presets; returns the Java lines registering them."""
    lines = []
    for item_id, (sprite, ek_model, blocks, stats, preset, reach, (pattern, keys)) in weapons.WEAPONS.items():
        sprite().image().save(os.path.join(ASSETS, f"textures/item/{item_id}.png"))
        model = {"parent": f"magistuarmory:item/{ek_model}", "textures": {"layer0": f"{MODID}:item/{item_id}"}}
        if blocks:
            model["overrides"] = [{"predicate": {"blocking": 1}, "model": f"{MODID}:item/{item_id}_blocking"}]
            write_json({"parent": f"magistuarmory:item/{ek_model}_blocking", "textures": {"layer0": f"{MODID}:item/{item_id}"}},
                       os.path.join(ASSETS, f"models/item/{item_id}_blocking.json"))
        write_json(model, os.path.join(ASSETS, f"models/item/{item_id}.json"))
        attrs = {"parent": f"bettercombat:{preset}"}
        if reach:
            attrs["attributes"] = {"attack_range": reach}
        write_json(attrs, os.path.join(DATA, f"weapon_attributes/{item_id}.json"))
        recipe(item_id, pattern, keys)
        lang[f"item.{MODID}.{item_id}"] = weapons.WEAPON_NAMES[item_id]
        lines.append(f"    public static final RegistrySupplier<MedievalWeaponItem> {item_id.upper()} = INSTANCE.addMedievalWeaponItem("
                     f"\"{item_id}\", new Item.Properties(), ModItemTier.{weapons.TIERS.get(item_id, 'STEEL')},\n            WeaponType.of({stats}));")
    for bow_id, sprite, bow_stats, (pattern, keys) in weapons.BOWS:
        sprite().image().save(os.path.join(ASSETS, f"textures/item/{bow_id}.png"))
        overrides = [{"predicate": {"pulling": 1}, "model": f"{MODID}:item/{bow_id}_pulling_0"},
                     {"predicate": {"pulling": 1, "pull": 0.65}, "model": f"{MODID}:item/{bow_id}_pulling_1"},
                     {"predicate": {"pulling": 1, "pull": 0.9}, "model": f"{MODID}:item/{bow_id}_pulling_2"}]
        write_json({"parent": "magistuarmory:item/longbow", "textures": {"layer0": f"{MODID}:item/{bow_id}"}, "overrides": overrides},
                   os.path.join(ASSETS, f"models/item/{bow_id}.json"))
        for k in range(3):
            sprite(k).image().save(os.path.join(ASSETS, f"textures/item/{bow_id}_pulling_{k}.png"))
            write_json({"parent": "magistuarmory:item/longbow", "textures": {"layer0": f"{MODID}:item/{bow_id}_pulling_{k}"}},
                       os.path.join(ASSETS, f"models/item/{bow_id}_pulling_{k}.json"))
        recipe(bow_id, pattern, keys)
        lang[f"item.{MODID}.{bow_id}"] = weapons.WEAPON_NAMES[bow_id]
        lines.append(f"    public static final RegistrySupplier<Item> {bow_id.upper()} = INSTANCE.addMedievalBowItem(\"{bow_id}\", "
                     f"RangedWeaponType.of({bow_stats}));")
    return lines


def main():
    lang = {"itemGroup.culturesarmory": "Cultures Armory"}
    preview = {}
    model_lines, type_lines, item_lines = [], [], []
    for item_id, factory, slot, (tough, kb, dur, defense, ench), dye, sound in ARMOURS:
        model = factory()
        model.pack()
        canvas = paint_model(model)
        layer = "layer_2" if slot == "LEGGINGS" else "layer_1"
        base, over = canvas.layers(dyeable=True)
        write_png(base, os.path.join(ASSETS, f"textures/models/armor/{item_id}_{layer}.png"))
        write_png(over, os.path.join(ASSETS, f"textures/models/armor/{item_id}_{layer}_overlay.png"))
        write_text(model.java("dev.culturesarmory.client.model"), os.path.join(JAVA, f"client/model/{model.java_name}.java"))
        tint = ((dye >> 16) & 255, (dye >> 8) & 255, dye & 255)
        write_png(canvas.composite(tint), os.path.join(PREVIEW, f"{item_id}.png"))
        preview[item_id] = dict(model.preview_json(), slot=slot, texture=f"{item_id}.png")

        const = item_id.upper()
        model_lines.append(f"    public static final ModelLayerLocation {const} = INSTANCE.addArmorModel(\"{model.location}\", "
                           f"{model.java_name}::createLayer);")
        slots = {"BOOTS": 0, "LEGGINGS": 1, "CHESTPLATE": 2, "HELMET": 3}
        durs = ["0"] * 4
        defs = ["0"] * 4
        durs[slots[slot]] = str(dur)
        defs[slots[slot]] = str(defense)
        type_lines.append(
            f"    public static final ArmorType {const} = new ArmorType(ARMOR_MATERIALS, id(\"{item_id}\"), id(\"{model.location}\"), "
            f"{tough}F, {kb}F,\n            new Integer[]{{{', '.join(durs)}}}, new Integer[]{{{', '.join(defs)}}}, {ench}, "
            f"SoundEvents.{sound}, true, true, \"c:ingots/steel\");")
        item_lines.append(
            f"    public static final RegistrySupplier<DyeableMedievalArmorItem> {const}_ITEM = INSTANCE.addDyeableMedievalArmorItem(\"{item_id}\", "
            f"ArmoryArmorTypes.{const}, ArmorItem.Type.{slot}, new Item.Properties(), 0x{dye:06X});")

        write_json({"parent": "item/generated", "textures": {"layer0": f"{MODID}:item/{item_id}",
                                                              "layer1": f"{MODID}:item/{item_id}_overlay"}},
                   os.path.join(ASSETS, f"models/item/{item_id}.json"))
        lang[f"item.{MODID}.{item_id}"] = NAMES[item_id]
        pattern, keys = RECIPES[item_id]
        write_json({"type": "minecraft:crafting_shaped", "category": "equipment", "pattern": pattern,
                    "key": {k: ingredient(v) for k, v in keys.items()},
                    "result": {"id": f"{MODID}:{item_id}", "count": 1}},
                   os.path.join(DATA, f"recipe/{item_id}.json"))

    weapon_lines = build_weapons(lang)
    write_tags()
    write_text(json.dumps(lang, indent=2, ensure_ascii=False) + "\n", os.path.join(ASSETS, "lang/en_us.json"))
    write_json(preview, os.path.join(PREVIEW, "models.json"))
    icons.write_all(os.path.join(ASSETS, "textures/item"), PREVIEW, ARMOURS)

    write_text("\n".join([
        "package dev.culturesarmory.client;", "",
        "import com.magistuarmory.api.client.render.model.ModModelsProvider;",
        "import dev.culturesarmory.client.model.*;",
        "import net.minecraft.client.model.geom.ModelLayerLocation;", "",
        "/** Generated by armory/tools/build_assets.py: the armour models (client only). */",
        "public class ArmoryModels extends ModModelsProvider {",
        "    public static final ArmoryModels INSTANCE = new ArmoryModels();",
        *model_lines, "",
        "    public ArmoryModels() {",
        f"        super(\"{MODID}\");",
        "    }",
        "}", ""]), os.path.join(JAVA, "client/ArmoryModels.java"))

    write_text("\n".join([
        "package dev.culturesarmory;", "",
        "import com.magistuarmory.item.armor.ArmorType;",
        "import dev.architectury.registry.registries.DeferredRegister;",
        "import net.minecraft.core.registries.Registries;",
        "import net.minecraft.resources.ResourceLocation;",
        "import net.minecraft.sounds.SoundEvents;",
        "import net.minecraft.world.item.ArmorMaterial;", "",
        "/** Generated by armory/tools/build_assets.py: one Epic Knights armour type per piece (its stats and model). */",
        "public final class ArmoryArmorTypes {",
        f"    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS = DeferredRegister.create(CulturesArmory.ID, Registries.ARMOR_MATERIAL);",
        "",
        *type_lines, "",
        "    private ArmoryArmorTypes() {}", "",
        "    static ResourceLocation id(String path) {",
        "        return ResourceLocation.fromNamespaceAndPath(CulturesArmory.ID, path);",
        "    }", "",
        "    public static void init() {",
        "        ARMOR_MATERIALS.register();",
        "    }",
        "}", ""]), os.path.join(JAVA, "ArmoryArmorTypes.java"))

    write_text("\n".join([
        "package dev.culturesarmory;", "",
        "import com.magistuarmory.api.item.ModItemsProvider;",
        "import com.magistuarmory.item.MedievalWeaponItem;",
        "import com.magistuarmory.item.ModItemTier;",
        "import com.magistuarmory.item.RangedWeaponType;",
        "import com.magistuarmory.item.WeaponType;",
        "import com.magistuarmory.item.armor.DyeableMedievalArmorItem;",
        "import dev.architectury.registry.registries.RegistrySupplier;",
        "import net.minecraft.world.item.ArmorItem;",
        "import net.minecraft.world.item.Item;", "",
        "/** Generated by armory/tools/build_assets.py: the armour and weapons, registered the Epic Knights way. */",
        "public class ArmoryItems extends ModItemsProvider {",
        "    public static final ArmoryItems INSTANCE = new ArmoryItems();",
        *item_lines, "",
        *weapon_lines, "",
        "    public ArmoryItems() {",
        "        super(CulturesArmory.ID);",
        "    }",
        "}", ""]), os.path.join(JAVA, "ArmoryItems.java"))
    print("generated", len(ARMOURS), "armours,", len(weapons.WEAPONS) + len(weapons.BOWS), "weapons")


if __name__ == "__main__":
    main()
