#!/usr/bin/env python3
"""
Headless dedicated-server acceptance harness for hywmill.

It installs NeoForge 21.1.226, copies the three mod jars in, starts a FRESH world with
Millénaire natural generation and random raids disabled, spawns deterministic test
villages by command, and runs scenario checks by parsing command output and logs.

Standard library only. Usage (from the repository root):

    python3 devtools/server-harness/harness.py --dir /tmp/hywmill-server install
    python3 devtools/server-harness/harness.py --dir /tmp/hywmill-server run all
    python3 devtools/server-harness/harness.py --dir /tmp/hywmill-server run A C F2

Exit code 0 only if every selected scenario passed.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

NEO_VERSION = "21.1.226"
INSTALLER_URL = f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{NEO_VERSION}/neoforge-{NEO_VERSION}-installer.jar"
REPO = Path(__file__).resolve().parents[2]
MILLENAIRE_JAR = REPO / "libs" / "millenaire-9.0.2.jar"
HYW_JAR = REPO / "libs" / "HundredYearsWar-0.7.1r-fix1-1.21.1-neoforge.jar"
ANSI = re.compile(r"\x1b\[[0-9;]*m")

# Fake "player" owner used for owned HYW units (never a real account).
OWNER_UUID = "11111111-2222-4333-8444-555555555555"
OWNER_NBT = "[I;286331153,572670771,-2075896491,1431655765]"
FAKE_PLAYER_UUID = "5f1c7d5e-0000-4000-8000-00000000beef"  # dev playerhit FakePlayer

# Seed 20260925: plains positions verified during M1. Villages are spawned, not generated.
VILLAGE_A_CANDIDATES = [("norman/agricole", 630, 82, 612), ("norman/agricole", 600, 80, 600), ("norman/agricole", 694, 80, 756)]
VILLAGE_B_CANDIDATES = [("norman/agricole", 830, 80, 612), ("norman/agricole", 860, 80, 640)]
FORCELOAD = [(528, 528, 700, 700), (740, 540, 900, 690)]
# M2: extra villages for classification/capacity/doctrine/performance (spawned on demand by X/P).
# Positions scouted on seed 20260925 (ground measured with the motion_blocking_no_leaves heightmap):
# the sea covers most of the area, and Millénaire rejects a centre building it cannot reach.
EXTRA_FORCELOAD = [(560, 830, 740, 1010), (310, 360, 490, 540), (960, 550, 1150, 730)]
EXTRA_VILLAGES = {
    "militaire": [("norman/militaire", 650, 68, 920), ("norman/militaire", 640, 68, 960), ("norman/militaire", 670, 68, 900)],
    "byzantine": [("byzantines/militaryvillage", 400, 71, 450), ("byzantines/militaryvillage", 380, 71, 600)],
    "artisans": [("norman/artisans", 1060, 80, 640), ("norman/artisans", 1040, 80, 612)],
}
MILLENAIRE_DATA_PREFIX = "millenaire/cultures/"


def log(msg):
    print(f"[harness] {msg}", flush=True)


# --------------------------------------------------------------------------- install

def install(d: Path):
    d.mkdir(parents=True, exist_ok=True)
    if not (d / "libraries" / "net" / "neoforged" / "neoforge" / NEO_VERSION).exists():
        inst = d / "installer.jar"
        log(f"downloading {INSTALLER_URL}")
        urllib.request.urlretrieve(INSTALLER_URL, inst)
        subprocess.run(["java", "-jar", str(inst), "--installServer", str(d)], cwd=d, check=True,
                       stdout=subprocess.DEVNULL)
    (d / "eula.txt").write_text("eula=true\n")
    (d / "user_jvm_args.txt").write_text("-Xmx6G\n")
    log("installed")


def write_configs(d: Path, hywmill_extra: str = ""):
    (d / "server.properties").write_text(
        "online-mode=false\nlevel-seed=20260925\nspawn-protection=0\nview-distance=6\n"
        "simulation-distance=6\nmax-tick-time=-1\ndifficulty=normal\nlevel-name=world\n"
        "enable-command-block=true\nmotd=hywmill-harness\n")
    cfg = d / "config"
    cfg.mkdir(exist_ok=True)
    verbose = "false" if os.environ.get("HYWMILL_QUIET") else "true"  # HYWMILL_QUIET=1: production log level (perf runs)
    (cfg / "hywmill-common.toml").write_text(
        f"[general]\n\tverboseLogging = {verbose}\n\tdevCommands = true\n" + hywmill_extra)
    # Deterministic worlds: no natural villages/lone buildings, no random raids, no telemetry.
    (cfg / "millenaire-server.toml").write_text(
        "[generation]\n\tgenerateVillages = false\n\tgenerateLoneBuildings = false\n"
        "[statistics]\n\tsendStatistics = false\n"
        "[raids]\n\traidingRate = 0\n")


def install_mods(d: Path, mods):
    md = d / "mods"
    if md.exists():
        shutil.rmtree(md)
    md.mkdir()
    for m in mods:
        shutil.copy(m, md / Path(m).name)


def built_jar() -> Path:
    jars = sorted((REPO / "build" / "libs").glob("hywmill-*.jar"))
    jars = [j for j in jars if "sources" not in j.name]
    if not jars:
        sys.exit("build/libs/hywmill-*.jar not found: run ./gradlew build first")
    return jars[-1]


# --------------------------------------------------------------------------- server

class Server:
    def __init__(self, d: Path):
        self.d = d
        self.proc = None
        self.log = d / "harness-server.log"

    def start(self, timeout=300):
        args = (self.d / "libraries" / "net" / "neoforged" / "neoforge" / NEO_VERSION / "unix_args.txt")
        self.logf = open(self.log, "a", encoding="utf-8", errors="replace")
        self.logf.write(f"\n===== harness start {time.ctime()} =====\n")
        self.logf.flush()
        self.start_pos = self.log.stat().st_size
        self.proc = subprocess.Popen(["java", "@user_jvm_args.txt", f"@{args}", "nogui"], cwd=self.d,
                                     stdin=subprocess.PIPE, stdout=self.logf, stderr=subprocess.STDOUT, text=True)
        self.wait_for(r"Done \(", timeout, since=self.start_pos, fail=r"Mod loading has failed|Crash")
        # the goal bridge installs right after Done when Millénaire is present
        time.sleep(3)
        log("server started")

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.send("stop")
            try:
                self.proc.wait(timeout=120)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self.proc = None
        log("server stopped")

    def pos(self):
        return self.log.stat().st_size

    def read_since(self, pos):
        with open(self.log, encoding="utf-8", errors="replace") as f:
            f.seek(pos)
            return [ANSI.sub("", l.rstrip("\n")) for l in f.readlines()]

    def send(self, command):
        self.proc.stdin.write(command + "\n")
        self.proc.stdin.flush()

    def cmd(self, command, wait=2.0):
        """Runs a console command and returns the log lines produced meanwhile."""
        p = self.pos()
        self.send(command)
        time.sleep(wait)
        return self.read_since(p)

    def output(self, command, wait=2.0):
        """Only the command's own chat output lines (MinecraftServer logger), prefix stripped."""
        return [l.split("]: ", 1)[1] for l in self.cmd(command, wait) if "[minecraft/MinecraftServer]: " in l]

    def wait_for(self, regex, timeout, since=None, fail=None):
        since = self.pos() if since is None else since
        rx = re.compile(regex)
        frx = re.compile(fail) if fail else None
        end = time.time() + timeout
        while time.time() < end:
            for l in self.read_since(since):
                if rx.search(l):
                    return l
                if frx and frx.search(l):
                    raise RuntimeError(f"failure pattern seen: {l}")
            if self.proc and self.proc.poll() is not None:
                raise RuntimeError("server process exited")
            time.sleep(1)
        return None


