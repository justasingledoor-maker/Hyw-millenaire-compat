# HYW ↔ Millénaire compat (`hywmill`)

This is a NeoForge 1.21.1 integration layer between **Millénaire 9.0.2** and **Hundred Years War 0.7.1r-fix1**. It is a separate JAR that modifies neither mod and bundles neither.

**Status:** Milestone 1 (garrison ledger and combat bridge). See [`docs/m1-report.md`](docs/m1-report.md). The original feasibility audit is in [`docs/millenaire-hyw-feasibility-audit.md`](docs/millenaire-hyw-feasibility-audit.md).

## Building

Both dependencies are All-Rights-Reserved JARs, so they are not in this repository. Get them yourself and place them exactly here:

```
libs/millenaire-9.0.2.jar                               (millenaire.org downloads)
libs/HundredYearsWar-0.7.1r-fix1-1.21.1-neoforge.jar
```

Then build:

```
./gradlew build          # -> build/libs/hywmill-<version>.jar
./gradlew runServer      # dev server; copies both jars into run/mods first
```

They are `compileOnly` dependencies. At runtime both are optional: the mod loads without either, and the matching integration disables itself.

## Commands

`/hywmill` (alias `/ourmod`):

| Command | What it does |
|---|---|
| `status` | Integration state, goal-bridge status and counters |
| `village info` | Nearest village: id, faction UUID, tier, garrison, strength, fortification, your relation |
| `village list` | All villages in the ledger |
| `village residents` | Loaded residents: role, current goal, attack target (op) |
| `threats` | Current HYW threats in the nearest village |
| `incidents [n]` | Recent combat incidents (op) |
| `admin clear-identities [all]` | Removes our village faction identity from residents of the nearest village (or all villages) and keeps it off (op 3) |
| `admin restore-identities [all]` | Lifts that clearance; residents are marked again (op 3) |
| `dev playerhit`, `dev relation`, `dev inspect` | Test helpers; need `general.devCommands=true` |

## Removing the mod from a world

hywmill gives every Millénaire villager an HYW identity marker (its village's faction UUID). HYW saves that marker with the villager. If you remove hywmill without clearing the markers first, they stay in the world. HYW then keeps treating those villagers as members of a faction that nothing manages any more: unowned HYW units stay hostile to them, and nothing makes the villagers fight back.

Once the mod is removed, nothing can clean up. Before removing it:

1. Run `/hywmill admin clear-identities all` (op level 3). This clears loaded villagers right away and records that every village must stay unmarked.
2. Load every village once so their villagers are cleared as they load (for example, visit them, or use Millénaire's `/millenaire chunkload` near each). `/hywmill status` shows how many markers have been removed.
3. Save and stop the server, then remove the jar.

Setting `bridge.markVillagers = false` in `config/hywmill-common.toml` also removes our markers from villagers as they load, and stops marking. To undo a clearance, run `/hywmill admin restore-identities all`.

Our own data (`data/hywmill_garrison_ledger.dat` and `data/hywmill_identity_clearance.dat` in the world folder) can be deleted after the mod is removed; nothing else reads it.

## Role tables (datapack)

Military roles come from explicit tables in `data/<namespace>/hywmill_roles/*.json`. The shipped ones are in `data/hywmill/hywmill_roles/<culture>.json`:

```json
{ "villagers": { "millenaire:norman/guard": "SOLDIER", "millenaire:norman/knight": "LEADER" },
  "buildings": { "millenaire:norman/guardhouse": "GUARDHOUSE", "millenaire:norman/fort": "FORT_TOWNHALL" } }
```

- **Villager roles:** SOLDIER, LEADER, MILITIA or CIVILIAN.
  - A villager type with Millénaire's `hostile` tag is always OUTLAW.
  - Otherwise the table entry wins.
  - Types not in the table: children are CIVILIAN, `helpInAttacks` types are MILITIA, and everything else is CIVILIAN.
  - `village info` and the log list unlisted `helpInAttacks` types that carry a `chief`, `archer`, `defensive` or `defender` tag, so you can review them.
- **Building roles:** GUARDHOUSE, WATCHTOWER, BARRACKS, ARMOURY, TRAINING, FORT_TOWNHALL, WALL, TOWER, GATE, BORDER_MARKER or NONE.
  - Wall pieces are derived from Millénaire's own wall types: wall, corner, cap and slope pieces are WALL, tower pieces are TOWER, and gateway pieces are GATE.
  - Every piece of a wall type that spawns no wall segments (Millénaire's border posts), and any plan set Millénaire flags as a border post, is BORDER_MARKER.
  - A table entry overrides the derived role, and `NONE` excludes a building.

A datapack can add a file, or replace a shipped one by using the same path.

Fortification points per operational building: WALL 1, TOWER 3, GATE 2, BORDER_MARKER 0, GUARDHOUSE 3, WATCHTOWER 3, BARRACKS 4, FORT_TOWNHALL 5, ARMOURY 0 and TRAINING 0.

Tier (first match wins):

| Tier | Condition |
|---|---|
| STRONGHOLD | GARRISON conditions, fortification ≥ 20 and at least one WALL |
| GARRISON | ≥ 3 SOLDIER/LEADER and a BARRACKS, ARMOURY, TRAINING or FORT_TOWNHALL |
| GUARD_POST | ≥ 1 SOLDIER, or ≥ 2 defenders and a GUARDHOUSE or WATCHTOWER |
| WATCH | ≥ 1 defender (SOLDIER, LEADER or MILITIA) |
| NONE | otherwise |
