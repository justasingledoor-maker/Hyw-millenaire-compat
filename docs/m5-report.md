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

### 6.3 Fix after the freeze: garrisons ignored Millénaire raids (`dist/hywmill-m5-fix3.jar`)

Bandits from a Japanese hideout raided a village. Its HYW garrison stood by while every Millénaire defender died. The M2
threat scan looked only at HYW combat units. Millénaire raid clones carry no HYW identity (by design, M1), so a raid was
never a threat.

* **Fix.** `ThreatTracker` also takes the settlement mod's raiders sent against the village
  (`SettlementSource.raidersAgainst`; for Millénaire, raid clones registered to the target village) as `SETTLEMENT_RAIDER`
  threats. They are actionable like an attack even with `proactive=false`. The existing alert, shelter and deployment
  then engage them.
* **Tests.**
  * JUnit 276/276, including `DefenseCoordinatorTest.settlementRaiderIsActionableWithoutProactive`.
  * Server harness scenario `RD` (`rd1.txt`), 8/8: a Millénaire raid B → A. Both raiders were listed as
    `SETTLEMENT_RAIDER` threats of A 29 s after the trigger, 4 garrison units deployed at 31 s, and the raid was repulsed.
* **Scope.** Includes the §6.1 and §6.2 fixes.

### 6.4 Equipment: whole armour kits, dyed liveries, painted shields (`dist/hywmill-m5-fix4.jar`)

**Bug report.** Units wore mixed gear, for example a knight's helmet over plain clothes. Each role list could set a single
armour slot, so a unit's slots came from different lists, or kept HYW's own item.

* **Kits.** Armour now comes from one whole kit ("kits": head, chest, legs, feet; "none" means empty) per culture, tier
  and class. The shipped data has kits for all 7 cultures and the defaults, and no per-slot armour lists.
* **Livery (request).**
  * Dyeable Epic Knights pieces are dyed in two colours per unit, from a 12-colour medieval palette
    (`dye.palette`).
  * Shields get random arms: vanilla geometric patterns and Epic Knights emblems, following the rule of tincture.
    Heraldry can be turned off with `heraldry: false`.
  * Both use vanilla item components and are deterministic per unit. Units re-equip once when the profile data changes.
* **Tests.**
  * JUnit 280/280, including `LiveryTest` and new `EquipmentProfilesTest` cases.
  * Server run with Epic Knights (`ek-livery1.txt`): EK1–EK5 and EK7 pass. Whole kits: 248/248 units in Epic Knights
    armour, 0 mixed. Three sampled shields carry three distinct arms.
  * EK6 failed only because the harness pattern did not match the SNBT field order (`show_in_tooltip` before `rgb`).
    Checked against the same logged data, 195/195 dyeable pieces were dyed, in 12 distinct colours. The pattern is fixed;
    the scenario has not been re-run.

### 6.5 Muster Roll and apologies (`dist/hywmill-m5-fix5.jar`)

**Muster Roll (request).** A block, the Muster Roll, hires soldiers of the village it stands in, paid in Millénaire money.

* **How it works.**
  * The block binds to the village it is placed in; only the player who placed it can set the spawn radius (2–36).
  * Any player can use it, within their own standing with that village.
  * Money is taken from the inventory; reputation is untouched.
  * Recruits appear around the block, belong to the player (not to the garrison), and never change the garrison.
* **Offers.**
  * Unwelcome and outlaws: nothing.
  * Anyone else: mercenaries (militia 3, archer 4, crossbowman 5 argent) in light gear.
  * The village's own soldiers from Trusted (Guard post gear), Patron (Garrison) and Sworn (Stronghold), capped by the
    village's tier and priced in or.
  * Patron gets 10% off and Sworn 20%.
  * At most 32 units per purchase.
  * All of this is data (`hywmill_recruitment`).
* **Recipe.** Iron sword, any banner and shield over a book and quill, over a lectern.

**Apology (request).** A player who is not an outlaw can pay to settle a grievance at once. This is the fix for Unwelcome
after striking the garrison.

* **Price and effect.** 1 argent per grievance point (`apologyPerGrievance`; 0 disables it). The grievance goes to zero
  and the standing is re-evaluated; reputation is untouched. Outlaws still need the pardon.
* **Where.** The Politics screen (home village) and `/hywmill politics apologize [pay]`.

**Tests.**
* JUnit 291/291, including `RecruitOffersTest` and `ApologyTest`.
* Server harness, driven through a dev stand-in player:
  * `MR` (`muster-roll1.txt`), 16/16: the offers per standing, hire, not enough money, the Unwelcome refusal, cultural
    soldiers, the 32 cap, recruits owned and placed near the block, and the garrison unchanged (48/47 before and after).
  * `AP` (`apology1.txt`), 6/6: three garrison assaults make the stand-in Unwelcome (grievance 27). The apology costs
    1728 deniers; it is refused with too little money, and after paying the stand-in is back to Stranger.
* SHA-256 of the jar: `666b182b8e6ceb8e16dc8cb664f96f2765fae26cb83f9ba9c6a8e55ff355dc7f`.

### 6.6 Crash fix: unarmed HYW archers (`dist/hywmill-m5-fix6.jar`)

**Bug report.** A game crashed with "Ticking entity" and `IllegalArgumentException: Invalid weapon firing an arrow` in
`ArcherEntity.canLobAttackTarget`.

* **Cause (HYW).** Every tick an archer has a target, HYW builds a test arrow from the main-hand stack. Vanilla rejects an
  empty weapon stack, so an HYW archer with an empty main hand crashes the server. HYW leaves the hand empty in several
  cases:
  * an item named in its equipment data is missing;
  * a unit is summoned with NBT (no spawn equipment);
  * another mod clears the hand.

  HywMill's equipment overlay only ever puts registered items in the main hand.
* **Fix.** `HywRangedWeaponGuard` is a server-side event listener: no mixin and no change to HYW. When any HYW archer or
  crossbowman joins the level with an empty main hand, or its main hand becomes empty, it gets HYW's own default weapon
  back (a bow or a crossbow). A throttled log line records each re-arm.
* **Tests.**
  * The crash was reproduced with fix5: an archer whose main hand was emptied next to a zombie crashed the server with
    the same stack (`weapon-guard-repro.txt`, `weapon-guard-repro-crash.txt`).
  * With the fix, harness scenario `WG` passes 4/4 (`weapon-guard1.txt`): both units are re-armed and the server keeps
    ticking.
  * The guarded server also re-armed, on load, an unarmed unit left in the save of the crashed run.
  * JUnit 291/291.
* SHA-256 of the jar: `ea769c20ba150f3e11d9702b933885af53d27b7b18fd3c07858008f04ec12461`.

### 6.7 Crash fix, second round: the guard ran too late (`dist/hywmill-m5-fix7.jar`)

**Bug report.** fix6 still crashed three more times, with the same stack, around the same archers.

* **Cause.**
  * HYW empties archers' hands itself in ordinary combat. Seen in the harness with Epic Knights (`WX`,
    `weapon-guard-combat-fix6.txt`): 4 archers had their main hand emptied in 5 minutes of fighting around a village.
  * The crashing check (`updateCombatMovementPauseByTargetTransition` → `canLobAttackTarget`) runs at the start of
    `BaseCombatEntity.tick`, before `super.tick()`. That is where vanilla detects equipment changes and fires
    `LivingEquipmentChangeEvent`, so fix6 re-armed one tick too late.
  * It only crashes when the archer would lob, i.e. when its target is behind cover.
* **Fix.** `HywRangedWeaponGuard` also listens to `EntityTickEvent.Pre` and `EntityTickEvent.Post`. Right before and
  right after every tick of an HYW archer or crossbowman, an empty main hand gets the default bow or crossbow. This is an
  instanceof check per entity tick, and it acts only when the hand is empty. Still no mixin.
* **Tests.**
  * Harness scenario `WL`: an archer with a zombie penned behind a stone wall has its hand emptied 40 times.
    * With fix6 it crashes with the reported stack (`weapon-guard-lob-fix6.txt`, `weapon-guard-lob-fix6-crash.txt`).
    * With fix7 it passes 2/2 runs together with `WG` (`weapon-guard-lob-fix7-*.txt`); the log shows the re-arms
      "before tick".
  * The 5-minute Epic Knights combat run (`weapon-guard-combat-fix7.txt`) ends with no crash and 4 "before tick" re-arms.
    Its "empty main hand" flags are a harness artefact (the query returned no output in its 0.3 s wait).
  * JUnit 291/291.
* SHA-256 of the jar: `a13f2137ec3b294907385a8ee0fe9089dd1ead2fd5e40226f2b10ec5c2a2a85c`.

### 6.8 Raid counsel: suggest a raid to your campaign ally (`dist/hywmill-m5-fix8.jar`)

**Request.** From the Politics screen, a player can suggest that a village raid its enemy. There is only a chance the
village agrees, and only while the two are at war and the player is on campaign with that village.

* **Where.** "Suggest a raid on <enemy>" appears in the Politics screen with the enemy selected, showing its cost and
  odds (likely, uncertain or unlikely). There is also `/hywmill war raid`, which uses the player's current campaign.
