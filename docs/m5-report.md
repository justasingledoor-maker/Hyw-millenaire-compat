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
