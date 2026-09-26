# HywMill M4-0 Spikes

The dedicated server ran NeoForge 21.1.226, Millénaire 9.0.2 and HYW 0.7.1r-fix1; the Epic Knights
runs added Epic Knights 10.15, Architectury 13.0.11 and Cloth Config 15.0.140. Static findings were
checked with `javap` against the pinned jars; runtime findings come from harness scenarios S4 and
S4b and from the `ekspike` run mode. Evidence is in `docs/m4-test-evidence/`.

**No spike requires a mixin or a change to Millénaire or HYW.** Every integration point below is a
public method of those mods or plain vanilla API.

## 1. Millénaire raid lifecycle

### Static findings

* **No raid events.** Millénaire 9.0.2 fires no raid events. `RaidManager` is static and package
  internal.
* **Public observation surface.** The public `Village` getters expose the whole lifecycle:

| Getter | Meaning |
|---|---|
| `getRaidTarget()` | The target `VillageId` during planning and the raid, null otherwise |
| `getRaidPlanningStart()` | Tick planning started |
| `getRaidStart()` | Tick the raid started; 0 while only planning |
| `isUnderAttack()` | On the defender |
| `getRaidsPerformed()`, `getRaidsSuffered()` | History; each grows by one when a raid ends |

* **How raiders travel.** Millénaire raiders do not walk to the target:
  * At the start of the raid the raiders' records are marked away-raiding.
  * About 500 ticks later, `materializeRaiders` spawns **clones** at the target's edge.
  * That spot comes from `RaidSpawnLocator.findSpawnPoint(level, target, attackerCenter)`, which is
    public, and it is used only if the spot is entity-ticking (never force-loaded).
  * At the end, `endRaid` cleans up the clones and resolves the result by strength
    (`resolveAttackersWin`).
* **Forced raids.** `millenaire dev raid trigger` calls the public `RaidManager.startRaidForced`.

### Runtime findings (S4, S4b)

* **Lifecycle observed** through `SettlementSource.raidInfo`, by polling public state:
  * A's target is set to B, with `raidStart = 2906`.
  * B is under attack.
  * About 74 s later the raid ended ("Raid FAILURE: … repulsed"), A's target was cleared, and
    `performed` and `suffered` each went up by one.
* **Landing point.** `RaidSpawnLocator.findSpawnPoint` returned a real landing point, 73 blocks
  from B's centre.
* **Raid units need to be close to fight.** HYW units placed at the landing point and engaged once
  on a distant resident did **not** fight. When moved close to the defending village and
  re-engaged every few seconds (`TemporaryHostileTargetManager.markHostile` + `setTarget`), they
  hit B's residents (4 hits) and B fought back (26 hits on the raid units).
* **Relation.** The A↔B faction relation stayed NEUTRAL (ALWAYS_REVERT); only temporary hostility
  is used.

### Integration point

* Poll the attacker's `raidInfo` on the village's existing staggered slot.
* At start, select and mark the contingent.
* At Millénaire's materialization time (raidStart + 500 ticks, target entity-ticking), move the
  contingent to Millénaire's own landing point.
* Then advance it towards the target by HYW home hops, re-engaging nearby defenders.
* At the end, return the survivors.

This mirrors how Millénaire moves its own raiders (materialization, not walking across unloaded
chunks). Millénaire's NPC raid participants are untouched.

## 2. Wand of Negation

### Static findings

* **How deletion is triggered.** Clicking a village with the wand only opens a client confirmation
  screen. The server's confirm handler (`NegationWandConfirmPayload`) checks that the player holds
  the wand and is in range, then calls the public static
  `NegationWandItem.performDeletion(level, data, village, player)`.
* **What `performDeletion` does:**
  * unlocks the chests;
  * discards the loaded villagers;
  * releases the village's force-loaded chunks;
  * calls `VillageManager.removeVillage`;
  * clears the other villages' relations and parent links;
  * aborts raids targeting it.

  It fires no event.

### Runtime findings (S4, calling that exact method with a fake player)

* **Village removed.** The village is gone from `VillageManager`, and 13 loaded villagers were
  discarded ("deleted by negation wand (13 villagers removed)").
* **HywMill notices.** It logged "missing from Millénaire's village list" within seconds.
* **No recruitment into the negated village**, with levy set to 10: none before the restart, and
  none after it.
