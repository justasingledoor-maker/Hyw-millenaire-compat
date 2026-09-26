# HywMill M5-0 Spike Report

**Status: STOPPED at the M5-0 boundary. M5-1 has not been started.**

One spike failed in a way the stop rule covers: **Spike B (player ↔ village HOSTILE)**. HYW's
public relation API does drive soldier-versus-soldier combat under DEFAULT exactly as designed.
But the same projection makes **every Millénaire resident of that village, civilians included, a
valid DEFAULT target**, because M1.1 marks all residents with the village faction's relation
identity. Fixing this needs a change to the frozen M1.1 identity semantics, so this report proposes
options and asks for a decision (§4). No workaround has been implemented.

* **Environment.** Dedicated server; NeoForge 21.1.226, Minecraft 1.21.1, Millénaire 9.0.2, HYW
  0.7.1r-fix1. Epic Knights was not needed for these spikes. Fresh world, seed 20260925.
* **Build.** Spike build from branch `claude/millenaire-hyw-audit-5n4u8s`. `hywmill-0.1.0-m1.jar`
  SHA-256 `3c83aa2f23ae002afa39ec1b075651342ec80e5a4b46d887884a353a5d2cfb1e`. It includes the
  dev-only spike tooling; it is not a release.
* **Evidence.** Everything is in `docs/m5-test-evidence/`:
  * `m5spike-run1.txt` … `m5spike-run5.txt`: harness output, every check and note;
  * `sg-offline-probe-current-data.txt`;
  * `sg-scout-militaire.txt`.
* **JUnit.** 178/178, unchanged. No production behaviour was changed.

## 1. Method

* **Static analysis.** `javap -c` of the pinned jars, for every foreign method M5 would call.
* **Runtime analysis.** A dedicated server driven by the harness (`run m5spike`, plus focused
  reruns with `--keep-world`) and dev-only tooling (`/hywmill dev m5 …`, gated by
  `general.devCommands`). The tooling adds:
  * **HYW probes.** Identity, relation get/set, `isValidTarget`, the relation-protection
    predicates, temporary hostility, and hits by kind: melee, a real arrow, an explosion.
  * **A damageable stand-in player.** A NeoForge `FakePlayer` added to the level, because a real
    client cannot join a headless run. It stands in for a player entity as a target.
  * **A test diplomacy policy.** Held only by the running server's runtime and never persisted. It
    stands in for M5's `PoliticalPolicy`.
  * **An M2 threat feed.** It feeds one M2 scan, going to `DefenseService.onScan` directly.
  * **An escort probe.** It reuses M4's own hop resolution unchanged.
  * **Millénaire probes.** Relations, raid planning, diplomacy points, history, reputation,
    traded goods and shops.
* **No production path changed.** The only production-visible edits are two:
  * a dev setter for the diplomacy policy (default unchanged, `ALWAYS_REVERT`);
  * a registry for dev command subtrees.

**Harness artefacts found and removed along the way.** None of these is HYW or Millénaire
behaviour; the report does not use results that depend on them.

1. **`/summon`-ed HYW units spawn with empty equipment slots and strategy `FREE_FIGHT`.** Spike
   units are now armed and set to `DEFAULT` explicitly. Garrison units already use `DEFAULT`
   (M3).
2. **A heightmap placement put units on house roofs** when their target stood indoors, so HYW
   dropped the unreachable target. Placement is now outdoors, or next to the target.
3. **`ServerPlayer` has 60 ticks of spawn invulnerability** that only its own tick counts down, and
   `FakePlayer` disables that tick. The stand-in now clears it.
4. **The escort "unloaded" goal was actually loaded.** Millénaire keeps its villages' chunks
   loaded. The escort run now surveys for genuinely unloaded, dry terrain.

## 2. Results by spike

### Spike A: identity of player-owned HYW units: PASS

