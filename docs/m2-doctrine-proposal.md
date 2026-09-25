# M2 doctrine defaults, design corrections and implementation plan

**Status:** proposal for review. No source files were changed. The decisions Q1–Q8 from the review are applied throughout.

The numbers below are grounded in the shipped Millénaire 9.0.2 data:
- **Village radius:** the default is 90. Hamlets use 50, small Seljuk villages 70, Notre-Dame 140. `VillageType.radius()` is public.
- **Wall types per village type:** outer walls are border posts almost everywhere. Inner stone walls: Norman artisans, bourg_autonome, grosbourg, militaire, walledcontrolled, ecclesiastique. Sandstone walls: Indian fort, palace, controlled_walled. Mud-brick walls: the larger Seljuk villages.
- **Defender types per culture and reputation scale:** see §0.1 and §0.3.

---

## 0. Definitions the values depend on

### 0.1 Villager roles (new; data-driven, reviewed with these values)

| Role | Rule | Shipped examples |
|---|---|---|
| SOLDIER | Explicit table | Norman `guard`, `orderguard`; Indian `soldier`; Japanese `samurai`, `bravewoman`; Mayan `warrior`; Seljuk `turk_soldier`, `turk_lonesoldier`; Byzantine `soldier_byzantine`, `guard_byzantine` |
| LEADER | `chief` tag, or an explicit table entry | Norman `knight`, `seneschal`; Indian `rajputgeneral`; Japanese `samuraigeneral` |
| MILITIA | `helpInAttacks` and not one of the above | Lumbermen, farmers, miners, carpenters, armysmiths, Inuit hunters and husbands, Byzantine forest keepers |
| OUTLAW | `hostile` tag | Bandits, felons, Mayan bandits, the Inuit madman |
| CIVILIAN | Everything else | Exactly the types Millénaire gives its own `hide` goal |

"Defenders" means SOLDIER + LEADER + MILITIA: the same set as Millénaire's `helpInAttacks`, and therefore the same set M1 counted as garrison.

### 0.2 Proactive vs reactive (correction to M1 wording; see §2.1)

A threat is **reactive-eligible** if its classification includes at least one of:
- `ATTACKING_RESIDENT`: its current target is a resident;
- `RECENT_ATTACKER`: it damaged a resident within the window;
- `ATTACKING_ALLY_PLAYER`: it targets a player the doctrine assists.

`proactive=true` additionally lets defenders engage units whose **only** reason is `HYW_ENEMY` (hostile by HYW rules, but not yet targeting anyone here). Proactive has **no** effect on ALERT or sheltering: an unowned HYW force inside the defense radius always raises ALERT, and civilians near it still shelter.

**Self-defense is never restricted by doctrine.** A SOLDIER, LEADER or MILITIA villager that is itself damaged by an HYW unit always fights back, whatever the commit, reserve or militia policy. Civilians never fight HYW units; they shelter.

### 0.3 Reputation thresholds (Millénaire's scale)

| Value | Millénaire label |
|---|---|
| −1024 | public enemy |
| −256 | unpleasant outsider |
| −64 | nuisance |
| **0** | **stranger** |
| **256** | **familiarity** |
| 512 | known face |
| 1024 | regular visitor |
| 4096 | can hire villagers |
| 8192 | friend of the village |

### 0.4 Alert state machine (timers in ticks)

| Transition | Condition |
|---|---|
| CALM → ALERT | Any classified threat inside `defenseRadius` |
| ALERT → ENGAGED | A resident or defender is damaged by a threat, or a defender lands a hit on one |
| ALERT → CALM | No threats for `alertClearTicks` |
| ENGAGED → RECOVERY | No threats for `engagedClearTicks` |
| RECOVERY → CALM | After `recoveryTicks` |

Effects:
- **ALERT and ENGAGED:** reserve defenders hold `defendingPos` (§2.4). Civilians within `shelterRadius` of a threat shelter.
- **ENGAGED only:** militia with policy `ON_ENGAGED` become assignable.
- **RECOVERY:** reserve keeps holding; militia return to work; nobody new is assigned unless a threat reappears (then back to ALERT).

---

## 1. Proposed doctrine values

### 1.1 Global baseline

Applied to any village type not listed below, and inherited by every culture unless overridden.