* **After the grace period** (with a restart in between; `goneSinceTick` persisted): 5 slots were
  LOST(VILLAGE_GONE), and orphan policy KEEP applied to 5 loaded units.

**Conclusion.** The wand is normal village deletion. HywMill's existing VILLAGE_GONE lifecycle
handles it with no change. Recruitment stops at once, because the village is no longer in the
settlement list, so no garrison slot runs for it.

## 3. HYW mounted units as scouts

### Static findings

* **Horse management.** `MountedLightLancerRiderEntity`, `MountedLancerRiderEntity` and
  `MountedArcherRiderEntity` spawn and manage their own horse (`spawnHorse` in `tick`, saved as
  `savedHorseData`/`horseUUID`).
* **Returning home.** HYW's `ReturnToHomeGoal` moves a unit to its HYW home through HYW's own
  pathing, with no explicit range cap in `canUse`.

### Runtime findings

* **Mounting.** Both rider types, spawned through the production path, mount their own `hyw_horse`.
  After a restart they reload once, still mounted, with no duplicate horses.
* **Single-hop moves** (moving only the HYW home): a spear_man and a mounted light lancer each
  reached a new home 16, 24, 32, 48 and 64 blocks away, ending within 1–4 blocks.
* **Long moves:**
  * In S4, a single 99-block move produced no movement. The spot was probably unreachable terrain
    or water; S4b shows 64-block hops work.
  * A chain of 20-block waypoint hops reached 4 of 5 waypoints and ended 10 blocks short of a
    99-block post.
  * So scout posts and route points must be chosen on standable, dry, loaded ground, validated like
    M3 spawn spots.

**Conclusion.** Mounted scouts work as ordinary garrison roster units. The rider is the roster
entry; the horse is HYW's.

Movement is driven only by the HYW home position:
* hops of at most 32 blocks;
* ground-validated waypoints;
* the next hop issued on arrival or timeout.

No pathfinding system of our own is needed.

## 4. Epic Knights equipment compatibility

### Static findings

* **HYW's own Epic Knights files.** HYW ships `equipment_epic_knights.json` for its units. These
  list, per level and slot, the Epic Knights items HYW itself uses. For example:
  * spear_man: pikes and ahlspiesses;
  * shieldman: swords, plus heater, kite or round shields and bucklers;
  * archer: the longbow;
  * crossbowman: the heavy crossbow.
* **Culture-flavoured items in Epic Knights 10.15:**
  * `norman_helmet`;
  * kite, round, elliptical, rondache and pavese shields;
  * `lamellar_chestplate` and `lamellar_boots`;
  * `shishak` (Turkic helmet);
  * `orthodox_cross_pattern`;
  * a full range from gambeson to plate.
* **How HYW applies equipment.** Its intrinsic equipment is applied only through its own JSON
  (`setEquipment`; the level-data setter is protected). Overlaying through vanilla `setItemSlot` is
  the only public route.

### Runtime findings (`ekspike`, HYW Epic Knights compat on)

* **Persistence.** 16 overlays on the six M3 unit types and the mounted rider all persisted after
  15 s and after a restart. This included deliberately mismatched weapons: a pike on an archer, a
  longbow on a crossbowman, a longbow on a spear_man, and a pike in a shieldman's offhand.
* **HYW does not reject mismatched gear.**
* **Unregistered items** (`magistuarmory:no_such_item`) cannot be set, and the unit keeps its HYW
  gear.
* **Combat with overlays is not conclusive.**
  * Units with mismatched weapons still damaged their targets.
  * Two same-family overlays dealt no damage in 30 s, because the units did not engage their
    target.
  * "It fought" is therefore not a usable compatibility test.

### Rule adopted for M4 (deterministic, data-derived)

* **Weapons and offhand.** A profile item for mainhand or offhand is allowed for a unit only if it
  belongs to an item family that HYW's own Epic Knights file lists for that unit's slot (family =
  the item id without its material prefix, for example `*_pike`, `longbow`, `*_kiteshield`).
* **Armour.** Armour items only need to fit their slot.
* **Fallback.** Anything else, including unregistered items and items when magistuarmory is absent,
  is skipped for that slot, keeping HYW's normal equipment.
* **Validation tool.** The M4 tool reports every invalid profile entry.
