# HywMill M4: Living Village Militaries (Report)

Pins: Minecraft 1.21.1, NeoForge 21.1.226, Millénaire 9.0.2, HYW 0.7.1r-fix1; optional Epic
Knights 10.15. One JAR, no mixins, no new dependencies (Epic Knights is read only through the item
registry and HYW's own data). M3 is unchanged apart from the explicit M4 data change in §2.2 and the
duty bookkeeping hooks in §2.6.

Branch: `claude/millenaire-hyw-audit-5n4u8s`.

## 1. Summary

| Area | Result |
|---|---|
| M4-0 spikes (raid lifecycle, Wand of Negation, mounted scouts, Epic Knights) | Feasible with public APIs; no mixins, no changes to Millénaire or HYW (`docs/m4-spike.md`) |
| JUnit | 178/178 (45 new for M4: duty data/quotas/allocation/plan/motion/persistence, raid planner, equipment profiles, spawn-location fallback) |
| G4 duty/raid acceptance, final jar | **31/31** (`docs/m4-test-evidence/spawn-fallback/g4-final.txt`; the pre-final run `g4-run4-final.txt` was also 31/31) |
| G4 Epic Knights profiles | **10/10** (`g4-epic-knights.txt`, `equipcheck-report.txt`) |
| M3 G3 suite with M4, incl. the spawn fallback (G3-18) | **51/51** |
| M2 regression (garrison disabled, the M2 baseline) | **70/72**; the only failures are the accepted performance-tail items P3a/P3b (a pre-final run was 72/72) |
| Calm cost, same world, duties off → on | server tick mean 57.6 → 63.2 µs, p99 644 → 706 µs; duty tick ≈ 97 µs per village per 40 ticks |

## 2. Design

### 2.1 State model (section 11)

* **Duty is separate from the M3 lifecycle.** Each `RosterEntry` keeps its M3 `UnitState` and adds:
  * `assignedDuty`: the standing duty, one of GARRISON, SENTRY, PATROL, SCOUT or RESERVE;
  * `duty`: the current duty, which is the standing duty or one of DEFENSE, RAID, RETURNING;
  * `dutyIndex`: the post, pair, patrol or scout slot;
  * `dutyStep`, `dutySince`: progress (patrol waypoint and pause; scout phase);
  * `equipRole`: the duty role its equipment was applied for.
* **Examples.** GARRISONED+SENTRY; DEPLOYED+DEFENSE (M2 deployment); DEPLOYED+RAID; RETURNING+RETURNING.
* **Storage.** The fields are an optional `duty` compound per entry, written only when not the
  default. An M3 roster loads as GARRISON, and the ledger format stays 4.
* **Raids.** A raid in progress is an optional `raid` compound on the roster: target, raid start,
  the attacker's raid-history length at the start, phase, and count sent.
* **Runtime-only state.** Plans, quotas and the hop cache are never persisted. They are recomputed
  from the layout and the roster.

### 2.2 Data (sections 5, 8)

`data/hywmill/hywmill_duties/defaults.json` is a new table. The M2 doctrine and the M3 schema are
unchanged. It holds:
* **Per tier: a `DutyRule`.** For sentry pairs, patrol and scouts, each rule has a share of the
  living available garrison, a hard maximum, and a minimum garrison size below which the duty is
  not staffed. The reserve has a share and a minimum. A duty whose minimum is met gets at least one
  unit (or pair).
* **`DutyQuota` guarantees:**
  * the total never exceeds the living available units;
  * trimming cuts scouts first, then patrol, then sentry pairs, then the reserve;
  * never more pairs than sentry posts.
* **Culture patches** merge per field. For example:
  * Seljuk: more scouts, farther out, larger raid share;
  * Japanese: more sentries;
  * Inuit: scouts even at WATCH tier.
* **`movement`:** hop ≤ 32, arrival radius, hop timeout, patrol radius and points, pause, sentry
  spacing.
* **`scouting`:** ring distance, posts, dwell, rest, phase timeout.
* **`raid`:** commit fraction, min/max contingent, home share kept, sentry pairs and reserve kept,
  minimum garrison.

Mounted scouts are an explicit **M4 data change** to the M3 garrison tables:
* two `units.json` entries, `light_lancer_rider` and `archer_rider` (new class CAVALRY, from
  GARRISON tier);
* CAVALRY allowed at GARRISON and STRONGHOLD tiers;
* one rider weight added to the compositions of the horse cultures. Mayan and Inuit get none; the
  M3 infantry weights are unchanged.

The riders are allow-listed (`Recruitment.M4_CAVALRY_TYPES`). Each rider manages its own HYW horse,
and the rider is the roster unit.

### 2.3 Allocation (section 5)

`DutyAllocator` is pure and deterministic by roster id.
* **Scouts first**, by preference: cavalry, then ranged, then levy. A current scout wins ties, so
  riders take over scouting once the village has them.
* **Everyone else keeps their duty** and post/pair/patrol slot while the quota has room.
* **Free places fill by preference:**
  * sentries: ranged, then line, then levy;
  * patrol: levy or line;
  * reserve: line.
* **The rest stand on GARRISON duty.**
* **When it re-runs.** Allocation runs again only when the set of available units, the tier or the
  plan changes. Available units are living, not MISSING, and not on a raid.

### 2.4 Posts and routes (sections 2, 3)

`SettlementSource.layout` reads Millénaire's operational buildings only when a plan is (re)computed:
* **Military role:** each building's role comes from the M1.1 role tables (wall, gate, tower,
  guardhouse, watchtower, barracks, armoury, training, fort townhall).