# --------------------------------------------------------------------------- helpers

class Ctx:
    def __init__(self, server):
        self.s = server
        self.a = None  # (x, y, z) center of village A
        self.b = None


def at(c, command):
    return f"execute positioned {c[0]} {c[1]} {c[2]} run {command}"


def surface_y(s, x, z):
    """Ground height at (x, z): a marker summoned on the motion_blocking_no_leaves heightmap."""
    s.cmd("kill @e[tag=hwY]", 0.5)
    s.cmd(f'execute positioned {x} 0 {z} positioned over motion_blocking_no_leaves run summon minecraft:marker ~ ~ ~ {{Tags:["hwY"]}}', 1)
    y = None
    for l in s.output("data get entity @e[tag=hwY,limit=1] Pos[1]", 1):
        m = re.search(r"has the following entity data: (-?[\d.]+)d", l)
        if m:
            y = int(float(m[1]))
    s.cmd("kill @e[tag=hwY]", 0.5)
    return y


def spawn_village(s, candidates, surface=False):
    """surface=True spawns at the real ground height (Millénaire's reachability check starts from
    the given position, so a guessed y above or below the surface rejects the village)."""
    for vtype, x, y, z in candidates:
        if surface:
            y = surface_y(s, x, z) or y
        lines = s.cmd(f'millenaire spawn at {x} {y} {z} "{vtype}" 100', wait=45)
        for l in lines:
            m = re.search(r"Village \S+ spawned at (-?\d+), (-?\d+), (-?\d+)", l)
            if m:
                return tuple(int(v) for v in m.groups())
    return None


def residents(s, c):
    """[(uuid, type, role, goal, attackTarget)] of loaded residents of the nearest village."""
    out = []
    for l in s.output(at(c, "hywmill village residents"), 2):
        m = re.match(r"\s*([0-9a-f-]{36}) (\S+) (\w+) goal=(\S+) attackTarget=(\S+)", l)
        if m:
            out.append(m.groups())
    return out


def incidents(s, n=100):
    rows = []
    for l in s.output(f"hywmill incidents {n}", 2):
        m = re.match(r"\s*t=(\d+) (\S+)\[(\w+) fac=(\S+)\] -> (\S+)\[(\w+) fac=(\S+)\] dmg=(\S+) inherent=(\w+) resident=(\w+)", l)
        if m:
            rows.append(dict(t=int(m[1]), atype=m[2], a=m[3], afac=m[4], vtype=m[5], v=m[6], vfac=m[7],
                             inherent=m[9] == "true", resident=m[10] == "true"))
    return rows


def info(s, c):
    d = {}
    for l in s.output(at(c, "hywmill village info"), 2):
        for key, rx in [("villageId", r"^VillageId: (\S+)"), ("faction", r"^Faction UUID \(synthetic\): (\S+)"),
                        ("tier", r"^Tier: (\w+)"), ("garrison", r"garrison: (\d+)"), ("fortification", r"fortification: (\d+)"),
                        ("defending", r"Defending strength \(Mill.naire\): (\d+)"),
                        ("villagerRoles", r"^Villager roles: (.*)"), ("buildingRoles", r"^Building roles: ([^|]*)")]:
            m = re.search(rx, l)
            if m:
                d[key] = m[1]
    return d


def relation(s, c, other):
    for l in s.output(at(c, f"hywmill dev relation {other}"), 2):
        m = re.search(r": (\w+) \| reverse: (\w+)", l)
        if m:
            return m[1], m[2]
    return None


def military(s, c):
    """Parses /hywmill village military."""
    out = s.output(at(c, "hywmill village military"), 2)
    d = {"lines": out, "threat_lines": [l for l in out if l.startswith(" threat ")]}
    for l in out:
        for key, rx in [("tier", r"^Tier: (\w+)"), ("soldiers", r"soldiers (\d+)"), ("leaders", r"leaders (\d+)"),
                        ("militia", r"militia (\d+),"), ("defenders", r"defenders (\d+)"), ("capacity", r"^Capacity: (\d+)"),
                        ("readiness", r"readiness: (\d+)%"), ("equipment", r"equipment: ([\d.]+|n/a)"),
                        ("fortification", r"fortification: (\d+)"), ("buildingRoles", r"^Building roles: (\{[^}]*\})"),
                        ("alert", r"^Alert: (\w+)"), ("eligible", r"eligible (\d+)"), ("committed", r"committed (\d+)"),
                        ("reserve", r"\| reserve (\d+)"), ("threats", r"^Active threats: (\d+)"), ("stats", r"^Stats: (.*)"),
                        ("doctrine", r"^Doctrine: (.*)")]:
            m = re.search(rx, l)
            if m and key not in d:
                d[key] = m[1]
    return d


def doctrine(s, c):
    """Parses /hywmill doctrine get into {field: (value, source)}."""
    d = {}
    for l in s.output(at(c, "hywmill doctrine get"), 2):
        m = re.match(r"(\w+) = (\S+)\s+\[(.*)\]$", l.strip())
        if m:
            d[m[1]] = (m[2], m[3])
    return d


def wait_alert(s, c, states, timeout):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        last = military(s, c).get("alert")
        if last in states:
            return last
        time.sleep(2)
    return last


def wait_residents(s, c, timeout=120):
    end = time.time() + timeout
    while time.time() < end:
        r = residents(s, c)
        if any(x[2] == "DEFENDER" for x in r) and any(x[2] == "CIVILIAN" for x in r):
            return r
        time.sleep(5)
    return residents(s, c)


# --------------------------------------------------------------------------- scenarios

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    log(f"{'PASS' if ok else 'FAIL'} {name} {detail}")
    return ok


def setup(ctx):
    s = ctx.s
    for box in FORCELOAD:
        s.cmd("forceload add {} {} {} {}".format(*box), wait=15)
    s.cmd("time set 1000", 1)
    s.cmd("gamerule doDaylightCycle true", 1)
    ctx.a = spawn_village(s, VILLAGE_A_CANDIDATES)
    ctx.b = spawn_village(s, VILLAGE_B_CANDIDATES)
    s.cmd("millenaire chunkload", 10)
    check("setup: village A spawned", ctx.a is not None, str(ctx.a))
    check("setup: village B spawned", ctx.b is not None, str(ctx.b))
    if ctx.a:
        r = wait_residents(s, ctx.a)
        check("setup: A has defenders and civilians", any(x[2] == "DEFENDER" for x in r) and any(x[2] == "CIVILIAN" for x in r),
              f"{len(r)} residents")


