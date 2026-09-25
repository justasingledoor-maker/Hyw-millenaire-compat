# Milestone 1 report: garrison ledger and combat bridge

**Result:** A persistent Millénaire village can be represented as an HYW faction. A hostile HYW unit and Millénaire guards fought each other through both mods' own combat code, and nothing broke in either mod. Every automated acceptance check passed on a real NeoForge 21.1.226 dedicated server running both production JARs. One check (D6, guards defending a real player) needs a real client and is still untested. See §8.

Build: `./gradlew build` → `build/libs/hywmill-0.1.0-m1.jar`. It contains only `dev/hywmill/**`; no Millénaire or HYW class is bundled.

Placeholders: the mod id is `hywmill` and the command is `/hywmill`. `/ourmod` is registered as an alias because the acceptance tests use it; rename both together later.

---

## 1. Files and classes

| Package | Class | Purpose |
|---|---|---|
| `dev.hywmill` | `HywMill` | `@Mod` entry. Registers config and core listeners, then loads integrations by reflection. |
| `config` | `HywMillConfig` | Common config: logging, dev commands, ledger interval, bridge toggles, escalation guard, player-assist policy. |
| `core` | `Integration`, `Integrations` | Optional-integration loader. Checks `ModList.isLoaded`, then `Class.forName`, and catches `LinkageError`. |
| `core` | `Services` | Service registry (`SettlementSource`, `CombatFactionService`) plus live diagnostics. |
| `core` | `CoreEvents` | Server tick (ledger every 200 ticks, threat scan every 20), `EntityJoinLevelEvent` (identity marking), `LivingDamageEvent.Post` (incident ledger and escalation guard), commands. Every body is guarded; a `LinkageError` disables the integrations instead of crashing the server. |
| `core` | `HywMillCommands` | `/hywmill status \| village info\|list\|residents \| threats \| incidents [n] \| dev playerhit\|relation\|inspect` |
| `core` | `HmLog` | Logging with DEBUG/INFO switching and per-key throttling. |
| `settlement` | `SettlementSource`, `SettlementSnapshot`, `ResidentInfo` | Mod-agnostic settlement view. |
| `settlement` | `GarrisonLedger` (SavedData), `VillageRecord`, `GarrisonUpdater` | Our persistent data (`world/data/hywmill_garrison_ledger.dat`) and the 200-tick update with diff logging. |
| `faction` | `FactionIds` | Deterministic faction UUID: `UUID.nameUUIDFromBytes("hywmill:millenaire_village:" + villageUuid)`. |
| `faction` | `CombatFactionService` | Mod-agnostic view of the HYW relation system. |
| `faction` | `FactionMarker` | Marks residents on join, plus a verification sweep every ledger update. |
| `military` | `MilitaryTier` | Tier formula (below). |
| `military` | `ThreatTracker` | Per-village cached threat snapshot. Every query is O(1) or O(threats). |
| `military` | `IncidentLedger` | Runtime-only damage ledger: first strike, and recent attacks on a village. |
| `military` | `EscalationGuard` | Detects HYW's permanent HOSTILE escalation involving a village faction and resets it (configurable). |
| `fortification` | `FortificationScore` | Fortification formula (below). |
| `integration.millenaire` | `MillenaireIntegration`, `MillenaireSettlementSource`, `MillTypes` | The **only** code importing `org.millenaire.*`. |
| `integration.millenaire` | `MillenaireGoalBridge`, `BridgeDecorator`, `EngageTargetDecorator`, `HuntMonsterDecorator`, `HideDecorator`, `BridgeEngageTask`, `BridgeHideTask` | Goal replacement through `GoalRegistry.replace`. |
| `integration.hyw` | `HywIntegration`, `HywCombatFactionService` | The **only** code importing `ydmsama.*`. |
| `integration.generic`, `client` | `package-info` only | Intentionally empty in M1. |