* **Standing point:** the building's `defendingPos` special point if it is within 3 blocks of the
  building's ground-level path anchor, else the anchor. Wall walks and tower tops are ones HYW
  units cannot path to.
* **Also read:** every operational building's path anchor, the townhall, and the village's own
  defending position and radius.

The layout is re-read every `layoutRecheckTicks` (1200); the plan is recomputed only if its key
changed. `DutyPlan` is pure:
* **Sentry posts.** In role priority order: gate, guardhouse, watchtower, tower, wall, armoury,
  barracks, fort townhall, training. Within a role, farthest-point order spreads the posts over the
  defences; posts closer than 10 blocks are merged. A village with no military buildings uses its
  defending position (and townhall). The two sentries of pair *i* stand on either side of post *i*.
* **Patrol.** In each of 8 angular sectors, the building anchor farthest from the centre within 75%
  of the village radius. In angular order these form a closed loop, which is always inside the
  defensive area. Patrol slots start spread over the loop.
* **Scout posts.** A ring at village radius + 40 (Seljuk and Inuit: 56), with 4 posts rotated by a
  per-village angle.
* **Muster and reserve points.** GARRISON-duty units stand at the military points. A village with
  fewer than 6 uses its innermost building anchors too. The reserve stands at the armoury, else the
  barracks, fort townhall, training ground, or defending position.

### 2.5 Movement (sections 3, 4, 10)

Units are moved **only** by setting their HYW home; there is no pathfinding of our own:
* **Hops.** One hop of at most 32 blocks, per the M4-0 finding.
* **Ground.** Standable ground near the point at its own height first, then the surface, only in
  entity-ticking chunks. HywMill never force-loads.
* **Blocked moves.** If no hop is possible, a scout watches from where it is and a patrol skips to
  its next waypoint.
* **Units that stop moving.** A unit that does not reach its hop within a hop timeout gets detour
  hops, rotated 35° per attempt with sides alternating. A unit that does not reach a fixed spot is
  tried on ground spots around it. As a last resort, a unit that has not moved at all is moved onto
  its spot or hop (≤ 40 blocks, loaded, standable).
* **Cadence.** The duty tick is staggered per village every 40 ticks, 5 ticks off the village's
  phase, so it never shares a tick with the refresh, the garrison slot or the sweep.
* **Hop cache.** A unit whose goal and home are unchanged, and which is at its final spot or still
  more than half a hop away, is skipped without a ground search.
* **Patrol progress.** A unit pauses at a waypoint on arrival, then moves to the next one; a leg
  times out after one hop timeout per 32 blocks.
* **Scout cycle.** Ride out, watch (dwell), ride back, rest; each ride goes to the next post. Any alert other than CALM sends
  out-riding scouts back at once, and no ride starts until CALM.

### 2.6 M2 defense and duties (sections 1, 11)

* **The M2 deployment stays authoritative** (M3 `Deployment`, M2 `DefenseCoordinator.assign`):
  * an assigned unit becomes DEPLOYED+DEFENSE;
  * a released one becomes RETURNING+RETURNING;
  * a unit back near the anchor **or its own duty home**, or past the return timeout, becomes
    GARRISONED and resumes its standing duty.
