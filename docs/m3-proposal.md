# HywMill M3 — Village-Owned HYW Garrison: Proposal

Status: **proposal only. M3 is not implemented and not authorized.** Nothing in this document is
built; M2 (`573baca`) remains the current code.

Pins audited: Millénaire 9.0.2, Hundred Years War (HYW) 0.7.1r-fix1, NeoForge 1.21.1.
Every HYW or Millénaire member named below was verified with `javap` against the pinned JARs,
unless it is marked **[spike]**. Items marked [spike] are runtime behaviour that bytecode alone
cannot settle; they are listed in §22 and scheduled first, in §23 step M3-0.

---

## 0. API findings and conflicts (read first)

These are the places where the M3 brief meets the real APIs. None of them blocks M3, but each
one shapes the design, and two of them (C2, C6) need a decision from you.

| # | Finding | Evidence | Consequence |
|---|---|---|---|
| C1 | `cavalry` and `cavalry_with_sword` are **not registered entity types**. They exist only as lang strings. `iron_mage` has an `equipment.json`, but its ID is **not** in the `HywEntityRegistry` string table. | `javap -constants HywEntityRegistry` string list; `assets/hundred_years_war/hyw/npc/*` | These IDs are excluded from every unit table. The table loader rejects unregistered IDs at load time (§6, §14). |
| C2 | HYW's order system (patrol, hold, follow, move) is keyed to the **owner player**. `BaseCombatEntity.getOwner()` only resolves the owner UUID through `PlayerList.getPlayer`. | Bytecode of `getOwner()` | Units are owned by `factionId` (not a player), so **no player can give them HYW orders through HYW's UI**, and that includes the controller. This follows directly from "don't repurpose the owner UUID". M3 gives the controller orders through `/hywmill` only (§12). Direct HYW-UI control would need a mixin, which is out of scope. |
| C3 | Units that require supply **starve**. `SupplyManager.tick` applies `starve` damage plus weakness/slowness to any `requiresSupply()` unit without a friendly source. The only exempt units are `SiegeUnit`. | `SupplyManager.isSupplyExempt`, `applyNoSupplyDamage` | Village units get `setRequiresSupply(false)`, the same call HYW's own `spawnQueuedUnit` makes with a boolean. The flag persists in NBT as `RequiresSupply`. Linking village supply is deferred to M4+. |
| C4 | Deleting a Millénaire village (negation wand → `VillageManager.removeVillage`) is a bare `Map.remove`. **No event is fired.** | Bytecode of `VillageManager.removeVillage` | A deleted village is detected by its absence from `SettlementSource.list` across a grace period (§8). |
| C5 | Equipment levels differ per unit. `militia`, `spear_man`, `shieldman`, `warrior`, `archer` and `crossbowman` have levels 0–3; `handgonne_man` has 0–1 and `matchlock_man` has 2–3. `setEquipment(int)` runs `normalizeTier` before applying. | `equipment.json` `levels` keys; `setEquipment` bytecode | The mapping from tier to level is data-driven. HYW clamps the value, and the roster stores the level that was actually applied. |
| C6 | **HYW already ships Epic Knights support.** When `ServerModConfig.enableEpicKnightsCompat` is on and `ModList.isLoaded("magistuarmory")` is true, `getCurrentEquipmentFileName()` returns `equipment_epic_knights.json`. Many units, including `spear_man`, `matchlock_man`, `priest` and `hyw_horse`, ship that file. | `ServerModConfig.getCurrentEquipmentFileName` | The M3 HYW equipment provider already gives Epic Knights gear when the server enables HYW's own option, with zero HywMill code. A separate HywMill Epic Knights provider (§16) is only worthwhile if you want gear that differs by culture. **Decision needed.** |
| C7 | HYW recruitment costs work on the player's inventory and XP. Every cost helper takes a `Player`. | `RecruitmentOrderManager.consumeBatchCosts(Player, …)`, `countItem(Player, …)` | HYW's cost system cannot be reused for villages. M3 uses its own data-driven levy pool (§4). |
| C8 | This audit verified **no** Millénaire API for taking goods out of a village's stores. | (none found or verified) | M3 does not consume Millénaire goods. The levy pool is abstract. Whether to consume goods is an open question (§22, Q3). |

Facts that shape the design (all verified):

* **Relation identity is the owner.** `ServerRelationHelper.resolveDirectRelationIdentity` maps a
  `BaseCombatSupport` to `RelationIdentity.owner(getOwnerUUID())`. A unit owned by `factionId`
  therefore has the **same relation identity as its village's marked residents**, so HYW sees them
  as the same side.
* **A non-player owner is safe.** `getOwner()` returns `null` when the UUID is not an online
  player. Only `BaseCombatEntity` itself calls it, in `isAlliedTo`, `awardKillScore` and the
  `OwnableEntity` bridge, and every one of those calls checks for null. `isAlliedTo` treats two
  `OwnableEntity`s with equal owner UUIDs as allies, so the units of one garrison are allied with
  each other.
* **Units persist.** `checkDespawn` only despawns a unit when `canNaturalDespawn` is true, or when
  the owner is null and both "null-owner friendly to enemy" configs are on. A unit with an owner
  and `CanNaturalDespawn=false` is never naturally despawned.
* **HYW saves everything we need in NBT:** `OwnerUUID`, `EquipmentLevel`, `RequiresSupply`,
  `CanNaturalDespawn`, `HomePosX/Y/Z`, `Level`, `ExperiencePoints`, `KillCount`, `CommandHold`,
  `PatrolPoints` and the offline-recovery field `UnloadWorldTicks`.
* **HYW's own spawn sequence** (`RecruitmentOrderManager.spawnQueuedUnit`) runs:
  `EntityType.create(level)` → safe-position search → `setOwnerUUID` → `setRequiresSupply(bool)` →
  `setEquipment(level)` → `setHomePosition(pos)` → `setAttackStrategy(DEFAULT)` → `setPos` →
  `ServerLevel.addFreshEntity`. M3 copies this sequence exactly.
* **Temporary retaliation** is available as `TemporaryHostileTargetManager.markHostile(BaseCombatEntity, LivingEntity)`
  and `isHostile(...)`. Both are public and static.

On the accepted M2 perf tail: I found **no simple, clearly worthwhile optimization**. The
`profile.refresh` tail comes from building the Millénaire snapshot, and fixing it means slicing
that snapshot across ticks, which is not a small change. M3 does not touch `profile.refresh`. Its
own slot is placed at a separate offset, so it never shares a tick with the refresh or the sweep
(§17).

---

## 1. Architecture

