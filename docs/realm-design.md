# Realms: treaties, war aims, provinces, loyalty (post-M5, fix56+)

Villages form official treaties, fight for aims the village AI chooses, and build realms of provinces (conquered villages)
and vassals that grow, fight and rebel. Everything here is HywMill state in the ledger (no new SavedData) projected
through Millénaire's relations and HYW's faction relations; Millénaire villages stay Millénaire villages.

## 1. Treaties (official alliances)

Treaties are the only bonds that oblige. Each tier is open while the two villages' Millénaire relation stays at or above
its threshold; it lapses when the relation falls 10 points below it, or when the two go to war.

| Treaty | Relation | What it does |
|---|---|---|
| Non-aggression pact | ≥ 25 | Neither besieges the other. |
| Defensive pact | ≥ 50 | When one is besieged, the other must send relief (always comes, 30-50% of its free garrison). |
| Military alliance | ≥ 75 | Defensive pact, plus: joins the other's wars, and sends a contingent (20-35%) with its sieges. |

* **Formation.** The village AI looks once a day at each pair of villages within 2,500 blocks of each other, not at war
  and not in the same realm. When their relation qualifies for a tier above their current one, they sign it, one time in
  four per day. Treaties appear in the chronicle and the Politics screen.
* **Vassalage by choice.** A weak village at war, with relation ≥ 75 to a much stronger village (at least three times its
  strength), may swear fealty to it for protection (rare).
* Provinces and vassals have no treaties of their own: their sovereign's treaties are theirs.

## 2. War obligations: choosing a side

A war starts between X and Y as before (relation at open conflict). At that moment every village bound to X or Y by a
military alliance or a subject tie (vassal, province, sovereign) must act, if the enemy is within 2,500 blocks:

* **Bound to one side only:** it joins that side. Its Millénaire relation with the enemy drops to −100, so it is at war
  with the enemy a day later (the existing war rule).
* **Bound to both:** it sides with the stronger bond and breaks with the other. Bond = tie weight (province 6, vassal 5,
  sovereign 4, alliance 3) + relation/100 + 0.3 for the same culture. The broken treaty ends; a broken subject tie is a
  rebellion.
* The new wars start later and oblige the joiners' own allies in turn, so wars spread along alliance chains over days,
  not all at once. A war joined for an ally remembers it (`joinedFor`). When the ally makes peace with that enemy, so
  does the joiner.

## 2b. Keeping the peace on one side

Villages that send men to one side of a siege must not fight each other.

* **No fighting beside a foe.** An independent village (a vassal, an ally, a friend sending relief) will not send relief,
  a vassal levy or an ally's contingent to a side where a village it is at war with, or holds at −50 or worse, already
  has men. The chronicle says so ("X will not march beside Y"). First come, first served: the later helper stays home.
* **Provinces mind no one.** A province always comes. It has no wars of its own: once a day, any war it is in that its
  sovereign is not in ends in peace, so it never fights its sovereign's friends.