* **Hands-off.** The duty service never moves DEPLOYED or RETURNING units. Raid contingents are not
  part of the home deployment and are not recalled by it.

### 2.7 Raids (sections 7, 8)

`RaidService` runs on the attacker's duty tick and polls Millénaire's public raid state (§1 of
`docs/m4-spike.md`). Millénaire itself is not changed, and its raid participants are not touched.
1. **Raid started** (target set, raid start > 0) while the attacker is CALM:
   * `RaidPlanner` picks the contingent from units at home on standing duty. It never takes scouts
     who are out, and never takes units that do not exist yet.
   * Size is `floor(n * commitFraction)`, at most `maxCommit`, and never more than leaves
     `ceil(n * minHome)` at home. Below `minCommit` (or `minGarrison`) nobody goes.
   * Order: GARRISON (roving) duty and the reserve beyond `keepReserve`, then patrol, then whole
     sentry pairs beyond `keepSentryPairs` (the best post stays manned), then resting scouts.
   * Chosen units become DEPLOYED+RAID and muster at the reserve point.
2. **Materialization.** At raid start + 500 ticks, when Millénaire materializes its raiders, and
   only if the target is entity-ticking: the contingent is moved to Millénaire's own landing point
   (`RaidSpawnLocator.findSpawnPoint`). A rider moves with its horse.
3. **At the target.** Every duty tick, a unit without a live target engages the nearest target
   resident within 32 blocks. This is HYW temporary hostility only; the relation stays NEUTRAL.
   With no resident in range, the unit hops towards the target centre.
4. **Raid over** (target cleared, raid history grew, village gone, or older than a day), **or home
   alerted** (M2 ALERT/ENGAGED):
   * loaded survivors are brought back next to the home anchor;
   * DEPLOYED ones take the normal M2 return path, then resume their standing duty;
   * units unloaded at the time follow once they load again.
5. **Deaths** follow the M3 death path: DEAD, no respawn, and a replacement is a new paid recruit.
   The contingent counts against the village's manpower throughout.

### 2.8 Equipment profiles (section 6)

* **Provider selection.** A new provider, `hyw_profiles` in `integration.hyw`, is chosen through
  the existing `EquipmentProvider` SPI. The garrison default `equipmentProvider` is now
  `hyw_profiles`. `EquipmentProvider` gains a default `apply(..., Context)`, `reequips()` and
  `validate()`.
* **Base equipment.** HYW's own `setEquipment(level)` runs first, exactly as the `hyw` provider,
  so the M3 level semantics and the stored applied level are unchanged.
* **Overlay (only if Epic Knights is loaded).** For each slot, the first list from
  `hywmill_equipment` that has at least one item valid for the unit, in this order:
  * the duty role, culture then defaults;
  * the culture's class role, then its "all";
  * the defaults' class role, then their "all".

  The item is picked deterministically by roster id. Without Epic Knights the provider behaves
  exactly as `hyw`. HywMill has no compile-time Epic Knights dependency.
* **Validity rule** (from the M4-0 spike). An item is used only if:
  * it is registered;
  * armour fits its slot (vanilla `Equipable`);
  * a weapon or offhand item's family (id without material prefix) is one HYW's own
    `equipment_epic_knights.json` gives that unit in that slot.

  Riders' lance and main weapons are switched by HYW and never overlaid.
* **Re-equipping.** When a unit's duty role changes (sentry, patrol, scout, reserve, or none), its
  equipment is re-applied (HYW level, then overlay).
* **Profile data.** It covers Norman, Byzantine, Seljuk, Indian, Japanese, Mayan and Inuit, for
  the roles militia, line, ranged, sentry, patrol, scout and reserve. It scales by tier: gambeson
  and wood/stone at WATCH, chainmail and iron at GUARD_POST, brigandine/lamellar and steel at
  GARRISON, plate and steel at STRONGHOLD.
* **Validation tool.** `/hywmill admin equipcheck` (op 3) reports every:
  * unregistered item or wrong armour slot (INVALID);
  * item/unit pair outside HYW's families (INCOMPATIBLE: skipped for that unit);
  * list with no usable item for a unit (FALLBACK).

### 2.9 Wand of Negation (section 9)

The M4-0 spike showed the wand is Millénaire's normal deletion. The existing M3 VILLAGE_GONE
lifecycle handles it and is unchanged:
* **No recruitment into the deleted village.** It leaves the village list, so it gets no garrison
  slot or duty tick.
