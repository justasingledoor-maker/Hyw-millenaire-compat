# HywMill M5: Player Politics (Design Audit and Proposal)

Status: **design for review. Nothing here is implemented.** M3 and M4 stay frozen. M5 builds on
the systems already in place:
* Millénaire's reputation, relations and diplomacy;
* the M2 defense doctrine;
* M3 garrisons;
* M4 duties and deployments;
* HYW relations.

Every Millénaire fact below was read from the pinned 9.0.2 jar with `javap`, including the bytecode
of the relevant methods. Items marked **(spike)** still need confirming on a dedicated server in
M5-0, before any M5 code is written.

---

## 1. What Millénaire 9.0.2 already provides

### 1.1 Player reputation (per village and per culture)

| Mechanism | Detail |
|---|---|
| Village reputation | `VillageReputation`: player UUID → int, stored per village. Public: `Village.getReputation()`, `addReputation(UUID,int)`, `adjustReputation(ServerLevel,UUID,int)` |
| Culture reputation | `PlayerCultureReputation` (SavedData): player → culture → int, clamped to −640..4096. `adjustReputation` adds the full delta to the village and about **delta/10** to the culture |
| Combined reputation | `Village.getCombinedReputation(level, player)` = village reputation + culture reputation. Every threshold below uses this value |
| Constants (`ReputationConstants`) | `BOYCOTT_THRESHOLD` / `MIN_REPUTATION_FOR_ACCESS` = −1024; `HIRE_REPUTATION_THRESHOLD` = 4096; `FRIEND_OF_THE_VILLAGE` = 8192; `ONE_OF_US` = 32768; `REPUTATION_DONATION_MULTIPLIER` = 4 |
| Culture labels (data) | e.g. Norman `culture_reputation.json`: scourge_of_god −640, dreadful −128, bad −1, neutral 0, decent 256, good 512, excellent 1024, stellar 2048 |
| Sources of change | Trade (`TradeMenu`: buying and selling); donations (donation mode, ×4); quests (`QuestInstance`: rewards and penalties); **attacking a villager** (`VillagerCombat`: about −10 per point of damage); the admin `ReputationCommand` |
| What reputation gates | **Below −1024 villagers refuse to interact or trade** (the boycott); hiring needs ≥ 4096; culture-control purchase, crop and hunting learning, building purchases |

### 1.2 Village-to-village relations and diplomacy

| Mechanism | Detail |
|---|---|
| Relations | `Village.getRelation/setRelation/adjustRelation/adjustRelationSymmetric` (public), −100..100, with bands `VillageRelations` EXCELLENT 90 … NEUTRAL 0 … BAD −30, VERY_BAD −50, ATROCIOUS −70, **OPEN_CONFLICT −90** |
| Nightly drift | `VillageDiplomacyHelper.performNightlyDiplomacyDrift`. Each relation has a **10 % chance per night** to move. The direction is random and weighted by band (hostile relations tend to stay hostile, good ones good). Nearby players are notified ("improving/worsening"). Player-controlled villages and lone buildings are skipped |
| **Player diplomacy (already remote)** | `DiplomacyActionPayload` (GUI) calls `DiplomacyHelper.performDiplomacy(level, player, village, targetId, improve)`: |
| | • requires **combined reputation > 0** with the village and a **diplomacy point** for it; |
| | • change = ±10 × f(reputation) × a **random 80–120 %**, where f is logarithmic in reputation, capped at 32768; |
| | • applied **symmetrically**, consuming one point; |
| | • rate-limited, but with **no distance check** |
| Diplomacy points | `PlayerCultureReputation`: at most **5 per player per village**; `getDiplomacyPoints`, `consumeDiplomacyPoint`, `regenerateDiplomacyPoints` (nightly, from `VillageDiplomacyHelper.regenerateDiplomacyPointsForPlayers`) |
| Quests | `RelationChange` records in quest steps change village relations |

### 1.3 Relations → war

| Mechanism | Detail |
|---|---|
| Raids | `RaidManager.selectRaidTarget` considers villages at ≤ **OPEN_CONFLICT (−90)** (plus strength viability and bandit rules). `shouldAbortDueToImprovedRelations` **cancels a planned raid if relations improve**. Raid history: `getRaidsPerformed/Suffered` |
| Player-controlled villages | Controllers get a military panel and raid button (`ControlledMilitaryService`, `RaidButtonState`) |

### 1.4 History and chronicle

| Mechanism | Detail |
|---|---|
| Chronicle | `VillageEvent` (typed: FOUNDED, BUILDING_*, BIRTH, DEATH, MIGRATION, MERCHANT_ARRIVED …). There are no military or diplomatic types |
| History | `VillageHistoryEntry(tick, message)`, written with the public **`Village.recordEvent(ServerLevel, String)`**. HywMill can write political events there **(spike: how the text renders in the village book)** |

### 1.5 Combat towards players

| Mechanism | Detail |
|---|---|
| Guards | `EngageTargetGoal` fights **only the villager's current attack target**. That target is set by `CombatHelper.callForHelp` when a villager is hit. `isPlayerTargetable` only excludes creative and spectator players |
| Consequence | Reputation **never** makes a player an enemy. A player at −30 000 who stops swinging is ignored by guards (but boycotted by traders) |