Build files: `settings.gradle`, `build.gradle` (ModDevGradle 2.0.147, NeoForge 21.1.226, `compileOnly files(libs/…)`, `copyDevMods` for dev runs), `gradle.properties`, `src/main/templates/META-INF/neoforge.mods.toml` (both mods `type="optional"`, versions pinned to `[9.0.2,9.0.3)` and `[0.7.1r-fix1-1.21.1-neoforge]`), `.gitignore` (`libs/*.jar` is never committed).

**Isolation was verified in the bytecode.** A `javap -v` scan of the compiled classes found no reference to `org/millenaire` or `ydmsama` outside the matching `integration.*` package. The Millénaire and HYW adapters don't reference each other either.

**No mixins.** None were needed; everything uses public methods, NeoForge events and `GoalRegistry.replace`.

## 2. Exact foreign APIs used (all `public`, checked with `javap` against the supplied JARs)

**Millénaire 9.0.2**

| Class | Methods and fields used |
|---|---|
| `VillageSavedData` | `get(ServerLevel)`, `getVillageManager()` |
| `VillageManager` | `getAllVillages()`, `getVillage(VillageId)`, `findNearestVillage(BlockPos,double)` |
| `Village` | `getId`, `getVillageName`, `getCultureId`, `getVillageTypeId`, `getCenter`, `isActive`, `computeBounds`, `getVillagerRecords`, `getVillagerRecord`, `getVillageDefendingStrength`, `getBuildings`, `getOperationalBuildingsWithTag`, `getTownhall`, `getCombinedReputation` |
| `VillageId` | record constructor, `uuid()` |
| `VillagerRecord` | `isKilled`, `isRaidingVillage`, `getVillagerTypeId`, `getOriginalVillageId` |
| `BuildingInstance` | `isOperational`, `isWallSegment`, `getPlanSetId`, `getFirstPointPos("shelterPos")`, `getOrigin` |
| `ModCultures` / `VillagerType` | `ModCultures.getVillagerType`; `VillagerType.isHelpInAttacks`, `isRaider`, `isArcher`, `isChild` |
| `MillVillager` | `getVillageId`, `getVillagerTypeId`, `isRaiderEntity`, `getAttackTarget`, `setAttackTarget`, `performAttack`, `ensureCombatWeaponEquipped`, `getCombatWeapon`, `getNavManager`, `initGoals`, `getGoalScheduler` |
| `VillagerNavDriver` | `navigateTo`, `navigateToCombatTarget`, `isDestinationCombatTarget`, `getDestination`, `stop` |
| `Millenaire` / `GoalRegistry` | `Millenaire.getGoalRegistry()`; `GoalRegistry.get`, `replace` |
| Goal interfaces | `VillagerGoal`, `VillagerTask`, `GoalContext`, `StopReason`, `TravelPhase`, `GoalScheduler.getCurrentGoal` |
| Goal IDs | `EngageTargetGoal.ID`, `HuntMonsterGoal.ID`, `HideGoal.ID` |

**HYW 0.7.1r-fix1**

| Class | Methods used |
|---|---|
| `RelationOwnerMarkedEntity` (mixed into `Entity`) | `hyw$markRelationOwnerUUID`, `hyw$getMarkedRelationOwnerUUID`, `hyw$hasRelationIdentityMarker`, `hyw$clearRelationIdentityMarker` |
| `ServerRelationHelper` | `getRelationUUID(Entity)`, `isEnemyRelationByUUID(UUID,UUID)` |
| `RelationSystem` | `getRelation(UUID,UUID)`; `setRelation(…, NEUTRAL)`, used **only** by the escalation guard |
| `BaseCombatEntity` | `getOwnerUUID()`, `getHywTarget()` |
| `TemporaryHostileTargetManager` | `isHostile(BaseCombatEntity, LivingEntity)`, read only |
| `NonCombatUnit` | marker interface, used to exclude HYW worker units |

## 3. Formulas (documented in code)

**Garrison** is the number of living residents, excluding foreign raid clones, whose villager type has Millénaire's `helpInAttacks` tag.