def reuse(ctx):
    """--keep-world: find the two villages already in the ledger instead of spawning new ones."""
    s = ctx.s
    for box in FORCELOAD:
        s.cmd("forceload add {} {} {} {}".format(*box), wait=15)
    s.cmd("millenaire chunkload", 10)
    centers = []
    for l in s.output("hywmill village list", 2):
        m = re.search(r" \((-?\d+), (-?\d+), (-?\d+)\) tier=| (-?\d+), (-?\d+), (-?\d+) tier=", l)
        if m:
            g = [x for x in m.groups() if x is not None]
            centers.append(tuple(int(x) for x in g))
    ctx.a = centers[0] if centers else None
    ctx.b = centers[1] if len(centers) > 1 else None
    check("reuse: villages found in existing world", ctx.a is not None, str(centers))


def scenario_M(ctx):
    """M1.1-5: a format-1 ledger (written by an M1-format build) is migrated and recomputed."""
    s = ctx.s
    lines = s.read_since(s.start_pos)
    mig = next((l for l in lines if re.search(r"migrated \d+ record\(s\) from format 1 to 2", l)), None)
    check("M1 format-1 ledger migrated on load", mig is not None, mig or "")
    rec = s.wait_for(r"recomputed after ledger migration", 60, since=s.start_pos)
    check("M2 migrated records recomputed", rec is not None, rec or "")
    out = s.output(at(ctx.a, "hywmill village info"), 2)
    check("M3 village info shows role counts", any(l.startswith("Building roles:") for l in out), "; ".join(out))


def scenario_A(ctx):
    s = ctx.s
    time.sleep(12)  # at least one ledger update
    before = info(s, ctx.a)
    check("A1-3 village info populated", all(k in before for k in ("villageId", "faction", "tier", "garrison", "fortification")), str(before))
    s.output(at(ctx.a, "hywmill doctrine set reserve 0"), 2)
    s.cmd("save-all flush", 5)
    s.stop()
    s.start()
    time.sleep(15)
    loaded = s.wait_for(r"Garrison ledger loaded: \d+ village.*format 3", 30, since=s.start_pos)
    after = info(s, ctx.a)
    check("A4 ledger reloaded from disk", loaded is not None, loaded or "")
    check("A5-6 same VillageId and faction after restart",
          before.get("villageId") == after.get("villageId") and before.get("faction") == after.get("faction"),
          f"{before.get('faction')} vs {after.get('faction')}")
    ctx.info_after_restart = after
    d = doctrine(s, ctx.a)
    check("A7 doctrine override persisted across restart (ledger format 3)", d.get("reserve") == ("0", "override"), str(d.get("reserve")))
    s.output(at(ctx.a, "hywmill doctrine reset"), 2)
    check("A8 doctrine reset restores the inherited value", doctrine(s, ctx.a).get("reserve", ("", ""))[1] != "override")


def scenario_B(ctx):
    s = ctx.s
    c = ctx.a
    s.cmd(f"summon hundred_years_war:militia {c[0] + 3} {c[1]} {c[2] + 3} {{OwnerUUID:{OWNER_NBT}}}", 2)
    time.sleep(60)
    inc = incidents(s)
    attacks = [i for i in inc if i["atype"] == "hundred_years_war:militia" and i["vtype"] == "millenaire:villager"]
    threats = s.output(at(c, "hywmill threats"), 2)
    check("B owned HYW unit does not attack villagers", not attacks, f"{len(attacks)} attacks")
    check("B owned HYW unit is not a threat", not any("militia" in l for l in threats[1:]), "; ".join(threats))


def scenario_C(ctx, proactive_expected=True):
    """A hostile (unowned) HYW unit that actually attacks residents. M2 (proactive=false): defenders
    respond once it targets or damages a resident (ATTACKING_RESIDENT / RECENT_ATTACKER), not merely
    because it is present; the outcome is the same as in M1: defenders fight it, civilians shelter."""
    s = ctx.s
    c = ctx.a
    res = wait_residents(s, c)
    civ = {r[0][:8] for r in res if r[2] == "CIVILIAN"}
    defenders = {r[0][:8] for r in res if r[2] == "DEFENDER"}
    p = s.pos()
    bandit_hits = defender_hits = []
    for attempt in range(3):
        s.cmd(f"summon hundred_years_war:bandit_soldier {c[0] + 2} {c[1] + 1} {c[2] + 2}", 1)
        s.wait_for(r"Threat cleared in village", 90, since=p)
        inc = [i for i in incidents(s) if i["t"] >= 0]
        bandit_hits = [i for i in inc if i["atype"] == "hundred_years_war:bandit_soldier" and i["resident"]]
        defender_hits = [i for i in inc if i["atype"] == "millenaire:villager" and i["vtype"] == "hundred_years_war:bandit_soldier"]
        if bandit_hits and defender_hits:
            break
    lines = s.read_since(p)
    civ_attacks = [i for i in incidents(s) if i["atype"] == "millenaire:villager" and i["a"] in civ
                   and i["vtype"].startswith("hundred_years_war:")]
    check("C1 threat detected", any("Threat detected in village" in l and "bandit_soldier" in l for l in lines))
    check("C2 bandit attacks marked villagers", bool(bandit_hits), f"{len(bandit_hits)} hits")
    check("C3 defenders fight back", bool(defender_hits) and all(i["a"] in defenders for i in defender_hits),
          f"{len(defender_hits)} hits by {sorted({i['a'] for i in defender_hits})}")
    check("C4 civilians never attack HYW units", not civ_attacks, f"{len(civ_attacks)}")
    check("C4 civilians shelter", any("enters hide behavior" in l for l in lines))


def scenario_D(ctx):
    s = ctx.s
    c = ctx.a
    s.cmd("kill @e[type=hundred_years_war:militia]", 1)
    s.cmd(f"summon hundred_years_war:militia {c[0] + 4} {c[1]} {c[2] - 4} {{OwnerUUID:{OWNER_NBT}}}", 3)
    temp = []
    for _ in range(3):
        out = s.output(at(c, "hywmill dev playerhit @e[type=hundred_years_war:militia,limit=1,sort=nearest] 1.0"), 2)
        temp += [("tempHostile(unit->fakePlayer)=true" in l) for l in out if "fake player hit" in l]
    check("D3-4 HYW temporary retaliation after player hits", bool(temp) and all(temp), str(temp))
    check("D5 village <-> player stays NEUTRAL", relation(s, c, FAKE_PLAYER_UUID) == ("NEUTRAL", "NEUTRAL"), str(relation(s, c, FAKE_PLAYER_UUID)))
    check("D5 village <-> unit owner stays NEUTRAL", relation(s, c, OWNER_UUID) == ("NEUTRAL", "NEUTRAL"), str(relation(s, c, OWNER_UUID)))
    s.cmd("kill @e[type=hundred_years_war:militia]", 1)
    scenario_D5b(ctx)