| Question | Observed (runs 1–2) |
|---|---|
| Relation identity of a player-owned unit | **The owner's (player's) UUID** (`getRelationUUID` = `OwnerUUID`). Bytecode: `BaseCombatSupport` → `getOwnerUUID()`; `Player` → own UUID |
| Does an HYW team UUID replace it? | **No.** With the player owning a team (`createTeam`), the unit's identity is still the player UUID. HYW uses teams only in its UI packets; `TeamRelationUpdatePacket` copies a team relation onto each member with `setRelation` |
| HOSTILE on the team UUID | Does **not** make the member's units enemies (`enemy=false` both ways) |
| `setRelation(player, faction, HOSTILE)` | Stored **both ways** (HYW writes the reverse HOSTILE itself) and makes the player's units and the garrison mutual enemies (`valid=true enemy=true` both ways) |
| Default strategy of a summoned player-owned unit | `FREE_FIGHT`. M5 cannot rely on player units being `DEFAULT`; the player chooses |

`joinTeam` for a second, never-seen UUID returned `false`: HYW's join flow expects a known player
record. This does not matter for M5, since teams are not combat identities.

### Spike B: player ↔ village HOSTILE: **FAIL (stop condition)**

**What works.** Measured in the field, away from every village (run 5, S5-W), with units kept
alive:

| | baseline NEUTRAL | HOSTILE (8 samples) | 4–32 s after NEUTRAL both ways |
|---|---|---|---|
| Player units targeting garrison units | 0 | 3/3 every sample | 0 |
| Garrison units targeting player units | 0 | 3/3 every sample | 0 |

* The enemy garrison also targets and damages the (stand-in) player: runs 2 and 3, and S5-G.
* An unrelated third party (another owner) is never targeted and never targets: S5-B, both runs.

**What fails.** Inside the village, the same projection made the player's units attack Millénaire
villagers, **civilians included**:

* **Run 2, S5-B.** Over 40 s: 4 samples of player units targeting villagers, 2 of them
  **CIVILIAN**; 0 samples of them targeting the garrison.
* **Incident-ledger chronology.** The first hostile act was a player unit hitting a Millénaire
  defender (tick 4128), before any villager had hit it. So this is not retaliation.
* **Run 3, S5-V: controlled confirmation** with a NoAI player unit and direct predicate calls:

| Target (resident of the village) | NEUTRAL | player ↔ faction HOSTILE |
|---|---|---|
| Identity-marked **civilian** | `valid=false` | **`valid=true enemy=true`** |
| Identity-marked defender | `valid=false` | `valid=true enemy=true` |
| The same civilian with its marker removed | — | `valid=false` (`participant=false`) |

**Cause (bytecode, `BaseCombatEntity.isValidTarget`, after the temporary-retaliation check):**

```
if (!(target instanceof BaseCombatEntity) && !(target instanceof Player) && !(target instanceof TamableAnimal)
        && ServerRelationHelper.canParticipateInRelation(target))        // = relation identity is known
    return isEnemyRelation(this, target);                                // "relation_participant"
```

* M1.1 marks **every** resident with the village faction identity, so every resident is a
  relation participant.
* Under HOSTILE, every resident is therefore a legitimate DEFAULT target.
* The design audit (§16.1) said "villagers are never relation targets". That is true only for
  **unmarked** entities; for HywMill's marked residents it is **wrong**.
* The same holds for village ↔ village war. In run 2 (S5-N), A's units, projected HOSTILE to B,
  attacked B's defenders 7 times and never B's garrison in that window. The civilians are just as
  valid (S5-V).

This violates the M5 rule "civilians must remain protected by default". It cannot be fixed
inside HYW's public API without changing **who carries which identity**, which is frozen M1.1
(and possibly M3) semantics. Per the stop rule, no workaround was built; see §4.

### Spike C: FRIENDLY co-belligerents: PASS

Runs 1 and 2 give identical results. The test pair is a player-owned unit and a faction-owned
unit.

