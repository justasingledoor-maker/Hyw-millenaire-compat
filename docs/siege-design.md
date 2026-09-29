# Sieges (post-M5): design

## The problem

The garrison used to join the village's own Millénaire raids, and in play the two systems clashed.

* **How Millénaire raids work.** Its raiders are villager records that materialize at the target, loaded or not.
* **How the garrison works.** Its soldiers are real entities, which exist only while their home is loaded.
* **What went wrong.** A player waiting at the target saw no HYW soldiers join: the home was unloaded, so none could be
  chosen. When the 2–5 Millénaire raiders died, the raid ended, however many HYW soldiers were still fighting.

## Decisions (user)

* **Raids.** Millénaire raids are Millénaire-only again. The garrison no longer joins them: `hywmill_duties`
  `raid.enabled` is false; the code stays.
* **Sieges.** A separate, HYW-only system.
* **Unwatched sieges.** A siege nobody watches is resolved off-screen by comparing strength.
* **Victory.** Tribute money, and standing for the players who fought with the winner.
* **Who starts sieges.** Both players and villages:
  * players, as a counsel from the Politics screen (Patron or Sworn on campaign);
  * villages at war, now and then on their own.

## Lifecycle (`Siege`, persisted in the ledger; `SiegeService` ticks every second)

1. **MUSTER** (`musterTicks`). The host is chosen from the attacker's living garrison with the raid planner's rules
   (`commitFraction`, `minHome`, kept sentry pairs and reserve). Chosen slots become DEPLOYED, with duty SIEGE, which is
   away from home: excluded from duties and defense. Loaded units walk to the muster point.
2. **MARCH** (a travel time proportional to the distance). The host is stowed:
   * Each slot drops its entity (`entityUuid = null`); a loaded entity (and its mount) is discarded.
   * The Reconciler ignores slots without an entity, so a marching soldier is never counted missing.
   * `JoinAdjudicator` refuses any old entity that loads later as a duplicate, so nothing is duplicated.
3. **Arrival.**
   * If the target's landing point (Millénaire's raid landing point) is loaded, the host materializes there. Each slot
     gets its next generation and a deterministic UUID, spawned by the unit provider with the attacker's equipment.
     Then **BATTLE** starts.
   * Otherwise the host **WAITS** (`waitTicks`), then is resolved off-screen.
4. **BATTLE (watched).**
   * The host advances and engages the target's combatants. The war's HOSTILE projection makes both garrisons fight.
   * The attackers win when the defenders fall to `breakFraction` of their number at the start; they lose when their
     own host falls to `routFraction`.
   * At `battleTicks` the side that kept the larger share wins.
   * If the target unloads mid-battle, the survivors are stowed and the rest is resolved off-screen.
   * Players on campaign with either side who are near the target are recorded as helpers.
5. **Off-screen resolution.**
   * Host strength H = Σ cost × (1 + 0.25 × equipment level). Defense D = the target's garrison at home, the same way,
     plus `millenaireWeight` × Millénaire's defending strength, times (1 + fortification/100, at most +50%).
   * P(attacker wins) = H^e / (H^e + D^e), drawn from the siege's seed.
   * Losses are HYW slots only (DEAD). The loser loses `loserLoss`; the winner loses the smaller share
     `winnerLossBase × loser/winner`, capped. Millénaire villagers take no off-screen losses.
6. **Outcome.**
   * The loser's tier sets the tribute (in deniers). `levyShare` of it becomes levy points for the winner's recruiting,
     taken from the loser's levy.
   * `playerShare` of it is paid as Millénaire money to the winning side's helpers; an offline helper is paid at login.
   * Helpers also gain `helperRep` reputation and SIEGE_VICTORY Favor with the winner.
   * The chronicle and the players involved are told.
7. **RETURN.** The survivors are stowed and march home. They materialize at the home anchor once it is loaded, then take
   the normal return path (RETURNING, then back to their duty).

## Triggers

* **Counsel (player).** Requirements:
  * at war;
  * on campaign with the attacker against this target;
  * Patron or better;
  * the attacker is not already besieging, the target is not already besieged, and the host is large enough.

  It costs `counselPoints` diplomacy points, then a cooldown. The chance is by standing (Patron 50%, Sworn 75%), ×
  `tooStrongFactor` when D > 1.5 × H.
* **Villages.** Checked every `aiInterval`. At war, not in a truce, no siege under way, past the cooldown, a large
  enough host and H/D ≥ `aiMinRatio` launch with `aiDailyChance` per day.
* **Admin.** `/hywmill war admin siege <attacker> <target>` launches a siege at once.

## War and peace (post-M5, fix25)

* **Peace after a siege.** Every finished siege ends the war between the two villages: the loser sues for peace. Both
  relations are set to `warCounsel.peaceRelation` (−75), above open conflict (−90), so the war does not restart
  unless the villages drift back into conflict by themselves. Any other host between the two is called home, with no
  outcome.
* **War and peace counsel.** From the Politics screen, a Patron or Sworn player can suggest war (between villages at
  peace) or peace (between villages at war). `WarCounsel` holds the pure rules; the service is `WarCounselService`.
  * **War.** The council decides alone. A declared war starts at once, with both relations set to −100.
  * **Peace.** The council decides, then the enemy weighs the armies. It accepts with probability equal to the
    proposer's strength share, ours²/(ours² + theirs²), clamped to 0.1–0.9.
  * **Cost.** Diplomacy points (war 2, peace 1) and a one-day cooldown per player and village.
  * **Operators.** Operators can force either with `/hywmill war [for <player>] declare|peace <village> on|with <other>
    force`, which skips the standing, the cost and both rolls.
* **Mobilization.** A village that is not a stronghold fills its garrison up to its current target when it goes to
  war.
  * It pays no levy for these troops.
  * They are equipped at `max(1, regular − 1)`: never a Watch's clubs.
  * They serve like any other unit.
  * When the village is at peace again they are discharged (LOST, DISCHARGED). A soldier who is away on a siege is
    discharged when the host comes home.