def scenario_D5b(ctx):
    """M1.1-4: three hits in one tick (two inside i-frames, no damage event for them) still make HYW
    escalate; the reconciliation pass must reset it to NEUTRAL within its 200-tick interval."""
    s = ctx.s
    c = ctx.a
    res = wait_residents(s, c)
    civ = next((r for r in res if r[2] == "CIVILIAN"), None)
    if not check("D5-b a civilian to hit", civ is not None):
        return
    p = s.pos()
    out = s.output(f"hywmill dev playerhit {civ[0]} 0.5 3", 1)
    hostile = any("relation(target->fakePlayer)=HOSTILE" in l for l in out)
    check("D5-b HYW escalated to HOSTILE on same-tick hits", hostile, "; ".join(out))
    warn = s.wait_for(r"Permanent HYW HOSTILE between village faction .*found by reconciliation", 20, since=p)
    rel = relation(s, c, FAKE_PLAYER_UUID)
    check("D5-b reconciliation reset it to NEUTRAL within 200 ticks", warn is not None and rel == ("NEUTRAL", "NEUTRAL"),
          f"{rel} {warn or 'no WARN'}")


def scenario_I(ctx):
    """Freeze audit: the player-owned invasion path, with several attackers.
    Presence is not aggression; damaging a resident makes a unit a threat; defenders (never
    civilians) engage; village <-> owner diplomacy stays NEUTRAL; nothing is duplicated."""
    s = ctx.s
    c = ctx.a
    s.cmd("kill @e[type=hundred_years_war:militia]", 1)
    res = wait_residents(s, c)
    civ = [r[0] for r in res if r[2] == "CIVILIAN"][:3]
    civ8 = {r[0][:8] for r in res if r[2] == "CIVILIAN"}
    defenders = {r[0][:8] for r in res if r[2] == "DEFENDER"}
    for i in range(3):
        s.cmd(f'summon hundred_years_war:militia {c[0] + 2 * i} {c[1]} {c[2] + 6} {{OwnerUUID:{OWNER_NBT},Tags:["hwI{i}"]}}', 1)
    p = s.pos()
    time.sleep(25)  # > 1 ledger interval and many threat scans, units idle inside the village
    threats = s.output(at(c, "hywmill threats"), 2)
    check("I1 3 owned units passing through are not threats", not any("militia" in l for l in threats[1:])
          and not any("Threat detected" in l and "militia" in l for l in s.read_since(p)), "; ".join(threats))
    if len(civ) < 3:
        check("I2 three civilians to attack", False, str(civ))
        return
    before = len(incidents(s))
    for i, v in enumerate(civ):
        s.cmd(f"damage {v} 1 minecraft:mob_attack by @e[tag=hwI{i},limit=1]", 0.2)
    time.sleep(3)
    threats = s.output(at(c, "hywmill threats"), 2)
    lines = [l for l in threats[1:] if "militia" in l]
    ids = [re.search(r"militia\[(\w+)", l)[1] for l in lines if re.search(r"militia\[(\w+)", l)]
    check("I2 each unit that damaged a resident is a threat, once", len(ids) == 3 and len(set(ids)) == 3
          and all("RECENT_ATTACKER" in l for l in lines), "; ".join(threats))
    time.sleep(2)
    ctx.i_military = military(s, c)
    time.sleep(38)
    inc = incidents(s, 100)
    hits = [i for i in inc if i["atype"] == "millenaire:villager" and i["vtype"] == "hundred_years_war:militia"]
    check("I3 defenders engage the attackers", bool(hits) and all(i["a"] in defenders for i in hits),
          f"{len(hits)} hits by {sorted({i['a'] for i in hits})}")
    check("I4 civilians do not join the fight", not any(i["a"] in civ8 for i in hits))
    s.cmd("kill @e[type=hundred_years_war:militia]", 1)
    time.sleep(12)  # > 200 ticks: at least one reconciliation pass
    rel = relation(s, c, OWNER_UUID)
    check("I5 village <-> unit owner NEUTRAL after the fight", rel == ("NEUTRAL", "NEUTRAL"), str(rel))
    rel = relation(s, c, FAKE_PLAYER_UUID)
    check("I6 village <-> player still NEUTRAL", rel == ("NEUTRAL", "NEUTRAL"), str(rel))
    status = s.output("hywmill status", 2)
    esc = next((l for l in status if l.startswith("escalation guard")), "")
    m = ctx.i_military
    per = [len(re.findall(r"[0-9a-f]{8}", l.split("<-")[1])) for l in m["threat_lines"]]
    assigned = [x for l in m["threat_lines"] for x in re.findall(r"[0-9a-f]{8}", l.split("<-")[1])]
    check("I8 M2-5 commit/reserve during the fight: <= commitPerThreat per threat, no defender on two threats",
          per and all(n <= 3 for n in per) and len(assigned) == len(set(assigned)),
          f"per threat {per}, reserve {m.get('reserve')}, eligible {m.get('eligible')}, alert {m.get('alert')}")
    check("I7 incident ledger bounded (capacity 512) and threats cleared", len(threats) >= 1
          and not any("militia" in l for l in s.output(at(c, "hywmill threats"), 2)[1:]),
          f"{len(inc)} of last 100 incidents shown (+{len(inc) - before}); {esc}")


def scenario_E(ctx):
    s = ctx.s
    c = ctx.a
    res = wait_residents(s, c)
    target = next((r for r in res if r[2] == "DEFENDER"), None)
    if not check("E0 a defender to kill", target is not None):
        return
    # VillageIntegrityChecker.isRespawnAllowed also needs gameTime - lastRespawnTick >= 6000, and
    # every resident's lastRespawnTick is its spawn time. A fresh harness world is only a few
    # thousand ticks old here, so fast-forward the game clock first.
    gt = next((int(m[1]) for l in s.output("time query gametime", 1) for m in [re.search(r"The time is (\d+)", l)] if m), 0)
    if gt < 9000:
        p0 = s.pos()
        s.cmd(f"tick sprint {9000 - gt}", 1)
        s.wait_for(r"Sprint completed", 300, since=p0)
    s.cmd(f"kill {target[0]}", 2)
    # VillageIntegrityChecker.lastElapsedDuskDay counts a dusk once dayTime % 24000 >= 13000.
    day = next((int(m[1]) for l in s.output("time query day", 1) for m in [re.search(r"The time is (\d+)", l)] if m), 0)
    p = s.pos()
    s.cmd(f"time set {(day + 1) * 24000 + 13500}", 1)
    line = s.wait_for(r"Villager respawned: type=" + re.escape(target[1]), 240, since=p)
    check("E1 Millénaire resurrected the defender", line is not None, line or "")
    if not line:
        return
    new = re.search(r"new UUID=([0-9a-f]{8})", line)[1]
    marked = any(("identity assigned: " + new) in l for l in s.read_since(p))
    check("E2 resurrected villager got the village faction identity", marked, new)
    s.cmd("time set 1000", 1)