* **After the grace period** its garrison is LOST(VILLAGE_GONE), and orphan policy KEEP applies.

## 3. Acceptance (section 12)

The dedicated-server harness scenarios are `G4_0`..`G4_10`, `G4_perf` and `G4_EK` in
`devtools/server-harness/harness.py`; `run duties` and `run duties-ek`. Villages: A and B are
Norman agricole (WATCH/GUARD_POST), M is a Norman militaire STRONGHOLD (filled to 64 including 14
riders), and Z is a Byzantine military village.

| Criterion | Check(s), final run | Result |
|---|---|---|
| Distributed standing troops | G4-1a/b/c: quotas ≤ garrison; 11–52 distinct home points per village; the stronghold fields 8 pairs, 8 patrol, 4 scouts and 9 reserve, while the 7-unit village fields 1 pair and 1 patrol | PASS |
| Sentry pairs | G4-2: every pair has two units standing within 10 blocks of its own post, taken from building data | PASS |
| Deterministic patrols | G4-3a/b: identical route on every read; 8/8 patrol units advance | PASS |
| Scouts outside the village | G4-4a/b: riders chosen as scouts; scouts reach 110–141 blocks out (beyond the village radius) and cycle out/watch/back/rest | PASS |
| Duties survive restart | G4-5a/b/c: same assignments, posts, routes and scout posts; no duplicates | PASS |
| M2 defense overrides duties | G4-6a/b: deployed units are DEFENSE with their standing duty kept, then back on it | PASS |
| Casualties permanent; recruitment replaces | G4-7a/b: a killed sentry is DEAD and not respawned; its pair is refilled from the living garrison. Replacement recruitment is the unchanged M3 path (G3-10, §4) | PASS |
| EK equipment by culture/tier/role; unsupported combinations fall back | G4-EK1..5: Norman norman_helmet vs Byzantine chainmail_helmet; sentries in bascinet/greathelm; stronghold plate/brigandine vs chainmail/gambeson; no kiteshield on spear_man, shieldman kiteshield, archer longbow; `equipcheck` finds 0 invalid items and reports 160 incompatible pairs, which are skipped | PASS |
| Raids include HYW troops; the home is not emptied | G4-8a/b: 12 of 62 join; 51 stay home; the contingent lands at Millénaire's landing point next to the target | PASS |
| Raid deaths permanent; survivors return | G4-8c/d: a raider killed at the target stays DEAD; the survivors return to their standing duties; nothing is spawned | PASS |
| Wand of Negation | G4-9a/b: no recruitment or spawning into the deleted village; LOST(VILLAGE_GONE) after the grace period | PASS |
| No global scan; calm cost | G4-10b, G4-P: duty tick mean 57–118 µs across runs; server tick with duties on is within +6 µs of off | PASS |
| M3 duplication invariants | G4-5c, G4-10a: dupSlots 0, unbound 0, badOwner 0, tagged = bound | PASS |

### How the final run got there

Runs 1–3 (evidence kept) found real defects, and each was fixed before the final run:

| Run | Result | Defects found → fix |
|---|---|---|
| 1 | 26/30 | (a) The raid contingent was brought home in the tick it was chosen (cleanup ran after selection) → cleanup only when no raid is running, plus a persisted `lastRaidStart` so a raid is never joined twice. (b) A scout was stuck on hilly ground (a hop spot inside the hill) → intermediate hops prefer the surface; detour hops when a unit does not reach its hop. (c) Sentries could not reach a tower-top post → fallback ground spots around the post |
| 2 | 26/30 | (d) The contingent landed after Millénaire's raiders (materialization is faster than 500 ticks) → it lands 100 ticks after the raid start. (e) Raised defending points (wall walks, tower tops) → the building's ground-level path anchor is used instead. Harness: a raider is now killed at the target so raid deaths are really exercised |
| 3 | 28/30 | (f) Units trapped in pits or wells for good → bounded unstick: after every detour/fallback, a unit that has not moved is moved onto its spot or hop (≤ 40 blocks, loaded, standable). (g) A scout post across water pinned a scout → each ride goes to the next post |
| 4 | **31/31** | — |

## 4. Regression

Final results are from the reruns on the final jar, after the spawn-location fallback (§8). The
earlier pre-final results are kept for history.

