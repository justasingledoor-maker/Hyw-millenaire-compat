# M2 report: village defense doctrine

**Result:** Village defense is now a data-driven, deterministic doctrine system built on the frozen M1.1 foundation.

- **Dedicated server:** 70/72 checks pass with Millénaire 9.0.2 and HYW 0.7.1r-fix1.
  - Every functional check passes: all M1/M1.1 regression checks and all new M2 checks.
  - The 2 failures are the performance targets: a profile-refresh slice exceeds 2 ms in its tail (§5).
- **JUnit:** 56/56.
- **Optional integrations:** 3/3.

The behavior contract you set is preserved and tested:
- A player-owned HYW force walking through a village is not attacked (I1, B).
- An unowned unit that attacks no one is not attacked either (N3, the proactive=false contract).
- HYW units that actually attack residents are fought by the village's defenders (I2–I4, C1–C4).
- Village ↔ player and village ↔ owner stay NEUTRAL (I5, I6, D5).

M3 has not been started.

---

## 1. Commits and artifact

| Commit | Content |
|---|---|
| `35394cd` | M2-0: `classify` package moved under `military/` (non-behavioral; JUnit 23/23) |
| `59a71ac` | M2: doctrine, profile, defense coordination, commands, ledger format 3, harness |
| (this commit) | M2 report and test evidence |

**Artifact:** `build/libs/hywmill-0.1.0-m1.jar`, built from the M2 commit and attached in chat as `hywmill-0.1.0-m2.jar`. The file name still carries the placeholder version from `gradle.properties`, and the mod ID is `hywmill`, unchanged.

---

## 2. What was built (maps to your spec)

```
military/classify/   BuildingRole, VillagerRole, RoleTable, RoleClassifier, RoleTableLoader, RoleTables   (moved, unchanged)
military/doctrine/   Doctrine, DoctrineField, DoctrinePatch, DoctrineDefaults, DoctrineResolver,
                     DoctrineOverride, DoctrineLoader, DoctrineTables, MilitiaPolicy, AssistMode, ControllerAssist, TierModifier
military/profile/    MilitaryProfile, ProfileCalculator
military/defense/    AlertState, AlertStateMachine, DefenseCoordinator, ShelterPolicy, AssistPolicy, DefenseArea,
                     DefenseService, VillageDefenseState, DefenseStats, DefenseStatsRecorder
core/                PerfCounters, MilitaryCommands (+ HywMillRuntime owns DefenseService and PerfCounters)
integration/millenaire/  DefendVillageDecorator, BridgeHoldTask (+ engage/hunt/hide decorators rewired)
data/hywmill/hywmill_doctrine/defaults.json
```

Isolation is intact. A `javap` scan finds `org/millenaire` only in `integration/millenaire`, and `ydmsama` only in `integration/hyw`. The domain packages have no foreign imports, and there are no mixins.

**Persistent contracts are unchanged:**
- mod ID `hywmill`;
- the `FactionIds` derivation;
- `hywmill_garrison_ledger`;
- the villageId ↔ factionId relationship.

`controllerPlayerId` is a separate field that never replaces the faction (unit test `format3KeepsOverrideStatsAndControllerSeparateFromFaction`).

### 2.1 Military profile
Profile fields: soldiers, leaders, militia, defenders, outlaws, civilians, capacity, readiness, equipment, fortification, tier, building roles and infrastructure.

- **Capacity:** the SOLDIER and MILITIA resident slots of **operational** buildings, read with `BuildingPlanSet.maleResidentsAt` / `femaleResidentsAt` at each building's **current variant and level**.
  - Each building instance lists only its own residents. Sub-buildings are separate instances, and a parent never lists its children's residents, so nothing is double-counted.
  - Plan names and future levels are never used.
  - Per-level data does matter: `byzantines/fortress_a_barracks` declares 1→4 soldiers across levels 0→3.
- **Readiness:** `min(100, 100 × (living SOLDIER + MILITIA) / capacity)`, or 100 when there is no capacity but there are defenders. This is my definition; the spec didn't give one.
- **Equipment score:** the mean of (armor value + main-hand attack damage) over **loaded** defenders, using vanilla attributes. It keeps the last value while no defender is loaded (−1 = never measured). Also my definition.

