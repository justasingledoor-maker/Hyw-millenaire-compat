# HywMill M5: Politics, Diplomacy, Rules of Engagement and Garrison Scale (Report)

Pins: Minecraft 1.21.1, NeoForge 21.1.226, Millénaire 9.0.2, HYW 0.7.1r-fix1. One JAR, no mixins, no
changes to Millénaire or HYW, compileOnly against the pinned jars, foreign imports only in
`integration.*`. Branch: `claude/millenaire-hyw-audit-5n4u8s`. Design: `docs/m5-design.md` (§18 is
authoritative). Spikes: `docs/m5-spike.md`.

**Status of this report:** release candidate, frozen. The production code is frozen at `fd13410`, and the release jar
is `dist/hywmill-m5.jar`, SHA-256 `b08886a8d323528cc11ea94c565d956ee5f48a05693ff485cbd00ada801d8ecd` (§6). Escorts were
deferred out of M5 (§4.3 A). G4 is 30/31: G4-2 is an accepted validation limitation, not a production defect (§4.3 B).

## 1. What was built, by phase

| Phase | Content | Main classes |
|---|---|---|
| Option 1 (approved) | Per-village resident identity; permanent resident ↔ faction FRIENDLY; resident identities never HOSTILE; frozen again (`docs/m1.1-freeze.md` addendum) | `FactionIds.residentsOf`, `ResidentAlliance`, `FactionRegistry`, `FactionMarker`, `EscalationGuard` |
| M5-G (approved) | C3 target formula, locked caps 24/48/72/128, target-scaled levy, load-state gate, duty/raid data retune | `Recruitment.target(TargetInputs…)`, `ScalingGate`, `hywmill_garrison/defaults.json`, `hywmill_duties/defaults.json` |
| M5-1 | Pure politics core: standing, grievances, favor, tables, records, persistence (ledger format 4 → 5) | `politics.*`, `PoliticsNbt`, `VillageRecord.politics` |
| M5-2 | Status refresh, event-driven grievances, chronicle (persisted, mirrored to Millénaire), word travels, intel, commands | `PoliticsService`, `PoliticsView`, `PoliticsCommands` |
| M5-3 | Outlaw threat (`OUTLAWED_PLAYER`), `PoliticalPolicy` replacing ALWAYS_REVERT, outlaw HYW projection on the faction identity, formal pardon (weregild) | `PoliticalPolicy`, `Pardon`, `ThreatTracker` |
| M5-4 | Envoys (reconcile, truce, encourage, sow discord) with seeded logistic odds, travel, truce floor, raid truce check, offline reports | `DiplomacyOdds`, `EnvoyService`, `DiplomacyCommands` |
| M5-5 | Requests (detachment; escorts deferred, §4.3 A), Favor from service, casualty cost, approved Reconciler away-clock pause | `Requests`, `ErrandService`, `Reconciler`, `Duty.ESCORT/DETACHED` |
| M5-5b | Automatic wars, campaigns, relation projector with previous-relation records, `ENEMY_COMBATANT`, raids engage combatant villagers only (approved) | `politics.war.*`, `RelationProjector`, `WarCommands`, `RaidService` |
| M5-6 | Armoury content pack (data only, Patron-gated) and honours | `content/millenaire-custom/hywmill_armoury`, `devtools/make_armoury_pack.py` |
| M5-UI | Unbound keybind, versioned payloads, Politics screen over the shared API | `net.*`, `client.*`, `PoliticsView.actions`, `PoliticsActions.submit` |

## 2. Design notes

### 2.1 Architecture rules kept

* **Political state is authoritative; HYW relations are a projection.** Outlawry, wars and campaigns
  live in the ledger. `PoliticsService.project` (outlaws) and `RelationProjector` (wars, campaigns)
  write HYW relations and reconcile them every 200 ticks. The projector remembers the relation it
  replaced on each edge it changed and restores it; it never touches an edge it did not change.
* **Resident identities are never HOSTILE.** The guard reverts any permanent HOSTILE on a resident
  identity before asking the policy; the relation plan never contains one. Combatant villagers are
  engaged only through HYW temporary hostility (raid contingents, approved M4 change).
* **DEFAULT targeting only.** Nothing sets INDISCRIMINATE.
* **No new SavedData.** Everything is in `GarrisonLedger`, format 5: per-village `politics`
  (players, truces, sow-discord cooldowns, chronicle), and ledger-level `envoys`, `envoyReports`,
  `wars`, `campaigns`, `projections`. All keys are optional; format-4 ledgers load unchanged
  (political memory starts empty).
