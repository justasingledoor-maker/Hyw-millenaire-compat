#!/usr/bin/env python3
"""
Writes the 1.6 squads: more culture squads for every village's Muster Roll (with the armory's 1.5 armour), and the
mercenary companies hired at a Muster Roll set down outside any village (hywmill_squads/mercenaries.json), found by the
land they roam and the people of the nearest village, with a look of their own each (hywmill_equipment/squad_looks.json).
Rerunning replaces what it wrote before.

    python3 devtools/squads_gen.py
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import unit_kits as uk  # noqa: E402

RES = os.path.join(HERE, "..", "src/main/resources/data/hywmill")
SQUADS = os.path.join(RES, "hywmill_squads/defaults.json")
COMPANIES = os.path.join(RES, "hywmill_squads/mercenaries.json")
LOOKS = os.path.join(RES, "hywmill_equipment/squad_looks.json")
PROFILES = os.path.join(RES, "hywmill_equipment/profiles.json")

E, A, SL = "magistuarmory:", "magistuarmoryaddon:", "slavicarmory:"
G, S = "GUARD_POST", "STRONGHOLD"
SPEC = {"millenaire:byzantines": uk.BYZ, "millenaire:seljuk": uk.SELJUK, "millenaire:japanese": uk.JAPAN, "millenaire:indian": uk.INDIA,
        "millenaire:mayan": uk.MAYA, "millenaire:inuits": uk.INUIT, "millenaire:norman": uk.NORMAN}
CLASS = {"militia": "militia", "spear_man": "line", "shieldman": "line", "warrior": "line", "archer": "ranged", "crossbowman": "ranged",
         "light_lancer_rider": "scout", "archer_rider": "scout", "lancer_rider": "scout"}

# Epic Knights kits (head, chest, legs, feet) for the Normans and the companies that wear Latin or steppe gear
EK = {
    "tunic": (A + "linen_coif", A + "tunic", E + "pantyhose", A + "tunic_boots"),
    "gambeson": (E + "coif", E + "gambeson_chestplate", E + "pantyhose", E + "gambeson_boots"),
    "gambeson_kettle": (E + "kettlehat", E + "gambeson_chestplate", E + "pantyhose", E + "gambeson_boots"),
    "chained": (A + "chapel", A + "chained_gambeson", E + "pantyhose", A + "chained_gambeson_boots"),
    "serjeant": (E + "norman_helmet", E + "gambeson_chestplate", E + "pantyhose", E + "gambeson_boots"),
    "mail": (E + "norman_helmet", E + "chainmail_chestplate", E + "chainmail_leggings", E + "chainmail_boots"),
    "mail_coif": (E + "chainmail_helmet", E + "chainmail_chestplate", E + "chainmail_leggings", E + "chainmail_boots"),
    "kettle_mail": (E + "kettlehat", E + "chainmail_chestplate", E + "pantyhose", E + "gambeson_boots"),
    "brigandine": (E + "kettlehat", E + "brigandine_chestplate", E + "chainmail_leggings", E + "chainmail_boots"),
    "crusader": (E + "norman_helmet", E + "crusader_chestplate", E + "chainmail_leggings", E + "chainmail_boots"),
    "greathelm": (E + "greathelm", E + "crusader_chestplate", E + "crusader_leggings", E + "crusader_boots"),
    "dark_crusader": (A + "dark_crusader_helmet", A + "dark_crusader_chestplate", A + "dark_crusader_leggings", A + "dark_crusader_boots"),
    "knight13": (A + "early_greathelm", A + "xiii_century_knight_chestplate", A + "xiii_century_knight_leggings", A + "xiii_century_knight_boots"),
    "gallowglass": (E + "kettlehat", A + "gallowglass_chestplate", A + "gallowglass_leggings", A + "gallowglass_boots"),
    "gallowglass_ek": (E + "kettlehat", E + "chainmail_chestplate", E + "chainmail_leggings", E + "gambeson_boots"),
    "saracen": (A + "saracen_helmet", A + "saracen_chestplate", E + "pantyhose", A + "saracen_boots"),
    "cuman": (A + "cuman_helmet", E + "lamellar_chestplate", E + "pantyhose", E + "lamellar_boots"),
    "cuman_light": (A + "light_cuman_helmet", E + "gambeson_chestplate", E + "pantyhose", E + "lamellar_boots"),
    "lamellar": (E + "kettlehat", E + "lamellar_chestplate", E + "pantyhose", E + "lamellar_boots"),
    "rus": (SL + "nikolskoe_helmet", SL + "rus_chainmail_chestplate", SL + "rus_chainmail_leggings", SL + "rus_chainmail_boots"),
    "rus_gambeson": (SL + "eastern_kettlehat", SL + "rus_gambeson", E + "pantyhose", SL + "rus_gambeson_boots"),
    "varangian_slav": (SL + "varangian_helmet", SL + "rus_chainmail_chestplate", SL + "rus_chainmail_leggings", SL + "rus_chainmail_boots"),
}


def kits_of(culture, names):
    """Armory kits by the unit_kits names of a culture, or Epic Knights kits by an "ek:" name."""
    out = []
    for n in names:
        t = EK[n[3:]] if n.startswith("ek:") else SPEC[culture]["kits"][n]
        out.append(uk.kit(t))
    return out


def ek_fallback(prof, culture, tier, cls):
    """The culture's own Epic Knights kits for the class (worn when the armory is absent)."""
    tiers = prof["cultures"][culture]["tiers"]
    out = uk.ek_kits(tiers, prof["defaults"]["tiers"], tier, cls)
    plain = [k for k in out if all(v.startswith(E) or v == "none" for v in k.values())]
    return (plain[:1] + [k for k in out if k not in plain[:1]])[:2]


def arms(base, pattern, colour):
    return [{"base": base, "layers": [{"pattern": pattern, "color": colour}]}]


