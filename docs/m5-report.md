# HywMill M5: Politics, Diplomacy, Rules of Engagement and Garrison Scale (Report)

Pins: Minecraft 1.21.1, NeoForge 21.1.226, Millénaire 9.0.2, HYW 0.7.1r-fix1. One JAR, no mixins, no
changes to Millénaire or HYW, compileOnly against the pinned jars, foreign imports only in
`integration.*`. Branch: `claude/millenaire-hyw-audit-5n4u8s`. Design: `docs/m5-design.md` (§18 is
authoritative). Spikes: `docs/m5-spike.md`.

**Status of this report:** every phase is implemented and tested. Two items are open and need a
decision or more work before M5 is signed off; see §4.3.

## 1. What was built, by phase

| Phase | Content | Main classes |
|---|---|---|
| Option 1 (approved) | Per-village resident identity; permanent resident ↔ faction FRIENDLY; resident identities never HOSTILE; frozen again (`docs/m1.1-freeze.md` addendum) | `FactionIds.residentsOf`, `ResidentAlliance`, `FactionRegistry`, `FactionMarker`, `EscalationGuard` |
| M5-G (approved) | C3 target formula, locked caps 24/48/72/128, target-scaled levy, load-state gate, duty/raid data retune | `Recruitment.target(TargetInputs…)`, `ScalingGate`, `hywmill_garrison/defaults.json`, `hywmill_duties/defaults.json` |
| M5-1 | Pure politics core: standing, grievances, favor, tables, records, persistence (ledger format 4 → 5) | `politics.*`, `PoliticsNbt`, `VillageRecord.politics` |
| M5-2 | Status refresh, event-driven grievances, chronicle (persisted, mirrored to Millénaire), word travels, intel, commands | `PoliticsService`, `PoliticsView`, `PoliticsCommands` |
| M5-3 | Outlaw threat (`OUTLAWED_PLAYER`), `PoliticalPolicy` replacing ALWAYS_REVERT, outlaw HYW projection on the faction identity, formal pardon (weregild) | `PoliticalPolicy`, `Pardon`, `ThreatTracker` |
| M5-4 | Envoys (reconcile, truce, encourage, sow discord) with seeded logistic odds, travel, truce floor, raid truce check, offline reports | `DiplomacyOdds`, `EnvoyService`, `DiplomacyCommands` |
| M5-5 | Requests (escort, detachment), Favor from service, casualty cost, approved Reconciler away-clock pause | `Requests`, `ErrandService`, `Reconciler`, `Duty.ESCORT/DETACHED` |
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
| Missing clock paused for ESCORT/DETACHED units in unloaded chunks | `Reconciler` (additive overload) | M3 code |
| Raid contingents engage combatant villagers only | `RaidService.advance` | M4 code |

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

**248/248** (201 before M5-G; 47 new: C3 formula and neutral equivalence, scaling gate, duty data,
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
* **M5-5:** escort granted from spare units with Favor paid on acceptance; no teleport (max 3.9
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

### 4.3 Open items

**A. Escort leaving a village through a trap spot (decision needed).** In test village A the escort
walks into a spot at (652, 79, 613) and cannot get out. The same spot trapped M4 sentries in
`docs/m4-test-evidence/g4-run2.txt`; M4 solved it with its "trapped → unstick" step, which moves the
unit onto nearby ground (a short teleport). Lent soldiers never teleport (approved Q4), so they hold
there. Detours and side-steps do not free them. Options: (1) allow the M4 unstick for errand units
only when trapped (a few blocks, never to catch up); (2) keep "never teleport" and accept that a
trapped lent soldier holds until the errand ends. The detachment case passes.

**B. G4 at the larger duty sizes.** G4-2 (every sentry pair within 10 blocks of its post) failed in
both final runs, each time for one of the 16 stronghold pairs; G4-4b (scouts reach their ring) failed
in one of two runs. Both are M4 acceptance checks run against the approved M5-G duty retune (16
sentry pairs, 8 scouts). Not yet root-caused; to be investigated before sign-off rather than
treated as flakes.

## 5. Known limitations

* The Politics screen is compiled and its server side is tested headless (`/hywmill dev m5 ui`); the
  client rendering itself cannot be exercised on a headless server.
* Opening the screen from the village leader or town hall (sneak + use) needs a spike and was not
  built; the keybind is the entry point.
* Village-war engagement of combatant villagers beyond raid contingents relies on HYW's own
  temporary retaliation; HywMill does not proactively mark combatant villagers outside raids.
* The per-server NoAI budget and dormant units are not implemented (not approved). C3 must be
  measured again on the target server hardware.