* **No static mutable HywMill state.** Services hang off `HywMillRuntime`: `politics()`, `envoys()`,
  `relations()`, `uiLimiter()`. Data tables use the existing volatile-immutable-snapshot pattern.
* **Commands and the GUI share one backend.** Reads go through `PoliticsView`, writes through
  `PoliticsActions`. The screen's options are dry-run evaluations by the same services.

### 2.2 Changes to frozen M3/M4 (all approved)

| Change | Where | Kind |
|---|---|---|
| Target and levy formula terms; caps 24/48/72/128 | `Recruitment`, `TierRule`, `GarrisonTable`, garrison data | M3 code (pure) + data |
| Load-state gate (no scaling decision from a partial village load) | `ScalingGate`, `GarrisonService.slot` | M3 code, requested with the approval |
| Duty maxima and raid `maxCommit` at the new sizes | duty data | M4 data only |
| Missing clock paused for DETACHED (lent) units in unloaded chunks | `Reconciler` (additive overload) | M3 code |
| Raid contingents engage combatant villagers only | `RaidService.advance` | M4 code |
| Recovery of a trapped unit whose target is beyond 40 blocks | `DutyService.recoverySpot` | M4 code (`docs/m4-report.md` addendum) |
| Stuck home-duty units fall back to the village (last-resort move) | `StuckWatch`, `DutyService` | M4 code (addendum) |
| Post exclusion after a recovery (10 min, runtime only); a recovered scout resumes its ride | `StuckWatch.Avoid`, `DutyAllocator.Candidate.avoid` | M4 code (addendum) |
| Diagnostic log line when a unit is held 10–24 blocks from its spot (no behaviour change) | `DutyService.holdDiag` | M4 code (addendum) |

Additive, not behaviour changes: `Duty.away()` generalises the existing `== RAID` exclusions to the
new errand duties; `RaidService` skips joining a raid between villages under a truce (§10 of the
design).

### 2.3 M5-G gate

After a restart or chunk load the first profile refreshes can see a partial village (observed in
S-G: a stronghold briefly assessed as GUARD_POST). `ScalingGate` makes the target authoritative
only when the record has been refreshed since this activation, needs no migration recompute, and its
inputs have been stable for `settleTicks`. Until then the target is `min(computed, live)`: no growth,
no trimming, no starting grant.

## 3. JUnit

**275/275** on the release commit (248 at M5-UI; then the escort deferral, `DutyRecoveryTest` 6, `StuckWatchTest` 11,
`PostExclusionTest` 7). The original M5 figures: 201 before M5-G; 47 new: C3 formula and neutral equivalence, scaling gate, duty data,
pardon, diplomacy odds, requests, Reconciler pause, war/plan/RoE, wire format, persistence).

## 4. Server evidence (`docs/m5-test-evidence/`)

### 4.1 Results

| Suite | Jar | Result | File |
|---|---|---|---|
| Option 1 follow-up (S5-P/V/B/N/W, D, G) | Option 1 + M5-1 | pass | `opt1-run1.txt` |
| M2 regression (garrison off) | Option 1 + M5-1 | 71/72 (A4 was the harness format-3/4 regex; fixed) | `opt1-regress-m2.txt` |
| G3 (M3) | Option 1 + M5-1 | 51/51 | `opt1-regress-g3.txt` |
| G4 (M4) | Option 1 + M5-1 | 31/31 | `opt1-regress-g4.txt` |
| M4 world → M5 migration | M5-UI | **10/10** | `migrate4-run1.txt` |
| M5 phases (G5_G, G5_2 … G5_5b, G5_6, G5_UI), full | `3765f830…` | **101/103** (both failures: §4.3 A) | `final-m5-1.txt` |
| G3 (M3) with M5-G | `3765f830…` | **51/51** | `final-g3-1.txt` |
| G4 (M4) with M5-G data | `3765f830…` | 30/31 (G4-2) | `final-g4-1.txt` |
| G5_5 alone | `884d4414…` | 15/16 (§4.3 A) | `g55-run6.txt` |
| G4 (M4) with M5-G data | `884d4414…` | 29/31 (G4-2, G4-4b; §4.3 B) | `final-g4-2.txt` |
| G4, settled G4-2 window (harness) | `eb3b2ba2…` (40-block recovery fix) | 31/31 | `g4-poll1.txt` |
| G4 + stuck-unit fallback | `b7f49681…` | 30/31 (G4-P), 30/31 (G4-5b), 28/31 (G4-2, G4-5a, G4-P) | `g4-stuck1/2/3*.txt` |
| G4 + diagnostics | `0fc8c4b0…` | 29/31 (G4-2, G4-4b); re-stuck cycle confirmed | `g4-diag4*.txt` |
| G4 + post exclusion | **`b08886a8…` (release)** | 30/31 (G4-2); 0 same-post returns | `g4-excl1*.txt` |
| G4, 10-min G4-2 window (harness only) | **`b08886a8…` (release)** | **30/31 (G4-2, accepted limitation)** | `g4-final*.txt` |

