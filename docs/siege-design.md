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
   * **Paid daily (post-M5, fix36).** The loser pays the full tribute (its levy points, and each helper's share) every
     Minecraft day for 3–5 days, drawn from the siege's seed. The first day's payment comes at once, so a victory is a wage
     for the days after it.
     * Payments stop if either village is gone.
     * `/hywmill war tributes` lists the tributes being paid. The dev command `/hywmill war admin tribute-due` makes the
       next day's payment fall due now.
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

## Relief forces (post-M5, fix26)

* **Who.**
  * When any siege is launched (by counsel, by a village's own decision, or by an admin), each village that meets all
    of these conditions promises relief with `relief.chance` (0.2):
    * a relation of at least `minRelation` (80) with the besieged village;
    * at peace with the besieged village;
    * not a party to the siege and not a lone building;
    * at least `minGarrison` soldiers at home.
  * At most `maxHelpers` (6) villages relieve one siege.
* **When.** The force sets out when the attackers finish mustering and march. It is 5–20% of the helper's garrison
  at home. It travels stowed, by forced march, and arrives after 1–2 minutes, usually before the attackers.
* **At the target.**
  * The soldiers appear on a ring round the village (half its radius, 12–40 blocks).
  * While the force is out, the helper's and the attacker's factions are projected HOSTILE (`RelationPlan` relief
    pairs).
  * The relief counts among the defenders, both in watched battles (foes, defenders, the 20% line) and off-screen
    (strength, and a share of the defenders' losses).
* **On the way.**
  * **Ambush** (12%). 20–60% of the force is killed, a real loss to the helper's garrison. With a further 40% chance
    the survivors rout home and none arrive.
  * **Lost** (6%). 30% to all of the force never arrives. These soldiers go home unharmed.
* **After.** When the siege ends, or peace calls it off, the force goes home. The siege record stays until the relief
  is back.
* **Admin.** `/hywmill war admin relief <helper> <target> [clean|ambushed|routed|straggled|lost]` makes the helper
  relieve the siege of the target, optionally with a forced fate.

## The field of battle (post-M5, fix32)

* **Landing in groups.**
  * The host no longer lands as one block. `SiegeGroups` splits its soldiers into groups of 4–8, about six each, so a
    50-soldier host lands as eight groups.
  * Group 0 lands at the main landing, on the side facing home. The others land round the near half of the village's rim,
    alternately either side, within 110° of the main landing, with jitter and 0–12 blocks of extra depth.
  * Each group needs dry ground with a dry way in. If none is found, its bearing is turned 12° either side, and if that
    fails too the group lands at the main landing.
  * The engines still set up behind the main landing.
* **Autonomous combat.** Every attacker placed, and every HYW defender (garrison and relief), is set to HYW
  `FREE_FIGHT` for the battle. Defenders go back to `DEFAULT` when it ends. Attackers take the garrison's default again
  when they re-materialize at home.
* **Boss bar.**
  * Each siege in battle has one boss bar, shown to players within the village radius + 80 of the target.
  * Its title: "Siege of B: A attackers-standing/start vs B defenders-standing/start".
  * Progress is the defenders' share still standing. The colour is the nearest bar colour to the attacker's first livery
    colour.
  * It is runtime-only: rebuilt from the ledger and removed when the battle ends.
* **Mercenaries.**
  * One minute before the host arrives (`Mercenaries.LEAD`), there is a 25% roll, drawn from the siege's seed, that the
    attacker has hired a free company. The candidates are the Brabançon routiers, Genoese crossbowmen, Turcopoles,
    Daylamites, nobushi, ronin, Bhil, Pindari and Turki horse archers.
  * A company brings 10–20 soldiers who wear its look and no village livery. They are equipped like the village's levies,
    a step below its regulars.
  * They join the host as mobilized roster slots marked `mercLook`: DEPLOYED, duty SIEGE, stowed. `hostStart` grows by
    their number.
  * They do not count towards the village's garrison (`live()`), are never re-equipped by duties, and are paid off
    (LOST, DISCHARGED) when the siege ends or is recalled.
  * The deal is announced and chronicled.
  * Admin: `/hywmill war admin mercs <attacker>` forces a hire before the host deploys.
* **Garrison rotation** (`mobilization.rotateInterval`, default 2400 ticks; 0 turns it off).
  * During a war, a village that is calm, at strength and not topping up levies does one of these every interval (and
    not within one interval of its last levy or recruit):
    * sends its longest-serving levy at home back home;
    * recruits a paid regular in his place, but only if its levy points cover the regular's cost.
  * The army's quality recovers over a long war.

## Help for the besieged (post-M5, fix38)

* **When.** About a minute before the attackers arrive, at the same moment as the attackers' mercenary roll, the target
  rolls for help. Each kind is a separate chance (`DefenderAid`):
  * **Militia (50%).** 5–15 HYW soldiers in levy kit, from the mobilization levy units: 5 + population / 6, ±2. These are
    HYW soldiers only; Millénaire's AI is not touched.
  * **Mercenaries (20%).** A free company of 10–20, as for the attackers, in its own look.
  * **The lord's household (30%, Garrison and Stronghold villages only).** 6–8 soldiers of the culture's best squad, in
    its look, in the village's colours, at Stronghold equipment.
* **What they are.** Temporary slots of the target's roster (`RosterEntry.extra`), held in `Siege.extras`:
  * If the village is loaded they appear at home at once; otherwise when the battle starts.
  * They count among the defenders, in the watched battle and off-screen.
  * They take no standing duty and are not counted in the garrison.
  * They go home (LOST, DISCHARGED) when the siege ends, is called off or is lost.
* **Admin.** `/hywmill war admin aid <attacker>` brings every kind at once.
* **Relief forces, bigger and more frequent.**
  * Friends at relation 70+ now send help, not only 80+.
  * The chance is 35% per friend, up from 20%.
  * The force is 8–25% of the friend's garrison at home, and at least 5 soldiers (`minForce`), as long as that leaves the
    friend half its garrison.
* **A host on its way home blocks nothing.** It no longer stops its village from launching a new siege, or its target from
  being besieged again.
## The build-up: two days, columns on the road, scouts (post-M5, fix43)

* **Timing.** A siege is announced, then prepared for two days (`Siege.Phase.PREPARE`). On the third day the host musters
  (the old one-minute muster) and marches out by night, so that it is before the walls **at dawn** (game day time 0 of the
  third day). Siege time counts the larger of game time and day time since the announcement, so a night slept through or
  `/time add` moves the build-up on. The host is chosen when it musters, not at the announcement; if too few soldiers are
  left then, the siege is called off. `war admin siege <a> <t> quick` keeps the old flow (no build-up) for tests.
* **Columns** (`Column`, `ColumnService`). Anything that travels between villages in a war is a column on a straight road,
  positioned by time while nobody is near. Within 96 blocks of a player it comes into the world as soldiers of its village
  (untagged HYW units of its faction: hostile to a player on campaign against that village, and to his men). Its march is
  held while they stand; when no player is within 144 blocks they leave the world and the column goes on with its
  survivors. Killed to the last man it is destroyed and never arrives. Columns:
  * **Mercenaries** hired by either side at the announcement (25% attacker, 20% defender), riding 500-900 blocks from afar
    and arriving during the two days. They join the host when it musters, or man the defences.
  * **Messengers**: the besieged send one to each ally the relief rules chose (a relief comes only if its messenger
    arrives) and each side to its vassals. A vassal's messenger, if answered (75%), turns into a **vassal column** of 10-15
    mixed men riding to the overlord. Killing envoys denies reinforcements.
  * **The alarm**: when the host marches (50%), the besieged's scouts ride home with the news (seen by the host's pickets:
    known to the attacker at once). If they arrive, the besieged **open their coffers**: regular recruitment four times as
    fast for a day and 4 levy points (emergency levies are not touched).
  * **Supply convoys**: an enemy convoy camped for the night, found by scouts at any time in a war (siege or not). One to
    three AstikorCarts supply carts, each with a horse hitched (a chest minecart without AstikorCarts), 4-8 guards resting
    round them (not on autonomous combat). Its carts hold the culture's food and drink, its soldiers' kit and camp supplies,
    by theme: military, provisions, mixed, trade, siege works, armoury. Killed to the last man, the carts stay for the
    taking; left alone, the convoy breaks camp and is gone. Recruitment (levy) points are not touched.
* **Scouts.** Light horse (light lancers, horse archers) are commoner (composition weights up, from Guard Post) and do not
  march with sieges: they scout. Every minute each village at war may (40%) send one out for 2-4 minutes. One in ten does
  not come back (killed); of the rest, one in two found something: the nearest enemy column nobody found yet, else (half the
  time) an enemy supply convoy. Players on campaign with that village, and near it, are told what was found, its
  coordinates, its distance and direction from where they stand, and its arrival.
* **Decisions.** A found column is listed in the **War tab** of the Politics screen (open ones first, then past ones). A
  player on campaign against its village may **Intercept** (take the job: the council leaves it to him) or, for
  mercenaries, **Bribe** them from the tab (48 deniers a man): the company then rides for his side. In person, mercenaries
  are hostile and fight. If nobody takes it, after a while (an hour, or half its remaining road) the council decides: half
  the time it acts, and then catches it six times in ten. `/hywmill war intel [take|bribe <id>]` does the same in chat.

## Siege battles in three waves (post-M5, fix44)

The user's decisions: help (mercenaries, vassals, relief, the besieged's militia and household) joins only before the first
assault; wounded soldiers of every kind (help included) come back; defenders are wounded more often (45%) than attackers
(35%), being at home; the first side down to 20% alive or wounded loses; no camp is built in the world; engines are
treated like soldiers ("repairable" or "beyond repair"). The rest (`SiegeWaves`, `SiegeService`):

* **Days.** The host arrives at dawn and the first wave begins. A wave runs until sundown (at least 2 minutes, at most half a
  day without a daylight cycle). At sundown the host withdraws to its lines (it leaves the world) and the defenders stand
  down: `Siege.Phase.NIGHT`. At the next dawn the second wave begins, then the third. Sleeping moves it on.
* **Wounds.** When a soldier of a siege (the host, the target's garrison and temporary help, a relief force) falls in a
  wave, his death is cancelled at his side's chance (35% attackers, 45% defenders): he is carried off the field (stowed,
  `RosterEntry.wounded`), never placed in the world until dawn, and counts as alive for the victory rule. In the night each
  wounded man dies of his wounds one time in ten, else stands again at dawn. An engine knocked out is repaired overnight, or
  found beyond repair at the same chance.
* **Victory.** Checked all the time during a wave and again at dawn: the first side down to a fifth of the strength it
  brought to the walls (alive or wounded) loses at once (if both, the attackers: a host that cannot hold the field gives
  up). Strength: the host's soldiers; the defenders' garrison, temporary help, relief at the village and Millénaire
  fighters (counted in the world during a wave fought there, else as at the first dawn).
* **Stalemate.** After the third wave, neither side beaten: the attackers give up and go home. No tribute, no peace, no
  vassalage; the war goes on. The History tab says "stalemate".
* **Unwatched waves.** A wave nobody watches is drawn at sundown: the side that holds the field (by the strength odds of the
  off-screen rule) loses 8-18% of the men it put in, the other 22-38%, each who falls dead or wounded at his side's chance.
  A player who comes during a wave brings it into the world from then on.
* **No help mid-siege.** Columns (mercenaries, vassals, messengers) and relief forces still on the road when the first wave
  begins come too late and turn back. No recruiting or levies at the target during waves and nights.
* **News.** Horns and announcements at each dawn ("Dawn of the second day ... forms up again (n of m)") and sundown (the
  day's dead and wounded each side, who can still fight, days left), the night's news (who rose to fight again, who died
  of wounds, engines repaired). The boss bar shows the day (1-3) and night. The battle report adds one line per day and the
  totals of dead and wounded.

## Desperate defences (post-M5, fix46)

* **Recall.** At the announcement the target calls home everyone away but its scouts: detachments lent to players, relief
  it sent elsewhere, its own host if it marches on another village and is not yet fighting.
* **Refugees.** 15-20 from the countryside take up arms (militia, some spearmen; temporary, like the militia).
* **Fortified wounds.** Defenders' wound chance 45% + 0.25% per fortification point, at most 60%.
* **Surgeons.** Night deaths of the wounded 10%, 4% with surgeons: defenders with barracks, a fortified town hall or a
  Garrison/Stronghold town; attackers whose host comes from a Garrison/Stronghold town.
* **Numbers.** Garrison caps 48 / 96 / 115 / 128 (Watch, Guard Post, Garrison, Stronghold); relief 20-60% of the helper's
  garrison (at least 12, chance 50%); vassals 25-38; mercenaries 45% (attackers) and 40% (defenders), 18-35 men.

## Alliances in war (post-M5, fix47)

* When a war starts, the attacked village's allies are called: relation 70 or more, its vassals and its overlord.
* An ally of both sides stays out and helps neither.
* Any other ally joins (it declares war on the attacker) or breaks the alliance (relation 10). The chance is 40% at the
  threshold, more for close friends, vassals and enemies of the attacker, less down the chain.
* An ally that joins calls its own allies, at most 2 links deep and 8 joiners.
* A village's side in a war is recorded (`WarRecord.sides`); relief and vassal help go to that side only.