* **Rules** (`RaidCounsel`, pure; `hywmill_politics` `raidCounsel`, per culture). A suggestion is refused unless:
  * the villages are at war;
  * the player's active campaign is with this village against this target;
  * the village is not already planning or conducting a raid;
  * the target is not under attack;
  * the village has raiders.
* **Cost.** 1 Millénaire diplomacy point, spent whatever the answer, then a one-day cooldown per player.
* **Chance.**
  * By standing: Trusted 35%, Patron 55%, Sworn 75%.
  * ×0.3 when the target's defending strength is at least twice the village's raiding strength (Millénaire's own limit
    for choosing a raid target).
  * Kept between 5% and 90%.
* **Agreement.** Millénaire plans the raid its usual way (`RaidManager.planRaid`): its announcement, then the raid
  about a day later. The garrison's M4 raid share goes with it. The chronicle records who counselled it.
* **Also.** The Politics screen tooltips no longer show a literal `\n` before "Outcome:".
* **Tests.**
  * JUnit 300/300 (`RaidCounselTest`, `PoliticsDataTest`).
  * Harness `RC` on a fresh world after `G4_0` (`raid-counsel2.txt`), 14/14 in all. It checks:
    * the refusal without a campaign (the screen shows the option disabled with the reason);
    * the war and the campaign joins;
    * the screen offering the option with its cost and odds;
    * a refused roll spending a point, then the cooldown;
    * an agreed roll planning Millénaire's raid;
    * the refusal while raiding, through the command and through the screen's submit handler;
    * a day later, the raid setting out with 2 Millénaire raiders and 8 garrison soldiers on RAID duty.
  * An earlier run on the reused world passed 7/7 (`raid-counsel1.txt`).
* SHA-256 of the jar: `bc9db4d256e74012afe23f8f32baf25e3cd4babddf7cfb76603dd5ef1c4397ec`.

### 6.9 Sieges; the garrison leaves Millénaire raids (`dist/hywmill-m5-fix9.jar`)

**Bug report and request.** A player waited at the target of an allied raid with HYW troops, and no garrison soldiers
came. Millénaire raiders are records that materialize at the target, while garrison soldiers are entities that exist only
while their (unloaded) home is loaded. On top of that, a raid ended as soon as Millénaire's few raiders died. The player
asked to separate small raids from larger sieges and made four decisions:
* raids stay Millénaire-only;
* tribute and standing for victory;
* unwatched sieges are resolved off-screen;
* both players and villages start sieges.

The full design is in `docs/siege-design.md`.

* **Raids.** `hywmill_duties` `raid.enabled` is now false: the garrison no longer joins Millénaire raids (the code is
  kept; one flag restores it). Raid counsel (§6.8) therefore now sends Millénaire's raiders only.