Earlier M5 runs and their fixes: `m5-run1.txt` (67/79), `m5-run2.txt` (93/101), `m5-run3.txt`
(100/103), `g55-run4.txt`, `g55-run5.txt`; the commit messages record each root cause.

### 4.2 Verified on the server

* **M5-G:** shipped caps are the locked caps; every village's target equals C3 on its own inputs;
  after a restart the gate holds the target at the live count until the village has settled.
* **M5-2/M5-3:** real-event grievances; immediate peacetime-killing outlawry; word travels; intel;
  chronicle mirrored and persisted; outlaw HOSTILE on the faction identity only, kept by the guard,
  never on residents; the outlaw is an M2 threat and is engaged with `proactive=false`; civilians
  do not attack; formal pardon; persistence.
* **M5-4:** requirements, diplomacy point spent, travel, seeded outcome applied through Millénaire's
  relation, both chronicles, cooldowns, truce floor −85, sow-discord limits, pending envoys persist.
* **M5-5 (escort items superseded: escorts were deferred after these runs, §4.3 A):** escort granted from spare units with Favor paid on acceptance; no teleport (max 3.9
  blocks/s), no force-load; dismissal and clean-errand Favor; detachment reaches and holds its
  point; casualty costs Favor; **the approved Reconciler change: an errand unit in an unloaded
  chunk stays DEPLOYED and is found again when the chunk loads**; no duplicate slots.
* **M5-5b:** war only after sustained open conflict; HOSTILE between faction identities only, kept
  by the guard; campaign FRIENDLY/HOSTILE projection, grievance, enemy-combatant status and
  `ENEMY_COMBATANT` threat; persistence; leave and truce restore the previous relations.
* **M5-6:** the armoury pack loads in all 7 cultures (Patron-gated, originals kept); honours.
* **M5-UI (server side):** snapshots from the shared API, server verdicts per action, intents
  re-validated (cooldown, unknown action, range), wire round-trip, no client classes loaded on the
  dedicated server.

### 4.3 Final dispositions of the open items

**A. Escort (resolved by a scope decision).** Escorts are deferred out of M5 (`docs/m5-design.md`, scope-change note).
Lent soldiers never teleport, detachments remain, and the approved Reconciler pause is kept. Escort checks are reported by
the harness as DEFERRED, separately from PASS/FAIL.

**B. G4 at the larger duty sizes (resolved; one accepted validation limitation).**

* *Production defects found and fixed (approved M4 changes, §2.2):*
  * The 40-block recovery ceiling left a trapped sentry stuck for good.
  * Genuinely stuck home-duty units had no recovery at all. The fix is the fallback, with a last-resort move.
  * The re-stuck cycle *post → stuck → fallback → GARRISON → allocator → same post* was confirmed: 10 of 13 reassigned
    units went back to the same post (`g4-diag4*`). The post exclusion fixed it: `g4-excl1*` had 0 of 8 recoveries return
    to the same post, and `g4-final*` had 0 of 21.
* *G4-4b (scouts reach the ring):* it failed once, in `g4-diag4`, when the re-stuck cycle kept a scout restarting the same
  route. It passes with the post exclusion.
* *G4-2 (every sentry within 10 blocks of its own post): accepted validation limitation, not a production defect.* The
  10-block criterion is unchanged. The observation window is a harness setting, raised from 6 to 10 minutes (harness only,
  `db96ed3`; it is not part of production behaviour). The final run still fails it in two ways:
  1. **Serialized recovery on a difficult ravine route.** Pair #5 was reached only by the third unit:
     * two units in turn got stuck on the ravine route (5–8 min each);
     * the exclusion then gave the pair to a unit that reached the post in 12 s, but after the window had closed.

     A fixed window cannot bound a chain of recoveries and replacements.
  2. **M4 hold inside 24 blocks.** A sentry was held 11.2 blocks from its post, inside M4's existing 24-block hold radius
     (frozen M4 behaviour) but beyond G4-2's 10-block post distance.

  Neither case shows units failing to recover or a unit cycling. Both are the difference between bounded test criteria
  and frozen, accepted movement behaviour.