### 1.6 Services

| Mechanism | Detail |
|---|---|
| Hiring | Hiring individual Millénaire villagers (`HireAction`, one day, reputation ≥ 4096, paid) |
| Trade | `TradeMenu`, data-driven `TradeGood` and `ShopProfile` |
| Chest locking | Chest locking for controlled villages |

### 1.7 HywMill and HYW (existing)

| Mechanism | Detail |
|---|---|
| M1 incident ledger | Already records **players damaging residents** (attacker, victim, damage, tick) |
| M2 doctrine | Already contains `assistPlayers` / `assistMinReputation` / `assistProvokingPlayer` (Millénaire reputation decides whether the village helps a player who is being attacked) |
| M2 threats | Threats are HYW units only (`HYW_ENEMY`, `ATTACKING_RESIDENT`, `RECENT_ATTACKER`, `ATTACKING_ALLY_PLAYER`) |
| HYW relations | `RelationSystem` has HOSTILE / NEUTRAL / FRIENDLY / CONTROL between identities (village faction UUID ↔ player UUID) |
| M1.1 escalation guard | Reverts permanent HOSTILE through the `DiplomacyPolicy` SPI. It ships `ALWAYS_REVERT` and was designed to be replaced by a real policy |

---

## 2. Gaps, and what M5 adds

| Area | Millénaire already does | Missing (M5 adds) |
|---|---|---|
| Reputation value | ✔ village + culture, well tuned, data labels | Nothing. **M5 does not add a reputation meter** |
| Consequences of bad reputation | Boycott (no talk or trade) | **Political status**: warnings, unwelcome, **outlaw** (the M2 threat and the HYW HOSTILE relation), pardon |
| What counts as a grievance | Reputation loss for hitting villagers | Grievances for **killing** residents or garrison soldiers, attacking the garrison, and breaking agreements (escort abuse). These come from the incident ledger and M3 death events |
| Diplomacy | Flat, reputation-scaled, random, symmetric, point-limited, remote | **Context** (recent raids, culture, distance, power, previous attempts), **kinds** of proposal (reconcile, truce, sow discord), **delayed outcomes** (envoys travel) and **backfire** |
| Truce | Raid abort when relations improve (but drift can undo it) | A **dated truce** that holds the relation above open conflict until it expires |
| Benefits of high standing | Hiring, trade, crop learning | **Intelligence**, **military requests** (escort, detachment), **armoury access** **(spike)**, **chronicle recognition** |
| Political capital | Diplomacy points (5, for diplomacy only) | **Favor**: earned by **military and political service** only, spent on requests |
| Faction level | Culture reputation (1/10 spill-over, persistent) | **"Word travels"**: outlaw status and honours are **known** to friendly villages of the same culture nearby (derived, not stored) |

---

## 3. Guiding model

A village is an **autonomous community**. The player is an outsider it knows by reputation (what
Millénaire tracks), by deeds (grievances and favor, which HywMill tracks) and by rumour (word
travels).

The player can be:
* a **stranger**;
* an **unwelcome** trouble-maker;
* an **outlaw** under the village's ban;
* a **trusted friend**;
* a **patron**, a benefactor whose requests the village weighs seriously;
* a **sworn friend**, "one of us".

These are **medieval social categories, not a meter**. The status is **derived** from Millénaire's
reputation plus HywMill's grievance and favor records. It has **hysteresis**: harder to leave a
status than to enter it. Changes are logged in the village's own history.

---

## 4. Political status (per player per village)

