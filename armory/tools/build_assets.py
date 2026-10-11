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
import byzantine  # noqa: E402
import byzantine_ext  # noqa: E402
import horse  # noqa: E402
import india  # noqa: E402
import inuit  # noqa: E402
import inuit_ext  # noqa: E402
import japan  # noqa: E402
import japan_ext  # noqa: E402
import maya  # noqa: E402
import seljuk  # noqa: E402
import seljuk_ext  # noqa: E402
import shields  # noqa: E402
import weapons  # noqa: E402
import weapons3d  # noqa: E402

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

# the cultures: Japan (above), then each culture's module (its pieces, names and recipes)
CULTURES = [seljuk, inuit, byzantine, india, maya, japan_ext, seljuk_ext, inuit_ext, byzantine_ext]
ARMOURS = japan.ARMOURS + [a for m in CULTURES for a in m.ARMOURS]
for _m in CULTURES + [horse]:
    NAMES.update(_m.NAMES)
    RECIPES.update(_m.RECIPES)


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
    tag("dyeable", [a[0] for a in ARMOURS] + [b[0] for b in horse.BARDINGS if b[3]])
    for name, slot in (("head_armor", "HELMET"), ("chest_armor", "CHESTPLATE"), ("leg_armor", "LEGGINGS"), ("foot_armor", "BOOTS")):
        tag(name, [a[0] for a in ARMOURS if a[2] == slot])
    tag("swords", list(weapons.WEAPONS) + list(weapons3d.WEAPONS3D))
    tag("bows", [b[0] for b in weapons.BOWS])
    tag("enchantable/bow", [b[0] for b in weapons.BOWS])


def build_bardings(lang, preview):
    """Horse armour in 3D: each barding's model (Java), its texture at twice the density (dyed bardings: a tinted base and
    an overlay), the item (an Epic Knights MedievalHorseArmorItem with a blank vanilla texture: the armory's horse layer
    draws it), its recipe; client/ArmoryBardings lists the models and textures for the layer."""
    from PIL import Image
    lines, looks, layers = [], [], []
    blank = "textures/entity/horse/armor/blank.png"
    write_png(Image.new("RGBA", (64, 64), (0, 0, 0, 0)), os.path.join(ASSETS, blank))
    for bid, make, material, dyeable, dye in horse.BARDINGS:
        model = make()
        model.pack()
        canvas = model.paint()
        base, over = canvas.layers(dyeable)
        tex = f"textures/entity/horse/barding/{bid}"
        write_png(base if dyeable else over, os.path.join(ASSETS, tex + ".png"))
        if dyeable:
            write_png(over, os.path.join(ASSETS, tex + "_overlay.png"))
        write_text(model.java("dev.culturesarmory.client.model"), os.path.join(JAVA, f"client/model/{model.java_name}.java"))
        tint = ((dye >> 16) & 255, (dye >> 8) & 255, dye & 255) if dyeable else None
        write_png(canvas.composite(tint) if tint else over, os.path.join(PREVIEW, f"{bid}.png"))
        preview[bid] = dict(model.preview_json(), slot="HORSE", texture=f"{bid}.png")
        textures = {"layer0": f"{MODID}:item/{bid}", "layer1": f"{MODID}:item/{bid}_overlay"}
        write_json({"parent": "item/generated", "textures": textures}, os.path.join(ASSETS, f"models/item/{bid}.json"))
        lang[f"item.{MODID}.{bid}"] = NAMES[bid]
        pattern, keys = RECIPES[bid]
        recipe(bid, pattern, keys)
        props = "new Item.Properties().stacksTo(1)"
        if dyeable:
            props += f".component(DataComponents.DYED_COLOR, new DyedItemColor(0x{dye:06X}, false))"
        lines.append(f"    public static final RegistrySupplier<Item> {bid.upper()} = INSTANCE.items.register(\"{bid}\", () -> new MedievalHorseArmorItem(\n"
                     f"            ArmorMaterials.{material}, ResourceLocation.fromNamespaceAndPath(CulturesArmory.ID, "
                     f"\"{blank}\"), false, {props}));")
        overlay = f"tex(\"{bid}_overlay\")" if dyeable else "null"
        looks.append(f"            new Look(ArmoryItems.{bid.upper()}, layer(\"{bid}\"), {model.java_name}::createLayer, tex(\"{bid}\"), {overlay}, "
                     f"{'true' if dyeable else 'false'}, 0x{(dye or 0xFFFFFF):06X})")
    preview["_horse"] = dict(horse.vanilla_preview(), slot="HORSE_BASE", texture="horse.png")
    lines.append("    /** The horse armour (listed in Epic Knights' armour tab; the felt barding's icon is tinted by its dye). */")
    lines.append("    public static final java.util.List<RegistrySupplier<Item>> BARDINGS = java.util.List.of("
                 + ", ".join(b[0].upper() for b in horse.BARDINGS) + ");")
    lines.append("    public static final java.util.List<RegistrySupplier<Item>> DYED_BARDINGS = java.util.List.of("
                 + ", ".join(b[0].upper() for b in horse.BARDINGS if b[3]) + ");")
    write_text("\n".join([
        "package dev.culturesarmory.client;", "",
        "import dev.culturesarmory.ArmoryItems;",
        "import dev.culturesarmory.CulturesArmory;",
        "import dev.culturesarmory.client.model.*;",
        "import net.minecraft.client.model.geom.ModelLayerLocation;",
        "import net.minecraft.client.model.geom.builders.LayerDefinition;",
        "import net.minecraft.resources.ResourceLocation;",
        "import net.minecraft.world.item.Item;", "",
        "import javax.annotation.Nullable;",
        "import java.util.List;",
        "import java.util.function.Supplier;", "",
        "/** Generated by armory/tools/build_assets.py: the bardings' models and textures, drawn by BardingLayer (client only). */",
        "public final class ArmoryBardings {",
        "    private ArmoryBardings() {}", "",
        "    /** A barding: its item, model layer, mesh, texture (dyed bardings: the tinted base) and overlay, its default dye. */",
        "    public record Look(Supplier<? extends Item> item, ModelLayerLocation layer, Supplier<LayerDefinition> mesh,",
        "                       ResourceLocation texture, @Nullable ResourceLocation overlay, boolean dyeable, int dye) {}", "",
        "    public static final List<Look> LOOKS = List.of(",
        ",\n".join(looks) + ");", "",
        "    static ModelLayerLocation layer(String id) {",
        "        return new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(CulturesArmory.ID, id), \"main\");",
        "    }", "",
        "    static ResourceLocation tex(String id) {",
        "        return ResourceLocation.fromNamespaceAndPath(CulturesArmory.ID, \"textures/entity/horse/barding/\" + id + \".png\");",
        "    }",
        "}", ""]), os.path.join(JAVA, "client/ArmoryBardings.java"))
    return lines