> Note: in Millénaire, armed tradesmen such as lumberjacks, farmers, miners and carpenters carry `helpInAttacks`, so "garrison" means Millénaire's own defenders, not only guard-type villagers.

**Fortification** (`FortificationScore`) counts **operational (built) buildings only**:

| Component | Points |
|---|---|
| Each wall segment | 1 |
| Each wall tower (a wall segment that is also patrol-tagged), on top of its segment point | +2 |
| Each non-wall patrol-tagged building (guardhouse, watchtower, fort tower…) | 3 |
| The town hall's plan is a fort | +5 |

Planned but unbuilt wall segments are reported separately in `/hywmill village info` and are not scored.

**Tier** (`MilitaryTier`) is evaluated top-down; the first match wins:

| Tier | Condition |
|---|---|
| STRONGHOLD | GARRISON conditions and fortification ≥ 20 |
| GARRISON | garrison ≥ 4 and (armoury or training building, or a barrack/armoury plan) |
| GUARD_POST | garrison ≥ 2 and ≥ 1 defensive building |
| WATCH | garrison ≥ 1 |
| NONE | otherwise |

Also recorded: counts per tag (`patrol`, `armoury`, `training`, `wall_level_0/1/2`), military plan names (keyword match on the plan-set id), the town hall plan, operational building count, and loaded vs marked residents.

## 4. Bridge behavior (as built)

**1. Identity.** Every loaded resident is marked with its village's faction UUID through HYW's `hyw$markRelationOwnerUUID`. The villager's real UUID is untouched. Marking happens on `EntityJoinLevelEvent` and is re-verified by a sweep every ledger update.

**Raid clones are deliberately left unmarked.** Millénaire registers a clone to the *target* village. Marking it with the target's faction would make HYW treat raider and defender as the same side and cancel their damage.

**2. Threats**, rebuilt every 20 ticks per active village. An HYW combat unit within the building bounds (+16 blocks) is a threat if **any** of these hold:

| Reason | Condition |
|---|---|
| HYW_ENEMY | HYW's own `isEnemyRelationByUUID(owner, faction)` says so |
| ATTACKING_RESIDENT | its current target is a resident |
| RECENT_ATTACKER | the ledger shows it hit a resident within 600 ticks |
| ATTACKING_ALLY_PLAYER | it targets a player inside the village whose Millénaire reputation is ≥ 0, unless the ledger shows the player struck first (`assistProvokingPlayer=false`) |

Classification only reads HYW state; it never writes HYW relations.

**3. `millenaire:engage_target` decorator.** It delegates to the original goal and adds one case: a defender (`helpInAttacks`, not a raid clone) whose `attackTarget` is a threatening HYW unit. Civilians never engage HYW units.

**4. `millenaire:hunt_monster` decorator.** It adds "idle defender picks the nearest tracked threat within 64 blocks". This is needed because Millénaire's hunt only sees `Monster`, and every HYW unit, bandits included, is a plain `PathfinderMob`. Without it, guards only react after someone has been hit.

**5. `millenaire:hide` decorator.** It adds "civilian (the exact types Millénaire gives `hide`) while the village has a threat". Civilians walk to the town hall's `shelterPos` (same point Millénaire's own `HideGoal` uses), fall back to the town hall origin, then the village center. The original raid-driven hide is untouched.

**6. Fighting** uses only public `MillVillager` methods (`performAttack`, `ensureCombatWeaponEquipped`, navigation), mirroring Millénaire's package-private `CombatGoalSupport`. Damage, weapons and cooldowns stay entirely Millénaire's. If Millénaire clears the target (for example the `defensive` leash, or Peaceful difficulty), the task ends.

**7. Escalation guard.** If HYW's damage mixin escalates a village faction and another party to permanent HOSTILE (3 hits between NEUTRAL identities), we reset that pair to NEUTRAL and log a WARN. HYW's own 600-tick retaliation is unaffected. Config: `preventPermanentEscalation`.