* **Sieges** (`SiegeService`; `Siege` records in the ledger):
  * **Muster.** Half the garrison musters, keeping 40% at home.
  * **March.** The host marches stowed: slots without entities, ignored by the Reconciler, with any old entity refused
    as a duplicate by `JoinAdjudicator`. It materializes before the target (Millénaire's landing point) when that is
    loaded.
  * **Watched battle.** Won when the defenders fall to 20%, lost when the host falls to 30%, with a 5-minute deadline.
  * **Off-screen.** After a 1-minute wait, the siege is decided by strength: P = H^1.5/(H^1.5+D^1.5); Millénaire
    defenders count 0.3×, fortification up to +50%; HYW losses on both sides.
  * **Outcome.** The loser pays tribute by its tier. Levy points move to the winner, and 40% of the tribute is paid to
    the winner's helpers (campaigners near the battle; offline helpers at login). Helpers also gain 512 reputation and
    5 SIEGE_VICTORY Favor.
  * **Return.** Survivors march home and take the normal return path.
* **Triggers.**
  * "Suggest a siege of <enemy>" in the Politics screen, or `/hywmill war siege`: Patron or Sworn on campaign, 2
    diplomacy points, a 50%/75% chance cut to ×0.4 against a much stronger target, 2-day cooldown.
  * Villages at war: 25% a day when host/defense ≥ 0.9, then a 3-day cooldown.
  * `/hywmill war admin siege <attacker> <target>`. `/hywmill war sieges` lists the sieges under way.
  * All numbers are `hywmill_politics` `siege` data, patchable per culture.
* **Tests.**
  * JUnit 311/311, including `SiegeMathTest` (11); `DutyTest` is updated for raids off.
  * Harness on a fresh world after `G4_0` (`sieges1.txt`), 17/17 overall:
    * **SG, watched siege:**
      * a Trusted campaigner is refused; a Patron sees the option with cost and odds;
      * the counsel is heeded, and A musters 24 of 48 on SIEGE duty;
      * marching, the host's 24 entities leave the world while the roster stays at 48 live, none missing;
      * all 24 stand before B;
      * **WON**, with 11 of 32 defenders left, and B pays tribute; the helper gained Favor (27);
      * the survivors are home, with 1 killed and 0 duplicates.
    * **OS, unwatched siege** (dev switch): 23 muster against Z, and the siege survives a restart mid-march. It was
      decided off-screen, host strength 65 against defense 260, P = 0.11 → **LOST**. The host lost 12 and the
      defenders 4, exactly the garrisons' killed totals. The survivors came home with 0 duplicates.
* SHA-256 of the jar: `66129aa8b645922b224e2f387a2d57ce213e3314c311cd310988438e683f91e7`.

### 6.10 War arsenals and siege engines; engines at the Muster Roll (`dist/hywmill-m5-fix10.jar`)

**Request**, after the exploration (`docs/siege-engines-exploration.md`):
* only catapults and trebuchets (strongholds also a nest of bees);
* 1–4 engines per village whenever it goes to war;
* lost engines are not replaced during the war, and survivors despawn at peace;
* a fresh arsenal for the next war;
* bystanders can be hit ("cost of war");
* no gunpowder;
* the Muster Roll sells catapults, trebuchets and battering rams.

* **War arsenal** (`ArsenalService`, `ArsenalPlan`; the roster's separate arsenal list).
  * A village at war, declared or declared on, raises engines by tier: watch 1, guard post 2, garrison 3, stronghold 4
    (one of them a nest of bees). Each engine has a siege engineer, who mounts it by himself (HYW's own goal).
  * They stand at the defending position and fire at enemies in range.
  * The arsenal is not part of the garrison: never counted, recruited or given duties. Joins, deaths and duplicate
    refusal work as for garrison units, and a destroyed engine does not affect recruiting.
  * Lost engines are not replaced. At peace the survivors stand down; those away on a siege stand down when they come
    home. The next war brings a fresh arsenal.
* **Sieges.**
  * The village's engines and crews march with the host and set up 24 blocks behind the landing point, where they hold.
  * Rout and victory shares count soldiers only.
  * Off-screen, each engine weighs 8 and cancels 25% of the target's fortification bonus; a defender's engines at home
    add to its defense.
* **Muster Roll** (`hywmill_recruitment` `engines`):
  * catapult 2 or (Trusted, with an engineer);
  * trebuchet 4 or (Patron, with an engineer);
  * battering ram 1.5 or (Trusted, player-driven).

  Standing discounts apply, at most 4 per purchase. An HYW ram belongs to whoever rides it: bought, it stands
  ownerless by the block until the player boards it.
* **Fix.** `GarrisonService.stow` no longer discards a tracked vehicle together with its rider.
* **Tests.**
  * JUnit 318/318, including `ArsenalPlanTest` (6) and a new engine-offer test.
  * Harness `ARS` on a fresh world after `G4_0` (`war-arsenal1.txt`), 12/13, then 13/13 with ARS-7 re-checked
    (`war-arsenal-ars7.txt`). The first ARS-7 expected the ram to carry the buyer as its owner; as above, that is not
    HYW's rule.
    * **At war:** A (guard post) raised a catapult and a trebuchet, and B (watch) a trebuchet, both crews mounted.
    * **Losses:** a destroyed engine was not replaced (2 → 1).
    * **Siege:** it took the surviving engine along, which set up behind the host before B.
    * **Truce:** B's engines stood down, and A's stood down when its host came home.
    * **Next war:** A raised 2 again.
    * **Muster Roll:** it sold the catapult (with an engineer) and the ram, and refused the trebuchet to a Trusted
      player.
    * No duplicate units at any point.
* SHA-256 of the jar: `fe4645ba4647a8de25f39944fa09bcfa6e1e7d3800e34369934d320f822a36e4`.

### 6.11 Siege fixes from play (`dist/hywmill-m5-fix11.jar`)

**Report.** A stronghold host of about 50 attacked a village with 16 defenders. The attackers landed in water. Villagers
killed the player while the HYW attackers ignored them. When the player respawned far away, the battle was decided
off-screen, and the attackers lost 22 soldiers to the defenders' 1.

* **Civilians.** Attackers now engage every villager of the target, civilians included (Millénaire villagers respawn).
  Victory still counts the defenders' fighters.
* **Water.** No unit is placed in water any more. The old fallback used the raw landing point when no safe spot was
  found; now the search walks back towards home every 8 blocks, then tries the target's centre. A unit that cannot be
  placed yet waits and retries; it is never resolved off-screen for that.
* **Unwatched battles.** A battle whose target is no longer loaded (the player left or died) now pauses where it stands:
  its clock stops, and it resumes when the area is loaded again. After 10 minutes unwatched it ends on the shares it
  stood at, with no new losses. The off-screen strength roll is only for sieges nobody ever watched.
* **Odds.** The off-screen exponent is 2 (was 1.5): a clearly stronger host wins more reliably.
* **Tests.**
  * JUnit 318/318.
  * Harness `SGF` (`siege-fixes1.txt`), 4/4:
    * attackers targeted `millenaire:villager`s as well as soldiers;
    * no attacker stood in water;
    * made unwatched mid-battle, the battle paused with no off-screen decision, and it resumed when watched again.
* SHA-256 of the jar: `8180a58c6435baed7286d7de5f04d4198c31a53532de20c5f1c955af2ad305fd`.

### 6.12 Civilians are fair game in war (`dist/hywmill-m5-fix12.jar`)

**Report.** On campaign against a town, its civilians attacked the player and the player's troops, and the troops did
not fight back. The user decided civilians should be killable: they respawn.

* **Cause.** Option 1 kept every village's resident identity (its civilians) out of all HYW relations, and the escalation
  guard reset any hostility involving one. So HYW never treated enemy civilians as enemies.
* **Change** (`RelationPlan`, `PoliticalPolicy`, `EscalationGuard`):
  * a war now also makes each village's soldiers HOSTILE with the enemy's residents;
  * a campaign makes the player, and so the player's own troops, HOSTILE with the enemy's residents;
  * the guard permits exactly these planned edges. Outlawry stays soldiers-only, and villagers are never set against
    villagers.
* **Tests.**
  * JUnit 319/319 (`WarTest.warAndCampaignMakeTheEnemysCiviliansFairGame`).
  * Harness `CIV` (`civilians-in-war1.txt`), 2/2:
    * player ↔ enemy residents and ally soldiers ↔ enemy residents are HOSTILE both ways, and still so 25 s later;
    * 4 archers owned by the campaigning player, placed in the enemy village, targeted its `millenaire:villager`s.
* SHA-256 of the jar: `fb0089a7dc7ad51c5deee3c56391b7758e4ea0bb0f10e6f213c7ac39564e40b6`.

### 6.13 Longer sieges, fought to the last soldier (`dist/hywmill-m5-fix13.jar`)

**Request.** Sieges were too short. A battle should run until one side has no soldiers left, or until a time limit
somewhere between 10 and 20 minutes.

* **Change.** `battleTicks` is 18000 (15 minutes) and `breakFraction`/`routFraction` are 0: a battle ends when the
  attackers or the defenders' fighters (garrison and Millénaire soldiers; civilians do not count) reach zero.
  * At the deadline the side that kept the larger share of its starting number wins.
  * Time while nobody is near does not count.
  * A server can still set break/rout lines in `hywmill_politics` `siege`.
* **Tests.** JUnit 319/319 (`SiegeMathTest.watchedBattleIsFoughtToTheLastSoldierOrTheDeadline`). This is a data and
  threshold change; no new harness run.
* SHA-256 of the jar: `3bfd2d9479540ef61195a578a2d8f6b13cedf52ffefdf41f18566f090bc1ba46`.

### 6.14 Lone buildings leave the Politics screen (`dist/hywmill-m5-fix14.jar`)

**Request.** Small non-village raider sites crowded the diplomacy screen, and diplomacy with bandits makes no sense.

* **Change.** The Politics screen no longer lists Millénaire lone buildings (`VillageRecord.loneBuilding`: bandit
  camps, inns, lone farms), and one is never chosen as the "home" village. Envoys to or from one are refused on the
  server: "bandits and lone buildings take no part in diplomacy".
* **Unchanged.** Their garrisons, raids and threat handling.
* **Tests.** JUnit 319/319; no harness run (the harness world has no lone building).
* SHA-256 of the jar: `b219eb5d417a2cfc983deef03c5a5a350701ea608b5f53cb93b20a6bcd62ff9b`.

### 6.15 Siege counsel without cooldown or points (`dist/hywmill-m5-fix15.jar`)

**Request.** A command to suggest a siege without the counsel cooldown or the diplomacy point cost.

* **Change.** `/hywmill war siege force` (operators, permission level 2; `/hywmill war for <player> siege force` for
  another player) makes the Politics screen's siege counsel. It skips the cooldown and costs no diplomacy points, and it
  does not start a new cooldown.
* **Unchanged.** The other checks still apply: at war, on campaign against the target, Patron or better, no siege
  under way, a large enough host. The chance roll by standing still applies too.
* **Tests.** JUnit 319/319; no harness run.
* SHA-256 of the jar: `c21f01649ad9ca8f0d6a68dbd869bfd47b779b882c4df12a8951c846cba5a29e`.

### 6.16 Attackers spread round the village (`dist/hywmill-m5-fix16.jar`)

**Request.** Siege attackers should not all head for the centre. The main force takes the centre while other units mop
up around the village.

* **Change.** In a watched battle, a soldier with no one to fight heads for a goal set by `SiegeMath.sweepOffset`.
  * **Main force.** About 60% of the host, a stable choice per roster slot, heads for the target's centre as before.
  * **Squads.** The rest form four squads, one per quarter of the village. Each works a point on a ring round the
    centre: 0.6 × the village radius, clamped to 16–48 blocks.
  * **Sweep.** Every minute of battle the squads move on 45°, so over the battle they sweep the whole outskirts.
  * Engaging is unchanged: any soldier attacks the nearest foe within 32 blocks, civilians included.
* **Tests.** JUnit 320/320 (new `SiegeMathTest` case: roles, ring, four squads, rotation); no harness run.
* SHA-256 of the jar: `464d35cf9236000780a3000bc7bfa1120997c8433c219db1ca445143929e8978`.

### 6.17 Spaced ranks on arrival (`dist/hywmill-m5-fix17.jar`)

**Request.** Space the siege host out when it materializes, as the arsenal engines are.

* **Change.** `SiegeService.formation` places each unit's spawn point before the spot search (dry, sturdy ground)
  runs from it.
  * **Soldiers.** Ranks of 8, 3 blocks apart, across the line from the landing point to home. Each further rank is 3
    blocks nearer home.
  * **Engines and crews.** A line of 6 positions, 5 blocks apart, 24 blocks behind the landing point. Each engine
    shares its position with its crew.
* **Tests.** JUnit 320/320; no harness run.
* SHA-256 of the jar: `0e245351a75a4a640071ad6d20559657c6100923a7ebe2b196f38c09653be0c6`.

### 6.18 A siege is lost at 20% of the starting force (`dist/hywmill-m5-fix18.jar`)

**Request.** A side loses the siege when it is down to 20% of its force at the start.

* **Change.** The default `breakFraction` and `routFraction` are now 0.2 (`hywmill_politics` `siege`; the
  `PoliticsTables` default too).
  * **Attackers.** They lose when the host falls to 20% or less of the soldiers who arrived.
  * **Defenders.** The attackers win when the defenders fall to 20% or less of their number when the battle began.
  * **Both at once.** If both sides reach the line in the same check, the attackers lose.
* **Unchanged.** The 15-minute deadline; the side that kept the larger share wins at the deadline.
* **Tests.** JUnit 320/320 (`SiegeMathTest` battle case updated to the new lines); no harness run.
* SHA-256 of the jar: `3eaf10cf61eb1bc2d76ddaa3463b4db9bb13ce576a303ceed7bcb350d4d1e20c`.

### 6.19 Tripled siege tribute (`dist/hywmill-m5-fix19.jar`)

**Request.** The loser of a siege should pay a larger tribute.

* **Change.** The tribute by the loser's tier is tripled (`hywmill_politics` `siege.tribute`; the `PoliticsTables`
  default too):

  | Tier | Before (deniers) | Now (deniers) | Now (money) |
  | --- | --- | --- | --- |
  | Watch | 2048 | 6144 | 1½ or |
  | Guard post | 4096 | 12288 | 3 or |
  | Garrison | 12288 | 36864 | 9 or |
  | Stronghold | 32768 | 98304 | 24 or |

* **Effects.** Everything derived from the tribute triples with it:
  * the levy points moved from the loser to the winner (a Garrison loser now gives 18);
  * the helpers' 40% share.
* **Tests.** JUnit 320/320 (`SiegeMathTest` tribute case updated); no harness run.
* SHA-256 of the jar: `df6f180544e2a12e136771d7260159d79fa435262400e65e720e00147afe0034`.

### 6.20 Siege tribute sized to soldier prices (`dist/hywmill-m5-fix20.jar`)

**Request.** fix19's tribute was still small. One regular soldier costs about an or and some argent on the Muster
Roll: for a Garrison, (0.5 + 0.25 × 3) × 1.25 ≈ 1.56 or.

* **Change.** The tribute is now worth about 25 of the loser's soldiers for a Garrison.

  | Tier | Tribute |
  | --- | --- |
  | Watch | 8 or |
  | Guard post | 16 or |
  | Garrison | 40 or |
  | Stronghold | 96 or |

* **Levy.** `levyShare` is cut from 2.0 to 0.5 levy points per 4096 deniers, so the recruiting points that move from
  the loser to the winner stay moderate:

  | Tier | Levy points |
  | --- | --- |
  | Watch | 4 |
  | Guard post | 8 |
  | Garrison | 20 |
  | Stronghold | 48 |

* **Helper pay.** The helpers still split 40% of the tribute: 16 or for a Garrison loser.
* **Tests.** JUnit 320/320 (`SiegeMathTest` tribute case updated); no harness run.
* SHA-256 of the jar: `7b360ab1a4d5edb92909daa97cb73abee4e5ec2648104fff2fd5ddd7bab9e085`.

### 6.21 Returning siege hosts no longer die of entity cramming (`dist/hywmill-m5-fix21.jar`)

**Report.** 24 of 30 attackers survived a siege. After they returned home, the player found a pile of their gear
where they had mustered.

* **Cause.** The home is the defending position, which is also the muster point. When a host returns,
  `bringHome` materializes every survivor at once through the spawn search, and that search tries the anchor block
  itself first. So all the survivors appeared on the same block. With 24 or more mobs on one block, vanilla entity
  cramming (`maxEntityCramming` 24) kills them, and they drop their gear. Each death is a real garrison death: the
  slot becomes DEAD (KILLED).
* **Fix.**
  * `bringHome` places the survivors in spaced ranks round the anchor (`formation`: rows of 8, 3 blocks apart).
  * The spawn search (`GarrisonService.findSpot`) skips any block a living entity already stands on. This applies to
    every spawn: recruits, the arsenal and sieges.
* **Tests.** JUnit 320/320. No harness run: the diagnosis is from the code (the spawn order and the vanilla cramming
  rule).
* SHA-256 of the jar: `ba2631f2ca3f7f6694a5f09833bc4cb530a4d4e28419f6b4b51a95d755f4edf6`.

### 6.22 No trebuchets in village war arsenals (`dist/hywmill-m5-fix22.jar`)

**Request.** Trebuchets are far too big for village defense. The war arsenal should field catapults and other engines
that make sense.

* **Change.** The arsenal's `types` are now `mangonels` (catapult) and `springald` (HYW's light bolt-thrower,
  which is also crewed by a siege engineer). Before, they were `mangonels` and `trebuchets`.
* **Unchanged.**
  * A stronghold still fields one nest of bees (`strongholdTypes`).
  * The counts per tier are the same.
  * The Muster Roll still sells trebuchets to players.
* **Current wars.**
  * A trebuchet already in a village's arsenal is removed on the next arsenal pass (`dropRetiredTypes`), unless it
    is away on a siege.
  * Its engineer stays with the village.
* **Tests.** JUnit 320/320 (`ArsenalPlanTest` updated); no harness run (the springald is not yet seen in play).
* SHA-256 of the jar: `b9f0cd42e9f39be4a4010dddceb66eb6e5afcdb1e65c60b4e4c92e4d7d41b685`.

### 6.23 The siege host lands outside the village (`dist/hywmill-m5-fix23.jar`)

**Report.** Siege soldiers appeared in the middle of the target village, not outside its radius.

* **Cause.** The host could be placed inside the village in two ways:
  * it landed at Millénaire's raid landing point, which can lie inside the village;
  * when no dry ground was found, the spot search fell back to the target's centre.
* **Fix.**
  * `SiegeService.landing` now uses its own staging point: on the side facing the attacker's home,
    `villageRadius + 16` blocks from the target's centre (48 is assumed when no radius is known). The point is taken
    on the surface when its chunk is loaded.
  * The search for dry ground only steps further away from the target, never towards its centre. If nothing is
    found, the host waits and tries again.
  * The soldiers' ranks and the engine line form round the staging point. The host then marches in.
* **Tests.** JUnit 320/320; no harness run.
* SHA-256 of the jar: `b726a3b452fa3924c2ac38bbf27a69ea3a44438fe6131b57acb3d5793dc54c18`.

### 6.24 Siege helpers: those who fight count (`dist/hywmill-m5-fix24.jar`)

**Report.** A player fought in a siege and got no money for helping.

* **Cause.** A helper had to be on an active campaign with a side against the other, and within 96 blocks of the
  target's centre. A player who fought without a campaign never counted. Since fix23 the host also lands
  `villageRadius + 16` blocks out, so near a large village a player fighting beside it could be out of range.
* **Fix.**
  * A player also counts as a helper after striking a combatant or resident of the target, which counts for the
    attackers. Striking a soldier of the host counts for the defenders. The strike must fall within the last 2 s at
    each battle step.
  * A player counts for one side only: the first one recorded.
  * The helper range is now max(96, village radius + 16 + 32) round the target's centre.
  * The log records each helper ("Siege …: <player> fights with <village>").
* **Unchanged.** Only the winning side's helpers are paid: 40% of the tribute, shared.
* **Tests.** JUnit 320/320; no harness run.
* SHA-256 of the jar: `74660d1d0fae567b46b7cb8da3ae96d98c6813aa63c0329f1b2b6005f587043b`.

### 6.25 War and peace counsel, wartime mobilization, peace after a siege (`dist/hywmill-m5-fix25.jar`)

**Request.** The user asked for three things:

* A mobilization boost at war for villages that are not strongholds. It fills the current cap, not the tier cap. The
  troops are decently equipped but below regulars, draw on no population, behave as ordinary units and are sent home
  after the war.
* Peace after a lost siege, with the relation set to −75 instead of −100.
* "Suggest war" and "Suggest peace" options. The council decides by chance, and the enemy weighs the armies. Both must
  also be available as commands that an operator can force.

**Changes.** docs/siege-design.md, "War and peace".

* **Peace after a siege.**
  * Every finished siege ends the war (`warCounsel.peaceAfterSiege`). `WarCounselService.makePeace` sets both
    relations to `peaceRelation` (−75, above open conflict at −90) and ends the `WarRecord` at once.
  * Any other host between the two villages is recalled, with no outcome (`SiegeService.recall`).
  * The war restarts only if Millénaire's relation drifts back to −90.
* **War and peace counsel** (`WarCounsel`, `WarCounselService`). Both need a Patron or Sworn player, and each has
  a per-player cooldown of one day.
  * **War** is offered while the villages are at peace. It costs 2 diplomacy points. The council's chance is
    `warChance[standing]` (Patron 0.4, Sworn 0.65) × (1 − relation/200) × 2 × our strength share, where the last
    factor is capped at ×1.25. If the council agrees, war is declared at once (relations −100).
  * **Peace** is offered while they are at war. It costs 1 point. The council's chance is `peaceChance[standing]`
    (0.5 / 0.75) × (1.5 − our share). If the council agrees, the enemy accepts with our share, clamped to 0.1–0.9.
  * The share is ours²/(ours² + theirs²). Strength is the siege defense value: the garrison at home, weighted
    Millénaire defenders, engines and fortification.
  * **Politics screen.** It offers "Suggest war on X" or "Suggest peace with X" with the verdict and odds band.
  * **Commands.** `/hywmill war [for <player>] declare <village> on <other>` and `... peace <village> with
    <other>`.
  * **Forcing.** Operators add `force`, which skips the standing, the cost and both rolls. `roll <draw>` is for
    dev use.
* **Mobilization** (`Mobilization`, `MobilizationService`).
  * Once per war, a WATCH, GUARD_POST or GARRISON village raises min(target, tier cap) − live free recruits,
    marked `mobilized`.
  * Their equipment level is max(1, regular − 1), so there are no Watch clubs.
  * They are ordinary garrison units. At peace they are discharged (LOST, DISCHARGED); soldiers away on a siege are
    discharged when the host comes home.
  * Strongholds and lone buildings do not mobilize. Losses during the war are replaced only by normal recruitment.
* **Data.** The new blocks are `warCounsel` and `mobilization` in `hywmill_politics`. Cultures can patch them.

**Tests.**

* JUnit 326/326: `WarCounselTest` and `MobilizationTest` are new.
* Harness scenario WP passed 8/8 (`docs/m5-test-evidence/war-peace-mobilization1.txt`):
  * a stranger is refused;
  * a forced war is declared at once at −100;
  * A, a Guard post at 22/47, mobilized 25 to 47/47 at equipment level 1 without spending levy;
  * a forced peace set −75 and ended the war;
  * the 25 levies went home (47 → 22);
  * no restart after 30 s;
  * an unwatched siege ended with "sued for peace" at −75;
  * no duplicates.
* SHA-256 of the jar: `3912a6a240f5b791d8a6aeafcb62c7fe86e171a77c6ef7570b3307eae055ded7`.

### 6.26 Relief forces (`dist/hywmill-m5-fix26.jar`)

**Request.** An allied village on great terms with a besieged village sometimes sends a relief force.

* **Size.** 5–20% of its garrison, deployed temporarily until the siege is over.
* **Timing.** It sets out when the attackers finish mustering and march, and arrives by forced march in 1–2 minutes,
  usually before the attackers.
* **Outcomes on the way:**
  * the force arrives cleanly, spread round the village;
  * it is ambushed and loses soldiers, then arrives smaller or routs and never arrives;
  * more rarely, it loses its way: part or all of it never shows, with no loss to the home garrison.
* **Who.** AI-launched sieges trigger it as well.

**Changes.** docs/siege-design.md, "Relief forces"; `Relief`, `ReliefService`, and the `relief` block in
`hywmill_politics`.

* **Planning.** At every siege launch, each village meeting all of these conditions promises relief with chance 0.2, at
  most 2 per siege:
  * relation with the besieged village of at least 80;
  * at peace with it;
  * not a party to the siege and not a lone building;
  * at least 4 soldiers at home.

  This covers counsel, a village's own decision and admin launches.
* **Dispatch and arrival.** When the attackers march, the helper sends `Relief.size` (5–20% of its garrison at home)
  stowed. They arrive after 1200–2400 ticks and appear on a ring round the village (`Relief.post`). While the force is
  out, the `RelationPlan` relief pairs make the helper's faction and the attacker's HOSTILE both ways.
* **In battle.** The relief counts among the defenders:
  * watched: foes, defenders and the 20% line, with `defendersStart` raised if it joins mid-battle;
  * off-screen: its strength is added to the defense, and it takes the defenders' loss share.
* **Journey** (`Relief.journey`, seeded with `SplittableRandom`):
  * **Ambush** (12%): 20–60% of the force killed (DEAD, a real loss). With 40% the rest rout home and none arrive.
  * **Lost** (6%): 30% to all of the force never arrive, and they go home unharmed.
  * Otherwise the force arrives cleanly.
* **Return.** When the siege ends or peace recalls it, the force goes home, in spaced ranks at the helper's anchor. The
  siege record stays until the relief is home (`Siege.reliefsDone`).
* **Admin.** `/hywmill war admin relief <helper> <target> [clean|ambushed|routed|straggled|lost]`.
* **Status.** `/hywmill war sieges` shows each relief (phase, fate, soldiers).

**Found by the tests.** `java.util.Random`'s first draw hardly varies across nearby seeds, so every journey came out
clean in the JUnit check. The journey and dispatch draws now use `SplittableRandom`.

**Tests.**

* JUnit 330/330, with the new `ReliefTest`: eligibility, size, travel, fate distribution, posts.
* Harness scenario RL passed 9/9 (`docs/m5-test-evidence/relief-forces1.txt`). Villages: C = Saint-Pierre le-fort,
  relation 90 with B; A besieges B.
  * The relief set out 1 s after A's host marched. It arrived about 30 s before the attackers, and C↔A was
    HOSTILE/HOSTILE.
  * At the peace it went home, and the siege record cleared after it.
  * A forced ROUTED relief lost 2 soldiers (C killed 3 → 5), and none arrived.
  * A forced LOST relief never arrived, with no deaths.
  * No duplicates.
  * Two earlier runs failed only on harness mistakes, now fixed: a march check that read a player message, and a
    relation lookup passed a string instead of coordinates.
* SHA-256 of the jar: `b5c27626f15a3d72d91078604fcdaebaf1435a33116c8e9a08f8caa7a2096195`.

### 6.27 Up to 6 relief forces per siege (`dist/hywmill-m5-fix27.jar`)

**Request.** At most 2 helpers per siege is too low.

* **Change.** `relief.maxHelpers` default 2 → 6. This is in the code default and in `hywmill_politics`.
* **Unchanged.** A village helps only with a relation of 80 or more to the besieged village, and each one decides with
  a 20% chance. A village with many close friends can now receive several relief forces at once.
* **Tests.** JUnit 330/330; no harness run (a data value; the relief path is covered by RL in 6.26).
* SHA-256 of the jar: `4654b47a43fc16b0a82191cd3a03ef73a06b71ab9ca0178e6e48c79735e5fc5e`.

### 6.28 Wartime levies (`dist/hywmill-m5-fix28.jar`)

**Report.** In several wars at once a village loses its whole garrison to one siege, is besieged again and is "just
punched around".

**Request.** Accelerated recruitment for villages that are not strongholds, of mobilized quality, with low-quality
shieldmen and poorly armoured spearmen in the mix.

* **Top-up** (`MobilizationService.reinforce`, `Mobilization.topUp`).
  * While at war, after the war-start fill, a village that is not a stronghold and is below its target raises
    `reinforceBatch` (2) more free levies every `reinforceInterval` (600 ticks = 30 s).
  * A wiped-out garrison of 40 is back in about 10 minutes.
  * Levies are marked mobilized and go home at peace, as in 6.25.
  * Normal paid recruitment still runs alongside.
* **Mix** (`Mobilization.weights`).
  * Levies are drawn from the village's composition plus `levyUnits` extra weights: spearmen +3 and shieldmen +3.
  * Shieldmen are allowed even below their usual tier (GARRISON).
* **Gear.**
  * Mobilized soldiers use the new `levy` equipment role. It gives light kits (gambeson with kettle hat, coif or bare
    head; cheap wooden or iron shields) at the tier of their own equipment level, via `EquipmentProfiles.gearTier`
    (level 1 = GUARD_POST: iron weapons).
* **Fix.** Before this change, with Epic Knights installed, mobilized troops in a Watch village drew the Watch's
  clubs and wooden swords, because profiles were keyed by the village tier.
* **Data.** `mobilization.reinforceInterval`, `reinforceBatch` and `levyUnits` in `hywmill_politics`; `levy`
  role kits in `hywmill_equipment`.
* **Tests.**
  * JUnit 333/333; new `MobilizationTest` cases for the mix, the top-up batches and the levy gear tier/role.
  * Harness scenario LV 4/4 (`docs/m5-test-evidence/wartime-levies1.txt`):
    * A (GUARD_POST) at war lost 8 of 47, and was topped back to 47 in 4 batches 30 s apart.
    * The batches were [spear_man, crossbowman], [shieldman, spear_man] and [spear_man, shieldman]: shieldmen at a
      guard post.
    * At peace, A's live garrison went from 47 to 21 as the levies went home.
  * The harness has no Epic Knights, so the `levy` kits themselves were not seen in play.
* SHA-256 of the jar: `886c88610a52b1deb20c51a860001c92b207e2ca7aab879f63028a7c57b05b5c`.

### 6.29 Sieges across water (`dist/hywmill-m5-fix29.jar`)

**Report.** A siege host sometimes landed across a body of water from the target and never advanced.

* **Causes.**
  * The landing point was always on the side of the target facing the attacker's home. Water between that point and
    the village was not checked.
  * When a soldier's next hop fell on water (`DutyService.standable` refuses fluid), `hopTarget` returned
    nothing and the soldier was given no goal: it stood still.
* **Fix.**
  * `SiegeService.landing` tries 16 bearings round the village, starting with the side facing home and widening
    alternately. It takes the first staging point with a dry way in, checked by `dryApproach`: loaded ground every
    4 blocks, with no water or lava on top, up to half the village radius. If none qualifies, it keeps the old point.
  * In battle, a soldier whose hop is blocked tries up to 6 detours to either side (`hopTarget` with turns). Failing
    those, it is sent to its goal directly, so the unit's own pathfinding finds a way round.
  * The soldiers' ranks, the engine line and the dry-ground search now extend away from the target rather than
    towards home, since the landing may be on any side.
* **Tests.**
  * JUnit 333/333.
  * Harness scenario WT 3/3 (`docs/m5-test-evidence/siege-water1.txt`). The side of B facing A was flooded (x
    737..827). The host landed on the dry north side at (847, 70, 518) and advanced: 3 of A's soldiers were inside 40
    blocks of B's centre.
* SHA-256 of the jar: `82a244317aa718201c9a34aa3f60954111dcbfff018b64b6440d2ba7c34e27c4`.

### 6.30 Culture squads; militia made rare (`dist/hywmill-m5-fix30.jar`)

**Request.**

* Shields and spears draw battles out longer than single-weapon militia. Militia should be few and far between unless
  a culture calls for them.
* Design 16 culturally aligned squads per culture, hired permanently from the Muster Roll on a separate tab:
  * 6 infantry: 2 low, 2 medium and 2 high quality;
  * 4 ranged: bows, crossbows, and low-quality bows and crossbows;
  * 4 horse, for example Seljuk cataphracts and horse archers or Norman heavy knights;
  * 2 unique.
* Each squad has 8–12 soldiers, no siege weapons, and costs a little less than its soldiers bought singly.

**Militia.**

* Militia weight is 0 in the default, Norman, Byzantine, Seljuk, Japanese and Indian compositions; Indian militia go
  to spearmen.
* The Maya and the Inuit keep 1, since every farmer or hunter fights there. Inuit weights are now spearmen 3,
  archers 3.
* WATCH villages may field spearmen: LINE is allowed at WATCH and `spear_man` has minTier WATCH. This stops a small
  village from being all militia and archers.
* Existing militia stay until they die.

**Squads** (`hywmill_squads/defaults.json`, `Squads`, `SquadTableLoader`; the full list is in
`docs/squads.md`).

* There are 7 cultures × 16 = 112 squads, each with the agreed split and 8–12 soldiers.
* A member is a unit, a count, a gear tier and an optional `levy` kit.
* Requirements by quality:

  | Quality | Standing | Village | Gear |
  | --- | --- | --- | --- |
  | LOW | Trusted | any | levy kit, guard-post weapons |
  | MEDIUM | Patron | Guard Post+ | garrison gear |
  | HIGH | Sworn | Garrison+ | stronghold gear |

* **Price:** the members' single prices, less `squadDiscount` (10%), then the standing discount.
* **Heavy cavalry:** HYW's `mounted_lancer_rider` (heavy lancer) is added as unit `lancer_rider` (CAVALRY, cost 6)
  and allow-listed. It is used only by squads: knights, kataphraktoi, cataphracts and mounted samurai.
* **Substitutions.** The Maya and the Inuit had no horses, so their 4 "cavalry" slots are fast foot bands. Japan,
  India, the Maya and the Inuit rarely fielded crossbows, so their crossbow slots are bow squads.
* **Muster Roll screen.** New tabs: Soldiers and Squads. Each squad lists its category, name and price. A squad that
  cannot be hired is marked x and says why.
* **Details panel:** quality, size, roster, best gear, description and price, with a "Hire this squad" button.
* **Protocol:** a new client-bound `muster_squads` payload; version 2. A hire is `Hire(pos, "squad:<id>", 1)`.
* **Refunds:** members that cannot be placed are refunded pro rata.

**Tests.**

* JUnit 338/338:
  * the new `SquadsTest` covers the catalogue shape, horses by culture, no engines, militia, pricing, requirements
    and bad data;
  * garrison tests were updated to the new compositions and the Watch LINE rule;
  * the wire version test is now 2.
* Harness scenario SQ passed 7/7 (`docs/m5-test-evidence/squads1.txt`):
  * the catalogue loaded 7 cultures and 112 squads;
  * Fyrd Spearmen hired whole (10) for 9 or;
  * the Serjeants were refused (needs Patron), and knights were refused at a Guard Post;
  * at a Garrison-sized Norman village, the Knights of the Household came to 8 heavy lancers, all 8 mounted;
  * the Conroi came to 4 knights and 6 light riders;
  * the Byzantine Hippotoxotai came to 10 horse archers.
* The squads tab itself was not clicked through in a client.
* SHA-256 of the jar: `3997f92ab1f7ac7646007998e2147b0e163c8baea7053b8620331e306c80271c`.

### 6.31 Squad looks (`dist/hywmill-m5-fix31.jar`)

**Request.** Squads' armour should be unique or relevant, for example crusaders in assorted crusader attire, in
crusader colours, with crusade-like banner patterns on their shields. This applies to every new squad.

* **Looks** (`hywmill_equipment/squad_looks.json`, `EquipmentProfiles.Look`).
  * There are 112 looks, one per squad; a squad names its look in `hywmill_squads` (`"look"`).
  * A look has its own armour kits, shield list, dye palette (a repeated colour is weighted) and fixed arms (base
    colour and banner layers).
  * Squad members are equipped with the role `look:<id>`. Where the look names nothing for a slot, or Epic Knights
    or HYW cannot use an item, the culture's gear is used.
  * All item and pattern ids were checked against the Epic Knights 10.15 jar.
* **Provider** (`HywProfileEquipmentProvider`).
  * The look's kits and items come first.
  * The look's palette dyes the dyeable pieces: surcoat, gambeson, coif, great helm and others.
  * Shields are painted with one of the look's arms, chosen by the soldier; there is no random livery.
* **Examples** (every squad is listed in `squad_looks.json`):

  | Squads | Armour | Colours | Shields |
  | --- | --- | --- | --- |
  | Norman Crusader Band, Dismounted Knights, Knights | great helm / Norman helm, crusader surcoat | mostly white, some red and black | white field with a red crusader or apostolic cross; red field with a white cross; white field with a black cross |
  | Byzantine Excubitors, Kataphraktoi | lamellar (and face helms) | purple and gold | two-headed eagle |
  | Skoutatoi | lamellar | red or blue | Orthodox cross on oval shields |
  | Varangians | Norman helms, mail | — | dragon on round shields |
  | Seljuk Hassa, Cataphracts | shishak, lamellar | red and gold | two-headed eagle |
  | Daylamites | — | — | sun on pavises |
  | Japanese | lamellar, sallets and face helms | red and black, or blue and white | — |
  | Ashigaru | kettle-hat "jingasa" | — | wooden standing shields |
  | Indian | shishak and mail | saffron | sun and lion on round dhal shields |
  | Maya | quilted cotton | Jaguar yellow and black, Eagle white and brown | serpent and sun shields |
  | Inuit | bone slat armour (lamellar) | hide and bone | — |

* **Tests.**
  * JUnit 339/339. The new `SquadsTest` case checks that every squad has its own look (112), with kits and a
    palette, and that crusader surcoats and red-cross arms and the two-headed eagle are present.
  * Harness scenario LK 5/5 with Epic Knights 10.15 (`docs/m5-test-evidence/squad-looks1.txt`):
    * **Crusader Band:** 4 of 4 shieldmen in crusader surcoats, dyed; steel kite shields with a red crusader cross,
      a red apostolic cross and a black cross, on white.
    * **Excubitors:** 3 of 3 in lamellar with the two-headed eagle.
    * **Knights of the Household:** 4 of 4 riders in crusader surcoats, dyed white and red.
  * The first run's LK-0 and LK-3 failures were harness mistakes: a wrong command name, and knights from an earlier
    run sampled.
* SHA-256 of the jar: `73ae7011fe6cf4b661533561b5409be7c7b9ecc7c95787588cbf8874e5d0e990`.

### 6.32 Village liveries, Politics colours and distances, siege field (`dist/hywmill-m5-fix32.jar`)

* **Village livery.**
  * Each village takes two dye colours once, and they are persisted (`VillageLivery`, `LiveryService`).
  * The first colour is the one least used by the liveries within 3000 blocks. After that the culture's taste decides,
    and the village id breaks ties.
  * The second colour contrasts with the first by the rule of tincture, and the pair avoids the pairs already used
    nearby.
  * So neighbours, the villages that fight each other, rarely share a first colour, and never share a full pair while
    one is free.
  * The garrison wears the livery on Epic Knights dyeable pieces: chest, head and feet in the first colour, legs in the
    second. Shields bear assorted arms drawn from the culture's ordinaries and charges (`profiles.json` `heraldry`), in
    the village's two colours.
  * Existing soldiers re-dress once, through the equip stamp.
* **Politics screen** (protocol version 3). Each village row shows its two colours and its distance from the home
  village, in m or km. The home header shows its own colours.
* **Squads.**
  * The 68 generic squads (levies and regulars of the culture) keep their look's armour but wear the hiring village's
    colours and arms.
  * Unique and foreign squads keep their own look: crusaders, Varangians, Genoese, Welsh, Mamluks, Jaguar and Eagle
    warriors, monks and ronin.
* **Sieges** (see `docs/siege-design.md`, "The field of battle").
  * The host lands in groups of 4–8 round the near half of the village.
  * Attackers and defenders fight in HYW autonomous combat (`FREE_FIGHT`).
  * A boss bar per siege is shown near the target.
  * There is a 25% chance of a mercenary company of 10–20 a minute before the assault. It is announced, equipped like
    levies, and paid off at the end.
* **Garrison rotation.** In a long war, a calm village at strength swaps its longest-serving levy for a paid regular
  every `rotateInterval` ticks (2400), if its levy points allow.
* **Tests.**
  * JUnit 346/346. New: `VillageLiveryTest`, which covers neighbour avoidance, unique pairs, contrast, and arms in the two
    colours only; and `SiegeExtrasTest`, which covers the mercenary odds and sizes, group sizes and bearings.
  * Harness scenario VL with Epic Knights 10.15 (`docs/m5-test-evidence/siege-field1.txt`):
    * **VL-1 liveries:** 4 neighbouring villages took purple/yellow, red/white, blue/yellow and green/white.
    * **VL-2 garrison colours:** 5 of 6 soldiers dyed; the sixth wore undyeable mail.
    * **VL-3 mercenaries:** 12 Genoese crossbowmen hired, taking the host from 6 to 18.
    * **VL-4 groups:** 18 soldiers landed in 3 groups, against 24 defenders.
    * **VL-5 boss bar:** raised, green, in the attacker's colour.
    * **VL-6 autonomous combat:** 5 of 5 sampled attackers in `FREE_FIGHT`.
    * **VL-7 pay-off:** the 12 mercenaries paid off at peace.
    * **VL-8 rotation:** a levy shieldman went home and a regular crossbowman took his place.
  * VL-3 failed in the recorded run, but the hire itself worked: the command returned "HIRED 12" and the host grew to 18.
    The check was waiting for the chat announcement in the server log, but that text goes to players, not the log. The
    server log has the hire line, and the check now waits for it. The scenario has not been re-run since that change.
* SHA-256 of the jar: `195af9f32802433968065c7e8a78ebef3b260c9dc3fd678e07f00dfbd7404e7a`.

### 6.33 Player colours on the Muster Roll (`dist/hywmill-m5-fix33.jar`)

* **The pickers.**
  * The Muster Roll has two colour pickers, "Your colours", at the bottom left: left-click steps forward through the 16
    dyes, right-click steps back, and "x" clears the choice.
  * The choice is per player and kept in the garrison ledger (`playerColours`), so it survives death and restarts.
  * The server re-validates it: dye ids 0-15, or cleared.
* **Who wears them.**
  * Single hires and generic squads wear the player's colours on dyeable Epic Knights pieces, with shields bearing
    culture arms in them.
  * With no choice made, single hires keep the culture palette and generic squads the village's livery.
  * Unique and foreign squads always keep their own look.
  * Soldiers already hired keep what they wear.
* **Protocol:** version 4. The `View` carries the colours, and a new `SetColours` intent sets them.
* **Tests.**
  * JUnit 347/347, including a new wire round trip for the View and SetColours.
  * Harness scenario PC with Epic Knights (`docs/m5-test-evidence/player-colours1.txt`): with blue and yellow chosen,
    a Village Shield-Wall came out as 4 of 4 shieldmen in blue gambesons with blue-and-yellow shields.
* SHA-256 of the jar: `99a21b29d8d8f7875c25f142f7964548af15a75ffc3e3e62321659a7f0b23b0b`.

### 6.34 No recruiting during a siege battle; levies muster faster (`dist/hywmill-m5-fix34.jar`)

* **The user's report.** A village about to be besieged showed 48/48 but "alive 11, recruited 37".
* **The cause.** This was not a loss:
  * At war the village raised its whole levy at once.
  * Those slots count towards the target, but spawn only 2 per garrison slot, so the numbers crept up.
* **The rule now.**
  * While a siege is being fought at a village (its BATTLE phase), the village recruits nothing:
    * no war reinforcement levies;
    * no paid recruits (blocker NOT_CALM);
    * no garrison rotation;
    * no spawning of slots still waiting to muster.
  * The muster, march and wait before the battle are unaffected.
* **Faster mustering.** While mobilized levies wait to muster, a village spawns up to 8 a slot
  (`GarrisonService.LEVY_BURST`), so a fresh levy fills the ranks in a few slots.
* **Tests.**
  * JUnit 347/347.
  * Harness LV 3/3 (`docs/m5-test-evidence/levies-fix34.txt`).
  * Harness VL 8/8 with Epic Knights (`docs/m5-test-evidence/siege-field2.txt`). The target recruited nothing during
    the battle, and VL-3 now passes with its corrected check (the Turki horse archers, 19 hired).
* SHA-256 of the jar: `fcfbcc399a2d237b9acc1ebf0e2911eccebc72b606d00551bc6664a2269bf236`.

### 6.35 Epic Knights addons: Epic Knights: Addon and Slavic Armory (`dist/hywmill-m5-fix35.jar`)

* **Supported** (both optional):
  * Epic Knights: Addon 2.5 (`magistuarmoryaddon`, 421 items).
  * Epic Knights: Slavic Armory 2.3 (`slavicarmory`, 61 items).
* **How it works.**
  * Kits may use addon pieces. A kit with a piece from an addon that is not installed is skipped, so without the addons
    every unit and squad wears exactly what it did before.
  * `/hywmill admin equipcheck` says whether each addon is loaded. It lists missing addon pieces as optional, not
    invalid, and now also checks every squad look's kits.
* **Garrisons** (`profiles.json`, +108 kits; regenerated with `add_addon_kits.py`, additive):
  * **Byzantines** (Slavic Armory):
    * Byzantine lamellar and scale, Rus heavy lamellar.
    * Phrygian, Nikolskoe and Varangian Guard helmets; gilded sets at Stronghold.
    * Levies in kaftans or Rus gambesons with eastern kettle hats or shapkas.
  * **Seljuks:**
    * Saracen sets.
    * Cuman, Bulgar and mamluk helmets, and the kulah khud.
    * Kuyak, bahteretz and yushman armour; mirror armour at Stronghold.
  * **Indians:** tunics, saracen pieces, kulah khud and mirror armour.
  * **Japanese:**
    * Splint armour, straw hats and eastern kettle hats for ashigaru.
    * Scale and lobster-tail helmets for samurai.
    * Grotesque and devilish face helmets at Stronghold.
    * Neither addon has real Japanese armour; these are the nearest pieces.
  * **Normans:**
    * Chapel hats, linen coifs, chained gambesons and tunics.
    * Coats of plates; early and late great helms with XIII-century knight harness.
    * Dark crusader sets, klappvisor bascinets, and heavy brigandine with visored kettle hats.
  * **Maya:** tunics.
  * **Inuit:** unchanged; nothing in either addon fits.
* **Squads** (`squad_looks.json`): 96 of the 112 looks get addon kits matched to their culture and armour.
  * Varangians get Varangian Guard helmets and Rus mail.
  * Excubitors and the Tagma get gilded Byzantine lamellar.
  * Samurai get splint armour; the daimyo's guard gets face helmets.
  * Mamluks get mamluk helmets.
  * Every look keeps a plain Epic Knights kit.
* **Bug fixed.** `magistuarmory:brigandine_boots` does not exist in Epic Knights: there is no such item. The four
  brigandine looks (Brabançons, Genoese, Dhal, imperial archers) now wear chainmail boots. Before, those kits were
  silently skipped.
* **Tests.**
  * JUnit 347/347. The profile and look tests allow the addon namespaces and require a plain Epic Knights fallback in
    every list.
  * Harness AD with Epic Knights 10.15 + Addon 2.5 + Slavic Armory 2.3 (`docs/m5-test-evidence/ek-addons1.txt`):
    * **AD-0:** both addons loaded, 0 invalid kits.
    * **AD-1:** Excubitors 5 of 10 in Slavic Armory (gilded Rus heavy lamellar with Andreevski helmets, Byzantine scale
      with Phrygian helmets).
    * **AD-2:** the Byzantine garrison re-dressed in part, 2 of 12 sampled.
    * **AD-4:** a Norman shield-wall 6 of 10 in Addon tunics, chapel hats and chained gambesons.
    * **AD-3:** the harness world has no Japanese village, so it was not seen on soldiers. Its kits are covered by AD-0
      (every piece registered and in the right slot).
  * Without the addons, LK 4/4: squads unchanged, and equipcheck reports 85 optional addon pieces and 0 invalid.
* SHA-256 of the jar: `95b9f1635ff3165bb76ea7f1fc36fff01f3e8722fd54bc3bb258bf130d4a1389`.

### 6.36 Tribute paid every day for 3–5 days (`dist/hywmill-m5-fix36.jar`)

* **The change.** A siege's loser now pays the full tribute every Minecraft day for 3–5 days, drawn from the siege's seed,
  instead of once. The amount is unchanged: it is still set by the loser's tier.
  * Each day the winner gains the tribute's levy points, taken from the loser.
  * Each day every helper of the winning side gets their full share in money. The first day is paid at once.
  * Offline helpers are paid at login, as before.
  * The chronicle says "pays X a day in tribute to Y for N days", and notes when the tribute is paid in full.
* **Storage.** `Tribute` (pure) is kept in the garrison ledger under `tributes`. Payments stop if either village is gone.
* **Commands.** `/hywmill war tributes` lists the tributes being paid. The dev command `/hywmill war admin tribute-due`
  makes the next day fall due now.
* **Tests.**
  * JUnit 349/349. New: `TributeTest`, which checks 3–5 days and the full amount every day.
  * Harness TR 4/4 (`docs/m5-test-evidence/tribute1.txt`):
    * an unwatched siege ended with "Barneville pays 8 or a day in tribute to Campigny for 5 days";
    * day 1 was paid at once, and `war tributes` listed it as 1/5;
    * day 2 moved the same 4.00 levy points;
    * after day 5 the tribute was gone.
  * The run had no helpers, so helper money was not exercised. It goes through the same pay-or-pend path as before,
    once a day.
* SHA-256 of the jar: `14be676ec74aa62a46c9af80e4d59c23a479205597ad2e53d2bedbbe3abcdc3d`.

### 6.37 Recall every host; soldiers stranded by a lost siege record come home (`dist/hywmill-m5-fix37.jar`)

* **The user's report.** After the mod jar was replaced while three hosts were away, they stayed away with no time shown.
* **The likely cause.** A siege record that did not survive the update. Its soldiers stay on duty SIEGE, stowed, and
  nothing brings them back. The "orphan sweep" named in a code comment had never been written.
* **`/hywmill war admin recall-all`** (permission 3). Every host away comes home now, and nothing is decided:
  * Sieges not yet decided are called off: no outcome, no tribute, no losses.
  * The march home is skipped. A host arrives at once if its village is loaded, else as soon as it is.
  * Relief forces turn back and skip their march too.
  * Soldiers with no siege record come home.
  * It prints one line per host.
* **Automatic repair.** Every minute, slots on duty SIEGE that no siege or relief force holds come home when their village
  is loaded, taking the normal return path: `returnOrphans`, sharing `homecoming` with the siege's own return.
  Mercenaries in such a group are paid off.
* **Dev command.** `/hywmill war admin siege-forget` drops siege records without bringing anyone home, to reproduce the
  case.
* **Tests.**
  * JUnit 349/349.
  * Harness RC 2/2 (`docs/m5-test-evidence/recall1.txt`):
    * a host marching on Barneville, with a relief force out, was home at once (10 away to 0) and no siege was decided;
    * after its record was dropped, a marching host's 23 soldiers came home through the repair (23 away to 0).
* SHA-256 of the jar: `092267e37119136f74b95ae60e302653f73b12d4b226813d47170e35aaacdc72`.

### 6.38 Help for the besieged; bigger relief forces; a host marching home blocks nothing (`dist/hywmill-m5-fix38.jar`)

* **Help for the besieged.** Rolled a minute before the assault, each kind by its own chance:
  * **Militia (50%):** 5–15 HYW soldiers in levy kit, by population.
  * **Mercenaries (20%):** a free company of 10–20.
  * **The lord's household (30%, Garrison and Stronghold only):** 6–8 soldiers of the culture's best squad, in its look
    and the village's colours.
  * They appear at home, count among the defenders, take no duty, are not counted in the garrison, and go home when the
    siege ends.
  * Each kind is announced and chronicled.
  * `/hywmill war admin aid <attacker>` forces all of them. See `docs/siege-design.md`.
* **Relief forces.**
  * Friends at relation 70+ send help, not only 80+.
  * The chance is 35% per friend, up from 20%.
  * The force is 8–25% of the friend's garrison, and at least 5 soldiers while that leaves the friend half its garrison.
    Before, a small friend sent 1.
* **The user's report:** after recall-all, five hosts on their way home to unloaded villages kept their villages from
  launching new sieges.
  * A host in RETURN no longer counts as "already besieging" for its village, and does not shield its target.
  * Such a host still arrives when its village is loaded.
* **Tests.**
  * JUnit 352/352. New: `DefenderAidTest`. `ReliefTest` updated for the new sizes and relation.
  * Harness DA 5/5 with Epic Knights + addons (`docs/m5-test-evidence/defender-aid1.txt`):
    * Barneville raised 9 militia and 11 Bhil hillmen;
    * its soldiers near home went from 20 to 41;
    * the battle started with 48 defenders;
    * all 20 went home after the recall;
    * Saint-Pierre le-fort (a Garrison village) called its lord's household: 8 of the Conroi.
  * Harness RC: RC-1 and RC-2 pass. RC-3 shows that, with a host of the same village marching home, a new siege is no
    longer refused as "already besieging". It was refused HOST_TOO_SMALL instead, because the whole garrison was away. The
    check was then corrected to accept that, and the scenario has not been re-run.
* SHA-256 of the jar: `5b79ae1374f75333c87b4b7e976d9591e18e0f8467f40c5526b3330ac79b4c1d`.

### 6.39 recall-all wipes every siege (`dist/hywmill-m5-fix39.jar`)

* **The user's report.** After fix37, five hosts marching home to villages that were not loaded kept their records, and
  those records blocked new sieges.
* **The fix.** `/hywmill war admin recall-all` now removes every siege record at once, and decides nothing.
  * Each host and relief force appears at its village if that village is loaded.
  * Otherwise its soldiers are put back on the village's roster at home, stowed (GARRISONED, no entity).
    `GarrisonService.respawnStowed` places them round the anchor when the village is next loaded.
  * Temporary defenders go home, and mercenaries are paid off.
* **Tests.** Harness RS 1/1 (`docs/m5-test-evidence/recall-all-wipe1.txt`), using the dev variant `recall-all stowed`,
  which treats every village as unloaded:
  * a marching host of 26 and a relief force of 10 were wiped at once, and `war sieges` showed 0;
  * the relief soldiers rejoined their garrison as their village was processed;
  * a new siege from the same village was launched at once.

### 6.40 Battle reports (History tab), vassalage, war horns, period horse armour (`dist/hywmill-m5-fix40.jar`)

* **Battle reports.**
  * Every decided siege writes a report, `BattleReport` (kept in the ledger, the latest 60). It records:
    * the day, both sides and the outcome;
    * attackers and defenders, with how many fell;
    * whether it was fought in sight or off-screen;
    * who helped: mercenaries, militia, the lord's household, relief forces and vassals;
    * the tribute.
  * The Politics screen has Overview and History tabs. History lists the home village's vassal ties and its battles, newest
    first; it scrolls with the arrows or the mouse wheel. Protocol 5.
  * `/hywmill war history <village>` prints the same.
* **Vassalage.**
  * The loser of a siege becomes the winner's vassal for 21 Minecraft days. The two are allied (relation 90) for that
    time and neutral (0) after.
  * When its overlord besieges or is besieged, a vassal sends, three times in four, 10–15 soldiers drawn from its own
    units. About half are levies (levy kit and level) and half regulars (its own level).
  * They arrive with the mercenaries, a minute before the assault: into the host, or to the defence. They are temporary
    and go home when the siege ends. When a vassal sends no one, that is announced too.
  * `/hywmill war vassals` lists vassalages. `/hywmill war admin vassal <vassal> <overlord>` creates one.
* **War horns.** The raid horn sounds, from the right direction, for players within 256 blocks when:
  * a host marches;
  * the battle starts;
  * help (militia, mercenaries, household, vassals) arrives;
  * the siege ends.
* **Period horse armour.**
  * When HYW equips a rider's horse it uses vanilla leather, iron and diamond. Now, when an HYW horse joins the level, and
    about once a second after (HYW re-applies its own armour), it gets:
    * dyed leather in the rider's colours at low levels, and for light horse below the top;
    * Epic Knights chainmail horse armour for heavy horse at the middle level and light horse at the top;
    * Epic Knights plate barding for heavy horse at the top, about one in three in the Addon's dark barding.
  * Never gold or diamond. Without Epic Knights, iron stands in.
  * The armour is set through HYW's own horse armour (`setOwnedHorseArmor` and `syncOwnedHorseArmorVisibility`, by
    reflection, in `integration.hyw`).