| Check | Observed |
|---|---|
| Control (NEUTRAL) | Melee 20→18.5, arrow 20→17.5, explosion 20→10.03: all damage applied |
| FRIENDLY set both ways | Stored both ways (FRIENDLY is **directed**: two calls) |
| Targeting | `valid=false`, `protected=true` |
| FRIENDLY vs temporary hostility | Marked hostile (`temp=true`) and still `valid=false` |
| Melee | 20→20, cancelled |
| Arrow (a real projectile owned by the ally) | 20→20, cancelled |
| Explosion caused by the ally (area) | 20→20, cancelled |
| Collision | `shouldIgnoreFriendlyCollision=true` |
| One-way FRIENDLY | Already protected (`isRelationProtected` checks both directions); M5 still sets both |

Player-caused friendly fire stays governed by HYW's own `enablePlayerFriendlyFire`, as designed.

### Spike D: restoration: PASS

| Check | Observed |
|---|---|
| `setRelation(a,b,HOSTILE)` | Writes a→b **and** b→a |
| `setRelation(a,b,NEUTRAL)` | Clears **only a→b**; b→a stays HOSTILE. Clearing needs two calls. HYW logs "immunity started" per cleared direction |
| Immunity window | Bytecode: 30 000 ms of real time during which `recordDamage(a,b)` returns 0. It suppresses only the damage-count escalation for that direction; it does not change targeting or `die()` escalation |
| Residual targeting after NEUTRAL | None from the first sample (4 s), in the field with all units alive (run 5) |
| Exact restoration of an asymmetric previous state | (FRIENDLY, NEUTRAL) → projected (HOSTILE, HOSTILE) → restored (FRIENDLY, NEUTRAL), by setting each direction explicitly (run 1) |
| Persistence across a restart | HywMill-written relations persist through HYW's own save: one-way FRIENDLY and a HOSTILE pair (runs 1 and 2). `ensureRelationDataExists` gives non-player UUIDs a record, so HYW's load-time cleanup keeps them |

Run 2's restoration check failed on a **real side effect**, not a restore defect:

* the player's units were killed by the garrison while NEUTRAL;
* HYW escalated that kill to HOSTILE (`die()`);
* the test policy, still active, kept it.

The projector must therefore compute "previous state" from its own records, not from whatever HYW
holds at that moment. The design already says this (§16.6).

### Spike E: escalation guard with a political policy: PASS

Runs 1 and 2 give identical results.

| Step | Observed |
|---|---|
| A neutral third party kills a faction unit | HYW sets third↔faction HOSTILE at once |
| No political cause | The guard reverts it to NEUTRAL within 15 s ("Permanent HYW HOSTILE between village faction … reset") |
| The policy permits the pair | HOSTILE is kept (15 s later still HOSTILE) |
| The cause is removed (policy cleared) | Reconciliation reverts the pair within 15 s |
| After a restart (the test policy is not persisted) | A HOSTILE village pair with no cause is reverted |

`DiplomacyPolicy.permitsPermanentHostility` is the right seam. `PoliticalPolicy` only has to answer
it from the persisted political state.

### Spike F: combatant villagers through temporary hostility: PASS

Run 3, with the unit placed next to a Millénaire defender (a seneschal).

* Baseline: an owned unit next to villagers targets none of them.
* After `markHostile(unit, defender)`: the defender is `valid=true` (through `temp=true`, not by
  relation); a civilian stays `valid=false`.
