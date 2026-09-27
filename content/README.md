# HywMill optional content

## `millenaire-custom/hywmill_armoury` (M5-6 armoury)

A Millénaire content sub-mod: data only, no code, no jar. It lets Patrons of a village buy HYW
recruit scrolls at that culture's military shop:

| Culture | Shop | Scrolls added |
|---|---|---|
| Norman | armoury | militia, spear man, shieldman, crossbowman, archer |
| Byzantine | armyforge | militia, spear man, shieldman, archer |
| Seljuk | armoury | militia, spear man, warrior, archer |
| Japanese | armyforge | militia, spear man, archer |
| Indian | armyforge | militia, spear man, warrior, archer |
| Mayan | mayanarmyforge | militia, spear man, warrior, archer |
| Inuit | armoury | militia, spear man, archer |

* Gated by Millénaire's own `min_reputation` 8192 (friend of the village, which HywMill calls Patron).
* Prices in deniers: militia 32, spear man 48, shieldman/warrior/archer 64, crossbowman 80.
* A Millénaire pack shop file **replaces** the original, so each shop repeats the original lists of
  Millénaire 9.0.2 exactly, with the scrolls appended to `sells`.

**Install:** copy `hywmill_armoury` into the server's `millenaire-custom/` directory and restart.
Remove the folder to uninstall. HywMill itself does not need it.

**Regenerate** after a Millénaire update (the original shop lists may change):

    python3 devtools/make_armoury_pack.py libs/millenaire-<version>.jar content/millenaire-custom