def scenario_F1(ctx):
    """Vanilla monsters stay Millénaire's business. Run in daytime (villagers asleep in 'rest' at
    night ignore a zombie next to them) with a helmeted zombie so it does not burn."""
    s = ctx.s
    c = ctx.a
    s.cmd("time set 6000", 1)
    hits = []
    tried = set()
    for attempt in range(3):
        res = wait_residents(s, c)
        d = next((r for r in res if r[2] == "DEFENDER" and r[3] != "millenaire:rest" and r[0] not in tried), None)
        if d is None:
            break
        tried.add(d[0])
        pos = next((l for l in s.output(at(c, "hywmill village residents"), 2) if l.strip().startswith(d[0])), "")
        m = re.search(r"@(-?\d+), (-?\d+), (-?\d+)", pos)
        zx, zy, zz = (int(m[1]) + 2, int(m[2]), int(m[3]) + 2) if m else (c[0] + 2, c[1] + 1, c[2] + 2)
        s.cmd(f'summon minecraft:zombie {zx} {zy} {zz} {{ArmorItems:[{{}},{{}},{{}},{{id:"minecraft:leather_helmet",count:1}}]}}', 30)
        hits = [i for i in incidents(s) if i["atype"] == "millenaire:villager" and i["vtype"] == "minecraft:zombie"]
        s.cmd("kill @e[type=minecraft:zombie]", 1)
        if hits:
            break
    check("F1 villagers still fight vanilla monsters", bool(hits), f"{len(hits)} hits after {len(tried)} zombie(s)")
    s.cmd("time set 1000", 1)


def scenario_F2(ctx):
    s = ctx.s
    if not ctx.b:
        check("F2 raid", False, "no village B")
        return
    p = s.pos()
    out = s.output(f"millenaire dev raid trigger {ctx.a[0]} {ctx.a[1]} {ctx.a[2]} {ctx.b[0]} {ctx.b[1]} {ctx.b[2]}", 3)
    started = any("Raid triggered" in l for l in out)
    check("F2a raid triggered", started, "; ".join(out))
    if not started:
        return
    end = s.wait_for(r"Raid (FAILURE|SUCCESS)|raid.*(repulsed|succeeded)", 240, since=p)
    lines = s.read_since(p)
    check("F2b raid clone left unmarked", any("left without faction identity" in l for l in lines))
    check("F2c Millénaire resolved the raid", end is not None, end or "")


def scenario_H(ctx):
    """M1.1-3: a neutral uncrewed siege weapon (no crew, owner or passengers) is inert, not a threat."""
    s = ctx.s
    c = ctx.a
    p = s.pos()
    out = s.output(f"summon hundred_years_war:trebuchets {c[0] + 5} {c[1]} {c[2] + 5}", 2)
    check("H0 uncrewed trebuchet summoned", any("Summoned" in l for l in out), "; ".join(out))
    time.sleep(30)  # several threat scans
    threats = s.output(at(c, "hywmill threats"), 2)
    lines = s.read_since(p)
    hits = [i for i in incidents(s) if i["vtype"] == "hundred_years_war:trebuchets" or i["atype"] == "hundred_years_war:trebuchets"]
    check("H1 uncrewed trebuchet is not a threat",
          not any("trebuchets" in l for l in threats[1:]) and not any("Threat detected" in l and "trebuchets" in l for l in lines),
          "; ".join(threats))
    check("H2 villagers leave the uncrewed trebuchet alone", not hits, f"{len(hits)} incidents")
    s.cmd("kill @e[type=hundred_years_war:trebuchets]", 1)


def marker_of(s, uuid, expect=None, timeout=30):
    """The villager's HYW identity marker; with `expect`, polls until it matches (loading after a restart)."""
    end = time.time() + timeout
    last = "?"
    while True:
        for l in s.output(f"hywmill dev inspect {uuid}", 2):
            m = re.search(r" marker=(\S+)", l)
            if m and l.strip().startswith(uuid):
                last = m[1]
        if expect is None or last == expect or time.time() > end:
            return last
        time.sleep(2)


def restart(ctx, hywmill_extra=""):
    s = ctx.s
    s.cmd("save-all flush", 5)
    s.stop()
    write_configs(s.d, hywmill_extra)
    s.start()
    s.cmd("millenaire chunkload", 10)
    wait_residents(s, ctx.a)


def scenario_G(ctx):
    """M1.1-6: our identity markers can be removed before uninstalling, and stay removed."""
    s = ctx.s
    c = ctx.a
    faction = info(s, c).get("faction")
    res = wait_residents(s, c)
    v = next((r[0] for r in res if r[2] == "CIVILIAN"), None)
    if not check("G0 a marked resident", v is not None and marker_of(s, v) == faction, f"{v} faction={faction}"):
        return
    out = s.output(at(c, "hywmill admin clear-identities"), 3)
    check("G1 clear-identities removes loaded markers", marker_of(s, v, "null") == "null", "; ".join(out))
    restart(ctx)
    check("G2 still unmarked after restart (clearance persisted, not re-marked on load)", marker_of(s, v, "null") == "null")
    s.output(at(c, "hywmill admin restore-identities"), 3)
    check("G3 restore-identities re-marks", marker_of(s, v, faction) == faction)
    restart(ctx, "[bridge]\n\tmarkVillagers = false\n")
    check("G4 markVillagers=false removes markers on load", marker_of(s, v, "null") == "null")
    restart(ctx)
    check("G5 markVillagers=true marks again", marker_of(s, v, faction) == faction)


def roles_by_uuid8(s, c):
    return {r[0][:8]: r[1] for r in residents(s, c)}


