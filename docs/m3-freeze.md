# HywMill M3: Freeze

M3 (village-owned HYW garrisons) is **complete and frozen** as of commit `1336ee3` on
`claude/millenaire-hyw-audit-5n4u8s`. The full account is in `docs/m3-report.md`, the spike in
`docs/m3-spike.md`, and the evidence in `docs/m3-test-evidence/`.

After the freeze, further work is limited to documentation and cleanup, unless a new reproducible
correctness defect is found.

## Accepted evidence

| Suite | Result |
|---|---|
| JUnit (clean build of the final commit) | 133/133 (77 new, incl. 10,000 seeded duplication runs) |
| G3 suite and scale, final jar | 47/47 |
| Scale | 112 village-owned HYW units at their tier caps, twice |
| Migration, ledger format 3 → 4 (G3-16) | 7/7 |
| Epic Knights optional check | 7/7 |
| **M2 baseline**: M2 regression with M3 loaded, garrison disabled | 70/72; only the already-accepted performance-tail items P3a/P3b |
| M2 regression, garrison enabled | 67/72; P3a/P3b, the A4 harness string (since fixed), and C2/C3 as below |

## Frozen decisions

* **C2/C3 (garrison enabled) are intentional M3 behaviour.** The garrison kills scenario C's bandit
  before it reaches a villager. Scenario C is not altered, and the garrison is not weakened or
  bypassed. The garrison-disabled run is the M2 regression baseline.
* **Caps.** Per-village tier caps of 0/8/16/32/64, taken from data. There is **no** global HYW-unit
  cap and no hidden equivalent.
* **Spawn throttling is a throughput safeguard, not a population cap.** It keeps
  `spawnsPerSlot = 2` and `spawnsPerTick = 2`. The measured worst warm case is about 12 ms of spawn
  work in one tick. `spawnsPerTick = 1` stays available as a server-side setting but is not the
  default.
* **Native HYW targeting.** Garrison units engaging monsters and unowned HYW units on sight,
  regardless of `proactive`, is documented as a known limitation. It is not redesigned in M3.
  HywMill's own deployment follows M2 (proactive=false).
* **Epic Knights.** HywMill has no Epic Knights code or dependency; HYW's own compatibility is
  sufficient.
* **M2 doctrine.** It is not reopened, and its schema is unchanged.
* **Unchanged semantics:**
  * orphan policy KEEP;
  * death → no respawn, replacement is a new paid recruit after the cooldowns;
  * migration from format 3 to 4 with a one-time 50% starting grant;
  * ownership: HYW OwnerUUID = village faction, never the controller;
  * controller = permissions only;
  * duplication prevention: roster-first, deterministic UUIDs, join adjudication, adoption,
    settle delay.

## Known limitations (carried forward)

See `docs/m3-report.md` §18.

* **Native HYW targeting**, as above.
* **Short temporary hostility.** HYW's temporary hostility lasts about 11 s; deployment re-engages
  on every M2 scan.
* **Inactive time is runtime-tracked.** Time a village spent inactive before a server restart counts
  as active for the MISSING/LOST timers after the restart.
* **Headless verification.** Permissions and Millénaire coexistence were verified without a real
  player client.
* **Occasional single-tick maxima.** Maxima of 10–14 ms occurred during fights and fills on the
  shared test host. They are not fully attributable; per-tick p99 was ≤0.7 ms.
