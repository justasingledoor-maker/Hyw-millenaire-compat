#!/usr/bin/env python3
"""
M5-6: generates the optional HywMill armoury content pack for Millénaire (data only, no code, no jar).

    python3 devtools/make_armoury_pack.py libs/millenaire-9.0.2.jar content/millenaire-custom

Output: <out>/hywmill_armoury/cultures/<culture>/{traded_goods.json, shops/<shop>.json}. A server owner
copies <out>/hywmill_armoury into the server's millenaire-custom/ directory. Each culture's military
shop sells what it sold before (Millénaire's pack shop file REPLACES the original, so the original lists
are copied verbatim from the pinned jar) plus HYW recruit scrolls of the culture's garrison composition,
gated by Millénaire's own reputation check (min_reputation 8192 = friend of the village = Patron).
"""
import json
import sys
import zipfile
from pathlib import Path

# culture -> military shop file in Millénaire 9.0.2
SHOPS = {"norman": "armoury", "seljuk": "armoury", "inuits": "armoury", "byzantines": "armyforge",
         "indian": "armyforge", "japanese": "armyforge", "mayan": "mayanarmyforge"}
# HYW recruit scrolls per culture, following hywmill_garrison compositions (no mounted scrolls: horses are HYW's own)
SCROLLS = {
    "norman": ["militia", "spear_man", "shieldman", "crossbowman", "archer"],
    "byzantines": ["militia", "spear_man", "shieldman", "archer"],
    "seljuk": ["militia", "spear_man", "warrior", "archer"],
    "japanese": ["militia", "spear_man", "archer"],
    "indian": ["militia", "spear_man", "warrior", "archer"],
    "mayan": ["militia", "spear_man", "warrior", "archer"],
    "inuits": ["militia", "spear_man", "archer"],
}
PRICE = {"militia": 32, "spear_man": 48, "shieldman": 64, "warrior": 64, "archer": 64, "crossbowman": 80}
PATRON = 8192


def main(jar, out):
    root = Path(out) / "hywmill_armoury" / "cultures"
    with zipfile.ZipFile(jar) as z:
        for culture, shop in SHOPS.items():
            original = json.loads(z.read(f"millenaire/cultures/{culture}/shops/{shop}.json"))
            goods = [{"id": f"hywmill_scroll_{u}", "item": f"hundred_years_war:scroll_{u}", "selling_price": PRICE[u],
                      "min_reputation": PATRON, "category": "military"} for u in SCROLLS[culture]]
            d = root / culture
            (d / "shops").mkdir(parents=True, exist_ok=True)
            (d / "traded_goods.json").write_text(json.dumps({"goods": goods}, indent=2) + "\n")
            shop_json = dict(original)
            shop_json["sells"] = list(original.get("sells", [])) + [g["id"] for g in goods]
            (d / "shops" / f"{shop}.json").write_text(json.dumps(shop_json, indent=2) + "\n")
            print(f"{culture}/{shop}: {len(original.get('sells', []))} original + {len(goods)} scrolls")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