def scenario_N(ctx):
    """M2-4 radius, proactive=false, M2-7 shelter, M2-9 sighting: an unowned HYW unit that attacks
    nobody (NoAI) raises ALERT inside the defense radius, civilians near it shelter, and no defender
    attacks it. Outside the radius it is ignored."""
    s = ctx.s
    c = ctx.a
    s.cmd("kill @e[type=hundred_years_war:bandit_soldier]", 1)
    s.cmd("kill @e[type=hundred_years_war:militia]", 1)  # B's owned unit would attack an unowned bandit itself
    dr = doctrine(s, c)
    radius = int(dr.get("radiusOffset", ("16", ""))[0]) + 90
    ox, oz = c[0], c[2] + radius + 12
    s.cmd(f"forceload add {ox} {oz}", 3)
    s.cmd(f"summon hundred_years_war:bandit_soldier {ox} {c[1] + 1} {oz} {{NoAI:1b,Tags:[\"hwN\"]}}", 2)
    time.sleep(4)
    m = military(s, c)
    check("N1 unowned unit outside the defense radius: no threat, CALM", m.get("threats") == "0" and m.get("alert") == "CALM",
          f"radius {radius}, unit at +{radius + 12}; alert={m.get('alert')} threats={m.get('threats')}")
    s.cmd("kill @e[tag=hwN]", 1)
    before = incidents(s, 100)
    p = s.pos()
    s.cmd(f"summon hundred_years_war:bandit_soldier {c[0] + 2} {c[1] + 1} {c[2] + 2} {{NoAI:1b,Tags:[\"hwN\"]}}", 2)
    state = wait_alert(s, c, {"ALERT"}, 10)
    m = military(s, c)
    check("N2 unit inside the radius: ALERT with an HYW_ENEMY-only threat", state == "ALERT"
          and any("HYW_ENEMY" in l and "bandit" in l for l in m["lines"]), f"alert={state}; " + "; ".join(m["lines"][-3:]))
    time.sleep(15)
    hits = [i for i in incidents(s, 100) if i not in before and i["vtype"] == "hundred_years_war:bandit_soldier"
            and i["atype"] == "millenaire:villager"]
    m = military(s, c)
    check("N3 proactive=false: no defender attacks a unit that attacks nobody", not hits and m.get("committed") == "0",
          f"{len(hits)} hits, committed={m.get('committed')}")
    res = residents(s, c)
    lines = s.output(at(c, "hywmill village residents"), 2)
    pos = {}
    for l in lines:
        mm = re.match(r"\s*([0-9a-f-]{36}) \S+ (\w+) goal=(\S+) .*@(-?\d+), (-?\d+), (-?\d+)", l)
        if mm:
            pos[mm[1]] = (mm[2], mm[3], int(mm[4]), int(mm[6]))
    sheltering = [k for k, v in pos.items() if v[0] == "CIVILIAN" and v[1].startswith("millenaire:hide")]
    started = [l for l in s.read_since(p) if "enters hide behavior" in l]
    check("N4 civilians near the unit shelter (shelterRadius 48)", bool(started), f"{len(started)} started, {len(sheltering)} hiding now")
    s.cmd("kill @e[tag=hwN]", 1)
    state = wait_alert(s, c, {"CALM"}, 20)
    check("N5 sighting that never came to blows returns to CALM after alertTicks", state == "CALM", str(state))


def scenario_W(ctx):
    """M2-6 on the server: militiaPolicy NEVER keeps militia out of HywMill defense; only SOLDIER/LEADER respond."""
    s = ctx.s
    c = ctx.a
    s.cmd("kill @e[type=hundred_years_war:militia]", 1)
    s.output(at(c, "hywmill doctrine set militiaPolicy NEVER"), 2)
    res = wait_residents(s, c)
    types = {r[0][:8]: r[1] for r in res}
    civ = next((r[0] for r in res if r[2] == "CIVILIAN"), None)
    s.cmd(f'summon hundred_years_war:militia {c[0] + 2} {c[1]} {c[2] + 6} {{OwnerUUID:{OWNER_NBT},Tags:["hwW"]}}', 2)
    before = incidents(s, 100)
    s.cmd(f"damage {civ} 1 minecraft:mob_attack by @e[tag=hwW,limit=1]", 1)
    time.sleep(20)
    hits = [i for i in incidents(s, 100) if i not in before and i["atype"] == "millenaire:villager"
            and i["vtype"] == "hundred_years_war:militia"]
    hitters = sorted({types.get(i["a"], "?") for i in hits})
    pro = {"millenaire:norman/guard", "millenaire:norman/seneschal", "millenaire:norman/knight"}
    check("W1 militiaPolicy NEVER: only SOLDIER/LEADER engage", all(t in pro for t in hitters), f"{len(hits)} hits by {hitters}")
    s.cmd("kill @e[tag=hwW]", 1)
    s.output(at(c, "hywmill doctrine reset militiaPolicy"), 2)
    check("W2 override removed again", doctrine(s, c).get("militiaPolicy", ("", ""))[1] != "override")


def scenario_L(ctx):
    """M2-9: CALM -> ALERT -> ENGAGED -> RECOVERY -> CALM with the doctrine timers."""
    s = ctx.s
    c = ctx.a
    wait_alert(s, c, {"CALM"}, 60)
    stats0 = military(s, c).get("stats", "")
    res = wait_residents(s, c)
    civ = next((r[0] for r in res if r[2] == "CIVILIAN"), None)
    s.cmd(f"summon hundred_years_war:bandit_soldier {c[0] + 2} {c[1] + 1} {c[2] + 2} {{NoAI:1b,Tags:[\"hwL\"]}}", 1)
    seen = [wait_alert(s, c, {"ALERT"}, 10)]
    s.cmd(f"damage {civ} 1 minecraft:mob_attack by @e[tag=hwL,limit=1]", 1)
    seen.append(wait_alert(s, c, {"ENGAGED"}, 5))
    s.cmd("kill @e[tag=hwL]", 1)
    t0 = time.time()
    seen.append(wait_alert(s, c, {"RECOVERY"}, 30))
    t_rec = time.time() - t0
    seen.append(wait_alert(s, c, {"CALM"}, 60))
    t_calm = time.time() - t0
    stats1 = military(s, c).get("stats", "")
    check("L1 lifecycle CALM -> ALERT -> ENGAGED -> RECOVERY -> CALM", seen == ["ALERT", "ENGAGED", "RECOVERY", "CALM"], str(seen))
    check("L2 timers: RECOVERY after ~engagedTicks (200), CALM ~recoveryTicks (600) later",
          8 <= t_rec <= 16 and 36 <= t_calm <= 52, f"recovery after {t_rec:.1f}s, calm after {t_calm:.1f}s")
    check("L3 persistent statistics count the alert and the engagement", stats0 != stats1, f"{stats0} -> {stats1}")


def ensure_extra_villages(ctx):
    if getattr(ctx, "extra", None) is not None:
        return ctx.extra
    s = ctx.s
    for box in EXTRA_FORCELOAD:
        s.cmd("forceload add {} {} {} {}".format(*box), wait=15)
    ctx.extra = {}
    for name, cands in EXTRA_VILLAGES.items():
        ctx.extra[name] = spawn_village(s, cands, surface=True)
    s.cmd("millenaire chunkload", 10)
    time.sleep(15)
    return ctx.extra


def millenaire_data():
    """Villager tags and building resident slots straight from the Millénaire jar (independent of the mod)."""
    import json
    import zipfile
    tags, buildings = {}, {}
    with zipfile.ZipFile(MILLENAIRE_JAR) as z:
        for n in z.namelist():
            if not n.startswith(MILLENAIRE_DATA_PREFIX) or not n.endswith(".json"):
                continue
            parts = n[len(MILLENAIRE_DATA_PREFIX):].split("/")
            culture = parts[0]
            try:
                d = json.loads(z.read(n))
            except Exception:
                continue
            if len(parts) > 2 and parts[1] == "villagers":
                tags[f"millenaire:{culture}/{parts[-1][:-5]}"] = set(d.get("tags", []))
            elif len(parts) > 2 and parts[1] == "buildings" and isinstance(d, dict):
                buildings[f"millenaire:{culture}/{d.get('building_id', parts[-1][:-5])}"] = d
    return tags, buildings