* **Tests.**
  * JUnit 356/356. New: `VassalageTest` and `HorseArmourTest`.
  * Harness VS 4/4 (`docs/m5-test-evidence/vassals-history-horses1.txt`):
    * the History tab showed "Day 11: Campigny besieged Barneville - Barneville held", with 25 attackers (12 fell), 32
      defenders (4 fell), the ronin band, the relief from Saint-Pierre (10 sent, 1 fell), and "16 or a day for 4 days";
    * Campigny swore fealty for 21 days;
    * as Barneville's vassal it sent 13 men to Barneville's next siege, and they went home after.
  * Harness HA, with HYW's Epic Knights mode off (it was on in the harness before, which hid the problem):
    * the first runs reproduced diamond and iron horse armour, and showed that setting HYW's horse armour without its
      visibility sync did not stick;
    * the final run had 20 horses: 12 in dyed leather, 6 in barding, 2 in dark barding, and none in diamond or iron.
* SHA-256 of the jar: `49a22c964ce37e092f3d42a8c633762d7d0432e4cb918d39db8e6373980d8d14`.

### 6.41 Crossbowmen carry pavises (`dist/hywmill-m5-fix41.jar`)

* **The change.**
  * HYW gives its crossbowmen nothing in the off hand. Now about 60% of them, chosen per soldier (`Pavise`), carry an
    Epic Knights pavise of their gear tier: wood (Watch), wood or iron (Guard Post), iron (Garrison) or steel
    (Stronghold).
  * Squad looks that list pavises (the Genoese, for one) now get them too: the off-hand family check allows the pavise
    for crossbowmen.
  * The pavise is painted like any shield: village colours, squad arms or culture arms.
