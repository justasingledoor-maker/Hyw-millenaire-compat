# HywMill: prebuilt jars

Both jars are built from this branch and contain only HywMill's own classes. Nothing from
Millénaire, HYW or Epic Knights is bundled.

| Jar | Content | SHA-256 |
|---|---|---|
| `hywmill-m4.jar` (current) | M4: duties (sentries, patrols, scouts, reserve), raid contingents, optional Epic Knights profiles; includes everything in M3 plus the approved spawn-location fallback | `e6712d3af1c14eb30f54920aab61f8fd3cfd95de49dd76fb98e457406776880f` |
| `hywmill-m3.jar` | The frozen M3 build (garrisons, M2 doctrine, M1.1 bridge) | `82908266de9ec48535582fd64e5d11516d005dbafda349b7fb3c7a90a5206458` |

## Install

Put these jars in your NeoForge 21.1.226 (Minecraft 1.21.1) `mods/` folder:

* **one** HywMill jar (`hywmill-m4.jar` or `hywmill-m3.jar`, not both);
* Millénaire 9.0.2;
* Hundred Years War 0.7.1r-fix1;
* optional, for the M4 equipment profiles: Epic Knights 10.15 (with Architectury and Cloth Config).

## Settings

M4 adds these server config sections (`config/hywmill-common.toml`):

```toml
[duties]
enabled = true
intervalTicks = 40
layoutRecheckTicks = 1200

[raids]
enabled = true
```

Duty and raid sizes are datapack data:
* `data/hywmill/hywmill_duties/`: duty and raid sizes;
* `data/hywmill/hywmill_equipment/`: equipment profiles.

Validate the profiles with `/hywmill admin equipcheck`.