| Suite | Final (post-fallback) | Evidence | Earlier (pre-final) |
|---|---|---|---|
| M3 G3 suite (`run garrison`) with M4 | **51/51** (42 M3/M4 checks + 9 G3-18 fallback checks) | `spawn-fallback/g3-final.txt` | 41/42, then 42/42 (`g3-with-m4-run1.txt`, `g3-with-m4.txt`) |
| M2 regression (`run all`, garrison disabled: the frozen M2 baseline) | **70/72**; the only failures are the accepted performance-tail items P3a/P3b (the frozen M2 baseline is 70/72) | `spawn-fallback/m2-final.txt` | 72/72 (`m2-regression.txt`; P3a/P3b passed in that run) |
| JUnit | **178/178** | `./gradlew test` | 172/172 before the 6 fallback tests |

In the first pre-final G3 run (41/42), the failing check was G3-4:
* M2 deployed the two nearest garrison units, and both were in a well next to their duty spot, so
  they could not engage.
* They were within 2 blocks of their spot, only one block below it, so they were not counted as
  stuck.
* A unit that sits a block or more below its spot without moving now counts as trapped, and gets the
  same fallback and unstick. The pre-final rerun gave 42/42.

## 5. Performance (section 10)

* **Nothing global.** Nothing scans entities or the world:
  * units are found by UUID from the roster;
  * the layout is read every 1200 ticks per village (≈ 150–220 µs), and plans are recomputed only
    when the layout key changes;
  * a raid poll is a few field reads (≈ 6–13 µs mean).
* **Duty tick.** Staggered per village every 40 ticks, on a phase that never shares a tick with
  the M2/M3 per-village work. Mean 57–118 µs per village tick (64 units), p99 ≤ 0.8 ms.
* **Hop cache.** A unit at its spot, or still travelling to a valid hop, costs a hash lookup and
  a distance check.
* **Like-for-like (G4-P, same world and units, 120 s CALM each):**

| | duties off | duties on |
|---|---|---|
| tick.total mean | 57.6 µs | 63.2 µs |
| tick.total p99 | 644 µs | 706 µs |
| duty.tick mean | 16.8 µs (raid poll only) | 96.9 µs |

## 6. Known limitations

* **M3 spawn spots (resolved, §8).** A garrison whose defending position had no safe spot used to
  wait unspawned. It now falls back to the village centre for that attempt.
* **Raid combat is brief.** Millénaire resolves a raid by its own strength rules, usually within a
  minute of materialization. The contingent is present and engaging (HYW temporary hostility,
  re-engaged every duty tick), but it lands few hits in that window, and it does not change
  Millénaire's result. Millénaire exposes no API to add strength to a raid without a mixin.
* **Unstick moves.** A unit that has not moved through every detour is moved onto its spot or hop
  (≤ 40 blocks, loaded ground). This is the only movement besides the HYW home; the raid landing and
  return use Millénaire's own materialization model.
* **Raised posts.** Wall walks and tower tops are replaced by the building's ground-level anchor
  where Millénaire gives one. A post still out of HYW's reach (a tower top) gets its sentry moved
  onto it once, after the fallback ground spots.
* **Scouts need loaded, dry ground.** Scouts ride only through entity-ticking chunks, and HywMill
  never force-loads. Without players nearby, scouts watch from the edge of the loaded area; a post
  across water is watched from the shore, and the next ride tries the next post.
* **Equipment is applied once.** Profile equipment is applied at spawn and re-applied only when the
  duty role changes. A later tier change does not re-equip (the M3 semantics).
* **Carried forward.** The M3 known limitations still apply (native HYW targeting, short temporary
  hostility, inactive time tracked at runtime, headless verification).

## 7. Evidence

`docs/m4-test-evidence/`:

| File | What it holds |
|---|---|
| `m4-0-spike-*.txt` | The M4-0 spikes |
| `g4-explore.txt` | First duty run |
| `g4-run1.txt`, `g4-run2.txt`, `g4-run3.txt` | Runs with defects found |
| `g4-run4-final.txt` | 31/31, the last pre-fallback run |
| `g4-epic-knights.txt` | 10/10 |
| `equipcheck-report.txt` | The full validation report |
| `g3-with-m4-run1.txt`, `g3-with-m4.txt` | M3 G3 regression, pre-final (41/42, then 42/42) |
| `m2-regression.txt` | M2 regression, pre-final (72/72) |
| `spawn-fallback/*-attempt1.txt` | First post-fallback attempt (failures and causes in §8) |
| `spawn-fallback/g3-final.txt` | **Final** M3 G3 suite: 51/51 |
| `spawn-fallback/g4-final.txt` | **Final** M4 acceptance: 31/31 |
| `spawn-fallback/m2-final.txt` | **Final** M2 regression: 70/72 (only P3a/P3b) |