def build_shields(lang, preview):
    """The cultures' shields, Epic Knights' way: the model (Java), its texture in the shield atlas (not paintable: the
    painting is its own), the item (an Epic Knights MedievalShieldItem), its item model (held as Epic Knights' round or
    kite shield), recipe. Returns the item and model registration lines."""
    items, models, atlas = [], [], []
    for sid, (make, held, tier, stats, name, (pattern, keys)) in shields.SHIELDS.items():
        model = make()
        model.pack()
        canvas = model.paint()
        _, over = canvas.layers(False)
        for kind in ("nopattern", "pattern"):
            write_png(over, os.path.join(ASSETS, f"textures/entity/{sid}_{kind}.png"))
            atlas.append({"type": "single", "resource": f"{MODID}:entity/{sid}_{kind}"})
        write_png(over, os.path.join(PREVIEW, f"{sid}.png"))
        preview[sid] = dict(model.preview_json(), slot="SHIELD", texture=f"{sid}.png")
        write_text(model.java("dev.culturesarmory.client.model"), os.path.join(JAVA, f"client/model/{model.java_name}.java"))
        models.append(f"    public static final ModelLayerLocation {sid.upper()}_SHIELD = INSTANCE.addModel(\"{sid}\", {model.java_name}::createLayer);")
        write_json({"parent": f"magistuarmory:item/{held}",
                    "overrides": [{"predicate": {"blocking": 1}, "model": f"{MODID}:item/{sid}_blocking"}]},
                   os.path.join(ASSETS, f"models/item/{sid}.json"))
        write_json({"parent": f"magistuarmory:item/{held}_blocking"}, os.path.join(ASSETS, f"models/item/{sid}_blocking.json"))
        lang[f"item.{MODID}.{sid}"] = name
        recipe(sid, pattern, keys)
        items.append(f"    public static final RegistrySupplier<MedievalShieldItem> {sid.upper()} = INSTANCE.addMedievalShieldItem(\"{sid}\", "
                     f"\"{sid}\", new Item.Properties(),\n            ModItemTier.{tier}, false, true, ShieldType.of({stats}));")  # (paintable, 3D), as Epic Knights registers its bucklers
    # the textures join the game's shield atlas (atlas sources add up across packs, as Epic Knights adds its own)
    write_json({"sources": atlas}, os.path.join(ROOT, "src/main/resources/assets/minecraft/atlases/shield_patterns.json"))
    return items, models


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
    for item_id, (make, kind, stats, display, attrs, (pattern, keys)) in weapons3d.WEAPONS3D.items():
        img, elements = make().build()
        img.save(os.path.join(ASSETS, f"textures/item/{item_id}.png"))
        overrides = None
        if kind == "lance":
            # Epic Knights' lances: held raised unless couched on horseback
            overrides = [{"predicate": {"magistuarmory:raised": 1}, "model": f"{MODID}:item/{item_id}_raised"}]
            write_json(weapons3d.item_model(MODID, item_id, elements, weapons3d.LANCE_RAISED_DISPLAY),
                       os.path.join(ASSETS, f"models/item/{item_id}_raised.json"))
        write_json(weapons3d.item_model(MODID, item_id, elements, display, overrides), os.path.join(ASSETS, f"models/item/{item_id}.json"))
        write_json(attrs, os.path.join(DATA, f"weapon_attributes/{item_id}.json"))
        recipe(item_id, pattern, keys)
        lang[f"item.{MODID}.{item_id}"] = weapons3d.NAMES[item_id]
        add = "addLanceItem" if kind == "lance" else "addMedievalWeaponItem"
        lines.append(f"    public static final RegistrySupplier<MedievalWeaponItem> {item_id.upper()} = INSTANCE.{add}("
                     f"\"{item_id}\", new Item.Properties(), ModItemTier.STEEL,\n            {stats});")
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

    shield_lines, shield_models = build_shields(lang, preview)
    weapon_lines = build_weapons(lang) + build_bardings(lang, preview) + shield_lines
    write_tags()
    write_text(json.dumps(lang, indent=2, ensure_ascii=False) + "\n", os.path.join(ASSETS, "lang/en_us.json"))
    write_json(preview, os.path.join(PREVIEW, "models.json"))
    icons.write_all(os.path.join(ASSETS, "textures/item"), PREVIEW,
                    ARMOURS + [(b[0], None, None, None, b[4] if b[3] else 0xFFFFFF, None) for b in horse.BARDINGS])

    write_text("\n".join([
        "package dev.culturesarmory.client;", "",
        "import com.magistuarmory.api.client.render.model.ModModelsProvider;",
        "import dev.culturesarmory.client.model.*;",
        "import net.minecraft.client.model.geom.ModelLayerLocation;", "",
        "/** Generated by armory/tools/build_assets.py: the armour models (client only). */",
        "public class ArmoryModels extends ModModelsProvider {",
        "    public static final ArmoryModels INSTANCE = new ArmoryModels();",
        *model_lines, "",
        "    // the shields (Epic Knights draws them, baking these layers by the shield's location)",
        *shield_models, "",
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
        "import com.magistuarmory.item.MedievalShieldItem;",
        "import com.magistuarmory.item.MedievalWeaponItem;",
        "import com.magistuarmory.item.ModItemTier;",
        "import com.magistuarmory.item.RangedWeaponType;",
        "import com.magistuarmory.item.ShieldType;",
        "import com.magistuarmory.item.WeaponType;",
        "import com.magistuarmory.item.WeaponTypes;",
        "import com.magistuarmory.item.armor.DyeableMedievalArmorItem;",
        "import com.magistuarmory.item.armor.MedievalHorseArmorItem;",
        "import net.minecraft.core.component.DataComponents;",
        "import net.minecraft.resources.ResourceLocation;",
        "import net.minecraft.world.item.ArmorMaterials;",
        "import net.minecraft.world.item.component.DyedItemColor;",
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
    print("generated", len(ARMOURS), "armours,", len(weapons.WEAPONS) + len(weapons.BOWS) + len(weapons3d.WEAPONS3D), "weapons,",
          len(shields.SHIELDS), "shields")


if __name__ == "__main__":
    main()
