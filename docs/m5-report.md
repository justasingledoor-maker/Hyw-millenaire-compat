# HywMill M5: Politics, Diplomacy, Rules of Engagement and Garrison Scale (Report)

Pins: Minecraft 1.21.1, NeoForge 21.1.226, Millénaire 9.0.2, HYW 0.7.1r-fix1. One JAR, no mixins, no
changes to Millénaire or HYW, compileOnly against the pinned jars, foreign imports only in
`integration.*`. Branch: `claude/millenaire-hyw-audit-5n4u8s`. Design: `docs/m5-design.md` (§18 is
authoritative). Spikes: `docs/m5-spike.md`.

**Status of this report:** the implementation of every phase is complete. Server evidence is being
collected; see §4. Sections marked *pending* are filled when the runs finish.

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

*Pending final count;* 248/248 at commit `6dc03c4` (was 201 before M5-G).

## 4. Server evidence

*Pending.*

## 5. Known limitations

* The Politics screen is compiled and its server side is tested headless (`/hywmill dev m5 ui`); the
  client rendering itself cannot be exercised on a headless server.
* Opening the screen from the village leader or town hall (sneak + use) needs a spike and was not
  built; the keybind is the entry point.
* Village-war engagement of combatant villagers beyond raid contingents relies on HYW's own
  temporary retaliation; HywMill does not proactively mark combatant villagers outside raids.
* The per-server NoAI budget and dormant units are not implemented (not approved). C3 must be
  measured again on the target server hardware.
