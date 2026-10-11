#!/usr/bin/env python3
"""
Writes the per-unit equipment lists ("unit:<HYW unit key>") into hywmill_equipment/profiles.json: for every culture, every
tier (WATCH, GUARD_POST, GARRISON, STRONGHOLD) and every HYW unit, the kits it wears and the weapons and shields it
carries, after the armies each culture actually fielded. Each list keeps Epic Knights kits and weapons beside the
Cultures Armory ones (taken from the culture's class lists), so it works with or without the armory installed.
A unit without lists of its own (one HYW adds later) falls through to its class. Rerunning replaces the unit lists.

    python3 devtools/unit_kits.py
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PROFILES = os.path.join(HERE, "..", "src/main/resources/data/hywmill/hywmill_equipment/profiles.json")
LOOKS = os.path.join(HERE, "..", "src/main/resources/data/hywmill/hywmill_equipment/squad_looks.json")
TIERS = ["WATCH", "GUARD_POST", "GARRISON", "STRONGHOLD"]
CLASS = {"militia": "militia", "spear_man": "line", "shieldman": "line", "warrior": "line", "archer": "ranged",
         "crossbowman": "ranged", "handgonne_man": "ranged", "matchlock_man": "ranged", "light_lancer_rider": "scout",
         "archer_rider": "scout", "lancer_rider": "scout"}
# the Epic Knights families HYW itself gives each unit (its equipment_epic_knights.json); riders' weapons are HYW's own
FAMILIES = {"militia": {"mainhand": {"barbedclub", "blacksmith_hammer", "club", "katzbalger", "messer_sword", "noble_sword",
                                     "pitchfork", "shortsword"}},
            "spear_man": {"mainhand": {"ahlspiess", "pike"}},
            "shieldman": {"mainhand": {"katzbalger", "shortsword"}, "offhand": {"buckler", "heatershield", "kiteshield", "roundshield"}},
            "warrior": {"mainhand": {"bastardsword", "claymore", "heavymace", "lochaberaxe"}},
            "archer": {"mainhand": {"longbow"}}, "crossbowman": {"mainhand": {"heavy_crossbow"}},
            "light_lancer_rider": {"offhand": {"buckler", "roundshield", "target"}},
            "lancer_rider": {"offhand": {"buckler", "heatershield", "kiteshield", "roundshield"}}}
MATERIALS = ["wood", "wooden", "stone", "iron", "gold", "golden", "diamond", "netherite", "copper", "steel", "bronze", "silver", "tin"]
ARMORY_FAMILIES = {
    "wakizashi": "shortsword", "katana": "bastardsword", "nodachi": "claymore", "naginata": "lochaberaxe", "yari": "pike",
    "jumonji_yari": "ahlspiess", "kanabo": "heavymace", "yumi": "longbow", "kilij": "katzbalger", "sayf": "bastardsword",
    "shishpar": "heavymace", "tabarzin": "lochaberaxe", "mizrak": "pike", "turkic_bow": "longbow", "harpoon": "pike",
    "ivory_lance": "ahlspiess", "bone_club": "heavymace", "savik": "shortsword", "sinew_bow": "longbow", "kontarion": "pike",
    "menavlion": "ahlspiess", "spathion": "katzbalger", "paramerion": "bastardsword", "pelekys": "lochaberaxe",
    "siderorabdion": "heavymace", "bardoukion": "heavymace", "skoutarion": "kiteshield", "aspis": "roundshield",
    "varangian_shield": "roundshield", "kalkan": "roundshield", "sipar": "roundshield", "tedate": "kiteshield",
    "toxarion": "longbow", "tachi": "bastardsword", "nagamaki": "lochaberaxe", "talwar": "katzbalger", "khanda": "bastardsword",
    "katar": "shortsword", "barchha": "pike", "tabar": "heavymace", "gada": "heavymace", "dhanush": "longbow",
    "macuahuitl": "katzbalger", "flint_spear": "pike", "flint_axe": "heavymace", "maya_bow": "longbow", "dhal": "roundshield",
    "pakal": "roundshield", "flexible_shield": "kiteshield"}


def family(item):
    p = item.split(":", 1)[1]
    for m in MATERIALS:
        if p.startswith(m + "_") and len(p) > len(m) + 1:
            p = p[len(m) + 1:]
            break
    return ARMORY_FAMILIES.get(p, p) if item.startswith("culturesarmory:") else p


def ca(x):
    return x if ":" in x or x == "none" else "culturesarmory:" + x


def kit(t):
    return {"head": ca(t[0]), "chest": ca(t[1]), "legs": ca(t[2]), "feet": ca(t[3])}


def armory(k):
    return any(v.startswith("culturesarmory:") for v in k.values())


def per_tier(w, g, ga, s):
    return dict(zip(TIERS, (w, g, ga, s)))


def ek_mat(tier, h=0):
    return {"WATCH": "wood", "GUARD_POST": "iron", "GARRISON": "iron", "STRONGHOLD": "steel"}[tier]


# ---------------------------------------------------------------------------------------------------------------- Byzantium
# The Middle Byzantine army (10th-12th c.): the thematic levy and the skutatoi in klibania and padded kabadia, the menavlatoi
# who met the enemy horse with their heavy spears, toxotai, the light horse (trapezitai) of the akritic frontier, the
# kataphraktoi, the Varangian Guard and the palace excubitors.
BYZ = {
    "kits": {
        "peasant": ("pilos", "chiton", "byzantine_hose", "tzangia"),
        "levy": ("phakeolion", "kabadion", "byzantine_hose", "tzangia"),
        "skutatos_light": ("kassis", "kabadion", "kremasmata", "tzangia"),
        "skutatos": ("kassis", "klibanion", "kremasmata", "tzangia"),
        "skutatos_mail": ("mail_coif", "lorikion", "kremasmata", "tzangia"),
        "skutatos_scale": ("phrygian_helmet", "scale_armour", "scale_kremasmata", "tzangia"),
        "epilorikion": ("kassis", "epilorikion", "kremasmata", "tzangia"),
        "menavlatos": ("phrygian_helmet", "klibanion", "lamellar_kremasmata", "podopsella"),
        "toxotes_hat": ("skiadion", "kabadion", "byzantine_hose", "tzangia"),
        "toxotes_cloth": ("phakeolion", "kabadion", "byzantine_hose", "tzangia"),
        "toxotes_mail": ("kassis", "lorikion", "byzantine_hose", "tzangia"),
        "akrites": ("phrygian_helmet", "akrites_lamellar", "byzantine_hose", "tzangia"),
        "akrites_kassis": ("kassis", "akrites_lamellar", "kremasmata", "tzangia"),
        "trapezites": ("kassis", "lorikion", "byzantine_hose", "tzangia"),
        "varangian": ("varangian_helmet", "varangian_hauberk", "mail_chausses", "tzangia"),
        "excubitor": ("phrygian_helmet", "excubitor_armour", "scale_kremasmata", "kampagia"),
        "kataphraktos": ("kataphract_helmet", "kataphract_armour", "lamellar_kremasmata", "podopsella"),
        "strategos": ("strategos_helmet", "strategos_klibanion", "lamellar_kremasmata", "kampagia"),
    },
    "units": {
        "militia": per_tier(["peasant", "levy"], ["levy", "skutatos_light", "peasant"], ["skutatos_light", "epilorikion", "levy"],
                            ["skutatos_light", "epilorikion", "akrites_kassis"]),
        "spear_man": per_tier(["levy", "skutatos_light"], ["skutatos_light", "skutatos", "akrites_kassis"],
                              ["skutatos", "skutatos_scale", "epilorikion"], ["skutatos", "skutatos_scale", "skutatos_mail", "menavlatos"]),
        "shieldman": per_tier(["skutatos_light"], ["skutatos_light", "skutatos"], ["skutatos", "skutatos_mail", "epilorikion"],
                              ["skutatos_scale", "skutatos_mail", "excubitor"]),
        "warrior": per_tier(["skutatos_light"], ["skutatos", "akrites"], ["menavlatos", "varangian", "skutatos_scale"],
                            ["menavlatos", "varangian", "excubitor"]),
        "archer": per_tier(["peasant", "toxotes_cloth"], ["toxotes_cloth", "toxotes_hat", "toxotes_mail"],
                           ["toxotes_hat", "toxotes_mail", "akrites"], ["toxotes_mail", "akrites", "skutatos_mail"]),
        "light_lancer_rider": per_tier(["toxotes_cloth"], ["trapezites", "akrites"], ["trapezites", "akrites", "akrites_kassis"],
                                       ["akrites", "skutatos_mail", "trapezites"]),
        "archer_rider": per_tier(["toxotes_cloth"], ["toxotes_mail", "akrites"], ["akrites", "toxotes_mail"], ["akrites", "skutatos_mail"]),
        "lancer_rider": per_tier(["skutatos"], ["skutatos_mail", "kataphraktos"], ["kataphraktos", "skutatos_mail"],
                                 ["kataphraktos", "strategos"]),
    },
    "mainhand": {
        "militia": ["steel_spathion"], "spear_man": ["steel_kontarion", "steel_menavlion"], "shieldman": ["steel_spathion"],
        "warrior": ["steel_paramerion", "steel_siderorabdion", "steel_bardoukion", "steel_pelekys"], "archer": ["toxarion"],
    },
    "offhand": {"shieldman": ["skoutarion", "aspis"], "light_lancer_rider": ["aspis"], "lancer_rider": ["skoutarion", "aspis"]},
    "armory_weapons_from": "GUARD_POST",
}
BYZ["units"]["crossbowman"] = BYZ["units"]["archer"]

# ---------------------------------------------------------------------------------------------------------------- the Seljuks
# The Great Seljuks and Rum (11th-13th c.): the Turkmen horse archers, the ghulams in mail and jawshan, the Daylamite foot
# with spear and shield, the Kipchak mercenaries, the Bedouin auxiliaries, the peasant levy of Anatolia.
SELJUK = {
    "kits": {
        "peasant": ("turban", "kaftan", "dolak_shalwar", "carik"),
        "shepherd": ("bork", "kepenek", "dolak_shalwar", "felt_boots"),
        "bedouin": ("kufiya", "bedouin_robe", "shalwar", "carik"),
        "turkmen": ("bork", "kaftan", "shalwar", "turkic_riding_boots"),
        "turkmen_quilted": ("bork", "quilted_kaftan", "shalwar", "turkic_riding_boots"),
        "daylami": ("seljuk_spangenhelm", "quilted_kaftan", "shalwar", "carik"),
        "daylami_mail": ("seljuk_spangenhelm", "mail_kaftan", "shalwar", "turkic_riding_boots"),
        "ghulam": ("seljuk_conical_helmet", "kazaghand", "mail_leggings", "turkic_riding_boots"),
        "ghulam_jawshan": ("seljuk_veiled_helmet", "jawshan", "lamellar_thigh_guards", "splinted_greaves"),
        "ghulam_turban": ("seljuk_turbaned_helmet", "mail_kaftan", "shalwar", "turkic_riding_boots"),
        "kipchak": ("kipchak_mask_helmet", "kipchak_lamellar", "shalwar", "turkic_riding_boots"),
        "kipchak_light": ("bork", "kipchak_lamellar", "shalwar", "felt_boots"),
        "emir": ("atabeg_helmet", "gilded_jawshan", "lamellar_thigh_guards", "splinted_greaves"),
        "court": ("sharbush", "khalat", "shalwar", "turkic_riding_boots"),
    },
    "units": {
        "militia": per_tier(["peasant", "shepherd"], ["peasant", "shepherd", "bedouin"], ["turkmen_quilted", "daylami", "bedouin"],
                            ["turkmen_quilted", "daylami_mail"]),
        "spear_man": per_tier(["peasant", "daylami"], ["daylami", "turkmen_quilted"], ["daylami", "daylami_mail", "ghulam_turban"],
                              ["daylami_mail", "ghulam", "ghulam_turban"]),
        "shieldman": per_tier(["daylami"], ["daylami", "daylami_mail"], ["daylami_mail", "ghulam_turban", "ghulam"], ["ghulam", "ghulam_jawshan"]),
        "warrior": per_tier(["turkmen_quilted"], ["daylami_mail", "kipchak"], ["ghulam", "kipchak", "ghulam_turban"],
                            ["ghulam_jawshan", "ghulam", "kipchak", "court"]),
        "archer": per_tier(["turkmen", "shepherd"], ["turkmen", "turkmen_quilted", "bedouin"], ["turkmen_quilted", "ghulam_turban"],
                           ["ghulam_turban", "ghulam"]),
        "light_lancer_rider": per_tier(["bedouin", "turkmen"], ["bedouin", "turkmen_quilted", "kipchak_light"],
                                       ["kipchak_light", "kipchak", "ghulam_turban"], ["kipchak", "ghulam"]),
        "archer_rider": per_tier(["turkmen"], ["turkmen", "turkmen_quilted", "kipchak_light"], ["turkmen_quilted", "kipchak_light", "ghulam_turban"],
                                 ["ghulam", "kipchak", "ghulam_turban"]),
        "lancer_rider": per_tier(["ghulam_turban"], ["ghulam"], ["ghulam", "ghulam_jawshan"], ["ghulam_jawshan", "emir"]),
    },
    "mainhand": {
        "militia": ["steel_kilij"], "spear_man": ["steel_mizrak"], "shieldman": ["steel_kilij"],
        "warrior": ["steel_sayf", "steel_shishpar", "steel_tabarzin"], "archer": ["turkic_bow"],
    },
    "offhand": {"shieldman": ["kalkan", "sipar"], "light_lancer_rider": ["kalkan"], "lancer_rider": ["sipar", "kalkan"]},
    "armory_weapons_from": "GUARD_POST",
}
SELJUK["units"]["crossbowman"] = SELJUK["units"]["archer"]

# ---------------------------------------------------------------------------------------------------------------- Japan
# The Sengoku period: the ashigaru with yari, bow and matchlock in munition armour under the clan's sashimono, the
# samurai in tosei gusoku, the older great armour of the mounted archer, commanders in jinbaori, warrior monks.
JAPAN = {
    "kits": {
        "peasant": ("sugegasa", "none", "ashigaru_hakama", "waraji"),
        "ashigaru_light": ("ashigaru_hachigane", "tatami_do", "ashigaru_hakama", "ashigaru_kyahan"),
        "ashigaru": ("jingasa", "okegawa_do", "ashigaru_hakama", "ashigaru_kyahan"),
        "ashigaru_waraji": ("jingasa", "okegawa_do", "ashigaru_hakama", "waraji"),
        "ashigaru_sashimono": ("jingasa", "sashimono_do", "ashigaru_hakama", "ashigaru_kyahan"),
        "kogashira": ("zunari_kabuto", "haramaki", "ashigaru_hakama", "ashigaru_kyahan"),
        "sohei": ("sohei_zukin", "haramaki", "ashigaru_hakama", "waraji"),
        "tosei": ("momonari_kabuto", "nimai_do", "ikada_haidate", "tsutsu_suneate"),
        "hotoke": ("zunari_kabuto", "hotoke_do", "ikada_haidate", "tsutsu_suneate"),
        "domaru": ("zunari_kabuto", "do_maru", "samurai_haidate", "samurai_suneate"),
        "samurai": ("kabuto", "samurai_do", "samurai_haidate", "samurai_suneate"),
        "menpo": ("menpo_kabuto", "samurai_do", "samurai_haidate", "samurai_suneate"),
        "nanban": ("nanban_kabuto", "nanban_do", "ikada_haidate", "tsutsu_suneate"),
        "o_yoroi": ("hoshi_kabuto", "o_yoroi", "samurai_haidate", "samurai_suneate"),
        "taisho": ("eboshi_kabuto", "jinbaori", "ikada_haidate", "tsutsu_suneate"),
        "daimyo": ("mikazuki_kabuto", "jinbaori", "samurai_haidate", "samurai_suneate"),
    },
    "units": {
        "militia": per_tier(["peasant", "ashigaru_light"], ["ashigaru_light", "ashigaru_waraji", "sohei"], ["ashigaru", "sohei", "kogashira"],
                            ["ashigaru", "kogashira"]),
        "spear_man": per_tier(["ashigaru_light", "ashigaru_waraji"], ["ashigaru", "ashigaru_sashimono"],
                              ["ashigaru_sashimono", "ashigaru", "kogashira"], ["ashigaru_sashimono", "kogashira", "tosei"]),
        "shieldman": per_tier(["ashigaru_light"], ["ashigaru"], ["ashigaru_sashimono", "ashigaru"], ["ashigaru_sashimono", "kogashira"]),
        "warrior": per_tier(["kogashira"], ["kogashira", "domaru"], ["tosei", "hotoke", "domaru", "sohei"],
                            ["tosei", "hotoke", "menpo", "nanban", "taisho"]),
        "archer": per_tier(["ashigaru_light"], ["ashigaru", "ashigaru_light"], ["ashigaru_sashimono", "kogashira"],
                           ["ashigaru_sashimono", "domaru"]),
        "handgonne_man": per_tier(["ashigaru_light"], ["ashigaru"], ["ashigaru", "ashigaru_sashimono"], ["ashigaru_sashimono", "ashigaru"]),
        "light_lancer_rider": per_tier(["kogashira"], ["domaru", "kogashira"], ["tosei", "domaru", "hotoke"], ["tosei", "menpo", "nanban"]),
        "archer_rider": per_tier(["domaru"], ["domaru", "samurai"], ["samurai", "o_yoroi"], ["o_yoroi", "menpo"]),
        "lancer_rider": per_tier(["samurai"], ["samurai", "tosei"], ["menpo", "tosei", "taisho"], ["taisho", "daimyo", "nanban", "menpo"]),
    },
    "mainhand": {
        "militia": ["steel_wakizashi"], "spear_man": ["steel_yari", "steel_jumonji_yari"], "shieldman": ["steel_wakizashi"],
        "warrior": ["steel_katana", "steel_tachi", "steel_nodachi", "steel_naginata", "steel_nagamaki", "steel_kanabo"],
        "archer": ["yumi"],
    },
    "offhand": {"shieldman": ["tedate", "none"], "light_lancer_rider": ["none"], "lancer_rider": ["none"]},
    "armory_weapons_from": "GUARD_POST",
}
JAPAN["units"]["crossbowman"] = JAPAN["units"]["archer"]
JAPAN["units"]["matchlock_man"] = JAPAN["units"]["handgonne_man"]

# ---------------------------------------------------------------------------------------------------------------- India
# The Rajput clans, the Sultanates and the Mughals (13th-17th c.): the paiks (foot) in quilted coats with sword and dhal,
# the Bhil archers, the barchha spearmen, the toradar (matchlock) men, the Rajput horse in turbans and chilta, the heavy
# cavalry of the mansabdars in char aina, the Vijayanagara nobles in scale.
INDIA = {
    "kits": {
        "villager": ("pagri", "angarkha", "dhoti", "chappal"),
        "bhil": ("bhil_headband", "none", "dhoti", "chappal"),
        "paik": ("pagri", "angarkha", "paijama", "jutti"),
        "paik_quilted": ("pagri", "chilta_hazar_masha", "paijama", "jutti"),
        "sipahi_turban": ("turbaned_khud", "zirah_bagtar", "paijama", "moza_boots"),
        "sipahi": ("kulah_khud", "zirah_bagtar", "mail_paijama", "moza_boots"),
        "heavy": ("kulah_khud", "char_aina", "zanupush", "moza_boots"),
        "heavy_top": ("top_khud", "char_aina", "mail_paijama", "moza_boots"),
        "noble": ("gilded_kulah_khud", "char_aina", "zanupush", "moza_boots"),
        "vijayanagara": ("top_khud", "kavacha", "zanupush", "jutti"),
        "rajput": ("pagri", "chilta_hazar_masha", "paijama", "moza_boots"),
        "nayar": ("none", "none", "dhoti", "none"),            # the Nayar of Kerala fought unarmoured, with sword and shield
    },
    "units": {
        "militia": per_tier(["villager", "paik"], ["paik", "villager"], ["paik", "paik_quilted"], ["paik_quilted", "sipahi_turban"]),
        "spear_man": per_tier(["villager", "paik"], ["paik", "paik_quilted"], ["paik_quilted", "sipahi_turban"], ["sipahi_turban", "sipahi"]),
        "shieldman": per_tier(["paik"], ["paik_quilted", "paik"], ["paik_quilted", "sipahi_turban", "vijayanagara"],
                              ["sipahi", "vijayanagara", "heavy"]),
        "warrior": per_tier(["paik_quilted"], ["rajput", "sipahi_turban"], ["sipahi", "heavy", "vijayanagara"], ["heavy", "heavy_top", "noble"]),
        "archer": per_tier(["bhil", "villager"], ["bhil", "paik"], ["paik", "paik_quilted"], ["paik_quilted", "sipahi_turban"]),
        "handgonne_man": per_tier(["paik"], ["paik"], ["paik", "paik_quilted"], ["paik_quilted", "sipahi_turban"]),
        "light_lancer_rider": per_tier(["paik"], ["rajput", "paik_quilted"], ["rajput", "sipahi_turban"], ["rajput", "sipahi"]),
        "archer_rider": per_tier(["paik_quilted"], ["rajput", "sipahi_turban"], ["sipahi_turban", "sipahi"], ["sipahi", "heavy"]),
        "lancer_rider": per_tier(["sipahi_turban"], ["sipahi"], ["heavy", "sipahi"], ["noble", "heavy", "heavy_top"]),
    },
    "mainhand": {
        "militia": ["steel_talwar", "steel_katar"], "spear_man": ["steel_barchha"], "shieldman": ["steel_talwar", "steel_katar"],
        "warrior": ["steel_khanda", "steel_gada", "steel_tabar"], "archer": ["dhanush"],
    },
    "offhand": {"shieldman": ["dhal"], "light_lancer_rider": ["dhal"], "lancer_rider": ["dhal"]},
    "armory_weapons_from": "GUARD_POST",
    # the class and duty lists of a culture that had no armory kits before 1.5
    "class_kits": {"all": per_tier(["paik", "villager"], ["paik", "paik_quilted"], ["paik_quilted", "sipahi_turban", "sipahi"],
                                   ["sipahi", "heavy", "vijayanagara"]),
                   "militia": per_tier(["villager", "paik"], ["paik"], ["paik", "paik_quilted"], ["paik_quilted"]),
                   "ranged": per_tier(["bhil", "villager"], ["bhil", "paik"], ["paik", "paik_quilted"], ["paik_quilted", "sipahi_turban"]),
                   "levy": per_tier(["villager", "paik"], ["villager", "paik"], ["paik", "paik_quilted"], ["paik", "paik_quilted"])},
    "class_offhand": {"all": ["dhal"], "levy": ["dhal"], "sentry": ["dhal"]},
}
INDIA["units"]["crossbowman"] = INDIA["units"]["archer"]
INDIA["units"]["matchlock_man"] = INDIA["units"]["handgonne_man"]

# ---------------------------------------------------------------------------------------------------------------- the Maya
# The Classic and Postclassic Maya: the holkan (warriors) in quilted cotton (ichcahuipilli) with flint-bladed spears,
# obsidian-edged clubs and shields, hunters with bows, the jaguar and feathered-serpent warrior orders, the lords (ajaw)
# in jade and quetzal plumes. The Maya had no horses: their riders are dressed as the warriors they would have been.
MAYA = {
    "kits": {
        "commoner": ("holkan_headband", "manta_cape", "ex_loincloth", "xanab_sandals"),
        "hunter": ("deer_headdress", "manta_cape", "ex_loincloth", "xanab_sandals"),
        "holkan": ("holkan_headband", "quilted_cotton_armour", "ex_loincloth", "xanab_sandals"),
        "holkan_kilt": ("cotton_war_cap", "quilted_cotton_armour", "cotton_war_kilt", "xanab_sandals"),
        "serpent": ("serpent_headdress", "quilted_cotton_armour", "cotton_war_kilt", "jade_anklets"),
        "jaguar_cotton": ("jaguar_helmet", "quilted_cotton_armour", "jaguar_kilt", "jaguar_leg_wraps"),
        "jaguar": ("jaguar_helmet", "jaguar_pelt_armour", "jaguar_kilt", "jaguar_leg_wraps"),
        "noble": ("quetzal_headdress", "feathered_war_suit", "cotton_war_kilt", "jade_anklets"),
        "ajaw": ("quetzal_headdress", "jade_pectoral_armour", "jaguar_kilt", "jade_anklets"),
    },
    "units": {
        "militia": per_tier(["commoner", "hunter"], ["commoner", "holkan"], ["holkan", "holkan_kilt"], ["holkan_kilt", "holkan"]),
        "spear_man": per_tier(["commoner", "holkan"], ["holkan", "holkan_kilt"], ["holkan_kilt", "serpent"], ["serpent", "jaguar_cotton"]),
        "shieldman": per_tier(["holkan"], ["holkan_kilt", "holkan"], ["holkan_kilt", "serpent"], ["serpent", "jaguar_cotton"]),
        "warrior": per_tier(["holkan"], ["holkan_kilt", "serpent"], ["jaguar_cotton", "serpent", "jaguar"], ["jaguar", "noble", "ajaw"]),
        "archer": per_tier(["hunter", "commoner"], ["hunter", "holkan"], ["holkan", "holkan_kilt"], ["holkan_kilt", "serpent"]),
        "light_lancer_rider": per_tier(["holkan"], ["holkan_kilt"], ["holkan_kilt", "serpent"], ["serpent", "jaguar_cotton"]),
        "archer_rider": per_tier(["hunter"], ["hunter", "holkan"], ["holkan", "holkan_kilt"], ["holkan_kilt", "serpent"]),
        "lancer_rider": per_tier(["holkan_kilt"], ["serpent"], ["jaguar_cotton", "jaguar"], ["jaguar", "noble"]),
    },
    "mainhand": {
        "militia": ["macuahuitl"], "spear_man": ["flint_spear"], "shieldman": ["macuahuitl"], "warrior": ["flint_axe"], "archer": ["maya_bow"],
    },
    "offhand": {"shieldman": ["pakal", "flexible_shield"], "light_lancer_rider": ["pakal"], "lancer_rider": ["pakal", "flexible_shield"]},
    "armory_weapons_from": "WATCH",                 # stone and wood: no smith needed
    "class_kits": {"all": per_tier(["commoner", "holkan"], ["holkan", "holkan_kilt"], ["holkan_kilt", "serpent", "jaguar_cotton"],
                                   ["serpent", "jaguar", "jaguar_cotton"]),
                   "militia": per_tier(["commoner"], ["commoner", "holkan"], ["holkan"], ["holkan", "holkan_kilt"]),
                   "ranged": per_tier(["hunter", "commoner"], ["hunter", "holkan"], ["holkan", "holkan_kilt"], ["holkan_kilt"]),
                   "line": per_tier(["holkan"], ["holkan", "holkan_kilt"], ["holkan_kilt", "serpent"], ["serpent", "jaguar_cotton"]),
                   "levy": per_tier(["commoner", "holkan"], ["commoner", "holkan"], ["holkan", "holkan_kilt"], ["holkan", "holkan_kilt"])},
    "class_offhand": {"all": ["pakal", "flexible_shield"], "levy": ["pakal"], "sentry": ["pakal", "flexible_shield"]},
}
MAYA["units"]["crossbowman"] = MAYA["units"]["archer"]

# ---------------------------------------------------------------------------------------------------------------- the Inuit
# The Arctic peoples: hunters in caribou and sealskin with harpoons and sinew-backed bows; in the feuds of the Bering
# Strait, armour of bone and ivory plates, slats and walrus hide. No shields, no horses.
INUIT = {
    "kits": {
        "caribou": ("parka_hood", "caribou_parka", "caribou_trousers", "caribou_kamik"),
        "seal": ("parka_hood", "sealskin_parka", "sealskin_trousers", "sealskin_kamik"),
        "bird": ("wolverine_hood", "bird_skin_parka", "sealskin_trousers", "sealskin_kamik"),
        "polar": ("wolverine_hood", "caribou_parka", "polar_bear_trousers", "polar_bear_kamik"),
        "kayak": ("bentwood_visor", "gut_skin_parka", "sealskin_trousers", "sealskin_kamik"),
        "goggles": ("snow_goggle_hood", "caribou_parka", "caribou_trousers", "caribou_kamik"),
        "walrus": ("bone_plate_hood", "walrus_hide_armour", "caribou_trousers", "caribou_kamik"),
        "bone": ("bone_plate_hood", "bone_plate_armour", "bone_plate_leggings", "caribou_kamik"),
        "slat": ("ivory_lamellar_helmet", "slat_armour", "bone_plate_leggings", "sealskin_kamik"),
        "ivory": ("ivory_lamellar_helmet", "bone_plate_armour", "bone_plate_leggings", "polar_bear_kamik"),
        "elder": ("wolverine_hood", "ceremonial_parka", "polar_bear_trousers", "polar_bear_kamik"),
    },
    "units": {
        "militia": per_tier(["caribou", "seal"], ["caribou", "seal", "bird"], ["bird", "polar", "walrus"], ["walrus", "polar"]),
        "spear_man": per_tier(["caribou", "seal"], ["seal", "goggles", "walrus"], ["walrus", "bone"], ["bone", "slat"]),
        "shieldman": per_tier(["seal"], ["walrus"], ["walrus", "bone"], ["bone", "slat"]),
        "warrior": per_tier(["caribou"], ["walrus", "polar"], ["bone", "walrus", "slat"], ["slat", "ivory", "bone", "elder"]),
        "archer": per_tier(["goggles", "caribou"], ["goggles", "bird", "kayak"], ["walrus", "polar", "goggles"], ["bone", "slat"]),
        "light_lancer_rider": per_tier(["caribou"], ["polar", "caribou"], ["walrus", "polar"], ["bone", "walrus"]),
        "archer_rider": per_tier(["goggles"], ["goggles", "bird"], ["walrus", "goggles"], ["bone", "slat"]),
        "lancer_rider": per_tier(["walrus"], ["walrus", "bone"], ["bone", "slat"], ["ivory", "slat"]),
    },
    "mainhand": {
        "militia": ["savik"], "spear_man": ["harpoon", "ivory_lance"], "shieldman": ["savik"], "warrior": ["bone_club"], "archer": ["sinew_bow"],
    },
    "offhand": {"shieldman": ["none"], "light_lancer_rider": ["none"], "lancer_rider": ["none"]},
    "armory_weapons_from": "WATCH",
}
INUIT["units"]["crossbowman"] = INUIT["units"]["archer"]

# ---------------------------------------------------------------------------------------------------------------- the Normans
# The Normans (11th-12th c.), in Epic Knights' own armour (and its addon's): the peasant levy in tunics, serjeants in
# gambeson or mail with the nasal helmet, archers and crossbowmen (both at Hastings), the milites in mail and surcoat,
# and by the late 12th century the great helm.
_E, _A = "magistuarmory:", "magistuarmoryaddon:"
NORMAN = {
    "kits": {
        "peasant": (_A + "linen_coif", _A + "tunic", _E + "pantyhose", _A + "tunic_boots"),
        "peasant_ek": (_E + "coif", _E + "gambeson_chestplate", _E + "pantyhose", _E + "gambeson_boots"),
        "levy": (_A + "chapel", _A + "chained_gambeson", _E + "pantyhose", _A + "chained_gambeson_boots"),
        "serjeant": (_E + "norman_helmet", _E + "gambeson_chestplate", _E + "pantyhose", _E + "gambeson_boots"),
        "serjeant_mail": (_E + "norman_helmet", _E + "chainmail_chestplate", _E + "chainmail_leggings", _E + "chainmail_boots"),
        "serjeant_kettle": (_E + "kettlehat", _E + "chainmail_chestplate", _E + "pantyhose", _E + "gambeson_boots"),
        "coif_mail": (_E + "chainmail_helmet", _E + "chainmail_chestplate", _E + "chainmail_leggings", _E + "chainmail_boots"),
        "archer": (_E + "coif", _E + "gambeson_chestplate", _E + "pantyhose", _E + "gambeson_boots"),
        "archer_addon": (_A + "cervelliere", _A + "chained_gambeson", _E + "pantyhose", _A + "chained_gambeson_boots"),
        "miles": (_E + "norman_helmet", _E + "crusader_chestplate", _E + "chainmail_leggings", _E + "chainmail_boots"),
        "miles_13": (_A + "early_greathelm", _A + "xiii_century_knight_chestplate", _A + "xiii_century_knight_leggings",
                     _A + "xiii_century_knight_boots"),
        "miles_great": (_E + "greathelm", _E + "crusader_chestplate", _E + "crusader_leggings", _E + "crusader_boots"),
    },
    "units": {
        "militia": per_tier(["peasant", "peasant_ek"], ["peasant", "levy", "peasant_ek"], ["levy", "serjeant"], ["serjeant", "levy"]),
        "spear_man": per_tier(["peasant_ek", "levy"], ["serjeant", "levy"], ["serjeant", "serjeant_kettle"], ["serjeant_mail", "serjeant_kettle"]),
        "shieldman": per_tier(["serjeant"], ["serjeant", "serjeant_kettle"], ["serjeant_mail", "serjeant_kettle"], ["serjeant_mail", "coif_mail"]),
        "warrior": per_tier(["serjeant"], ["serjeant_mail"], ["serjeant_mail", "coif_mail", "miles"], ["miles", "coif_mail", "miles_13"]),
        "archer": per_tier(["peasant_ek", "archer_addon"], ["archer", "archer_addon"], ["archer", "serjeant"], ["serjeant", "serjeant_kettle"]),
        "crossbowman": per_tier(["archer", "archer_addon"], ["archer", "serjeant_kettle"], ["serjeant_kettle", "serjeant"],
                                ["serjeant_kettle", "serjeant_mail"]),
        "light_lancer_rider": per_tier(["serjeant"], ["serjeant", "serjeant_mail"], ["serjeant_mail", "coif_mail"], ["coif_mail", "miles"]),
        "archer_rider": per_tier(["archer"], ["archer", "serjeant"], ["serjeant", "serjeant_kettle"], ["serjeant_mail"]),
        "lancer_rider": per_tier(["serjeant_mail"], ["serjeant_mail", "miles"], ["miles", "coif_mail"], ["miles", "miles_great", "miles_13"]),
    },
    "mainhand": {},
    "offhand": {"lancer_rider": ["@kiteshield"]},
    "armory_weapons_from": "STRONGHOLD",
}

CULTURES = {"millenaire:byzantines": BYZ, "millenaire:seljuk": SELJUK, "millenaire:japanese": JAPAN, "millenaire:indian": INDIA,
            "millenaire:mayan": MAYA, "millenaire:inuits": INUIT, "millenaire:norman": NORMAN}


# squad looks: (culture, kits, weapons, shields) put first in each look (with the armory installed, its pieces are worn)
SQUADS = {
    # India
    "in.paika": (INDIA, ["villager", "paik"], ["steel_talwar", "steel_barchha"], ["dhal"]),
    "in.bhil": (INDIA, ["bhil"], ["dhanush"], []),
    "in.nayar": (INDIA, ["nayar"], ["steel_talwar"], ["dhal"]),
    "in.dhal": (INDIA, ["paik_quilted", "sipahi_turban"], ["steel_talwar", "steel_katar"], ["dhal"]),
    "in.rajput_foot": (INDIA, ["rajput", "sipahi_turban"], ["steel_khanda", "steel_talwar"], ["dhal"]),
    "in.royal_spears": (INDIA, ["sipahi", "vijayanagara"], ["steel_barchha"], ["dhal"]),
    "in.imperial_archers": (INDIA, ["sipahi_turban", "paik_quilted"], ["dhanush"], []),
    "in.dhanurdhara": (INDIA, ["paik", "paik_quilted"], ["dhanush"], []),
    "in.hill_archers": (INDIA, ["bhil"], ["dhanush"], []),
    "in.temple_bows": (INDIA, ["villager", "paik"], ["dhanush"], []),
    "in.rajput_lancers": (INDIA, ["rajput", "sipahi"], [], ["dhal"]),
    "in.silladar": (INDIA, ["sipahi_turban", "rajput"], [], ["dhal"]),
    "in.pindari": (INDIA, ["villager", "paik"], [], ["dhal"]),
    "in.turki_archers": (INDIA, ["sipahi_turban", "sipahi"], [], []),
    "in.kshatriya": (INDIA, ["noble", "heavy", "heavy_top"], ["steel_khanda", "steel_gada"], ["dhal"]),
    "in.temple_defenders": (INDIA, ["vijayanagara", "paik_quilted"], ["steel_gada", "steel_talwar"], ["dhal"]),
    # the Maya
    "maya.milpa_levy": (MAYA, ["commoner", "holkan"], ["flint_spear", "macuahuitl"], ["pakal"]),
    "maya.hunters": (MAYA, ["hunter"], ["maya_bow"], []),
    "maya.holkan": (MAYA, ["holkan", "holkan_kilt"], ["macuahuitl", "flint_spear"], ["pakal", "flexible_shield"]),
    "maya.atlatl": (MAYA, ["holkan", "commoner"], ["flint_spear"], []),
    "maya.jaguar": (MAYA, ["jaguar", "jaguar_cotton"], ["macuahuitl", "flint_axe"], ["pakal"]),
    "maya.nacom_guard": (MAYA, ["noble", "serpent"], ["macuahuitl", "flint_spear"], ["pakal"]),
    "maya.royal_archers": (MAYA, ["serpent", "holkan_kilt"], ["maya_bow"], []),
    "maya.temple_archers": (MAYA, ["holkan_kilt", "hunter"], ["maya_bow"], []),
    "maya.bowmen": (MAYA, ["holkan", "hunter"], ["maya_bow"], []),
    "maya.skirmishers": (MAYA, ["commoner", "hunter"], ["flint_spear", "maya_bow"], []),
    "maya.runners": (MAYA, ["commoner"], [], []),
    "maya.serpent": (MAYA, ["serpent"], ["macuahuitl"], ["flexible_shield", "pakal"]),
    "maya.captive_takers": (MAYA, ["holkan_kilt", "jaguar_cotton"], ["macuahuitl", "flint_axe"], ["pakal"]),
    "maya.eagle": (MAYA, ["noble", "serpent"], ["macuahuitl"], ["pakal"]),
    "maya.sacred_war_band": (MAYA, ["ajaw", "noble", "jaguar"], ["macuahuitl", "flint_axe"], ["pakal"]),
    "maya.city_guard": (MAYA, ["holkan_kilt", "serpent"], ["flint_spear", "macuahuitl"], ["flexible_shield", "pakal"]),
    # 1.5's new pieces in the other cultures' squads
    "jp.ashigaru_yari": (JAPAN, ["ashigaru_sashimono"], [], []),
    "jp.nobushi": (JAPAN, ["kogashira"], [], []),
    "jp.sohei": (JAPAN, ["sohei"], [], []),
    "jp.ashigaru_tate": (JAPAN, ["ashigaru_sashimono"], [], []),
    "jp.samurai": (JAPAN, ["tosei", "hotoke"], ["steel_tachi", "steel_nagamaki"], []),
    "jp.hatamoto": (JAPAN, ["taisho", "nanban"], [], []),
    "jp.yumi_samurai": (JAPAN, ["tosei"], [], []),
    "jp.ashigaru_yumi": (JAPAN, ["ashigaru_sashimono"], [], []),
    "jp.kiba_musha": (JAPAN, ["tosei"], [], []),
    "jp.mounted_samurai": (JAPAN, ["tosei", "menpo"], [], []),
    "jp.daimyo_guard": (JAPAN, ["taisho", "daimyo", "nanban"], [], []),
    "jp.ronin": (JAPAN, ["kogashira", "hotoke"], ["steel_tachi"], []),
    "jp.hunters": (JAPAN, ["peasant"], [], []),
    "seljuk.ahdath": (SELJUK, ["peasant"], [], []),
    "seljuk.tribal_foot": (SELJUK, ["shepherd"], [], []),
    "seljuk.tribal_bows": (SELJUK, ["shepherd"], [], []),
    "seljuk.turkmen_horse_archers": (SELJUK, ["kipchak_light"], [], []),
    "seljuk.mamluk_horse": (SELJUK, ["kipchak"], [], []),
    "seljuk.mamluk_foot": (SELJUK, ["kipchak"], [], []),
    "seljuk.atabeg_retinue": (SELJUK, ["court", "emir"], [], []),
    "seljuk.iqta_cavalry": (SELJUK, ["kipchak"], [], []),
    "byz.thematic_levy": (BYZ, ["peasant"], [], []),
    "byz.akritai": (BYZ, ["akrites", "akrites_kassis"], [], []),
    "byz.excubitors": (BYZ, ["excubitor"], [], []),
    "byz.psiloi": (BYZ, ["toxotes_hat"], [], []),
    "byz.toxotai": (BYZ, ["toxotes_hat"], [], []),
    "byz.prokoursatores": (BYZ, ["akrites"], [], []),
    "byz.skoutatoi": (BYZ, ["skutatos_scale", "skutatos_mail"], [], []),
    "byz.menavlatoi": (BYZ, ["menavlatos"], [], []),
    "byz.tagma": (BYZ, ["skutatos_scale"], [], []),
    "inuit.kayak_raiders": (INUIT, ["kayak"], [], []),
    "inuit.whale_hunt": (INUIT, ["kayak", "bird"], [], []),
    "inuit.winter_warband": (INUIT, ["polar"], [], []),
    "inuit.shaman_band": (INUIT, ["elder"], [], []),
    "inuit.tundra_scouts": (INUIT, ["bird"], [], []),
    "inuit.feud_party": (INUIT, ["bird", "polar"], [], []),
}


def squads():
    with open(LOOKS, encoding="utf-8") as f:
        doc = json.load(f)
    looks = doc["looks"]
    for name, (spec, kit_names, weapons, shields) in SQUADS.items():
        look = looks[name]
        add = [kit(spec["kits"][n]) for n in kit_names]
        look["kits"] = add + [k for k in look.get("kits", []) if k not in add]
        items = look.setdefault("items", {}) if "items" in look else look
        for slot, ids in (("mainhand", weapons), ("offhand", shields)):
            if ids:
                new = [ca(i) for i in ids]
                items[slot] = new + [i for i in items.get(slot, []) if i not in new]
    with open(LOOKS, "w", encoding="utf-8") as f:
        f.write(json.dumps(doc, indent=1, ensure_ascii=False) + "\n")
    print(f"squad looks: {len(SQUADS)} given the armory's pieces")


def lookup(src, tier, roles, key):
    for role in roles:
        v = src.get(tier, {}).get(role, {}).get(key)
        if v:
            return v
    return []


def ek_kits(culture, defaults, tier, cls):
    for src in (culture, defaults):
        for role in (cls, "all"):
            ks = [k for k in src.get(tier, {}).get(role, {}).get("kits", []) if not armory(k)]
            if ks:
                return ks
    return []


def ek_items(culture, defaults, tier, cls, unit, slot):
    fams = FAMILIES.get(unit, {}).get(slot)
    if not fams:
        return []
    out = []
    for src in (culture, defaults):
        for role in (cls, "all", "sentry"):
            for i in src.get(tier, {}).get(role, {}).get(slot, []):
                if i != "none" and not i.startswith("culturesarmory:") and family(i) in fams and i not in out:
                    out.append(i)
        if out:
            return out
    return out


def main():
    with open(PROFILES, encoding="utf-8") as f:
        prof = json.load(f)
    defaults = prof["defaults"]["tiers"]
    total = 0
    for cname, spec in CULTURES.items():
        tiers = prof["cultures"][cname]["tiers"]
        for tier in TIERS:
            t = tiers.setdefault(tier, {})
            for role in [r for r in t if r.startswith("unit:")]:
                del t[role]
            # a culture new to the armory: its class and duty lists get its own kits and shields first
            for role, by_tier in spec.get("class_kits", {}).items():
                entry = t.setdefault(role, {})
                ek = [k for k in entry.get("kits", []) if not armory(k)] or ek_kits(tiers, defaults, tier, role if role != "levy" else "militia")
                entry["kits"] = [kit(spec["kits"][n]) for n in by_tier[tier]] + ek
            for role, items in spec.get("class_offhand", {}).items():
                entry = t.setdefault(role, {})
                rest = [i for i in entry.get("offhand", []) if not i.startswith("culturesarmory:")]
                entry["offhand"] = [ca(i) for i in items] + rest
            armory_weapons = TIERS.index(tier) >= TIERS.index(spec["armory_weapons_from"])
            for unit, by_tier in spec["units"].items():
                cls = CLASS[unit]
                entry = {}
                kits = [kit(spec["kits"][n]) for n in by_tier[tier]]
                if any(armory(k) for k in kits):
                    kits += ek_kits(tiers, defaults, tier, cls)[:3]
                entry["kits"] = kits
                own = [ca(i) for i in spec["mainhand"].get(unit, [])] if armory_weapons else []
                main_ek = ek_items(tiers, defaults, tier, cls, unit, "mainhand")
                if own or (main_ek and spec["mainhand"].get(unit)):
                    entry["mainhand"] = own + main_ek
                off = []
                for i in spec["offhand"].get(unit, []):
                    if i.startswith("@"):
                        off.append(f"magistuarmory:{ek_mat(tier)}_{i[1:]}")
                    else:
                        off.append(ca(i))
                if off:
                    if "none" not in off:
                        off += [i for i in ek_items(tiers, defaults, tier, cls, unit, "offhand") if i not in off]
                    entry["offhand"] = off
                t["unit:" + unit] = entry
                total += 1
                assert any(not armory(k) for k in entry["kits"]), (cname, tier, unit, "needs an Epic Knights kit")
                assert any(all(v.startswith("magistuarmory:") or v == "none" for v in k.values())
                           for k in entry["kits"]), (cname, tier, unit, "needs a kit of Epic Knights' own pieces")
    with open(PROFILES, "w", encoding="utf-8") as f:
        f.write(json.dumps(prof, indent=1) + "\n")
    print(f"unit lists: {total} (cultures {len(CULTURES)} x tiers {len(TIERS)})")
    squads()


if __name__ == "__main__":
    main()