def squad(id, name, cat, q, desc, members, kits, main=(), off=(), palette=("#8E2A2A", "#E9E4D4"), arms_=None, village=False, **extra):
    """members: (unit, count[, kit]) — gear by quality (high: stronghold, else guard post)."""
    gear = S if q == "HIGH" else G
    m = []
    for x in members:
        e = {"unit": x[0], "count": x[1], "gear": x[2] if len(x) > 2 and x[2] in (G, S, "GARRISON") else gear}
        if x[-1] == "levy":
            e["kit"] = "levy"
        m.append(e)
    d = {"id": id, "name": name, "category": cat, "quality": q, "look": id, "description": desc, "members": m}
    if village:
        d["livery"] = "village"
    d.update(extra)
    return d, {"kits": kits, "main": list(main), "off": list(off), "palette": list(palette), "arms": arms_ or arms("white", "minecraft:border", "red")}


# ---------------------------------------------------------------------------------------------------------- village squads

def village_squads():
    C = {}
    n = "millenaire:norman"
    C[n] = [
        squad("norman.castle_serjeants", "Castle Serjeants", "INFANTRY", "LOW", "The castle's paid serjeants: spear, shield and a nasal helmet.",
              [("shieldman", 4), ("spear_man", 6)], kits_of(n, ["ek:serjeant", "ek:gambeson_kettle"]), off=[E + "iron_kiteshield", E + "wood_kiteshield"],
              palette=("#8E2A2A", "#E9E4D4"), village=True),
        squad("norman.flemish_spearmen", "Flemish Spearmen", "INFANTRY", "MEDIUM",
              "Flemings in Anglo-Norman pay, close-packed spearmen who held the line in the Anarchy.",
              [("spear_man", 10)], kits_of(n, ["ek:kettle_mail", "ek:chained"]), main=[E + "steel_pike", E + "iron_pike"],
              palette=("#D4A22A", "#222222"), arms_=arms("yellow", "magistuarmory:lion1", "black")),
        squad("norman.templar_serjeants", "Templar Serjeants", "INFANTRY", "MEDIUM",
              "Serjeant-brothers of the Temple in black and brown, the order's footmen and squires.",
              [("shieldman", 6), ("spear_man", 4)], kits_of(n, ["ek:dark_crusader", "ek:mail"]), off=[E + "steel_kiteshield"],
              palette=("#222222", "#6A4A30"), arms_=arms("black", "magistuarmory:crusader_cross", "white")),
        squad("norman.huscarls", "Huscarls", "INFANTRY", "HIGH",
              "English household men who kept the great Dane axe after the Conquest; some sailed to Constantinople.",
              [("warrior", 10)], kits_of(n, ["ek:mail", "ek:mail_coif"]), main=[E + "steel_lochaberaxe"],
              palette=("#2E4A7D", "#D4A22A"), arms_=arms("blue", "magistuarmory:dragon", "yellow")),
        squad("norman.welsh_bowmen", "Welsh Bowmen", "RANGED", "MEDIUM",
              "Bowmen of the March of Wales, whose arrows Gerald of Wales saw pierce an oak door.",
              [("archer", 10)], kits_of(n, ["ek:tunic", "ek:gambeson"]), main=[E + "longbow"],
              palette=("#3F6B35", "#E9E4D4"), arms_=arms("green", "magistuarmory:dragon", "red")),
        squad("norman.sicilian_saracens", "Saracen Archers of Sicily", "RANGED", "MEDIUM",
              "Muslim archers in the pay of the Norman kings of Sicily, from Roger II to Lucera.",
              [("archer", 10)], kits_of(n, ["ek:saracen", "ek:lamellar"]), main=[E + "longbow"],
              palette=("#2F6B3A", "#E9E4D4"), arms_=arms("green", "minecraft:circle", "white")),
        squad("norman.breton_horse", "Breton Horse", "CAVALRY", "MEDIUM",
              "Breton horsemen, who held the left at Hastings and feigned flight.",
              [("light_lancer_rider", 10)], kits_of(n, ["ek:serjeant", "ek:kettle_mail"]), off=[E + "iron_roundshield"],
              palette=("#E9E4D4", "#222222"), arms_=arms("white", "minecraft:stripe_center", "black")),
        squad("norman.hospitaller_knights", "Hospitaller Knights", "CAVALRY", "HIGH",
              "Knights of the Hospital of St John, in black surcoats with the white cross.",
              [("lancer_rider", 8)], kits_of(n, ["ek:greathelm", "ek:knight13", "ek:crusader"]), off=[E + "steel_kiteshield"],
              palette=("#222222", "#E9E4D4"), arms_=arms("black", "minecraft:straight_cross", "white")),
        squad("norman.apulian_adventurers", "Adventurers of Apulia", "UNIQUE", "MEDIUM",
              "Norman fortune-seekers of the south, sons of Tancred's house: knights and their serjeants.",
              [("lancer_rider", 4), ("shieldman", 4), ("crossbowman", 2)], kits_of(n, ["ek:crusader", "ek:mail", "ek:kettle_mail"]),
              off=[E + "iron_kiteshield"], palette=("#2E4A7D", "#E9E4D4"), arms_=arms("blue", "minecraft:half_horizontal", "white")),
    ]
    b = "millenaire:byzantines"
    C[b] = [
        squad("byz.ploimoi", "Ploïmoi Marines", "INFANTRY", "LOW", "The fleet's fighting men, in tunics and padded coats, spear and shield.",
              [("spear_man", 6), ("shieldman", 4)], kits_of(b, ["peasant", "levy"]), main=["culturesarmory:steel_kontarion"],
              off=["culturesarmory:aspis"], palette=("#2F4B7A", "#E6E1D3"), arms_=arms("blue", "minecraft:circle", "white"), village=True),
        squad("byz.kontaratoi", "Kontaratoi", "INFANTRY", "MEDIUM", "The heavy spearmen of the Praecepta's front rank, in scale and Phrygian helmets.",
              [("spear_man", 10)], kits_of(b, ["skutatos_scale", "skutatos"]), main=["culturesarmory:steel_kontarion"],
              off=["culturesarmory:skoutarion", "culturesarmory:aspis"], palette=("#8E2A2A", "#D4A22A"),
              arms_=arms("red", "magistuarmory:orthodox_cross", "yellow"), village=True),
        squad("byz.armenian_foot", "Armenian Foot", "INFANTRY", "MEDIUM", "Armenian infantry, the best of the themes' foot in Leo VI's eyes.",
              [("shieldman", 6), ("spear_man", 4)], kits_of(b, ["akrites_kassis", "skutatos_mail"]), main=["culturesarmory:steel_spathion"],
              off=["culturesarmory:aspis"], palette=("#5E2A5C", "#D4A22A"), arms_=arms("purple", "magistuarmory:eagle", "yellow")),
        squad("byz.hetaireia", "Hetaireia", "INFANTRY", "HIGH", "The emperor's companions of the palace, in gilt scale under the strategos' crest.",
              [("shieldman", 6), ("warrior", 4)], kits_of(b, ["excubitor", "strategos"]),
              main=["culturesarmory:steel_spathion", "culturesarmory:steel_paramerion"], off=["culturesarmory:skoutarion"],
              palette=("#5E2A5C", "#D4A22A"), arms_=arms("purple", "magistuarmory:two_headed_eagle", "yellow")),
        squad("byz.solenarioi", "Solenarioi", "RANGED", "MEDIUM", "Archers with the solenarion, the arrow-guide that shot short darts far.",
              [("archer", 10)], kits_of(b, ["toxotes_mail", "toxotes_hat"]), main=["culturesarmory:toxarion"],
              palette=("#3E6B3A", "#E6E1D3"), arms_=arms("green", "minecraft:border", "white"), village=True),
        squad("byz.armenian_archers", "Armenian Archers", "RANGED", "LOW", "Archers of the eastern themes, in head cloths and padded coats.",
              [("archer", 10)], kits_of(b, ["toxotes_cloth", "toxotes_hat"]), main=["culturesarmory:toxarion"],
              palette=("#A8782E", "#E6E1D3"), arms_=arms("orange", "minecraft:circle", "white")),
        squad("byz.trapezitai", "Trapezitai", "CAVALRY", "LOW", "Light horse of the frontier, raiders and scouts in leather lamellar.",
              [("light_lancer_rider", 10)], kits_of(b, ["akrites", "trapezites"]), off=["culturesarmory:aspis"],
              palette=("#2F4B7A", "#A8782E"), arms_=arms("blue", "minecraft:half_horizontal", "orange"), village=True),
        squad("byz.vardariotai", "Vardariotai", "CAVALRY", "MEDIUM", "The palace's Magyar horse archers of the Vardar, in Turkic gear.",
              [("archer_rider", 10)], kits_of("millenaire:seljuk", ["kipchak_light", "turkmen_quilted"]),
              palette=("#8E2A2A", "#2F4B7A"), arms_=arms("red", "magistuarmory:sun", "blue")),
        squad("byz.scholai", "Scholai", "CAVALRY", "HIGH", "The first of the imperial tagmata, heavy horse of the capital.",
              [("lancer_rider", 8)], kits_of(b, ["strategos", "kataphraktos"]), off=["culturesarmory:skoutarion"],
              palette=("#5E2A5C", "#D4A22A"), arms_=arms("purple", "magistuarmory:orthodox_cross", "yellow")),
        squad("byz.pechenegs", "Pecheneg Federates", "UNIQUE", "MEDIUM", "Steppe horse archers settled in the Balkans as the empire's allies.",
              [("archer_rider", 8), ("light_lancer_rider", 2)], kits_of("millenaire:seljuk", ["kipchak_light", "kipchak"]),
              palette=("#6A4A30", "#D4A22A"), arms_=arms("brown", "magistuarmory:sun", "yellow")),
    ]
    sj = "millenaire:seljuk"
    C[sj] = [
        squad("seljuk.anatolian_levy", "Anatolian Levy", "INFANTRY", "LOW", "Peasants of Rum in çarık and leg wraps, called out with their spears.",
              [("spear_man", 10, "levy")], kits_of(sj, ["peasant", "shepherd"]), main=["culturesarmory:steel_mizrak"],
              off=["culturesarmory:kalkan"], palette=("#E8E4D8", "#8E2A2A"), village=True),
        squad("seljuk.ahi_brotherhood", "Ahi Brotherhood", "INFANTRY", "MEDIUM", "The craftsmen's brotherhoods (futuwwa) who kept Anatolia's towns.",
              [("shieldman", 6), ("spear_man", 4)], kits_of(sj, ["turkmen_quilted", "peasant"]), main=["culturesarmory:steel_kilij"],
              off=["culturesarmory:kalkan"], palette=("#E8E4D8", "#2F6B3A"), arms_=arms("white", "minecraft:circle", "green"), village=True),
        squad("seljuk.ghazis", "Ghazi Band", "INFANTRY", "MEDIUM", "Frontier warriors of the uj, raiding into Byzantine lands.",
              [("warrior", 6), ("shieldman", 4)], kits_of(sj, ["turkmen_quilted", "ghulam_turban"]),
              main=["culturesarmory:steel_tabarzin", "culturesarmory:steel_kilij"], off=["culturesarmory:kalkan"],
              palette=("#2F6B3A", "#E8E4D8"), arms_=arms("green", "magistuarmory:sun", "white")),
        squad("seljuk.silahdars", "Silahdars", "INFANTRY", "HIGH", "The armour-bearers of the sultan's household, in veiled helmets and jawshans.",
              [("shieldman", 6), ("warrior", 4)], kits_of(sj, ["ghulam_jawshan", "emir"]),
              main=["culturesarmory:steel_sayf", "culturesarmory:steel_shishpar"], off=["culturesarmory:sipar"],
              palette=("#8E2A2A", "#D4A22A"), arms_=arms("red", "magistuarmory:sun", "yellow")),
        squad("seljuk.bedouin_bows", "Bedouin Bowmen", "RANGED", "LOW", "Arab tribesmen of the Syrian steppe, bow in hand.",
              [("archer", 10)], kits_of(sj, ["bedouin"]), main=["culturesarmory:turkic_bow"], palette=("#6A4A30", "#E8E4D8"), village=True),
        squad("seljuk.khorasani_archers", "Khorasani Archers", "RANGED", "MEDIUM", "Archers from the east of the sultanate, turbaned, in mail.",
              [("archer", 10)], kits_of(sj, ["ghulam_turban", "turkmen_quilted"]), main=["culturesarmory:turkic_bow"],
              palette=("#2E7D7A", "#E8E4D8"), arms_=arms("cyan", "minecraft:circle", "white")),
        squad("seljuk.bedouin_raiders", "Bedouin Raiders", "CAVALRY", "LOW", "Arab tribal horse, quick to raid and quick to vanish.",
              [("light_lancer_rider", 10)], kits_of(sj, ["bedouin"]), off=["culturesarmory:kalkan"], palette=("#6A4A30", "#E8E4D8"), village=True),
        squad("seljuk.kurdish_horse", "Kurdish Horse", "CAVALRY", "MEDIUM", "Kurdish tribal cavalry of the mountains, Saladin's own people.",
              [("light_lancer_rider", 10)], kits_of(sj, ["ghulam_turban", "turkmen_quilted"]), off=["culturesarmory:kalkan"],
              palette=("#D4A22A", "#8E2A2A"), arms_=arms("yellow", "magistuarmory:sun", "red")),
        squad("seljuk.kipchak_riders", "Kipchak Riders", "CAVALRY", "MEDIUM", "Kipchak horse from the steppe, the stock of the later Mamluks.",
              [("archer_rider", 6), ("light_lancer_rider", 4)], kits_of(sj, ["kipchak_light", "kipchak"]), off=["culturesarmory:kalkan"],
              palette=("#A0402E", "#222222"), arms_=arms("red", "minecraft:stripe_middle", "black")),
        squad("seljuk.masked_lancers", "Masked Kipchak Lancers", "UNIQUE", "HIGH", "Kipchak chiefs behind steel face masks, the heavy lance of the steppe.",
              [("lancer_rider", 8)], kits_of(sj, ["kipchak"]), off=["culturesarmory:sipar"], palette=("#222222", "#A0402E"),
              arms_=arms("black", "magistuarmory:sun", "red")),
    ]
    j = "millenaire:japanese"
    C[j] = [
        squad("jp.ikko_ikki", "Ikkō-ikki", "INFANTRY", "LOW", "Leagues of peasants and Pure Land monks who fought daimyō and held provinces.",
              [("spear_man", 8, "levy"), ("warrior", 2)], kits_of(j, ["peasant", "sohei"]),
              main=["culturesarmory:steel_yari", "culturesarmory:steel_naginata"], off=["none"], palette=("#E6E1D3", "#2E2E34"),
              village=True),
        squad("jp.nagae_yari", "Nagae Yari Ashigaru", "INFANTRY", "MEDIUM", "Ashigaru with the long pike, in ranks under the clan's sashimono.",
              [("spear_man", 12)], kits_of(j, ["ashigaru_sashimono", "ashigaru"]), main=["culturesarmory:steel_yari"], off=["none"],
              palette=("#B0302A", "#2E2E34"), village=True),
        squad("jp.jizamurai", "Jizamurai", "INFANTRY", "MEDIUM", "Village samurai, half farmer, half warrior, in haramaki.",
              [("warrior", 6), ("spear_man", 4)], kits_of(j, ["kogashira", "domaru"]),
              main=["culturesarmory:steel_katana", "culturesarmory:steel_yari"], off=["none"], palette=("#5A7A3A", "#E6E1D3"), village=True),
        squad("jp.nanban_guard", "Nanban Hatamoto", "INFANTRY", "HIGH", "A wealthy lord's bannermen in European cuirasses and helmets.",
              [("warrior", 6), ("spear_man", 4)], kits_of(j, ["nanban", "tosei"]),
              main=["culturesarmory:steel_katana", "culturesarmory:steel_jumonji_yari"], off=["none"], palette=("#2A3048", "#D4A22A")),
        squad("jp.yumi_gumi", "Yumi-gumi", "RANGED", "MEDIUM", "A company of ashigaru archers under its banner.",
              [("archer", 10)], kits_of(j, ["ashigaru_sashimono", "ashigaru"]), main=["culturesarmory:yumi"], off=["none"],
              palette=("#2C3E78", "#E6E1D3"), village=True),
        squad("jp.bando_musha", "Bandō Musha", "CAVALRY", "MEDIUM", "Mounted archers of the eastern plains, the old way of the bow and horse.",
              [("archer_rider", 10)], kits_of(j, ["domaru", "samurai"]), off=["none"], palette=("#2C3E78", "#B0302A")),
        squad("jp.takeda_horse", "Takeda Horse", "CAVALRY", "HIGH", "The famed cavalry of Kai, in tōsei gusoku with the lance.",
              [("lancer_rider", 8)], kits_of(j, ["tosei", "hotoke"]), off=["none"], palette=("#B0302A", "#2E2E34")),
        squad("jp.akazonae", "Akazonae", "UNIQUE", "HIGH", "Ii Naomasa's 'red devils', every man's armour lacquered vermilion.",
              [("lancer_rider", 4), ("warrior", 6)], kits_of(j, ["hotoke", "taisho"]),
              main=["culturesarmory:steel_katana", "culturesarmory:steel_nagamaki"], off=["none"], palette=("#A8302A", "#A8302A", "#2E2E34")),
    ]
    i = "millenaire:indian"
    C[i] = [
        squad("in.barchhait", "Barchhait Spearmen", "INFANTRY", "LOW", "Hill infantry with the bamboo spear, the barchha.",
              [("spear_man", 10, "levy")], kits_of(i, ["villager", "paik"]), main=["culturesarmory:steel_barchha"],
              off=["culturesarmory:dhal"], palette=("#D08A28", "#EAE4D4"), village=True),
        squad("in.mavla", "Mavla Swordsmen", "INFANTRY", "LOW", "Hillmen of the Western Ghats with sword and dhal, fast on broken ground.",
              [("shieldman", 10)], kits_of(i, ["paik", "villager"]), main=["culturesarmory:steel_talwar"], off=["culturesarmory:dhal"],
              palette=("#9E2A2A", "#D08A28"), village=True),
        squad("in.vijayanagara_guard", "Vijayanagara Guard", "INFANTRY", "HIGH", "Palace guards of the southern empire in gilt-edged scale.",
              [("shieldman", 6), ("spear_man", 4)], kits_of(i, ["vijayanagara", "heavy_top"]),
              main=["culturesarmory:steel_talwar", "culturesarmory:steel_barchha"], off=["culturesarmory:dhal"],
              palette=("#D08A28", "#9E2A2A")),
        squad("in.vijayanagara_bows", "Vijayanagara Bowmen", "RANGED", "MEDIUM", "Archers of the southern armies, in quilted coats.",
              [("archer", 10)], kits_of(i, ["paik_quilted", "paik"]), main=["culturesarmory:dhanush"], palette=("#2E3E78", "#EAE4D4"), village=True),
        squad("in.carnatic_archers", "Carnatic Archers", "RANGED", "LOW", "Village bowmen of the Carnatic.",
              [("archer", 10)], kits_of(i, ["villager", "bhil"]), main=["culturesarmory:dhanush"], palette=("#EAE4D4", "#2E3E78"), village=True),
        squad("in.bargir", "Bargir Horse", "CAVALRY", "LOW", "Light horse riding mounts the state provided, quick raiders of the plateau.",
              [("light_lancer_rider", 10)], kits_of(i, ["paik_quilted", "paik"]), off=["culturesarmory:dhal"],
              palette=("#D08A28", "#EAE4D4"), village=True),
        squad("in.afghan_horse", "Afghan Horse", "CAVALRY", "MEDIUM", "Afghan cavalry of the Lodi and Sur lords, turbaned, in mail.",
              [("light_lancer_rider", 10)], kits_of(i, ["sipahi_turban", "rajput"]), off=["culturesarmory:dhal"],
              palette=("#2F6B3A", "#EAE4D4"), arms_=arms("green", "magistuarmory:sun", "white")),
        squad("in.ahadis", "Ahadi Troopers", "CAVALRY", "HIGH", "The emperor's own gentlemen troopers, paid from the treasury, in char aina.",
              [("lancer_rider", 8)], kits_of(i, ["noble", "heavy"]), off=["culturesarmory:dhal"], palette=("#7A1E3A", "#D4A22A")),
        squad("in.kesariya", "Kesariya Band", "UNIQUE", "HIGH", "Rajputs in saffron, sworn to the last charge (saka) rather than surrender.",
              [("warrior", 6), ("shieldman", 4)], kits_of(i, ["rajput", "paik_quilted"]),
              main=["culturesarmory:steel_khanda", "culturesarmory:steel_talwar"], off=["culturesarmory:dhal"],
              palette=("#D08A28", "#D08A28", "#D4A22A")),
    ]
    m = "millenaire:mayan"
    C[m] = [
        squad("maya.tribute_levy", "Tribute Levy", "INFANTRY", "LOW", "Commoners owed to the lord's war, flint spears in hand.",
              [("spear_man", 10, "levy")], kits_of(m, ["commoner", "holkan"]), main=["culturesarmory:flint_spear"],
              off=["culturesarmory:pakal"], palette=("#EDE8DA", "#A8402E"), village=True),
        squad("maya.flexible_shields", "Flexible-Shield Wall", "INFANTRY", "MEDIUM", "Warriors behind the tall flexible shields of the Bonampak murals.",
              [("shieldman", 8), ("spear_man", 2)], kits_of(m, ["holkan_kilt", "holkan"]), main=["culturesarmory:macuahuitl"],
              off=["culturesarmory:flexible_shield"], palette=("#EDE8DA", "#2E6E9E"), village=True),
        squad("maya.obsidian_clubs", "Obsidian Clubmen", "INFANTRY", "MEDIUM", "Holkan with obsidian-edged clubs and round shields.",
              [("shieldman", 10)], kits_of(m, ["holkan", "holkan_kilt"]), main=["culturesarmory:macuahuitl"], off=["culturesarmory:pakal"],
              palette=("#A8402E", "#EDE8DA"), village=True),
        squad("maya.serpent_archers", "Serpent Archers", "RANGED", "MEDIUM", "Archers of the feathered-serpent warriors.",
              [("archer", 10)], kits_of(m, ["serpent", "holkan_kilt"]), main=["culturesarmory:maya_bow"], palette=("#3E7A4A", "#EDE8DA")),
        squad("maya.peten_raiders", "Petén Raiders", "CAVALRY", "LOW", "A fast band from the forests of the Petén, out for captives.",
              [("warrior", 6), ("spear_man", 4)], kits_of(m, ["holkan", "commoner"]),
              main=["culturesarmory:macuahuitl", "culturesarmory:flint_spear"], palette=("#A8402E", "#2C2A28"), village=True),
        squad("maya.ajaw_household", "Ajaw's Household", "UNIQUE", "HIGH", "The holy lord's own warriors in quetzal plumes and jade.",
              [("warrior", 4), ("shieldman", 6)], kits_of(m, ["ajaw", "noble", "jaguar"]),
              main=["culturesarmory:macuahuitl", "culturesarmory:flint_axe"], off=["culturesarmory:pakal"],
              palette=("#3E7A4A", "#D4A22A")),
    ]
    u = "millenaire:inuits"
    C[u] = [
        squad("inuit.umiak_crew", "Umiak Crew", "INFANTRY", "LOW", "The open boat's crew, harpoons and lances to hand.",
              [("spear_man", 10)], kits_of(u, ["seal", "caribou"]), main=["culturesarmory:harpoon"], off=["none"],
              palette=("#D8D0BE", "#8B3A22")),
        squad("inuit.bear_hunters", "Polar Bear Hunters", "INFANTRY", "MEDIUM", "Hunters of the far north in bear-skin trousers, who face the nanuq on foot.",
              [("warrior", 6), ("spear_man", 4)], kits_of(u, ["polar", "bird"]), main=["culturesarmory:bone_club", "culturesarmory:ivory_lance"],
              off=["none"], palette=("#E9E4D4", "#8B3A22")),
        squad("inuit.bering_warriors", "Bering Strait Warriors", "INFANTRY", "HIGH", "Feud warriors of the strait in slat and ivory armour.",
              [("warrior", 6), ("shieldman", 4)], kits_of(u, ["slat", "ivory"]), main=["culturesarmory:bone_club"], off=["none"],
              palette=("#6E4A2A", "#E9E4D4")),
        squad("inuit.bird_skin_bows", "Bird-Skin Bowmen", "RANGED", "LOW", "Archers in eider-skin parkas with the sinew-backed bow.",
              [("archer", 10)], kits_of(u, ["bird", "seal"]), main=["culturesarmory:sinew_bow"], off=["none"], palette=("#D8D0BE", "#2E5B8A")),
        squad("inuit.sled_raiders", "Sled Raiders", "CAVALRY", "MEDIUM", "A raiding party travelling fast over the ice behind its dogs.",
              [("warrior", 6), ("archer", 4)], kits_of(u, ["polar", "walrus"]), main=["culturesarmory:bone_club", "culturesarmory:sinew_bow"],
              off=["none"], palette=("#8B3A22", "#E9E4D4")),
    ]
    return C


