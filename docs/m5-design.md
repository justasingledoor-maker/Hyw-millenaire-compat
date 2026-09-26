# HywMill M5: Player Politics (Design Audit and Proposal)

Status: **approved design direction; nothing is implemented yet.** Locked decisions are in §18
(2026-09-26): the player-facing politics GUI with a keybind, the larger garrison scale, and the
answers to every open question (§15, §18.3). M3 and M4 stay frozen except for the changes §18.3
approves, each made in its own M5 step. M5 builds on
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
| **Outlaw** (the village's ban) | **Normal rule:** a grievance ≥ `outlaw` (a serious offence such as killing a resident or garrison member) **and** combined reputation ≤ −1024. **Exception:** killing a resident or garrison member **inside the village during peacetime** makes the player an outlaw at once, whatever their reputation. Severity and context (where, whether at peace) come from the grievance record, not from a second meter (approved, §18.3) | • An **M2 threat** inside the defense radius (new additive reason `OUTLAWED_PLAYER`), so the garrison and Millénaire defenders respond under the unchanged doctrine and coordinator. • **HYW relation** village faction → player = HOSTILE (allowed by the new `DiplomacyPolicy`), so garrison units engage on sight. • Chronicle entry. • Neighbouring friendly same-culture villages treat the player as **unwelcome** (word travels) |
| **Unwelcome** | Combined reputation ≤ −1024 (Millénaire's own boycott), or grievance ≥ `warning` | • Millénaire's boycott already applies. • HywMill: a message at the village edge; **sentries and patrols watch** (M4 duties; no attack); requests, intelligence and diplomacy are refused. • A killing **inside** the village makes them an outlaw at once (the exception above, as for anyone) |
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

**Trade never earns Favor** (approved, §18.3): trade already earns Millénaire reputation, and
counting it twice would let money buy military obligation. Only the service deeds above, and any
future deed explicitly defined as service, earn Favor.

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
| **Sow discord** (A ↔ C, where C is A's rival) | **Sworn** with A, **not** trusted by C; a diplomacy point of A **and** Favor with A; limits below | −Δ | **Exposed**: a grievance with C, and a relation drop between A and the player's other patrons (standing elsewhere can suffer) |

**Sow discord limits (approved, §18.3).** It is powerful by design (Sworn is rare), so it must not
be spammable:
* a per-player cooldown (for example one attempt per in-game week), and a per-pair cooldown for the
  same A ↔ C pair shared by all players;
* at most one pending sow-discord envoy per player;
* it costs Favor as well as the diplomacy point, and the cost rises with each recent attempt;
* the exposure chance grows with repeated attempts against the same village;
* it can fail outright, and a failure still spends the point and the Favor.

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

* **Commands first** (development, admin and headless testing): `/hywmill diplomacy status`,
  `list`, `propose <reconcile|truce|encourage|sow_discord> <from> <to>`, `cancel`.
* **The player interface is a GUI (locked, §18.1)**: a Politics / Diplomacy screen reached from the
  village leader or town hall and from a configurable keybind. It is built after the backend and
  its API boundary are stable, and it contains no political logic.
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
| M5-G | Garrison population scaling (§17, locked by §18.2): spike S-G first (targets from real harness villages, reachability of the locked caps, duties, raids, M2 deployment, spawning, server scale test); then the target and levy formulas through the existing data-driven tier machinery, with a neutral-values regression, and the M4 duty and raid **data** retuned to the new sizes (approved, Q10). Independent of the politics steps |
| M5-6 | Armoury (if spike 7 allows) and honours |
| M5-UI | Politics / Diplomacy GUI (§18.1): client keybind, screen, network payloads over the `PoliticsView` API; after M5-1..M5-5 are stable |
| M5-7 | Harness suite G5, M4/M3/M2 regressions, performance, report |

---

## 15. Decisions on the open questions

All questions are answered (2026-09-26). The binding text is §18.3; this is the index.

| # | Question | Decision |
|---|---|---|
| 1 | Outlaw trigger | Serious grievance **and** reputation ≤ −1024; a killing inside the village in peacetime is enough alone. Hysteresis for leaving outlawry unchanged |
| 2 | Favor from trade | **No.** Favor comes from service only |
| 3 | Sow discord | **Yes**, Sworn only, costs Favor and a diplomacy point, cooldowns and limits, can fail, exposure backfires |
| 4 | Escorts and unloaded terrain | **Accepted.** No force-loading; escorts hold when the terrain ahead is not loaded, never teleport |
| 5 | Interface | Commands first; a GUI with a configurable keybind later (§18.1) |
| 6 | Automatic war between villages | **Yes, on by default**, after a configurable minimum duration at ≤ −90, with a server switch; truce or peace ends it |
| 7 | Co-belligerents | **FRIENDLY** both ways for the campaign only; the previous relation is restored afterwards |
| 8 | M4 raid contingents | **Approved:** engage combatant villagers only (a narrow change to frozen M4 target selection) |
| 9 | Garrison scaling | Caps 24 / 48 / 72 / 128, about 2–3× today (§18.2) |
| 10 | Duty and raid data at the larger sizes | **Approved** as part of M5-G, data only |

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
  * **On by default** (approved). Server config: `politics.autoWar` (default true) disables the
    HYW war projection entirely, and `politics.warMinConflictTicks` sets the minimum duration.
    Millénaire's relation remains the diplomatic fact; the war state is only its military
    projection.
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

---

## 17. Garrison population scaling (balance audit; M3/M4 unchanged)

> **Superseded in part by §18.2 (locked).** The caps are now WATCH 24, GUARD_POST 48, GARRISON 72,
> STRONGHOLD 128, and the scale is about 2–3× today's garrisons. Population is **not** a realism
> limit: a garrison larger than the village's civilian population is acceptable. The example caps and
> the population-ceiling framing below are kept as the original audit; §18.2 governs.

**Request.** Developed settlements should field a garrison that looks like one (roughly double or
triple the current higher tiers), while hamlets stay lightly defended. Military population should
follow the settlement's real development, not a flat per-tier army. This section audits the current
system and proposes a model. **Nothing in M3/M4 has been changed.** The example numbers below are
illustrations, not approved values.

### 17.1 What decides the garrison today (audit)

Source: `Recruitment.target`, `TierRule`, `hywmill_garrison/defaults.json`, and
`ProfileCalculator.capacity`.

```
target   = min( clamp(round(capacity × perCapacity), minTarget, maxTarget), maxUnits )
capacity = SOLDIER + MILITIA resident slots declared by the village's operational buildings,
           at their current variant and level (M2)
```

| Tier | perCapacity | minTarget | maxTarget = maxUnits (the "tier cap") | Levy/day | Pool cap | Classes |
|---|---|---|---|---|---|---|
| WATCH | 1.0 | 1 | 8 | 1.0 + 0.25 × capacity | 4 | LEVY, RANGED |
| GUARD_POST | 1.0 | 2 | 16 | 1.5 + 0.25 × capacity | 6 | + LINE |
| GARRISON | 1.0 | 3 | 32 | 2.0 + 0.25 × capacity | 10 | + GUNPOWDER (off), CAVALRY |
| STRONGHOLD | 1.0 | 4 | 64 | 3.0 + 0.25 × capacity | 16 | same |

* **The tier cap is a ceiling, not the target.** With `perCapacity = 1.0`, the target is
  simply **one HYW troop per Millénaire soldier or militia slot**, with a small floor. The cap only
  binds when a village declares more slots than the cap. Few Millénaire villages do, so in practice
  **capacity, not the tier, decides the garrison size.**
* The **tier** is decided separately (`MilitaryTier.assess`): professionals, military buildings,
  fortification and walls. It unlocks classes, equipment level, levy base and duty quotas, but
  adds almost nothing to the target itself (only `minTarget`).
* **Population, village type and non-slot infrastructure do not enter the target at all.** A large
  town with one guardhouse gets the same target as a hamlet with one guardhouse.
* **Recruitment throughput.**
  * Starting grant: 50 % of the target, free, once per village (`startingGranted` is never reset).
  * After that, paid recruits: at most one every `recruitIntervalTicks` (2400 ticks, so ≤ 10 per
    in-game day). Recruits happen only while CALM, and only while the levy covers the unit cost:
    1–4 points, averaging about 1.8 for the default composition and about 2.5 for Norman.
  * Death and wipe-out cooldowns apply.
  * Levy accrues only while the village is active (loaded); there is no offline catch-up.

### 17.2 Why a village shows "6/8 (tier cap 16)"

`/hywmill garrison` prints `live/target (tier cap maxUnits)`. That GUARD_POST village declares
**8 soldier and militia slots**, so its target is `min(clamp(8 × 1.0, 2, 16), 16) = 8`, and the 16 is
never reached. It has 6 live (non-terminal) entries: 4 from the starting grant (50 % of 8), plus
2 paid recruits so far. The rest are waiting on one of these, which the summary names as the blocker:
* levy (`POINTS`);
* the recruit interval (`INTERVAL`);
* an alert (`NOT_CALM`);
* a death cooldown.

It is working as designed. The tier cap simply has no effect on most villages.

### 17.3 Recommended model: development-driven target

Keep the structure (pure, data-driven, per-village, no global state), but compute the target from
the settlement's actual development:

```
target = min(
    maxUnits[tier],                                           // tier ceiling (raised)
    floor(supportRatio[tier] × population),                   // what the settlement can feed
    clamp(round( slots × perSlot[tier]                        // Millénaire's own military slots
               + Σ infraBonus[role] × buildings[role]         // barracks, armoury, walls...
               + levyShare[tier] × adults                     // levy from the working population
               ) × typeFactor[culture/type],
          minTarget[tier], maxTarget[tier]) )
```

| Term | Purpose | Example values (not approved) |
|---|---|---|
| `perSlot` | A Millénaire soldier slot stands for a small squad, not one man | WATCH 1.0, GUARD_POST 1.25, GARRISON 1.5, STRONGHOLD 2.0 |
| `infraBonus` | Military infrastructure supports troops beyond resident slots | BARRACKS +8, FORT_TOWNHALL +8, ARMOURY +4, TRAINING +4, GUARDHOUSE +2, WATCHTOWER +2, TOWER +1, plus fortification / 4 (capped at +16) |
| `levyShare × adults` | Every village can raise some levy; bigger ones raise more | WATCH 0.10, GUARD_POST 0.15, GARRISON 0.20, STRONGHOLD 0.25 |
| `supportRatio × population` | **Realism ceiling:** a hamlet cannot keep an army larger than its people; a fortified seat draws on its hinterland | WATCH 0.5, GUARD_POST 0.75, GARRISON 1.5, STRONGHOLD 2.5 |
| `typeFactor` | Millénaire village types and cultures: military outposts and forts above 1, farming and fishing villages below 1 | a data patch per culture and village type, as M3/M4 cultures already patch |
| `maxUnits` | Hard per-village ceiling, still data | WATCH 12, GUARD_POST 24, GARRISON 64, STRONGHOLD 128 |

**Worked examples** (the current target is shown for comparison):

| Settlement | Inputs | Current target | Proposed target |
|---|---|---|---|
| Farming hamlet, WATCH | 1 slot, 10 adults, pop 14, type 0.8 | 1 | `(1 + 0 + 1) × 0.8` → **2** (ceiling 7) |
| Village with guardhouse, GUARD_POST | 8 slots, 25 adults, pop 35, guardhouse | 8 | `10 + 2 + 3.75` → **16** (ceiling 26) |
| Market town, GARRISON | 12 slots, 40 adults, pop 55, barracks + armoury, fortification 12 | 12 | `18 + 12 + 3 + 8` → **41** (ceiling 82) |
| Norman fort, STRONGHOLD | 16 slots, 40 adults, pop 55, fort townhall + barracks + armoury + training, fortification 40 | 16 | `32 + 24 + 10 + 10` → **76** (ceiling 137) |
| Large walled city, STRONGHOLD | 30 slots, 70 adults, pop 90, same buildings, fortification 60 | 30 | `60 + 24 + 15 + 17.5` → **117** (cap 128; ceiling 225) |

So hamlets stay at 1–3, strongholds reach roughly 70–128, and the shape comes from slots,
buildings and population, not from the tier alone. **Both** the cap and the calculated target must
change: raising only the caps would change almost nothing (§17.1).

**Cavalry availability.** Today cavalry is gated by tier (GARRISON+) and culture composition only.
There is no stable building role. Proposal: an optional `STABLE` building role (role-table data) and
a cavalry share ceiling (for example ≤ 10 %, or ≤ 4 riders per stable). This needs a spike to see
whether Millénaire plans expose stables reliably; until then keep the composition weights.

### 17.4 Levy: is a larger garrison reachable?

Current rates with larger targets (STRONGHOLD, capacity 16, about 2.5 points per unit):
* Levy is 3 + 0.25 × 16 = **7 points/day**, about 2.8 units/day.
* A target of 76 grants 38 at once; the other 38 take **about 14 active in-game days**.
* Rebuilding after a wipe-out (75 % of 76 = 57 dead) takes about 20 active days. **Too slow.**

Proposal: levy scales with the target, so that time-to-fill stays about constant:

```
dailyRate = baseDaily[tier] + perCapacityDaily × capacity + perTargetDaily × target
poolCap   = max(poolCap[tier], 0.25 × target)
```

Example: with `perTargetDaily` = 0.12, the rate at a target of 76 is 3 + 4 + 9.1 = 16.1 points/day,
about 6.4 units/day.
* The remaining 38 fill in **about 6 active days**.
* A wipe-out rebuild takes about 9 days.
* The recruit interval (≤ 10/day) remains the throughput guard, and at target 128 it becomes the
  binding limit, about 7 days to fill the non-granted half.
* Levy stays active-time only (no offline catch-up), so unloaded villages do not silently build
  armies.

**Existing villages** keep `startingGranted = true`. Their larger target is filled by levy, not by a
second free grant, so an update does not spawn dozens of units at once. A one-time `top-up` admin
command could be optional.

### 17.5 Impact on M4 duties, raids, defense and performance

| Area | Impact | Needed change (data, M5 step) |
|---|---|---|
| **Duty quotas** | STRONGHOLD maxima (8 sentry pairs, 8 patrol, 4 scouts, reserve 15 %) staff about 40 units. At 128, about 85 would stand at muster on GARRISON duty. That is visually a "standing garrison", but it crowds the muster points (units are spread by a UUID hash over the muster list) | Raise maxima: sentry pairs ≤ posts (already bounded by the layout), patrol ≤ 16, possibly as two patrol groups on the same ring, scouts ≤ 6, reserve 20 %. Add a "barracks duty" idle point per BARRACKS building so idle troops gather at military buildings, not the townhall |
| **Sentry posts** | Posts come from the layout (walls, gates, towers). More troops do not create more posts | None; the extra troops go to patrol, reserve or barracks |
| **Raids (M4)** | `maxCommit` 12 (16 Seljuk) caps the contingent. The commit fraction is unaffected. Millénaire still decides raid outcomes by its own rules | Scale `maxCommit` per tier (for example STRONGHOLD 24). Keep `minHome` 0.5 |
| **M2 defense** | Deployment is `commitPerThreat` × threats, bounded by doctrine and reserve. Larger pools mean more responders and a stronger reserve. Coordinator cost grows with pool size only during alerts | None required; verify deploy latency with a pool of 128 |
| **Spawning** | `spawnsPerSlot` 2 per slot interval, `spawnsPerTick` 2. A 64-unit starting grant would take many slots. That is intended, as a throughput guard | None (it is only the first fill) |
| **Equipment** | Profiles are applied once at spawn, so there is no per-tick cost. More entities carry more item data for clients (Epic Knights armour included) | None; measure client FPS in the spike |
| **HywMill CPU** | Duty tick is roughly linear in units: about 97 µs for a 64-unit village (G4-P), so about 200 µs at 128 per village tick every 40 ticks. Recruitment and reconcile are per village, not per unit | Negligible |
| **HYW entity AI (the real cost)** | Each HYW unit runs its own target scans, pathfinding and (for riders) horse AI every tick. M3 validated **112** loaded units. Several loaded strongholds at 128 means 300–500 HYW entities | **Scale spike required** (below); a server `garrisonScale` multiplier (default 1.0) and an optional per-server ceiling of loaded garrison units (off by default) for low-end servers |

### 17.6 Recommendation

1. **Change both the formula and the caps**, driven by development (§17.3), with population as the
   realism ceiling. Do not simply multiply the caps.
2. **Scale the levy with the target** (§17.4) so larger garrisons fill in about a week of active
   play, and rebuild in about ten days.
3. **Retune M4 duty and raid data** so the added troops have jobs (§17.5).
4. **Implement it as a separate M5 step (M5-G)**, independent of the politics steps, so it can be
   approved and shipped on its own. It is the only M5 step that changes frozen M3/M4 code:
   * `Recruitment.target` and `dailyRate` gain terms;
   * `TierRule` gains optional fields;
   * the rest is data.
   * No new persisted state: the target is still computed, and the ledger format is unchanged.

   With every new factor at zero and the current caps, it reproduces today's numbers exactly. That
   is a JUnit regression requirement, so the frozen M3/M4 behaviour stays available as a data
   setting.
5. **Gate it behind a scale spike (M5-0, spike S-G):**
   * 2 STRONGHOLD at 128 + 2 GARRISON at 64 (384 units) loaded on a dedicated server;
   * measure MSPT (mean and p99), HYW entity tick share, duty tick and client FPS;
   * include a raid and an M2 alert at full size.

   If MSPT exceeds budget, the defaults drop to about double (STRONGHOLD 96, GARRISON 48) and
   `garrisonScale` remains the lever.
6. **Politics tie-in (optional):** a patron's or sworn player's donations could add levy points, or
   Favor could fund a temporary `levy` boost. This would use the existing levy pool; no new
   recruitment path.

---

## 18. Locked decisions (2026-09-26)

These two decisions are settled. They are recorded here as requirements, not proposals. The audit
below checks each against the frozen M3/M4 architecture: **no technical contradiction was found.**
The items that need care are listed as notes, not objections.

### 18.1 Player-facing politics GUI

**Requirement.** Commands are for development, admin and debug. The player experience is a GUI:

```
Village leader / town hall (or the Politics keybind)
  → Politics / Diplomacy screen
  → select a discovered village
  → inspect relation and political status
  → choose an available action
  → envoy travels, proposal resolves
  → result shown to the player and written to the relevant village chronicles
```

**Home view (the village the player is dealing with):**
* the player's standing (Stranger / Unwelcome / Trusted / Patron / Sworn);
* reputation and Favor;
* current grievances and any current truce;
* relations with discovered villages;
* recent relevant history.

**Selected village view:**
* current relation, political status and trend;
* relevant recent incidents;
* whether the village is willing to negotiate;
* the available actions (Reconcile, Truce, Encourage, Sow Discord), each with its required
  reputation or Favor, diplomacy-point cost, cooldown, and general expected outcome.

Player requests go through the same screen later: intelligence, escort, detachment, truce-related
requests, and chronicle and honours.

**Keybind.** A standard `KeyMapping`, registered through NeoForge's `RegisterKeyMappingsEvent` in a
HywMill category, so it appears in Controls. **Default unbound** (`InputConstants.UNKNOWN`). Pressing
it asks the server for the Politics screen; there is no hard-coded key and no command-only path for
players.

**The GUI holds no political logic.** The backend is authoritative for reputation, standing,
grievances, Favor, diplomacy points, eligibility, willingness, odds, envoy travel time, resolution,
relation changes, truces, consequences, refusal during alerts or raid preparation, and all
persistence. The screen shows a snapshot and submits intents.

**Architecture (API boundary first):**

| Layer | Content |
|---|---|
| `politics.api` (pure, server) | `PoliticsView`: builds immutable snapshots (`HomeView`, `VillageView`, `ActionOption` with requirements, cost, cooldown and an outcome band). `PoliticsActions`: validates and submits intents, returning `Accepted` or a typed `Refusal` (not discovered, standing, Favor, points, cooldown, alert, raid preparation, truce rules...) |
| Commands (M5-1..M5-5) | `/hywmill diplomacy status / list / propose <kind> <from> <to> / cancel` call **the same** `PoliticsView` and `PoliticsActions`. So the headless harness tests exactly what the GUI will use |
| Network (M5-UI) | Versioned custom payloads: client → server `OpenPolitics(villageHint)`, `SelectVillage(id)`, `SubmitAction(kind, from, to)`; server → client `PoliticsSnapshot`, `ActionResult`. The server re-validates every intent (standing, discovery, distance-free rules, cooldowns), rate-limits requests, and sends only what that player may see |
| Client (M5-UI) | `KeyMapping`, the screen and widgets. Client-only classes behind `Dist.CLIENT`; nothing in the client decides anything |

**Technical notes (not contradictions):**
1. **HywMill has no client-side code or payloads today.** M5-UI adds the first. The mod is
   already declared on both sides (`side="BOTH"`) and Millénaire and HYW are client-required
   anyway, so players install nothing new. The server must still behave correctly with a client
   that never opens the screen, since commands remain.
2. **"An extension of Millénaire".** Adding a tab **inside** Millénaire's own leader or town hall
   screen would need Millénaire changes or a mixin, which M5 avoids. The additive plan:
   * the keybind is the primary entry;
   * an interaction on the village leader or town hall sign that Millénaire does not already consume
     (for example sneak + use, **spike**) opens the same screen with that village preselected;
   * the screen follows Millénaire's look (parchment panels, village and culture names, its
     chronicle wording), referencing Millénaire's loaded textures by resource location where
     practical rather than copying assets.

   Millénaire's GUI is not rewritten.
3. **Order.** The GUI comes after the backend and `politics.api` are stable. Building the API
   boundary into M5-1..M5-5 from the start is cheap and avoids a later rewrite.

### 18.2 Garrison population scaling: intentionally much larger

**Principle.** Realism-inspired gameplay, not a population simulator. A garrison larger than the
village's civilian population is acceptable when it makes a more convincing medieval military world.
Believable defenses, visible military presence, meaningful patrols, sentries and scouts, and enough
manpower for raids and defense are the goals. Demographic ratios are not.

**Locked caps** (ceilings; the target formula decides how much of each a settlement fills):

| Tier | Current cap | Locked cap |
|---|---|---|
| WATCH | 8 | **24** |
| GUARD_POST | 16 | **48** |
| GARRISON | 32 | **72** |
| STRONGHOLD | 64 | **128** |

**Requirements.**
1. Higher tiers produce substantially larger garrisons: about **2–3× today's** where the settlement
   supports it.
2. Military infrastructure, settlement size, and culture and village type all raise the target.
3. The caps are actually reached by large, developed settlements.
4. Deterministic and data-driven.
5. No new persistence format for this change.
6. The M3/M4 lifecycle, ownership, deterministic UUIDs, roster, duties, equipment provider,
   faction and controller identity, and relation semantics are untouched.
7. Levy scales with the target (for example `perTargetDaily ≈ 0.12`,
   `poolCap = max(poolCap[tier], 0.25 × target)`), with no offline catch-up. It is still one paid
   recruit per recruit interval, under the existing calm, paused and cooldown rules. Replacements
   stay paid through levy. There is no global server cap unless the scale test proves one is needed.
8. A regression path: with the new factors at neutral values, the current target and levy are
   reproduced exactly (JUnit).
9. Performance is not solved by shrinking armies back. If the scale test finds a bottleneck, it is
   identified and fixed where it is.

#### 18.2.1 Fit with the frozen architecture (audit)

* **Target and levy are computed, not stored.** `Recruitment.target` and `dailyRate` are pure
  functions of the village record and `GarrisonTable`. The new terms read fields the ledger already
  has: `capacity`, `population`, `adults`, `buildingRoles`, `fortification`, `culture`, `type`.
  **No ledger format change.**
* **`TierRule` gains optional fields** (`perSlot`, `levyShare`, `supportRatio`, `perTargetDaily`,
  `poolCapShare`) plus a table-level `infraBonus` map and a `typeFactor` patch. Missing fields
  default to the neutral values (`perSlot` = current `perCapacity`, all others 0 or off), so an old
  datapack behaves exactly as today.
* **Caps are data** (`maxUnits`, `maxTarget` in `hywmill_garrison/defaults.json`). Recruitment's
  `TIER_CAP` and `TARGET_REACHED` blockers, the starting grant (50 % of target, once), the wipe-out
  rule (75 % of target) and cooldowns keep their meaning at the larger sizes.
* **Existing villages** keep `startingGranted = true`, so their larger target fills through levy;
  there is no mass spawn on update. The throttles (`spawnsPerSlot`, `spawnsPerTick`) are unchanged.
* **The M3 freeze text** ("caps 0/8/16/32/64") gets an addendum when M5-G is implemented, like the
  spawn-fallback addendum.

#### 18.2.2 First reading of the investigation values (on paper, from harness villages)

The M4 harness logs record capacity for three villages; population and adults will come from the
spike (assumed about 30 adults here, only to show the shape):

| Harness village | Tier | Capacity (= target today) | Investigation formula | vs today |
|---|---|---|---|---|
| Norman "Ondefontaine les-pâtures" | GUARD_POST | 16 | 16 × 1.25 + guard buildings (≈ 2–4) + 0.15 × 30 ≈ **27–29** | ≈ 1.7–1.8× |
| Byzantine military village (barracks, armoury, fort townhall; 2 professionals, so GUARD_POST) | GUARD_POST | 16 | 20 + 8 + 4 + 8 + 4.5 ≈ **45** | ≈ 2.8× |
| "militaire" | depends on buildings | 11 | spike | spike |

**Finding.** The investigation values reach the 2–3× goal where military buildings exist, but they
undershoot it for ordinary villages at the lower tiers. They also make WATCH 24 and GUARD_POST 48
hard to reach. The main reasons:
* `supportRatio` 0.5 and 0.75 would cap WATCH at half the population, which conflicts with the
  locked principle;
* `perSlot` is only 1.0–1.25 at the lower tiers.

The spike should therefore tune toward an effective 2–3 troops per soldier or militia slot, with
`supportRatio` defaulting to **off** (kept only as an optional data knob). It should confirm on real
villages that each locked cap is reachable by a well-developed settlement of that tier. Setting the
values themselves is spike work; the direction is settled.

#### 18.2.3 Spike S-G (before any M3/M4 change)

1. Inspect the target and levy implementation (done in §17.1).
2. Dump every harness village's inputs on a dedicated server: tier, capacity, population, adults,
   building roles, fortification, culture, type. Use a read-only command or the existing
   `/hywmill village` output; no behaviour change.
3. Compute targets per tier with candidate values in a pure offline table (JUnit-style), check the
   2–3× band, and check that each locked cap is reachable.
4. Duty allocation at 24, 48, 72 and 128 with the current M4 duty data (pure `DutyAllocator` runs).
5. Raid contingent sizes at those sizes (current `commitFraction` and `maxCommit`).
6. M2 deployment with large rosters: deploy latency and coordinator cost during an alert.
7. Spawning and equipment load: time to spawn a 64-unit starting grant, and profile application cost.
8. **Server scale test:** 2 strongholds × 128 + 2 garrisons × 72 + smaller villages, all loaded.
   Measure MSPT mean and p99, the HYW entity tick share, HywMill duty and recruitment cost, target
   and pathfinding behaviour, and client FPS. Run it while CALM, during an alert, and during a raid.
9. Report. If performance is acceptable, implement through the existing data-driven tier
   machinery. If not, name the bottleneck and propose a targeted fix.

#### 18.2.4 Consequences to decide separately

* **M4 duty data.** STRONGHOLD maxima (8 sentry pairs, 8 patrol, 4 scouts, reserve 15 %) staff about
  40 units. At 128, about 85 would stand on GARRISON duty at the muster points, which also crowd,
  since units are spread by UUID hash over the muster list. The locked decision keeps M4 duties
  unchanged unless separately approved, so retuning these **data** maxima was open question 10, now
  **approved** (§18.3). No code change to the duty system is needed; it is already quota-driven.
* **M4 raid data.** `maxCommit` 12 (16 Seljuk) caps contingents regardless of garrison size. Scaling
  it per tier is approved with question 10 (§18.3).

### 18.3 Approved answers to the open questions (binding)

**Q1. Outlaw trigger.**
* **Normal rule:** a serious grievance (killing a resident or garrison member, or the equivalent
  severity) **and** combined Millénaire reputation ≤ −1024.
* **Exception:** killing a resident or garrison member **inside the village during peacetime**
  makes the player an outlaw at once, whatever their reputation.
* Severity and context come from the **grievance record**; there is no new meter. Each grievance
  stores its kind, the place (inside the village radius or not) and whether it was peacetime.
  "Peacetime" means no war or campaign (§16) puts the player against that village, and the player
  was not already a legitimate threat of the village. A killing in self-defence, where the victim
  attacked first according to the incident ledger, is recorded at a lower severity.
* The removed rule: the earlier draft also made reputation ≤ −4096 alone enough. That clause is
  **dropped**, since it was not approved.
* Leaving outlawry keeps the §4 hysteresis: the grievance must decay below `pardon` **and**
  reputation must be above −1024.

**Q2. Favor.** No trade-based Favor. Favor is earned only through service: defending the village,
successful diplomacy the village asked for, successful escorts and detachments, long good standing,
and future deeds explicitly defined as service.

**Q3. Sow discord.** Included; Sworn only; costs a diplomacy point and Favor; cooldowns and limits
as in §7.2; can fail; exposure gives a grievance with the target village and can hurt the player's
standing elsewhere. It must not be a spammable relation lever.

**Q4. Escorts and unloaded terrain.** No force-loading for escorts, ever.
* Escort units move normally through loaded, ticking terrain.
* They cross unloaded terrain only when the player travels with them and loads it naturally.
* If the player gets too far ahead and the terrain ahead is not loaded, the escort **holds** where
  it is. It does not force-load and does not teleport.
* **Integration defect found (needs a small, approved M3 change in M5-5).** M3's `Reconciler`
  marks a unit MISSING after `missingGrace` of *village-active* time without being seen, then LOST
  after `lostTimeout`. It is correct for home units, and M4 raid contingents never sit away in
  unloaded terrain (Millénaire's raid materialization moves them). An escort or detachment holding
  in unloaded terrain while its home village is loaded would be **wrongly marked MISSING and then
  LOST**. This contradicts M3's own principle, "unloaded is never dead and never lost".
  * **Proposed fix:** for a unit on an away temporary duty (ESCORT / DETACHED), the reconciler does
    not run the missing clock while the chunk of its last known position is not loaded. It resumes
    once that chunk is loaded and the unit is still not found.
  * It is additive, keyed on the new duty values, and leaves home units, raids and the M3 lifecycle
    unchanged.
  * It will be implemented and tested as part of M5-5, under an explicit approval for that step.
  * If the chunk is loaded and the unit is truly gone, the normal MISSING and LOST path applies,
    and the loss costs Favor as §5 says.

**Q5. Interface.** Commands first; the GUI with a configurable keybind follows (§18.1).

**Q6. Automatic war between villages.** On by default. When the Millénaire relation between two
villages is ≤ −90 (OPEN_CONFLICT) for at least the configured minimum duration, HywMill projects
A faction → HOSTILE → B faction and B → HOSTILE → A. Details:
* not at the instant the relation reaches −90;
* a truce or peace ends the projection;
* the server switch `politics.autoWar` turns it off entirely;
* Millénaire's relation stays the diplomatic fact, and the war state is its military consequence.

**Q7. Co-belligerents.** FRIENDLY in both directions between the player and the allied village
faction, for the campaign only, so HYW's full friendly-fire protection applies. The player's
participating units use the player's identity (spike 1 settles team identities). When the campaign
ends:
* the projector restores the previous relation;
* the temporary FRIENDLY is removed;
* the normal political relationship is not changed by the campaign ending.

**Q8. Raid contingents engage combatants only.**
* This is a narrow change to frozen M4 target selection in `RaidService`, which today engages the
  nearest target residents with temporary hostility. It will engage only combatant villagers of
  the target village: SOLDIER and LEADER always, and MILITIA unless the target village's
  `militiaPolicy` is NEVER. Roles come from the M1.1 role tables.
* Civilians are never raid targets, however close.
* Millénaire's raid mechanics and participant list are unchanged, and M3 ownership and lifecycle
  are unchanged.
* If no combatant is in reach, the contingent holds its position in the raid and keeps
  retaliating (HYW temporary hostility) against whatever attacks it.
* It is delivered in M5-5b and tested in the G4 raid scenarios.

**Q9. Garrison scale.** Caps WATCH 24, GUARD_POST 48, GARRISON 72, STRONGHOLD 128; about 2–3×
today's garrisons. The scale spike checks technical and performance viability, not realism
(§18.2).

**Q10. Duty and raid data at the larger sizes.** Approved for M5-G: raise the data values for
sentry pairs, patrol, scouts, reserve, their minimum and maximum quotas, and raid commitment
(`maxCommit` per tier), so that a 128-unit stronghold has substantially more than about 40 units on
active roles.
* Raids still respect the minimum home share, kept sentry pairs, reserve, patrol and scout
  constraints, M2 readiness, village willingness, doctrine and living units.
* The exact values come from the spike's duty-capacity analysis.
* **Data only.** The duty algorithms change only if the spike proves they cannot support the larger
  values; that would be reported first.

**Changes to frozen M3/M4 now approved, and where they land:**

| Change | Kind | Step |
|---|---|---|
| Target and levy formula terms; caps 24/48/72/128 | M3 code (pure functions) + data | M5-G |
| Duty quotas and raid commitment at the new sizes | M4 data only | M5-G |
| Raid contingents engage combatants only | M4 code (`RaidService` target selection) | M5-5b |
| Missing clock paused for away units in unloaded terrain | M3 code (`Reconciler`), additive | M5-5 (**requires your confirmation**; found in this audit) |