### 2.2 Doctrine
- **Resolution chain:** baseline → culture → lone building → village type (exact ID or `*` glob) → tier modifier → per-village override.
- **Radius:** `defenseRadius = villageRadius + offset (+ tier)`, clamped to 40–160. `VillageType.radius()` already includes Millénaire's `villageRadiusOverride`.
- **Traceability:** every field reports the layer it came from, e.g. `reserve = 3 [village type millenaire:norman/militaire + tier STRONGHOLD]`.
- **Values:** all your values are in `defaults.json`. Every village-type key exists in 9.0.2. `seljuk/*_small_seljuks` matches `agriculture_small_seljuks` and `artisans_small_seljuks`, but not `controlled_small_village_seljuks`, which doesn't fit the pattern.
- **Overrides:** persisted. `doctrine set` / `reset` require op level 2 or the village controller; reputation never grants permission.

### 2.3 Defense behavior
- **Threat area:** the defense radius around the village center, measured horizontally.
- **Reasons that allow commitment:** ATTACKING_RESIDENT, RECENT_ATTACKER and ATTACKING_ALLY_PLAYER. HYW_ENEMY alone never triggers commitment unless `proactive=true`.
- **Coordinator:** deterministic.
  - Eligibility is role-based: SOLDIER and LEADER always, MILITIA per policy.
  - The reserve is kept first (professionals first, then closest to the defending position, then UUID).
  - Existing assignments are kept, then each threat is filled up to `commitPerThreat`, by distance to the threat, then UUID.
  - No defender is ever assigned to two threats.
- **No delay:** a resident hit by an HYW unit forces a scan of that village on the next tick.
- **Self-defense:** a defender damaged by an HYW unit may always fight it (vanilla `getLastHurtByMob`, 100 ticks).
- **Reserve:** a new `defend_village` decorator holds `RaidManager.resolveDefendingPos(village)` in ALERT, ENGAGED and RECOVERY.
  - During a real raid (`Village.isUnderAttack()`), Millénaire's own goal runs unchanged.
  - A hold task also ends if a raid starts mid-hold.
  - `defend_village` has priority 9999, and Millénaire only preempts a combat task for a strictly higher priority. So the hold declines while the villager was just hurt, which lets `engage_target` run for self-defense. No mixin was needed.
- **Shelter:** a civilian starts sheltering when a threat is within `shelterRadius` of them (−1 = every civilian of the village). Once sheltering, they stay until the village leaves ALERT/ENGAGED. Otherwise a civilian heading for a shelter outside the radius would turn back halfway. They use Millénaire's own `shelterPos`.
- **Player assistance:** handled by `AssistPolicy`. The provoking player is not assisted unless `assistProvokingPlayer`. The controller with `assistController=ALWAYS` is always assisted, matched by `controllerPlayerId`.
- **Alert state machine:**
  - CALM → ALERT when a threat is inside the radius.
  - ALERT → ENGAGED on an engage signal: an HYW unit damaged a resident, or a resident hit an HYW unit.
  - ALERT → CALM after `alertTicks` with no threat (a sighting that never came to blows).
  - ENGAGED → RECOVERY after `engagedTicks` with no threat.
  - RECOVERY → ALERT if a threat returns, → CALM after `recoveryTicks`.
  - State is runtime-only. Transitions are counted in the persistent statistics.

### 2.4 Persistence (ledger format 3)
- **New fields:** capacity, readiness, equipment, village radius, lone building, `controllerPlayerId`, doctrine override, and statistics (alerts, engagements, HYW kills, resident losses, last alert/engaged/kill/loss tick).
- **Migration:** formats 1 and 2 are migrated. Existing role counts and identity are kept, and derived values are recomputed at the next update.
- **Verified:** unit tests for 1→3 and 2→3, plus server check A7, where an override survives a restart. Assignment and alert state are runtime-only.

### 2.5 Commands
- `village military`
- `doctrine get | set <field> <value> | reset [field]`
- `alerts`
- `perf [reset]`
- `dev capacity`

---

## 3. Test results

### 3.1 JUnit: 56/56
- 23 carried over from M1.1. The shipped role-table test was extended with the final leader decisions.
- **M2-3 doctrine (8):** every culture, several village types, the glob, tiers, lone-building clamp, override precedence, and invalid-data reporting.
- **M2-2 capacity and profile (5):** capacity from current-level slots, and the parent + sub-building fort case.
- **Ledger migration (2 new):** 2→3, and format-3 round trip with controller ≠ faction.
- **M2-5 and M2-6 coordinator (9):**
  - exact commit/reserve;
  - reserve threshold;
  - no double assignment;
  - sticky assignments;
  - determinism under input order;
  - proactive=false;
  - CALM assigns nothing;
  - civilians and outlaws are never eligible;
  - all four militia policies.
- **M2-4, M2-7, M2-8, M2-9 (9):** horizontal radius, shelter radius and village-wide shelter, reputation / provoking player / controller, and the full lifecycle with exact timer edges, anti-thrash behavior and custom timers.