* **Comrades in arms.** While a siege is undecided, every village with men on one side of it (the besieged and its
  relief, or the attacker and its provinces', allies' and vassals' men) is FRIENDLY in HYW with the others on that side,
  and with their villagers, over any feud of their own. HYW then cancels their friendly fire and never retaliates between
  them. A pair on opposite sides of any siege is never made comrades. When the siege is decided, their own relations
  (a war's HOSTILE) come back.
* **The road.** Scouts and councils go after a column (mercenaries, a vassal's men, a messenger) only if its men are their
  village's enemies and ride for an enemy, and their own village has no men on that side of the siege. A company hired by
  a friend, or a vassal's men riding to a friend, are let pass.

## 3. War aims: the AI director chooses

Each siege now has an aim, chosen by the attacker when it is launched (counsel or AI) and announced with it:

| Aim | On victory |
|---|---|
| **Subjugate** | Tribute for 3-5 days, and the loser becomes a vassal (as before, but the vassalage no longer expires: it lasts until it rebels). |
| **Annex** | The loser becomes a province of the winner's realm (§4). No tribute. |
| **Punish** | No vassalage, no tribute, no peace. A large one-off indemnity: 3× the tribute in levy points to the winner and its helpers' share in money, and the loser's levy reserves are emptied. Weakened, the loser sues for peace (likely within a day). |
| **Raze** | The loser is destroyed with the negation wand's mechanics: its villagers and garrison vanish, its Millénaire record is deleted; the buildings stay as ruins. |

**How the director chooses** (weighted draw, stable per siege):
* Subjugate: base 3; +2 if the target is far (> 800 blocks); +1 for a different culture.
* Annex: base 2; +2 for the same culture; +2 if near (< 600 blocks); −2 per province beyond the realm's capacity (1 per
  tier step of the sovereign: WATCH 1 … STRONGHOLD 4); never a stronger-tier target.
* Punish: base 2; +1 per earlier siege between the two (a grudge); +1 if the target is of a higher tier.
* Raze (only if `politics.razeEnabled`; never a player-controlled village): 0 unless there is a grudge of 2+ sieges or
  the target rebelled against the attacker; then 1 + grudge, ×3 after a rebellion, ×0.3 for a target of GARRISON tier or
  above.

When the attackers lose, the defenders make them pay tribute; a much weaker attacker (two tiers lower) also becomes their
vassal.

## 4. Provinces (conquered villages)

* **Autonomy.** The Millénaire village goes on as before: its villagers, culture, buildings and trade.
* **Its army is the realm's.** Its old garrison is disbanded at the conquest, and a new one is raised at once and kept up
  as before. 70% of its recruits are of the sovereign's culture (drawn from the sovereign's unit table, in its kit), 30%
  of its own culture. All of them wear the sovereign's colours. A Norman village that takes a Seljuk one fields a Seljuk garrison of mostly Norman
  troops in Norman colours.
* **Foreign policy is the sovereign's.** A province launches no sieges, signs no treaties, and is at war with its
  sovereign's enemies (relation −100) and at peace with everyone else. Relation with the sovereign: 100. Its faction is
  FRIENDLY with the sovereign's and the other provinces' factions.
* **It fights for the realm, always:**
  * when the sovereign besieges someone, every province sends 30-50% of its garrison with the host (they are marked
    away from home; who dies stays dead);
  * when the sovereign or another province is besieged, every province (and the sovereign) sends 30-50% as relief.
    It always comes;
  * a province's soldiers count in the realm's battles like the sovereign's own.
* A province's own vassals and provinces pass to the conqueror.

**Vassals** keep their army and colours: they answer the call with their usual levy (75%) and their own relief. They are
bound to their lord's wars like military allies.

**Military allies** send 20-35% of their free garrison with each other's siege hosts (within 2,500 blocks of the target,
and not against a village they have a stronger treaty with). Those men march with the host as stand-ins in their own
colours and kit; their own slots are away meanwhile and share the stand-ins' fate (dead with them, or home again).

## 5. Loyalty and rebellion

Every vassal and province has a loyalty of 0-100. Provinces start at 50, vassals at 40.

* **Daily:** provinces drift towards 60 and vassals towards 50, at 2 a day. Each day costs 0.5 for a different culture
  from the sovereign, and 0.5 if more than 1,000 blocks from it.
* **Events:**
  * −0.5 for each of its soldiers killed in the sovereign's wars (at most −15 a siege);
  * −20 if it is besieged and falls;
  * +10 if it is besieged and holds;
  * +3 when the sovereign wins a siege it sent men to.
* **Rebellion:** below 30, once a day. The chance is (30 − loyalty)/30 × 25% for a vassal, or × 6% for a province (about
  a quarter as likely). It is doubled when the sovereign's garrison is smaller than the subject's.
  * A rebel is free at once, and the whole realm is at war with it at once (§8).
  * A province's garrison takes its own colours again, re-dressed by the existing livery mechanism.
  * The chronicle tells it; a realm may reconquer.

## 6. Politics screen

* **Village list:** each village shows its place: `[province of X]`, `[vassal of X]`, `[ally]`, `[defensive]`,
  `[pact]`.
* **New Realm tab**, for the selected village:
  * its realm: the sovereign, the provinces and vassals with their loyalty;
  * its treaties;
  * its wars, with who fights on which side and why ("joined for X");
  * the realms of the known world.

## 7. Switches and admin

* `politics.razeEnabled` (default true), `politics.realmsEnabled` (default true): treaties, aims other than Subjugate,
  provinces and rebellion.
* Admin, dev-gated where they bypass play:
  * `war admin treaty <a> <b> <pact|defensive|alliance|none>`;
  * `war admin annex <province> <sovereign>`;
  * `war admin loyalty <subject> <value>`;
  * `war admin siege <a> <t> [quick] … aim <subjugate|annex|punish|raze>`;
  * `war admin wars`: every war in the ledger.

## 8. Realm unity (1.6)

A realm is a capital and its subjects (vassals and provinces), however many. It has one diplomacy and one war.