* **Protection.** A mob holding a crossbow never raises a shield to block, so a carried pavise gives armour in the off
  hand: +2 for wood, +3 for iron, +4 for steel (an attribute modifier on the item, `hywmill:pavise_armour`).
* **Tests.**
  * JUnit 357/357. New: `PaviseTest`.
  * Harness CB 2/2 with Epic Knights (`docs/m5-test-evidence/pavises1.txt`):
    * Town Crossbowmen and Genoese, 18 crossbowmen: 13 carried pavises, all painted and armoured;
    * a 200-health zombie placed in front of them lost 13 health to their bolts, so the pavise does not stop them
      shooting.
* SHA-256 of the jar: `21d9fd087427666f439ea8fb73b523872066c23d2361ec4949b2dbf4d489852a`.

### 6.42 Spearmen carry shields (`dist/hywmill-m5-fix42.jar`)

* **The change.** HYW gives its spearmen nothing in the off hand. Now about 75% of them carry a shield (`SpearShield`) of
  their culture's shape and their tier's material:
  * **Normans:** kite shields, and heater shields in Garrison and Stronghold villages.
  * **Byzantines:** the oval skoutarion (elliptical), sometimes round.
  * **Seljuks, Indians and others:** round shields.
  * **Maya:** wooden round shields.
  * **Japanese** (yari, both hands) and **Inuit:** none.
* **Material** by tier: wood, wood or iron, iron, steel.
* **Squad looks and levy lists** that give spearmen off-hand shields now take effect: the off-hand family check allows
  shield families for spearmen.
* **Painting and armour.** Shields are painted like any other. Never raised to block, a carried shield gives +1 armour
  (wood), +2 (iron) or +3 (steel).
* **Tests.**
  * JUnit 358/358. New: `SpearShieldTest`.
  * Harness SS 2/2 with Epic Knights (`docs/m5-test-evidence/spear-shields1.txt`):
    * Fyrd Spearmen: 10 of 10 with painted, armoured shields, given by their look's levy list;
    * a 200-health zombie in front of them lost 30 health. The village garrison stood nearby, so this shows they fight,
      not how much each did.
  * The first run failed on a wrong hire key in the harness (nothing was hired), which made its SS-2 vacuous.
* SHA-256 of the jar: `55548ee0b682f68cb75ae8d0e71632d4d84c9f672e52a6ad70bb920cfc41968d`.