### 3.2 Dedicated server: 70/72 (full log: `docs/m2-test-evidence/harness-final.txt`)

| Area | Checks | Result |
|---|---|---|
| M1/M1.1 regression | setup, status, H, B, C, D, D5-b, I1–I7, F1, E, F2, A1–A6, G0–G5 | **all pass** |
| **Critical contract** | I1 (3 owned units pass through: no threat), N3 (unowned NoAI unit in village: 0 villager hits, 0 committed), I2–I4 (attackers each become one threat, defenders engage, civilians don't) | **pass** |
| M2-1 classification | X1 seneschal LEADER; X2 13 border markers, WALL/TOWER/GUARDHOUSE 0, fortification 0, WATCH; X3 `norman/militaire` FORT_TOWNHALL, 4 soldiers, STRONGHOLD; X4 byzantine soldiers (runtime) | **pass** |
| M2-2 capacity | X5 for three villages: mod value equals an independent sum computed by the harness from Millénaire's own JSON at each building's current variant/level (16, 10, 18) | **pass** |
| M2-3 doctrine | X6 A = baseline; X7 militaire reserve 3 = type 2 + STRONGHOLD, WHEN_ATTACKED; X8 byzantine commit 5 = type 4 + GARRISON, reserve 2, rep 256, recovery 1200 | **pass** |
| M2-4 radius | N1 outside (+118 > 106): CALM, no threat; N2 inside: ALERT | **pass** |
| M2-5 commit/reserve | I8 during a real fight: [3, 3] per threat, reserve 1 of 18 eligible, no defender on two threats | **pass** |
| M2-6 militia | W1 `militiaPolicy NEVER`: every hit came from the seneschal (LEADER); W2 override removed | **pass** |
| M2-7 shelter | N4 19 civilians near the unit sheltered; C4 | **pass** |
| M2-9 lifecycle | L1 ALERT→ENGAGED→RECOVERY→CALM; L2 RECOVERY after 14 s, CALM after 44 s (nominal 10 s / 40 s, plus the 1 s scan interval and 2 s polling); L3 statistics counted; N5 sighting → CALM | **pass** |
| Persistence | A4 format 3 loaded; A7 override survives restart; A8 reset | **pass** |
| M2-11 performance | P1 5 villages + ~20 HYW units, P2 scan mean 66 µs, **P3a/P3b 2 ms per-slice targets: fail** (§5) | 2 fail |
| M2-12 optional | hywmill alone, + Millénaire only, + HYW only | **3/3** |

The byzantine `militaryvillage` doctrine (commit 4, reserve 2) is kept, because runtime verification confirmed soldiers: 2 `soldier_byzantine` and the centurion as LEADER.

---

## 4. Contracts preserved (explicit)
- A neutral or passing player-owned HYW army is not attacked: I1, B.
- HYW troops that actually attack residents are fought by defenders, with no extra delay. The next-tick scan happens after the first hit.
- Civilians never fight HYW units: C4, I4.
- `DiplomacyPolicy.ALWAYS_REVERT` is unchanged: D5-b, I5 (3 escalations detected and reverted in the final run).
- HYW temporary retaliation is unchanged (D3-4). HYW's null-owner hostility is unchanged; it is what makes an unowned unit HYW_ENEMY.
- Millénaire's own monster defense, raids, resurrection and hide are unchanged: F1, F2, E.
- Real raids keep Millénaire's own `defend_village`.

---

## 5. Performance (M2-11)

Production log level (`verboseLogging=false`, `docs/m2-test-evidence/perf-production-logging.txt`): 5 active villages, about 20 HYW units (10 owned passing through, 9 NoAI bandits), 60 s after warm-up:

| Slice | Cadence | Mean | p99 | Max |
|---|---|---|---|---|
| `scan.village` | every 20 ticks per active village (staggered) | 88 µs | 598 µs | 808 µs |
| `defense.update` | with each scan | 84 µs | 602 µs | 779 µs |
| `profile.sweep` (identity) | every 200 ticks per village, own slot | 331 µs | 962 µs | 962 µs |
| `profile.refresh` | every 200 ticks per village | 1085 µs | 3288 µs | 3288 µs |
| — of which `profile.snapshot` (reading Millénaire) | | 745 µs | 2136 µs | 2136 µs |
| `tick.total` (all hywmill work in a server tick) | every tick | **90 µs** | 1.5 ms | 4.9 ms |

**Met:** the mean village scan is under 200 µs (66–113 µs across runs). The per-tick average is 60–100 µs, about 0.2% of the 50 ms tick budget.

**Not met:** "no single slice > 2 ms".
- The profile refresh, one per village every 10 s, has a 1.1 ms mean and tail spikes of 2.6–4.7 ms.
- About 70% of it is reading Millénaire state: residents, buildings, resident slots, loaded-defender gear.
- The test host is a shared 4-CPU container where Millénaire itself logs "Can't keep up" and 200–450 ms waypoint-graph rebuilds on the server thread. The maxima vary from run to run by 2× with identical code.

**Optimizations applied:**
- the identity sweep moved to its own staggered slot;
- the resolved doctrine is cached until one of its inputs changes;
- the wall-role map is cached;
- each village is refreshed on its own staggered slot.

**Not done** (would need your call): split the snapshot across two ticks, or refresh less often than every 200 ticks.

---

## 6. Gameplay behaviors verified

**By me, on a real dedicated server with both production JARs** (§3.2):
- neutral passage;
- an unowned idle unit (ALERT, no attack);
- a real attack, answered by committed defenders;
- reserve;
- militia policy;
- shelter;
- the full alert lifecycle;
- raids;
- resurrection;
- monster defense;
- persistence.

**Not verified by me:** anything needing a real client or a real player.
- D6 and M2-8 (defenders helping a real player) are **UNVERIFIED** on a server; they have unit tests only.
- The reserve *visibly holding* the position was checked only through logs and assignments, not by watching it.

**Suggested client checks:**
1. Walk an owned army through a village: it is not attacked.
2. Set troops to indiscriminate: defenders respond, and `/hywmill village military` shows ENGAGED, committed defenders and the reserve.
3. Watch the reserve stand at the town hall's defending point.
4. `/hywmill doctrine set militiaPolicy NEVER`: only guards and leaders fight.
5. `/hywmill alerts` after the fight: RECOVERY, then CALM about 30 s later.

---

## 7. Limitations and assumptions

1. **Performance tail:** per-slice spikes over 2 ms in the profile refresh (§5).
2. **Real-player assistance:** D6 and M2-8 are unverified with a real player (unit tests only).
3. **Roles only drive Millénaire's defender types.** Millénaire injects `engage_target`, `hunt_monster` and `defend_village` only into `helpInAttacks` types. A role-table entry that makes a non-helpInAttacks type SOLDIER affects counts and tier, but that villager has no combat goals to act on. No shipped entry does this.
4. **Reserve threshold** is counted over the *eligible* pool. With `ON_ENGAGED`, militia aren't eligible in ALERT, so a village with fewer than `commit + 2` professionals only withholds its reserve once ENGAGED.
5. **Targeting counts as reactive.** A unit *targeting* a resident (ATTACKING_RESIDENT) already allows SOLDIER/LEADER commitment, following the approved §0.2 definition. `WHEN_ATTACKED` militia need actual damage (RECENT_ATTACKER).
6. **Stale Millénaire targets.** `callForHelp` may leave an unassigned defender with an HYW unit as its Millénaire attack target. It won't fight it (not assigned), and Millénaire clears the target itself. It no longer blocks that defender's own assignment.
7. **Timer resolution** is the scan interval (20 ticks).
8. **Statistics:** "HYW kills" counts kills by residents only; "resident losses" counts deaths caused by HYW units only.
9. **Superseded config:** `assistMinReputation` / `assistProvokingPlayer` in the config are superseded by the doctrine and ignored (commented as UNUSED). `guardsAssistPlayers` remains a master switch. `threatMarginBlocks` now only pads vertically.
10. **Spec choices where it differed from the proposal document:**
    - WATCH tier: no change (the proposal forced reserve 0);
    - NONE tier: reserve 0 only;
    - `bravewoman` stays unlisted, since the frozen table doesn't include it.
11. **Test environment:** on seed 20260925 most land near the test villages is sea. Villages are spawned at the measured ground height (the harness had a y=80 guess that Millénaire correctly rejected). `norman/militaire` placement is randomised by Millénaire, and the harness tries three positions.

---

## 8. What M3 should build on
- `DefenseService` (runtime seam),
- `DefenseCoordinator` (pure assignment),
- `AlertStateMachine`,
- `DoctrineResolver` / `DoctrineField` (new fields are one enum entry plus JSON),
- `MilitaryProfile` (capacity, readiness, equipment),
- `VillageRecord` format 3 (bump plus migration pattern),
- `DiplomacyPolicy` (war declarations),
- `controllerPlayerId`.

---

**Stopping here.** M3 will not start without your authorization.