* **Entering a realm.** A village that swears fealty or is annexed makes peace with every member it was at war with and is
  friends (relation 90) with all of them. The realm's wars become its own at once, and its own wars the realm's (a village
  that swears fealty for protection brings its war along).
* **Provinces** stay friends with every member (kept at 90 every village decision); their diplomacy is the realm's.
* **Shared hatred.** A relation of −50 or worse is hatred. The realm's feeling for an outsider is its worst member's when
  that is hatred: whoever hates first, the capital or any subject, the whole realm comes to hate that village as much (each
  village decision). Relations are mutual, so the outsider's own realm answers in kind. Lone buildings are left out.
* **One war.** A war between villages of two realms (by any cause: declared, open conflict, a raid, a rebellion) is a war of
  every village of one against every village of the other, at once; a truce between a pair holds. Allies by treaty of the
  two capitals then take sides as in §2. A peace between villages of two realms is the realms' peace: every war between them
  ends. Players counsel war and peace to the capital: a subject answers "the realm's war and peace are made there".
* **The call to war.** Provinces always march. Each vassal answers:
  * loyalty 20 or less: it refuses and breaks away: free, and at war with the whole realm at once;
  * loyalty under 40 and a friend (75 or better) of a village of the enemy's realm: the same;
  * holding a fellow member at −50 or worse: the capital's **council** decides: with loyalty 35 or more and the rest of the
    realm at least as strong as the vassal, it ends the feud by decree (the two are friends again; loyalty −10) and the
    vassal marches; else, with loyalty 20 or more, it lets the vassal stay home for **scutage** (64 deniers a soldier, paid
    to the capital each day for 3 days, as levy points; loyalty −5); else it casts the vassal out: free, and at war with
    the realm;
  * otherwise it marches.
* **Feuds in peacetime.** Two members at open conflict (−90) or at war go to the council at once: a capital or a province
  is never at fault; of two vassals, the less loyal is. It ends the feud by decree (loyalty 35 or more, the rest of the
  realm at least as strong) or casts the culprit out.
* The Realm tab shows a vassal excused for scutage.

## 9. The national army (1.6)

A capital with at least two subjects raises a national army: a muster roll, not a standing host. Each member pledges a
share of its free garrison (at home, on a standing duty), counted when the army is called:

| Member | Share |
|---|---|
| Capital | 60% |
| Province | 40% |
| Vassal | 25% at loyalty 50, in proportion to its loyalty (10%–40%) |

* **Its sieges.** A siege by the capital is the army's: the capital commits 60% of its garrison (instead of half), each
  province its 40% and each vassal its share (a vassal excused for scutage from that war stays home), as stand-ins in the
  host in their own colours, as provinces did before. The army musters at the member nearest the target, and the march is
  timed from there. The chronicle and the siege bar name it "the army of the realm of X". Military allies still send their
  20–35%.
* **Breaking a siege.** When any member is besieged, every other member marches to break it with its pledge, vassals too
  (instead of their old levy), as one army: the same road time for every contingent, and none waylaid or lost on the way.
* A realm with a single subject keeps the earlier rules (§4: the province's 30–50%, the vassal's own levy).
* **Realm tab.** "Army of the realm of X: N men pledged (capital a, provinces b, vassals c)", and the realm list shows it,
  then where each of its armies is (gathering, on the road, at the walls with its standing men, camped for the night,
  coming home, or marching to break a siege of one of its villages).