* `markHostile` alone: HYW's own selection did not pick the defender within 15 s.
* `markHostile` + `setTarget`, re-applied every ~3.5 s (M4's engagement): the unit engaged the
  defender (4 of 6 samples) and **never selected a civilian**.

This matches the approved M4 change: raid contingents engage combatants through temporary
hostility only. The relation is never touched.

### Spike G: a player as an M2 threat: PARTIAL

| Check | Observed |
|---|---|
| M2 alert raised by a player threat | Yes (ALERT → ENGAGED) |
| Coordinator commits defenders | Yes (2 → 6 committed) |
| HOSTILE garrison engages the player natively | Yes (targeted; stand-in health 20 → 6) |
| Bystander player | Never targeted, never damaged |
| Creative-mode player | Not a valid HYW target (`valid=false` although `enemy=true`) |
| Millénaire defenders take the player as their attack target | **Not observed** |

The last row is not a defect of the idea. The spike fed the threat straight into
`DefenseService.onScan`. The defender half of the bridge (`HuntMonsterDecorator`) resolves the
assigned threat through `ThreatTracker.threatEntity`, which the spike bypassed. `EngageTargetDecorator`
leaves Player targets to Millénaire's own `EngageTargetGoal`, which accepts `ServerPlayer`.

`OUTLAWED_PLAYER` therefore needs the tracker itself to consider players, an additive M5-3 change
already in the design, and a real verification then. Doctrine semantics are unchanged.

### Spike H: escort movement: PASS

Run 4, in a surveyed dry corridor north of village A.

* The probe unit followed moving goals through loaded terrain with M4's hop resolution (home moves
  of ≤ 16 blocks) and reached the last loaded waypoint.
* With the goal in unloaded terrain (`goalTicking=false`), the hop resolution returned **hold**.
  The unit stayed on loaded ground at the edge.
* Nothing was force-loaded: the `forceload query` output was identical before and after.
* When the terrain was loaded (standing in for the player walking on), the escort resumed and
  reached the goal.
* No teleport: the largest displacement in any 2.5 s sample was 14 blocks (walking and running).
* **Static finding:** HYW's own `FollowEntityGoal` calls `teleportTo` to catch up. The escort
  must not use HYW's follow order; the probe unit's `followTarget` stayed null.

### Spike I: armoury through Millénaire content: PASS (no code)

A Millénaire **content sub-mod** in the server directory, with no jar and no HywMill code:

```
millenaire-custom/hywmill_armoury/cultures/norman/traded_goods.json   (adds good "hywmill_scroll_archer")
millenaire-custom/hywmill_armoury/cultures/norman/shops/armoury.json  (armoury sells list + the new good)
```

* The good is loaded as `hundred_years_war:scroll_archer`, price 64, **`minReputation` 8192** (it
  resolves to the HYW item).
* The Norman armoury sells the original goods plus the new one.
* The shop file **replaces** the sells list, so an armoury pack must repeat the originals.
* `min_reputation` is Millénaire's own reputation gate, so "patron-only" is expressible in data.

This only verifies that the goods load. Buying in the trade screen needs a real client.

### Spike J: donations: PASS (calibration)

* **Bytecode (`TradeMenu.executeSell`).** In donation mode the player receives no deniers, and the
  village reputation changes by **4 × the goods' value** (×1 in a normal sale).
* **Runtime.** `adjustReputation(+400)` → village +400, culture +40: the culture value always
  moves by a tenth. Combined −3300 → −2860.
* **Pardon calibration.** Leaving outlawry needs combined reputation above −1024:
  * each denier of donated goods raises combined reputation by about **4.4 points** (4 village +
    0.4 culture);
  * a player at −3000 combined needs about **450 deniers** of donations;
  * suggested: tie the pardon's grievance decay to the same scale (see §5).

### §13.1–13.3 and §16.7-7: Millénaire relations, diplomacy points, history, faction war

