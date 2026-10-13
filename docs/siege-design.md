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

*1.9 (fix72): the militia, hired company and lord's household are gone; see "Levies and scouts".*

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

*1.9 (fix72): the build-up is one day; see "Morale".*

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

*1.9 (fix72): victory, the final stand and the fall-back are decided by morale and the days by the host's size; see "Morale".*

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
* **The final stand (1.6; the defenders' only since 1.8).** So that a siege does not drag on for days: when the defenders'
  standing men (not wounded) are down to a fifth of the strength they brought while they still have wounded, their wounded
  rise and go back in at once, a final stand (once a wave). From then until sundown only their standing men count: down to a
  fifth again, the walls fall that day. In the world the wounded come from inside the village and the relief's to their
  posts; far from any witness the stand is drawn at sundown by strength (stand and risen wounded against the host's standing
  men, squared): it holds, or it breaks and the siege is decided. A stand that lasts to sundown changes nothing more: the
  night and the next dawn go as ever. The siege bar says "(final stand)". A national capital's stand brings out its ruler
  and royal guard (docs/realm-design.md §14).
* **The host falls back (1.8).** The attackers make no final stand. When the host's standing men are down to a fifth of
  its strength while its wounded keep it above that (wounded counted as alive), it falls back to its lines (out of the
  world, as at sundown), tends its wounded as in a night and comes on again once regrouped: at least 2 min and by day, or
  after the longest night. That assault counts as one of the three; falling back on the last one ends the siege in a
  stalemate. The host is beaten only when it is down to a fifth, wounded included. The siege bar says "(the host regroups)".
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

* **Recall.** At the announcement the target calls home everyone away but its scouts and supply convoys: detachments lent
  to players, relief it sent or promised elsewhere, its vassal levies on the road, raid contingents, and its own host if it
  marches on another village (fix51: a host already fighting breaks off that siege: a stalemate, nothing paid).
* **Reserves (fix52).** In a wave fought in the world, the host holds back 45% of its soldiers at its camp. The defenders
  hold back 35% of their garrison inside: those nearest the centre, but never one a player stands within 24 blocks of.
  Sides of fewer than 8 keep no reserve. A reserve goes in, 8 a step, whenever its side's front is down to 60% of what it
  was, and all of it from noon (6,000 ticks into the wave): the host's from its camp at the landing, the defenders' in a
  sally from the town hall. The defenders' reserve counts as defenders. Reserves stand down at sundown and are picked again
  at the next dawn.
* **Resistance (fix52).** While a wave is fought in the world, everyone near the besieged village has Resistance (III since fix54)
  (config `politics.siegeResistance`, 0-4, default 3 since fix54; 0 is off). That means players, every HYW unit (players' own troops however
  often they are placed or picked up), the village's Millénaire villagers, and what they ride. It is refreshed while they
  stay and wears off within five seconds of leaving or of the fighting's end. Damage taken is 40% lower, so fights last
  about 1.67 times as long.
* **Engines (fix53).**
  * **Trebuchets are back.** The arsenal draws trebuchets twice as often as a catapult or a springald, so about half a
    village's engines are trebuchets.
  * **Trebuchets stay home.** Each trebuchet marches with a siege host only one time in five; otherwise it stays behind,
    with its engineer. Catapults and springalds always march, each with one engineer.
  * **Batteries round the edge.** At home, the engines stand at batteries round the village's edge: one per equal sector,
    four blocks to the side of a road (Millénaire paths) between 70% of the village's radius and just past it. Where a
    sector has no road, the battery is on open ground at 85% of the radius.
  * **Never in the middle.** An engine at home inside half the radius (from before) is moved out to its battery while no
    siege is fought there. Engines coming home from a siege, or reappearing, go to their batteries too.
  * **Attacking engines move up.** Every 10 s, a host's engine with no target and no foe within 70% of its reach moves
    its home 12 blocks towards the village; it packs up to move and sets up again where it stops. It stops at the
    village's edge. Reach: catapult and springald 100, nest of bees 128, trebuchet 170.
  * **Admin.** `/hywmill war admin rearm <village>` raises a fresh arsenal for a village at war under the current rule.
* **Amnesty (fix55).** When a siege is won or lost (not a stalemate), the loser's terms forgive every player who helped the
  winners: on campaign with them near the battle, or striking their foes. Their grievances with the loser are wiped,
  including a peacetime killing, so an outlaw is pardoned. Their combined reputation with it is raised to at least the
  Trusted line, so they are Trusted at least. A better standing is kept.
* **No help from the besieged (fix51).** While a siege against it is declared or fought, a village sends no relief, no
  vassal levy, joins no raid, lends no detachment and launches no siege of its own.
* **Refugees.** (Removed in 1.9: levies instead.) 15-20 from the countryside took up arms.
* **Fortified wounds.** Defenders' wound chance 45% + 0.25% per fortification point, at most 60%.
* **Surgeons.** Night deaths of the wounded 10%, 4% with surgeons: defenders with barracks, a fortified town hall or a
  Garrison/Stronghold town; attackers whose host comes from a Garrison/Stronghold town.
* **Numbers.** Garrison caps 48 / 96 / 115 / 128 (Watch, Guard Post, Garrison, Stronghold); relief 20-60% of the helper's
  garrison (at least 12, chance 50%); vassals 25-38; mercenaries 45% (attackers) and 40% (defenders), 18-35 men.

## Morale (1.9, fix72)

The user's decisions: armies do not fight to the last man but break at 10-30% losses, as real ones did; big armies fight
longer, small or lopsided ones are settled quickly; the build-up is one day, not three; sieges stay epic (engines, waves,
assaults); Resistance goes up. This supersedes the victory rule, the final stand and the fall-back of "Siege battles in three
waves" and the refugees, militia, defenders' hired company and lord's household of "Help for the besieged" and "Desperate
defences". The rules are pure (`Morale`):

* **Resolve.** Each side has a resolve: the share of the men it brought that may fall (dead or wounded) before it breaks.
  * By grade: levies (mobilized men, militia, pressed levies) 10%; hired swords 16%; regulars 20%; veterans (equipment 4+),
    the household, the royal guard and the ruler 30%. A side's resolve is the average of its men's.
  * Circumstances: at home +3%, and walls up to +8% (0.15% a fortification point); numbers up to ±5% (4% for each doubling
    of the odds); a ruler in the field +5%; surprised -4%. It stays between 8% and 36%.
* **Assaults.** A siege is fought in assaults. An assault breaks when the men the host threw in have lost its resolve: it is
  thrown back, falls back to its lines (out of the world), regroups for two minutes and, if the day lasts, forms up and
  comes on again (the second assault, the third...). The host gives up the siege when it has lost its campaign's worth (1.6
  times its resolve, at most 45%, all assaults counted), judged when an assault after its first is thrown back or at the
  day's end: a first assault thrown back always comes again. The defenders yield when they have lost their resolve, all assaults
  counted. So a siege ends with 10-30% of the beaten side down, never the last man.