* **On the road.** A bar shows an army while it gathers and marches ("The army of X marches on T (42 men): before the walls
  in about 3 min"), and the realm's army marching to break a siege; players near the target or near any village of the
  army's realm see it.
* The capital's royal guard is for later.

## 10. The realm's council (1.6)

A realm with a national army is run by one council, at its capital. Its members no longer besiege on their own. It sits
every quarter of a day (about five minutes; 1.7: it was every tenth). Inspired by the kingdom AIs of strategy games (a marshal gathering a kingdom's
lords, a campaign AI weighing fronts), kept to what the realm can see.

* **It sees.** Each member: the men it pledges now and their strength, whether a host is announced, mustering or marching
  on it (threatened) or at its walls (besieged), and whether it can lead an army (its own host is home). Each enemy (every
  village at war with a member): its strength at home (the siege defence: garrison, Millénaire fighters, engines, walls);
  whether it rebelled against the realm or broke away; whether its own army is away besieging; whether the realm hates it;
  whether it is of the capital's people under another lord; whether it is besieged already. The enemies' armies: their
  realms' pledges (half the free garrison of a village without a national army).
* **Its stance**, by the balance of arms: on the attack (1.5 to 1 or better), holding its ground (0.8 to 1.5), on the
  defensive (under 0.8), at peace. It holds back 20%, 35% or 60% of every member's pledge to defend; a threatened or
  besieged member keeps all of its men home (its defence is a goal).
* **Its goals**, worthiest first for what they cost (weaker, nearer, worthier): take back a village that was its own;
  strike an enemy whose army is away (its home is thin); annex its own people under a foreign lord; punish a village it
  hates; subdue the rest. Each goal names what it needs (at least the target's defence, and at least 10 men; a village's own AI goes at 0.9).
* **Its armies.** It divides what it does not hold back into up to three field armies, each raised from the members nearest
  its target and led by the one with the most men whose host is home ("the army of Itanos"; "the army of the realm of X"
  when the capital leads). Each is a siege with the usual build-up (it gathers two days, arrives at dawn on the third),
  every member sending its pledge less the reserve as stand-ins in the host. One new army every half day at most (1.7). A goal it cannot
  fill yet stays on its list, "waiting for men".
* **It adapts.** On the defensive with a village threatened, it calls home the armies not yet at the walls, and sends none.
  The enemy of its enemy: it courts each village that fights one of its enemies (its relation with the capital rises 5 a
  day, up to 80; 1.7: at most two villages at a time, the nearest within reach of its capital, once a day), so a military
  alliance follows by the treaty rule.
* **At peace** (`realmAmbition`), after two days of peace (1.7), with an army to spare, it covets a neighbour (within 1,500 blocks) it has a claim on: one
  that was its own, one it hates, or one of its capital's people under another lord, that four fifths of its army could take. Two days later (1.7), if it still covets it, it demands its fealty (§12), and if refused declares war (and the realm's unity does the rest).
* **What you see.** The Realm tab, for a village of the realm: "The council of X: on the attack (our army against our
  enemies': 2.0 to 1)", its armies and where they are, its goals and their state (an army is on it, ready, waiting for men
  with its strength of what it needs, its men stay home), the men held back, the villages it courts, the one it covets
  ("war in about 12 min"), and its last decisions ("Day 12: the council sends the army of Itanos (about 34 men from Itanos,
  Saint-André) to annex Araden (Annex)"). The chronicle of the capital tells the same. `/hywmill war council` prints it;
  `war admin council <capital>` makes it sit now.
* `politics.realmCouncil` (default true): false gives each village its own siege decisions again.
* **1.7: slower and more focused.** Only its front: an enemy village farther than 2,000 blocks from all of its villages is
  no goal of its own (unless its army marches on the realm); and it pursues three goals at a time, the worthiest (its own
  villages' defence always). The Realm tab counts the others ("4 other enemies too far or not worth an army now"). Its
  officials lean its judgement (§11), its scouts read its enemies' plans, and its allies' contingents and its own siege
  engines count in what it can take (§12).

## 11. In a realm's service (1.7)

A player may serve one realm at a time (`politics.realmService`), from the Politics screen (his own village selected) or
`/hywmill service volunteer|mercenary|knight` where he stands; `/hywmill service leave|status`.

* **Volunteer** (anyone not unwelcome or outlawed): called to the realm's fights (a call on his HUD), never bound; paid a
  third of the reward only when he answers a call at its rally point.
* **Mercenary** (anyone not unwelcome or outlawed; the realm must be at war): a 3-day contract, renewed by signing again;
  each dawn 4 argent and half an argent a village of the realm while it is at war; 1 denier or for each order obeyed, 12
  argent for fighting elsewhere at the same fight. The first time with a realm, a signing purse (1 or) and three of its men
  (two spearmen and an archer, his to keep), once only: never again from that realm. Leaving before the contract ends bars
  him from it for 7 days. The realm puts him on campaign against the enemy of each order.
* **Knight** (a patron or one sworn to its capital; the culture's title: Knight, Archon, Samurai, Sipahi, Sardar, Nacom,
  Champion): part of the realm: a stipend each dawn (6 argent and one a village, in peace too), orders from its ruler (the
  King, the Basileus, the Daimyo, the Sultan, the Raja, the Ajaw, the Chief), a voice in its council.
* **Orders** appear on the HUD at the left of the screen, not in chat (a short notice above the hotbar when new): when the
  realm's army sets out, "muster on the E side of X (x, z); we attack from there at first light", with the time to dawn
  and the distance and bearing to the rally point; when one of its villages is besieged, "hold the W side of Y, where the
  enemy will land"; when its army goes to meet a host on the road (§13), "meet the host of Z on the road at (x, z)".
  Answered when the first day's fight ends: at the rally point (within 40 blocks) while it was fought, obeyed (paid, and
  its officials are pleased); at the fight elsewhere (within the village radius + 96), present (paid less, no reproach);
  absent: a knight's order ignored.