| Status | Entered when (defaults; all data-driven) | Consequences |
|---|---|---|
| **Outlaw** (the village's ban) | Grievance ≥ `outlaw` (e.g. a killing, or repeated assaults within the memory window) **and** combined reputation ≤ −1024 (already boycotted). Or combined reputation ≤ −4096 regardless | • An **M2 threat** inside the defense radius (new additive reason `OUTLAWED_PLAYER`), so the garrison and Millénaire defenders respond under the unchanged doctrine and coordinator. • **HYW relation** village faction → player = HOSTILE (allowed by the new `DiplomacyPolicy`), so garrison units engage on sight. • Chronicle entry. • Neighbouring friendly same-culture villages treat the player as **unwelcome** (word travels) |
| **Unwelcome** | Combined reputation ≤ −1024 (Millénaire's own boycott), or grievance ≥ `warning` | • Millénaire's boycott already applies. • HywMill: a message at the village edge; **sentries and patrols watch** (M4 duties; no attack); requests, intelligence and diplomacy are refused. • Any new offence **inside** the village escalates straight to outlaw |
| **Stranger** | Default | Nothing extra |
| **Trusted** | Combined reputation ≥ 4096 (Millénaire's hire threshold) and no open grievance | • **Intelligence**, coarse (see §8). • May ask for an **escort** (small). • May propose **reconciliation** diplomacy |
| **Patron** | Combined reputation ≥ 8192 (`FRIEND_OF_THE_VILLAGE`), favor ≥ `patron`, no grievance in the memory window | • Detailed intelligence. • Larger escorts and **detachments**. • **Truce** proposals. • Armoury access **(spike)**. • A chronicle honour |
| **Sworn** ("one of us") | Combined reputation ≥ 32768 (`ONE_OF_US`), plus long service (favor earned in total) | • The village may **volunteer** help when the player is attacked nearby (M2 `assistPlayers`, already there). • May ask for a small **expedition**. • May propose **sowing discord** against the village's enemies |

**Hysteresis and time.**
* Grievances **decay**: a half-life of a few in-game weeks, and faster while the player keeps
  their distance.
* Outlawry **persists** until *both* the grievance has decayed below `pardon` *and* reputation is
  above −1024 again. Reputation recovers through Millénaire's own **donations** (×4), which work
  like **weregild**, and through quests.
* An optional **formal pardon** request (paid in goods through donation) cuts the waiting time.
* Leaving Trusted or Patron is slower than losing it on an offence: any serious grievance drops the
  player to Stranger at once.

**Player-controlled villages.** The controller is unaffected: controller = permissions (M2/M3
semantics, unchanged).

---

## 5. Favor (political capital)

Millénaire's **diplomacy points** stay what they are, the currency of diplomacy, and M5 **reuses**
them. Military requests need a different, earned capital. **Favor** is a small per player, per
village integer (default cap 100). It is earned **only by observable service**:

| Deed (observed through existing HywMill hooks) | Favor |
|---|---|
| Damaging or killing an M2 threat of the village during an alert (incident ledger: the attacker is the player, the victim is a current threat) | + per kill, capped per alert |
| Being present while an alert is repelled (within the radius, while ENGAGED) | small |
| Successful diplomacy the village asked for (§7) | + |
| Escort or detachment returned with no losses caused by the player | small |
| Long good standing (monthly, at reputation ≥ trusted, no grievance) | + 1 |

Favor is spent on requests (§9). Soldiers lost on the player's errand **cost** favor ("you got our
sons killed"). Favor never turns into reputation, and reputation never into favor: goodwill and
obligation are different things. There is no passive regeneration beyond the monthly trickle.

---

## 6. Faction and culture level

* **No new culture meter.** Millénaire's `PlayerCultureReputation` already aggregates a tenth of
  each village change per culture, and HywMill uses the combined value.
* **Word travels (derived, not stored).** When status is computed for village V, HywMill also
  looks at villages of **the same culture** within a data radius with **relation ≥ GOOD (50)**
  with V:
  * an **outlaw** there makes the player **unwelcome** in V;
  * a **sworn** friend there gives the player a small bonus to V's willingness.

  Enemies of V (≤ BAD) do the opposite: being outlawed by V's enemy is a mild recommendation.
* Distinct villages keep distinct opinions. A player can be a patron of one Norman village and
  unwelcome in another.

---

## 7. Diplomacy from a distance (built on Millénaire's)

### 7.1 Two layers

1. **Millénaire's own action stays untouched**: its GUI, points and formula.
2. **HywMill envoy missions** are richer, contextual proposals. They **consume a Millénaire
   diplomacy point** of the sponsoring village, and they are applied **through Millénaire's API**
   (`adjustRelationSymmetric`, `setRelation`), so Millénaire stays the only relation model.

### 7.2 Proposal kinds

| Kind | Needs | Effect on success | Backfire on bad failure |
|---|---|---|---|
| **Reconcile** (A ↔ B) | Trusted with A, known to B (discovered, reputation ≥ 0) | Relation +Δ (larger when it is very bad and recently calm) | Small relation drop; −reputation with B |
| **Truce** (A ↔ B, at ≤ OPEN_CONFLICT or after a recent raid) | Patron with A, reputation ≥ decent with B | Relation raised above −90, so **Millénaire aborts planned raids**. A **dated truce** is recorded; during it HywMill **holds the floor** at −85 nightly (`setRelation`) and no HywMill raid contingent joins raids between them | A raid is triggered sooner (the relation drops) |
| **Encourage** (improve an already fair relation) | Trusted with A | +Δ, small | None |
| **Sow discord** (A ↔ C, where C is A's rival) | Sworn with A, **not** trusted by C | −Δ | **Exposed**: a grievance with C, and a relation drop between A and the player's other patrons |

### 7.3 Outcome

A logistic success chance from contextual terms, all data-driven weights:
* the player's standing with A (and with B);
* A's view of B (current relation and its trend);
* **recent conflict** (raids between them in the last N days, from Millénaire's raid history,
  and killings);
* **same culture** (+) or different;
* **distance** (−, far villages care less);
* **relative strength** (Millénaire `getVillageDefendingStrength` / raiding strength; the weaker
  village is keener on peace);
* **previous attempts** (diminishing returns; the same pair has a cooldown);
* **plausibility**: a proposal that makes no sense (reconciling friends) gets a low chance and a
  small reward.

The magnitude Δ follows Millénaire's own scale (about 10 × a reputation factor), with random ±20 %.

### 7.4 Envoys travel

* The result is **not instant**. An envoy leaves, and the outcome resolves after a delay
  proportional to the distance (for example, one in-game hour per 200 blocks, at least one).
* Resolution runs on the sponsoring village's existing staggered slot. It works while the villages
  are unloaded, because it is pure data.
* Results are announced to the player (a message when online, otherwise on next login) and written
  to **both** villages' histories.

### 7.5 Interface

* **Commands**: `/hywmill diplomacy propose <kind> <from> <to>`, `status`, `cancel`.
* A Millénaire GUI button would need Millénaire changes, so it is deferred.
* Proposals are allowed from anywhere, because envoys are letters and messengers, but only for
  villages the player has **discovered** (Millénaire's `hasDiscoveredVillage`).

---

## 8. Benefits of high standing

| Benefit | Trusted | Patron | Sworn | Built on |
|---|---|---|---|---|
| **Intelligence** (`/hywmill village intel`, remote for discovered villages) | Coarse: alert state ("at peace", "troubled", "at war"), garrison size in words, relations with neighbours as bands | Exact: garrison by class, duties, known threats, raids planned or suffered | + the village's military intentions (its current raid target) | M2 alert, M3 roster, M4 duties, Millénaire relations and raid state (all read-only) |
| **Escort** (units follow the player for up to a day) | ≤ 2 units, within the village's lands | ≤ 4 units, may leave the lands | ≤ 6 units | M3 units, M4 temporary duty (see §10) |
| **Detachment** (units hold a point the player names for N days) | — | ≤ 4 units, within a data radius | ≤ 8 units | M4 duty machinery, fixed-spot movement |
| **Volunteer defense** (the village helps when the player is attacked in its lands) | Existing M2 `assistPlayers` | same | Also in nearby lands | M2 doctrine (unchanged) |
| **Armoury** (buy culture equipment; M4 profile items or HYW recruit scrolls) **(spike)** | — | ✔ | ✔ | Millénaire trade goods data, if extensible by datapack |
| **Honours** | Chronicle entry "trusted friend of …" | "patron of …" | "one of us" | `Village.recordEvent` |

**The village keeps agency.** Every request is **evaluated**, not granted:
* It is refused when the village is not CALM.
* It is refused if a raid of its own is being prepared.
* Units offered = what the M4 raid-style planner can spare under the village's `minHome` rule
  (reusing `RaidPlanner` semantics: never the best sentry pair, never below the home share).
* Willingness falls with recent casualties on the player's errands and rises with favor.
* The village may **offer fewer** units than asked, and the reply says why ("the harvest is in,
  we can spare two").

Favor is **paid only on acceptance**. Units are **never created**; losses are permanent (M3).

---

## 9. Consequences for hostile players

| Stage | Trigger | Effect |
|---|---|---|
| Warning | First grievance (e.g. hitting a villager) | A message; nothing else (Millénaire already applies −reputation) |
| Unwelcome | §4 | Boycott (Millénaire); sentries watch; no services |
| Outlaw | §4 | M2 threat (`OUTLAWED_PLAYER`) inside the defense radius; HYW HOSTILE relation; Millénaire defenders engage through the existing M2 bridge; word travels |
| Pardon | Grievance decayed **and** reputation > −1024 (donations, quests), or a paid pardon | Threat and HYW HOSTILE cleared; a chronicle entry "pardoned" |

**Proportionality.**
* An unwelcome player who keeps out is left alone.
* The M2 doctrine still decides commitment and reserve.
* `proactive=false` is respected for everything **except** outlawed players, whose ban is the
  village's own declared decision.
* No pursuit beyond the defense radius (deferred: bounty hunters, §12).

---

## 10. Integration with M2, M3 and M4

| System | M5 use | Change needed |
|---|---|---|
| M2 `ThreatTracker` | Players in the village's defense radius with status outlaw become threats | **Additive** reason `OUTLAWED_PLAYER`. Players come from `level.players()` (O(online players)), not an entity scan |
| M2 `DefenseCoordinator` and doctrine | Unchanged. Commitment, reserve, shelter and assist all apply | None |
| M1.1 escalation guard | Must **not** revert the HYW HOSTILE M5 sets for outlaws | A new `DiplomacyPolicy` implementation (`PoliticalPolicy`) that allows HOSTILE exactly for (village faction, outlawed player). **This is the SPI M1.1 left for this purpose; no M1–M4 behaviour changes otherwise** |
| M3 roster | Units lent to the player stay the village's units: roster-first, same UUIDs, deaths permanent | None |
| M4 duties | Escort and detachment are **temporary duties** like RAID: DEPLOYED + ESCORT / DETACHED, excluded from home defense like RAID, returning via the M2 path | **Additive** `Duty` values (persisted as strings; M4 saves load unchanged) and small `EscortService` / `DetachmentService` classes modelled on `RaidService` |
| M4 `RaidService` | No HywMill raid contingent joins a raid against a village under a truce; the raid planner's "can spare" logic is reused for requests | A truce check (additive) |

**Possible defect to confirm in M5-0.** M2 `ATTACKING_ALLY_PLAYER` and `assistMinReputation`
currently read the **combined** reputation. Correct, but it should be verified against
Millénaire's boycott semantics.

---

## 11. Architecture, persistence and performance

### 11.1 Packages (foreign imports stay in `integration.*`)

**`politics` (pure).**
* `Standing`: status, hysteresis rules.
* `Grievances`: decaying record.
* `Favor`.
* `DiplomacyOdds`: logistic model, pure and seeded.
* `EnvoyMission`, `Truce`, `RequestEvaluator` (uses `RaidPlanner`).
* `PoliticsTables`: data.

**`politics.service`.**
* `PoliticsService`: per-village status refresh on the existing staggered slot; event hooks.
* `EnvoyService`: resolves due missions.
* `EscortService`, `DetachmentService` (M4 temporary duties).
* `PoliticalPolicy` (`DiplomacyPolicy`).

**`SettlementSource` additions** (Millénaire adapter), read-only unless noted:
* `reputation(level, village, player)` (combined);
* `cultureOf`;
* `relation(a, b)` and `adjustRelation(a, b, Δ)` **(write)**;
* `setRelationFloor` **(write)**;
* `diplomacyPoints` / `consumeDiplomacyPoint` **(write)**;
* `adjustReputation(village, player, Δ)` **(write, backfire only)**;
* `discovered(player, village)`;
* `raidHistory`;
* `strength`;
* `recordHistory(village, text)` **(write)**.

**`CombatFactionService` additions** (HYW adapter): `setPlayerHostile(villageFaction, player, on)`.

### 11.2 Persistence

In the existing **`GarrisonLedger`**, one ledger format bump (4 → 5 with optional keys; format-4
saves load unchanged):
* `VillageRecord.politics`: player UUID → { grievance (value, last tick), status (sticky),
  statusSince, favor, favorEarnedTotal, lastRequest, casualtiesOnErrands, lastProposal per target };
* **only non-default entries** are stored, and default entries are pruned;
* `VillageRecord.truces`: target village → until-tick;
* a ledger-level list of **pending envoy missions** (small, bounded per player).

There is no new SavedData. Nothing is stored in Millénaire's data that Millénaire doesn't already
store.

### 11.3 Cost

* **Event-driven.** Grievances come from the existing incident and death hooks.
* **Status refresh.** Once per village per ledger interval, for **online players only** (bounded by
  the player count). Reputation reads are field reads.
* **Threats.** Outlaw threats are checked during the existing M2 scan; a player list with a
  distance filter.
* **Envoys.** Resolved on the sponsoring village's slot.
* **No per-tick world work and no static mutable state.** Everything lives in `HywMillRuntime`
  services or the ledger.

### 11.4 Data (`data/hywmill/hywmill_politics/*.json`, culture patches like M3/M4)

Holds:
* status thresholds and hysteresis;
* grievance weights and half-life;
* favor sources, caps and costs;
* request limits per status;
* diplomacy weights, Δ, travel speed and cooldowns;
* the word-travels radius and bands;
* the truce length.

Cultures differ. Examples: Seljuk slower to forgive, Japanese stricter honour codes, Inuit more
forgiving; names and values to be decided in M5-1.

---

## 12. Deferred to M6+

* **War.** Player-declared wars and player-led raids against villages; sieges; relief forces
  marching to a besieged ally; coalitions and multi-village alliances; vassalage and tribute.
* **Pursuit.** Bounty hunters and pursuit of outlaws outside a village's lands; prisoners and
  ransom.
* **Social and economic politics.** Marriage and kinship; religion; trade embargoes; mercenary
  contracts with pay.
* **Interface.** Custom GUIs and Millénaire GUI buttons (they need Millénaire changes); for now
  commands and chat.
* **Culture-wide and multiplayer politics.** A culture-wide "kingdom" standing beyond Millénaire's
  culture reputation; player-to-player politics (one player as another's envoy; shared favor in
  multiplayer teams).
* **FRIENDLY HYW relation for patrons**, whose semantics are unverified (spike first).
* **Wars without villages.** Player-declared wars on villages without an allied village (M5 reaches hostility through acts and outlawry only); multi-front campaigns; HYW team-wide campaigns; formations and orders for mixed allied armies.

---

## 13. M5-0 spikes (dedicated server, before any M5 code)

1. **Relation writes.** `setRelation` / `adjustRelationSymmetric` from HywMill:
   * whether they persist;
   * whether they reach the client and the travel book;
   * whether a raise above −90 **aborts a planned raid**;
   * whether a nightly floor survives drift.
2. **Diplomacy points.** Whether `consumeDiplomacyPoint` and `performDiplomacy` can be called
   directly; the regeneration rules (per night? reputation-gated?).
3. **History.** `recordEvent` text: where it appears and whether it is localized; the size limit.
4. **Outlaw.** HYW `RelationSystem` HOSTILE for a player UUID:
   * whether garrison units engage on sight;
   * whether the escalation guard with `PoliticalPolicy` keeps it;
   * whether clearing it restores neutrality;
   * no side effects on other players or villages.
5. **M2 with a player threat.**
   * Coordinator assignment, the Millénaire defender bridge, shelter.
   * No friendly fire on other players.
   * Behaviour in creative mode (excluded).
6. **Escort.** An HYW unit's home following a moving player with hop limits (M4 movement) across
   loaded chunks; return and unload behaviour.
7. **Armoury.** Whether Millénaire trade goods or shop profiles can be extended by datapack
   (culture shops selling M4 profile items or HYW scrolls) without code changes.
8. **Donations as weregild.** The donation-mode reputation gain rate, to calibrate pardon times.

**Stop condition, as for M3/M4.** Stop and report if any spike needs a mixin or a change to
Millénaire or HYW.

---

## 14. Proposed M5 steps (after approval)

| Step | Content |
|---|---|
| M5-0 | The spikes above; report |
| M5-1 | Politics data, `Standing` / `Grievances` / `Favor` (pure), ledger format 5, JUnit |
| M5-2 | Status service, chronicle entries, word travels, `/hywmill politics` status and intel commands |
| M5-3 | Outlaw: `OUTLAWED_PLAYER` threat, `PoliticalPolicy` with HYW HOSTILE, pardon |
| M5-4 | Envoy missions and truces (Millénaire relation API, delayed resolution, backfire) |
| M5-5 | Requests: escort and detachment (M4 temporary duties), evaluator, favor costs, casualties |
| M5-5b | Rules of engagement (§16): wars, campaigns, relation projector and reconciliation, `ENEMY_COMBATANT`, combatant-only engagement |
| M5-6 | Armoury (if spike 7 allows) and honours |
| M5-7 | Harness suite G5, M4/M3/M2 regressions, performance, report |

---

## 15. Open questions for review

1. **Outlaw trigger.** Should a single killing be enough, or must reputation also be ≤ −1024?
   Proposed: both, except for a killing **inside** the village during peace, which alone is enough.
2. **Favor.** Should it also be earned from trade volume (medieval merchants did buy influence)?
   Proposed: no; trade already raises Millénaire reputation.
3. **Sow discord.** Should it exist at all in M5? It is powerful and easy to abuse. Proposed: yes,
   sworn only, with the exposure backfire.
4. **Escorts leaving the village's lands.** They cross unloaded terrain only with the player present
   (chunks are loaded by the player). Acceptable?
5. **Commands only for the interface in M5.** Acceptable?
6. **Automatic war between villages.** Should village ↔ village war projection (HOSTILE between
   faction garrisons at ≤ −90) be on by default? Proposed: yes, with a minimum duration at open
   conflict, and a server config switch.
7. **Co-belligerents as FRIENDLY in HYW.** Should co-belligerents be projected as FRIENDLY (full
   friendly-fire protection) or only kept NEUTRAL (no mutual targeting, but stray hits count)?
   Proposed: FRIENDLY, for the campaign only.
8. **Combatant-only engagement for M4 raid contingents** (a small change to frozen M4). Approve?

---

## 16. Rules of engagement and allied warfare

**Goal.** A player can take part in a legitimate war with their own HYW troops on HYW's normal
(DEFAULT) attack strategy, not INDISCRIMINATE:
* the enemy's soldiers are valid targets;
* allies and neutrals are not;
* civilians are never targets by default.

This should follow from the political state M5 establishes, through HYW's existing public relation
API, with **no mixins and no HYW changes**.

### 16.1 HYW audit (0.7.1r-fix1, bytecode of the relevant methods)

| HYW mechanism | What it does |
|---|---|
| **Relation identity** (`ServerRelationHelper.getRelationIdentity/getRelationUUID`) | Every entity resolves to an identity: an owner UUID, a known null owner (e.g. bandits), or unknown. Resolution goes through owner, summoner and rider chains, and a marker is cached on the entity (`RelationOwnerMarkedEntity`). HywMill already uses this: garrison units have **owner = village faction UUID** (M3), and Millénaire residents are **marked** with the faction identity (M1) |
| **Relations** (`RelationSystem.getRelation(a, b)` / `setRelation`) | **Directed**, identity → identity. HOSTILE / NEUTRAL / FRIENDLY / CONTROL; the default is **NEUTRAL**; an identity against itself is CONTROL. Persisted by HYW itself (`saveRelations`). Changing HOSTILE → NEUTRAL starts an **immunity window** (`hostileToNeutralSwitchTime`, `RELATION_IMMUNITY_TIME`) |
| **Teams** (`createTeam/joinTeam/getPlayerTeamUUID`, `TeamRelationData`) | Player teams (owner/admin/member) with a team UUID. It is unknown **(spike)** whether a team member's units resolve to the team UUID for relations |
| **Target selection** (`BaseCombatEntity.isValidTarget`), for DEFAULT, FREE_FIGHT and FREE_ROAM | In order: <br>1. rejects: siege weapons, blacklist, creative players, `CEASE_FIRE`; <br>2. **temporary hostility** (`TemporaryHostileTargetManager.isHostile`, about 11 s): the target is valid unless relation-protected; <br>3. **relation participants** (target is an HYW unit, **a player** or a tamed animal, with a known identity): valid **iff `isEnemyRelation`**, i.e. the relation is HOSTILE; <br>4. **monsters** (`Enemy`): valid; <br>5. anything else, **including Millénaire villagers, whatever their faction**: not a target |
| `INDISCRIMINATE` | Every living thing except the same owner and FRIENDLY/CONTROL identities, **villagers and neutrals included**. This is why players reach for it today |
| **Protection and friendly fire** | `isRelationProtected` (same owner, target blacklist); `shouldCancelFriendlyDamage` / `shouldIgnoreFriendlyCollision` (FRIENDLY/CONTROL and same owner; player friendly fire has its own config) |
| **Automatic escalation** | `BaseCombatEntity.die`: a unit killed by a **NEUTRAL** identity sets that relation to **HOSTILE** (`setRelation`), and HYW records damage (`recordDamage`). HywMill's M1.1 escalation guard reverts this today (`DiplomacyPolicy.ALWAYS_REVERT`) |

**Conclusion.** HYW already implements, in DEFAULT mode, most of the rules of engagement asked for:
* **soldiers versus soldiers by relation**;
* **no civilians** (villagers are never relation targets);
* **neutrals ignored**;
* **friendly fire suppressed for FRIENDLY identities**.

What is missing is only **who is HOSTILE or FRIENDLY to whom, and when**. That is exactly what a
diplomatic layer can supply through `setRelation`. **No mixin is needed.**

### 16.2 The model: political state first, HYW relations as its projection

HywMill keeps the political truth in its own ledger. It **projects** that truth onto HYW relations,
and the projection is recomputed and reconciled; it is never the source of truth. The three states
the review asked to keep apart:

| State | Meaning | HYW projection |
|---|---|---|
| **Friendship** (standing) | A lasting good relationship: the player is a patron or sworn friend of A, or two villages are at EXCELLENT relations | None by default (NEUTRAL already means "do not attack"). Optionally FRIENDLY for sworn friends, for friendly-fire protection **(spike)** |
| **Co-belligerence** (a *campaign*) | "We are fighting the same enemy, for now": time-bounded, tied to a specific war, can end without any friendship | **FRIENDLY between the co-belligerents' identities for the campaign's duration**, for friendly fire and collision; restored to the previous relation afterwards |
| **War** (belligerence) | An open, declared or recognized conflict between two parties | **HOSTILE in both directions** between their identities, for the war's duration |
| Retaliation | A one-off answer to a specific attack | **HYW temporary hostility** (about 11 s, per unit and target), as M2/M3 use today. **Never** a relation change |

Friendship is about **who we like**; co-belligerence is about **who is on our side in this fight**.
A player can be:
* a co-belligerent of a village that only tolerates them;
* a friend of a village that stays out of their war.

### 16.3 Who is at war with whom

* **Village ↔ village war.** It follows Millénaire's own state: relation ≤ OPEN_CONFLICT (−90),
  or a raid in progress between them.
  * It starts after the relation has been at open conflict for a minimum time (no flapping on
    nightly drift).
  * It ends at truce or peace (§7), or once the relation has been above −90 for a minimum time.
  * The projection is HOSTILE between the two **village faction UUIDs**, which covers all M3
    garrison units and M4 raid contingents automatically.
* **A player joins a war** (`/hywmill war join <A> against <B>`). This needs:
  * standing **trusted or better** with A;
  * **not trusted** by B, or at least accepting the political cost below;
  * an active A ↔ B war.

  Effects:
  * a **campaign** is recorded (A, the player and B, with an end tick);
  * projection: player ↔ B **HOSTILE** (both directions), player ↔ A **FRIENDLY** for the
    campaign;
  * **political cost with B**: a grievance, standing at most "enemy combatant" for the duration
    (see below), and a chronicle entry in both villages.
* **A player's own private war** (the player versus B, without A). That is the outlaw path of §4
  seen from B's side. A player cannot start a war on a village by declaration alone in M5; they get
  there through acts, and B's response is outlawry.
* **Leaving and ending.** `/hywmill war leave`, an expiry (for example, seven in-game days,
  renewable), peace between A and B, or the player losing standing with A.
  * Projections are removed and prior relations restored.
  * HYW's immunity window then prevents instant re-hostility.
  * B's grievance **decays** normally, so the player is not an outlaw by default after the war.

**Enemy combatant** is a new, temporary status of a player with B during a campaign. The player is
a legitimate military target of B, as with outlawry: the M2 threat reason `ENEMY_COMBATANT`, plus
the HYW HOSTILE projection. It **ends with the campaign**, without the long pardon process an outlaw
needs.

### 16.4 How the requested behaviour follows

| Requirement | How it follows (DEFAULT strategy, no INDISCRIMINATE) |
|---|---|
| **Allied forces do not attack each other** | FRIENDLY projection (co-belligerents) or NEUTRAL (the default): not relation targets; FRIENDLY also cancels friendly damage and collision |
| **Allies protected from normal hostile targeting** | Only HOSTILE identities, monsters and temporary-hostility targets are ever targets; allies are none of these |
| **Allies operate together** | M2 of A treats co-belligerent units as allies: never threats (they are not HOSTILE); assisted when attacked through the existing `ATTACKING_ALLY_PLAYER` reason, extended to "attacking a co-belligerent's unit" |
| **Enemy military forces can be attacked** | Player ↔ B HOSTILE: the player's units target B's **garrison units** (owner = B's faction) and B's co-belligerent players' units |
| **The enemy can attack back** | HOSTILE is set in both directions, so B's garrison targets the player's units and the player natively; B's M2 sees them as `HYW_ENEMY` / `ENEMY_COMBATANT` threats and deploys under its doctrine |
| **Neutral forces stay out** | Third parties keep NEUTRAL; stray damage that HYW would escalate on a kill is **reverted by the escalation guard** unless a campaign or war covers the pair |
| **Civilians are not targets** | Millénaire villagers are never relation targets in DEFAULT mode. **Combatant villagers** (M2 roles SOLDIER/LEADER, and MILITIA per doctrine) of an enemy village are made targets only through **temporary hostility**, marked by HywMill for units within the campaign's engagement area. Never by relation, so civilians are never included |

### 16.5 Interactions

| System | Interaction |
|---|---|
| **M2 village defense** | Enemy HYW units are already threats through `HYW_ENEMY` (explicit HOSTILE). Adds the additive reasons `ENEMY_COMBATANT` (players at war with the village) and the co-belligerent assist. The doctrine (commitment, reserve, `proactive`) is unchanged; `proactive=false` still keeps the garrison from chasing enemies merely present, except those declared enemies. The defense coordinator never targets FRIENDLY or co-belligerent units |
| **M3 ownership** | Unchanged. Garrison units belong to the village faction; the faction's relations apply to all of them. Controller = permissions, not identity (unchanged) |
| **M4 raid contingents** | In a war, contingents fight B's garrison natively through HOSTILE. Their resident engagement (currently the nearest resident, civilians included) should be restricted to **combatant villagers**. That is a small behavioural change to frozen M4, proposed here for approval as part of M5 |
| **Player-owned HYW units** | Identity = the player (or the player's HYW team, **spike**). The player keeps choosing strategies; DEFAULT is enough for war. INDISCRIMINATE stays available but is never needed |
| **Village-owned units** | Identity = the village faction. They never use INDISCRIMINATE; HywMill sets DEFAULT (M3) |
| **Village ↔ village wars** | HOSTILE between faction UUIDs while at war: garrisons, scouts and raiders of warring villages fight on sight wherever they meet (loaded chunks only) |
| **Player in village wars** | Through a campaign (§16.3), with standing prerequisites and political costs |
| **Temporary versus permanent** | Temporary hostility = retaliation and targeted strikes (seconds). A campaign or war projection = the duration of the conflict (days). Outlawry = until pardon. HYW's automatic escalation (killed by a neutral) is **reverted** unless one of these political states covers the pair |
| **Friendly fire** | FRIENDLY projection for co-belligerents and (optionally) sworn friends; HYW's own `shouldCancelFriendlyDamage`. Player friendly fire stays governed by HYW's own config |

### 16.6 Architecture

* **`politics.war` (pure).**
  * `War` (pair of villages, since, cause).
  * `Campaign` (player, ally, enemy, until).
  * `RoeState` (combatant categories).
  * `RelationPlan`: computes the **desired HYW relation** for every (identity, identity) pair
    HywMill manages. Pure, and unit-tested for symmetry, expiry and restoration.
* **`RelationProjector`** (service, in `HywMillRuntime`).
  * Applies the plan's differences through `CombatFactionService`, with new adapter methods
    `setRelation(a, b, type)` and `relation(a, b)`.
  * Remembers the **previous relation** it replaced, so ending a campaign restores it.
  * **Reconciles at startup and on every ledger interval**: it removes HywMill-made relations that
    no longer have a political cause, so HYW's persisted relations never drift from HywMill's
    ledger.
  * It never touches pairs it did not create.
  * An admin command clears all HywMill projections (uninstall hygiene, like M1.1
    `clear-identities`).
* **`PoliticalPolicy`** (the `DiplomacyPolicy` SPI from M1.1) consults the plan. The escalation guard
  keeps HOSTILE only where the plan wants it; everything else is reverted as today.
* **Persistence** (ledger format 5, optional keys): wars per village pair, campaigns per player, and
  the projector's "previous relation" records.
* **No static state** of HywMill's own. HYW's relation store is static by HYW's design, and HywMill
  treats it as an external system it reconciles against.
* **Cost.** Projections change only when a war or campaign starts or ends: a handful of `setRelation`
  calls. There is no per-tick work; HYW's own targeting does the fighting.

### 16.7 Spikes for M5-0 (added)

1. **Identity of player-owned units:**
   * the player's UUID, or the HYW team UUID when the player is in a team;
   * whether `setRelation` on the player's UUID governs the player's units.
2. **War projection.** Player ↔ village faction HOSTILE (both directions) under DEFAULT:
   * the player's units attack B's garrison units, and not B's villagers;
   * B's garrison attacks the player's units and the player;
   * nobody else is affected.
3. **FRIENDLY between co-belligerents:**
   * no targeting;
   * melee, arrow and area friendly-fire cancellation;
   * collision.
4. **Restoration.** After HOSTILE → NEUTRAL: the immunity window, no residual targets, and whether
   HYW's own `saveRelations` persists the change.
5. **Escalation guard.** A unit killed by a neutral third party is escalated by HYW (`die`), and the
   guard with `PoliticalPolicy` must revert it only when no war or campaign covers the pair.
6. **Combatant villagers.** Temporary hostility on SOLDIER/LEADER villagers of the enemy village;
   civilians untouched.
7. **Faction ↔ faction HOSTILE.** Two garrisons meeting in the field; M2 `HYW_ENEMY` alerts at both
   villages; no effect on the villages' residents.

**Stop condition as before.** If spike 1 or 2 shows that DEFAULT targeting cannot be driven by
relations for player-owned units without HYW changes, stop and report before designing a
workaround.