## 8. Post-M4 fix: spawn-location fallback (approved change to M3)

**Problem.** In two of the six M4 test worlds, a village's garrison never spawned. M3's spot search
(24 deterministic candidates within 8 blocks of Millénaire's defending position, loaded chunks only)
found no safe spot, so the units stayed RECRUITED indefinitely.

**Fix.** The change is surgical, as approved:
1. `SpawnSpots.choose` first runs the **unchanged** M3 search around the defending position.
2. Only if that finds nothing, it runs the same bounded, deterministic search around the **village
   centre**, for that spawn attempt only. The search uses loaded chunks only (`hasChunk`) and never
   force-loads.
3. If neither location has a safe spot, the slot stays RECRUITED and is retried on the next garrison
   slot, exactly as before.

What does not change:
* The spawn request's HYW home is still the defending position; the stored anchor is not replaced.
* Recruitment, roster state, tier caps, ownership, deterministic UUIDs, join adjudication, duties,
  equipment and the M2 doctrine are untouched.
* The garrison summary now also reports the spawn anchor and the fallback centre (read-only).

**Tests.** JUnit `SpawnFallbackTest` (6 tests):
* the normal defending-position spawn is used, and the centre is not searched;
* when the defending position has no spot, the centre fallback succeeds within the bounded area;
* when both fail over 1000 garrison passes, the slot stays RECRUITED, never lost, at generation 0,
  and the same slot spawns later;
* unloaded terrain is skipped and never loaded;
* a centre equal to the defending position is searched once;
* a fallback spawn keeps the deterministic UUID, is rebound once after a restart, a second copy is
  refused, and its duty assignment is unchanged.

**Dedicated server: G3-18, 9/9.** Barrier roofs over the real candidate areas force each case:

| Check | Result |
|---|---|
| (a) Normal spawn | At the defending position |
| (b) Defending position blocked | The unit spawned near the centre: (652, 80, 625), with the centre at (647, 80, 624) |
| (c) Both blocked | The slot stayed RECRUITED, with no entity and not lost |
| (d) Roofs removed | The **same** slot spawned |
| (e) Force-loading | `forceload query` unchanged |
| (f) Stored state | The anchor and the duty plan are unchanged |
| (g) Restart | No duplicate; the fallback-spawned unit is bound once with the same duty |

**Also fixed in the same reruns (M4 duties).** A sentry whose post is on a tower HYW cannot path
up to is now moved onto the post once, after the three fallback ground spots (≤ 40 blocks, loaded,
standable).

Harness robustness:
* G4-6 now uses a player-owned attacker, which HYW's own targeting ignores, so M2 is what deploys
  (as in G3-4).
* G4-4b samples scouts for 10 minutes.
* G3-18's roof covers exactly the 8-block candidate area.

The first attempt's failures, with their causes, are kept in
`docs/m4-test-evidence/spawn-fallback/*-attempt1.txt`:
* G3-18 roof too wide for that world's 12-block anchor/centre separation;
* G4-2 tower post;
* G4-4b short sampling window;
* G4-6a bandits killed by native HYW targeting before any deployment;
* M2 I2: a defender killed an attacker before the threat list was read, with the garrison disabled.

**Final reruns on the final jar (SHA-256 `e6712d3a…880f`):**
* M3 garrison suite: **51/51**;
* M4 acceptance: **31/31**;
* M2 regression, garrison disabled: **70/72**, only the accepted P3a/P3b;
* JUnit: **178/178**.

## 9. Freeze

**M3 and M4 are frozen** on `claude/millenaire-hyw-audit-5n4u8s`. The final results are the
same in §1, §4 and §8:
* JUnit 178/178;
* M3 G3 suite 51/51;
* M4 acceptance 31/31;
* Epic Knights 10/10;
* M2 regression 70/72 (only the accepted P3a/P3b). The M3 freeze
(`docs/m3-freeze.md`) holds, with the single approved addition in §8. Further work is limited to
documentation and cleanup unless a new reproducible correctness defect is found.
