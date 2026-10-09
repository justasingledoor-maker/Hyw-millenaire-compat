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
  * A rebel is free at once. Its relation with the old sovereign drops to −100, so a war follows.
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
  * `war admin siege <a> <t> [quick] … aim <subjugate|annex|punish|raze>`.
