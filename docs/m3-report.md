# HywMill M3: Village-Owned HYW Garrisons (Report)

Pins: Minecraft 1.21.1, NeoForge 21.1.226, Millénaire 9.0.2, HYW 0.7.1r-fix1. One JAR, no
bundled foreign classes, no mixins, no new dependencies.

Branch: `claude/millenaire-hyw-audit-5n4u8s`.

## 1. Commits

| Commit | Content |
|---|---|
| `8522eac` | M3 proposal (docs) |
| `efb7312` | Main implementation checkpoint: SPI, HYW provider, tag, spike tools, roster and format 4, tables, reconciler and join adjudication, recruitment, deployment, commands, 76 new JUnit tests |
| `befee09`, `fe0bbbb`, `a338288` | M3-0 spike harness fix, spike report, spike evidence |
| `49e749a`, `a2f7210`, `aefc2e2` | G3 harness fixes (controller command path, census invariant, unload timing, fight window, controller test on a player-controlled village) |
| later commits | Evidence for G3 (41/41), M2 regression (enabled and disabled), migration, Epic Knights, performance; this report |

The first implementation commit is a single checkpoint covering M3-1 to M3-7, not one commit per
step. The steps were still **built in the required order**: the M3-0 spike code first, then the
roster and format 4, tables, provider, anti-duplication, recruitment, deployment and commands.
Anti-duplication (reconciler, join adjudication, deterministic UUIDs, property test) was complete
and tested before automatic recruitment was enabled on any server. The dedicated-server runs
followed M3-0.

## 2. Files and classes