```
dev.hywmill
├── garrison/                         pure logic (no Millénaire or HYW imports; unit-tested)
│   ├── GarrisonRoster                roster of one village + village-level garrison counters
│   ├── RosterEntry                   one soldier slot (identity, state, generation, bound entity)
│   ├── UnitState                     RECRUITED, SPAWNED, GARRISONED, DEPLOYED, RETURNING, MISSING, DEAD, LOST
│   ├── LossReason                    KILLED, MISSING_TIMEOUT, CAPTURED, REMOVED, VILLAGE_GONE, ADMIN
│   ├── Reconciler                    observations → state transitions (the anti-duplication core)
│   ├── LevyPool                      deterministic accrual/spend of recruitment points
│   ├── RecruitmentPlanner            target size, next recruit, replacement gating
│   ├── StartingGarrison              one-time grant, seeded composition
│   ├── DeploymentPlanner             threat → unit assignment (reuses the M2 coordinator ordering)
│   ├── tables/  UnitSpec, GarrisonTable, GarrisonTables, GarrisonTableLoader (datapack reload)
│   └── spi/     UnitProvider, EquipmentProvider, SpawnRequest, SpawnResult, UnitObservation
├── garrison/service/
│   ├── GarrisonService               owned by HywMillRuntime; per-village slot; wires everything
│   └── GarrisonEvents                NeoForge join/leave/death/hurt handlers (O(1) tag checks)
├── garrison/tag/
│   └── GarrisonTag                   NeoForge data attachment on spawned units {village, rosterId, generation}
├── integration/hyw/
│   ├── HywUnitProvider               the only code that creates/configures HYW entities
│   └── HywEquipmentProvider          calls BaseCombatEntity.setEquipment(level)
└── core/GarrisonCommands             /hywmill village garrison …, admin/dev subcommands
```

Rules, carried over from M1/M2:

* **Foreign imports only in `integration.*`.** `garrison/*` sees HYW only through `spi/`.
  `integration.hyw` is loaded only when HYW is present. HYW is already a required dependency, but
  the SPI keeps the pure logic testable, and it is the seam for §15 and §16.
* **No static mutable state.** `GarrisonService` is a field of `HywMillRuntime`, and
  `GarrisonTables` is swapped atomically on datapack reload, the same pattern as `DoctrineTables`.
* **No mixins.** Everything uses public HYW methods, vanilla `Mob` methods and NeoForge events
  (`EntityJoinLevelEvent`, `EntityLeaveLevelEvent`, `LivingDeathEvent`, `LivingIncomingDamageEvent`).
* **Deterministic.** Recruitment choices, the starting composition and spawn-spot search order are
  all seeded from `villageId` plus a roster counter. No `Random` without a seed.
* **One JAR, exact pins, nothing bundled.**
* The data attachment type is registered by HywMill in its own `DeferredRegister<AttachmentType<?>>`.
  This is NeoForge core, not a foreign import.

---

## 2. Persistent data model

The roster lives in `VillageRecord` as a new field, `hywRoster`. The existing `int garrison` field
is M1's count of Millénaire soldiers, so it keeps its meaning and name. The ledger moves to
format 4 (§18).

### Required in M3

`GarrisonRoster` (one per village):

| Field | Type | Meaning |
|---|---|---|
| `startingGranted` | bool | The one-time starting garrison has been issued (§5). It is never reset. |
| `levyPoints` | double | Current recruitment points (§4). |
| `lastAccrualTick` | long | Game time of the last levy accrual (`getGameTime`, not day time). |
| `nextSeq` | int | Monotonic counter; seeds `rosterId` and composition choices. |
| `paused` | bool | Recruitment is paused (controller/op command). Existing units are unaffected. |
| `lastRecruitTick` | long | Enforces the per-village recruit interval. |
| `goneSinceTick` | long (-1) | The village went missing from `SettlementSource.list` (§8, C4). |
| `totals` | ints | `recruited`, `spawned`, `killed`, `lost`, `recovered`, `duplicatesDiscarded`. |
| `entries` | list | `RosterEntry`s, in insertion order. |

`RosterEntry`:

| Field | Type | Meaning |
|---|---|---|
| `rosterId` | UUID | Stable slot identity. Never reused. |
| `unitKey` | string | The key in the unit table, for example `spear_man`. |
| `entityType` | ResourceLocation | For example `hundred_years_war:spear_man`. It is frozen at recruitment, so a datapack edit never changes an existing unit. |
| `equipmentLevel` | int | The level actually applied (after HYW's clamp). |
| `state` | UnitState | §8 |
| `entityUuid` | UUID? | The currently bound entity (null while RECRUITED or after a terminal state). |
| `generation` | int | Incremented on every spawn attempt for this slot; stamped into the entity tag. |
| `stateSinceTick` | long | For timeouts. |
| `lastSeenTick` | long | The last time the bound entity was observed loaded and alive. |
| `lastSeenPos` | BlockPos | Diagnostics only. |
| `recruitedTick` | long | Diagnostics and ordering. |
| `lossReason` | LossReason? | Set on DEAD and LOST. |
| `paid` | bool | Whether levy points were spent. Starting-grant and admin entries are unpaid, so no refunds are ever computed from them. |

Entity-side tag (`GarrisonTag`, a NeoForge attachment serialized with the entity):
`{villageId: UUID, rosterId: UUID, generation: int}`. It holds nothing else. The roster is
authoritative, and the tag exists only so a loaded entity can be matched to its slot.

### Deferred to M4+ (not stored in M3)

HYW `Level`/XP carry-over across replacement, unit names, per-unit equipment overrides and
upgrades, squads and formations, player-assigned posts and patrol routes, linking to HYW supply
points, wages and upkeep, consuming Millénaire goods, taking part in Millénaire raids and offensive
deployment, morale, `equipmentProvider` choice per unit (Epic Knights), cavalry mounts (a two-entity
roster), and siege crews.

---

## 3. Ownership model

| Identity | Stored where | Used for | Changes when |
|---|---|---|---|
| `villageId` | Roster key, entity tag | Which roster a unit belongs to | Never |
| `factionId` = `FactionIds.forVillage(villageId)` (frozen M1.1 derivation) | **HYW `OwnerUUID`** of every unit; residents' relation marker | HYW relation identity, allies, retaliation | Never (derived) |
| `controllerPlayerId` | `VillageRecord.controllerPlayerId` (M2) | **Permissions only** (§12) | Millénaire `setOwner` / switch-control commands |

* The army belongs to the **village/faction**. A controller change writes nothing to any entity,
  and the garrison carries on unchanged.
* The HYW owner UUID is never set to a player, so HYW's "owner player" features (orders UI,
  kill-score credit, player alliance through `ServerPlayer.isAlliedTo`, no-supply hints) do not
  apply. That is intended (C2).
* If a unit's `OwnerUUID` ever stops being `factionId`, for example after capture with an HYW
  scroll, `/hyw` commands or another mod, the reconciler marks the entry **LOST (CAPTURED)**,
  unbinds it and **never writes the owner back**. The entity now belongs to someone else, and
  reclaiming it would be a hidden override.

---

## 4. Recruitment model

The recruitment model is a data-driven, deterministic **levy pool**.

* **Target size**
  `target = clamp(round(capacity × perCapacity[tier]), minByTier[tier], maxByTier[tier])`, and then
  `min(target, config.maxUnitsPerVillage)`.
  `capacity` and `tier` come from the M2 `MilitaryProfile` and are read only.
  Tier `NONE` and lone buildings give `target = 0`.
* **Accrual.** Once per village slot:
  `levyPoints += days × (baseDaily[tier] + perCapacityDaily × capacity)`, capped at `poolCap[tier]`.
  `days = (gameTime − lastAccrualTick) / 24000`. Accrual happens only while the village is
  **active** (its chunks are loaded), which is Millénaire's own simulation boundary. There is no
  offline catch-up in M3.