**8. Goal-replacement timing, verified.** Millénaire freezes its built-in goals in its mod constructor. Its `ServerStartedEvent` listener (NORMAL priority) then calls `resetToBuiltins()` and re-initializes loaded villagers. We replace at `ServerStartedEvent` LOW, then call `initGoals` on already-loaded villagers.

Villagers loaded later get the decorators automatically. `MillVillager.initGoals` resolves goals by id, both in Millénaire's `EntityJoinLevelEvent` handler and in `VillagerSpawnFactory`.

Runtime proof:
* The log shows "Goal replacement initialized …" after Millénaire's content load, and "decorator is live" once villagers ticked.
* `/hywmill village residents` shows `goal=millenaire:engage_target(bridged)`.
* `/hywmill status` counters separate original starts from bridge starts.

## 5. Build result

```
./gradlew build   ->  BUILD SUCCESSFUL
build/libs/hywmill-0.1.0-m1.jar  (≈93 KB, only dev/hywmill/** + META-INF)
```

## 6. Runtime test results

**Environment:**
* A real **NeoForge 21.1.226 dedicated server**, installed with the official installer, with the three production JARs in `mods/`.
* Seed `20260925`, driven headlessly through a console pipe.
* Villages were kept active with `/forceload` and `/millenaire chunkload`, because a headless server has no player to activate them.
* Player actions were simulated with a NeoForge **FakePlayer** through `/hywmill dev playerhit` (config-gated). A FakePlayer is a real `ServerPlayer`, so it exercises both mods' player code paths, but it is **not physically in the world**.

Evidence: [`m1-test-evidence/`](m1-test-evidence/) holds curated log excerpts.

**Test villages:**
* **Douvres la-forge**, `norman/artisans`, **naturally generated** by Millénaire's world-gen queue, 83 planned buildings. Residents: 5 defenders (guildmaster, lumberman, farmer, carpenter, miner) and 4 civilians (wives). A girl was born during testing.
* **Marolles les-pâtures**, `norman/agricole`, spawned fully built with `/millenaire spawn … 100`. Used as the raid target.

### TEST A — persistence: PASS

* The first update logged the discovery, the record creation and "Village record initialized: tier=GUARD_POST garrison=5 population=9 defending=90 fortification=12 patrol=4 …".
* `/ourmod village info` showed name, VillageId, faction UUID `1aa3e6cf-84d4-3b7b-8e61-f06352a032d8`, tier, garrison, defending strength, fortification, buildings, and resident marking 9/9.
* After a clean stop, `world/data/hywmill_garrison_ledger.dat` existed. On restart the log showed "Garrison ledger loaded: 1 village record(s) … faction-id mismatches corrected: 0". `info` showed the **identical faction UUID**, the original first-seen tick (4600) and a continuing update counter.
* After a third restart, both village records loaded.

### TEST B — neutral HYW: PASS

A `hundred_years_war:militia` with `OwnerUUID` set to a synthetic player UUID stayed 60 s in the village. It had target none, generated no damage incidents and no threat classification, and villagers ignored it. Later a vanilla zombie hit it, and it fought the zombie under HYW's own AI.

### TEST C — bandit aggression: PASS

A `hundred_years_war:bandit_soldier` (null owner) was summoned at the village center:

1. **Same tick:** "Threat detected … bandit_soldier[owner=null][HYW_ENEMY]".
2. **Guards detect and engage:** the guildmaster and carpenter "select HYW target … via hunt_monster", and the engage decorator takes over.
3. **Civilians hide:** two wives "enter hide behavior … sheltering at 620, 82, 610".
4. **The bandit attacks marked villagers:** "HYW entity attacks Millénaire villager … inherentlyHostile=true", 3 hits. This confirms HYW's relation rules accept the marker.
5. **Guards fight back:** 9 hits landed.
6. **Resolution:** the bandit died about 7 s later, "Threat cleared", and the civilians left hide.

**No civilian ever appears as an attacker in the incident ledger** (0 of 4 checked).