* *Other G4 checks on the release jar:* G4-4b, G4-5a/b/c, G4-P (+14.0 µs) and G4-10b (180 µs mean) pass.
  * G4-P failed once (+56 µs, `g4-stuck1`) and once more (+65 µs, `g4-stuck3`) before the post exclusion, with no single
    identified cause. It passed in every later run.
  * G4-5a failed once, when a recovery fell between its two reads; the harness now excludes such units, as approved.
  * G4-5b failed once (Lappa, `g4-stuck2`). Its before/after plan diagnostics never captured a recurrence, so it remains
    unclassified.

## 5. Known limitations

* The Politics screen is compiled and its server side is tested headless (`/hywmill dev m5 ui`); the
  client rendering itself cannot be exercised on a headless server.
* Opening the screen from the village leader or town hall (sneak + use) needs a spike and was not
  built; the keybind is the entry point.
* Village-war engagement of combatant villagers beyond raid contingents relies on HYW's own
  temporary retaliation; HywMill does not proactively mark combatant villagers outside raids.
* The per-server NoAI budget and dormant units are not implemented (not approved). C3 must be
  measured again on the target server hardware.

## 6. Release candidate (frozen)

| Item | Value |
|---|---|
| Production commit | `fd13410` (no production source change after it; later commits are harness, evidence and docs only) |
| Release jar | `dist/hywmill-m5.jar` (the exact validated bytes; Gradle's output name is `build/libs/hywmill-0.1.0-m1.jar`) |
| SHA-256 | `b08886a8d323528cc11ea94c565d956ee5f48a05693ff485cbd00ada801d8ecd` (`dist/hywmill-m5.jar.sha256`) |
| JUnit | 275/275 |
| G4 (M4 acceptance) on this jar | 30/31; G4-2 is an accepted validation limitation (§4.3 B); `g4-final.txt`, `g4-final-timeline.txt` |

**Rebuild reproducibility.** A clean rebuild of `fd13410` gives a jar with a different hash (`20f677ca…`). Its 337 entries
are byte-identical to the release jar's; only the zip entry timestamps differ, because Gradle does not fix them. The
validated bytes are therefore kept as `dist/hywmill-m5.jar`.

**Coverage of the release jar.** JUnit and the full G4 suite ran on this exact jar. The M5 phase suite (G5_*), G3 and the
M4 → M5 migration were last run on earlier jars (`final-m5-1`, `final-g3-1`, `migrate4-run1`, §4.1). That was before the
escort deferral and the M4 reliability changes, and they have not been re-run on the release jar.


### 6.1 Client UI fix after the freeze: `dist/hywmill-m5-ui1.jar`

The Politics screen, first opened in a real client, showed its centre panel blurred. Vanilla 1.21 `Screen.render` draws
the blurred background itself, and the screen called it *after* drawing its text, so the blur covered the text. The scroll
arrows and chronicle bullets also showed literal `▲`/`▼`/`•`, because of a doubled escape.

* **Fix.** The parchment is drawn in `renderBackground` (after the blur), `super.render` runs first, and the text is drawn
  on top. The three escapes are corrected.
* **Scope.** One file, `client/PoliticsScreen.java`, which is client-only and never loaded on a dedicated server. The jar
  differs from the frozen release only in `PoliticsScreen.class`. All other 336 entries are identical, so server and
  gameplay behaviour are the validated `b08886a8…`.
* **Tests.** JUnit 275/275. G4 was not re-run, because no server code changed.
* **SHA-256.** `0b6b174628bc6262cfb242a0d4fc0238fff7dfa7f3edbfe2b92f3f72bdc293fc`

### 6.2 Fix after the freeze: diplomacy "not discovered" (`dist/hywmill-m5-fix2.jar`)

A player with 337,360 reputation at a village could not send envoys from it: "you have not discovered both villages". The
envoy check used Millénaire's `PlayerCultureReputation.hasDiscoveredVillage` alone. In Millénaire 9.0.2 that flag is set
only by `VillageMapMarkerService`, when a filled map the player is **holding** reveals the village. Visiting, trading or
building reputation never set it.

* **Fix (`MillenaireSettlementSource.discovered`).** A village counts as known if it is map-discovered, **or** the player
  has a reputation record with that village. The record is `Village.getReputation().getAll()`, per village, not per
  culture.
* **Effect.** This is the same "known" test used by envoys, the Relations list and the Wars list. The M5-UI village list
  already included villages the player has a HywMill record with.
* **Scope.** The jar differs from `hywmill-m5-ui1.jar` only in `MillenaireSettlementSource.class`. Includes the §6.1 UI fix.
* **Tests.** JUnit 275/275. Not run on the server: the check needs Millénaire at runtime and the harness marks villages
  discovered directly.
* **SHA-256.** `64bfc8b46f781d333c6a64058e21fea095373959ceee1d0e6c598255bc55712a`
