# M1 audit and M2 proposal

**Status:** audit and design only. No M2 code has been written, and M1 implementation files are unchanged. Everything below comes from the current source, `javap`/decompilation of the supplied JARs, and targeted runtime experiments on the M1 dedicated-server test world.

**Evidence labels used in this document:**
- **PROVEN-RUNTIME**: reproduced on the server.
- **PROVEN-DATA**: read from the shipped JAR data or saves.
- **STATIC**: read from the code.
- **ESTIMATE**: reasoned, not measured.

---

## 1. Executive assessment of M1

M1 did what it had to: it proved the bridge. The isolation (optional loading, `LinkageError` handling, no foreign classes in core) is sound, the goal-replacement seam is real, and the combat path works in both directions.

The audit found **one functional bug**, **one semantic error that makes the ledger's headline numbers misleading**, and **two persistence and lifecycle hazards**. None of them invalidates M1's proof, but all should be fixed before M2 builds on the ledger:

| # | Finding | Evidence |
|---|---|---|
| 1 | **Escalation guard misses escalations.** HYW can escalate a village faction to permanent HOSTILE without our guard ever seeing it. | PROVEN-RUNTIME |
| 2 | **Fortification and tier numbers are wrong for real villages.** The `patrol` tag is not a defensive-building marker, and border posts aren't counted as walls. | PROVEN-DATA |
| 3 | **Identity markers outlive the mod.** Removing hywmill (or setting `markVillagers=false`) leaves villagers permanently marked, and unowned HYW units keep hunting them while guards can no longer fight back. | PROVEN-RUNTIME |
| 4 | **Uncrewed "neutral" siege engines are classified as threats**, although HYW's own targeting ignores them. | STATIC |

The architecture is adequate for M2 **if** two structural debts are paid at the start of M2 (§2.B):
- Static singletons need to become one per-server runtime context.
- Heuristic classification needs to become a data-driven role table.

As M1 stands, "tier" and "fortification" are **not trustworthy enough to drive behavior**. That is the main reason the M2 recommendation below starts with classification.

---

## 2. Problems found

### 2.A Correctness and safety (feeds §3 M1.1)

**P1. Escalation guard is event-driven and misses i-frame hits. PROVEN-RUNTIME.**
- HYW's `LivingEntityHurtMixin` injects at the **head** of `LivingEntity.hurt`. It records "damage" and escalates after 3 records **before** vanilla's invulnerability-frame check.
- Our guard runs from `LivingDamageEvent.Post` (`CoreEvents.java:56`). That event is not posted when the hit is absorbed by invulnerability frames.
- **Experiment:** three FakePlayer hits on a villager in the same tick. Hit 1: `hurt=true`, incident recorded. Hits 2–3: `hurt=false`, no event. HYW printed its escalation, and `/hywmill dev relation` then showed village ↔ player **HOSTILE / HOSTILE**, with no guard WARN. It stays in `relations.dat`.
- **Real-play trigger:** a player swinging quickly at villagers, sweep attacks, or multishot.

**P2. `patrol` is treated as "defensive building". PROVEN-DATA.**
- In Millénaire content, `patrol` marks guard patrol **destinations**. It is on the Norman forge and 12 abbey sections, Seljuk markets, wells, olive farms and pistachio groves, Byzantine gardens, olive presses and nodes, and every culture's `borderpost_gate`.
- `MillenaireSettlementSource.java:99-114` counts every patrol-tagged non-wall building as defensive. `MilitaryTier.java:24` uses `patrol>0` as "defensive infrastructure".

**P3. Border posts are invisible as walls, and their gates count as defensive buildings. PROVEN-DATA.**
- Douvres la-forge's saved buildings contain **13 completed border posts** (7 posts, 2 corners, 4 gates). `BuildingPlanSet.isWallSegment()` is false for them, so M1 reports `walls=0`.
- The 4 gates are patrol-tagged, so they are the **only** reason the village shows `defensive=4`, `fortification=12` and tier `GUARD_POST`. It has no guardhouse, watchtower or fort.

**P4. The military-plan keyword heuristic is too broad. PROVEN-DATA.**
- `MILITARY_PLAN_KEYWORDS` contains `"tower"` (`MillenaireSettlementSource.java:43`). That matches `abbaye_a_tower_bottom/top`, bandit towers and so on.

**P5. Identity markers persist after the mod is removed or marking is disabled. PROVEN-RUNTIME.**
- The test world was booted with Millénaire + HYW and **without** hywmill.
- `/data get entity <villager> HundredYearsWarRelationIdentity` returned `{OwnerUUID:[I;446949071,…]}`. That is `1aa3e6cf-…`, the village faction.
- HYW's mixin loads and saves this tag independently of us. Villagers therefore remain an HYW faction that every unowned unit treats as an enemy, while the Millénaire goal decorators that let guards fight back are gone.
- `markVillagers=false` has the same effect: it stops *new* marking and never clears existing markers (`FactionMarker.ensure`).

**P6. Uncrewed "neutral" siege engines are classified as threats. STATIC.**
- HYW's own `BaseCombatEntity.isValidTarget` rejects `isNeutralUncrewedSiegeWeapon()` targets (`"neutral_uncrewed_siege"`).
- Our `findCombatUnits` and classification don't. An unowned, uncrewed trebuchet in or near a village (for example inside an HYW structure) would make defenders attack it and make civilians shelter indefinitely.

### 2.B Architecture and design debt (not bugs; pay down in M2)