New, pure logic (no foreign imports), in `dev.hywmill.garrison`:
* `UnitState` (explicit transition table)
* `LossReason`
* `RosterEntry`
* `GarrisonRoster` (NBT, roster-first spawn steps)
* `Reconciler`
* `JoinAdjudicator`
* `Recruitment` (target, levy, balanced choice, starting grant, gating, cooldowns)
* `Deployment` (reuses M2's `DefenseCoordinator.assign`)
* `SpawnSpots`
* `GarrisonSettings`

Other new code:
* `garrison.tables`: `UnitSpec`, `UnitClass`, `TierRule`, `GarrisonTable`, `GarrisonTables`,
  `GarrisonTableLoader`, `GarrisonTablesDescribe`.
* `garrison.spi`: `UnitProvider`, `EquipmentProvider` (the authorized signature), `SpawnRequest`,
  `SpawnResult`.
* `garrison.tag`: `GarrisonTag` (village, rosterId, generation; deterministic entity UUID) and
  `GarrisonAttachments` (NeoForge data attachment).
* `garrison.service.GarrisonService`: world glue, owned by `HywMillRuntime` (no static server
  state).
* `integration.hyw`: `HywUnitProvider` and `HywEquipmentProvider`, the only classes that touch HYW
  garrison APIs.
* `core`: `GarrisonCommands` and `GarrisonSpikeCommands` (dev-only).
* Data: `data/hywmill/hywmill_garrison/units.json` and `defaults.json` (tiers, levy, cultures).

Changed:
* `VillageRecord`: format 4, `hywRoster`. `garrison` is still the Millénaire soldier count.
* `GarrisonLedger`: 3→4 migration log.
* `GarrisonUpdater`: garrison slot at offset interval/4; village-gone check at list refresh.
* `DefenseService`: a read-only scan listener; M2 state is untouched.
* `IncidentLedger`: `victimGarrisonOf`, so an attack on a garrison unit is an attack on the village.
* `CoreEvents`: join, leave and death hooks; table loader.
* `HywMillConfig`: `[garrison]` section.
* `Services`: unit and equipment providers.
* `HywIntegration`, `HywMill`: attachment registration.
* `SettlementSource`, `MillenaireSettlementSource`: dev-only `devRemove`, which calls Millénaire's
  own `VillageManager.removeVillage`.
* `MilitaryCommands`: `village military` and `perf` show garrison data.
* `build.gradle`: `-Dhywmill.*` system properties reach the tests.
* Harness: scenarios S, G3-1 to G3-17, and the `migrate3` and `epicknights` run modes.

## 3. M3-0 spike (R1–R8)

See `docs/m3-spike.md`. All results are acceptable, and R1 needs no countermeasure, so the stop
rule was not triggered.

* **R1:** no neutral targets or hits in 60 s. The bystanders tested were another village's
  residents, a player-owned HYW unit, a cow, a vanilla villager, and the village's own residents.
* **R2:** home and return work.
* **R3:** `markHostile` plus `setTarget` works; the hostility clears after about 11 s and is never
  permanent.
* **R4:** vanilla refuses a duplicate UUID, both when spawning and on chunk load.
* **R5:** the tag survives a dimension change and a restart.
* **R6:** equipment levels 0–3 apply exactly. HYW maps an out-of-range level to 0, and the roster
  stores the level actually applied.
* **R7:** residents' routines are unaffected.
* **R8:** units are never residents' targets or M2 threats.

## 4. Unit table validation

Every table entity type is validated at load against the live registry *and* the M3 allowlist:
militia, spear_man, shieldman, warrior, archer, crossbowman, plus handgonne_man and matchlock_man,
which are disabled.

* Invalid or unknown entries are logged and skipped. JUnit covers cavalry, iron_mage,
  mounted_lancer_rider, siege_engineer and an unknown class.
* Loading the shipped data produced **0 problems** on every dedicated-server run:
  `Garrison tables loaded … 8 unit type(s) (6 enabled), 7 culture(s)`.
* Mounted, siege, worker, hostile-faction and unregistered units cannot be declared.
* Gunpowder units are off by default.

## 5. Tier caps

Per village only: NONE 0, WATCH 8, GUARD_POST 16, GARRISON 32, STRONGHOLD 64, taken from data
(`maxUnits`). Verified by JUnit and in the server log line `tier caps …`.

Target formula: `min(clamp(round(capacity × perCapacity), minTarget, maxTarget), maxUnits)`.
* A small stronghold stays small: capacity 5 gives 5.
* A large one reaches 64.

There is **no server-wide cap** and no hidden equivalent: nothing counts HYW entities globally.
`spawnsPerTick` (2) only limits throughput; excess waits for the next slot.

## 6. Starting grant

Measured on the dedicated server:

| Village | Tier | Capacity | Target | Grant (ceil(target × 0.5)) |
|---|---|---|---|---|
| A | GUARD_POST | 16 | 16 | 8 |
| B | WATCH | 6–7 | 6–7 | 3–4 |

Additional results:
* All granted units spawned, 2 per slot.
* Units are unpaid.
* No second grant after a restart (G3-1), after migration (G3-16), or after a further restart.

## 7. Recruitment examples

Norman composition over 90 draws is exactly 10/30/20/20/10 for weights 1:3:2:2:1 (JUnit). Gating
order is enforced (JUnit): disabled, paused, not CALM, tier cap, target, interval, no unit, points.

On the server:
* A GUARD_POST village accrued `+5.50/day` (1.5 + 0.25 × 16) up to its cap of 6.
* A paid recruit followed a death only after the death cooldown and the recruit interval (G3-10).
* A reloaded datapack composition applied to new recruits only (G3-15: new slot = militia;
  existing slots unchanged).

## 8. Relations and ownership

Every unit has HYW OwnerUUID = the village faction UUID, never a player (G3-1). The garrison owner,
the residents' relation marker and the village faction are identical (G3-2).

| Situation | Result |
|---|---|
| Garrison vs. its own residents (G3-2) | 0 hits either way |
| Player-owned units passing through (G3-3) | Untouched; relation NEUTRAL |
| Such a unit damaging a resident (G3-4) | M2 threat, then ENGAGED; 2 garrison units deployed (commitPerThreat 2) with temporary HYW hostility; they return when it ends; relation still NEUTRAL (ALWAYS_REVERT) |
| Zombie (G3-5) | Killed by HYW's native AI; no HywMill deployment |
| Unowned bandit (G3-6) | Fought and killed by the garrison; 0 hits on residents |
| Millénaire owner change (G3-12a) | Units and faction unchanged |
| Player-controlled village (G3-12b) | controllerPlayerId is separate from the faction; that non-op controller can pause the garrison, a stranger cannot |

## 9. Duplicate prevention

JUnit:
* Join adjudication: bind, adopt, duplicate (forged UUID, other generation, terminal slot,
  retired slot), orphan, release.
* The property test runs **10,000 seeded runs** of spawns, deaths, captures, unloads, saves,
  crashes with either the roster or the entities behind, rewinds and restarts, asserting I1–I5
  after every step, plus 2,000 restart-idempotency runs.
* Explicit test: "10 roster entries never become 20 entities".

The property test found a real defect, which is fixed. After a stale roster, a failed spawn used to
consume a generation, and a retry would then get a *new* UUID and duplicate the unit. Now a revert
restores the generation, so retries reuse the UUID (vanilla refuses it while the original is
loaded), and the reconciler adopts a loaded unit with the slot's next-generation UUID.

Server results:
* G3-7: three restarts gave an identical census, 0 spawns, 0 duplicates.
* G3-8, loaded: the stale slot adopted its unit, and the spawn was refused.
* G3-8, unloaded: the respawn reused the slot UUID, vanilla refused the stale copy on chunk load,
  and each slot has exactly one unit.

## 10. Death and replacement

* G3-10: `/kill` gives DEAD(KILLED); the slot is never respawned.
* A replacement is a new rosterId, created only after the death cooldown and the recruit
  interval, and paid from the levy.
* Wipe-out (≥75% of the target killed during one alert) adds 24000 ticks of cooldown (JUnit).
* `equipmentDrops=false` sets the drop chances to 0.

G3-9: a unit in an unloaded chunk is still bound during the grace period, becomes MISSING after it
(never DEAD), is not replaced, and becomes RECOVERED and then GARRISONED when it loads again.

G3-11: an owner change gives LOST(CAPTURED). The owner is not rewritten; the tag is removed.

## 11. Village deletion

G3-14: Millénaire's own `removeVillage` fires no event.
* HywMill logs "missing from the village list" and waits the 6000-tick grace period; nothing
  happens early.
* After the grace period all slots become LOST(VILLAGE_GONE).
* Orphan policy KEEP: all units stay alive as ordinary HYW units, untagged, owner unchanged.

## 12. Migration

G3-16, 7/7: a world written by the M2 build (ledger format 3) loads in M3.
* It is migrated to format 4 with IDs, faction UUIDs and the Millénaire soldier count (17 and 8)
  unchanged.
* There is exactly one starting grant per village.
* After a restart it is format 4 on disk, with no further grant.

JUnit also covers these: an M2 reader ignores the roster (every format-3 field is byte-identical in
format 4), and an unknown saved state degrades to MISSING. Downgrade is unsupported.

## 13. M2 regression

| Run | Result | Failures |
|---|---|---|
| M3 loaded, **garrison disabled** (the M2 regression confirmation) | **70/72** | Only the accepted performance-tail items P3a and P3b (`profile.refresh`). C2 and C3 pass (bandit 5 hits on villagers; 4 defenders, 14 hits). |
| M3 loaded, **garrison enabled** | 67/72 | P3a and P3b; A4 (the harness expected ledger "format 3", fixed to accept 4); **C2/C3: intended M3 behavior change**, see below |

**C2/C3 with garrisons enabled.** Village A's garrison killed scenario C's bandit before it reached
a villager, so there was no attack for Millénaire's defenders to answer. This is the new persistent
HYW defense layer working (the same behavior G3-6 verifies). As decided, the garrison is not
weakened or bypassed and scenario C is not modified. The garrison-disabled run confirms that M2's
own behavior is unchanged.

## 14. JUnit

**132 tests pass (76 new).**

| Class | Tests |
|---|---|
| `GarrisonLifecycleTest` | 11 |
| `ReconcilerTest` | 12 |
| `JoinAdjudicatorTest` | 10 |
| `DuplicationPropertyTest` | 3 (10,000 + 2,000 seeded runs) |
| `RecruitmentTest` | 16 |
| `GarrisonTablesTest` | 8 |
| `DeploymentTest` | 9 |
| `SpawnSpotsTest` | 2 |
| `GarrisonMigrationTest` | 5 |

## 15. Dedicated-server harness

| Suite | Result | Evidence |
|---|---|---|
| M3-0 spike (S) | 17/18; the only FAIL is a measurement regex, and bystanders were verified unharmed in the log | `m3-spike-run4.txt` |
| G3-1 … G3-15 | **41/41** | `m3-g3-run3-final.txt` |
| G3-16 migration | 7/7 | `m3-migration-g3-16.txt` |
| G3-17 performance | §16 | `m3-performance.txt` |
| G3-18 M2 regression | 70/72 (garrison off), 67/72 (on) | `m2-regression-*.txt` |
| Optional: Epic Knights | 7/7 | `m3-epic-knights-optional.txt` |

## 16. Performance

PERF_PLACEHOLDER

## 17. Optional dependencies (Epic Knights)

7/7:
* The HywMill jar's `neoforge.mods.toml` has no magistuarmory entry, and **no class references
  it**.
* With Epic Knights 10.15, Architectury and Cloth Config installed, and HYW's own
  `enableEpicKnightsCompat` switched on in HYW's config, the server loads with no HywMill errors.
* Garrison units spawned by the unchanged HYW provider wear Epic Knights gear from HYW's own
  `equipment_epic_knights.json`: 30 magistuarmory items on the first 6 units.

HywMill contains no Epic Knights code.

## 18. Known limitations

* **Native HYW targeting.** Garrison units are real HYW units, so HYW's own AI engages
  inherently hostile targets (monsters, unowned HYW units) on sight, whatever the doctrine's
  proactive flag says. HywMill's *deployment* follows M2 (proactive=false). As a result, a
  garrisoned village often stops such an attacker before any villager is hurt (C2/C3 above).
* **Short temporary hostility.** HYW temporary hostility lasts about 11 s. The deployment
  re-engages on every M2 scan while M2 lists the threat.
* **Inactive time is runtime-tracked.** Inactive-time exclusion (the MISSING and LOST timers, and
  no offline accrual) uses the gap since the last slot. Time a village spent inactive *before* a
  server restart counts as active for these timers after the restart.
* **Headless coexistence only.** Pathing and collision with Millénaire were observed without a
  player client.
* **Headless permissions.** They were verified with a non-op NeoForge `FakePlayer`
  (`/hywmill dev runas`), not a real client.
* **HywMill-only control.** The controller manages the garrison through `/hywmill` commands only;
  there is no HYW UI control, by design.
* **Equipment level mapping.** HYW maps an out-of-range equipment level to 0. The shipped data
  never requests one for the enabled units.

## 19. Deviations from the authorization

* **Test-only additions:**
  * `dev spike-spawn`, `spike-engage` and `spike-info` (M3-0 tooling);
  * `dev remove-village` (Millénaire's own deletion API);
  * `dev runas` (non-op fake player).

  All require `devCommands=true` and op level 2.
* **`spawnsPerTick = 2` config.** This is the per-tick spawn budget the authorization asks for,
  not a troop cap.
* **`RECOVERED` is a short-lived live state.** It is implemented as an explicit state in the
  transition table, and the next slot moves it to GARRISONED.
* **Commit structure.** One checkpoint commit covers M3-1 to M3-7 (see §1).

No frozen decision was changed.