* **A clash takes time.** A side breaks only once the assault has lasted a minute and a second and a half more for every man
  in it (at most five minutes), unless it has lost twice its resolve. Big armies fight longer.
* **Days.** A host keeps the field 2-4 days by its size (1 + a thirtieth of its men); after its last day it goes home: a
  stalemate. Far from any witness a day is drawn (`Morale.paperDay`): the side that loses the day loses its resolve's worth of
  the men in the fight, only part of it when it is big (no more than about 60 stand in the front), the winner a third
  (defenders) or two thirds (attackers, walls are costly) of what it inflicts. Small or lopsided sieges end the first day;
  big, even ones take days and several assaults.
* **The ruler rallies.** When a realm's capital's defenders waver (three quarters of their resolve gone), its ruler comes out
  with the royal guard (as before), and their resolve rises by 5%.
* **Field battles.** In the open, the same: a side breaks at its resolve and is pursued (4-12% more fall, up to 10% more with
  the victors' horse). The marshals' orders (pursue, rally) read the spirit left (`Morale.spirit`).
* **Resistance.** Soldiers in a siege have Resistance IV (config `siegeResistance`, default 4, was 3); players Resistance I
  (`siegeResistancePlayers`, default 1). An existing server config keeps its old value until changed.
* **The build-up: one day.** A siege announced is before the walls at the next dawn (at least 9,000 ticks of build-up, else at
  the dawn after). The muster and the march by night are as before.

## Levies and scouts (1.9, fix72)

* **The besieged see the host coming.** At the announcement both sides know the other only by rumour (±40%). The besieged
  press their countryside into service against the host as they believe it to be: poorly armed levies (militia, spearmen,
  shieldmen, a level below the levy kit), enough to stand at 85% of its numbers with the men they have (their walls make up
  the rest), at most 4 + a third of the village's people + a sixth of its realm neighbours' within 800 blocks (60 at most).
  The attackers keep the better troops. Levies go home when the siege ends.
* **Help left.** Allied relief, vassals and the attackers' mercenaries remain (columns on the road, as before). The
  refugees, the bells' militia, the defenders' hired company and the lord's household are gone.
* **Scouts count the enemy.** A village scout who found something brings a count of a siege's enemy six times in ten (the
  host at its muster or on its road, or the defence). A player riding for a side who comes within 64 blocks of the host (for
  the besieged) or of the walls (for the attackers) counts it and is paid 64 deniers. The alarm riders count the host too.