| # | Debt | Why it matters |
|---|---|---|
| D1 | **Static singletons everywhere** (`Services`, `ThreatTracker.STATES`, `IncidentLedger`, `FactionIds`, `HmLog.LAST`, `MillenaireGoalBridge.INSTALLED`) | Cleared by hand on `ServerStoppedEvent`. Works for a dedicated server. The **integrated server (single-player) path is untested**: world switching in one JVM relies on every static being reset. M2 adds more state, so introduce one `HywMillRuntime` created at server start and dropped at stop. |
| D2 | **Classification is scattered heuristics** (tags, keywords, `helpInAttacks`) inside `MillenaireSettlementSource` | M2/M3 need stable *roles*. See P2–P4 and §4.1. |
| D3 | **`garrison` means `helpInAttacks`** | This counts lumberjacks, farmers, miners and carpenters. Reasonable as "people who fight", but doctrine needs soldiers vs militia vs leaders. The data shows no single Millénaire flag separates them (the `patrol` goal also appears on chiefs, forest keepers and bandits), so an explicit role table is needed. |
| D4 | **Gameplay settings live in COMMON config** | Values such as radii and policies should be SERVER (per-world) config or data. |
| D5 | **Burst work** | All active villages are refreshed in the same tick every 200 ticks and scanned in the same tick every 20. Fine now; worth staggering by village hash (§6). |
| D6 | **Village-wide hide** | Civilians hide when any threat exists anywhere in the village bounds, even 150 blocks away. This is a doctrine parameter (§4). |
| D7 | **`ATTACKING_ALLY_PLAYER` requires the player inside the building bounds** | Not a defense radius; related to D6. |
| D8 | **Stale records** | Villages Millénaire forgets stay in our ledger and threat map forever. Small, but there should be an explicit "orphaned" state. |
| D9 | **`LinkageError` inside decorators** | Decorators run inside Millénaire's `GoalScheduler`, which catches `Exception`, not `Error`. A first-call `LinkageError` there would propagate. Risk is low with pinned versions (see Q1); wrapping the bridge-only branch is cheap. |
| D10 | **INFO log noise** | "Village record updated" logs at INFO whenever `defendingStrength` changes, which follows inventory churn. Keep INFO for tier, garrison and fortification changes only. |
| D11 | **No automated tests** | `MilitaryTier`, `FortificationScore` and classification are pure functions and should have JUnit tests. The headless-server harness (`cmd.sh`, console pipe) lives only in the scratch area and should be committed under `devtools/`. |
| D12 | **Pinned optional-dependency ranges crash on mismatch. PROVEN-RUNTIME.** | A variant JAR requiring HYW `[0.7.2,)` with 0.7.1r-fix1 present gives "Mod loading has failed … Expected range … Actual version", a hard stop rather than a graceful disable. Pinning is defensible (it prevents running against unknown internals), but it is a product decision (Q1). |
| D13 | **Persistent names are now contracts** | `hywmill_garrison_ledger` (SavedData), `hywmill:millenaire_village:` (faction UUID namespace, deliberately *not* derived from the mod id) and the `HundredYearsWarRelationIdentity` markers written into worlds. If you rename the mod id, the faction namespace and SavedData name must stay fixed (Q2). |
| D14 | **No re-marking when a hired villager's village changes** | For example `transferVillagerPermanently`. Only the 200-tick sweep catches it, and only for active villages. Acceptable for now. |

### 2.C Things checked and found OK

- **Classloading:** a `javap` scan of all non-integration classes shows no `org/millenaire` or `ydmsama` references. Optional-mode runs pass.
- **Accidental relation writes:** our only HYW writes are identity markers and the guard's reset to NEUTRAL. No HOSTILE is ever written.
- **Civilian combat:** our code never makes civilians fight HYW units. Millénaire's *native* engage still makes a civilian hit by a player or `Monster` fight back, which is Millénaire behavior and intentionally preserved.
- **Raid clones:** they are never marked, which avoids HYW's same-identity damage cancellation. The raid regression passed.
- **Dev commands:** gated by op level 2 **and** `devCommands=false` by default. `status`, `village info`, `list` and `threats` are open to all players. They are read-only and only reveal UUIDs.
- **Memory:** the incident ledger is capped (512 entries plus pruned indexes). The log-throttle map is pruned. The threat map is bounded by the number of villages.
- **Hot paths:** decorator `canStart` (called every tick per villager) does only map lookups.

---

## 3. M1.1 REQUIRED

All fixes are small and behavior-local, and none adds features. After M1.1, M1 tests A–F are re-run; the expected changes are listed per fix.

**M1.1-a. Escalation guard reconciliation (P1)**

| | |
|---|---|
| Problem | HYW escalations caused by hits that fire no damage event are never reverted. |
| Evidence | Same-tick triple-hit experiment above. |
| Why it matters | This is exactly the "accidental combat becomes permanent diplomacy" case you ruled out. |
| Minimum fix | Keep the event hook. Add a reconciliation pass every 200 ticks (or every threat scan for active villages): for each known village faction, check `RelationSystem.getAllRelations(faction)` (public), and reset any HOSTILE entry through the same `EscalationGuard` path with a WARN. Cost: one map lookup per village. |
| Acceptance impact | New test D5-b: 3 same-tick hits, then within ≤200 ticks the relation is NEUTRAL and a WARN is logged. |

**M1.1-b. Correct defensive classification (P2, P3, P4)**