| Spike | Observed |
|---|---|
| `adjustRelationSymmetric` | 50/50 → 60/60 (both villages) |
| **Truce and raid** | A raid planned at −95, then lifted to −85 before its start: **aborted** at start ("Raid aborted (relation improved)"; bytecode: the check runs once, when `dayTime − planningStart ≥ 24000`). Control at −95: the raid starts. A raid that has already started is not cancelled by relation |
| Nightly drift | 200 direct nights at −85: 1 change (−15). The truce floor must be re-applied after the nightly drift, on HywMill's slot |
| Relation persistence | −42 written by HywMill survives a restart |
| Diplomacy points | Per player per village; default **0**; nightly regeneration sets **online** players' points to 5, offline players are skipped; `consumeDiplomacyPoint` spends one and refuses at 0 |
| **History (chronicle)** | `recordEvent` appends the raw text (no translation) with the server's *uptime* tick; max 1000 entries. **It is session-only.** After a restart the history was empty (`size=0`); no Millénaire code saves or loads it |
| Faction ↔ faction HOSTILE (field) | Two village garrisons fight each other, 3/3 both ways every sample (run 5). Inside a village, B's marked residents are targets too (Spike B) |
| Travel-book / client display of relations | Not verifiable headless |

**Consequence of the history finding (not a stop condition).** M5's chronicle and honours must be
**persisted by HywMill** (ledger format 5). Millénaire's history can only be a display mirror,
re-emitted after a restart if that is wanted.

### Spike S-G (garrison scale): prepared, not run

S-G belongs to M5-G, which is not part of M5-0. What is ready:

* the offline probe of the current M3/M4 functions at the locked sizes
  (`sg-offline-probe-current-data.txt`);
* the formula model (`devtools/sg_model.py`);
* the harness scale run (`run sgscale`: real village inputs, a test-world datapack with the locked
  caps, 2 strongholds, CALM/ALERT/raid with `/tick query`);
* a scouted second stronghold site (380, 72, 600).

What the current data gives:

* **Duty staffing.** At 128 units a STRONGHOLD staffs only **47** active roles (8 sentry pairs,
  8 patrol, 4 scouts, 19 reserve); 81 stay on GARRISON duty. At 72, 38 are active. At WATCH, 5 are
  active at any size.
* **Raids** are capped at **12** (Seljuk 16) at every size.

This confirms the need for the approved M5-G data retune.

## 3. Summary

| Spike | Result |
|---|---|
| A identity | PASS |
| **B player ↔ village HOSTILE** | **FAIL: marked civilians become DEFAULT targets** (soldier-vs-soldier works) |
| C FRIENDLY | PASS |
| D restoration | PASS |
| E escalation guard + policy | PASS |
| F combatant villagers | PASS |
| G player as M2 threat | PARTIAL (the defender half needs the M5-3 tracker change to verify) |
| H escort | PASS |
| I armoury | PASS (content pack, no code) |
| J donations | PASS (calibration) |
| §13.1 relations / truce | PASS; history is session-only (design consequence) |
| §16.7-7 faction war | Soldier-vs-soldier PASS; residents: see B |
| S-G | Prepared; runs with M5-G |

Nothing required a mixin or a change to Millénaire or HYW. The failure in B is an interaction
between HYW's public targeting rule and HywMill's own M1.1 identity markers.

## 4. Decision needed: resident identity under a war projection

Four options, all using public HYW API only.

**Option 1: a separate "resident identity" per village (recommended).**
* All Millénaire residents are marked with a deterministic per-village resident UUID (e.g.
  `FactionIds.residentsOf(village)`) instead of the faction UUID.
* The garrison keeps the faction UUID (M3 ownership unchanged).
* HywMill keeps a permanent FRIENDLY in both directions between resident identity and faction
  identity. Spike C verified that FRIENDLY gives the same protection the shared identity gives
  today: not a target, damage cancelled, collision ignored.
* Wars and campaigns are projected only on the faction (military) identity. So residents are never
  relation targets: civilians are always protected. Combatant villagers are engaged only through
  temporary hostility, as the approved M4 raid change and Spike F already do.
* **Changes to frozen code:**
  * M1.1 marker target UUID;
  * `FactionRegistry` knows both identities, so the escalation guard and incidents keep working;
  * a one-time permanent FRIENDLY projection per village.
* Existing worlds migrate naturally. The M1.1 lifecycle re-marks residents (observed: within
  25 s). The M1.1 `clear-identities` / `restore-identities` commands keep working on the new
  marker.