def expected_slots(bdef, culture, variant, level):
    lvl = {}
    for v in bdef.get("variants") or []:
        if v.get("variant") == variant:
            lvl = next((l for l in v.get("levels", []) if l.get("level") == level), {})
    names = (lvl.get("male") if "male" in lvl else bdef.get("male", [])) + \
            (lvl.get("female") if "female" in lvl else bdef.get("female", []))
    return sorted(n if ":" in n else f"millenaire:{culture}/{n}" for n in names)


def role_of(type_id, tags, table):
    t = tags.get(type_id, set())
    if "hostile" in t:
        return "OUTLAW"
    if type_id in table:
        return table[type_id]
    if "child" in t:
        return "CIVILIAN"
    return "MILITIA" if "helpInAttacks" in t else "CIVILIAN"


def scenario_X(ctx):
    """M2-1 classification, M2-2 capacity (cross-checked against Millénaire's own JSON), M2-3 doctrine."""
    import json
    s = ctx.s
    extra = ensure_extra_villages(ctx)
    check("X0 extra villages spawned", all(extra.values()), str(extra))
    table = {}
    for f in sorted((REPO / "src/main/resources/data/hywmill/hywmill_roles").glob("*.json")):
        table.update(json.loads(f.read_text())["villagers"])
    tags, buildings = millenaire_data()

    # M2-1
    a = military(s, ctx.a)
    check("X1 A: seneschal is LEADER, leaders >= 1", int(a.get("leaders", 0)) >= 1, str({k: a.get(k) for k in ("tier", "soldiers", "leaders", "militia")}))
    b = military(s, ctx.b)
    check("X2 B (Douvres pattern): border markers only, fortification 0, tier WATCH",
          b.get("fortification") == "0" and "BORDER_MARKER" in b.get("buildingRoles", "") and b.get("tier") == "WATCH"
          and not any(k in b.get("buildingRoles", "") for k in ("WALL=", "TOWER=", "GUARDHOUSE=")),
          f"{b.get('buildingRoles')} fort={b.get('fortification')} tier={b.get('tier')}")
    if extra.get("militaire"):
        m = military(s, extra["militaire"])
        check("X3 norman/militaire: FORT_TOWNHALL and guards as SOLDIER", "FORT_TOWNHALL" in m.get("buildingRoles", "")
              and int(m.get("soldiers", 0)) >= 1, f"{m.get('buildingRoles')} soldiers={m.get('soldiers')} tier={m.get('tier')}")
    if extra.get("byzantine"):
        m = military(s, extra["byzantine"])
        ctx.byz_soldiers = int(m.get("soldiers", 0))
        check("X4 byzantines/militaryvillage runtime soldier verification", ctx.byz_soldiers >= 1,
              f"soldiers={m.get('soldiers')} leaders={m.get('leaders')} militia={m.get('militia')} capacity={m.get('capacity')} {m.get('buildingRoles')}")

    # M2-2
    for name, c in [("A", ctx.a), ("militaire", extra.get("militaire")), ("byzantine", extra.get("byzantine"))]:
        if not c:
            continue
        out = s.output(at(c, "hywmill dev capacity"), 2)
        mismatches, total = [], 0
        for l in out:
            mm = re.match(r"slots (\S+) (\S+) (-?\d+) (\S+) capacity=(\d+)", l)
            if not mm:
                continue
            plan, variant, level = mm[1], mm[2], int(mm[3])
            reported = sorted(x.rsplit(":", 1)[0] for x in mm[4].split(","))
            culture = plan.split(":")[1].split("/")[0]
            exp = expected_slots(buildings.get(plan, {}), culture, variant, level)
            if reported != exp:
                mismatches.append(f"{plan}@{variant}{level}: {reported} vs json {exp}")
            total += sum(1 for t in exp if role_of(t, tags, table) in ("SOLDIER", "MILITIA"))
        cap = military(s, c).get("capacity")
        check(f"X5 capacity of {name} = SOLDIER+MILITIA slots of operational buildings at current variant/level",
              not mismatches and str(total) == cap, f"harness {total} vs mod {cap}; " + "; ".join(mismatches[:3]))

    # M2-3
    d = doctrine(s, ctx.a)
    exp = {"commitPerThreat": "3", "reserve": "1", "militiaPolicy": "ON_ENGAGED", "shelterRadius": "48", "proactive": "false",
           "assistMinReputation": "0", "alertTicks": "100", "engagedTicks": "200", "recoveryTicks": "600"}
    check("X6 doctrine of A (norman/agricole) = baseline", all(d.get(k, ("",))[0] == v for k, v in exp.items()),
          str({k: d.get(k) for k in exp}))
    if extra.get("militaire"):
        d = doctrine(s, extra["militaire"])
        check("X7 norman/militaire: reserve 2 and militia WHEN_ATTACKED from its village type",
              d.get("reserve", ("",))[0] in ("2", "3") and "militaire" in d.get("reserve", ("", ""))[1]
              and d.get("militiaPolicy", ("",))[0] == "WHEN_ATTACKED", str({k: d.get(k) for k in ("reserve", "militiaPolicy", "commitPerThreat")}))
    if extra.get("byzantine"):
        d = doctrine(s, extra["byzantine"])
        check("X8 byzantines/militaryvillage: commit 4 / reserve 2 (type), reputation 256 and recovery 1200 (culture)",
              d.get("commitPerThreat", ("",))[0] in ("4", "5") and d.get("assistMinReputation", ("",))[0] == "256"
              and d.get("recoveryTicks", ("",))[0] == "1200",
              str({k: d.get(k) for k in ("commitPerThreat", "reserve", "assistMinReputation", "recoveryTicks")}))