| | |
|---|---|
| Problem | Fortification and tier are inflated by non-defensive buildings, and walls built from border posts are ignored. |
| Evidence | Douvres la-forge building dump above. |
| Why it matters | These numbers are the input to everything in M2. |

Minimum fix:
- Derive wall roles from Millénaire's own wall definitions: `ModCultures.getAllWallTypes()` gives each `WallType` its wall, tower, gateway, corner, cap and slope plan sets plus `wallSpawn()`.
- Use `BuildingPlanSet.isBorderPost()` / `isBorderBuilding()` to identify border markers.
- Classify buildings as WALL / TOWER / GATE / BORDER_MARKER. A plan set that belongs to a wall type with `wallSpawn()==false` (border posts) is a marker, not a wall.
- Replace the `patrol` tag and the keyword list with a small **explicit** table of military plan sets for the 7 shipped cultures (guardhouse, watchtower, fort, largefort, barracks, lookout, kastron/fortress, sarayfort, armoury…), shipped as a JSON resource.
- Keep `patrol` only as a reported count.
- Document the revised formula.

**Acceptance impact:** TEST A values change. Douvres should become tier WATCH with fortification from border markers only, weighted low or zero (see Q6). A ledger format bump (1 → 2) forces recomputation on load.

**M1.1-c. Identity marker lifecycle (P5)**

| | |
|---|---|
| Problem | Our faction markers outlive our mod and our config. |
| Evidence | The run without hywmill above. |
| Why it matters | Removing the mod silently leaves villages permanently "at war" with every unowned HYW unit and defenseless. That is a world-data hazard for players and pack makers. |

Minimum fix:
- When `markVillagers=false`, `ensure()` actively clears markers whose UUID is a known village faction.
- Add `/hywmill admin clear-identities [village|all]` (op 3), which clears loaded villagers now and flags the rest to clear on join.
- Document an uninstall procedure: run clear-identities, then remove the mod.
- A fully automatic uninstall isn't possible: once the mod is gone, nothing can run.

**Acceptance impact:** none on A–F. New test G: toggle `markVillagers=false`, restart, and markers are gone.

**M1.1-d. Ignore neutral uncrewed siege engines (P6)**

| | |
|---|---|
| Problem | Threat classification disagrees with HYW's own target filter. |
| Evidence | `BaseCombatEntity.isValidTarget` → `isNeutralUncrewedSiegeWeapon()` (public, bytecode-verified), versus our `findCombatUnits`. |
| Minimum fix | Exclude `isNeutralUncrewedSiegeWeapon()` units in `HywCombatFactionService.findCombatUnits`. |
| Acceptance impact | None on A–F. New test H: summon an uncrewed `hundred_years_war:trebuchets` with no owner in the village → no threat. |

**Recommended alongside M1.1 (non-behavioral):**
- Commit the headless test harness.
- Add JUnit tests for the pure formulas.
- D9 `LinkageError` wrapping.
- D10 log levels.

**D6 (real client) and single-player.** D6 can be done as a small follow-up and doesn't block M2 *design*. It should be done during M1.1 validation, because M2 changes the player-assist path. The same client session should also cover the single-player (integrated server) path, which has never been run: open a world, fight, save, quit to title, open a second world, then reopen the first. D6 cannot be automated here, since HYW registers content a vanilla client or bot can't join with. It needs your client, about 10 minutes, and a checklist is in §9.

---

## 4. API discoveries (verified with `javap` against the JARs unless marked)

### Millénaire 9.0.2 (all public)

**Wall roles.**
- `ModCultures.getAllWallTypes()` / `getWallType(id)`. `WallType` record fields: `id, culture, key, wallPlanSet, towerPlanSet, gatewayPlanSet, cornerPlanSet, cap*/slope* plan sets, wallSpawn, towerSpawn, gatewaySpawn, cornerSpawn, capSpawn, wallsBetweenTowers`.
- Border posts are a wall type with `wall_spawn:false` (data).

**Plan-set metadata.** `ModCultures.getBuildingPlanSet(id)` / `getAllBuildingPlanSets()`. `BuildingPlanSet` exposes:
- `isWallSegment()`, `isBorderPost()`, `isBorderBuilding()`, `isTownHall()`, `hasTag()`, `tags()`
- `maleResidents()` / `femaleResidents()`, and **`maleResidentsAt(variant, level)` / `femaleResidentsAt(variant, level)`**, which give per-level resident slots and therefore **real garrison capacity**
- `category()`, `maxCount()`

**Building state.** `BuildingInstance.getVariant()`, `getLevel()`, `getStatus()`, `getPlanSetId()`, `getInventory()`, `getPointsByType(type)`, `getFirstPointPos(type)`.