A second run after resurrection: all 5 civilians hid, including the newborn girl, and the resurrected guard engaged.

### TEST D — player-provoked HYW combat: PASS for steps 1–5; step 6 NOT TESTED

| Step | Result |
|---|---|
| 1–2 | The owned militia was neutral (see TEST B). |
| 3–4 | Three FakePlayer hits. After each, `TemporaryHostileTargetManager.isHostile(unit, player)` was `true`, confirming HYW retaliation. HYW also printed its own escalation between the **unit's owner** and the player; that is HYW's own business and doesn't involve a village, so it was left alone. |
| 5 | `/hywmill dev relation` showed village faction ↔ player **NEUTRAL/NEUTRAL** and village ↔ unit owner **NEUTRAL/NEUTRAL**. The village gained no diplomatic HOSTILE state. |
| 6 | Guards assisting a real player whom an HYW unit attacks: **NOT TESTED**. The FakePlayer isn't in the world, so HYW units can't target it. The code path exists (`ATTACKING_ALLY_PLAYER`) and needs a client test. |

**Extra: escalation guard.** The FakePlayer hit a marked villager 4×. HYW printed that it had escalated faction ↔ player to HOSTILE. Our guard logged the WARN and reset it, HYW logged "Relation changed from hostile to neutral … (immunity started)", and the 4th hit was skipped by HYW's immunity window. The final relation was NEUTRAL both ways.

Millénaire's own reaction to player aggression is unchanged: after the grace hits, it calls for help and the villagers engage the player through the **original** `engage_target` task.

### TEST E — resurrection: PASS

1. `/kill` on the guildmaster: Millénaire logged the death.
2. At the next dusk, Millénaire logged "Villager respawned: … old UUID=cfc9d345, new UUID=b14cf652".
3. Our log showed "Villager faction identity assigned: b14cf652 (millenaire:norman/guildmaster) -> faction 1aa3e6cf…", emitted from inside the spawn call, before Millénaire's respawn log line.
4. The resurrected guard then killed a zombie (original goals) and engaged a bandit (bridged goals).

The newborn child (`norman/girl`) was also marked on join.

### TEST F — Millénaire regression: PASS

* **Vanilla monsters:** a zombie was killed by the guard. `/hywmill status` showed `engage_target {original starts=8, bridge starts=0}` and `hunt_monster {original starts=2}` before any HYW threat, so the originals ran through the decorators.
* **Raids:** `/millenaire dev raid trigger` (Douvres → Marolles). The clone materialized and was **left unmarked**. The raider damaged a defender, so HYW did not cancel raider damage. Defenders damaged the raider, and Millénaire resolved it: "Raid FAILURE: Douvres la-forge repulsed".
* **Civilians:** with no HYW threat, civilians ran normal jobs (`craft_*`, `gather_goods`, `deliver_resources_shop`). The hide bridge showed 0 starts until an HYW threat appeared.
* **Player aggression:** Millénaire's native response is intact (see TEST D).

### Optional-dependency modes: PASS

| Mods present | Result |
|---|---|
| hywmill only | Loads. Both integrations reported "absent (integration disabled)". |
| hywmill + HYW | Loads. HYW integration enabled; no settlement source. |
| hywmill + Millénaire | Loads. The goal bridge installs; HYW integration disabled. The decorators simply delegate because no threats exist. |
| All three | Loads (the full test above). |

**Errors in any log:** 0 from `dev.hywmill` and 0 exceptions. The only `ERROR` lines were 15 vanilla `PoiSection: POI data mismatch`, all logged in the same second that Millénaire's `spawn … 100` command placed Marolles's buildings.

## 7. Known failures, discrepancies and unknowns