* Needs your approval (frozen M1.1 identity semantics).

**Option 1b: as 1, but SOLDIER/LEADER keep the faction identity.**
* Soldiers would be relation targets for the duration of a war.
* That contradicts "do not make combatant villagers HOSTILE identities", so I do not recommend it.

**Option 2: a separate military identity for garrisons.**
* It changes M3 unit ownership (a persistent identifier) and every existing unit.
* Much more invasive. Not recommended.

**Option 3: no markers on residents.**
* Residents would never be relation targets.
* But they lose HYW friendly-fire protection from their own garrison, and M1.1's escalation
  handling. Not recommended.

**Option 4: no relation projection; temporary hostility per unit pair.**
* It would need per-tick scanning and re-marking between armies, and it gives up HYW's native
  targeting. Not recommended.

If you approve Option 1, the next step is a **short follow-up spike** before any M5-1 code. It
would repeat S5-B, S5-N and S5-V with residents on the resident identity, and re-run the M1.1
identity scenarios (G, D5) and the M2/M3/M4 regressions. Only then would M5-1 start.

## 5. Other design consequences recorded for M5

1. **Chronicle.** Persist HywMill's chronicle in the ledger; mirror to Millénaire's session-only
   history.
2. **Projector writes.**
   * HOSTILE is written symmetrically by HYW.
   * NEUTRAL and FRIENDLY are directed, so the projector sets and clears **each direction**.
   * Restoration comes from the projector's own records.
3. **Truce.** Lift the relation above −90 before a planned raid's start (at least 24 000 day-time
   ticks after planning); re-apply the floor after nightly drift. A started raid is unaffected.
4. **Diplomacy points** are Millénaire's per player per village; they are 0 until the first
   nightly regeneration with the player online.
5. **Armoury** is a content pack: shop files replace sells lists; `min_reputation` gates on
   Millénaire reputation.
6. **Escort** never uses HYW's follow order (it teleports); M4 hops only.
7. **Player units' strategy** is the player's choice (summoned units default to `FREE_FIGHT`). The
   ROE model must not assume DEFAULT for player-owned units. It does not need to: war targets
   come from relations in both DEFAULT and FREE_FIGHT.
8. **Pardon calibration:** about 4.4 combined reputation points per denier donated (−3000 → −1024 ≈ 450 deniers).

## 6. Not verified headless (accepted limitations of this spike)

* Real client behaviour: the travel book and client display of relations, trade-screen purchase
  of the armoury good, client FPS.
* A real (non-`FakePlayer`) player as a target. The stand-in is a `ServerPlayer` subclass, so HYW's
  checks see a player, but movement and client-side effects are absent.

## Appendix: static findings (javap of the pinned jars)

### HYW 0.7.1r-fix1