# ---------------------------------------------------------------------------------------------------------- mercenary companies

def companies():
    n, b, sj, j, i, m, u = ("millenaire:norman", "millenaire:byzantines", "millenaire:seljuk", "millenaire:japanese", "millenaire:indian",
                            "millenaire:mayan", "millenaire:inuits")

    def co(culture, regions, near, *a, everywhere=False, **k):
        d, look = squad(*a, **k)
        d["culture"] = culture
        d["regions"] = regions
        d["near"] = near
        if everywhere:
            d["everywhere"] = True
        return d, look, culture
    return [
        # the woodlands and the grasslands of the Latin west
        co(n, ["WOODLAND", "GRASSLAND"], [n], "merc.routiers", "Brabançon Routiers", "INFANTRY", "MEDIUM",
           "Routiers of Brabant, who sold their swords to kings and robbed the land between wars.",
           [("warrior", 6), ("crossbowman", 4)], kits_of(n, ["ek:brigandine", "ek:chained"]), main=[E + "steel_bastardsword", E + "heavy_crossbow"],
           off=[E + "iron_heatershield"], palette=("#222222", "#D4A22A"), arms_=arms("yellow", "magistuarmory:lion1", "black")),
        co(n, ["WOODLAND", "GRASSLAND"], [n], "merc.free_lances", "Free Lances", "CAVALRY", "MEDIUM",
           "Landless knights and serjeants riding from lord to lord for pay.",
           [("light_lancer_rider", 8), ("lancer_rider", 2)], kits_of(n, ["ek:mail", "ek:crusader"]), off=[E + "iron_kiteshield"],
           palette=("#2E4A7D", "#E9E4D4"), arms_=arms("blue", "minecraft:border", "white")),
        co(n, ["WOODLAND", "MOUNTAIN"], [], "merc.welsh_archers", "Welsh Archers", "RANGED", "LOW",
           "Bowmen down from the Welsh hills for a season's pay.",
           [("archer", 10)], kits_of(n, ["ek:tunic", "ek:gambeson"]), main=[E + "longbow"], palette=("#3F6B35", "#E9E4D4"),
           arms_=arms("green", "magistuarmory:dragon", "red")),
        co(n, ["SWAMP"], [], "merc.fenmen", "Fenmen of the Wake", "RANGED", "LOW",
           "Men of the fens who held out with Hereward on the Isle of Ely, bow and spear.",
           [("archer", 6), ("spear_man", 4)], kits_of(n, ["ek:tunic", "ek:gambeson"]), main=[E + "longbow", E + "iron_pike"],
           palette=("#6A4A30", "#3F6B35"), arms_=arms("brown", "minecraft:stripe_middle", "green")),
        co(n, ["COAST"], [], "merc.genoese_marines", "Genoese Marines", "RANGED", "MEDIUM",
           "Crossbowmen of the Genoese galleys, the best that money could hire.",
           [("crossbowman", 8), ("shieldman", 2)], kits_of(n, ["ek:brigandine", "ek:kettle_mail"]), main=[E + "heavy_crossbow"],
           off=[E + "iron_pavese"], palette=("#E9E4D4", "#9E1B1B"), arms_=arms("white", "minecraft:straight_cross", "red")),
        co(n, ["COAST", "MOUNTAIN"], [], "merc.gallowglass", "Gallowglass", "INFANTRY", "HIGH",
           "Hebridean axemen in mail shirts and padded coats, hired by Irish kings.",
           [("warrior", 10)], kits_of(n, ["ek:gallowglass", "ek:gallowglass_ek"]), main=[E + "steel_lochaberaxe"],
           palette=("#3F6B35", "#E9E4D4"), arms_=arms("green", "magistuarmory:swords", "white")),
        co(n, [], [], "merc.free_company", "Free Company", "INFANTRY", "LOW",
           "A band of masterless men, spears and crossbows, who will march for anyone who pays.",
           [("spear_man", 6), ("crossbowman", 4)], kits_of(n, ["ek:gambeson_kettle", "ek:chained"]), main=[E + "iron_pike", E + "heavy_crossbow"],
           palette=("#6A4A30", "#E9E4D4"), arms_=arms("brown", "minecraft:border", "white"), everywhere=True),
        # the taiga: the Norse and the Rus
        co(b, ["TAIGA", "COAST"], [b], "merc.varangian_exiles", "Varangian Exiles", "INFANTRY", "HIGH",
           "Northmen and English bound for Miklagard, axe on shoulder.",
           [("warrior", 10)], kits_of(b, ["varangian"]) + kits_of(n, ["ek:mail"]), main=["culturesarmory:steel_pelekys", E + "steel_lochaberaxe"],
           palette=("#8E2A2A", "#E9E4D4"), arms_=arms("red", "minecraft:half_vertical", "white")),
        co(n, ["TAIGA"], [], "merc.rus_druzhina", "Rus Druzhina", "INFANTRY", "MEDIUM",
           "A prince's war band from the river lands, in mail and pointed helmets.",
           [("shieldman", 6), ("spear_man", 4)], kits_of(n, ["ek:rus", "ek:varangian_slav", "ek:mail"]), off=[E + "iron_roundshield"],
           palette=("#8E2A2A", "#D4A22A"), arms_=arms("red", "magistuarmory:orthodox_cross", "yellow")),
        # the grasslands of the steppe
        co(b, ["GRASSLAND"], [b], "merc.pechenegs", "Pecheneg Horse", "CAVALRY", "MEDIUM",
           "Horse archers of the Pontic steppe, as ready to raid the empire as to serve it.",
           [("archer_rider", 10)], kits_of(sj, ["kipchak_light", "turkmen"]) + kits_of(n, ["ek:cuman_light"]),
           palette=("#6A4A30", "#D4A22A"), arms_=arms("brown", "magistuarmory:sun", "yellow")),
        co(sj, ["GRASSLAND", "SAVANNA"], [], "merc.cumans", "Cuman Horde", "CAVALRY", "MEDIUM",
           "Cuman riders under their khan, bows and light lances.",
           [("archer_rider", 6), ("light_lancer_rider", 4)], kits_of(sj, ["kipchak", "kipchak_light"]) + kits_of(n, ["ek:cuman"]),
           off=["culturesarmory:kalkan"], palette=("#A0402E", "#222222"), arms_=arms("red", "minecraft:stripe_middle", "black")),
        co(sj, ["GRASSLAND", "DESERT"], [sj], "merc.turkmen", "Turkmen Free Band", "CAVALRY", "LOW",
           "A Turkmen clan with its horses and bows, following the grass and the pay.",
           [("archer_rider", 10)], kits_of(sj, ["turkmen", "turkmen_quilted"]), palette=("#A0402E", "#E8E4D8")),
        # the desert
        co(sj, ["DESERT"], [], "merc.bedouin_raiders", "Bedouin Raiders", "CAVALRY", "LOW",
           "Tribesmen of the desert, lance and swift horse.",
           [("light_lancer_rider", 10)], kits_of(sj, ["bedouin"]), off=["culturesarmory:kalkan"], palette=("#6A4A30", "#E8E4D8")),
        co(sj, ["DESERT"], [], "merc.caravan_guards", "Caravan Guards", "INFANTRY", "LOW",
           "Hired swords and bows of the caravan roads.",
           [("shieldman", 6), ("archer", 4)], kits_of(sj, ["bedouin", "turkmen_quilted"]),
           main=["culturesarmory:steel_kilij", "culturesarmory:turkic_bow"], off=["culturesarmory:kalkan"], palette=("#E8E4D8", "#2E7D7A")),
        # the mountains
        co(sj, ["MOUNTAIN"], [sj], "merc.daylamites", "Daylamite Mercenaries", "INFANTRY", "MEDIUM",
           "Hillmen of Daylam, the hired infantry of half the Islamic world, with spear and painted shield.",
           [("shieldman", 6), ("spear_man", 4)], kits_of(sj, ["daylami", "daylami_mail"]),
           main=["culturesarmory:steel_kilij", "culturesarmory:steel_mizrak"], off=["culturesarmory:kalkan"],
           palette=("#2F6B3A", "#E8E4D8"), arms_=arms("green", "minecraft:circle", "white")),
        co(sj, ["MOUNTAIN"], [sj], "merc.kurds", "Kurdish Horsemen", "CAVALRY", "MEDIUM", "Mountain horsemen of the Kurdish tribes.",
           [("light_lancer_rider", 10)], kits_of(sj, ["ghulam_turban", "turkmen_quilted"]), off=["culturesarmory:kalkan"],
           palette=("#D4A22A", "#8E2A2A")),
        co(j, ["MOUNTAIN"], [j], "merc.yamabushi", "Mountain Sōhei", "INFANTRY", "MEDIUM",
           "Warrior monks of the mountain temples, hired out with their naginata.",
           [("warrior", 10)], kits_of(j, ["sohei"]), main=["culturesarmory:steel_naginata"], off=["none"], palette=("#E6E1D3", "#2E2E34")),
        # the coast
        co(j, ["COAST"], [j], "merc.wokou", "Wokou", "INFANTRY", "MEDIUM",
           "Sea raiders of the Japanese and Chinese coasts, with long swords and bows.",
           [("warrior", 6), ("archer", 4)], kits_of(j, ["peasant", "kogashira"]),
           main=["culturesarmory:steel_nodachi", "culturesarmory:steel_katana", "culturesarmory:yumi"], off=["none"],
           palette=("#2E2E34", "#B0302A")),
        # near the Japanese: masterless men
        co(j, ["WOODLAND"], [j], "merc.ronin", "Rōnin Band", "UNIQUE", "MEDIUM", "Samurai without a lord, selling the only trade they know.",
           [("warrior", 4), ("archer", 4), ("spear_man", 2)], kits_of(j, ["kogashira", "hotoke", "tosei"]),
           main=["culturesarmory:steel_katana", "culturesarmory:steel_tachi", "culturesarmory:yumi", "culturesarmory:steel_yari"], off=["none"],
           palette=("#2E2E34", "#E6E1D3")),
        # the savanna: India
        co(i, ["SAVANNA", "GRASSLAND"], [i], "merc.pindari", "Pindari Riders", "CAVALRY", "LOW",
           "Irregular horse who served for plunder, not pay.",
           [("light_lancer_rider", 12)], kits_of(i, ["villager", "paik"]), off=["culturesarmory:dhal"], palette=("#D08A28", "#2E3E78")),
        co(i, ["SAVANNA"], [i], "merc.purbiya", "Purbiya Rajputs", "INFANTRY", "HIGH",
           "Rajput soldiers of fortune from the east, who served any throne that paid.",
           [("warrior", 6), ("shieldman", 4)], kits_of(i, ["sipahi", "rajput"]),
           main=["culturesarmory:steel_khanda", "culturesarmory:steel_talwar"], off=["culturesarmory:dhal"], palette=("#9E2A2A", "#D08A28")),
        co(i, ["SAVANNA", "MOUNTAIN"], [], "merc.gosains", "Gosain Ascetics", "INFANTRY", "MEDIUM",
           "Warrior-ascetics, ash-smeared, who fought as hired bands for many a prince.",
           [("shieldman", 10)], kits_of(i, ["nayar"]), main=["culturesarmory:steel_talwar"], off=["culturesarmory:dhal"],
           palette=("#D08A28", "#EAE4D4")),
        # the jungle: the Maya
        co(m, ["JUNGLE"], [m], "merc.canul", "Canul Mercenaries", "INFANTRY", "MEDIUM",
           "Warriors from the Gulf coast whom the lords of Mayapán hired to keep their city.",
           [("shieldman", 6), ("spear_man", 4)], kits_of(m, ["serpent", "holkan_kilt"]),
           main=["culturesarmory:macuahuitl", "culturesarmory:flint_spear"], off=["culturesarmory:pakal", "culturesarmory:flexible_shield"],
           palette=("#2E6E9E", "#EDE8DA")),
        co(m, ["JUNGLE"], [], "merc.jungle_hunters", "Jungle Hunters", "RANGED", "LOW", "Hunters of deer and jaguar, bows strung for war.",
           [("archer", 10)], kits_of(m, ["hunter", "commoner"]), main=["culturesarmory:maya_bow"], palette=("#6A4A30", "#3E7A4A")),
        co(m, ["JUNGLE", "SWAMP"], [m], "merc.jaguar_band", "Jaguar Band", "UNIQUE", "HIGH", "Jaguar warriors who left a fallen city's service.",
           [("warrior", 6), ("shieldman", 4)], kits_of(m, ["jaguar", "jaguar_cotton"]),
           main=["culturesarmory:flint_axe", "culturesarmory:macuahuitl"], off=["culturesarmory:pakal"], palette=("#D4A22A", "#222222")),
        # the frozen north: the Inuit
        co(u, ["ARCTIC"], [u], "merc.feud_exiles", "Feud Exiles", "INFANTRY", "MEDIUM", "Men driven from their camp by a blood feud, looking for a new one.",
           [("warrior", 6), ("spear_man", 4)], kits_of(u, ["walrus", "bone"]), main=["culturesarmory:bone_club", "culturesarmory:harpoon"],
           off=["none"], palette=("#6E4A2A", "#E9E4D4")),
        co(u, ["ARCTIC", "TAIGA"], [], "merc.sled_hunters", "Sled Hunters", "RANGED", "LOW", "Hunters with dogs and sinew bows, at home on the ice.",
           [("archer", 8), ("spear_man", 2)], kits_of(u, ["goggles", "polar"]), main=["culturesarmory:sinew_bow", "culturesarmory:harpoon"],
           off=["none"], palette=("#8B3A22", "#E9E4D4")),
    ]


