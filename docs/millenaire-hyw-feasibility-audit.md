# Millénaire + Hundred Years War: integration feasibility audit

Status: reconnaissance and design only. No mod code has been written, and neither Millénaire nor HYW was modified.
Date of audit: 2026-09-25.

---

## 0. What was actually inspected (read this first)

| Artifact | Version | Source | License | Notes |
|---|---|---|---|---|
| Hundred Years War | `0.7.1r-fix1-1.21.1-neoforge` (modid `hundred_years_war`, author ydmsama) | JAR you provided | All Rights Reserved | Not obfuscated. Class and member names are readable. Decompiled with Vineflower 1.11.1 (1,248 classes). Shades `lz4-pure-java`. Requires NeoForge `[21.1,21.2)`. |
| Millénaire (official) | `9.0.2` (2026-09-18, built for NeoForge 21.1.226) | `millenaire.org/api/downloads/9.0.2~millenaire-9.0.2.jar` | All Rights Reserved | **Primary audit target.** This is the build players install. Decompiled (688 top-level classes). |
| Millénaire (public Git) | `9.0.0-dev-preview.7/8` | `github.com/Leviaria/Millenaire` (fork of MoonCutter2B's port; last commit 2026-06-07) | GPL-3.0 | Used as a readable reference. **It lacks the entire combat layer that 9.0.2 has** (no `combat/` package, no raids, no `DefendVillageGoal`, `EngageTargetGoal`, `HuntMonsterGoal` or `HideGoal`, no villager attack targets). |
| Millénaire (other) | — | `github.com/gblfxt/Millenaire-rewrite-1.21.1` | MIT | A separate rewrite lineage. Not the same codebase. Ignored. |

No public source repository for the official 9.x line could be found. MoonCutter2B's GitHub shows no Millénaire repository.

**Three consequences follow:**

1. **Decide which Millénaire build you will actually ship with.** The Git fork and 9.0.2 differ by more than 100 classes. Almost everything in this report about guards, combat and raids exists **only in 9.0.2**. If you build against the Git fork, most military hooks do not exist.
2. **`VillageGuardsManager` does not exist** in 9.0.2, the Leviaria fork or the gblfxt rewrite. I searched both class names and string constants. In 9.0.2, guards are ordinary resident villager types, and their behavior is spread across `VillagerCombat`, `CombatHelper`, `DefendVillageGoal`, `EngageTargetGoal`, `HuntMonsterGoal` and `RaidManager`, all described below. If you saw that name somewhere, please point me to it. It may be from the 1.12 codebase or from another mod such as Guard Villagers.
3. Both production artifacts are **All Rights Reserved** and **neither has a public API**. Every integration below is either data-driven (content packs, configs, registry IDs) or calls into internals that carry no stability promise. The architecture in §8 is designed around that fact.

Class names below are the real fully-qualified names from the JARs (`org.millenaire.*`, `ydmsama.hundred_years_war.*`).

---

## 1. Millénaire 9.0.2 architecture summary

### 1.1 Villages

| Question | Answer (from code) |
|---|---|
| Representation | `org.millenaire.village.Village`, a plain Java object (**not** an entity, block entity or vanilla structure). Fields: `VillageId id`, `cultureId`, `villageTypeId`, `BlockPos center`, `villageName`, `List<BuildingInstance> buildings`, `Map<UUID, VillagerRecord> villagerRecords`, `Map<VillageId,Integer> relations`, `VillageReputation`, `VillageRaidState`, chronicle (≤500 entries), history (≤1000), owner UUID/name (player-controlled villages), `parentVillageId` (hamlets), `MarvelManager`. |
| Village ID | `record VillageId(UUID uuid)`. Stable and persisted. **This is the key to use for our own data.** |
| Lookup | `VillageSavedData.get(ServerLevel).getVillageManager()` → `VillageManager.getVillage(VillageId)`, `getAllVillages()`, `findNearestVillage(BlockPos, maxDist)`, `findVillageContaining(BuildingId)`. There is also the static shortcut `Village.resolve(ServerLevel, VillageId)`. |
| Dimension | **Overworld only.** The server tick listener in `org.millenaire.Millenaire` only ticks `VillageSavedData.get(overworld)`. |
| Boundaries | No polygon or claim. `Village.computeBounds()` returns the AABB of all building footprints plus an 8-block margin. `VillageType.radius()` is the nominal radius. `computeVillageChunks()` gives the force-load set. `getBuildingAt(pos)` / `BuildingInstance.containsPos(pos)` give point-in-building tests. |
| Persistence | `VillageSavedData extends SavedData`, file id `millenaire_villages`. It serializes everything, including villager records, raid state, relations and chronicle. |
| Ticking | A `ServerTickEvent` listener calls `VillageManager.tick(overworld)` every tick. Per village, `tickVillageActivation` force-loads chunks when a player is within `keepActiveRadius` (default 200, `forceLoadVillages=true`). `Village.tick()` runs only while active (construction, integrity every 600 ticks, respawn, panels). `Village.backgroundTick()` **always** runs: nightly diplomacy drift, raid planning, raid ticking, hire expiry. |
| Load/unload | Chunk tickets through `VillageChunkLoader` (a `RegisterTicketControllersEvent` controller). At `ServerStartedEvent` Millénaire loads all content, then `VillageRehydrator.rehydrateAll` and `initVillagerGoals`. |
| World interaction | Villages spawn from a chunk-load queue (`VillageSpawnQueue`, new overworld chunks only). Buildings are placed progressively by `BuildGoal`. Paths are built by `BuildPathGoal`. Walls come from `WallGrowthManager`. Chests are locked and unlocked during raids. Village map markers exist. |
| Spawn conflicts | `world.StructureAvoidance` only avoids vanilla villages and six vanilla structures (outpost, desert pyramid, jungle temple, swamp hut, mansion, igloo). **Modded castles, forts and HYW sites are not avoided.** |

### 1.2 Buildings

* `org.millenaire.building.BuildingInstance` holds `BuildingId`, `planId`, `planSetId`, `origin`, `rotation`, `level` (upgrade tier), `variant` and `Status {PLANNED, UNDER_CONSTRUCTION, UPGRADING, COMPLETE}`. It also exposes `isOperational()`, `isWallSegment()`, `getEffectiveWidth/Depth()` and `BuildingInventory getInventory()` (its chests).
* Typed **special points** are available through `getFirstPointPos(type)` / `getPointsByType(type)`. Examples: `defendingPos`, `shelterPos`, `sellingPos`, `sleepingPos`, `pathStartPos`. These already encode where to defend and where civilians should hide.
* Plans (`BuildingPlan` record) carry string **tags**. `Village.getBuildingsWithTag(tag)` and `getOperationalBuildingsWithTag(tag)` are cached lookups.
* Military-relevant tags in the shipped content: `patrol` (70 plans across all 7 cultures: guardhouses, forts, watchtowers, wall towers), `wall_level_0/1/2` (Indian, Norman, Seljuk walls), `armoury` (12), `training` (Seljuk), `borderpostsign`. Norman military plans include `houses/guardhouse`, `extra/watchtower`, `townhalls/fort`, `townhalls/largefort` (with `largefort_a_barrack` and `largefort_a_armoury` sub-buildings), `marvel/guardroom`, `marvel/largeguardhouse`, and `walls/*` (full wall, tower, gateway, corner, borderposts).
* Walls: `culture.WallType` (data: `wall_type/<culture>/*.json`, for example `norman/stonewalls` and `norman/borderposts`) plus `village.WallGrowthManager`. Each wall piece is a `BuildingInstance` with `isWallSegment()==true`.
* **No repair logic exists.** I found no code that restores damaged blocks in completed buildings. This matters for sieges (see §5.I).

### 1.3 Population, residents, leaders, culture

* One entity type for every villager: **`millenaire:villager`** (`org.millenaire.entity.MillVillager extends PathfinderMob`). It is **not** `Monster`, `Enemy` or `AbstractVillager`. Wall decorations are a second entity type.
* A persistent identity lives in `VillagerRecord` (UUID, villager type, home building, names and family, `VillagerInventory`, killed flag, raid flags, hire state, quest tags, `getMilitaryStrength()`). The record and the entity are synced both ways (`updateFromEntity` / `applyToEntity`).
* Villagers are `setPersistenceRequired()` and `removeWhenFarAway()` returns false, so they **never despawn**. Dead residents are marked killed and **resurrected** at dusk by `VillageIntegrityChecker`, unless their type carries `noresurrect`.
* A culture is a set of data files under `cultures/<culture>/` (culture, reputation, villagers, villages, buildings, shops, traded goods, namelists). Seven cultures ship: norman, indian, japanese, mayan, byzantines, seljuk, inuits. They are held in `culture.ModCultures`.
* Leaders: `MillVillager.isChief()` is a synced flag. `Village.getTownhall()` returns the town hall building. Player-controlled villages have `getOwnerUUID()`.
* **AI is not vanilla.** `MillVillager` runs a private `GoalScheduler` of `VillagerGoal`s (priority-based, with a "combat urgent" flag) from `tick()`. The vanilla `goalSelector` only holds Float, OpenDoor and OpenFenceGate. The goal IDs a villager can run come from its villager-type JSON, plus injected goals (see §1.4).

### 1.4 Existing military and defense (9.0.2)

**Guards are villager types, not a manager.** Example: `cultures/norman/villagers/normalvillagers/guard.json`.

| Field | Value |
|---|---|
| tags | `heavydrinker`, `archer`, `raider`, `helpInAttacks` |
| max_health / base_attack_strength | 60 / 4 |
| default_weapon | `minecraft:wooden_sword` |
| tool_needed_classes | `armour`, `toolssword`, `rangedweapons` |
| goals | idle, socialise, chat, rest, pray, drink_cider, **`millenaire:patrol`** |
| spawn_weight | 0 (they only exist as building residents) |
| hiring_cost | 32 |

The semantics of the villager-type tags come from the `culture.VillagerType` record helpers and `withResolvedGoods()`:

| Tag | Effect |
|---|---|
| `helpInAttacks` | Injects `hunt_monster` and `defend_village`. Counts toward `getVillageDefendingStrength()`. Answers `CombatHelper.callForHelp`. |
| `raider` | Injects `raid_village`. The villager can be sent on raids. |
| `defensive` | Target is dropped once the villager is more than 20 blocks from the town hall `defendingPos`. |
| `archer` | Uses a bow between 5 and 20 blocks. |
| `hostile` | Marks bandit-type Millénaire villagers. Other villagers can hunt them. |
| neither `helpInAttacks` nor `raider` | Injects `hide` (civilians). |

`initGoals()` also adds `engage_target`, `hired_escort`, `get_tool`, `gather_goods` and `light_hearth` to all adults.

| Question | Answer |
|---|---|
| How are guards spawned? | As **residents** of buildings whose plan lists them (`"male": ["guard"]` in `guardhouse.json`; also forts and `largeguardhouse`, which is tagged `autospawnvillagers`). Numbers equal the number of resident slots in built buildings. There is no separate guard spawner. |
| Entity class | `MillVillager`, the same as every other villager. |
| Equipment | `VillagerInventory` is **`Map<Item,Integer>`, so NBT and data components are discarded** (no enchantments, no durability, no TaCZ gun data). Armor slots are virtual: `MillVillager.getItemBySlot()` returns the best owned item from the data-driven `tool_categories` lists (`armourshelmet`, `armourschestplate`, and so on). The weapon comes from `VillagerCombat.getCombatWeapon()`: best owned from `weaponsranged` (archers, when the target is more than 5 blocks away), then `weaponshandtohand`, then the type's `default_weapon`. Items are acquired by `GetToolGoal` from village shops or storage, or from `initial_inventory`. |
| Threat detection | (1) `HuntMonsterGoal` scans 50×10×50 around the town hall for `Monster` (excluding creepers) and `hostile`-tagged MillVillagers. (2) `VillagerCombat.onHurt`: any attacker becomes `attackTarget`, and `CombatHelper.callForHelp` alerts same-village `helpInAttacks` villagers within 80 blocks. (3) During raids, `DefendVillageGoal` targets raider MillVillagers only. (4) `VillagerCombat.triggerMobAttacks` makes nearby `Monster`s target villagers. |
| Player aggression | A player hit costs village reputation (`-10 × damage`) for non-hostile types. There is a **2-hit grace window** (the villager only complains for 6000 ticks). The third hit sets the player as target and calls for help. |
| Fighting | `EngageTargetGoal` (priority 5000). **`resolveTarget()` only accepts `ServerPlayer`, `Monster` or `MillVillager` targets; anything else is silently dropped.** Melee damage is `baseAttackStrength + ceil(weaponDamage/2)` with a 20-tick cooldown. Ranged uses a vanilla `Arrow` plus the `MillenaireBow` bonus, with a 100-tick cooldown. |
| Despawn | Never. Death is recorded in the chronicle and the villager is resurrected later. |
| Persistent identity | Yes (`VillagerRecord` UUID, names, family). |
| Can equipment be influenced externally? | Yes, three ways: (a) `tool_categories` data (see §4), (b) `MillVillager.getInventory().add(item, n)` / `VillagerRecord.getInventory()`, (c) depositing items into the village armoury chests (`BuildingInventory.add`). |
| Promotion or reclassification | **Yes, through public methods.** This is exactly what `ChildBecomeAdultGoal` does: `villager.setVillagerTypeId(t)`, `villager.initGoals(Millenaire.getGoalRegistry(), type)`, `VillagerAppearanceFactory.randomizeAppearance(...)`, then `village.updateVillagerType(uuid, t)`. The target type must exist in content (a custom villager type from our content pack). |
| Military strength | `combat.MilitaryStrength.compute(maxHealth, baseAttack, bestMelee, archerWithBow, armor)`. `Village.getVillageDefendingStrength()`, `getVillageRaidingStrength()` and `getVillageAttackerStrength()` are all public. |
| Extensible from outside? | Partly. Data-driven parts are easy. Behavior changes require replacing registered goals (see §2.1 item 3) or a mixin. |

**Raids.** `org.millenaire.combat.raid.RaidManager` is village-vs-village only.

* **Planning:** each night `attemptPlanNewRaid` runs if a player is within `backgroundRadius` (2000). The chance is `raidingRate` (default 20%). A regular village can only target villages at relation ≤ −90 (`OPEN_CONFLICT`) whose defending strength is below 2× its raiding strength. Bandit lone buildings target anything within `banditRaidRadius` (1500).
* **Execution:** 24,000 ticks after planning, the raid starts. The raiders' `VillagerRecord`s are **cloned** into the target village and the original entities are discarded. Clones materialize at the village edge after 500 ticks, and the fight happens around `defendingPos`.
* **Resolution:** the raid ends when the attackers are dead, or when the defenders are dead and a raider reaches `defendingPos`. `resolveAttackersWin` uses a 1.2 ratio. Afterwards come looting (up to 1024 items), chest locking, chronicle and history entries, and advancements. Timeout is 23,000 ticks.
* **Player control:** controlled villages pick raid targets in `ControlledMilitaryScreen`. There are commands `/millenaire raid …` and `military`.
* **The raid state cannot be faked.** `Village.setUnderAttack(true)` is public, but the next `RaidManager.tickRaid` finds no raider records, clears it (`findAttackerId()==null` → `setUnderAttack(false)`) and cleans up clones. So "civilians hide" (`HideGoal`) cannot be triggered by just setting that flag.

**Other systems:**

* **Diplomacy:** village↔village integer relations from −100 to 100, with labels at ±10/30/50/70/90. Nightly drift in `VillageDiplomacyHelper`, player praise and slander in `DiplomacyHelper`, and per-player and per-culture reputation.
* **Chronicle:** the `VillageEventType` enum is **closed** (FOUNDED, BUILDING_*, BIRTH, DEATH, MIGRATION, and so on; no military types). Free-text `Village.recordEvent(level, String)` goes into history.
* **Quests:** data-driven (`quests/`, global scope), with quest tags on villager records.
* **Economy:** `BuildingInventory` over chests, `Village.getVillageItemCount(item)` and `getVillageTagCount(tag)`, shops, traded goods, imports and exports. **Millénaire's locked chests return a `null` item-handler capability**, so hoppers and other mods cannot pull from them.
* **Hiring:** players can hire villagers with a `hiring_cost`, using `HiredEscortGoal` and an aggressive stance. This is an existing player retinue.
* **Content guide:** Millénaire ships a content guide at `millenaire/docs/content-guide.en.md` inside the JAR. It is the closest thing to a documented contract.

---

## 2. Millénaire extension points

There are **no custom events, no API package, no capabilities, no data attachments and no public registries** meant for other mods. What exists:

| # | Integration point | Where | What it gives | What we can safely do | Needs internals? | Fragility |
|---|---|---|---|---|---|---|
| 1 | **Content sub-mod overlay** | `<gameDir>/millenaire-custom/<pack>/…`, loaded at `ServerStartedEvent` by `content.CustomContentIndex` (documented in the content guide §2–§7) | New or overridden villager types, building plans (`.json`+`.nbt`), village types, shops, traded goods, **`tool_categories` (merged per item with priorities)**, visit goals, gathering types, quests, namelists, sentences, `_disabled.json` | New guard, soldier and veteran villager types; new military buildings; add HYW or other-mod weapons to guard equipment priorities; per-culture doctrine as data | No (file format only) | **Low–medium.** Documented format. Villager, building and village JSONs are REPLACE_FIRST_ALPHA **per file**, so overriding a shipped file such as `guard.json` shadows future Millénaire edits to it. Prefer **adding** new files over replacing. It is not a datapack: our mod must deploy files into `millenaire-custom/<ourmod>/` before server start. Client-side textures need the files on each client. |
| 2 | **Direct Java calls into public classes** | `VillageSavedData`, `VillageManager`, `Village`, `BuildingInstance`, `VillagerRecord`, `MillVillager`, `RaidManager` (`planRaid`, `startRaidForced`, `resolveDefendingPos` are public), `ModCultures`, `Millenaire.getGoalRegistry()` | Full read access to villages, buildings, residents, strengths, relations and raid state; controlled writes (relations, reputation, villager type, inventory, history entries) | Read-mostly adapter; small, validated writes | **Yes** (`compileOnly` against the 9.0.2 JAR) | **High.** No API contract, rapid 9.0.x releases, and the Git fork differs heavily. Keep everything behind one adapter (§8). |
| 3 | **`GoalRegistry` (`register` / `replace`)** | `org.millenaire.goal.GoalRegistry`, reached through `Millenaire.getGoalRegistry()` | Custom `VillagerGoal`s, or **replacing a built-in goal ID** (the class documents `replace()` as "External content overrides built-in goal") | Wrap `millenaire:engage_target`, `hunt_monster` and `hide` with decorators that also handle HYW threats (§6). All guards and civilians get them automatically, because those IDs are injected for everyone. | Yes | **Medium–high.** Timing trap: built-ins are frozen in Millénaire's mod constructor, and `resetToBuiltins()` runs in its `ServerStartedEvent` listener (NORMAL priority), which also initializes the goals of loaded villagers. We must register in a LOW-priority `ServerStartedEvent` listener and then re-run `villager.initGoals(...)` for villagers that are already loaded. Villagers loaded later pick up the registry state through Millénaire's `EntityJoinLevelEvent` handler. Needs a runtime test. |
| 4 | **Vanilla/NeoForge entity events on `MillVillager`** | NeoForge bus: `EntityJoinLevelEvent`, `LivingIncomingDamageEvent`, `LivingDamageEvent.Post`, `LivingDeathEvent`, `LivingChangeTargetEvent` (fired because `VillagerCombat.setAttackTarget` also calls `setTarget`), `EntityTickEvent` | Attack and death facts, target changes, spawns | Incident ledger ("who hit whom first"), kill counts for veterans, marking villagers | No | **Low.** Standard NeoForge. |
| 5 | **NeoForge data attachments on `MillVillager`** | Our own `AttachmentType` | Per-villager military data (rank, kills, class), serialized with the entity | Veteran status, soldier class, last-combat time | No | **Low.** Note that `Village` is a POJO, so it cannot take attachments. Village-level data goes in **our own `SavedData`** keyed by `VillageId.uuid()`. |
| 6 | **Server config** | `MillenaireServerConfig`: `raidingRate`, `banditRaidRadius`, `backgroundRadius`, `keepActiveRadius`, `forceLoadVillages` | Read raid and activation parameters | Read only; document recommended values | No | Low |
| 7 | **Item and block tags** | `millenaire:culture_weapons`, `millenaire:own_right_click`, plus a few block tags | Almost nothing military | — | No | Low (and low value) |
| 8 | Commands | `/millenaire …` (raid, military, villages, query, dev) | Debugging only | Not an integration API | — | — |
| 9 | Networking | Internal payloads only | Nothing | Use our own payloads for UI | — | — |

**Where there is no clean API:** village-level events (founded, building completed, raid started and ended) are not published. The safest alternative is **polling diffs** in our own low-frequency server tick: every N seconds, snapshot each active village's building statuses, record counts and raid fields, then compare to the previous snapshot and emit our own internal events. This needs no mixin, and polling at 20–200-tick intervals is cheap.

---

## 3. Hundred Years War 0.7.1r-fix1 architecture summary

### 3.1 Entities (101 entity types, `hundred_years_war:*`)

**Every HYW humanoid, bandit, undead and siege engine extends `BaseCombatEntity extends PathfinderMob implements BaseCombatSupport, OwnableEntity`.** None extends `Monster` or implements `Enemy`, including `hyw_zombie`, `hyw_skeleton` and all bandits. This single fact decides most of the cross-mod combat analysis.

| Group | Entity IDs (examples) | Class root | Notes |
|---|---|---|---|
| Infantry | `militia`, `warrior`, `spear_man`, `shieldman`, `archer`, `crossbowman`, `handgonne_man`, `matchlock_man`, `iron_mage`, `priest`, `siege_engineer` | `MilitiaEntity`, `WarriorEntity`, … | Unit-role marker interfaces: `LightUnit`, `HeavyUnit`, `RangedUnit`, `CavalryUnit`, `SiegeUnit`, `Counter*`, `NonCombatUnit` |
| Cavalry | `mounted_lancer_rider`, `mounted_light_lancer_rider`, `mounted_archer_rider`, `mounted_matchlock_man`, `cavalry*`, `*_horse`, `hyw_horse`, `hyw_camel` | `Mounted*RiderEntity`, `NpcHorse`, `HywHorseEntity extends Horse` | Mount toggling (`CavalryMountToggleable`) |
| Bandits | `bandit_soldier`, `bandit_soldier_elite`, `bandit_archer`, `bandit_crossbowman`, `bandit_raider`, `bandit_shield_axeman`, `bandit_executioner`, `bandit_knife_thrower`, `bandit_mage`, `bandit_cavalry`, `bandit_mounted_archer`, `bandit_handgonne_man`, `bandit_matchlock_man`, `bandit_demolitionist`, `bandit_beastmaster`, `bandit_war_hound`, `bandit_siege_engineer` | Subclasses of the normal unit classes | **"Bandit" is an entity type, not a faction.** Hostility comes from having **no owner** (§3.3). |
| Desert raiders | `desert_raider_*` (incl. `desert_raider_commander`, `desert_raider_boss`, camel cavalry) | Bandit subclasses | `DesertRaiderCommanderEntity` is a melee unit with a buff aura (20 blocks, every 40 ticks). **It is not a strategic commander.** |
| Undead and fantasy | `hyw_zombie`, `hyw_fast_zombie`, `hyw_skeleton*`, `hyw_wither_skeleton*`, `hyw_giant`, `wood_elf_*` | `BaseCombatEntity` | Recruitable through scrolls |
| Workers | `farmer`, `fisher`, `lumberjack`, `miner`, `breeder`, `craftsman`, `porter` | `BaseWorkerEntity` (`NonCombatUnit`) | Work at HYW workstation blocks, with warehouses and transport orders |
| Siege | `trebuchets`, `mangonels`, `springald`, `cannon`, `bombard`, `great_bombard`, `culverin`, `ribauldequin`, `nest_of_bees`, `battering_ram`, `siege_tower` | `EngineerOperatedSiegeEntity` / `SiegeTowerEntity` (`MultiSeatVehicle`, `CrewOperatedSiegeWeapon`) | Crewed by `SiegeEngineer` units (`AutoMountEngineerOperatedSiegeGoal`). **The battering ram calls `level.destroyBlock`. Projectiles implement `BlockBreakable`** (radius, hardness and count limits). Vanilla explosions use `ExplosionInteraction.NONE`. |
| Misc | `recruitment_flag`, `bow_puppet` / `crossbow_puppet` / `melee_puppet` (player puppets for RTS), `tacz_*` (TaCZ compat), `rocket` | | |

**Per-entity behavior (common to `BaseCombatEntity`):**

* **AI goals** (`registerGoals`): Float, OpenDoor, OpenFenceGate, OpenTrapdoor, `FollowEntityGoal`, `BaseCombatEntityAttackGoal`, `PatrolGoal` (patrol points), `ReturnToHomeGoal`, and a target selector `HywNearestAttackableTargetGoal(LivingEntity, predicate = isValidTarget)`. There is also a commanded-goal queue (`CommandedGoal`, `MoveGoal`, `MoveToBlockGoal`, `FormMoveGoal`, `HoldGoal`, `TargetAttackGoal`).
* **Equipment:** normal vanilla slots, filled from per-unit, per-level JSON: `assets/hundred_years_war/hyw/npc/<entity>/equipment.json` (or `equipment_epic_knights.json`, chosen by config). These are arbitrary item IDs with probabilities and enchantments. **They are loaded with `Class.getResourceAsStream` from HYW's own JAR (`EquipmentDataCache`)**, so datapacks, resource packs and other mods **cannot override them**. The only external switch is the config's equipment file name. At runtime, anyone can call `setItemSlot` on a unit (HYW itself lets players swap equipment).
* **Faction and team:** owner UUID (`OWNER_UUID` synced data; public `setOwnerUUID(UUID)` / `getOwnerUUID()`) plus the relation system (§3.3). `isAlliedTo` = same owner, or the owner player's vanilla scoreboard team.
* **Target selection:** `BaseCombatEntity.isValidTarget(LivingEntity)` checks, in order: cease-fire strategy; temporary retaliation targets; **relation participants** (`ServerRelationHelper.isEnemyRelation`); vanilla `Enemy` mobs; the config target list; tamed animals by owner relation; aggressive null-owner rules; and players and other HYW units by owner relation.
* **Orders:** `AttackStrategy` (including CEASE_FIRE and INDISCRIMINATE), move, attack-move, formation move, follow, hold, patrol points (`getPatrolPoints()` returns the mutable list, persisted in NBT), home position, squads.
* **Persistence:** entity NBT (owner, level, XP, kills, patrol points, skins, formation). Null-owner units can despawn only in "enemy mode" configs.
* **Ownership and recruitment:** player-centric. Scroll items (`scroll_<unit>_<level>`) and recruitment flags (`RecruitmentOrderManager` SavedData) with XP and material costs and unlock conditions (`recruitment/*.json`, also jar-internal).
* **Formation and group behavior:** `FormationManager`, combined formations, `GroupPathingManager.registerEntity(groupId, target, entity)` (shared flow-field-style paths), and `CombatAlertCoordinator` (help calls within 30 blocks, at most 2 recipients, chain chance 50%).
* **Death and despawn:** normal vanilla death with XP sharing. Player-owned units persist.

### 3.2 Military systems: what is actually present in the JAR

| System | Present? | Evidence |
|---|---|---|
| Recruitment | ✅ Player-only | `recruitment/*`, scroll items, recruitment flag entity |
| Armies / squads | ✅ Per-player squads | `selection.SelectionSystem` (`squads.dat`) |
| Commanders (strategic) | ❌ | Only aura-buff "commander" units |
| Factions | ⚠️ Owner-UUID relations and teams (§3.3); no named NPC factions | `RelationSystem`, `TeamRelationData` |
| Orders / RTS control | ✅ | RTS freecam, command staff, `/hyw …` commands, goal queue |
| Patrols | ✅ Point lists | `PatrolGoal`, `getPatrolPoints()` |
| Sieges (as a system) | ❌ No siege state or objective logic | — |
| Siege weapons | ✅ 11 types, with block breaking | `entity/entities/siege/*` |
| Supply | ✅ Abstract | `supply.SupplyManager`: supply sources (PLAYER with 2000 max, SUPPLY_POINT block with 10000 max); units `requiresSupply`; food→supply conversion; `supply.dat` |
| Production | ✅ Worker units plus workstations | Farming, fishing, lumber, mining, breeding and crafting workstations; warehouses; transport orders |
| Settlement management | ⚠️ Light | Building templates (`.hywt`), `BuildingTaskManager`, `PlacedBuildingRegistry`, roads (`RoadNetworkManager`). There is no concept of a town, population or economy loop comparable to Millénaire. |
| Structure generation | ⚠️ A custom system, **not vanilla worldgen** | `generation/*` "simulate then materialize" near players, with hidden-chunk visibility management. Only **2 templates ship**: `ancient_white_tower`, `desert_fortified_oasis`. These are **not** vanilla `Structure`s, so structure tags cannot see them. |
| Natural spawning | ❌ | No biome modifiers or spawn placements. Hostile HYW units come from spawn-point blocks inside HYW templates, from scrolls, or from `/hyw summon`. |
| NPC relationships | ✅ UUID↔UUID HOSTILE / NEUTRAL / FRIENDLY / CONTROL | §3.3 |
| Military progression | ✅ Per-unit levels and XP (equipment tier per level), level cap, unlocks | |
| Autonomous armies, campaigns, invasions | ❌ | Nothing marches on anything without a player's order |

### 3.3 The HYW relation system (the most important finding for integration)

* `ydmsama.hundred_years_war.main.utils.RelationSystem` holds static maps: `getRelation(uuidA, uuidB)` (default NEUTRAL; same UUID means CONTROL), `setRelation(a, b, type)` (HOSTILE is set symmetrically), teams (`createTeam`, `joinTeam`), and damage records. It is saved to **`<world>/relations.dat` only on `ServerStoppingEvent`** (a crash loses changes since startup).
* `ServerRelationHelper.getRelationIdentity(entity)` resolves, in order: a player → its UUID; a `BaseCombatSupport` → owner UUID (null = "null owner"); a tamed animal → owner; **any other entity carrying HYW's identity marker**; summoner or owner reflection; unknown.
* **`EntityRelationOwnerMarkerMixin` mixes `RelationOwnerMarkedEntity` into every `Entity`.** Its methods (`hyw$markRelationOwnerUUID(UUID)`, `hyw$markKnownNullRelationOwner()`, `hyw$clearRelationIdentityMarker()`) are public, and the marker is persisted in entity NBT under `HundredYearsWarRelationIdentity`. **A third mod can therefore give a `MillVillager` an HYW relation identity without touching either mod.**
* `isEnemyRelation(a, b)`: if both owners are null → false. **If exactly one owner is null → true** (null-owner units, such as spawned bandits, are enemies of every identified party). Otherwise the answer is HOSTILE in either direction.
* `isRelationProtected(a, b)`: same owner, or FRIENDLY or CONTROL. HYW's `LivingEntityHurtMixin` **cancels damage** between protected pairs (`shouldCancelFriendlyDamage`) and `TargetingConditionsRelationMixin` blocks targeting them.
* **Escalation:** in `LivingEntityHurtMixin`, if two *identified* parties are NEUTRAL and one damages the other 3 times, `RelationSystem.setRelation(victim, attacker, HOSTILE)` is applied. This is **permanent**; nothing decays it back.
* **Temporary retaliation:** `TemporaryHostileTargetManager.markHostile(unit, attacker)` is public static. It lasts **600 ticks**, is keyed by the unit's **owner UUID** (so the whole owner group, including all null-owner units, retaliates), and is called by `CombatAlertCoordinator.onHurt`.
* **Config target lists:** `config/hundredyearswar/target_list.json5`, `target_blacklist.json5`, `hostile_target_list.json5`, `hostile_target_blacklist.json5`. They take entity **type** IDs or `"namespace:"` wildcards.
* **Null-owner config** (`ServerModConfig` defaults): `nullOwnerUnitsFriendlyToEnemy=false` and `nullOwnerUnitsAggressive=true`. The aggressive rule only applies in "enemy mode", which requires the friendly flag, so **by default null-owner units attack players (and identified parties) and vanilla monsters, but ignore unidentified mobs.**
* **`MobRelationAwareTargetMixin`** (on `Mob.<init>`) adds a `RelationAwareTargetGoal` to every non-HYW mob that has an attack-damage attribute. **`MillVillager` has `ATTACK_DAMAGE`, so every Millénaire villager very likely already carries this HYW goal in its vanilla target selector.** Millénaire ignores vanilla targets for combat, so today it is inert. This needs runtime confirmation (Unknown #1).

### 3.4 Items (222 registered; `hundred_years_war:*`)

* **Melee** (`SwordItem` subclasses): `*_saber`, `*_scimitar`, `*_sickle_sword`, `sickle_sword`, `classy_saber`, `assassin_rapier`, `long_handled_blade` (`LongHandledBladeItem`), `*_pike` / `*_pike_charge` (`PikeItem`), `throwing_knife` (`ThrowingKnifeItem`).
* **Non-sword weapons** (plain `Item`): `*_lance` (`LanceItem`, charge damage), `handgonne`, `matchlock` (+ `bullet`).
* **Armor:** **none for humanoids.** HYW only adds `*_camel_armor` and `desert_round_shield`. Units wear vanilla armor, or Epic Knights armor with the Epic Knights equipment file.
* **Control items:** `command_staff`, `conquerors_staff`, `conquerors_manual`, `building_tool`, `transport_order`, `loot_table_tool`, ~150 `scroll_*` items and siege weapon deploy items.
* **Tags:** HYW defines none.
* Optional compat is built in for TaCZ, Iron's Spells, Epic Fight, Better Combat and Epic Knights.

### 3.5 HYW integration surface

| Hook | Kind | Safe? |
|---|---|---|
| Registry IDs of entities and items | Stable `DeferredRegister` IDs | ✅ Safest |
| `config/hundredyearswar/*.json5` target lists and server config | Plain files | ✅ But the lists are **per entity type**, and all Millénaire villagers share one type (`millenaire:villager`), so this is all-or-nothing |
| `RelationSystem` / `ServerRelationHelper` / identity marker / `TemporaryHostileTargetManager` | Public static Java, mixin-interface methods | ⚠️ Internals, but narrow and highly valuable |
| `BaseCombatEntity` public methods (`setOwnerUUID`, `setAttackStrategy`, `setHomePosition`, `getPatrolPoints`, `setFollowTarget`, `addCustomGoal`, `setRequiresSupply`, formation setters) | Public Java | ⚠️ Internals |
| `SupplyManager.registerSupplySource(uuid, type, max, pos, dim)` | Public static | ⚠️ Semantics untested |
| `GroupPathingManager.registerEntity` | Public | ⚠️ Complex internals |
| `/hyw` commands | Brigadier | ❌ For automation: most commands require a player executor and use the player's look target. `relation set` is usable. |
| Equipment and recruitment JSON | Jar-internal resources | ❌ Not overridable |
| Events, capabilities, API package | — | ❌ None exist |
| Mixins in HYW | 66 common mixins touching `Entity`, `Mob`, `LivingEntity.hurt`, `TargetingConditions`, `NearestAttackableTargetGoal`, `PathNavigation`, `ChunkMap`, `PlayerChunkSender`, the light engine, `EntitySectionStorage`, and more | ⚠️ Very invasive, which raises the conflict surface for us too |

What "closed or no API" means for us: we compile against the exact HYW JAR (`compileOnly`, never bundled), touch only a thin set of public statics and methods, pin the HYW version range, and wrap every call so that a `NoSuchMethodError` or `NoClassDefFoundError` disables the HYW module instead of crashing.

---

## 4. Important classes, entities and registries (quick reference)

**Millénaire 9.0.2**
- `org.millenaire.Millenaire`: `getGoalRegistry()`, content load at `ServerStartedEvent`, tick wiring
- `village.VillageSavedData`: `get(ServerLevel)` (SavedData `millenaire_villages`)
- `village.VillageManager`: `getVillage`, `getAllVillages`, `findNearestVillage`
- `village.Village`: see §1.1; strengths, tags, relations, raid fields, `recordEvent`, `computeBounds`, `getTownhall`, `updateVillagerType`, `adjustReputation`, `getVillageItemCount`
- `village.VillageId` (UUID record), `village.VillagerRecord`, `village.VillageRelations` (thresholds)
- `building.BuildingInstance`, `building.BuildingPlan` (tags), `culture.WallType`, `village.WallGrowthManager`
- `entity.MillVillager` (`millenaire:villager`): `getVillageId`, `getVillagerTypeId`, `getAttackTarget`/`setAttackTarget`, `performAttack`, `ensureCombatWeaponEquipped`, `getNavManager()`, `getInventory()`, `initGoals`, `isChief`, `isHired`
- `entity.VillagerCombat` (private field; behavior described in §1.4), `combat.CombatHelper`, `combat.MilitaryStrength`
- `goal.GoalRegistry`, `goal.VillagerGoal`, `goal.VillagerTask`, `goal.GoalContext` (public record); `goal.impl.EngageTargetGoal`, `HuntMonsterGoal`, `DefendVillageGoal`, `HideGoal`, `RaidVillageGoal` (IDs `millenaire:engage_target`, `hunt_monster`, `defend_village`, `hide`, `raid_village`). `CombatGoalSupport` is **package-private**, but everything it uses is public on `MillVillager` and `VillagerNavDriver`.
- `combat.raid.RaidManager`: `planRaid`, `startRaidForced`, `resolveDefendingPos`, `abortRaidForAttacker`
- `tool.ToolCategoryRegistry`: categories `toolsaxe … toolssword, armours{helmet,chestplate,leggings,boots}, weaponshandtohand, weaponsranged`

**HYW 0.7.1r-fix1**
- `entity.entities.BaseCombatEntity` (8k lines), `entity.utils.BaseCombatSupport`, unit-tag interfaces in `entity.entities.tags`
- `utils.RelationSystem`, `utils.ServerRelationHelper`, `utils.RelationOwnerMarkedEntity`, `utils.CombatTargetingHelper`, `utils.TargetListManager`
- `entity.utils.TemporaryHostileTargetManager`, `entity.utils.CombatAlertCoordinator`, `entity.goals.RelationAwareTargetGoal`, `entity.goals.PatrolGoal`
- `supply.SupplyManager`, `selection.SelectionSystem`, `entity.pathing.GroupPathingManager`, `recruitment.RecruitmentOrderManager`
- `template.PlacedBuildingRegistry` and `simulation.building.SimulatedBuildingRegistry` (SavedData for HYW-placed sites)
- `registry.HywEntityRegistry`, `registry.HywItemRegistry`
- `config.ServerModConfig` (`config/hundredyearswar/`)

---

## 5. Cross-mod integration analysis

### A. Millénaire village → military settlement

**Store our data in our own `SavedData`** (for example `ourmod_military`, overworld), keyed by `VillageId.uuid()`. Village IDs are stable UUIDs, so this is clean and survives Millénaire updates.

| Datum | Source | Difficulty |
|---|---|---|
| Garrison (active soldiers) | Count `villagerRecords` whose type `isHelpInAttacks()`, excluding killed ones. `getVillageDefendingStrength()`. | Easy |
| Reserve militia | Our own classification of non-guard adult types (config), or custom "militia" villager types via content pack | Easy (data) / Moderate (behavior) |
| Garrison capacity | Sum of resident slots of `patrol`- or `armoury`-tagged buildings, via the plan's `male` list in content | Easy–moderate |
| Equipment quality | `MilitaryStrength.scan(record.getInventory())` per guard | Easy |
| Fortification level | Wall segments (`isWallSegment`, `wall_level_*`), towers, forts (§5.D) | Easy |
| Readiness, threat, specialization | Our own computed fields (§5.C) | Easy (they are ours) |

Do **not** try to write these into Millénaire's `Village` NBT. It has no extension slot.

### B. Expanding Millénaire guards

| Idea | Realistic? | How |
|---|---|---|
| Different guard classes (archer, spearman, heavy) | 🟢 as data | New villager-type JSONs in our content pack, with tags (`helpInAttacks`, `archer`, `defensive`, `raider`), `max_health`, `base_attack_strength`, `default_weapon`, `tool_needed_classes`. **Limitation:** Millénaire combat is melee plus vanilla arrows only. There are no shields, reach, lances, guns, mounts or formations. |
| Better equipment | 🟢 | Add items to `tool_categories` with higher priority. Stock armoury chests, or add to `VillagerInventory`. **Only item type matters**, because enchantments and durability are dropped. |
| Culture-specific soldiers | 🟢 | Per-culture villager types and plans in `cultures/<c>/…` |
| Veteran guards | 🟡 | Track kills with `LivingDeathEvent` (killer is a `MillVillager`), then promote with the public `ChildBecomeAdultGoal`-style sequence to a `*_veteran` type |
| Professional soldiers vs militia | 🟢 data / 🟡 behavior | Types plus a decorated engage goal that treats militia as `defensive` |
| Defensive positions | 🟢 | Already exist as `defendingPos` special points in building plans. Our plans can add more. |
| Patrols | 🟢 existing | `millenaire:patrol` visit goal walks between `patrol`-tagged buildings. Route patrols would be 🟡 (a custom goal). |
| Military training | 🟡 | Visit goal to a `training` tag (the Seljuk content already has the tag) plus our XP ledger |
| Garrison management UI | 🟡 | Our screen plus payloads, reading the adapter. Controlling numbers means building more resident buildings, or 🟠 spawning extra records through `VillagerSpawnFactory` (internal, risky). |
| New guard AI behaviors | 🟡 | Only through `GoalRegistry.replace` decorators or new goals referenced from our own villager types |

### C. Military progression

This is feasible **as a computed overlay**, because Millénaire exposes enough. An example ladder, with each tier gated on operational buildings:

| Tier | Requirement |
|---|---|
| Guard Post | ≥1 building tagged `patrol` |
| Barracks | Our plan ID or tag `ourmod_barracks`, or `norman:largefort_a_barrack` |
| Training Ground | Tag `training` |
| Armoury | Tag `armoury` |
| Fortified Settlement | Wall segments of `wall_level_1+` covering X% |
| Military Center | Fort town hall (`fort` / `largefort`) + armoury + ≥N guards |

It can be combined with population (`villagerRecords`), wealth (`getVillageItemCount`) and culture.

What we **cannot** easily do is make Millénaire *prefer* building military structures. Growth is driven by the village type's `layout` and priorities in content files. The only way to influence it is our own village types or plan priorities, which are REPLACE overrides of shipped files. Treat Millénaire's growth as the source of truth, and let our tier **react** to it rather than drive it.

### D. Fortification

| Class | Data |
|---|---|
| **Easy (direct from Millénaire)** | Count and levels of wall segments; `wall_level_*`, `patrol` (towers), gateway plan sets from `WallType`; forts (`townhalls/fort`, `largefort`); guard buildings; village radius and bounds; building `level` (upgrade tier); `isOperational()`; number of `defendingPos` points |
| **Moderate (world scan)** | Actual wall block integrity (compare the plan footprint to current blocks; notices siege damage); wall height; gate presence and open or closed state; terrain height delta around bounds; water or cliff moats (heightmap sampling over the bounds ring) |
| **Hard** | Enclosure completeness (is the perimeter closed, and do paths bypass the walls?), line-of-sight coverage from towers, and path-based attack routes. These need geometry and pathfinding analysis. Skip them. |

### E. HYW equipment for Millénaire guards (and vice versa)

* HYW items are registered through a normal `DeferredRegister` with stable IDs (`hundred_years_war:iron_saber`, …).
* **Millénaire guards can use any `Item` as a melee weapon.** Damage comes from the item's `ATTACK_DAMAGE` attribute modifier (`CombatHelper.weaponDamage`). HYW sabers, scimitars, sickle swords, pikes, rapier and long-handled blade are `SwordItem`s, so they work. **Lances, handgonnes, matchlocks and throwing knives will not work as intended**: Millénaire only swings in melee or shoots vanilla arrows from `BowItem`s. Special mechanics (pike reach, lance charge, firearm projectiles) are not reproduced.
* **Armor** has to be an `ArmorItem`. HYW has no humanoid armor, so use vanilla or Epic Knights armor.
* **Selection mechanism:** use the **Millénaire `tool_categories` content file** (priority per item ID) shipped in our content pack. It is data-driven, merge-friendly and survives updates. Prefer this to hardcoded Java item IDs. For our own logic, define **our own item tags** (`ourmod:military/melee_tier_2`, …) with `"required": false` entries, so missing mods do not break loading.
* **HYW units using arbitrary equipment:** only by runtime `setItemSlot` (the unit JSON cannot be overridden). HYW's equipment-by-level JSON would re-roll only on HYW's own reinitialization paths. Unknown #6.
* Do not duplicate weapons. Reference HYW items by ID. If HYW is absent, our tags simply resolve empty.

### F. Raids and military events

What exists is Millénaire's village-vs-village raids (above). With public API:

| Event | Feasibility | How |
|---|---|---|
| Push two villages into raids | 🟢 | `adjustRelationSymmetric` down to ≤ −90, and Millénaire plans raids by itself. Or call `RaidManager.planRaid` directly. |
| Counter-raids | 🟡 | Poll `getRaidsSuffered()` / raid end, then plan a raid back, subject to raider strength |
| Scouting parties, patrols between villages | 🟠 | Millénaire has no "go outside the village" military task. Needs our own goal plus long-distance navigation over possibly unloaded chunks. |
| Organized raids with HYW units | 🟠 | Our own event: spawn null-owner or faction-owned HYW units at the village edge (the way Millénaire materializes clones), give them a move order, and drive the outcome from our ledger. Millénaire will **not** recognize it as a raid, so civilians will not hide unless our `hide` decorator does it, and its chronicle and looting will not trigger. |
| Reinforcement parties | 🟠 | Same machinery as above |
| Army camps | 🟠 | Needs a structure (vanilla template or ours), a persistent group record, and spawning on load |
| Attacks on strategic settlements | 🟠 | Requires the site index (§6) plus the virtual army simulation (§5.G) |

Millénaire's raid code path cannot be extended to non-`MillVillager` raiders. Clones are `VillagerRecord`s by design, and the check "attackers alive" only counts records. Doing so would need a mixin into `RaidManager`, and it is **not recommended**.

### G. Army movement

HYW **has** group movement primitives, but no autonomous armies: move and attack-move, formation moves, squads, `GroupPathingManager` shared paths, patrol points, follow. All of it is player-issued.

* A Millénaire settlement as a *target* for a player's HYW army already works physically. The player can march units to a village. The only missing parts are the aggro rules (§6) and the consequences (reputation and relations), which are cheap to add.
* Autonomous NPC armies moving between settlements: the hard part is **not** HYW's pathing but **chunk loading**. Entities do not tick in unloaded chunks, and Millénaire only keeps chunks loaded within 200 blocks of players. Real-entity marching across the map means force-loading corridors (a performance and grief risk). The workable pattern is Millénaire's own raid pattern: a **virtual army** (a data record with position, strength, composition and speed) advances in our SavedData tick, and **materializes** as real HYW entities only when it enters a loaded area near players, then de-materializes back to data. That is 🟠: a substantial subsystem, but self-contained. Waypoints and roads can reuse HYW's road network or just straight-line and heightmap stepping.

### H. Battles

HYW handles *unit-level* group combat (targeting, help calls, formations, counters, morale-like tags), and Millénaire handles villager combat. Neither has a "battle" object (sides, objectives, outcome).

* Feeding Millénaire guards into HYW combat: done by the **relation bridge** (§6). Once guards have an HYW identity, HYW units treat them as friend or foe, and Millénaire guards need the engage decorator to fight back. Result: mixed local battles work with no replacement of either AI.
* A "battle" abstraction (sides, casualties, winner, aftermath) is ours to build as a ledger over `LivingDeathEvent` in an area and time window. 🟡

### I. Sieges

| | HYW | Millénaire |
|---|---|---|
| Provides | 11 crewed siege engines, siege engineers, block breaking (ram, projectiles), siege towers carrying passengers | Walls, towers and gates as buildings; `defendingPos` and `shelterPos`; raid flow and chest locking |
| Missing | Siege objectives or state; AI that chooses to besiege anything | Any response to wall damage; **no repair**; no gate closing; no concept of siege |

They can be connected only through our layer.

**Minimum viable siege expansion (recommendation):** a scripted "Siege Event". A virtual attacking force materializes near a village when the player is nearby. It brings one engine (a battering ram at the nearest gateway `BuildingInstance`, or a mangonel with block breaking **disabled** at first). Our threat state makes civilians hide and guards hold `defendingPos`. Victory or defeat is decided by our ledger, and the result is written to Millénaire as a free-text history entry plus relation and reputation changes. The **block-damage policy must be decided first**, because Millénaire will not repair walls. Either make siege damage cosmetic or temporary (our mod records broken blocks and restores them later), or accept permanent damage.

### J. Logistics

* Village economy data: `Village.getVillageItemCount(item)`, `getVillageTagCount(tag)` and `BuildingInventory.add/remove` (public) give real stock levels and let us consume goods.
* HYW supply: `SupplyManager.registerSupplySource(UUID, SUPPLY_POINT, max, pos, dim)`. Units that `requiresSupply` draw from sources within range. Food converts to supply.
* **Concept:** village food stock → our "garrison upkeep" (consume N food per day from village chests) → register or refresh an HYW supply source at the town hall with a value derived from the stock. This is **technically realistic** (🟡). It needs only public statics, but the semantics of non-player sources (owner, range, how units choose sources) must be tested (Unknown #9).
* Physical supply wagons or caravans: 🔴/🟠. There are no wagons in either mod. Porters and transport orders are HYW's intra-base logistics, not overland convoys.

---

## 6. Cross-mod combat and aggro integration

### 6.1 What happens today with both mods and no bridge (derived from code)

| Scenario | HYW side | Millénaire side | Net result |
|---|---|---|---|
| Player attacks an HYW NPC | `CombatAlertCoordinator.onHurt` → `markHostile` (600 ticks, whole owner group) plus help calls | — | ✅ HYW retaliates |
| Millénaire guards help the player against that NPC | — | Guards learn nothing (`callForHelp` only fires when a *villager* is hurt). Even if targeted, `EngageTargetGoal` rejects non-`Monster` targets. | ❌ Guards ignore it |
| HYW NPC attacks a Millénaire villager first | Depends on HYW targeting (below) | `VillagerCombat.onHurt` sets `attackTarget` and calls for help, but **no goal engages a non-Monster target** | ❌ Villagers "notice" but stand still |
| Null-owner HYW units (spawned bandits) vs villagers | Unmarked villagers are not relation participants, not `Enemy` and not listed, so they are **ignored** (default config) | `HuntMonsterGoal` only hunts `Monster` | Both sides ignore each other (unless config lists `millenaire:villager` as a hostile target, which makes *all* villagers targets) |
| Player-owned HYW units vs villagers | Ignored (no identity) | — | Neutral |
| Civilians during HYW attacks | — | `HideGoal` only runs during a real Millénaire raid | ❌ Civilians keep working |

### 6.2 Bridge design (least invasive; no replacement of either mod's combat AI)

**1. Identity bridge (HYW side, public API):**
* Each Millénaire village gets a synthetic *faction UUID*. Use a deterministic name-based UUID from the `VillageId`, stored in our SavedData.
* Register it with a readable name through `RelationSystem.getOrCreatePlayerRelationData(uuid, villageName)`. It will otherwise show as "Unknown" in HYW's relation UI.
* On `EntityJoinLevelEvent` for a `MillVillager` with a `villageId`, call `((RelationOwnerMarkedEntity) villager).hyw$markRelationOwnerUUID(factionUuid)`.
* Effects that fall out automatically:
  * Null-owner HYW units (bandits) now see villagers as enemies (`isEnemyRelation(null, faction) == true`). **This is "inherent hostility" for free.**
  * Player-owned HYW units stay NEUTRAL unless the relation says HOSTILE.
  * HYW friendly-fire protection applies between FRIENDLY or CONTROL pairs.
  * HYW's already-injected `RelationAwareTargetGoal` on each villager starts setting the villager's **vanilla** target to HYW enemies, which is a free detection signal.
* Bandit-type Millénaire villagers (`VillagerType.isHostile()`) and raid clones (`isRaiderEntity()`) should get either **no** marker or a separate "outlaw" faction UUID.

**2. Engagement fix (Millénaire side, `GoalRegistry.replace`):**
* Replace `millenaire:engage_target` with a decorator. It delegates to the original for Player, Monster and MillVillager targets. For other `LivingEntity` targets, it applies our policy: HYW units, and optionally any entity in a configurable list or tag. It then runs the same pursue-and-attack sequence through **public** `MillVillager` methods (`ensureCombatWeaponEquipped`, `performAttack`, `getNavManager().navigateToCombatTarget`).
* Replace `millenaire:hunt_monster` with a decorator. It adds "hostile HYW units inside village bounds" (our threat tracker) to the huntable set, and keeps `helpInAttacks` gating and Millénaire's 50-block hunt zone.

**3. Civilians flee:** replace `millenaire:hide` with a decorator. `canStart` = original **or** (our threat tracker reports hostile HYW units inside `computeBounds()`). Our task navigates to the town hall `shelterPos` with public calls. Civilians never fight, because they lack `helpInAttacks`, so the engage decorator's policy excludes them.

**4. Incident ledger (ours; NeoForge `LivingIncomingDamageEvent`, observed before either mod reacts):**
* Record `(attacker faction, victim faction, time, first-strike flag)` per encounter.
* **"Attacked first":** the first recorded hit between two factions within an encounter window.
* **"Defending an ally":** the attacker's current target (`Mob.getTarget()` or `BaseCombatEntity.getHywTarget()`) is a village resident or a player with village reputation ≥ threshold, and the incident is inside village bounds.
* **"Inherently hostile":** null-owner HYW unit (`getOwnerUUID()==null`), or a configurable entity-type list or tag (`ourmod:inherently_hostile`: `hundred_years_war:bandit_*`, `desert_raider_*`), or Millénaire `hostile`-tagged types.
* From the ledger we drive temporary hostility on both sides: HYW with `TemporaryHostileTargetManager.markHostile`, and Millénaire with `villager.setAttackTarget` on helpers.

**5. Expiry and reset:**
* HYW temporary hostility already expires after 600 ticks.
* **HYW's permanent 3-hit escalation must be counteracted.** Our ledger stores escalations it caused, and a daily decay sets `RelationSystem.setRelation(faction, player, NEUTRAL)` when our rules say so. This triggers HYW's hostile→neutral immunity window.
* Millénaire `attackTarget` clears on death, at more than 80 blocks, or in Peaceful. Our decorator also drops targets whose ledger entry has expired.
* Millénaire reputation loss for hitting villagers is Millénaire's own design. Leave it.

**Minimal illustration of the identity bridge.** This is only a snippet showing the integration point, not an implementation:

```java
// On EntityJoinLevelEvent (server), only when both mods are present:
if (entity instanceof MillVillager v && v.getVillageId() != null
        && entity instanceof RelationOwnerMarkedEntity marked) {
    marked.hyw$markRelationOwnerUUID(factions.uuidFor(v.getVillageId()));
}
```

**Feasibility: 🟡 overall.** It needs no mixins and no replacement of either AI. The risks are the goal-registry timing and HYW internal API drift.

A zero-code alternative exists but is too coarse: put `millenaire:villager` in HYW's `hostile_target_list.json5` (bandits attack every villager, including other bandits and civilians) or in the blacklist. Useful only as a fallback.

---

## 7. Additional world and structure mod integration

| Approach | Verdict |
|---|---|
| **Structure tags** (our own `ourmod:sites/castle`, `…/fort`, `…/tower`, `…/ruin`, `…/military_camp`, `…/town`) with `{"id": "othermod:castle", "required": false}` entries, filled by datapack or config | ✅ **Primary.** NeoForge 1.21.1-native, no hard dependencies, and users and modpack makers can extend them. |
| Lookups: `ChunkEvent.Load` → `chunk.getAllStarts()` / `getAllReferences()`, `structureManager().getStructureWithPieceAt(pos, tag)`, and `getAllStructuresAt(pos)` | ✅ Index discovered sites into our SavedData (bounding box, structure ID, tags). Avoid `findNearestMapStructure` on the main thread in loops (expensive). |
| Structure registry (`Registries.STRUCTURE`) with name heuristics (`*castle*`, `*fort*`) | ⚠️ Only as an optional, opt-in auto-classifier that writes a suggested tag list to a log |
| POI types | ❌ Only for POI blocks (beds, workstations). Not structures. |
| Block scanning | ⚠️ Last resort for untagged or player-built forts. Throttled, chunk-local, config-limited. |
| Configurable structure IDs | ✅ As a config mirror of the tags for non-datapack users |

**Caveats:**
* **Millénaire villages and HYW sites are not vanilla structures.** Neither appears in structure tags. Our site index therefore needs **providers**: `VanillaStructureSiteProvider`, `MillenaireSiteProvider` (adapter), `HywSiteProvider` (via `PlacedBuildingRegistry` / `SimulatedBuildingRegistry`, internals), and `ScanSiteProvider`.
* Millénaire's `StructureAvoidance` only avoids vanilla structures, so **Millénaire villages may overlap modded castles.** Our mod can detect and report overlaps, but not prevent them without a mixin.

---

## 8. Recommended architecture for the separate mod

```
ourmod/
  core/            lifecycle, scheduler (throttled server ticks), SavedData root, internal event bus
  faction/         Faction model (UUID, kind: VILLAGE | PLAYER | TEAM | OUTLAW | EXTERNAL), relations overlay, incident ledger
  settlement/      Settlement abstraction (id, bounds, center, culture/kind, provider), SiteIndex
  military/        MilitaryProfile per settlement (tier, garrison, readiness, threat), classes, veterans, progression rules (data-driven)
  fortification/   Fortification scoring (provider data + optional world scan)
  integration/
    millenaire/    MillenaireAdapter (ONLY place importing org.millenaire.*), content-pack deployer,
                   goal decorators (engage/hunt/hide), polling diff → internal events
    hyw/           HywAdapter (ONLY place importing ydmsama.*), identity bridge, temp-hostility bridge,
                   supply bridge, unit spawning helpers
    generic/       Structure-tag providers, block-scan provider, item/entity tag policies
  client/          Village military screen, overlays (read-only from synced payloads)
  config/          Common/server config, military rule JSON (datapack), tags
```

**Optional-dependency rules:**
* `neoforge.mods.toml`: declare `millenaire` and `hundred_years_war` as `type="optional"` with **pinned** version ranges (for example `[9.0.2,9.0.3)` and HYW `0.7.1r-fix1`). Watch Maven version ordering for HYW's `r-fix1` strings, and verify it in dev.
* Build: `compileOnly files("libs/millenaire-9.0.2.jar", "libs/HundredYearsWar-….jar")` (local, **never redistributed**), with `runtimeOnly` only in dev run configs. Both are All Rights Reserved: never bundle them or copy decompiled code into our sources.
* Entry: each integration has a `…Integration` class loaded **only** when `ModList.get().isLoaded(id)` is true. Everything else in the mod talks to it through interfaces in `settlement/`, `faction/` and `military/`, so core classes never reference foreign classes and the mod loads without either dependency.
* Wrap each adapter call in a guard that catches `LinkageError` (`NoSuchMethodError`, `NoClassDefFoundError`, `IncompatibleClassChangeError`), logs once, and disables that integration.
* If a mixin ever becomes unavoidable, use a separate mixin config per integration with `required: false` and an `IMixinConfigPlugin` that checks `LoadingModList` for the target mod. Current recommendation: **zero mixins for the first milestones.**
* Our own data lives in our SavedData or attachments, not in HYW's `relations.dat` or Millénaire's files. At server start we **re-apply** our view (faction UUIDs, relations) into HYW's `RelationSystem`, so a crash that loses `relations.dat` changes self-heals.
* The content pack ships inside our JAR (`/millenaire_pack/…`) and is copied to `millenaire-custom/ourmod/` at `ServerAboutToStartEvent` (before Millénaire reads it at `ServerStartedEvent`) when Millénaire is present. Include a version marker file, and only add files. Do not REPLACE shipped Millénaire files.
* With Millénaire absent, the Millénaire-specific features disappear, but structure-tag sites, HYW-unit logic and factions still run. With HYW absent, the Millénaire features still work; HYW items are missing from optional tags and the identity bridge is off.

---

## 9. Feature feasibility classification

Legend:
* 🟢 clean as a separate mod
* 🟡 possible, but substantial work or relies on questionable internals
* 🟠 invasive hooks, complex AI or pathfinding, or heavy reverse engineering
* 🔴 effectively a rewrite, or severe compatibility problems

The **Possible / Practical / Safe / Worth** columns use Y / ~ / N.

| Feature | Rating | Possible | Practical | Safe | Worth | Notes |
|---|---|---|---|---|---|---|
| Expanded village guards (data) | 🟢 | Y | Y | Y | Y | Content pack villager types, plans, tool categories |
| Expanded guards (new behaviors) | 🟡 | Y | ~ | ~ | Y | Goal decorators; registry timing |
| Military classes | 🟢 | Y | Y | Y | Y | Limited to melee and bow |
| Culture-specific doctrine | 🟢 data / 🟡 behavior | Y | Y | Y | Y | |
| Military progression (computed tiers) | 🟢 | Y | Y | Y | Y | Reacts to Millénaire growth; cannot steer it cleanly |
| Garrison management (view and policy) | 🟡 | Y | Y | ~ | Y | Adding soldiers beyond resident slots is 🟠 |
| Military buildings | 🟢 | Y | ~ (authoring effort) | Y | Y | Millénaire plan format plus NBT |
| Fortification ratings | 🟢 easy / 🟡 scan | Y | Y | Y | Y | |
| Patrols (existing building-to-building) | 🟢 | Y | Y | Y | Y | Already in Millénaire |
| Patrol routes and inter-village patrols | 🟠 | Y | ~ | ~ | ~ | Chunk loading, custom nav |
| Recruitment (village → HYW units for players) | 🟡 | Y | Y | ~ | Y | Spend village goods or reputation, then spawn an HYW unit with `setOwnerUUID(player)` |
| HYW equipment on guards (melee swords) | 🟢 | Y | Y | Y | Y | Via `tool_categories` |
| HYW lances or guns on guards | 🔴 | ~ | N | N | N | Millénaire cannot use them; would mean rewriting guard combat |
| Raids (Millénaire village vs village) | 🟢 | Y | Y | Y | Y | Drive through relations or `planRaid` |
| Raids by HYW forces on villages | 🟠 | Y | ~ | ~ | Y | Our event plus materialization |
| Counter-raids | 🟡 | Y | Y | ~ | Y | |
| Army camps | 🟠 | Y | ~ | ~ | ~ | |
| Army movement (near players) | 🟡 | Y | Y | ~ | Y | HYW move orders through public methods |
| Army movement (world-scale) | 🟠 real entities → 🔴 / virtual armies 🟠 | Y | ~ | ~ | ~ | Use virtual-then-materialize |
| Commanders (strategic AI) | 🟠 | Y | N (early) | ~ | ~ | Build later on top of virtual armies |
| Battles (local, mixed units) | 🟡 | Y | Y | ~ | Y | After the aggro bridge |
| Battle outcomes / aftermath (ledger, loot, reputation, history) | 🟡 | Y | Y | Y | Y | The chronicle enum is closed, so use history text |
| Supply wagons | 🔴 | ~ | N | ~ | N (now) | New entity plus overland logistics |
| Logistics (village stock → HYW supply) | 🟡 | Y | ~ | ~ | Y | Needs testing |
| Prisoners | 🟠 | Y | ~ | N | ~ | Conflicts with Millénaire resurrection and records |
| Ransom | 🟠 | ~ | ~ | ~ | ~ | Depends on prisoners; could be abstract |
| Occupation of settlements | 🔴 | ~ | N | N | N | Culture and residents are bound to the village. Player takeover via `setOwner` is maybe 🟠 but untested. |
| Territory (our overlay map) | 🟡 | Y | Y | Y | ~ | Does not change either mod's behavior unless bridged |
| Dynamic wars (between Millénaire villages) | 🟡 | Y | Y | ~ | Y | War state machine that sets relations ≤ −90, so Millénaire raids follow |
| Dynamic wars (with armies and sieges) | 🔴 now | | | | | After virtual armies |
| Diplomacy bridge (Millénaire ↔ HYW relations) | 🟡 | Y | Y | ~ | Y | Faction UUID mapping |
| Player factions | 🟡 | Y | Y | ~ | ~ | **Reuse HYW teams**, don't rebuild |
| Player armies | 🟢 (exists) | — | — | — | — | HYW already does this; just integrate consequences |
| Sieges (minimum scripted event) | 🟠 | Y | ~ | ~ | Y | Block-damage policy first |
| Sieges (full system) | 🔴 | | | | | |
| Siege weapons | 🟢 exist in HYW | — | — | — | — | Using them on villages causes permanent damage (no repair) |
| Castle integration (recognize) | 🟢 | Y | Y | Y | Y | Structure tags |
| Castle integration (garrison or ownership) | 🟠 | Y | ~ | ~ | ~ | |
| Worldgen structure integration | 🟢 recognize / 🟡 index | Y | Y | Y | Y | |
| Cross-mod aggro bridge | 🟡 | Y | Y | ~ | **Y (foundational)** | §6 |

**Where I would say no:**
* Taking over or occupying Millénaire villages.
* Making Millénaire guards use firearms or lances.
* World-scale real-entity armies.
* Supply wagons before a virtual-army layer exists.
* Anything that overrides shipped Millénaire villager or building JSONs. That is REPLACE semantics: your pack silently freezes those files against future Millénaire fixes.

---

## 10. First proof-of-concept milestone

The milestone is called **M1, "Garrison Ledger"**. It proves the architecture with no mixins and no JSON overrides.

1. **Detect villages:** `MillenaireAdapter` enumerates `VillageSavedData.get(overworld).getVillageManager().getAllVillages()` at server start and every 200 ticks (active villages only).
2. **Persistent military data:** our `SavedData` keyed by `VillageId.uuid()`. Fields: faction UUID, tier, garrison count, defending strength (`getVillageDefendingStrength()`), fortification score, last-updated tick. It survives restarts and is independent of Millénaire's files.
3. **Detect buildings:** operational buildings with tags `patrol`, `armoury`, `training`, `wall_level_*`, wall segments, and fort town halls. Compute the tier and fortification score from §5.C and §5.D, and log diffs as internal events ("Guard Post reached").
4. **Extend guards (pick one or both):**
   * (a) *Data-only:* deploy a tiny content pack that adds `hundred_years_war:iron_saber` and `iron_scimitar` to `weaponshandtohand`, and one new villager type `ourmod_veteran_guard` per test culture (start with Norman only).
   * (b) *Runtime:* `LivingDeathEvent` kill counter; at N kills, promote the guard to `ourmod_veteran_guard` through the public promotion sequence.
5. **Use HYW:** the identity bridge (§6.2.1) marks villagers with the village faction UUID and names that faction in `RelationSystem`. **Demonstrate:** `/hyw summon` a null-owner `bandit_soldier` near a village, and it now attacks villagers. Add the `engage_target` decorator (§6.2.2), and guards fight back. This single demo exercises detection, faction mapping and the goal-registry timing, which are the three riskiest assumptions.
6. **Display:** `/ourmod village info` (nearest village: tier, garrison, strength, fortification, faction relation to the player), plus a minimal client screen or chat panel through our own payload.

**Suggested split:**
* **M1a** = steps 1–3 and 6. All low risk, and proves Millénaire reading and persistence.
* **M1b** = steps 4 and 5. It proves the two fragile hooks. If M1b fails, you learn early, and cheaply, that the behavior-level integration must be redesigned.

---

## 11. Compatibility risks (summary)

1. **API drift in both mods.** 9.0.x ships frequently and is ARR with no API. The Git fork already diverges massively. HYW is at 0.7.x. Mitigation: pin versions, one adapter per mod, `LinkageError` guards, and a smoke test on every update.
2. **Which Millénaire build** (9.0.2 JAR vs Git fork). The combat hooks only exist in 9.0.2.
3. **Goal-registry timing:** `resetToBuiltins` on every server start, and goals already initialized for loaded villagers.
4. **HYW relation side effects once villagers are identified:**
   * Permanent 3-hit NEUTRAL→HOSTILE escalation.
   * HYW damage cancellation between protected pairs, which may interact with Millénaire reputation and hire behavior.
   * Synthetic UUIDs appear in HYW's relation UI.
   * Relations are saved only on clean shutdown.
5. **Performance:** HYW's relation-aware target scan on every villager every 20 ticks is already present, and marking makes it active. Add our own scans on top. Throttle everything and cap per tick.
6. **Chunk loading:** Millénaire force-loads around villages within 200 blocks of players, so HYW fights can happen offscreen but still near players. HYW has aggressive chunk-pipeline mixins (hidden chunks, generation priority), and their interplay with Millénaire's chunk-triggered village spawning is unknown.
7. **Pathing:** HYW mixes into `PathNavigation`, and Millénaire uses its own `MillPathNavigation` subclass. The interaction is untested.
8. **Permanent world damage** from HYW siege engines and demolitionists (3.5 explosion with MOB interaction) on villages that never repair.
9. **Structure overlap:** Millénaire ignores modded and HYW structures when placing villages.
10. **Item data loss:** Millénaire inventories drop components, so enchanted or modded-data gear given to guards becomes plain items.
11. **Licensing:** both are ARR. Do not redistribute or copy code. Mixins into ARR mods are legally and ethically gray, so prefer public calls, and consider contacting the Millénaire team (Discord) and ydmsama about a small official API (events for village founded, building completed, raid started and ended; a faction hook).
12. **Multiplayer content:** content-pack textures under `millenaire-custom/` must also exist on clients.

---

## 12. Unknowns that need a real NeoForge dev environment

1. Does HYW's `MobRelationAwareTargetMixin` actually add `RelationAwareTargetGoal` to `MillVillager`? Are attributes present at `Mob.<init>` return? Once a villager is marked, what does its vanilla target do?
2. Does marking villagers make HYW cancel any Millénaire-originated damage (villager arrows, raids between two marked villages of different factions)?
3. Does `CultureLoader.validateAll` reject villager types that reference goal IDs we register late? Does `GoalRegistry.replace` of `engage_target`, `hunt_monster` and `hide`, plus re-running `initGoals`, behave correctly for loaded and later-loaded villagers?
4. Is `EntityJoinLevelEvent` the right moment to mark villagers? Villager ID and `villageId` must already be set; raid clones are spawned through `VillagerSpawnFactory`.
5. Do HYW units owned by a **non-player synthetic UUID** (`setOwnerUUID(faction)`) behave sanely? Check follow, supply, worship, UI, `getOwner()==null` paths and despawn.
6. When does HYW re-roll equipment from its JSON? Would our `setItemSlot` changes on HYW units persist?
7. Does `GetToolGoal` ever acquire HYW items without our mod stocking shops? Which buildings does it search?
8. Balance: Millénaire guard (60 HP, attack 4 + weapon/2) vs HYW militia and bandits (levels, armor, reach, shields).
9. `SupplyManager` semantics for non-player sources (range, ownership, consumption).
10. Can `Village.setOwner` safely convert an NPC village to player control (the "occupation" question)?
11. Interaction between HYW hidden-chunk / structure materialization and Millénaire village spawning or rendering.
12. HYW `PathNavigationMixin` effect on `MillPathNavigation`.
13. `relations.dat` behavior on crash, and client sync of non-player relation entries.
14. Whether `hyw$` marker methods are callable across the module and classloader boundary exactly as decompiled (expected: yes, interface is public).
15. Whether Millénaire's resurrection resets anything we attached to the entity (attachments on a re-spawned entity are **new**, so key our veteran data by record UUID in SavedData).
16. Dedicated-server behavior of the content-pack deployment path and `millenaire-custom` permissions.
17. Performance with ~10 villages × 30 marked villagers × several HYW squads.
18. The actual origin of "`VillageGuardsManager`".