* **Recruiting.** Each slot recruits at most **one** unit. It recruits only when all of these hold:
  * `aliveOrPending < target`, where aliveOrPending counts non-terminal entries;
  * `levyPoints ≥ cost(unit)`;
  * `!paused`;
  * the alert state is `CALM`;
  * at least `recruitIntervalTicks` have passed since `lastRecruitTick`.

  The unit is picked by weighted choice from the culture's composition (§6), seeded with
  `(villageId, nextSeq)`. A composition-balancing rule picks the most under-represented class
  first, then breaks ties with the weights.
* **Recruitment only creates a RECRUITED entry.** Spawning is a separate step (§7). This
  separation is what the anti-duplication design (§19) depends on.
* **Server-wide cap.** When the total of non-terminal entries reaches `config.maxUnitsServer`,
  recruitment stops everywhere and a throttled warning is logged.

The defaults are in §14. With them, a GARRISON-tier village with capacity 6 reaches a target of 6
and accrues about 3 points a day. A spear_man costs 2, so a full garrison builds up over roughly
4 in-game days.

---

## 5. Starting garrison

**Recommendation:** a one-time, free starting grant of `ceil(target × startingFraction)` units, with
`startingFraction = 0.5` by default.

* It is issued the first time a village's M3 slot runs with `target > 0`, and then
  `startingGranted = true` is set permanently.
* The same rule applies to villages that already exist when the ledger migrates from 3 to 4.
  Their rosters load empty with `startingGranted = false`, so each village gets exactly one grant,
  whether it is new or pre-existing.
* The composition is drawn by the same seeded balancing rule, so the same village always gets the
  same starting units.
* Entries are `paid = false`. They are replaced after death only through normal paid recruitment.
* If the tier later rises, **no second grant** is issued. The levy fills the gap.

Alternatives considered: no starting garrison (villages look defenceless for days) and a full grant
(the whole army appears at once, which also makes every tier bump an instant army). The 50% grant
is a compromise, and its fraction is data-driven.

---

## 6. Unit-type mapping

Only IDs registered in `HywEntityRegistry` for 0.7.1r-fix1 are used. The classes of the eight
candidate units were checked, and all of them extend `BaseCombatEntity`.

| HYW ID | Class and HYW tags | Equipment levels | M3 class | Default use |
|---|---|---|---|---|
| `militia` | MilitiaEntity: LightUnit, CounterLight | 0–3 | LEVY | Yes: low tiers, cheap |
| `spear_man` | SpearManEntity: LightUnit, CounterCavalry | 0–3 | LINE | Yes |
| `shieldman` | ShieldmanEntity: HeavyUnit, UseShield | 0–3 | LINE | Yes: GARRISON and above |
| `warrior` | WarriorEntity: LightUnit, CounterHeavy, CounterSiege | 0–3 | LINE | Yes |
| `archer` | ArcherEntity: RangedUnit | 0–3 | RANGED | Yes |
| `crossbowman` | CrossbowmanEntity: RangedUnit, UseCrossbow | 0–3 | RANGED | Yes |
| `handgonne_man` | HandgonneManEntity: RangedUnit | 0–1 | GUNPOWDER | Off by default (era) |
| `matchlock_man` | MatchlockManEntity: RangedUnit | 2–3 | GUNPOWDER | Off by default (era) |

Excluded in M3, with reasons:

* **Mounted units:** `mounted_*_rider` and `mounted_matchlock_man`. A rider plus a `hyw_horse`
  would be a two-entity roster slot, which doubles the duplication surface. Deferred.
* **Siege units:** `siege_engineer` and every siege weapon. They are a separate system, supply-exempt
  and crewed.
* **Workers:** farmer, lumberjack, miner, fisher, breeder, craftsman, porter, priest. They are not
  garrison.
* **Hostile factions and optional mods:** bandit, desert raider, skeleton, zombie, giant, wood elf,
  TACZ and puppet units.
* **Not registered:** `cavalry`, `cavalry_with_sword` and `iron_mage` (C1).

Default composition per culture. These are weights over the allowed units, all data-driven; the
defaults use only the six "Yes" units above.

| Culture | LEVY | LINE | RANGED |
|---|---|---|---|
| norman | militia 1 | spear_man 3, shieldman 2 | crossbowman 2, archer 1 |
| byzantines | militia 1 | spear_man 2, shieldman 3 | archer 2 |
| seljuk | militia 1 | spear_man 2, warrior 1 | archer 4 |
| japanese | militia 1 | spear_man 4 | archer 3 |
| indian | militia 2 | spear_man 2, warrior 2 | archer 2 |
| mayan | militia 2 | spear_man 2, warrior 2 | archer 2 |
| inuits | militia 3 | spear_man 1 | archer 2 |

Rules:

* LINE units need tier `GUARD_POST` or higher.
* `shieldman` needs `GARRISON` or higher.
* WATCH-tier villages recruit only LEVY and RANGED units.

Equipment level by tier (data): WATCH 0, GUARD_POST 1, GARRISON 2, STRONGHOLD 3. The level is
stored per entry when the unit is recruited and is **not** upgraded later in M3.

**Separate from M2.** None of this touches M2's `equipmentScore`, which still describes only
Millénaire villagers. The garrison has its own display line (§13).

---

## 7. Placement and deployment

Spawning happens in the `GarrisonService` slot and only when **all** of these hold:

* the village is `active`;
* the alert state is `CALM` or `RECOVERY`;
* the anchor chunk and the eight chunks around it are loaded (`level.isLoaded`). Chunks are
  **never** force-loaded;
* the village has been active for at least `settleTicks`, 2 ledger intervals by default. This lets
  entities in chunks that were just loaded join first (§19);
* the server-wide spawn budget for the tick is not used up.

Each village spawns at most `spawnsPerSlot` units (default 2) per slot.

* **Anchor.** Millénaire's `RaidManager.resolveDefendingPos(village)`, the same point the M2
  reserve holds, or the village centre when there is none.
* **Spot search.** Deterministic, rather than HYW's private `findSafeSpawnPosition`:
  * take the `MOTION_BLOCKING_NO_LEAVES` heightmap at candidate columns;
  * walk rings at radius 0…8 in a fixed order, offset by `rosterId.hashCode()`;
  * accept a column with a solid top face below, two blocks of non-colliding space, no fluid, and
    no Millénaire building footprint marked as interior (optional, [spike]);
  * give up after 24 candidates, leave the entry RECRUITED and try again next slot.
* **Configuration.** The HYW sequence from §0 is applied in this order:
  1. `setOwnerUUID(factionId)`;
  2. `setRequiresSupply(false)`;
  3. `setCanNaturalDespawn(false)`;
  4. equipment provider `apply(level)`;
  5. `setHomePosition(anchor)`;
  6. `setAttackStrategy(DEFAULT)`;
  7. `setDropChance(slot, 0)` for the six slots (§19);
  8. attach the `GarrisonTag`;
  9. set a deterministic UUID (§19);
  10. `setPos`;
  11. `addFreshEntity`.
* **GARRISONED posture.** Units stay near the anchor through HYW's home behaviour. M3 does not set
  `CommandHold` or patrol points, so HYW's own threat response stays active. Whether HYW walks a
  unit back to its home while idle, and how `shouldIgnoreHomePosition` affects that, is a [spike].