| Parameter | Baseline | Meaning |
|---|---|---|
| `defenseRadius` | `villageRadius + 16`, clamped to 40–160 (default villages → **106**) | Threats inside it raise ALERT and are eligible for assignment. M1 effectively used building bounds + 16. |
| `proactive` | **false** | See §0.2. |
| `commitPerThreat` | **3** | Max defenders assigned to one threat (self-defense is extra). |
| `reserve` | **1** | Defenders withheld to hold `defendingPos`; only taken when defenders ≥ commit + 2. |
| `militiaPolicy` | **ON_ENGAGED** | Militia become assignable once the village is ENGAGED. |
| `shelterRadius` | **48** | A civilian shelters if a threat is within 48 blocks of *them*; `-1` means village-wide. |
| `assistPlayers` | **MIN_REPUTATION 0** | Assist players whose reputation is at least "stranger". |
| `assistProvokingPlayer` | **false** | No help for a player the ledger shows struck first (as M1). |
| `assistController` | **ALWAYS** | The controlling player of a player-controlled village is always assisted (Q8). |
| `alertClearTicks` / `engagedClearTicks` / `recoveryTicks` | **100 / 200 / 600** | 5 s, 10 s, 30 s. |

Tier modifiers (added after the culture and type values, then clamped):

| Tier | commitPerThreat | reserve | defenseRadius |
|---|---|---|---|
| NONE | = min(commit, defenders) | 0 | — |
| WATCH | +0 | 0 (forced) | +0 |
| GUARD_POST | +0 | +0 | +0 |
| GARRISON | **+1** | +0 | +0 |
| STRONGHOLD | **+1** | **+1** | **+16** |

Reasoning:
- **Commit 3** lets a village gang up on a lone bandit (which is what M1 did in practice: 2 defenders engaged) without the whole village abandoning work.
- **Reserve 1** is only taken from villages with at least 5 defenders.
- **ON_ENGAGED** keeps tradesmen working through a mere sighting, while still pulling them in once blood is drawn. That is closest to M1's outcome without M1's village-wide rush.

### 1.2 Per culture (values differing from the baseline)

