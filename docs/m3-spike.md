# HywMill M3-0 Spike: R1–R8

Dedicated server (NeoForge 21.1.226, Millénaire 9.0.2, HYW 0.7.1r-fix1), harness scenario `S`,
seed 20260925, villages A (norman/agricole, 630 82 612) and B (830 80 612). Automatic garrison
recruitment was **off** (`[garrison] enabled=false`) so that only spike units were involved.

The spike units were created by `/hywmill dev spike-spawn`, which uses the production spawn
sequence (`HywUnitProvider.spawn`):

1. create the entity;
2. `setOwnerUUID(village faction)`;
3. `setRequiresSupply(false)`;
4. `setCanNaturalDespawn(false)`;
5. equipment through `HywEquipmentProvider` (`BaseCombatEntity.setEquipment`);
6. `setHomePosition`;
7. `setAttackStrategy(DEFAULT)`;
8. drop chance 0;
9. garrison tag (NeoForge data attachment);
10. deterministic UUID;
11. `addFreshEntity`.

Each spike unit is also roster-backed: its slot is registered before the entity is added.

Result of run 4: **17/18 checks passed**; the one FAIL is the bystander-health check, which read `None` for the HYW militia because of the float-only regex (the log shows 20.0d before and after). Evidence: `docs/m3-test-evidence/m3-spike-run4.txt`. Runs 1–3 are kept for context. Their failed
checks were harness bugs, not findings: SNBT quoting, spawns inside blocks, spike units without a
roster slot, and a regex for float-only health values.

| # | Question | Result | Evidence |
|---|---|---|---|
| **R1** | Do units owned by a non-player village faction acquire or attack neutral targets? | **No.** Over 60 s: 0 targets and 0 hits against village B's residents (A's units moved into B), a player-owned HYW militia, a cow, a vanilla villager, and village A's own residents. Bystander health was unchanged: cow 10→10, villager 20→20, player-owned militia 20.0→20.0. | S-R1 lines; log health reads. The check line reads FAIL only because HYW stores Health as a double (`20.0d`) and the regex expected `f`. Fixed in `befee09`. |
| R1 (note) | Behaviour towards non-neutral targets | HYW's native AI engages hostile monsters (a zombie died within 15 s) and, per M1, unowned HYW units, whatever HywMill's `proactive` flag says. This is HYW's own behaviour for inherently hostile targets. HywMill's **deployment** still only uses M2's actionable threats (proactive=false respected). | S-R1 note |
| R2 | Home and return with a faction owner | **Works.** A unit teleported 16 blocks from home was back within 5 s (distance 16 → 2) and stayed there. | S-R2 |
| R3 | Temporary hostility for deployment | **Works.** `TemporaryHostileTargetManager.markHostile` plus `setTarget` gave `tempHostile=true`, and the unit attacked the given neutral target (health 20.0 → 11.75). The hostility cleared on its own after about 11 s, and the village↔owner relation stayed NEUTRAL. So deployment must keep refreshing the target while M2 still lists the threat. M3 does this on every M2 scan. | S-R3; log |
| R4 | Duplicate UUIDs | **Vanilla refuses them.** A second `addFreshEntity` with the same deterministic UUID fails while the first is loaded. An entity loaded from a chunk whose UUID is already loaded is not added (`PersistentEntitySectionManager: UUID of added entity already exists`). | S-R4 (both) |
| R5 | Attachment persistence | **Persists.** Tag and owner are unchanged after `execute in the_nether run tp` (same UUID in the Nether). After save and restart every tagged unit reloaded once with its tag, and none multiplied. | S-R5 (both) |
| R6 | Equipment levels | **Exact for levels 0–3** for militia, spear_man, shieldman, warrior, archer and crossbowman; armour visibly progresses from none, to chainmail, to iron, to enchanted diamond. HYW does **not** clamp an out-of-range level: handgonne_man asked for 3 got 0, and matchlock_man asked for 0 got 2. The roster stores the level actually applied. Gunpowder units are disabled by default. | S-R6; log `ArmorItems` |
| R7 | Millénaire coexistence | **No problem seen.** Residents stayed 36 → 36 with six units in the village centre for 60 s, and their goals kept changing (chat, drink, trade, mine, deliver…). Pathing and collision were observed only at this level (no player client). | S-R7 |
| R8 | Millénaire classification | **Correct.** 0 resident hits on the units, and the units were not threats of either village. | S-R8 (both) |

## Conclusion

R1 needs no countermeasure: the stop rule is not triggered. The planned hard filter (never deploy
on a target sharing the village's relation identity) is kept as defence in depth. M3 proceeds.

Findings carried into M3:

1. **Retries reuse the UUID.** A failed spawn must reuse the same deterministic UUID: the
   generation is restored on revert. The JUnit property test found that consuming a generation
   could create a second copy after a stale roster.
2. **Roster first.** Tagged entities without a roster slot are refused at join, so every creation
   path is roster-first, spike tools included.
3. **Refresh hostility.** Temporary hostility is short (about 11 s): the deployment re-engages
   while M2 still lists the threat.
4. **Apply HYW's level.** HYW maps an out-of-range equipment level to 0, not to the maximum: the
   data only requests levels 0–3 for units that have them, and stores what HYW applied.