* **The officials.** Each realm has four, named in its capital's tongue: the Marshal, the Chancellor, the Steward, the
  Spymaster, each hawkish, peaceable, greedy, honourable or cautious, and each with an opinion of every player in its
  service (an order obeyed +10, the Marshal +15; ignored -10, the Marshal -15). They lean the council: a hawkish Marshal
  sends armies with 10% less to spare, a cautious one asks 15% more and holds 10% more men home; a peaceable Chancellor
  holds more back and covets only with much more to spare, a hawkish or greedy one with less.
* **Counsel.** A knight may counsel war on a village (at war: the council presses the war on it, its worth x1.75 for a day;
  at peace: a declaration of war) or peace with one (the enemy weighs it by the balance of arms); the officials vote, each by
  his temper and his opinion of the knight, a majority carrying it (the Marshal breaks a tie on war, the Chancellor on
  peace). Once each half day.
* **Bribes.** 1 or to an official: a greedy one's opinion +30, others +12; an honourable one refuses it (-15).
* **Banishment.** A knight who has ignored three orders, while the officials think little of him (average under 25), or
  whom they despise (-50 or less), or who becomes unwelcome or an outlaw there, is banished: his service ends and the realm
  will not have him back for 14 days. The Realm tab lists the officials, what they think of the viewer, and who serves.

## 12. Realms answering each other (1.7)

`politics.realmDiplomacy` (default true).

* **Scouts.** A council sees which of its villages an enemy council is ready to march on (a goal with an army raised for
  it): that village keeps its men home, as if threatened ("Our scouts: the council of X is ready to march on Y").
* **Allies and engines count.** The contingent its military allies would send (about a quarter of their free garrison, if
  within reach of the target) counts toward an army; its own war engines cut the target's walls in its reckoning, as they
  do in the siege. The realm's allies march with any of its armies, whichever member leads it.
* **Demands.** Before declaring war for a claim, the council demands fealty: a village that rules and answers to no one
  gives in when the realm's army is well over its defence (no chance under 1.2 times; up to 70%); then it is the realm's
  vassal and there is no war.
* **Buying peace.** A realm on the defensive with its army under 0.6 of its enemies' offers each enemy realm 1 or a day per
  village of its own for three days, once a day each; a council on the attack with an army ready seldom takes it (25%),
  others often (60%). Taken: peace, and the tribute is paid.
* **Leagues.** A realm of four villages or more alarms its neighbours by its size and its strength against theirs; an
  alarmed neighbour (not its ally or friend) leagues against it: the league's members sign defensive pacts with each other
  and like it less each day; when together they are 1.1 times its match, they declare war on it.
* **Time heals.** Each day, between two villages within reach at peace with each other, a grudge (a relation below zero,
  short of open conflict) fades by 2: fewer quarrels turn into wars.

## 13. Field battles (1.7)

When an enemy host musters or marches on one of a realm's villages, the council weighs meeting it on the road: its field
army (every member's pledge, less the reserve, but the threatened village's own) must be at least the host's match, and
there must be time to meet it at least a minute before the walls, halfway along the march still to come. Then the army
marches (a boss bar: "The army of the realm of X marches to meet the host of Y on the road (x, z): battle in about N
min"), its knights are ordered to the meeting point, and when the armies meet the battle is decided by strength, squared,
as an off-screen siege battle: each player in the realm's service standing at the meeting point adds 12 to its strength.
The loser loses half of its men, the winner as many as a third of the smaller army (a few beat by many cost little): a broken host turns for home (the war goes on); a beaten army leaves
the host to march on. The chronicle and the council's log tell it.

## 14. The royal guard (1.7)

The capital of a realm with a national army keeps a royal guard. When it makes its final stand in a siege (siege-design
§final stand), its ruler (named: "King of X") comes out of his hall with six of his guard, in heavy decorated armour (the
culture's household kit) and the ruler in his regalia (Cultures Armory 1.7: the crowned bascinet and surcoat of the King,
the stemma and loros of the Basileus, the daimyo's kabuto and o-yoroi under a jinbaori, the sultan's turbaned helmet and
gilt jawshan, the raja's crowned khud and regalia, the ajaw's quetzal headdress and jade, the chief's crowned hood and
parka), a stronghold's equipment one level better, with Strength I. They fight beside the risen wounded (off-screen they
add to the stand) and go home with the siege; if the ruler falls, all are told.

