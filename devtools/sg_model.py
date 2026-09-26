#!/usr/bin/env python3
"""
Spike S-G (M5-G): offline evaluation of candidate garrison-target formulas against the inputs of
real harness villages. Pure; reads the "SG input {json}" lines the harness logs (SG_0) and prints,
per village and candidate, the current target (M3 as shipped), the candidate target, the ratio,
and whether the locked cap is reached. Nothing here changes the mod.

    python3 devtools/sg_model.py docs/m5-test-evidence/sg-run.txt

Candidate formula (design §18.2; population is not a hard ceiling, supportRatio off by default):

    raw    = slots * perSlot[tier] + sum(infraBonus[role] * count) + min(fortification / 4, 16) + levyShare[tier] * adults
    target = min(maxUnits[tier], clamp(round(raw * typeFactor), minTarget[tier], maxTarget[tier]))
    (optional) target = min(target, floor(supportRatio[tier] * population)) when supportRatio is set
"""
import json
import re
import sys

CAPS = {"NONE": 0, "WATCH": 24, "GUARD_POST": 48, "GARRISON": 72, "STRONGHOLD": 128}
CURRENT = {  # hywmill_garrison/defaults.json as shipped (M3/M4 frozen)
    "WATCH": dict(perCapacity=1.0, minTarget=1, maxTarget=8, maxUnits=8),
    "GUARD_POST": dict(perCapacity=1.0, minTarget=2, maxTarget=16, maxUnits=16),
    "GARRISON": dict(perCapacity=1.0, minTarget=3, maxTarget=32, maxUnits=32),
    "STRONGHOLD": dict(perCapacity=1.0, minTarget=4, maxTarget=64, maxUnits=64),
}
MIN_TARGET = {"WATCH": 1, "GUARD_POST": 2, "GARRISON": 3, "STRONGHOLD": 4}

CANDIDATES = {
    # the investigation values from the design audit (§18.2), supportRatio off
    "C1 design investigation values": dict(
        perSlot={"WATCH": 1.0, "GUARD_POST": 1.25, "GARRISON": 1.5, "STRONGHOLD": 2.0},
        levyShare={"WATCH": 0.10, "GUARD_POST": 0.15, "GARRISON": 0.20, "STRONGHOLD": 0.25},
        infra={"BARRACKS": 8, "FORT_TOWNHALL": 8, "ARMOURY": 4, "TRAINING": 4, "GUARDHOUSE": 2, "WATCHTOWER": 2, "TOWER": 1},
        fortDiv=4, fortCap=16),
    # tuned toward an effective 2-3 troops per Millénaire soldier/militia slot at every tier (§18.2.2)
    "C2 tuned (2-3x per slot)": dict(
        perSlot={"WATCH": 2.0, "GUARD_POST": 2.25, "GARRISON": 2.5, "STRONGHOLD": 3.0},
        levyShare={"WATCH": 0.15, "GUARD_POST": 0.20, "GARRISON": 0.25, "STRONGHOLD": 0.30},
        infra={"BARRACKS": 8, "FORT_TOWNHALL": 8, "ARMOURY": 4, "TRAINING": 4, "GUARDHOUSE": 3, "WATCHTOWER": 3, "TOWER": 1, "GATE": 1},
        fortDiv=4, fortCap=16),
}
TYPE_FACTOR = {}  # culture/type patches; none measured yet (1.0)


def roles(s):
    return {k: int(v) for k, v in re.findall(r"(\w+)=(\d+)", s or "")}


def current_target(d):
    t = d.get("tier", "NONE")
    if t not in CURRENT:
        return 0
    r = CURRENT[t]
    return min(r["maxUnits"], max(r["minTarget"], min(r["maxTarget"], round(d["capacity"] * r["perCapacity"]))))


def candidate_target(d, c, support=None):
    t = d.get("tier", "NONE")
    if t not in CAPS or t == "NONE":
        return 0
    b = roles(d.get("buildingRoles"))
    raw = d["capacity"] * c["perSlot"][t]
    raw += sum(c["infra"].get(k, 0) * n for k, n in b.items())
    raw += min(d.get("fortification", 0) / c["fortDiv"], c["fortCap"])
    raw += c["levyShare"][t] * d.get("adults", 0)
    raw *= TYPE_FACTOR.get(d.get("type"), 1.0)
    target = min(CAPS[t], max(MIN_TARGET[t], min(CAPS[t], round(raw))))
    if support:
        target = min(target, int(support[t] * d.get("population", 0)))
    return target


def main(path):
    rows = []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.search(r"SG input (\{.*\})\s*$", line)
        if m:
            d = json.loads(m[1])
            if d.get("faction") and d.get("faction") not in {r.get("faction") for r in rows}:
                rows.append(d)
    print(f"{len(rows)} villages")
    for d in rows:
        print(f"- {d.get('name')} ({d.get('type')}): tier {d.get('tier')}, capacity {d.get('capacity')}, population {d.get('population')}, "
              f"adults {d.get('adults')}, fortification {d.get('fortification')}, buildings {d.get('buildingRoles')}")
    for name, c in CANDIDATES.items():
        print(f"\n## {name}")
        print("| village | tier | current target | candidate | x current | locked cap | cap reached |")
        print("|---|---|---|---|---|---|---|")
        for d in rows:
            cur = current_target(d)
            new = candidate_target(d, c)
            print(f"| {d.get('name')} | {d.get('tier')} | {cur} | {new} | {new / cur if cur else float('nan'):.2f} | {CAPS.get(d.get('tier'), 0)} | "
                  f"{'yes' if new >= CAPS.get(d.get('tier'), 1) else 'no'} |")
        # what a developed settlement of each tier needs to reach its cap (same buildings as the richest harness village)
        print("\nSlots needed to reach the cap with 40 adults, fortification 40, barracks+armoury+training+fort townhall (GARRISON+), or a guardhouse+watchtower (lower tiers):")
        for t in ("WATCH", "GUARD_POST", "GARRISON", "STRONGHOLD"):
            b = "{BARRACKS=1, ARMOURY=1, TRAINING=1, FORT_TOWNHALL=1}" if t in ("GARRISON", "STRONGHOLD") else "{GUARDHOUSE=1, WATCHTOWER=1}"
            for slots in range(0, 200):
                if candidate_target(dict(tier=t, capacity=slots, adults=40, population=55, fortification=40 if t in ("GARRISON", "STRONGHOLD") else 0,
                                         buildingRoles=b), c) >= CAPS[t]:
                    print(f"  {t}: {slots} soldier/militia slots")
                    break


if __name__ == "__main__":
    main(sys.argv[1])