**Deployment**, driven by the M2 alert state:

* In ALERT or ENGAGED, the M2 threat list (the same targets the `DefenseCoordinator` already
  holds) is given to `DeploymentPlanner`.
* For each threat, in the coordinator's deterministic order, it assigns up to `commitPerThreat`
  (garrison table) of the nearest GARRISONED/RETURNING units inside `defenseRadius`. Ties break on
  `rosterId`.
* Assignment calls `TemporaryHostileTargetManager.markHostile(unit, threat)` and `unit.setTarget(threat)`.
  The state becomes DEPLOYED.
* Unassigned units stay home. HYW's own AI still lets them defend themselves.
* The planner **only accepts targets the M2 coordinator already classified as hostile.** It never
  derives a target of its own, so the M2 behaviour contract carries over unchanged: proactive only
  against monsters and unowned hostile HYW units, and reactive against everything else (§11).
* When the alert returns to RECOVERY or CALM, or a unit's target is dead, gone or outside
  `defenseRadius + 16`, the planner clears the target and the unit becomes RETURNING. RETURNING
  becomes GARRISONED once the unit is within 8 blocks of the anchor, or after
  `returnTimeoutTicks`, whichever is first.

---

## 8. Lifecycle

States: `RECRUITED`, `SPAWNED`, `GARRISONED`, `DEPLOYED`, `RETURNING` (all live);
`MISSING` (live but unobserved); `DEAD` and `LOST` (terminal).
`RECOVERED` is a **transition event** (MISSING or LOST-pending back to GARRISONED), counted in
`totals.recovered`. It is not a resting state.

| From | Event | To | Notes |
|---|---|---|---|
| (none) | Recruit, starting grant, admin grant | RECRUITED | Creates the entry; spends points if paid. |
| RECRUITED | Spawn succeeded | SPAWNED | `generation++` and `entityUuid` recorded **before** `addFreshEntity` returns control; reverted if it returns false. |
| SPAWNED | Observed loaded and alive at the next slot | GARRISONED | |
| GARRISONED | Deployment assignment | DEPLOYED | |
| DEPLOYED | Target cleared or alert over | RETURNING | |
| RETURNING | Near anchor or timeout | GARRISONED | |
| any live | `LivingDeathEvent` for the bound entity | DEAD (KILLED) | |
| any live | `EntityLeaveLevelEvent` with removal reason `KILLED`/`DISCARDED` (for example `/kill` or another mod) | LOST (REMOVED) | `UNLOADED_TO_CHUNK` and `UNLOADED_WITH_PLAYER` are **not** losses. `CHANGED_DIMENSION` is handled in the next row. |
| any live | Owner UUID ≠ factionId when observed | LOST (CAPTURED) | The owner is never rewritten. |
| any live | Bound entity not found while the village is active, after `missingGraceTicks` | MISSING | "Not loaded" and "dead" are never treated as the same thing. |
| MISSING | Bound entity observed again, via the join event or a slot lookup | GARRISONED (RECOVERED) | |
| MISSING | `lostTimeoutTicks` elapsed (default 3 in-game days of **active** time) | LOST (MISSING_TIMEOUT) | Only this frees the slot for **paid** replacement. |
| any | Village absent from `SettlementSource.list` for `villageGoneGraceTicks` | LOST (VILLAGE_GONE) for all entries | See "village deleted" below. |
| any | Admin purge | LOST (ADMIN) | Discards bound entities that are loaded. |

What happens to the garrison on each event:

* **Chunk unload or reload.** The entity is saved and loaded by vanilla. The join event re-checks
  its tag (§19). Nothing is spawned.
* **Village becomes inactive.** Timers pause: MISSING and LOST timeouts count active time only. No
  spawns, no accrual.
* **Server restart.** The first slot after load runs `settleTicks` later. Units re-bind through join
  events, and nothing that is SPAWNED or later in the lifecycle is ever re-spawned (§19).
* **Controller change or culture control grant.** No effect on units (§3). The command
  permissions follow the new controller.
* **Dimension change.** In 1.21.1 the entity is re-created in the new level with the same UUID.
  Whether the attachment is copied is a [spike]. The roster looks units up across **all** levels
  by UUID, so the unit stays bound.
* **Alert changes.** These drive DEPLOYED and RETURNING (§7).
* **Datapack reload.** Existing entries keep their frozen `entityType` and `equipmentLevel`. Only
  targets and new recruits change. If a lowered target leaves the roster over size, units are
  **not** removed in M3; recruitment simply stops until losses bring it back under.
* **Config caps lowered.** Same as the datapack case: no culling.
* **Village deleted (C4).** Every entry becomes LOST (VILLAGE_GONE). Loaded bound entities are
  handled by `orphanPolicy`: `KEEP` (the default) leaves them as HYW units still owned by
  `factionId`, which inherit no roster and are never replaced, while `DISCARD` removes them. The
  record and roster are kept for a further retention window for diagnostics, then pruned.
* **HywMill removed.** The units stay as ordinary HYW units owned by `factionId`. The attachment is
  dropped on load. No crash.
* **HYW removed.** HYW is a hard dependency, so this cannot happen.
* **Ledger migration.** §18.

---

## 9. Death and replacement: no free infinite armies

* A death puts the entry in DEAD. It is **never** respawned: DEAD entries are terminal.
* A replacement is a **new paid recruit**. It creates a new `rosterId` at full `cost`, via the
  normal rule in §4, and only in CALM. DEAD and LOST entries are pruned after
  `terminalRetentionTicks` (1 day), and totals are kept.
* **Replacement cooldown.** Each death pushes `lastRecruitTick` forward by `deathCooldownTicks`
  (default 1200 ticks). A wipe-out, meaning at least 75% of the target dead within one alert
  episode, adds `wipeoutCooldownTicks` (default one in-game day).
* **Upper bound.** The rate of new units per village is limited by
  `(baseDaily + perCapacityDaily × capacity) / minCost`, by `poolCap`, and by one recruit per slot
  in CALM. There is no path that creates units without a RECRUITED entry, and every RECRUITED entry
  is paid, a single starting grant, or created by an op.
* Loss of equipment is not a resource sink in M3, because gear is intrinsic to HYW. Drops are
  disabled so deaths cannot be farmed for gear (§19).

---

## 10. M2 integration

| M2 component | M3 relation |
|---|---|
| `MilitaryProfile` | **Read only**: `capacity` and `tier` feed the target size and levels. No field changes meaning, and `readiness` and `equipmentScore` are untouched. |
| `DoctrineResolver` / `Doctrine` | **Read only**: `defenseRadius`, `proactive` and the alert timings. No new doctrine fields. The garrison parameters live in the new `hywmill_garrison` table, so the approved doctrine schema and values stay frozen. |
| `DefenseService` | Gains a `garrison()` view. HYW units are **not** added to the Millénaire defender pool, so the M2 commit, reserve and militia logic is unchanged. |
| `DefenseCoordinator` | Provides the classified threat list and its deterministic ordering to `DeploymentPlanner`. No change to how it allocates Millénaire villagers. |
| `AlertStateMachine` | Drives deployment and return. **New input:** damage to a bound garrison unit counts as an attack on the village, recorded in the incident log exactly like damage to a resident. Retaliation and ALERT/ENGAGED therefore trigger when someone attacks the garrison. |
| `VillageRecord` | + `hywRoster`, format 4. |
| `controllerPlayerId` | Permissions only (§12). |
| `factionId` | The HYW owner UUID of every unit. |
| `FortificationScore` / `tier` | The tier sets the target bounds, the allowed classes and the equipment level. Fortification has no direct effect in M3. |
| `PerfCounters` | New keys `garrison.slot`, `garrison.spawn`, `garrison.deploy`, `garrison.event`. |
| `VillageScheduler` | A third per-village offset at `interval/4`. It never coincides with the refresh (offset 0) or the sweep (`interval/2`). |