def with_fallback(look, culture, prof, members):
    """Armory looks keep the culture's Epic Knights kits beside them (worn without the armory)."""
    if any(any(v.startswith("culturesarmory:") for v in k.values()) for k in look["kits"]):
        top = max(members, key=lambda x: x["count"])
        tier = members[0]["gear"]
        for k in ek_fallback(prof, culture, tier, CLASS[top["unit"]]):
            if k not in look["kits"]:
                look["kits"].append(k)
    for slot in ("main", "off"):
        ids = look[slot]
        if ids and any(x.startswith("culturesarmory:") for x in ids) and not any(x.startswith(E) or x == "none" for x in ids):
            # an Epic Knights item of the same use, for a world without the armory
            fam = {"main": {"archer": E + "longbow", "crossbowman": E + "heavy_crossbow"}, "off": {}}
            unit = max(members, key=lambda x: x["count"])["unit"]
            fb = fam[slot].get(unit) or (E + ("iron_roundshield" if slot == "off" else
                                              {"spear_man": "iron_pike", "warrior": "iron_heavymace"}.get(unit, "iron_shortsword")))
            ids.append(fb)
    return look


def main():
    prof = json.load(open(PROFILES, encoding="utf-8"))
    squads = json.load(open(SQUADS, encoding="utf-8"))
    looks_doc = json.load(open(LOOKS, encoding="utf-8"))
    looks = looks_doc["looks"]
    added = 0
    for culture, entries in village_squads().items():
        lst = squads["cultures"][culture]
        ids = {d["id"] for d, _ in entries}
        lst[:] = [x for x in lst if x["id"] not in ids]
        for d, look in entries:
            lst.append(d)
            looks[d["id"]] = to_look(with_fallback(look, culture, prof, d["members"]))
            added += 1
    squads["defaults"] = squads["cultures"]["millenaire:norman"]       # unknown cultures: the Normans' list
    comps = []
    for d, look, culture in companies():
        comps.append(d)
        looks[d["id"]] = to_look(with_fallback(look, culture, prof, d["members"]))
    squads["_comment"] = squads["_comment"].replace(
        "Per culture 16 squads: 6 infantry (2 LOW, 2 MEDIUM, 2 HIGH), 4 ranged, 4 cavalry (cultures without horses get fast foot bands "
        "instead), 2 unique;", "Per culture 21-26 squads: infantry (every quality), ranged, cavalry (cultures without horses get fast foot "
        "bands instead) and unique ones;").split(" Cultures Armory 1.6:")[0] + (
        " Cultures Armory 1.6: more squads per culture (the armory's 1.5 armour), written by devtools/squads_gen.py; the Muster Roll lists"
        " them by Foot, Missile, Horse (Raiders for peoples without horses) and Special, in subgroups by what most of their soldiers are."
        " Mercenary companies are in mercenaries.json.")
    with open(SQUADS, "w", encoding="utf-8") as f:
        f.write(json.dumps(squads, indent=1, ensure_ascii=False) + "\n")
    with open(COMPANIES, "w", encoding="utf-8") as f:
        f.write(json.dumps({
            "_comment": "HywMill 1.6 mercenary companies, hired at a Muster Roll set down outside any village (in the overworld). A company is "
                        "found by the land where the block stands ('regions', from its biome: ARCTIC, DESERT, JUNGLE, SAVANNA, SWAMP, COAST, "
                        "MOUNTAIN, TAIGA, GRASSLAND, WOODLAND), by the people of the nearest village within 1500 blocks ('near', its "
                        "culture), or anywhere ('everywhere'). Its soldiers are equipped as its 'culture' at each member's gear tier, in its "
                        "look, and are the hiring player's own. No standing is needed: the price is the members' squad price times "
                        "'companyPremium'. Members, quality and category as in defaults.json. Written by devtools/squads_gen.py.",
            "companyPremium": 1.25,
            "companies": comps}, indent=1, ensure_ascii=False) + "\n")
    with open(LOOKS, "w", encoding="utf-8") as f:
        f.write(json.dumps(looks_doc, indent=1, ensure_ascii=False) + "\n")
    print(f"village squads added: {added}; companies: {len(comps)}")


def to_look(look):
    out = {"kits": look["kits"]}
    if look["off"]:
        out["offhand"] = look["off"]
    out["palette"] = look["palette"]
    out["arms"] = look["arms"]
    if look["main"]:
        out["mainhand"] = look["main"]
    return out


if __name__ == "__main__":
    main()