1. **Unowned HYW units are always hostile, whatever their type** (an HYW rule, observed rather than invented by us). HYW's `isEnemyRelation` says "exactly one null owner ⇒ enemy". A `/summon`ed militia without an owner is classified `HYW_ENEMY` exactly like a bandit, and would attack marked villagers.
   * In HYW, "ordinary / neutral" means *owned*: units from scrolls or recruitment, with `OwnerUUID`.
   * Bandits are not a faction; they are simply unowned units, typically from HYW spawn-point blocks or commands.
   * If you want unowned non-bandit types to be neutral toward villages, that needs a policy that **overrides** HYW's rule, for example an entity-type allow-list. **Decision needed.**
2. **Garrison includes armed tradesmen.** That is Millénaire's own `helpInAttacks` semantics. Only a village with guard-type villagers would show guards specifically.
3. **Test D6 is untested.** Guards assisting a real player against an HYW attacker needs a real client.
4. **Timing assumption.** The goal bridge relies on Millénaire keeping its `ServerStartedEvent` listener at NORMAL priority and never calling `resetToBuiltins()` again. Verified for 9.0.2 only. Any Millénaire update must re-run TEST F plus `/hywmill status`.
5. **HYW relation UI side effect.** When HYW itself escalates a village faction (before our reset), `RelationSystem.setRelation` creates a permanent "Unknown" `PlayerRelationData` entry for the faction UUID in `relations.dat`. It is harmless, and cosmetic in HYW's relation screen.
6. **Transient type-less villager.** On village activation Millénaire briefly creates a `MillVillager` with a village ID but no villager type, and removes it almost immediately. We mark it too; this is harmless and logged as type `?`.
7. **Defender reach.** The hunt decorator looks 64 blocks from each defender. Tradesmen working far away (70+ blocks) did not join; this matches Millénaire's own hunt-zone behavior.
8. **Not covered here:** performance at scale (many villages and squads), multiplayer with real clients, Epic Fight or Better Combat present, and HYW structure spawn points near villages.

## 8. M1 acceptance checklist

| # | Check | Result |
|---|---|---|
| A1–3 | Village data populated; `/ourmod village info` shows all required fields | **PASS** |
| A4–6 | SavedData persists across restart; faction UUID identical | **PASS** |
| B | Owned HYW unit does not attack villagers merely by proximity | **PASS** |
| C1–2 | Bandit recognizes marked villagers as enemies and attacks | **PASS** |
| C3 | Guards detect the HYW attacker and fight back | **PASS** |
| C4 | Civilians do not become combatants (and hide) | **PASS** |
| D1–4 | Owned HYW unit neutral; retaliates after player hits (HYW temp hostility) | **PASS** (simulated player) |
| D5 | No permanent village HOSTILE from the player hitting an HYW unit | **PASS** |
| D5+ | HYW's own permanent escalation on village factions is detected and reverted | **PASS** |
| D6 | Guards assist a real player attacked by an HYW unit | **NOT TESTED** (needs client) |
| E | Resurrected guard receives the village faction identity | **PASS** |
| F1 | Guards still fight vanilla monsters | **PASS** |
| F2 | Millénaire raid and defense still work | **PASS** |
| F3 | Civilians keep normal behavior when there is no threat | **PASS** |
| — | Mod loads with either or both dependencies absent | **PASS** |
| — | No mixins; no dependency bundled or modified | **PASS** |

## 9. Reproducing the tests

1. Put `millenaire-9.0.2.jar` and `HundredYearsWar-0.7.1r-fix1-1.21.1-neoforge.jar` in `libs/` (see README), then run `./gradlew build`.
2. Install a NeoForge 21.1.226 server and copy the three JARs into `mods/`.
3. In `config/hywmill-common.toml`, set `devCommands = true` and `verboseLogging = true`.
4. Keep a test village loaded (stand in it, or run `/millenaire chunkload`).
5. Useful commands:
   * `/summon hundred_years_war:bandit_soldier ~ ~ ~`
   * `/hywmill threats`, `/hywmill village residents`, `/hywmill incidents 20`, `/hywmill status`
   * `/hywmill dev playerhit <entity>`, `/hywmill dev relation <uuid>`
   * `/millenaire dev raid trigger <attackerPos> <targetPos>`