---

## 11. HYW relation behaviour

The owner is `factionId`, so all of this follows from HYW's relation identity plus two HywMill
rules:

* **R1.** Deployment only uses M2-classified threats.
* **R2.** ALWAYS_REVERT is kept.

| Counterpart | Behaviour | Mechanism |
|---|---|---|
| Own village residents | Never attacked | Same relation identity (marker = owner = `factionId`). HYW relation-protects the same identity. R1 never puts a resident in the threat list, and M3 adds a hard filter that drops any target whose relation identity is `factionId`. |
| Own garrison units | Allied | Same owner UUID, via `BaseCombatEntity.isAlliedTo` → `OwnableEntity` equal owner. |
| Other Millénaire villages | Neutral: passage is not attacked | Different faction IDs, with a relation of NEUTRAL by default. Units respond only when the M2 coordinator classifies a raider or attacker as a threat, meaning an actual attack. |
| Unowned HYW units (bandits and the like) | Engaged when they are threats | Already classified by M1/M2 (unowned means hostile to everything). Proactive only if the doctrine says `proactive`. |
| Players | Neutral: passage is not attacked | No permanent relation. A player who attacks a resident or unit becomes a threat through the incident log. The units retaliate with `markHostile`, which expires by HYW's own timer (duration is a [spike]), plus the M2 alert timing. |
| Player-owned HYW units | Neutral: passage is not attacked | Same as players. Their relation identity is the player UUID. |
| HYW permanent relations | ALWAYS_REVERT | M1.1 escalation reconciliation already reverts any `HOSTILE` between `factionId` and another identity. The units share that identity, so the policy covers them automatically. |
| Temporary retaliation | Allowed | `TemporaryHostileTargetManager.markHostile`. This is the only hostility M3 creates. |

Whether HYW's built-in targeting of a unit with a non-player owner attacks **neutral** players or
other factions' units on its own is the most important [spike] (§22, R1). If it does, M3 needs a
small counter-measure inside `integration.hyw`, such as clearing HYW-chosen targets that are not
M2 threats in the slot or on `LivingChangeTargetEvent`. That is still no mixin.

---

## 12. Player and controller permissions

| Action | Op ≥ 2 | Controller | Other player |
|---|---|---|---|
| View garrison summary | yes | yes | yes (counts only) |
| View unit list | yes | yes | no |
| `pause` / `resume` recruitment | yes | yes | no |
| `recall` (clear targets, all units RETURNING) | yes | yes | no |
| Admin `grant`, `purge`, `reconcile`, `setpoints` | op ≥ 3 (like `admin`) | no | no |
| Dev commands | op ≥ 2 | no | no |
| HYW UI orders | nobody (C2) | nobody | nobody |

The controller check reuses the M2 doctrine rule: `controllerPlayerId == player UUID` and
the village is player-controlled.

Players cannot dismiss units in M3. A dismissal would need either a refund, which invites
duplication and point-farming, or nothing at all, which would be surprising. `purge` is admin-only.

---

## 13. Commands

The existing tree acts on the **nearest village**, like `village military` and `doctrine`. M3 adds:

```
/hywmill village garrison                 summary: target/alive/pending/missing/dead, levy points, next recruit ETA, paused
/hywmill village garrison units           one line per entry: rosterId(short) unit lvl state entity(short) lastSeen
/hywmill village garrison pause|resume    controller or op
/hywmill village garrison recall          controller or op
/hywmill admin garrison grant <unit> [n]  op 3; adds unpaid RECRUITED entries (n ≤ target headroom unless 'force')
/hywmill admin garrison purge             op 3; LOST(ADMIN) all, discard loaded bound entities
/hywmill admin garrison reconcile [all]   op 3; run the reconciler now (no spawns unless eligible)
/hywmill admin garrison setpoints <n>     op 3
/hywmill dev garrison spawn-now           op 2; bypass settle/interval (still roster-driven)
/hywmill dev garrison rewind <rosterIdPrefix>  op 2; sets entry back to RECRUITED without touching the entity (duplication test, §20)
/hywmill dev garrison census [radius]     op 2; counts tagged entities in a box, compares to roster (test-only, bounded)
```

`village military` gains a single line: `garrison: 5/6 alive (1 recruiting), lvl 2, levy 3.4`.
`perf` shows the new keys.

---

## 14. JSON and data formats

Datapack directory: `data/<ns>/hywmill_garrison/`. The directory is merged in the same way as
`hywmill_doctrine`: the base comes from `defaults.json`, and each culture file patches it.

`units.json`, the unit catalogue. IDs are validated against the entity registry at reload, and an
unknown or unregistered ID is logged and dropped.

```json
{
  "units": {
    "militia":     { "entity": "hundred_years_war:militia",     "class": "LEVY",    "cost": 1, "minTier": "WATCH" },
    "spear_man":   { "entity": "hundred_years_war:spear_man",   "class": "LINE",    "cost": 2, "minTier": "GUARD_POST" },
    "shieldman":   { "entity": "hundred_years_war:shieldman",   "class": "LINE",    "cost": 3, "minTier": "GARRISON" },
    "warrior":     { "entity": "hundred_years_war:warrior",     "class": "LINE",    "cost": 3, "minTier": "GUARD_POST" },
    "archer":      { "entity": "hundred_years_war:archer",      "class": "RANGED",  "cost": 2, "minTier": "WATCH" },
    "crossbowman": { "entity": "hundred_years_war:crossbowman", "class": "RANGED",  "cost": 3, "minTier": "GUARD_POST" },
    "handgonne_man": { "entity": "hundred_years_war:handgonne_man", "class": "GUNPOWDER", "cost": 4, "minTier": "GARRISON", "enabled": false },
    "matchlock_man": { "entity": "hundred_years_war:matchlock_man", "class": "GUNPOWDER", "cost": 5, "minTier": "STRONGHOLD", "enabled": false }
  }
}
```

`defaults.json`:

```json
{
  "tiers": {
    "NONE":       { "perCapacity": 0.0, "min": 0, "max": 0,  "baseDaily": 0.0, "poolCap": 0,  "equipmentLevel": 0 },
    "WATCH":      { "perCapacity": 1.0, "min": 1, "max": 2,  "baseDaily": 1.0, "poolCap": 4,  "equipmentLevel": 0 },
    "GUARD_POST": { "perCapacity": 1.0, "min": 2, "max": 4,  "baseDaily": 1.5, "poolCap": 6,  "equipmentLevel": 1 },
    "GARRISON":   { "perCapacity": 1.0, "min": 3, "max": 8,  "baseDaily": 2.0, "poolCap": 10, "equipmentLevel": 2 },
    "STRONGHOLD": { "perCapacity": 1.0, "min": 4, "max": 12, "baseDaily": 3.0, "poolCap": 16, "equipmentLevel": 3 }
  },
  "perCapacityDaily": 0.25,
  "startingFraction": 0.5,
  "commitPerThreat": 2,
  "composition": { "militia": 1, "spear_man": 3, "archer": 2 },
  "equipmentProvider": "hyw"
}
```

`cultures/norman.json` (a patch that replaces `composition` and may override any tier field):

```json
{ "composition": { "militia": 1, "spear_man": 3, "shieldman": 2, "crossbowman": 2, "archer": 1 } }
```

Server config (`hywmill-server.toml`, new `[garrison]` section):

| Key | Default |
|---|---|
| `enabled` | true |
| `maxUnitsPerVillage` | 16 |
| `maxUnitsServer` | 200 |
| `spawnsPerSlot` | 2 |
| `spawnsPerTickServer` | 2 |
| `settleIntervals` | 2 |
| `recruitIntervalTicks` | 2400 |
| `deathCooldownTicks` | 1200 |
| `wipeoutCooldownTicks` | 24000 |
| `missingGraceTicks` | 1200 |
| `lostTimeoutTicks` | 72000 |
| `villageGoneGraceTicks` | 6000 |
| `returnTimeoutTicks` | 1200 |
| `orphanPolicy` | KEEP |
| `equipmentDrops` | false |

`enabled=false` stops recruitment, spawning and deployment. Existing units stay, and the
reconciler still runs, so the roster stays accurate.

---

## 15. Equipment-provider abstraction

```java
// garrison/spi — no foreign types
public interface EquipmentProvider {
    String id();                                // "hyw"
    boolean available();
    int resolveLevel(UnitSpec unit, MilitaryTier tier, int requestedLevel);   // pure clamp/lookup
    int apply(Object entity, UnitSpec unit, int level);  // returns level actually applied
}
```

* **M3 ships only `HywEquipmentProvider`**, in `integration.hyw`. `apply` casts to
  `BaseCombatEntity` and calls `setEquipment(level)`, which runs `loadEquipmentData` and then
  `normalizeTier` and `captureIntrinsicEquipment`. It returns `getEquipmentLevel()`.
* Providers are chosen by `equipmentProvider` in the garrison table. An unknown or unavailable
  provider id falls back to `hyw` with a warning.
* The applied level is saved in the roster and in HYW's NBT, so a restart never re-rolls equipment.

## 16. Future Epic Knights boundary

* M3 contains **no Epic Knights code**. No Epic Knights classes were inspected or imported.
* Per **C6**, HYW's own `enableEpicKnightsCompat` already puts Epic Knights armour on village units
  when the server has `magistuarmory` loaded and the HYW option is on, because
  `HywEquipmentProvider` delegates to HYW's equipment files. **This is the recommended "Epic
  Knights support" for M3.** It is HYW's feature, and HywMill needs no code for it.
* If a later milestone wants gear that differs by culture (for example Byzantine versus Norman
  Epic Knights sets), it would add an `integration.epicknights.EpicKnightsEquipmentProvider`:
  * registered only when `ModList.isLoaded("magistuarmory")`, in a class never touched otherwise;
  * `neoforge.mods.toml` would list it as `type="optional"`, never a hard dependency;
  * it would apply items as **external** equipment with HYW's intrinsic equipment kept, relying on
    HYW's existing split between intrinsic and external equipment (`isIntrinsicEquipmentEnabled`,
    `HywSuspendedExternalEquipment`). That interaction would need its own audit.
* The M3 interface above is sufficient for that; nothing in M3 has to change.

---

## 17. Performance strategy

**No scanning.** M3 never iterates HYW entities or searches AABBs in a normal tick.

* **Slot work** (once per village per ledger interval, at offset `interval/4`):
  * for each roster entry, find the bound entity with `ServerLevel.getEntity(UUID)` across the
    loaded levels (a hash lookup);
  * apply `Reconciler` transitions;
  * accrue levy points, and recruit at most one entry;
  * spawn at most `spawnsPerSlot` entries.
* **Events:**
  * `EntityJoinLevelEvent` and `EntityLeaveLevelEvent`: `instanceof BaseCombatEntity`, then
    `getExistingData(GarrisonTag)`. Anything without the tag returns immediately.
  * `LivingDeathEvent` and `LivingIncomingDamageEvent`: the same tag check first.
* **Deployment** runs only while the village alert is not CALM, and only over roster-bound loaded
  units (≤ 16) × threats (already computed by M2).

Budgets, checked by the harness with production logging:

| Counter | Mean | p99 | Max |
|---|---|---|---|
| `garrison.slot` (per village, 16 entries) | ≤ 0.15 ms | ≤ 0.5 ms | ≤ 2 ms |
| `garrison.spawn` (per unit) | ≤ 1.0 ms | ≤ 3 ms | budgeted: ≤ 2 spawns per server tick |
| `garrison.deploy` (per village update in ALERT/ENGAGED) | ≤ 0.1 ms | ≤ 0.4 ms | n/a |
| `garrison.event` (per tagged entity event) | ≤ 5 µs | ≤ 20 µs | n/a |
| Increase in `tick.total` mean (5 villages, full garrisons, CALM) | ≤ +40 µs | n/a | n/a |

Spawn cost includes HYW's `loadEquipmentData` resource read. Whether HYW caches it is a [spike].
If it doesn't, the per-tick spawn cap is what keeps the budget.

---

## 18. Migration (ledger format 3 → 4)

* `VillageRecord.FORMAT = 4`. A format-4 record adds a `hywRoster` compound.
* **Load 3 → 4.** The roster is empty with `startingGranted = false`, and `levyPoints` is 0 with
  `lastAccrualTick` set to the current tick, so no retroactive accrual happens. The existing chain
  (1/2 → 3) still runs first. It logs "migrated N record(s) from format 3 to 4".
* **Load 4.** The roster is loaded as saved. An entry with an unknown `entityType` (the datapack or
  HYW changed) is kept, marked LOST (REMOVED) at the first slot if its entity cannot be found, and
  never respawned under a different type.
* **Downgrade (M2 JAR on a format-4 save).** The M2 loader reads the fields it knows and ignores
  `hywRoster`; this is to be confirmed in M3-1 by a test that loads a format-4 compound with the M2
  `VillageRecord.load` code path. The next save **drops the roster**. The HYW units in the world
  stay as ordinary HYW units owned by `factionId`. They are allied with the residents and are never
  re-spawned, because the roster is gone. Upgrading again later finds no roster, issues the starting
  grant once more, and the old units are untagged orphans (their attachments were dropped). This is
  documented as unsupported: "do not downgrade with garrisons".
* There is no separate SavedData, so the roster and ledger are written atomically together in one
  file.

---

## 19. Security and duplication protection

**Invariants**, each enforced in pure code and tested:

* **I1.** At most **one** bound entity per `rosterId` at any time.
* **I2.** Tagged live entities for a village ≤ non-terminal roster entries.
* **I3.** An entity is created **only** from an entry in RECRUITED. RECRUITED entries come only
  from paid recruitment, the single starting grant, or an op command.
* **I4.** An unobserved (unloaded) unit never causes a spawn. Only LOST, followed by a **new paid**
  entry, replaces it.
* **I5.** A restart is idempotent. Load plus settle plus reconcile performs **zero** spawns for
  entries in SPAWNED, GARRISONED, DEPLOYED, RETURNING or MISSING.

**Mechanisms:**

1. **Roster first.** The entry changes RECRUITED → SPAWNED (with `generation++` and `entityUuid`)
   and the ledger is marked dirty in the **same call** that runs `addFreshEntity`. If
   `addFreshEntity` returns false, the entry is reverted.
2. **Deterministic entity UUID.** Before the entity is added, it gets
   `entityUuid = UUID.nameUUIDFromBytes("hywmill:" + rosterId + ":" + generation)`. If a stale
   ledger ever re-spawns the same `(rosterId, generation)`, the two copies share one UUID, and
   vanilla's entity manager refuses to add an entity whose UUID is already loaded ("UUID of added
   entity already exists"). Confirming that this also holds for chunk-loaded entities in 1.21.1 is
   a [spike]. The reconciler does not depend on it; it is a second line of defence.
3. **Tag adjudication on join** (`EntityJoinLevelEvent`, tagged entities only):
   * the entry exists and `entityUuid` equals the entity UUID: bind (or RECOVERED if MISSING);
   * the entry exists, is in RECRUITED or MISSING with no other bound entity, and the tag's
     generation ≥ the entry's: **adopt**, set `entityUuid`, and move to GARRISONED. This is the
     "entity saved but ledger stale" crash window;
   * the entry exists and is bound to a **different** UUID in any live state: **duplicate**. It
     is discarded before it enters the world (the join event is cancelled) and counted in
     `duplicatesDiscarded`, with a WARN log;
   * the entry is DEAD or LOST: duplicate, discarded;
   * there is no entry or no roster (the village is gone): `orphanPolicy`.
   * The rule deliberately prefers **losing** one unit to **duplicating** one. When the bound UUID
     is not loaded and a different claimant appears, the claimant is discarded, and if the bound
     entity really is gone, MISSING → LOST → paid replacement follows.
4. **Settle delay.** No spawns for `settleIntervals` after a village activates or the server
   starts. This gives entities in freshly loaded chunks time to join first.
5. **No spawn outside loaded chunks, and never force-loading.**
6. **Owner is never rewritten.** A captured unit is LOST, not re-claimed (§3).
7. **Drops.** When `equipmentDrops = false`, the drop chance for all six equipment slots is set to
   0, so repeated deaths cannot be farmed for gear. The loot-table drops of the HYW unit itself are
   unchanged (a [spike] to list them).
8. **Commands.** `grant` is op 3 and bounded by target headroom unless `force` is given. There is
   no player dismiss or refund path.
9. **Caps.** `maxUnitsPerVillage` and `maxUnitsServer` are checked at recruitment. The per-tick
   spawn budget is checked at spawn time.

Worked example: **"10 roster entries → 20 entities" cannot happen.**

* All 10 entries are GARRISONED and their entities sit in unloaded chunks.
* On restart, I5 holds (no spawns for live states).
* Crash variant A (the ledger is behind): an entry reads RECRUITED while its entity exists in an
  unloaded chunk. Because of the settle delay the chunk usually loads first, and the entity is
  adopted. If the spawn wins the race instead, the respawn uses the same `(rosterId, generation)`,
  so the UUID is identical, vanilla rejects the second copy, and even without that rule, join
  adjudication discards the claimant bound to a different UUID.
* Crash variant B (the ledger is ahead): the entry is SPAWNED but the entity was never saved. The
  entity is not found, so the entry goes MISSING → LOST and a paid replacement follows.
* In every case, the count of tagged entities is ≤ 10.

---

## 20. Test plan

### JUnit (pure; target ≥ 45 new tests)

**Reconciler**

* Every transition in §8, table-driven.
* Unloaded is not missing while the village is inactive.
* The MISSING grace and the LOST timeout count active ticks only.
* Owner mismatch → LOST(CAPTURED) with no owner write.
* The leave reasons KILLED and DISCARDED → LOST(REMOVED); the UNLOADED reasons → no change.

**Join adjudication**

* bind; adopt when stale-RECRUITED; adopt when MISSING;
* duplicate when bound to another UUID; duplicate when DEAD or LOST;
* the generation-lower claimant is rejected;
* orphan handling under KEEP and DISCARD.

**Invariants (property test)**

* Random sequences of recruit, spawn, save, crash-rewind, join, leave, death and restart events.
  Assert I1–I5 after each step. Uses 10,000 seeded runs.

**LevyPool**

* Accrual over whole and fractional days; the cap; no accrual while inactive; no retroactive
  accrual after migration.
* Spending, and the rule that one recruit per slot is the maximum.

**RecruitmentPlanner**

* Target per tier and capacity; NONE and lone buildings give 0.
* The per-village and server caps.
* No recruiting while paused, outside CALM, or during a cooldown.
* The death cooldown and the wipe-out cooldown.
* Composition balancing is deterministic for a given seed.
* Class gating by tier.

**StartingGarrison**

* Granted once.
* The migration path grants once.
* A tier bump does not re-grant.
* The fraction rounding.

**DeploymentPlanner**

* commitPerThreat, the nearest-unit order and tie-breaks.
* Only M2 threats are accepted.
* Same-faction targets are filtered out.
* RETURNING rules.

**Tables**

* Merge of defaults and a culture patch.
* Unregistered IDs are dropped (checked against a supplied set of IDs).
* Disabled units are never chosen.
* Equipment-level clamping per unit (matchlock 2–3, handgonne 0–1).

**Ledger**

* Format 3 → 4 load; format-4 round-trip.
* The M2 loader tolerates format 4 (downgrade check).
* An unknown `entityType` entry is kept.

### Dedicated-server harness (new scenarios "G3-*", with the same world, seed and villages as M2)