* **The councils act on it.**
  * The besieged, with the host counted, press levies by the count (more or none).
  * The besieged, seeing no hope (their strength under 40% of the host's), offer to yield before the assault; the host takes
    it by its aim (raze 20%, annex 70%, others 90%): taken, they yield without a blow. A realm's capital never offers.
  * The attackers, at the muster and when their scouts counted the defence, call the siege off if it is more than 1.25
    times their host; a council that never scouted judges by rumour.
  * Behind walls of 20+ fortification, a host that saw them brings its trebuchets.
  * A side that was never scouted and is 1.33 times what the enemy believed falls on it by surprise: the enemy's resolve -4%.

## Spoils (1.9, fix72)

* **Head prices.** A soldier killed by a player, or by one of a player's own men, in a war that player fights against the
  soldier's village (on campaign against it, serving a realm at war with it, riding for a side of a siege against it, or in
  his own assault on it) earns the player his price at once, paid by his side: a levy 1 denier argent, a regular or hired sword
  2, a veteran 6, a war engine 8, a royal guard 16, the ruler 4 denier or (a soldier costs about 1 or on the Muster Roll:
  a few dozen heads pay for one). Offline, it waits for him.
* **Merit.** Every head price is also merit in the sieges and assaults the soldier fell in. When a siege is won, the helpers'
  share of the tribute, plunder or indemnity is split three tenths equally (they were there) and seven tenths by merit; with
  no merit at all, equally. Tributes paid over days keep the same shares.

## A player's assault (1.9, fix72)

* **Who.** A player at war with a village (on campaign against it, or serving a realm at war with it) may lead his own men
  (the soldiers he hired, at least 2 within 96 blocks) against it. Not a village besieged already (join that fight).
