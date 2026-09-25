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
| `dev playerhit`, `dev relation`, `dev inspect` | Test helpers; need `general.devCommands=true` |