| ID | Setup | Assertions |
|---|---|---|
| G3-1 Starting grant | Village A (norman) and B, fresh world | Within settle + 2 intervals, the `village garrison` pending count reaches `ceil(target/2)` and census = roster; every unit has OwnerUUID = `factionId`, RequiresSupply = 0, CanNaturalDespawn = 0, and an EquipmentLevel equal to the table value (after clamping). |
| G3-2 Allegiance | A's garrison next to A's residents for 2 minutes | 0 hits on residents; 0 hits between garrison units; `relation` A-units ↔ residents = same identity. |
| G3-3 Neutral passage | A player and a player-owned HYW squad walk through A | Garrison hits on them = 0; no HOSTILE relation afterwards. |
| G3-4 Response | A player hits a resident of A (M2 D-scenario hook) | Within 5 s ≥ 1 garrison unit targets the player (`TemporaryHostileTargetManager.isHostile`); state becomes DEPLOYED; after the alert: RETURNING → GARRISONED; the relation is still not HOSTILE (ALWAYS_REVERT). |
| G3-5 Proactive monsters | A zombie inside the defense radius | Garrison engages if the doctrine is proactive; the zombie dies; units return. |
| G3-6 Unowned HYW | A `bandit_soldier` near A | Engaged per doctrine; own residents untouched. |
| G3-7 Restart ×3 | Full roster → `stop` → restart, three times | After each restart and settle: census == roster count == N; `duplicatesDiscarded` = 0; spawns during settle = 0. |
| G3-8 Stale-ledger duplicate | `dev garrison rewind <id>` on a GARRISONED entry whose unit is out of loaded range → move the player so the chunk loads, then force `spawn-now` before and after | census ≤ roster count at every sample; exactly one entity with that rosterId; either it was adopted (RECOVERED++) or the claimant was discarded (`duplicatesDiscarded`++). |
| G3-9 Unload is not death | Walk away until A unloads, wait 2 × `missingGraceTicks`, return | No state change while inactive; units re-bind; no spawns. |
| G3-10 Death and replacement | `/kill` one unit, then kill one with damage | `/kill` → LOST(REMOVED); damage → DEAD; no replacement before the cooldown; after the cooldown and with points: a new rosterId; levy spent; census == roster. |
| G3-11 Capture | `/data modify` the unit's OwnerUUID to a player | Next slot: LOST(CAPTURED); the entity is kept and its owner not rewritten; census excludes it. |
| G3-12 Controller change | `SwitchVillageControlCommand` and `GrantCultureControlCommand` on B | The units' OwnerUUID is unchanged; the new controller can `pause`, the old one can't. |
| G3-13 Permissions | Non-op non-controller | `units`, `pause` and `recall` are denied; the summary is allowed. |
| G3-14 Village deleted | Negation-wand path via `VillageManager.removeVillage` (dev hook) | After the grace: all entries LOST(VILLAGE_GONE); units kept (KEEP); no spawns. |
| G3-15 Datapack reload | Change the composition and reload | Existing entries unchanged; the next recruit uses the new weights. |
| G3-16 Migration | Start from the saved M2 format-3 world from `docs/m2-test-evidence` | Format 4 is written; one grant; M2 records unchanged. |
| G3-17 Perf | 5 villages with full garrisons, 10 min CALM, then a 2-min fight | The §17 budgets hold, with production logging. |
| G3-18 M2 regression | The whole M2 suite | 70/72 or better. The two perf-tail items remain accepted. |

The optional suite keeps its 3 checks and adds one: with `magistuarmory` absent, the provider
reports `hyw` and loads `equipment.json`. There is no Epic Knights JAR in CI.

---

## 21. Explicit exclusions (M3)

The following are excluded from M3:

* Epic Knights code or dependency.
* Mounted units, siege units, workers, and gunpowder units in the default tables.
* HYW UI or order control by players or controllers (C2).
* HYW supply-point linkage and upkeep.
* Consuming Millénaire goods (C8).
* Offensive deployment: raids, marching to other villages, patrols, posts, formations.
* Equipment upgrades after recruitment.
* Carrying XP or level across replacement.
* Player dismiss or refunds.
* Culling units when a target is lowered.
* Chunk force-loading.
* Mixins.
* Any change to DiplomacyPolicy (it stays ALWAYS_REVERT), to the M2 doctrine schema and values, to
  M2 `readiness` or `equipmentScore`, or to the M2 allocation of Millénaire defenders.
* Offline accrual.

---

## 22. Risks and unresolved questions

**Risks.** Each has a spike in M3-0.

| # | Risk | Mitigation |
|---|---|---|
| R1 | HYW's own targeting may attack neutral players or other factions' units when the owner is a non-player UUID. | Spike first. If it happens: drop HYW-chosen targets that are not M2 threats in `integration.hyw` on `LivingChangeTargetEvent`. |
| R2 | The home/return behaviour may be weak; units could wander off. | Spike. If needed, M3 issues move commands in the slot through the public `startMoveCommand`/home APIs. |
| R3 | The `markHostile` duration is unknown. | Spike to measure; the M2 alert timing drives re-marking. |
| R4 | Rejection of a duplicate UUID from chunk load in 1.21.1. | Spike. It is defence in depth only. |
| R5 | Attachment copy on dimension change. | Spike. The UUID lookup covers it either way. |
| R6 | Spawn cost from equipment JSON loading. | Measure; the per-tick cap. |
| R7 | Millénaire villagers may path through or collide with garrison units near `defendingPos`. | Spike; the anchor offset is data-driven. |
| R8 | Millénaire may treat HYW units as raiders or monsters. | M1 already bridges this; include G3-2 in the spike. |
| R9 | The harness world has few STRONGHOLD villages. | Use the norman/militaire spot at (650, 68, 920). |

**Questions for you:**

* **Q1.** Is the starting grant at 50% of target and free (§5) acceptable?
* **Q2.** Should the target scale with the Millénaire soldier capacity (§4), or be a tier-only
  table independent of capacity?
* **Q3.** Should M3 stay with the abstract levy pool, or should it also consume Millénaire goods?
  The latter needs a separate audit of the Millénaire stores API, which is not verified (C8).
* **Q4.** Epic Knights (C6): is "HYW's own Epic Knights compat through the HYW provider" enough
  for the foreseeable future?
* **Q5.** For a deleted village, should `orphanPolicy` default to KEEP (orphaned units stay) or
  DISCARD?
* **Q6.** Is it acceptable that the controller cannot use HYW's UI (C2)? The alternative, a mixin
  or a second "commander" identity, contradicts the "no mixins" and owner rules.
* **Q7.** Should gunpowder units stay disabled by default for every culture?

---

## 23. Recommended implementation order

Each step is its own commit, with JUnit green and the M2 harness green before the next step.

* **M3-0 Spike (no persisted format change).** A dev-only command spawns one HYW unit with
  owner = `factionId` using the §7 sequence. Record the findings for R1–R8 in
  `docs/m3-spike.md`, then **stop and report** if R1 needs more than the planned counter-measure.
* **M3-1 Data model and migration.** `GarrisonRoster`, `RosterEntry` and format 4, plus the
  migration and downgrade tests. No behaviour change.
* **M3-2 Tables.** `units.json`, `defaults.json` and the culture patches; the loader with registry
  validation; atomic swap on reload.
* **M3-3 SPI and HYW provider.** `UnitProvider` and `EquipmentProvider`, `HywUnitProvider`,
  `GarrisonTag`, the spot search and the deterministic UUID. Driven by `dev garrison spawn-now`
  only.
* **M3-4 Reconciler and events.** The lifecycle and join adjudication, with the invariant property
  tests. Harness G3-7, G3-8, G3-9, G3-10, G3-11 and G3-14. **Anti-duplication lands before
  automatic recruitment.**
* **M3-5 Recruitment.** Levy pool, planner and starting grant; the server slot and caps.
  Harness G3-1, G3-15 and G3-16.
* **M3-6 M2 integration and deployment.** Garrison damage feeds incidents; `DeploymentPlanner`;
  RETURNING. Harness G3-2 to G3-6 and G3-12.
* **M3-7 Commands and permissions.** Harness G3-13.
* **M3-8 Perf, full regression and report.** G3-17 and G3-18, then `docs/m3-report.md` and test
  evidence.