* **His choices.** The side his men gather on (one of eight points of the compass; the rally point is 40 blocks outside the
  village's edge), how long until they go in (2, 5, 10, 15 or 20 minutes in the Politics screen; 1-30 by command), and
  whether to ask his side for help: the village he campaigns with, or his realm's member nearest the target, lends him up
  to 8 men (no more than he brings himself), who march to the rally point.
* **The village sees it.** When his men come within 64 blocks of its edge, or half the time has passed, it presses levies
  into service against the band (as for a siege, sized to the band).
* **The hour.** His men within 48 blocks of the rally point or already at the village, with the lent men, are the band; he must be there to lead them
  (else it is called off). Each side's resolve is reckoned as for a siege; his hired men count as the village's average
  soldier, a quarter better. They go in: each man without a foe takes the nearest defender.
* **The fight.** By morale: thrown back, the band falls back to the rally point and goes in again after 90 s; after a later
  repulse it gives up at its campaign's worth, or when the defenders held 15 minutes. One in three of his men who fall is only wounded, carried
  back to the rally point, weakened and out of the fight. The village yields when its defenders have lost their resolve: it
  pays him a ransom (one day of its tier's siege tribute); the village that lent men gets levy points by it. Every head taken
  earns its price.
* **Leaving.** A player gone from his band for a minute calls it off. Called off or over, his men stand down, the lent men go
  home, the levies go home.
* **Where.** The Politics screen's War tab: an assault bar over the reports (Rally side, In n min, With help/Alone, Lead
  assault on the selected village, Call off). Commands: `/hywmill war assault <village> <side> <minutes> [help|alone]`,
  `/hywmill war assault status`, `/hywmill war assault cancel`. His HUD shows the assault's state, his map the rally point
  with its clock.

## War on the roads (1.10, fix73)

The user's request: messengers, convoys and everything else that crosses from one village to another should take the roads
the Village Roads mod builds, so that a player can travel them and cut the enemy's supplies, for money and to help a coming
siege. With Village Roads installed (optional; HywMill calls its route API by reflection, `integration.roads`):

* **Who takes the roads.** Mercenary companies, messengers, the besieged's alarm riders, vassals' men, bribed companies on
  their new way, supply trains, siege hosts on the march (and so where the realm's army meets them: on the road, the two
  lines drawn up across it), relief forces on the map. Each goes across country to the nearest road (at most 160 blocks),
  along the roads through the villages they link, and off them to its end; a road journey is not taken when it is much
  longer than going straight (1.8 times and 64 blocks more) or the roads barely serve it. On the road travellers go a
  quarter faster, so a road a little longer takes no more time. Camped convoys pitch by a road leaving the enemy village.
  Without the mod, or where no road serves, everything goes straight as before.
* **Meeting them.** A column comes into the world near a player (96 blocks), on its road; its men march in file along the
  road's stretch they are on. A player who knows where the roads run can wait by them.
* **Supply trains** (`Supply`, pure; `Column.Kind.SUPPLY`). When a siege is announced:
  * the besieger's realm sends one train, and one more for each member that can spare one (three at most; the attacker
    first, then its nearest members, none besieged, within reach), to its host's camp before the walls, arriving through
    the first day of the siege;
  * the besieged's realm sends a train from each of its nearest members (three at most) to arrive before the host; one that
    comes once the siege is fought finds the host's lines barring the way.
  * A train is one to three carts (a cart for every three guards) and four to seven guards of its village, at a cart's pace.
  * **Arrived**, it stiffens its side: +2% resolve (two count). **Cut** on the road (its guards killed to the last man, by a
    player or by a council that caught it), it weakens it: -3% resolve (two count), and a host whose trains were cut more
    than arrived keeps the field a day less (never under one). Before the siege is fought these count when it begins; once
    it is fought, at once.
  * Whoever cuts a train: each player near it at war with its village earns a bounty from his side (8 deniers argent a
    cart) and merit in the siege; its carts stay on the road, loaded, for the taking. A train met and left goes on with what
    is left in its carts.
* **Head prices on the road.** Column soldiers killed by a player or his men in his war earn their price too (a guard 1
  denier argent, a messenger, scout or hired sword 2), with merit in the column's siege.
* **Finding them.** Scouts find columns as before; the War tab and the scouts' reports say whether a column is on the road
  ("riding for X by the road", "on the road", "camped by the road"). Found columns show on the map (JourneyMap: a cart
  marker) with where they ride and a clock to their arrival.
* **Admin.** `/hywmill war admin supply <attacker> [now]` sends (or lists) a siege's supply trains, with their roads.

## No friendly fire (1.10, fix74)

The user's request: helping in a siege, a player's arrows hit friendly soldiers and they all turned on him. While a player
fights for a realm (serving it as volunteer, mercenary or knight, or on campaign with one of its villages), there is no
friendly fire between him, the soldiers he hired, and that realm's soldiers and villagers (every village of it, the
column soldiers riding for it, a vassal's men riding to it):

* their arrows and other missiles pass through each other, on to whoever stands behind;
* a blow (melee, a sweep, splash, fire) does nothing, so nobody is angered and nobody turns on anyone;
* enemies are hurt as ever; between players and their men nothing changes.

Config `noFriendlyFire` (default true). `FriendlyFire` decides; `CoreEvents` cancels the projectile impact, the attack and
the incoming damage.

## Stray mounts (1.10)

An HYW rider's horse (or camel) is a separate creature. HYW removes it when its rider dies on it, but not in two cases:

* the rider is taken out of the world without dying (stowed for a march, dropped as a duplicate, sent off with a column);
* the rider loses track of its horse (it stands in a chunk not yet loaded) and HYW gives it a fresh one.

Those horses stood about for ever, saddled and armoured, and piled up in castle yards, slowing the game.

Every 10 s HywMill looks at the loaded soldiers' mounts (HYW's NPC horses and camels) in every dimension. One that no one
rides, whose rider is gone or dead or on another mount, is marked; one still so 30 s later is removed, with its gear and
nothing dropped. A rider who comes back to find his horse gone gets a new one (HYW).

Never touched: a horse a player owns (its owner a player who has been on the server), one ridden, one on a lead, one with a
name, and a horse that is no soldier's (not an NPC horse). The log says how many were removed.