| Culture | defenseRadius | commit | reserve | militia | shelter | assist players | timers (alert/engaged/recovery) | Reasoning |
|---|---|---|---|---|---|---|---|---|
| **Norman** | radius + 16 | 3 | 1 | ON_ENGAGED | 48 | rep ≥ 0 | 100/200/600 | Castle-and-guard culture with real `guard` soldiers. The baseline *is* the Norman model. |
| **Byzantine** | radius + 16 | 3 | **2** | **WHEN_ATTACKED** | 48 | **rep ≥ 256** | 100/200/**1200** | Disciplined professional army: soldiers hold the line and keep a reserve, and tradesmen only fight if struck. Formal toward outsiders, so it assists only familiar players. Stays vigilant longer. |
| **Seljuk** | **radius + 24** | 3 | 1 | ON_ENGAGED | 48 | rep ≥ 0 | 100/200/600 | Frontier culture with professional `turk_soldier` and walled towns. Watches a slightly wider perimeter. |
| **Indian** | radius + 16 | 3 | 1 | ON_ENGAGED | 48 | rep ≥ 0 | 100/200/600 | Rajput soldiers in forts; farming villages mostly militia (see types below). |
| **Japanese** | radius + 16 | **2** | 1 | ON_ENGAGED | **32** | rep ≥ 0 | 100/200/600 | Few but strong samurai and bravewomen fight in small numbers. Civilians are composed: they shelter only when danger is close. |
| **Mayan** | radius + 16 | **4** | **0** | **ALWAYS** | 48 | **rep ≥ 256** | 100/200/600 | Warrior culture: the whole able-bodied village answers, with no held reserve. Wary of outsiders. |
| **Inuit** | radius + 16 | **4** | **0** | **ALWAYS** | **-1 (village-wide)** | rep ≥ 0 | 100/200/**400** | Small hunting communities with no professional soldiers. The hunters *are* the defense, everyone else takes cover together, and life returns to normal quickly. |

`proactive` is **false** for every culture. The only candidates I would consider for `true` later (not proposed now) are the military types in §1.3.

### 1.3 Per village type (only where the type meaningfully differs from its culture)

| Village type | Differences from its culture | Reasoning |
|---|---|---|
| norman/militaire, norman/walledcontrolled | reserve **2**; militia **WHEN_ATTACKED** | Garrisoned, walled towns with several `guard`s: soldiers handle it and tradesmen keep working. |
| norman/grosbourg | militia **WHEN_ATTACKED** | Only 5 defenders, mostly guards; the town doesn't send tradesmen out. |
| norman/hameau_agricole, hameau_artisans, hameau_abbatiale (r=50) | commit **2**; reserve **0**; shelter **-1** | Tiny hamlets (3–7 defenders, no soldiers): everyone who can fight does, and everyone else hides. |
| norman/ecclesiastique | reserve **0** | No soldiers; tradesmen only. |
| norman/notredame (r=140) | defenseRadius **radius + 0** (140); commit **2** | Large pilgrimage site guarded by a few `orderguard`s. Its radius is already large. |
| byzantines/militaryvillage | reserve **2** (same as culture); commit **4** | The military settlement. Its soldier composition must be verified at runtime (the layout walk found only lumbermen resident). |
| indian/fort, indian/palace, indian/palace_autonomous, indian/controlled_walled | reserve **2**; militia **WHEN_ATTACKED** | Walled Rajput forts with standing soldiers. |
| indian/agricole, indian/agricole_hamlet | commit **2**; reserve **0** | Farming, lumberman militia only. |
| japanese/gunji (military), japanese/seiji | reserve **2** | Samurai-heavy administrative and military villages. |
| mayan/military | reserve **1** | Even warriors keep one at the temple. |
| seljuk/military_village_seljuks, seljuk/controlled_fort_seljuks | reserve **2**; militia **WHEN_ATTACKED** | Mud-brick-walled garrisons. |
| seljuk/*_small_seljuks (r=70) | commit **2**; reserve **0** | Small villages. |
| All `player_controlled` types (`*/controlled*`, `norman/walledcontrolled`) | inherit culture or type; `assistController=ALWAYS` | The controlling player is always defended (Q8: via `controllerPlayerId`, not faction identity). |
| Lone buildings (bandit camps, inns, lone farms) | baseline; commit **2**; reserve **0**; shelter **-1** | Tiny sites. Outlaw residents are never defenders, so bandit camps field no one. |

### 1.4 Resulting effective values (default radius 90, before tier modifiers)

| Example | radius | commit | reserve | militia | shelter | assist |
|---|---|---|---|---|---|---|
| Douvres la-forge (norman/artisans) | 106 | 3 | 1* | ON_ENGAGED | 48 | rep ≥ 0 |
| Marolles (norman/agricole) | 106 | 3 | 1* | ON_ENGAGED | 48 | rep ≥ 0 |
| norman/militaire | 106 | 3 (4 at GARRISON) | 2 | WHEN_ATTACKED | 48 | rep ≥ 0 |
| norman/hameau_agricole | 66 | 2 | 0 | ON_ENGAGED | village | rep ≥ 0 |
| indian/fort | 106 | 3 | 2 | WHEN_ATTACKED | 48 | rep ≥ 0 |
| inuits/huntingvillage | 106 | 4 | 0 | ALWAYS | village | rep ≥ 0 |

\* A WATCH-tier village's reserve is forced to 0. Douvres (WATCH after M1.1) will therefore commit up to 3 with no reserve.

### 1.5 Revised fortification and tier (M1.1-b; for review with the doctrine)

**Fortification points (operational buildings only):**

| Building role | Points |
|---|---|
| WALL | 1 each |
| TOWER | 3 |
| GATE | 2 |
| **BORDER_MARKER** | **0** (reported separately, per Q6) |
| GUARDHOUSE | 3 |
| WATCHTOWER | 3 |
| BARRACKS | 4 |
| FORT_TOWNHALL | 5 |

ARMOURY and TRAINING add no fortification, but they count toward tier.

**Tier (first match wins):**

| Tier | Condition |
|---|---|
| STRONGHOLD | GARRISON conditions, fortification ≥ 20, and ≥ 1 WALL |
| GARRISON | ≥ 3 SOLDIER/LEADER and ≥ 1 of {BARRACKS, ARMOURY, TRAINING, FORT_TOWNHALL} |
| GUARD_POST | ≥ 1 SOLDIER, or (≥ 2 defenders and ≥ 1 of {GUARDHOUSE, WATCHTOWER}) |
| WATCH | ≥ 1 defender |
| NONE | otherwise |

Expected on the test world: Douvres goes from **GUARD_POST (12) to WATCH (0)**; Marolles goes from **GUARD_POST (9) to WATCH (0)** unless its manor houses a knight (the role table decides).

---

## 2. Corrections to the M2 design after these decisions

**2.1 M1 was proactive; `proactive=false` changes an M1 observable.** M1's `hunt_monster` decorator engaged any tracked threat, including `HYW_ENEMY`-only units. In TEST C the log shows guards selecting the bandit "via hunt_monster" in the same tick it appeared, before it targeted anyone.

With `proactive=false`, defenders engage when the bandit **targets or damages** a resident. HYW bandits target marked villagers as soon as they see them, so in practice this is a delay of a few ticks. It does change the M1 regression expectation for TEST C:
- **Old wording:** "guards detect and engage".
- **New wording:** "guards engage once the unit targets or damages a resident, and civilians near it shelter from the moment it enters the radius".

The M1.1 phase itself keeps M1's proactive behavior unchanged; the change lands with M2 doctrine. **Please confirm this is the intended meaning of your "proactive defaults to false" decision.**

**2.2 The reserve needs a real behavior, which means a 4th decorator.** "Hold `defendingPos`" is new movement for villagers. The least intrusive seam is Millénaire's own `millenaire:defend_village` goal, which is already injected into exactly the defender types.
- **During a real Millénaire raid** the decorator delegates unchanged.
- **During our ALERT, ENGAGED or RECOVERY** it walks reserve defenders to `RaidManager.resolveDefendingPos(village)` and holds them there. That is the same point and priority as Millénaire's raid defense.
- If you'd rather not add behavior, the fallback is "reserve = not assigned, keeps working". That is simpler, but a reserve that ignores the fight isn't much of a reserve.
- **Recommendation:** decorate `defend_village`.

**2.3 Self-defense exemption** (added in §0.2). Without it, a reserve or uncommitted guard being hit would stand still, which is worse than M1.

**2.4 Defense radius is relative to the village radius** (`villageRadius + offset`), not an absolute number. This way hamlets, Notre-Dame and Millénaire's own `villageRadiusOverride` config all scale correctly.

**2.5 Identity model (Q8).** `VillageRecord` gains `controllerPlayerId`, refreshed every 200 ticks from `Village.isPlayerControlled()` / `getOwnerUUID()`. Controller changes are logged. `factionId` never changes. Doctrine permissions (Q4) check `controllerPlayerId` or op level. `assistController` uses it.

**2.6 Escalation policy seam (Q3).** M1.1 introduces a `DiplomacyPolicy` interface whose only implementation is "revert every generic HYW HOSTILE involving a village faction". A future war system registers *declared* hostilities, which the policy will then leave alone. No declared-war code in M2.

Note on the decision: the reconciliation pass cannot tell HYW's automatic escalation apart from an operator running `/hyw relation set <faction> … hostile` by hand. Both will be reverted under Q3. I'd consider that acceptable until our own diplomacy exists.

**2.7 Where the runtime refactor lands.** `HywMillRuntime` and the per-village scheduler move into the M1.1 phase as non-behavioral cleanup, so M2 is built on them from its first line. M1 A–F must pass unchanged after that step, before any M1.1 behavior fix.

**2.8 Data-driven role tables** ship as JSON in our JAR under `data/hywmill/military/…`, loaded with a server reload listener and replaceable by datapacks. M1.1-b ships only the *building* role table (needed for the fortification fix). The villager role table and the doctrine tables arrive in M2.

---

## 3. API and runtime risks found during planning

| # | Risk | Mitigation |
|---|---|---|
| R1 | `BuildingPlanSet.maleResidentsAt(variant, level)` returns short type ids (for example `"guard"`), which must be resolved against the plan's culture. Sub-buildings and upgrades may list residents at levels that aren't built yet. | Resolve with `culture` + id. Count only the operational level of each operational building. Unit-test against the Norman guardhouse and largefort JSON. |
| R2 | Byzantine `militaryvillage` showed no soldier residents in a static layout walk; soldiers may live in sub-buildings. | Verify with `/hywmill village military` on a spawned instance before approving its commit value. |
| R3 | The `defensive` villager tag (Millénaire leash of 20 blocks from `defendingPos`) can clear a committed defender's target mid-fight. | Respect it (as M1 does). The coordinator re-assigns on the next scan, and tests use non-defensive types. |
| R4 | Decorating `defend_village` must not alter raids. | Delegate whenever `Village.getRaidTarget() != null` or `isUnderAttack()`. Re-run the M1 raid regression (F2). |
| R5 | Resurrected villagers get new UUIDs. | Assignments are keyed by villager UUID and rebuilt every scan; no persistence needed. |
| R6 | Clearing identity markers (M1.1-c) can't reach unloaded villagers. | Keep a persisted "pending clear" set in the ledger and clear on join. The admin command reports loaded vs pending counts. |
| R7 | The reconciliation pass needs the full list of village factions after a restart, even for villages not yet loaded. | Iterate the ledger records (all villages ever seen), not only active ones. |
| R8 | The `HywMillRuntime` refactor touches every static manager. | Do it alone, non-behavioral, and run the full A–F regression before any behavior change. |
| R9 | JUnit on classes that reference Minecraft types. | Keep formula, doctrine and coordinator inputs as plain data (strings and ints). Use ModDevGradle's test source set for anything touching `ResourceLocation`. |
| R10 | Single-player (integrated server) is still untested; `HywMillRuntime` makes it more likely to work but doesn't prove it. | Covered by your D6 client session (audit §9, step 7). |
| R11 | A player-controlled village's controller can change without an event. | Poll every 200 ticks and log changes. The `controllerPlayerId` history is not persisted in M2. |
| R12 | Once proactive is off, M1's "detect via hunt" log line no longer appears in TEST C. | TEST C wording updated per §2.1; assertions use the incident ledger instead of log text. |

---

## 4. Implementation sequence

Each step ends with a build, JUnit, and the listed server tests. Commits are per step.

**Phase M1.1** (behavior fixes plus agreed cleanup; M1 semantics preserved except where M1.1 fixes them):

| # | Step | Tests |
|---|---|---|
| 1 | Commit the headless server harness (`devtools/server-harness/`: install script, console-pipe driver, scenario scripts for A–F) and set up JUnit. | Harness runs M1 A–F unchanged. |
| 2 | Non-behavioral cleanup: `HywMillRuntime` per-server context and staggered scheduler; `LinkageError` guard around the bridge-only branches in decorators; log-level cleanup (INFO only for tier, garrison and fortification changes). | A–F regression (no behavior change allowed). |
| 3 | M1.1-d: exclude neutral uncrewed siege engines. | New test H. |
| 4 | M1.1-a: `DiplomacyPolicy` plus reconciliation pass over all ledger factions every 200 ticks; the event hook stays. | New test D5-b: same-tick triple hit → NEUTRAL within 200 ticks, WARN logged. D5 still passes. |
| 5 | M1.1-b: building role table (JSON, wall roles derived from `WallType`, border markers 0), revised fortification and tier (§1.5), ledger format 1 → 2 migration. | JUnit for formulas; TEST A with updated expected values (Douvres WATCH / 0); migration of the existing test world verified. |
| 6 | M1.1-c: `markVillagers=false` clears our faction markers; `/hywmill admin clear-identities` (op 3); persisted pending-clear set; uninstall notes in the README. | New test G: toggle off, restart, markers gone; toggle on, re-marked. |
| 7 | Full M1 + M1.1 regression and an M1.1 report. **You run D6 and the single-player checklist on your client.** | — |

**Phase M2** (starts only after you approve §1 and §2):

| # | Step |
|---|---|
| 8 | Villager role table (JSON) and classifier. |
| 9 | Corrected military profile: roles, capacity from resident slots, readiness, equipment score, `controllerPlayerId`. |
| 10 | Doctrine data (approved values), resolver (baseline ⊕ culture ⊕ type ⊕ tier ⊕ override), persisted overrides, permission check (op or controller). |
| 11 | Alert state machine; persisted stats in our SavedData only (Q5). |
| 12 | Defense coordinator and assignments on the staggered scheduler; self-defense exemption. |
| 13 | Decorator integration: engage (assignment-aware, proactive flag), hunt (assignment-driven), hide (`shelterRadius`), `defend_village` (reserve hold, raid-safe). |
| 14 | Commands: `village military`, `doctrine get/set/reset`, `alerts`; role and assignment columns in `village residents`. |
| 15 | Performance counters in `/hywmill status`. |
| 16 | M2 acceptance tests M2-1 … M2-12 (audit §7.11, with M2-5 and TEST C wording updated per §2.1 and §2.2). |
| 17 | Full M1 + M1.1 + M2 regression, M2 report. |

---

## 5. What I need from you

1. Approve or adjust the values in §1.1–§1.5 (baseline, per culture, per village type, fortification and tier).
2. Confirm the proactive semantics in §2.1, including the TEST C wording change.
3. Choose the reserve behavior in §2.2: decorate `defend_village` (recommended), or have the reserve simply not engage.
4. Confirm that §2.6's side effect (manual `/hyw relation set … hostile` on a village faction is also reverted) is acceptable under Q3.
5. Approve the sequence in §4, in particular moving `HywMillRuntime` into the M1.1 phase (§2.7).