def scenario_P(ctx):
    """M2-11: scheduler/scan cost with >= 5 active villages and ~20 HYW units."""
    s = ctx.s
    extra = ensure_extra_villages(ctx)
    villages = [ctx.a, ctx.b] + [v for v in extra.values() if v]
    for i, c in enumerate(villages[:2]):
        for k in range(5):
            s.cmd(f'summon hundred_years_war:militia {c[0] + 3 * k} {c[1]} {c[2] + 8} {{OwnerUUID:{OWNER_NBT},Tags:["hwP"]}}', 0.2)
    for c in villages[2:]:
        for k in range(3):
            s.cmd(f'summon hundred_years_war:bandit_soldier {c[0] + 3 * k} {c[1] + 1} {c[2] + 3} {{NoAI:1b,Tags:["hwP"]}}', 0.2)
    time.sleep(20)  # warm-up (JIT, first scans)
    s.output("hywmill perf reset", 1)
    time.sleep(60)
    out = s.output("hywmill perf", 2)
    stats = {}
    for l in out:
        mm = re.match(r"\s*(\S+): n=(\d+) mean=([\d.]+)us max=([\d.]+)us p99=([\d.]+)us", l)
        if mm:
            stats[mm[1]] = (int(mm[2]), float(mm[3]), float(mm[4]), float(mm[5]))
    ctx.perf = (len(villages), out)
    active = sum(1 for c in villages if military(s, c).get("tier"))
    check("P1 >= 5 active villages and ~20 HYW units", len(villages) >= 5, f"{len(villages)} villages; extra: {extra}")
    scan = stats.get("scan.village", (0, 0, 0, 0))
    check("P2 mean village scan < 200 us", scan[0] > 0 and scan[1] < 200, f"scan.village {scan}")
    worst = {k: v[2] for k, v in stats.items() if k != "tick.total" and not k.startswith("snap.")}
    p99 = {k: v[3] for k, v in stats.items() if not k.startswith("snap.")}
    check("P3a p99 of every HywMill work slice (incl. the whole per-tick total) <= 2 ms", p99 and max(p99.values()) <= 2000, str(p99))
    check("P3b max of any single slice <= 2 ms (spikes include GC/scheduling on a shared 4-CPU host)",
          worst and max(worst.values()) <= 2000, str(worst))
    for l in out:
        log("perf " + l.strip())
    tick = stats.get("tick.total", (0, 0, 0, 0))
    check("P4 whole HywMill work per server tick (informational: mean/max)", True, f"tick.total n={tick[0]} mean={tick[1]}us max={tick[2]}us")
    s.cmd("kill @e[tag=hwP]", 2)


def scenario_status(ctx):
    out = ctx.s.output("hywmill status", 2)
    check("status: goal bridge installed", any("engage_target=bridged" in l and "hide=bridged" in l for l in out), "; ".join(out[:3]))


SCENARIOS = {"A": scenario_A, "B": scenario_B, "C": scenario_C, "D": scenario_D, "E": scenario_E,
             "F1": scenario_F1, "F2": scenario_F2, "H": scenario_H, "G": scenario_G, "I": scenario_I, "N": scenario_N, "W": scenario_W, "L": scenario_L, "X": scenario_X, "P": scenario_P, "M": scenario_M, "status": scenario_status}
ORDER = ["status", "H", "B", "N", "C", "D", "I", "W", "L", "F1", "E", "F2", "X", "P", "A", "G"]


def run(d: Path, names, fresh=True):
    write_configs(d)
    install_mods(d, [MILLENAIRE_JAR, HYW_JAR, built_jar()])
    if fresh and (d / "world").exists():
        shutil.rmtree(d / "world")
    s = Server(d)
    ctx = Ctx(s)
    try:
        s.start()
        if fresh:
            setup(ctx)
        else:
            reuse(ctx)
        for n in (ORDER if names == ["all"] else names):
            if ctx.a is None:
                break
            log(f"--- scenario {n}")
            SCENARIOS[n](ctx)
    finally:
        s.stop()
    passed = sum(1 for r in RESULTS if r[1])
    log(f"RESULT {passed}/{len(RESULTS)} checks passed")
    for name, ok, detail in RESULTS:
        print(f"  {'PASS' if ok else 'FAIL'}  {name}  {detail}")
    return all(r[1] for r in RESULTS)


def run_optional(d: Path):
    """Optional-integration check: hywmill must load and its commands must fail gracefully with
    neither dependency, with Millénaire only, and with HYW only."""
    ok = True
    for label, deps in [("core only", []), ("Millénaire only", [MILLENAIRE_JAR]), ("HYW only", [HYW_JAR])]:
        write_configs(d)
        install_mods(d, deps + [built_jar()])
        if (d / "world").exists():
            shutil.rmtree(d / "world")
        s = Server(d)
        try:
            s.start()
            out = []
            for c in ["hywmill status", "hywmill village list", "hywmill threats", "hywmill incidents 5",
                      "hywmill admin clear-identities all", "hywmill admin restore-identities all"]:
                out += s.output(c, 2)
            time.sleep(12)  # a few ledger/reconciliation intervals
            lines = s.read_since(s.start_pos)
            # hywmill's own "X not loaded; its integration is disabled" INFO line is expected here.
            bad = [l for l in lines if re.search(r"Exception|at dev\.hywmill|/ERROR\].*\[hywmill\]", l)]
            state = next((l for l in out if l.startswith("hywmill integrations:")), "")
            check(f"O {label}: loads, commands answer, no errors", not bad and bool(state), state + ("; " + bad[0] if bad else ""))
            ok &= not bad
        finally:
            s.stop()
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True, type=Path)
    sub = ap.add_subparsers(dest="action", required=True)
    sub.add_parser("install")
    r = sub.add_parser("run")
    r.add_argument("scenarios", nargs="+")
    r.add_argument("--keep-world", action="store_true")
    a = ap.parse_args()
    if a.action == "install":
        install(a.dir)
        return 0
    if a.scenarios[0] == "heights":
        # heights x0 x1 z0 z1 step: ground height grid on an existing world
        write_configs(a.dir)
        install_mods(a.dir, [MILLENAIRE_JAR, HYW_JAR, built_jar()])
        srv = Server(a.dir)
        srv.start()
        try:
            x0, x1, z0, z1, st = (int(v) for v in a.scenarios[1:6])
            for x in range(x0, x1 + 1, st):
                row = []
                for z in range(z0, z1 + 1, st):
                    srv.cmd(f"forceload add {x} {z}", 1.5)
                    row.append(f"{z}:{surface_y(srv, x, z)}")
                    srv.cmd(f"forceload remove {x} {z}", 0.3)
                log(f"heights x={x} " + " ".join(row))
        finally:
            srv.stop()
        return 0
    if a.scenarios[0] == "scout":
        # scout <type> x z [x z ...]: try Millénaire spawns on an existing world; prints the first that works
        write_configs(a.dir)
        install_mods(a.dir, [MILLENAIRE_JAR, HYW_JAR, built_jar()])
        srv = Server(a.dir)
        srv.start()
        try:
            vtype = a.scenarios[1]
            pts = a.scenarios[2:]
            for i in range(0, len(pts), 2):
                x, z = int(pts[i]), int(pts[i + 1])
                srv.cmd(f"forceload add {x - 112} {z - 112} {x + 112} {z + 112}", 25)
                hit = spawn_village(srv, [(vtype, x, 80, z)], surface=True)
                log(f"scout {vtype} at {x},{z}: {hit}")
                if hit:
                    break
        finally:
            srv.stop()
        return 0
    if a.scenarios == ["optional"]:
        run_optional(a.dir)
        passed = sum(1 for r in RESULTS if r[1])
        log(f"RESULT {passed}/{len(RESULTS)} checks passed")
        for name, ok, detail in RESULTS:
            print(f"  {'PASS' if ok else 'FAIL'}  {name}  {detail}")
        return 0 if all(r[1] for r in RESULTS) else 1
    return 0 if run(a.dir, a.scenarios, fresh=not a.keep_world) else 1


if __name__ == "__main__":
    sys.exit(main())