| Item | Observed in bytecode | Consequence for M5 |
|---|---|---|
| `RelationSystem.getRelation(a, b)` | Directed map lookup; `a == b` → CONTROL; missing → NEUTRAL | Projection must reason per direction |
| `setRelation(a, b, HOSTILE)` | Writes `a→b` **and** `b→a` HOSTILE, clears both immunity records, syncs both to clients | HOSTILE is always symmetric when set |
| `setRelation(a, b, NEUTRAL)` | Removes only the `a→b` entry; if it was HOSTILE, records `hostileToNeutralSwitchTime[a][b]` (immunity start) | Restoring from HOSTILE needs **two** calls, one per direction |
| `setRelation(a, b, FRIENDLY/CONTROL)` | Writes only `a→b` | FRIENDLY must be set in both directions explicitly |
| Immunity | `recordDamage(a, b)` returns 0 for 30 000 ms (real time) after `a→b` went HOSTILE→NEUTRAL | Only suppresses the damage-count escalation for 30 s; it does not affect `die()` escalation or targeting |
| `ensureRelationDataExists(uuid)` | Creates a `PlayerRelationData("Unknown")` entry for any UUID used in `setRelation` | Faction UUIDs survive HYW's `cleanupInvalidRelations` and are saved |
| Identity (`resolveDirectRelationIdentity`) | Player → own UUID; `BaseCombatSupport` → `getOwnerUUID()`; tamed animal → owner; marked entity → marker | A player's units resolve to the **player UUID**; teams are not consulted |
| Teams | `getPlayerTeamUUID` is used only by HYW's UI packets; `TeamRelationUpdatePacket` copies a team relation onto each member with `setRelation` | Team UUIDs are not combat identities |
| `isEnemyRelation` / `isEnemyRelationByUUID` | Enemy iff HOSTILE in either direction (or exactly one side null-owned) | One HOSTILE direction is enough to fight |
| `isRelationProtected` | Same owner, blacklist pair, or (no HOSTILE either way and) FRIENDLY/CONTROL in either direction | FRIENDLY one way already protects |
| `shouldCancelFriendlyDamage` | Player attacker: governed by HYW's `enablePlayerFriendlyFire`; otherwise relation participants → `shouldIgnoreFriendlyCollision` → `isRelationProtected` (+ passengers) | Friendly-fire protection covers any damage source HYW resolves to an attacker entity (runtime: melee, arrow, explosion) |
| `LivingEntityHurtMixin` | NEUTRAL pair: `recordDamage` then `setRelation(HOSTILE)` after repeated damage (escalation) | Existing M1.1 guard covers it; PoliticalPolicy decides |
| `AttackStrategy` | CEASE_FIRE, DEFAULT, FREE_FIGHT, FREE_ROAM, INDISCRIMINATE | |
| `FollowEntityGoal.tick` | Calls `teleportTo` (pet-style catch-up) when the followed entity is far | Escorts must **not** use HYW's follow order; M4 home hops only |
| No `teleportTo` in `BaseCombatEntity` | Home movement is pathfinding only | Home-based escort never teleports |
| `TemporaryHostileTargetManager` | Per (unit, target) entry, 11 s (220 ticks) | Retaliation and combatant marking stay per entity |

### Millénaire 9.0.2

| Item | Observed | Consequence |
|---|---|---|
| `Village.setRelation`, `adjustRelationSymmetric(level, other, delta, notify)` | Public | Envoy results and truce floors use these |
| `RaidManager.planRaid` / `tickRaid` | A planned raid starts once `dayTime − planningStart ≥ 24000`; `startRaidInternal` aborts it ("Raid aborted (relation improved)") if the relation is above −90 at that moment and the raid is not forced | A truce that lifts the relation above −90 before the start cancels the raid; a raid already started is not cancelled by relation |
| `VillageDiplomacyHelper.performNightlyDiplomacyDrift` | Per relation per night: 10 % chance of a change | A HywMill floor must be re-applied after each night |
| Diplomacy points | `PlayerCultureReputation.diplomacyPoints[player][village]`, default 0, max 5; `regenerateDiplomacyPointsForPlayers` sets every **online** player's points to 5 (nightly); `consumeDiplomacyPoint` public | Points are per player per village; envoy missions consume them through the public call |
| `Village.recordEvent(level, text)` | Appends a `VillageHistoryEntry(tick, text)`; `MAX_HISTORY_SIZE = 1000` | Chronicle text is stored raw |
| Donation | `TradeMenu.executeSell`: in donation mode the player gets no deniers and `adjustReputation(value × 4)`; otherwise `value × 1` | Weregild = 4 reputation per denier of goods donated |
| `ReputationConstants` | BOYCOTT −1024, HIRE 4096, FRIEND_OF_THE_VILLAGE 8192, ONE_OF_US 32768, donation multiplier 4 | |
| Content sub-mods | `<gameDir>/millenaire-custom/<submod>/cultures/<culture>/traded_goods.json` and `shops/<shop>.json` are layered over the built-in content; `TradeGood.minReputation` exists | An armoury needs only a content pack |