**Residents and strength.**
- `Village.getVillagerRecords()`
- `VillagerRecord.getMilitaryStrength()`, `getInventory()`, `isAwayHired()`, `getHiredBy()`, `getHomeBuilding()`, `add/remove/hasQuestTag()` (a free-form per-record tag store — could persist a role, but it writes into Millénaire's save)
- `Village.getVillageDefendingStrength()`, `getVillageRaidingStrength()`
- `Village.countResidents(Gender)`, `countResidentsInBuilding(BuildingId)`, `getResidentSlotManager()`

**Equipment (writes, M3+).**
- `VillagerInventory.add/remove/getAll` (`Map<Item,Integer>`: **no NBT components**)
- `ToolCategoryRegistry.get(id)`, `ToolCategory.getBestOwned()`

**Reclassification (M3+).** `Village.updateVillagerType(uuid, typeId)`, `MillVillager.setVillagerTypeId`, `initGoals`, following the same sequence as Millénaire's own `ChildBecomeAdultGoal`.

**Spawning (M3+).** `VillagerSpawnFactory.spawnInVillage(...)` has 3 public overloads.

**Diplomacy.**
- `Village.getRelation/setRelation/adjustRelation/adjustRelationSymmetric/getRelations`
- `getCombinedReputation`, `adjustReputation`

**Raids.**
- `RaidManager.planRaid`, `startRaidForced`, `abortRaidForAttacker`, `resolveDefendingPos`
- `Village.getRaidTarget`, `isUnderAttack`, `getRaidsPerformed/Suffered`
- Clone semantics as in M1.

**Hiring.** `Village.setVillagerHired(level, villager, owner, until)`.

**History.** `Village.recordEvent(level, String)` is free text, persisted in Millénaire's save. The chronicle is a closed enum.

**Patrols.** Data-driven: `visit_goal/patrol.json` visits buildings tagged `patrol`. Changing patrols means shipping Millénaire content (visit goals and tags) via `millenaire-custom/`. There's no Java API.

**Hard limits.**
- `VillageEventType` is closed.
- Raid logic cannot include non-`MillVillager` participants.
- `setUnderAttack` is reset without raid records.
- `CombatGoalSupport` is package-private (M1 mirrors it through public methods).
- There is no event bus.

### HYW 0.7.1r-fix1 (all public unless noted)

**Canonical unit spawn**, exactly as `BaseScrollItem` does it:
1. `EntityType.create(level)`
2. `BaseCombatSupport.setOwnerUUID(uuid)`
3. `setEquipment(level)`
4. `addFreshEntity`
5. `setHomePosition(pos)`

The owner may be any UUID, including a village faction. Entity types can be looked up by registry id; `HywEntityRegistry` fields are also public `Supplier`s.

**Persistence.** `BaseCombatEntity` calls `setPersistenceRequired()` in its constructor. Units never despawn unless HYW's null-owner "enemy mode" despawn config applies.

**Orders.**
- `setHomePosition` / `getHomePosition`
- `getPatrolPoints()` (a mutable list, persisted in NBT) / `clearPatrolPoints()`
- `setAttackStrategy(AttackStrategy {DEFAULT, FREE_FIGHT, FREE_ROAM, CEASE_FIRE, INDISCRIMINATE})`
- `setFollowTarget`, `setCommandHold` / `commandHold`, `setHolding`
- `addCustomGoal(priority, Goal)`

**Progression.** `getLevel` / `setLevel`, and `setEquipment(level)`, which loads equipment from JSON inside HYW's own JAR. That JSON is not overridable.

**Supply.**
- Units with `requiresSupply()` and no active supply relation get Hunger, Slowness and Weakness.
- Public controls: `setRequiresSupply(false)`, `SupplyManager.registerSupplySource(uuid, SUPPLY_POINT, max, pos, dim)`, `setSupplySourceOwner(sourceId, ownerId)`.
- **Village-owned units need an explicit supply decision** (M3).

**Relations.**
- `RelationSystem.getRelation/setRelation/getAllRelations/getRelationsByType`
- `ServerRelationHelper.isEnemyRelationByUUID/getRelationUUID`
- `TemporaryHostileTargetManager.isHostile/markHostile`
- the identity marker (M1)

**Squads and groups (internal, M3+).**
- `SelectionSystem.getSquads()`: a static `Map<UUID player, List<Squad>>` persisted to `squads.dat`. Player-centric, not an API.
- `GroupPathingManager.registerEntity(groupId, target, entity)` and `FormationManager` are public but deep internals.

**Commands.** Most `/hyw` unit commands need a player executor and the player's look target, so they're unusable for automation.

**Commanders.** The "commander" unit is a buff aura (`DesertRaiderCommanderEntity`); there is no strategic AI.

**Siege.** 11 engines, crewed by `SiegeEngineer` units. They break blocks: the battering ram via `destroyBlock`, projectiles via `BlockBreakable` with radius, hardness and count limits.

---

## 5. M2 feasibility matrix

| # | Feature | Feas. | Complexity | Key APIs | New architecture | Persistence | Main runtime risks | Mixins | Where |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Village military doctrine (policy driving behavior) | HIGH | M | M1 decorators, threat tracker, `Village` data | Doctrine resolver and policy hooks in decorators | Per-village overrides plus defaults data | Behavior tuning | No | **M2** |
| 2 | Culture-specific doctrine | HIGH | S | `Village.getCultureId`, datapack JSON | Doctrine defaults keyed by culture and village type | Data files | Balance | No | **M2** (defaults only) |
| 3 | Dynamic garrison capacity | HIGH | S | `BuildingPlanSet.maleResidentsAt`, `ModCultures.getVillagerType` | Capacity computation | Derived, cached in record | Variant/level edge cases | No | **M2** (reporting and doctrine input) |
| 4 | Guard specialization / classes | MED | M–L | Role table; changing *behavior* per class needs Millénaire types or content | Role table (M2); new villager types via content pack (M3+) | Data | Content-pack deployment untested | No | Roles in **M2**; new classes M3+ |
| 5 | Weapon / equipment progression | MED | M | `VillagerInventory.add`, `tool_categories` content, `getMilitaryStrength` | Armoury → inventory transfer policy | Ledger of issued items | Item-component loss; economy interplay | No | M3 |
| 6 | Expanded patrol behavior | MED | M | Visit-goal content or custom `VillagerGoal` | Patrol-route goal | Routes per village | Pathing and chunk loading | No | M3 |
| 7 | Village defensive radius | HIGH | S | Threat tracker, `Village.getCenter`, `computeBounds` | Doctrine parameter | Override | None significant | No | **M2** |
| 8 | Fortification progression | MED | M | Wall roles (M1.1), `BuildingInstance.getLevel` | Progression model | Derived | Needs Millénaire growth to cooperate; can't force building | No | Scoring in **M2**; progression M3+ |
| 9 | Military buildings affecting capability | HIGH (via doctrine) / LOW (via Millénaire stats) | M | Role table, capacity | Doctrine modifiers | Derived | Changing Millénaire damage or health needs attributes or types | No (attributes) | Modifiers to *policy* in **M2**; stat buffs M3 |
| 10 | Recruitment of HYW units from villages | HIGH (tech) | L | Canonical spawn path, `setOwnerUUID`, supply | Roster, recruitment rules, costs | Roster SavedData | Supply penalties, balance, unit AI far from home | No | **M3** |
| 11 | Persistent HYW units belonging to villages | HIGH (tech) | L | As #10, plus `setPersistenceRequired` (default), home and patrol | Roster reconciliation (dead, missing, unloaded) | Roster | Unloaded chunks, owner=faction quirks | No | **M3** |
| 12 | Village HYW squads / formations | LOW–MED | L | Squads are player-keyed internals; `GroupPathingManager` | Own squad model | Yes | Deep internals | Maybe | M4+ |
| 13 | Player interaction with village military policy | HIGH | S (commands) / L (GUI) | `Village.isPlayerControlled`, `getOwnerUUID`, reputation | Permission model | Overrides | Griefing if too open | No | **M2** (commands) |
| 14 | Military resources / supply from village economy | MED | M | `getVillageItemCount`, `BuildingInventory.remove`, `SupplyManager` | Upkeep model | Yes | Draining village stock breaks Millénaire economy | No | M3–M4 |
| 15 | Millénaire raid ↔ HYW combat integration | MED | M–L | `RaidManager` (public), clone semantics | Raid observer and HYW auxiliaries | Yes | Raid code only counts `MillVillager` records | No for observation; likely yes for deep changes | M4 |
| 16 | Village-to-village warfare | MED | L | Relations, `planRaid` | War state machine | Yes | Needs 10/11/15 first | No | M4+ |
| 17 | Siege warfare | LOW–MED | XL | HYW engines, wall roles | Siege event, block-damage policy | Yes | Permanent damage; no Millénaire repair | Likely no | M5+ |
| 18 | Territory / controlled area | MED | M | Bounds, radius | Territory model | Yes | Mostly cosmetic unless tied to behavior | No | M4+ |
| 19 | Off-screen / virtual armies | MED | XL | None (ours) | Simulation layer | Yes | Chunk materialization | No | M5+ |
| 20 | Military GUI | HIGH (tech) | M–L | Client screens, payloads | Client package, networking | No | Client/server sync, first client code | No | M3+ |
| 21 | Village military statistics / reporting | HIGH | S | Ledger and incident ledger | Stats counters | Yes (small) | None | No | **M2** |

---

## 6. Performance and scalability (ESTIMATES; M2 adds counters to measure)

The key fact is that Millénaire only keeps villages *active* within `keepActiveRadius` (200) of a player. Per-tick and per-scan work therefore scales with **active** villages, which scale with players, not with world size.

| Work | Frequency | Cost per active village | 10 active | 50 active | 100 active |
|---|---|---|---|---|---|
| Village list (all known villages) | 200 ticks | one small allocation per known village | trivial | trivial | trivial |
| Snapshot + marker sweep | 200 ticks | ~80 buildings, ~30 records, 7 tag lookups, ~30 `getEntity` → ~50–150 µs | ~1 ms spike | ~5 ms spike | ~10 ms spike |
| Threat scan (`getEntitiesOfClass` over bounds+16) | 20 ticks | ~10×10 chunks × 2–3 sections → ~20–60 µs | <1 ms spike | ~2–3 ms spike | ~5 ms spike |
| Decorator `canStart` (3 goals) | every tick per villager | ~5–10 map lookups | ~300 villagers → <0.1 ms/tick | ~0.3 ms/tick | ~0.6 ms/tick |
| Incident ledger | per damage event | `instanceof` checks; relation lookups only for relevant pairs | negligible | negligible | negligible |
| SavedData | autosave | ~1 KB per village | 10 KB | 50 KB | 100 KB |

**Verdict:** nothing is architecturally blocking. At roughly 50+ simultaneously active villages, the once-per-interval **spikes** start to matter, so M2 should:
- **Stagger** per-village work: village hash modulo interval, so each tick handles about 1/N of the villages.
- Add cheap `System.nanoTime` counters exposed in `/hywmill status`.

100+ known but inactive villages cost almost nothing.

---

## 7. Recommended M2: "Village Defense Doctrine"

The candidate idea is kept, but **narrowed and re-based**: M2 is doctrine *driving the M1 combat seams*, on top of a correct, data-driven classification. It includes no new unit types, no HYW spawning and no Millénaire content packs.

### 7.1 Purpose

Today every village defends identically:
- every idle defender within 64 blocks rushes any threat;
- every civilian hides village-wide;
- militia and soldiers are indistinguishable;
- the ledger's tier and fortification are informational (and, per P2/P3, wrong).

M2 makes each village's **military identity** (roles, capacity, fortification, culture) produce a **doctrine**. The doctrine deterministically changes *how* the village responds: who fights, how many, how far, who shelters, and whom it protects. M2 also introduces the **assignment** concept (which defender is committed to which threat), which is the foundation M3's village-owned HYW units will plug into.

### 7.2 Features included

1. **Military classification (data-driven).**
   - **Building roles:** WALL, TOWER, GATE, BORDER_MARKER, GUARDHOUSE, WATCHTOWER, BARRACKS, ARMOURY, TRAINING, FORT_TOWNHALL. Derived from `WallType` data plus an explicit datapack table, extending M1.1-b.
   - **Villager roles:** SOLDIER, MILITIA, LEADER, CIVILIAN, OUTLAW. Derived from `VillagerType` tags plus an explicit per-type table, with a documented fallback rule.
2. **Military profile** (computed every 200 ticks):
   - soldiers, militia, leaders
   - capacity (defender slots from `maleResidentsAt` of operational buildings, times role)
   - readiness (alive defenders / capacity)
   - equipment score (mean `VillagerRecord.getMilitaryStrength()` of defenders)
   - fortification (revised), tier (revised)
3. **Doctrine.** Resolved as `culture defaults ⊕ village-type defaults ⊕ tier modifiers ⊕ per-village override`. Parameters:
   - `defenseRadius` (blocks from village center; replaces "bounds+16" for threat relevance)
   - `proactive` (defenders engage HYW_ENEMY units before being attacked: yes/no)
   - `commitPerThreat` (max defenders assigned to one threat) and `reserve` (defenders that hold `defendingPos` and don't chase)
   - `militiaPolicy`: NEVER, WHEN_ATTACKED, or ON_ENGAGED (join once the village is ENGAGED)
   - `shelterRadius` (civilians shelter only if a threat is within N blocks of *them*, or village-wide)
   - `assistPlayers`: OFF, REPUTATION ≥ X, or ALWAYS; plus the existing provoking-player rule
4. **Village alert state machine** (runtime):
   - `CALM → ALERT` (a threat within `defenseRadius`)
   - `ALERT → ENGAGED` (a resident or defender damaged)
   - `ENGAGED → RECOVERY` (no threats for N ticks)
   - `RECOVERY → CALM`
   - Transitions are logged, and per-village stats count alerts, engagements, HYW kills and resident deaths to HYW units.
5. **Defense coordinator.** Each scan assigns defenders to threats, respecting commit, reserve, militia policy and distance. The decorators consult assignments instead of "nearest threat within 64". It is deterministic: stable ordering by distance, then UUID.
6. **Player policy commands:**
   - `/hywmill village military` (profile, doctrine, alert, assignments)
   - `/hywmill doctrine get|set <param> <value>|reset`, allowed for ops and for the owner of a player-controlled Millénaire village (Q4)
7. **Stats and reporting:** per-village counters (item 4) and per-subsystem timing counters in `/hywmill status`.
8. **Structural prep:** a per-server `HywMillRuntime` context (D1) and staggered per-village scheduling (§6).

### 7.3 Excluded from M2

- Recruiting or spawning HYW units; village-owned rosters.
- Changing Millénaire villager types, equipment, inventories, stats or content packs.
- New patrol routes.
- Raid integration, warfare, sieges, territory, supply or economy.
- GUI; virtual armies.
- Writing to Millénaire's history (Q5).

### 7.4 Proposed package and class structure (new or changed)

```
dev.hywmill
  core/        HywMillRuntime (new: owns all per-server state), Scheduler (new: staggered buckets),
               PerfCounters (new), CoreEvents (use runtime), HywMillCommands (+ military/doctrine)
  settlement/  SettlementSource (+ plan residents, wall roles, villager tags), SettlementSnapshot (+ roles)
  military/
    classify/  BuildingRole, VillagerRole, RoleTable (datapack-loaded), RoleClassifier
    profile/   MilitaryProfile, ProfileCalculator (capacity, readiness, equipment)
    doctrine/  Doctrine (record), DoctrineDefaults (datapack), DoctrineResolver, DoctrineOverride
    defense/   AlertState, VillageDefenseState, DefenseCoordinator (assignments), DefenseStats
    ThreatTracker (radius-aware; per-village state moves into VillageDefenseState)
    IncidentLedger, EscalationGuard (M1.1 reconciliation)
  fortification/ FortificationScore (role-based)
  integration/millenaire/
               MillenaireSettlementSource (+ WallType/BuildingPlanSet/VillagerType role facts)
               EngageTargetDecorator, HuntMonsterDecorator, HideDecorator (consult coordinator and doctrine)
  data/hywmill/military/building_roles/*.json, villager_roles/*.json, doctrine/*.json  (datapack defaults)
```

The layering stays the same: doctrine, profile and coordinator are pure core and never import foreign classes. The Millénaire adapter only supplies facts; the decorators only ask "am I assigned?" and "is my role allowed to act now?".

### 7.5 Data model

Persistent (our SavedData, ledger **format 2**):

```
VillageRecord (existing fields) +
  roles:        { soldiers, militia, leaders, civilians, outlaws }      (derived, cached)
  capacity:     int      readiness: float      equipmentScore: float     (derived)
  buildingRoles:{ WALL:n, TOWER:n, GATE:n, BORDER_MARKER:n, GUARDHOUSE:n, ... } (derived)
  doctrineOverride: { param -> value }   (ONLY user-set values; absent = use defaults)
  doctrineSource:   "culture:norman+type:militaire+tier:GARRISON"         (diagnostic)
  stats: { alerts, engagements, hywKills, residentLosses, lastAlertTick, lastEngagedTick }
```

Datapack defaults (reloadable with `/reload`):
- `building_roles/<culture>.json`: `{ "millenaire:norman/guardhouse": "GUARDHOUSE", … }` (wall roles are auto-derived)
- `villager_roles/<culture>.json`: `{ "millenaire:norman/guard": "SOLDIER", "millenaire:norman/knight": "LEADER", … }` (fallback: `hostile` → OUTLAW, `helpInAttacks` → MILITIA, otherwise CIVILIAN)
- `doctrine/<culture>.json` and optional `doctrine/<villagetype>.json`: parameter defaults, plus tier modifiers

Runtime only (not persisted): the alert state (recomputed from threats on load; stats persist), assignments, and per-village threat lists.

Migration: ledger format 1 → 2 keeps ids and faction UUIDs and recomputes derived fields on the first update.

### 7.6 Millénaire APIs (read-only in M2)

`ModCultures.getAllWallTypes`, `getBuildingPlanSet`, `getVillagerType`; `BuildingPlanSet.isWallSegment`, `isBorderPost`, `isBorderBuilding`, `isTownHall`, `maleResidentsAt`, `femaleResidentsAt`; `BuildingInstance.getVariant`, `getLevel`, `getStatus`, `getPlanSetId`; `VillagerRecord.getMilitaryStrength`; `Village.getCultureId`, `getVillageTypeId`, `getCenter`, `isPlayerControlled`, `getOwnerUUID`, `getCombinedReputation`; `RaidManager.resolveDefendingPos` (reserve hold point); plus the M1 set. **No Millénaire writes** beyond M1's existing `setAttackTarget` and navigation calls.

### 7.7 HYW APIs

Unchanged from M1, plus `isNeutralUncrewedSiegeWeapon` (M1.1-d) and `RelationSystem.getAllRelations` (M1.1-a). **No new HYW writes.**

### 7.8 Event flow

- **Server start** → `HywMillRuntime` created → datapack role and doctrine tables loaded (AddReloadListener) → Millénaire goal bridge (as M1) → ledger loaded (migration if needed).
- **Every tick** → the scheduler runs the bucket for `tick % N`:
  - villages hashed into that bucket get a **threat scan** (20-tick cadence per village);
  - villages whose 200-tick slot is due get a **profile/doctrine refresh**.
- **Threat scan** (per active village) → threats within `defenseRadius` → alert-state transition → `DefenseCoordinator.assign`, which writes the assignment map `villagerUUID → threat`.
- **Per tick, per villager** (inside Millénaire's scheduler): the decorators read the assignment map and doctrine flags. Engage and hunt act only for assigned defenders or, per militia policy, attacked militia. Hide checks `shelterRadius` against the civilian's own position.
- **Damage event** → incident ledger → alert ENGAGED → escalation guard (event) → stats.
- **200 ticks** → escalation reconciliation (M1.1-a) → profile refresh → dirty the ledger only if something changed.
- **`/reload`** → role and doctrine tables reloaded → profiles and doctrines recomputed on the next refresh.

### 7.9 Performance model

| Cadence | Work |
|---|---|
| Every tick | ~1/20 of active villages scanned; ~1/200 refreshed; decorators do map lookups only |
| 20 ticks per village | Entity query plus assignment: O(defenders × threats), typically ≤ 20×3 |
| 200 ticks per village | Snapshot, roles, capacity, doctrine resolution (cached until inputs change), reconciliation |
| On `/reload` | Table parse |
| Counters | Scan µs, refresh µs, assignments, decorator calls; shown in `/hywmill status` |

### 7.10 Commands and testing

- **New commands:** `/hywmill village military`, `/hywmill doctrine get|set|reset`, `/hywmill alerts`.
- **Extended commands:** `status` (perf counters), `village residents` (role and assignment columns).
- **Harness:** the headless dedicated-server harness is committed (`devtools/server-harness/`). It adds a small test-spawn helper using the existing `/millenaire spawn at … <type> 100` and `/summon`.
- **Unit tests (JUnit):** role classification, capacity, fortification, tier, doctrine resolution and the coordinator's assignment function. All are pure.

### 7.11 Acceptance tests (deterministic PASS/FAIL)

| # | Test | PASS condition |
|---|---|---|
| M2-1 | Classification | Douvres la-forge: `BORDER_MARKER=13`, `WALL=0`, `TOWER=0`, `GUARDHOUSE=0`, tier per the revised formula (expected WATCH). A spawned Norman `militaire` (fort) village shows FORT_TOWNHALL and GUARDHOUSE counts equal to its operational plan sets, and `guard` residents classified as SOLDIER. |
| M2-2 | Capacity | For a test village, `capacity` equals the hand-computed sum of SOLDIER/MILITIA slots from `maleResidentsAt(variant, level)` of its operational buildings (the expected value is printed from the plan JSON by the harness). |
| M2-3 | Doctrine resolution | `/hywmill doctrine get` returns exactly the datapack defaults for Norman+militaire+tier. After `set commitPerThreat 1` and a restart, the override persists and `doctrineSource` shows the override. |
| M2-4 | Defense radius | A null-owner bandit placed at `defenseRadius + 10` from the center → alert stays CALM and no assignments. Moved to `defenseRadius − 10` → ALERT within 40 ticks. |
| M2-5 | Commitment and reserve | 1 bandit, `commitPerThreat=2`, `reserve=1`, ≥4 defenders present: exactly 2 assigned engage; the reserve holds within 5 blocks of `defendingPos`; the rest keep their Millénaire tasks. Verified by `village residents` columns and the incident ledger (only the assigned UUIDs appear as attackers). |
| M2-6 | Militia policy | `militiaPolicy=WHEN_ATTACKED`: militia never appear as attackers unless they were the victim first (ledger order). `ON_ENGAGED`: militia join after the first resident is damaged. |
| M2-7 | Shelter radius | `shelterRadius=24`: civilians more than 24 blocks from every threat continue their task (goal ≠ hide); those within 24 hide. |
| M2-8 | Player assist | Client test, the D6 extension: `assistPlayers=REPUTATION≥0` → assigned defenders engage a player-owned HYW unit attacking the player; `OFF` → they don't. |
| M2-9 | Alert lifecycle and stats | Bandit killed → ENGAGED → RECOVERY → CALM within the configured timers. `hywKills` +1 and `engagements` +1, persisted across a restart. |
| M2-10 | Regression | M1 A–F plus the M1.1 tests (D5-b, G, H) all pass unchanged. |
| M2-11 | Performance | With ≥5 active villages and 10 HYW units, the `/hywmill status` mean scan time is below 200 µs per village and no single tick's hywmill work exceeds 2 ms (counter max). |
| M2-12 | Optional deps | The same four mod-presence modes as M1 still load. With HYW absent, doctrine still resolves and commands work; there are simply no threats. |

### 7.12 Future compatibility (M3 preparation)

- **Roles and capacity** tell M3 how many **village-owned HYW units** a village may field and of what kind. The doctrine gains a composition parameter, and the canonical HYW spawn path with `owner = faction UUID` is ready.
- **The defense coordinator's assignments** become a *roster*. M3 adds HYW units as assignable defenders next to Millénaire villagers, using HYW orders (`setHomePosition`, patrol points, `setAttackStrategy`) instead of decorators.
- **Alert state and stats** are the triggers M3/M4 need (reinforcements, counter-raids, war declarations).
- **`HywMillRuntime` plus the scheduler** give M3/M4 a home for rosters, raids and virtual-army ticking without new static state.

---

## 8. Deferred to M3+

| Milestone | Scope |
|---|---|
| M3 | Village-owned HYW garrison units: recruitment, roster, persistence reconciliation, supply decision, equipment level from doctrine. Weapon/equipment progression (armoury → villager inventory). Custom patrol routes. Military GUI (first client code). |
| M4 | Millénaire raid ↔ HYW auxiliaries; village-to-village war state; counter-raids; territory; economy upkeep. |
| M5+ | Sieges (after a block-damage and repair policy), virtual and off-screen armies, HYW squads and formations for village forces. |

---

## 9. D6 and single-player client checklist (≈10 minutes, your client)

1. Install NeoForge 21.1.226 with the three JARs. In `config/hywmill-common.toml` set `devCommands = true` and `verboseLogging = true`.
2. **Single-player:** create a world, find or spawn a Millénaire village (`/millenaire spawn at ~ ~ ~ "norman/militaire" 100`, on plains), and stand in it.
3. `/summon hundred_years_war:militia ~5 ~ ~ {OwnerUUID:[I;1,2,3,4]}`: a unit owned by someone else, neutral to you.
4. Hit it until it retaliates and chases you, staying inside the village near guards.
5. **PASS D6:** `/hywmill threats` lists it with `ATTACKING_ALLY_PLAYER`, and at least one defender engages it (`/hywmill incidents` shows a `millenaire:villager` → militia hit).
6. `/hywmill dev relation 00000001-0000-0002-0000-000300000004`: the village's relation to the unit's owner must be NEUTRAL.
7. Save and quit to title. Create or open a **second** world, then reopen the first. `/hywmill status` must show all three goals as bridged, and step 5 must work again (integrated-server lifecycle).

---

## 10. Decisions needed from you

| # | Question | My recommendation |
|---|---|---|
| Q1 | Dependency version policy. Keep exact pins (a mismatch is a hard launch error, proven) or use a bounded range such as `[0.7.1r-fix1,0.8)` with a runtime API self-check that disables the integration? | Keep pins until M3; revisit when HYW or Millénaire next update. |
| Q2 | Final mod id and name, before anyone uses it in a real world. | Rename now or never. The faction namespace and SavedData name will stay fixed either way. |
| Q3 | Escalation guard policy. M1.1 keeps "always revert HYW's automatic HOSTILE for village factions". Later intentional diplomacy (M4) will need an allow-list of *our* declared hostilities. | Confirm "always revert" for now. |
| Q4 | Who may change a village's doctrine? | Ops, plus the owner of a player-controlled Millénaire village. Not reputation-based yet. |
| Q5 | May M2 write short entries to Millénaire's village history (`Village.recordEvent`) for "attack repelled" events? It's good player feedback, but it writes into Millénaire's save. | Not in M2; keep feedback in our own stats and commands. |
| Q6 | Should border markers contribute to fortification? | 0 points, reported separately. |
| Q7 | Doctrine defaults per culture: do you want to provide flavor (for example Seljuk and Byzantine "professional army", Inuit "militia", Norman "castle garrison"), or should I propose values? | I propose the values; you adjust. |
| Q8 (M3 preview) | Player-controlled Millénaire villages: should their identity become the owner's player UUID, so villagers become allies of that player's HYW army? This changes HYW relations semantics. | Decide before M3. |
