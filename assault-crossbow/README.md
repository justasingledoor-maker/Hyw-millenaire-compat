# Assault Crossbow

A NeoForge 1.21.1 mod with one item: a repeating crossbow for the player. No other mods needed.

* **Magazine**: 30 arrows (one kind at a time; tipped and spectral arrows work).
* **Fire**: hold use for fully automatic fire (5 bolts a second), or tap for single shots. Bolts fly at crossbow speed
  and ignore the target's short hurt immunity, so every one lands.
* **Reload**: automatic when the magazine runs dry (1.5 s, from the arrows you carry; free in creative). Sneak + use tops
  up a part-full magazine. Switching away stops a reload.
* **HUD**: rounds left beside the crosshair; the bar under the icon is the magazine (blue while reloading).
* **Recipe** (shaped):

  ```
  iron ingot   hopper     iron ingot
  string       crossbow   string
               tripwire hook
  ```

Only players can use it: mobs, Millénaire villagers and HYW units never get it.

## Building

```
../gradlew build            # build/libs/assault-crossbow-<version>.jar
python3 tools/texture.py    # redraws the two item textures (needs Pillow)
```

`tools/client-test/xbowtest` is a datapack for a test world: it gives the crossbow and 84 arrows, lines up husks, and
reports arrows, husks and the magazine in chat (see `docs/`).
