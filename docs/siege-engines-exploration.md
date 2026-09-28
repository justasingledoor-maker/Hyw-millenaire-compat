# Siege engines in sieges: exploration (no production code)

## What HYW has (0.7.1r-fix1, decompiled)

* **Engineers.** `siege_engineer` is a `MilitiaEntity` subclass: an ordinary HYW combat unit. The unit provider can
  spawn, own and equip one like any soldier.
* **Engineer-operated engines** (`EngineerOperatedSiegeEntity`):
  * trebuchet, mangonel, springald, nest of bees;
  * gunpowder: cannon, culverin, bombard, great bombard, ribauldequin.
  * Each is a `BaseCombatEntity` with an operator seat. An engineer of the same side mounts it on its own
    (`AutoMountEngineerOperatedSiegeGoal`: an ownerless engine, or one relation-protected with the engineer). Without
    crew an engine does not fight.
* **Block damage.**
  * Every engine projectile explodes with `ExplosionInteraction.NONE`: it damages entities in the blast and breaks no
    blocks.
  * `BatteringRamEntity` breaks any block of hardness ≤ 50 at a position it is ordered to attack.
  * `SiegeTowerEntity` carries passengers over walls.
* **Tree felling** (`TreeFellingHandler`, HYW config `enableSiegeTreeFelling`, default on). A moving siege unit fells
  what it judges a natural tree:
  * logs need a vertical trunk and at least half as many leaves as logs, so timber-framed houses are safe;
  * **a leaf-only cluster also counts as a tree**, so hedges and leaf decoration in villages can go.

## Spike (`ENG`, `siege-engines-spike1.txt`)

A (at war with B) got a trebuchet, a mangonel and two engineers, roster-backed and owned by A's faction (`spike-spawn`),
placed about 60 blocks from B.

* **Mounting.** Both engineers mounted an engine unprompted.
* **Targets.** Both engines took targets in B: militia, and the mangonel also a crossbowman. They fired (projectiles
  seen), and a B crossbowman was killed. Neither targeted a civilian (blast damage to bystanders was not measured).
* **Movement.** Both engines walked back about 40 blocks towards A (their HYW home was A). In a siege their home must be
  held at the battle line.
* **Blocks.** A before/after comparison of B's core failed, but the control without engines (`ENGC`) fails the same way:
  village life (doors, crops, Millénaire building) changes blocks within 2 minutes. That check is inconclusive; the code
  says the explosions do not break blocks.

## Proposal

* **A siege train of temporary engines and engineers.** They are not garrison slots: spawned with the host, owned by the
  attacker's faction, recorded in the `Siege`, removed when the siege ends (and refused if a leftover loads later).
* **Size by the attacker's tier** (data, per culture):
  * GUARD_POST: none;
  * GARRISON: 1 light engine (mangonel or springald);
  * STRONGHOLD: 2 (a trebuchet, plus a cannon only where gunpowder is enabled).

  Each engine comes with one engineer.
* **Watched battle.** Engines deploy 30–50 blocks behind the landing point, with their home pinned there: stationary,
  so no tree felling. Engines never count in the host's rout share; their crews do.
* **Off-screen.** Each engine adds strength and cancels part of the target's fortification bonus: they are what walls
  are for.
* **Excluded:** battering rams (block breaking) and siege towers.
* **Open questions for the owner:**
  * explosion splash can hurt civilians near defenders;
  * leaf-only decoration can be felled if engines move (the HYW config switch covers it);
  * whether a defending stronghold should also get engines.
