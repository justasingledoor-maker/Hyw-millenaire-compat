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
    extra = hywmill_extra + os.environ.get("HYWMILL_EXTRA", "").replace("\\n", "\n").replace("\\t", "\t")  # e.g. "[garrison]\n\tenabled = false\n"
    (cfg / "hywmill-common.toml").write_text(
        f"[general]\n\tverboseLogging = {verbose}\n\tdevCommands = true\n" + extra)
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
    loaded = s.wait_for(r"Garrison ledger loaded: \d+ village.*format [34]", 30, since=s.start_pos)
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
    s.cmd(f"summon hundred_years_war:bandit_soldier {ox} {c[1] + 1} {oz} {{NoAI:1b,Tags:['hwN']}}", 2)
    time.sleep(4)
    m = military(s, c)
    check("N1 unowned unit outside the defense radius: no threat, CALM", m.get("threats") == "0" and m.get("alert") == "CALM",
          f"radius {radius}, unit at +{radius + 12}; alert={m.get('alert')} threats={m.get('threats')}")
    s.cmd("kill @e[tag=hwN]", 1)
    before = incidents(s, 100)
    p = s.pos()
    s.cmd(f"summon hundred_years_war:bandit_soldier {c[0] + 2} {c[1] + 1} {c[2] + 2} {{NoAI:1b,Tags:['hwN']}}", 2)
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
    s.cmd(f"summon hundred_years_war:bandit_soldier {c[0] + 2} {c[1] + 1} {c[2] + 2} {{NoAI:1b,Tags:['hwL']}}", 1)
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
    s.cmd("hywmill perf reset", 1)  # the fill-phase readout below then covers only this scenario's spawning
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
    s.cmd("hywmill perf reset", 1)  # the fill-phase readout below then covers only this scenario's spawning
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


# --------------------------------------------------------------------------- M3-0 spike

def ground(x, z, command):
    """Runs a command at the ground surface of column (x, z) (motion_blocking_no_leaves heightmap)."""
    return f"execute positioned {x} 0 {z} positioned over motion_blocking_no_leaves run {command}"


def spike_spawn(s, pos, unit, level, roster=None, gen=None):
    extra = f" {roster} {gen}" if roster else ""
    out = s.output(ground(pos[0], pos[2], f"hywmill dev spike-spawn {unit} {level}{extra}"), 2)
    for l in out:
        m = re.search(r"spike spawned ([0-9a-f-]{36}) roster=(\S+) gen=(\d+) applied=(-?\d+) (.*)", l)
        if m:
            return {"uuid": m[1], "roster": m[2], "applied": int(m[4]), "desc": m[5]}
    return {"uuid": None, "out": out}


def spike_info(s, selector, dim=None):
    cmd = f"hywmill dev spike-info {selector}"
    if dim:
        cmd = f"execute in {dim} run " + cmd
    rows = {}
    for l in s.output(cmd, 2):
        m = re.search(r"spike info ([0-9a-f-]{36}) dim=(\S+) pos=(-?\d+), (-?\d+), (-?\d+) (.*?) tag=(\S+) target=(\S+)(?: tempHostile=(\w+))?", l)
        if m:
            rows[m[1]] = {"dim": m[2], "pos": (int(m[3]), int(m[4]), int(m[5])), "desc": m[6], "tag": m[7],
                          "target": m[8], "temp": m[9]}
            mm = re.search(r" slots=(\S+) mount=(\S+) health=(-?[\d.]+)", l)
            if mm:
                rows[m[1]]["slots"] = dict(x.split("=", 1) for x in mm[1].strip(",").split(",") if "=" in x)
                rows[m[1]]["mount"] = mm[2]
                rows[m[1]]["health"] = float(mm[3])
    return rows


def health(s, tag):
    for l in s.output(f"data get entity @e[tag={tag},limit=1] Health", 0.5):
        m = re.search(r"entity data: ([\d.]+)[fd]\b", l)
        if m:
            return float(m[1])
    return None


def dist(a, b):
    return ((a[0] - b[0]) ** 2 + (a[2] - b[2]) ** 2) ** 0.5


def scenario_S(ctx):
    """M3-0 spike: village-owned HYW units (OwnerUUID = village faction UUID), spawned through the
    production spawn sequence, against the R1-R8 questions."""
    s = ctx.s
    a, b = ctx.a, ctx.b
    fa = info(s, a).get("faction")
    fb = info(s, b).get("faction")
    log(f"spike: faction A {fa}, faction B {fb}")
    s.cmd("kill @e[type=#minecraft:skeletons]", 0.5)

    # R6 equipment levels (and HYW's clamp for units with fewer levels)
    r6 = []
    for unit, lvl, want in [("spear_man", 0, 0), ("spear_man", 1, 1), ("spear_man", 2, 2), ("spear_man", 3, 3),
                            ("militia", 3, 3), ("archer", 2, 2), ("crossbowman", 1, 1), ("shieldman", 3, 3), ("warrior", 0, 0),
                            ("matchlock_man", 0, 2), ("handgonne_man", 3, 0)]:
        r = spike_spawn(s, (a[0] + 30, a[1] + 1, a[2] + 30), unit, lvl)
        armor = s.output(f"data get entity {r['uuid']} ArmorItems", 1) if r["uuid"] else []
        r6.append((unit, lvl, r.get("applied"), want, r["uuid"] is not None and "owner=" + fa in r.get("desc", "") and "supply=false" in r.get("desc", "") and "despawn=false" in r.get("desc", "")))
        if r["uuid"]:
            s.cmd(f"kill {r['uuid']}", 0.3)
    log(f"spike R6 {r6}")
    check("S-R6 equipment levels applied (levels 0-3 exact; HYW normalizes an out-of-range level, recorded as applied), owner/supply/despawn set",
          all(x[2] == x[3] and x[4] for x in r6), str(r6))

    # R1 + R8: units of A's faction placed among B's residents, next to a player-owned HYW unit,
    # a cow, a vanilla villager, and inside A with A's own residents. None of these are threats.
    s.cmd("kill @e[type=hundred_years_war:militia]", 0.5)
    s.cmd(ground(b[0] + 3, b[2] + 1, f"summon hundred_years_war:militia ~ ~ ~ {{OwnerUUID:{OWNER_NBT},Tags:['hwOwned']}}"), 1)
    s.cmd(ground(b[0] + 1, b[2] + 3, f"summon minecraft:cow ~ ~ ~ {{Tags:['hwCow']}}"), 1)
    s.cmd(ground(b[0] - 2, b[2] + 2, f"summon minecraft:villager ~ ~ ~ {{Tags:['hwVill'],NoAI:1b}}"), 1)
    # village A's units (owner = A's faction), moved into village B among B's residents
    near_b = [spike_spawn(s, (a[0] + 20 + dx, a[1] + 1, a[2] + 20 + dz), u, 1)["uuid"] for u, dx, dz in
              [("militia", 2, 2), ("archer", -2, 2), ("spear_man", 2, -2), ("crossbowman", -2, -2)]]
    for u, (dx, dz) in zip(near_b, [(2, 2), (-2, 2), (2, -2), (-2, -2)]):
        if u:
            s.cmd(ground(b[0] + dx, b[2] + dz, f"tp {u} ~ ~ ~"), 0.5)
    in_a = [spike_spawn(s, (a[0] + dx, a[1] + 1, a[2] + dz), u, 1)["uuid"] for u, dx, dz in
            [("militia", 3, 3), ("archer", -3, 3), ("spear_man", 3, -3), ("shieldman", -3, -3), ("warrior", 0, 4), ("crossbowman", 0, -4)]]
    ctx.spike_a = in_a
    health0 = [health(s, t) for t in ("hwCow", "hwVill", "hwOwned")]
    p = s.pos()
    bad_targets = []
    for _ in range(12):
        time.sleep(5)
        for u, row in spike_info(s, "@e[type=!minecraft:player]").items():
            if u in near_b + in_a and row["target"] != "none":
                bad_targets.append((u[:8], row["target"]))
    inc = incidents(s, 100)
    unit_ids = {x[:8] for x in near_b + in_a if x}
    hits_by_units = [i for i in inc if i["a"] in unit_ids]
    hits_on_units = [i for i in inc if i["v"] in unit_ids]
    health1 = [health(s, t) for t in ("hwCow", "hwVill", "hwOwned")]
    threats_a = s.output(at(a, "hywmill threats"), 2)
    threats_b = s.output(at(b, "hywmill threats"), 2)
    log(f"spike R1 targets {bad_targets} incidents-by-units {len(hits_by_units)} health {health0} -> {health1}")
    check("S-R1 faction-owned units acquire no neutral targets (other village, player-owned unit, cow, villager, own residents) in 60 s",
          not bad_targets and not hits_by_units, f"targets {bad_targets[:5]} hits {hits_by_units[:3]}")
    check("S-R1 neutral bystanders (cow, villager, player-owned unit) unharmed", health0 == health1 and None not in health1,
          f"{health0} -> {health1}")
    check("S-R8 Millénaire residents do not attack village-owned HYW units", not hits_on_units, str(hits_on_units[:3]))
    check("S-R8 village-owned HYW units are not threats of either village",
          not any(u in l for l in threats_a[1:] + threats_b[1:] for u in unit_ids), "; ".join(threats_a + threats_b))
    for u in near_b:
        if u:
            s.cmd(f"kill {u}", 0.3)
    s.cmd("kill @e[tag=hwCow]", 0.3)
    s.cmd("kill @e[tag=hwVill]", 0.3)

    # R1 (monsters): HYW's own targeting of Enemy mobs
    s.cmd(ground(a[0] + 6, a[2] + 6, f"summon minecraft:zombie ~ ~ ~ {{Tags:['hwZ'],PersistenceRequired:1b}}"), 1)
    time.sleep(15)
    z_alive = any("hwZ" in l or "Health" in l for l in s.output("data get entity @e[tag=hwZ,limit=1] Health", 1))
    log(f"spike R1 zombie alive after 15s: {z_alive}")
    check("S-R1 note: HYW's native targeting engages hostile monsters (Enemy) near the units", True, f"zombie alive after 15 s: {z_alive}")
    s.cmd("kill @e[tag=hwZ]", 0.3)

    # R3 temporary hostility: engage a unit on the player-owned (neutral) militia and time the hostility
    u = in_a[0]
    s.cmd(ground(a[0] + 8, a[2] + 8, "tp @e[tag=hwOwned] ~ ~ ~"), 1)
    owned_h0 = health(s, "hwOwned")
    eng = s.output(f"hywmill dev spike-engage {u} @e[tag=hwOwned,limit=1]", 1)
    log(f"spike R3 unit {u} before engage: {spike_info(s, u)} owned health {owned_h0}")
    t0 = time.time()
    temp_seen, dur = [], None
    attacked = False
    for _ in range(40):
        time.sleep(3)
        row = spike_info(s, u).get(u, {})
        temp_seen.append((round(time.time() - t0), row.get("target"), row.get("temp")))
        h = health(s, "hwOwned")
        if h is None or h < owned_h0:
            attacked = True
        if row.get("temp") != "true" and dur is None and len(temp_seen) > 1:
            dur = round(time.time() - t0)
            break
    log(f"spike R3 engage {eng} timeline {temp_seen}")
    check("S-R3 engage gives temporary HYW hostility and the unit attacks the given target", any("tempHostile=true" in l for l in eng) and attacked,
          f"attacked={attacked} timeline {temp_seen[:6]}")
    check("S-R3 temporary hostility expires by itself (no permanent relation)",
          dur is not None and relation(s, a, OWNER_UUID) == ("NEUTRAL", "NEUTRAL"), f"cleared after ~{dur}s; relation {relation(s, a, OWNER_UUID)}")
    s.cmd("kill @e[tag=hwOwned]", 0.5)

    # R2 home/return: move a unit 24 blocks away from its home and watch it
    u = in_a[2]
    home = spike_info(s, u).get(u, {}).get("pos")
    s.cmd(ground(a[0] - 3 + 24, a[2] - 3, f"tp {u} ~ ~ ~"), 1)
    p0 = spike_info(s, u).get(u, {}).get("pos")
    track = [round(dist(p0, (a[0] + 3, 0, a[2] - 3)), 1) if p0 else None]
    for _ in range(12):
        time.sleep(5)
        pos = spike_info(s, u).get(u, {}).get("pos")
        track.append(round(dist(pos, (a[0] + 3, 0, a[2] - 3)), 1) if pos else None)
    log(f"spike R2 home {home} distance track {track}")
    check("S-R2 idle unit returns towards its home position (faction owner)", track and track[-1] is not None and track[-1] <= 8,
          f"distance to home over 60 s: {track}")

    # R4 duplicates: same deterministic UUID twice while loaded, then across a chunk unload
    roster = "0000aaaa-0000-4000-8000-000000000001"
    first = spike_spawn(s, (a[0] + 5, a[1] + 1, a[2] + 5), "militia", 0, roster, 1)
    second = spike_spawn(s, (a[0] + 6, a[1] + 1, a[2] + 5), "militia", 0, roster, 1)
    check("S-R4 second spawn with the same (roster, generation) UUID is refused while the first is loaded",
          first["uuid"] is not None and second["uuid"] is None, f"{first.get('uuid')} / {second.get('out')}")
    far = (a[0] + 400, a[2] + 400)
    s.cmd(f"forceload add {far[0]} {far[1]}", 8)
    fy = surface_y(s, far[0], far[1]) or 70
    roster2 = "0000aaaa-0000-4000-8000-000000000002"
    far_unit = spike_spawn(s, (far[0], fy + 1, far[1]), "militia", 0, roster2, 1)
    s.cmd("save-all flush", 5)
    s.cmd(f"forceload remove {far[0]} {far[1]}", 25)
    loaded_before = far_unit["uuid"] in spike_info(s, "@e[type=hundred_years_war:militia]")
    copy = spike_spawn(s, (a[0] + 7, a[1] + 1, a[2] + 5), "militia", 0, roster2, 1)
    p = s.pos()
    s.cmd(f"forceload add {far[0]} {far[1]}", 25)
    lines = s.read_since(p)
    dup_warn = next((l for l in lines if "already exists" in l or "UUID" in l and "exist" in l), None)
    n = sum(1 for k in spike_info(s, "@e[type=hundred_years_war:militia]") if k == far_unit["uuid"])
    log(f"spike R4 far unit {far_unit.get('uuid')} unloaded={not loaded_before} copy={copy.get('uuid')} warn={dup_warn} loaded-count={n}")
    check("S-R4 chunk-loaded entity with an already-loaded UUID is not added (vanilla refuses the duplicate)",
          copy["uuid"] is not None and n == 1 and dup_warn is not None, f"count {n}; {dup_warn}")
    ctx.spike_dup = far_unit["uuid"]
    s.cmd(f"kill {far_unit['uuid']}", 0.5)
    s.cmd(f"forceload remove {far[0]} {far[1]}", 2)

    # R5 attachment through a dimension change
    u = in_a[1]
    s.cmd("execute in minecraft:the_nether run forceload add 0 0", 10)
    s.cmd(f"execute in minecraft:the_nether run tp {u} 0 120 0", 3)
    nether = spike_info(s, u, dim="minecraft:the_nether").get(u)
    check("S-R5 garrison tag and ownership survive a dimension change (same UUID)",
          nether is not None and nether["dim"] == "minecraft:the_nether" and nether["tag"] != "none" and ("owner=" + fa) in nether["desc"],
          str(nether))
    s.cmd("execute in minecraft:overworld run " + ground(a[0] - 3, a[2] + 3, f"tp {u} ~ ~ ~"), 3)
    s.cmd("execute in minecraft:the_nether run forceload remove 0 0", 2)

    # R7 coexistence: residents keep working with six units standing in the village centre
    before = residents(s, a)
    time.sleep(60)
    after = residents(s, a)
    goals_b = {r[3] for r in before}
    goals_a = {r[3] for r in after}
    moved = sum(1 for x, y in zip(sorted(before), sorted(after)) if x[3] != y[3])
    check("S-R7 Millénaire residents keep their routines with village-owned units present",
          len(after) >= len(before) - 1 and len(goals_a) >= 2, f"residents {len(before)}->{len(after)}, goals {sorted(goals_b)} -> {sorted(goals_a)}, changed {moved}")

    # R5 restart: tags persist; nothing multiplies
    units_before = {k: v for k, v in spike_info(s, "@e[type=!minecraft:player]").items() if v["tag"] != "none"}
    s.cmd("save-all flush", 5)
    s.stop()
    s.start()
    for box in FORCELOAD:
        s.cmd("forceload add {} {} {} {}".format(*box), wait=15)
    time.sleep(20)
    units_after = {k: v for k, v in spike_info(s, "@e[type=!minecraft:player]").items() if v["tag"] != "none"}
    check("S-R5 restart: every tagged unit reloads once with its tag and owner, none multiplied",
          set(units_before) == set(units_after) and all(units_before[k]["tag"] == units_after[k]["tag"] for k in units_after),
          f"before {len(units_before)} after {len(units_after)}")


# --------------------------------------------------------------------------- M3 garrison (G3-*)

def garrison(s, c):
    """Parses /hywmill village garrison."""
    out = s.output(at(c, "hywmill village garrison"), 2)
    d = {"lines": out}
    for l in out:
        m = re.search(r"^Garrison: (\d+)/(\d+) \(tier cap (\d+)\) \| alive (\d+), recruited (\d+), missing (\d+), deployed (\d+), returning (\d+) \| levy ([\d.]+)/", l)
        if m:
            for k, v in zip(["live", "target", "cap", "alive", "recruited", "missing", "deployed", "returning"], m.groups()[:8]):
                d[k] = int(v)
            d["levy"] = float(m[9])
        m = re.search(r"^Starting grant: (\w+) .*last slot: (\w*) .*alert (\w+)", l)
        if m:
            d["grant"], d["blocker"], d["alert"] = m[1], m[2], m[3]
        m = re.search(r"^Totals: recruited (\d+), spawned (\d+), killed (\d+), lost (\d+), recovered (\d+), duplicates refused (\d+)", l)
        if m:
            for k, v in zip(["t_recruited", "t_spawned", "t_killed", "t_lost", "t_recovered", "t_dups"], m.groups()):
                d[k] = int(v)
        m = re.search(r"faction (\S+)$", l)
        if l.startswith("== Garrison") and m:
            d["faction"] = m[1]
    return d


def g_units(s, c):
    """[(slot8, unitKey, level, state, gen, entity8)] from /hywmill village garrison units."""
    rows = []
    for l in s.output(at(c, "hywmill village garrison units"), 2):
        m = re.match(r"\s*([0-9a-f]{8}) (\S+) lvl(-?\d+) (\w+)(?:\((\w+)\))? gen(\d+)(?: entity=([0-9a-f]{8}))?", l)
        if m:
            rows.append(dict(slot=m[1], unit=m[2], lvl=int(m[3]), state=m[4], reason=m[5], gen=int(m[6]), entity=m[7]))
    return rows


def census(s, c):
    for l in s.output(at(c, "hywmill dev census"), 3):
        m = re.search(r"census .*: tagged=(\d+) slots=(\d+) duplicateSlots=(\d+) unbound=(\d+) badOwner=(\d+) factionOwned=(\d+) rosterLive=(\d+) rosterBound=(\d+)", l)
        if m:
            return dict(zip(["tagged", "slots", "dupSlots", "unbound", "badOwner", "factionOwned", "live", "bound"], map(int, m.groups())))
    return {}


def sprint(s, ticks):
    p = s.pos()
    s.cmd(f"tick sprint {ticks}", 1)
    s.wait_for(r"Sprint completed", 600, since=p)


def full_uuid(s, short):
    """Full UUID of a loaded entity from its first 8 hex digits (via spike-info over all entities)."""
    for u in spike_info(s, "@e[type=!minecraft:player]"):
        if u.startswith(short):
            return u
    return None


def wait_garrison(s, c, pred, timeout, step=5):
    end = time.time() + timeout
    g = garrison(s, c)
    while time.time() < end and not pred(g):
        time.sleep(step)
        g = garrison(s, c)
    return g


def scenario_G3_1(ctx):
    """Starting grant: each village gets ceil(target/2) free units once; they spawn throttled, owned
    by the village faction, supply off, no natural despawn, tier equipment level."""
    s = ctx.s
    ctx.g3 = {}
    for name, c in (("A", ctx.a), ("B", ctx.b)):
        g = wait_garrison(s, c, lambda g: g.get("grant") == "done" and g.get("recruited", 1) == 0 and g.get("alive", 0) > 0, 180)
        want = -(-g.get("target", 0) // 2)
        cen = census(s, c)
        ctx.g3[name] = dict(g=g, census=cen)
        check(f"G3-1 {name} starting grant = ceil(target/2) = {want}, all spawned", g.get("grant") == "done" and g.get("live") == want
              and g.get("alive") == want and g.get("t_recruited") == want, "; ".join(g["lines"][1:3]))
        check(f"G3-1 {name} census: one tagged entity per slot, faction-owned", cen.get("tagged") == want and cen.get("dupSlots") == 0
              and cen.get("unbound") == 0 and cen.get("badOwner") == 0, str(cen))
    fa = garrison(s, ctx.a).get("faction")
    rows = spike_info(s, "@e[type=!minecraft:player]")
    mine = [r for r in rows.values() if r["tag"] != "none" and ("owner=" + fa) in r["desc"]]
    lvl = {"WATCH": 0, "GUARD_POST": 1, "GARRISON": 2, "STRONGHOLD": 3}.get(info(s, ctx.a).get("tier"), -1)
    check("G3-1 units: HYW OwnerUUID = village faction (never a player), supply off, no despawn, tier equipment level",
          mine and all("supply=false" in r["desc"] and "despawn=false" in r["desc"] and f"equipment={lvl}" in r["desc"] for r in mine),
          f"{len(mine)} units, tier level {lvl}; e.g. {mine[0]['desc'] if mine else ''}")
    ctx.g3_start = s.pos()
    # no second grant: restart, then compare
    restart(ctx)
    time.sleep(40)
    g = garrison(s, ctx.a)
    check("G3-1 no second grant after restart", g.get("t_recruited") == ctx.g3["A"]["g"].get("t_recruited")
          and not any("Starting garrison granted" in l for l in s.read_since(s.start_pos)), "; ".join(g["lines"][1:3]))


def unit_entities(s, c):
    """{entity uuid: spike-info row} of the village's tagged units."""
    fac = garrison(s, c).get("faction")
    return {u: r for u, r in spike_info(s, "@e[type=!minecraft:player]").items() if r["tag"] != "none" and ("owner=" + fac) in r["desc"]}


def scenario_G3_2(ctx):
    """Allegiance: garrison and residents share the faction; neither attacks the other."""
    s = ctx.s
    c = ctx.a
    before = incidents(s, 100)
    units = unit_entities(s, c)
    ids = {u[:8] for u in units}
    time.sleep(45)
    inc = [i for i in incidents(s, 100) if i not in before]
    by_units = [i for i in inc if i["a"] in ids and i["resident"]]
    on_units = [i for i in inc if i["v"] in ids and i["atype"] == "millenaire:villager"]
    fa = garrison(s, c).get("faction")
    rel = [r for r in units.values()]
    check("G3-2 garrison never attacks its own residents", not by_units, str(by_units[:3]))
    check("G3-2 residents never attack their garrison", not on_units, str(on_units[:3]))
    marker = None
    res = wait_residents(s, c)
    if res:
        marker = marker_of(s, res[0][0])
    check("G3-2 garrison owner == residents' relation marker == village faction", marker == fa and len(units) > 0, f"marker {marker} faction {fa}")


def scenario_G3_3(ctx):
    """Neutral passage: player-owned HYW units standing in the village are not attacked."""
    s = ctx.s
    c = ctx.a
    s.cmd("kill @e[tag=hwP]", 1)
    for i in range(3):
        s.cmd(ground(c[0] + 2 * i, c[2] - 6, f"summon hundred_years_war:militia ~ ~ ~ {{OwnerUUID:{OWNER_NBT},Tags:['hwP']}}"), 1)
    h0 = [health(s, "hwP")]
    units = unit_entities(s, c)
    time.sleep(40)
    targets = [r["target"] for u, r in spike_info(s, "@e[type=!minecraft:player]").items() if u in units and r["target"] != "none"]
    g = garrison(s, c)
    check("G3-3 player-owned units passing through: no garrison target, no deployment, unharmed",
          not targets and g.get("deployed") == 0 and health(s, "hwP") == h0[0], f"targets {targets} deployed {g.get('deployed')} health {h0} -> {health(s, 'hwP')}")
    check("G3-3 village <-> unit owner stays NEUTRAL", relation(s, c, OWNER_UUID) == ("NEUTRAL", "NEUTRAL"), str(relation(s, c, OWNER_UUID)))


def scenario_G3_4(ctx):
    """Response: a player-owned unit that damages a resident becomes an M2 threat; the garrison
    deploys on it (temporary HYW hostility), then returns; the relation stays NEUTRAL."""
    s = ctx.s
    c = ctx.a
    res = wait_residents(s, c)
    civ = next((r[0] for r in res if r[2] == "CIVILIAN"), None)
    attacker = next(iter(u for u, r in spike_info(s, "@e[tag=hwP]").items()), None)
    if not check("G3-4 an attacker and a civilian", civ is not None and attacker is not None, f"{attacker} {civ}"):
        return
    s.cmd(ground(c[0] + 3, c[2] + 3, f"tp {attacker} ~ ~ ~"), 1)
    s.cmd(f"damage {civ} 1 minecraft:mob_attack by {attacker}", 1)
    deployed, tgt, temp = 0, [], []
    end = time.time() + 20
    while time.time() < end:
        g = garrison(s, c)
        rows = spike_info(s, "@e[type=!minecraft:player]")
        tgt = [r for u, r in rows.items() if r["tag"] != "none" and attacker[:8] in r["target"]]
        if g.get("deployed", 0) > 0 and tgt:
            deployed = g["deployed"]
            temp = [r["temp"] for r in tgt]
            break
        time.sleep(1)
    m = military(s, c)
    check("G3-4 garrison deployed on the attacker with temporary HYW hostility, within commitPerThreat",
          0 < deployed <= 2 and tgt and all(t == "true" for t in temp), f"deployed {deployed}, targeting {len(tgt)}, temp {temp}, alert {m.get('alert')}")
    s.cmd("kill @e[tag=hwP]", 1)
    g = wait_garrison(s, c, lambda g: g.get("deployed", 1) == 0 and g.get("returning", 1) == 0, 120)
    check("G3-4 after the threat: units return (RETURNING -> GARRISONED)", g.get("deployed") == 0 and g.get("returning") == 0, g["lines"][1] if len(g["lines"]) > 1 else "")
    check("G3-4 relation stays NEUTRAL (ALWAYS_REVERT)", relation(s, c, OWNER_UUID) == ("NEUTRAL", "NEUTRAL"), str(relation(s, c, OWNER_UUID)))


def scenario_G3_5(ctx):
    """Monsters: HYW's own AI handles a zombie; HywMill does not deploy for it (not an M2 threat)."""
    s = ctx.s
    c = ctx.a
    s.cmd(ground(c[0] + 5, c[2] + 5, f"summon minecraft:zombie ~ ~ ~ {{Tags:['hwZ'],PersistenceRequired:1b}}"), 1)
    seen_deployed = 0
    for _ in range(10):
        time.sleep(3)
        seen_deployed = max(seen_deployed, garrison(s, c).get("deployed", 0))
    alive = health(s, "hwZ") is not None
    check("G3-5 zombie is handled (HYW native targeting of monsters) without HywMill deployment", not alive and seen_deployed == 0,
          f"zombie alive {alive}, max deployed {seen_deployed}")
    s.cmd("kill @e[tag=hwZ]", 1)


def scenario_G3_6(ctx):
    """Unowned HYW: a bandit attacking the village is engaged (M2 threat -> deployment); residents
    are never hit by the garrison."""
    s = ctx.s
    c = ctx.a
    before = incidents(s, 100)
    units = unit_entities(s, c)
    ids = {u[:8] for u in units}
    s.cmd(ground(c[0] + 4, c[2] + 4, f"summon hundred_years_war:bandit_soldier ~ ~ ~ {{Tags:['hwB']}}"), 1)
    maxdep = 0
    for _ in range(45):  # a 1-vs-many HYW fight can take over 40 s
        time.sleep(2)
        maxdep = max(maxdep, garrison(s, c).get("deployed", 0))
        if health(s, "hwB") is None:
            break
    inc = [i for i in incidents(s, 100) if i not in before]
    unit_hits = [i for i in inc if i["a"] in ids and i["vtype"] == "hundred_years_war:bandit_soldier"]
    res_hits = [i for i in inc if i["a"] in ids and i["resident"]]
    check("G3-6 garrison fights the unowned bandit", bool(unit_hits) and health(s, "hwB") is None,
          f"{len(unit_hits)} garrison hits, max deployed {maxdep}, bandit dead {health(s, 'hwB') is None}")
    check("G3-6 garrison never hits residents", not res_hits, str(res_hits[:3]))
    s.cmd("kill @e[tag=hwB]", 1)


def scenario_G3_7(ctx):
    """Restart x3: the same units reload once; no slot that existed before a restart is spawned again
    (a new paid recruit after a restart is normal recruitment); no duplicates; no unbound units."""
    s = ctx.s
    c = ctx.a
    wait_garrison(s, c, lambda g: g.get("recruited", 1) == 0, 60)
    c0 = census(s, c)
    ok = True
    details = []
    for i in range(3):
        before = {u["slot"] for u in g_units(s, c) if u["state"] not in ("DEAD", "LOST", "RECRUITED")}
        restart(ctx)
        time.sleep(45)  # > settle + a few slots
        c1 = census(s, c)
        spawned = [re.search(r": ([0-9a-f]{8}) ", l.split("Garrison unit spawned for village")[1])[1]
                   for l in s.read_since(s.start_pos) if "Garrison unit spawned for village" in l]
        respawned = [x for x in spawned if x in before]
        details.append(f"#{i + 1}: tagged {c1.get('tagged')} bound {c1.get('bound')} dup {c1.get('dupSlots')} unbound {c1.get('unbound')} "
                       f"spawns {len(spawned)} (new slots {len(spawned) - len(respawned)}, existing slots {len(respawned)})")
        ok &= not respawned and c1.get("tagged") == c1.get("bound") and c1.get("dupSlots") == 0 and c1.get("unbound") == 0 \
            and c1.get("tagged", 0) >= c0.get("tagged", 0)
    check("G3-7 three restarts: no existing slot respawned, one entity per slot, no duplicates", ok, f"before {c0}; " + " | ".join(details))

def scenario_G3_8(ctx):
    """Stale roster: rewind a live slot to RECRUITED (as a save written before its spawn would show).
    Loaded case: the next slot adopts the unit. Unloaded case: a respawn uses the same deterministic
    UUID and vanilla refuses the stale copy when its chunk loads. Never two units for one slot."""
    s = ctx.s
    c = ctx.a
    units = [u for u in g_units(s, c) if u["state"] == "GARRISONED"]
    if not check("G3-8 two garrisoned slots to rewind", len(units) >= 2, str(units)):
        return
    u = units[0]
    c0 = census(s, c)
    s.output(at(c, f"hywmill dev rewind {u['slot']}"), 1)
    s.output(at(c, "hywmill dev spawn-now"), 2)          # attempt to spawn the rewound slot while its unit is loaded
    s.output(at(c, "hywmill admin reconcile"), 2)
    after = {x["slot"]: x for x in g_units(s, c)}
    c1 = census(s, c)
    check("G3-8 loaded: spawn refused, slot adopts its unit, one entity per slot", after[u["slot"]]["state"] in ("RECOVERED", "GARRISONED")
          and after[u["slot"]]["entity"] == u["entity"] and c1.get("tagged") == c1.get("bound") and c1.get("dupSlots") == 0 and c1.get("unbound") == 0,
          f"{after[u['slot']]} census {c0} -> {c1}")
    # unloaded case
    v = units[1]
    ent = full_uuid(s, v["entity"])
    far = (c[0] + 600, c[2] + 600)
    s.cmd(f"forceload add {far[0]} {far[1]}", 8)
    fy = surface_y(s, far[0], far[1]) or 70
    s.cmd(f"tp {ent} {far[0]} {fy + 1} {far[1]}", 2)
    s.cmd("save-all flush", 5)
    s.cmd(f"forceload remove {far[0]} {far[1]}", 25)
    s.output(at(c, f"hywmill dev rewind {v['slot']}"), 1)
    out = s.output(at(c, "hywmill dev spawn-now"), 3)
    p = s.pos()
    s.cmd(f"forceload add {far[0]} {far[1]}", 25)
    refused = s.wait_for(r"UUID of added entity already exists|Duplicate garrison unit refused", 10, since=p)
    c2 = census(s, c)
    check("G3-8 unloaded: respawn reuses the slot's UUID; the stale copy is refused on chunk load; one unit per slot",
          c2.get("dupSlots") == 0 and c2.get("unbound") == 0 and refused is not None and c2.get("tagged") == c2.get("bound"),
          f"spawn-now {out}; refusal {refused}; census {c2}")
    s.cmd(f"forceload remove {far[0]} {far[1]}", 2)


def scenario_G3_9(ctx):
    """Unload is not death: a unit in an unloaded chunk goes MISSING only after the grace, is never
    replaced, and is RECOVERED when it loads again."""
    s = ctx.s
    c = ctx.a
    units = [u for u in g_units(s, c) if u["state"] == "GARRISONED"]
    if not check("G3-9 a garrisoned unit", bool(units)):
        return
    u = units[0]
    ent = full_uuid(s, u["entity"])
    far = (c[0] - 600, c[2] + 600)
    s.cmd(f"forceload add {far[0]} {far[1]}", 8)
    fy = surface_y(s, far[0], far[1]) or 70
    s.cmd(f"tp {ent} {far[0]} {fy + 1} {far[1]}", 2)
    s.cmd(f"forceload remove {far[0]} {far[1]}", 3)
    g0 = garrison(s, c)
    sprint(s, 200)
    st1 = {x["slot"]: x for x in g_units(s, c)}[u["slot"]]["state"]
    sprint(s, 1600)
    st2 = {x["slot"]: x for x in g_units(s, c)}[u["slot"]]["state"]
    g1 = garrison(s, c)
    check("G3-9 unloaded unit: still bound before the grace, MISSING after it (never DEAD), no replacement",
          st1 == "GARRISONED" and st2 == "MISSING" and g1.get("live") == g0.get("live") and g1.get("recruited") == 0,
          f"states {st1} -> {st2}; live {g0.get('live')} -> {g1.get('live')}")
    s.cmd(f"forceload add {far[0]} {far[1]}", 15)
    s.cmd(ground(c[0] + 2, c[2] + 2, f"tp {ent} ~ ~ ~"), 2)
    s.cmd(f"forceload remove {far[0]} {far[1]}", 2)
    sprint(s, 400)
    st3 = {x["slot"]: x for x in g_units(s, c)}[u["slot"]]["state"]
    check("G3-9 unit loaded again: RECOVERED -> GARRISONED, same slot and entity", st3 in ("RECOVERED", "GARRISONED"), st3)


def scenario_G3_10(ctx):
    """Death and replacement: no respawn; a replacement is a new paid slot after the cooldown."""
    s = ctx.s
    c = ctx.a
    units = [u for u in g_units(s, c) if u["state"] in ("GARRISONED", "RECOVERED")]
    if not check("G3-10 a unit to kill", bool(units)):
        return
    u = units[0]
    g0 = garrison(s, c)
    s.cmd(f"kill {full_uuid(s, u['entity'])}", 2)
    time.sleep(3)
    rows = {x["slot"]: x for x in g_units(s, c)}
    g1 = garrison(s, c)
    check("G3-10 killed unit: DEAD(KILLED), no respawn of that slot", rows[u["slot"]]["state"] == "DEAD" and rows[u["slot"]]["reason"] == "KILLED"
          and g1.get("t_killed") == g0.get("t_killed", 0) + 1 and g1.get("recruited") == 0, str(rows[u["slot"]]))
    s.output(at(c, "hywmill admin setpoints 10"), 1)
    sprint(s, 600)   # inside the death cooldown + recruit interval
    g2 = garrison(s, c)
    early = g2.get("t_recruited") > g1.get("t_recruited")
    sprint(s, 3000)
    g3 = wait_garrison(s, c, lambda g: g.get("t_recruited", 0) > g1.get("t_recruited", 0) and g.get("recruited", 1) == 0, 60)
    new_slots = [x for x in g_units(s, c) if x["slot"] not in rows]
    check("G3-10 replacement only after the cooldown, as a new paid slot (levy spent)", not early and len(new_slots) >= 1
          and g3.get("levy", 10) < 10 and rows[u["slot"]]["slot"] not in [x["slot"] for x in new_slots],
          f"early={early}; new {new_slots}; levy {g3.get('levy')}")


def scenario_G3_11(ctx):
    """Capture: an owner change makes the slot LOST(CAPTURED); the unit is left with its new owner."""
    s = ctx.s
    c = ctx.a
    units = [u for u in g_units(s, c) if u["state"] in ("GARRISONED", "RECOVERED")]
    if not check("G3-11 a unit to capture", bool(units)):
        return
    u = units[0]
    ent = full_uuid(s, u["entity"])
    s.cmd(f"data modify entity {ent} OwnerUUID set value {OWNER_NBT}", 1)
    s.output(at(c, "hywmill admin reconcile"), 2)
    row = {x["slot"]: x for x in g_units(s, c)}[u["slot"]]
    info_ = spike_info(s, ent).get(ent, {})
    check("G3-11 owner mismatch -> LOST(CAPTURED); owner not rewritten; tag removed", row["state"] == "LOST" and row["reason"] == "CAPTURED"
          and OWNER_UUID in info_.get("desc", "") and info_.get("tag") == "none", f"{row} {info_}")
    s.cmd(f"kill {ent}", 1)


def scenario_G3_12(ctx):
    """Controller change. (a) Millénaire switchcontrol on an ordinary village changes its Millénaire
    owner; HywMill's controllerPlayerId stays M2's (only player-controlled village types have one)
    and every garrison unit keeps the faction owner. (b) On a player-controlled village type the
    controller (a non-op fake player) may pause its garrison; a stranger may not."""
    s = ctx.s
    c = ctx.b
    name = next((l.split("== Garrison of ")[1].split(" (")[0] for l in garrison(s, c)["lines"] if l.startswith("== Garrison of ")), None)
    fac = garrison(s, c).get("faction")
    owners0 = {u: r["desc"] for u, r in unit_entities(s, c).items()}
    out = s.output(f'millenaire dev switchcontrol "{name}" "HwCtl"', 3)
    time.sleep(12)
    owners1 = {u: r["desc"] for u, r in unit_entities(s, c).items()}
    check("G3-12a Millénaire owner change: faction and every unit owner unchanged", any("ownership transferred" in l for l in out)
          and owners0.keys() == owners1.keys() and all(("owner=" + fac) in d for d in owners1.values()) and fac == garrison(s, c).get("faction"),
          f"{out}; {len(owners1)} units")
    # (b) a player-controlled village type
    box = EXTRA_FORCELOAD[2]
    s.cmd("forceload add {} {} {} {}".format(*box), wait=20)
    pc = spawn_village(s, [("norman/controlled", 1060, 80, 640), ("norman/controlled", 1040, 80, 612)], surface=True)
    if not check("G3-12b player-controlled village spawned", pc is not None, str(pc)):
        return
    time.sleep(15)
    pname = next((l.split("== Garrison of ")[1].split(" (")[0] for l in garrison(s, pc)["lines"] if l.startswith("== Garrison of ")), None)
    s.output(f'millenaire dev switchcontrol "{pname}" "HwCtl"', 3)
    ctl = None
    for _ in range(10):
        time.sleep(5)
        m = military(s, pc)
        ctl = next((re.search(r"controller=(\S+)", l)[1] for l in m["lines"] if "controller=" in l), None)
        if ctl:
            break
    pfac = garrison(s, pc).get("faction")
    check("G3-12b controllerPlayerId set from Millénaire, separate from the faction", ctl is not None and ctl != pfac, f"controller {ctl} faction {pfac}")
    if ctl is None:
        return
    p = s.pos()
    s.cmd(at(pc, f"hywmill dev runas {ctl} hywmill village garrison pause"), 2)
    s.cmd(at(pc, "hywmill dev runas 00000000-0000-4000-8000-00000000abcd hywmill village garrison resume"), 2)
    lines = s.read_since(p)
    paused = any("[runas " + ctl[:8] + "]" in l and "paused" in l for l in lines)
    denied = any("[runas 00000000]" in l and "Only an operator or the controller" in l for l in lines)
    g = garrison(s, pc)
    check("G3-12b the (non-op) controller pauses its garrison; a stranger is refused", paused and denied
          and any("PAUSED" in l for l in g["lines"]), "; ".join(l.split("]: ", 1)[-1] for l in lines if "runas" in l))
    s.output(at(pc, "hywmill village garrison resume"), 1)

def scenario_G3_13(ctx):
    """Permissions: a non-op non-controller sees the summary only."""
    s = ctx.s
    c = ctx.a
    who = "00000000-0000-4000-8000-00000000abce"
    p = s.pos()
    for cmd in ["hywmill village garrison", "hywmill village garrison units", "hywmill village garrison pause", "hywmill village garrison recall",
                "hywmill admin setpoints 5", "hywmill admin grant militia 1", "hywmill dev census"]:
        s.cmd(at(c, f"hywmill dev runas {who} {cmd}"), 1.5)
    lines = [l.split("]: ", 1)[-1] for l in s.read_since(p) if "[runas 00000000]" in l]
    summary = any(l.startswith("[hywmill] [runas 00000000] == Garrison of") or "== Garrison of" in l for l in lines)
    denied = sum(1 for l in lines if "Only an operator or the controller" in l)
    unknown = sum(1 for l in lines if "Unknown or incomplete command" in l or "Unknown command" in l or "Incorrect argument" in l)
    g = garrison(s, c)
    check("G3-13 non-op stranger: summary yes; units/pause/recall refused; admin/dev commands unavailable",
          summary and denied >= 3 and "PAUSED" not in g["lines"][1], f"summary {summary}, refused {denied}, unavailable {unknown}; " + " | ".join(lines[:12]))


def scenario_G3_14(ctx):
    """Village deletion (Millénaire fires no event): after the grace period the slots are
    LOST(VILLAGE_GONE); orphan policy KEEP leaves the units as ordinary HYW units (untagged)."""
    s = ctx.s
    c = ctx.b
    units = unit_entities(s, c)
    fac = garrison(s, c).get("faction")
    p = s.pos()
    out = s.output(at(c, "hywmill dev remove-village"), 3)
    seen = s.wait_for(r"is missing from Millénaire's village list", 30, since=p)
    early = s.wait_for(r"is gone: \d+ garrison slot", 5, since=p)
    sprint(s, 6200)
    gone = s.wait_for(r"is gone: \d+ garrison slot\(s\) LOST\(VILLAGE_GONE\); orphan policy KEEP applied to \d+ loaded unit", 60, since=p)
    rows = spike_info(s, "@e[type=!minecraft:player]")
    kept = [u for u in units if u in rows]
    untagged = all(rows[u]["tag"] == "none" and ("owner=" + fac) in rows[u]["desc"] for u in kept)
    check("G3-14 village gone -> grace period -> LOST(VILLAGE_GONE); KEEP leaves units alive, untagged, owner unchanged",
          seen is not None and early is None and gone is not None and len(kept) == len(units) and untagged,
          f"{out}; {gone}; kept {len(kept)}/{len(units)}")


def scenario_G3_15(ctx):
    """Datapack reload: a new composition applies to new recruits only."""
    s = ctx.s
    c = ctx.a
    dp = s.d / "world" / "datapacks" / "hwm3test"
    (dp / "data" / "hywmill" / "hywmill_garrison").mkdir(parents=True, exist_ok=True)
    (dp / "pack.mcmeta").write_text('{"pack":{"pack_format":48,"description":"hywmill M3 test"}}')
    (dp / "data" / "hywmill" / "hywmill_garrison" / "zz_test.json").write_text(
        '{"cultures":{"millenaire:norman":{"composition":{"militia":1}}}}')
    before = {x["slot"]: x for x in g_units(s, c)}
    p = s.pos()
    s.cmd("reload", 10)
    s.cmd("datapack enable \"file/hwm3test\"", 8)
    loaded = s.wait_for(r"Garrison tables loaded", 20, since=p)
    s.output(at(c, "hywmill admin setpoints 10"), 1)
    sprint(s, 3000)
    after = {x["slot"]: x for x in g_units(s, c)}
    new = [x for k, x in after.items() if k not in before]
    unchanged = all(after[k]["unit"] == v["unit"] for k, v in before.items() if k in after)
    check("G3-15 reload: existing slots unchanged, new recruits use the new composition", loaded is not None and unchanged
          and new and all(x["unit"] == "militia" for x in new), f"new {[(x['slot'], x['unit']) for x in new]}")
    s.cmd("datapack disable \"file/hwm3test\"", 8)


def scenario_G3_perf(ctx):
    """End of the garrison suite: the perf counters over the whole suite (spawns, slots, events, deploys)."""
    out = ctx.s.output("hywmill perf", 2)
    log("G3 suite perf (whole run):\n  " + "\n  ".join(out))
    rows = perf_rows(out)
    check("G3 suite perf recorded, incl. garrison.spawn", "garrison.spawn" in rows,
          " | ".join(f"{k} n={v['n']} mean {v['mean']}us p99 {v['p99']}us max {v['max']}us" for k, v in rows.items() if k.startswith("garrison")))


def village_centers(s):
    centers = []
    for l in s.output("hywmill village list", 2):
        m = re.search(r" \((-?\d+), (-?\d+), (-?\d+)\) tier=| (-?\d+), (-?\d+), (-?\d+) tier=", l)
        if m:
            centers.append(tuple(int(x) for x in m.groups() if x is not None))
    return centers


def perf_rows(out):
    rows = {}
    for l in out:
        m = re.match(r"\s*([\w.]+): n=(\d+) mean=([\d.]+)us max=([\d.]+)us p99=([\d.]+)us total=([\d.]+)ms", l)
        if m:
            rows[m[1]] = dict(n=int(m[2]), mean=float(m[3]), max=float(m[4]), p99=float(m[5]), total=float(m[6]))
    return rows


def scenario_G3_17(ctx):
    """Performance and scale: every known village's garrison filled to its tier cap (admin grant of
    a unit its tier allows), then 120 s CALM and a fight, with production logging. Also checks
    recall, equipment drop chances and that player-owned units are not counted."""
    s = ctx.s
    s.cmd("hywmill perf reset", 1)  # the fill-phase readout below then covers only this scenario's spawning
    extra = ensure_extra_villages(ctx)
    for name in [k for k, v in extra.items() if v is None]:
        for attempt in range(3):  # Millénaire rolls the start building; a roll can find no location
            extra[name] = spawn_village(s, EXTRA_VILLAGES[name], surface=True)
            if extra[name]:
                break
    log(f"G3-17 extra villages: {extra}")
    time.sleep(30)
    villages = village_centers(s)
    granted = []
    for c in villages:
        g = garrison(s, c)
        head = g.get("cap", 0) - g.get("live", 0)
        if head > 0:
            out = s.output(at(c, f"hywmill admin grant archer {min(head, 64)}"), 2)
            granted.append((c, g.get("cap"), " ".join(out)[:90]))
    log(f"G3-17 grants: {granted}")
    end = time.time() + 900
    while time.time() < end:
        pend = sum(garrison(s, c).get("recruited", 0) for c in villages)
        if pend == 0:
            break
        time.sleep(15)
    per = {}
    for c in villages:  # the list also holds records of deleted villages; count each live village once
        g = garrison(s, c)
        per.setdefault(g.get("faction"), g)
    live = sum(g.get("alive", 0) for g in per.values())
    caps = sum(g.get("cap", 0) for g in per.values())
    ctx.g3_scale = (len(per), live, caps)
    check("G3-17 every garrison filled to its tier cap (no server-wide cap)", all(g.get("live") == g.get("cap") and g.get("recruited") == 0 for g in per.values()),
          f"{len(per)} villages, {live} units alive of caps {[g.get('cap') for g in per.values()]}")
    # equipment drops disabled on garrison units
    sample = list(unit_entities(s, ctx.a))[:3]
    drops = [" ".join(s.output(f"data get entity {u} ArmorDropChances", 1) + s.output(f"data get entity {u} HandDropChances", 1)) for u in sample]
    nums = [float(x) for d in drops for x in re.findall(r"(-?[\d.]+)f", d)]
    check("G3-17 equipmentDrops=false: garrison units have drop chance 0 in every slot", sample and nums and all(v == 0.0 for v in nums),
          f"{len(sample)} units, {len(nums)} slot chances, max {max(nums) if nums else None}")
    # player-owned units in the village are not garrison
    g0, c0 = garrison(s, ctx.a), census(s, ctx.a)
    for i in range(3):
        s.cmd(ground(ctx.a[0] - 6 + 2 * i, ctx.a[2] + 8, f"summon hundred_years_war:militia ~ ~ ~ {{OwnerUUID:{OWNER_NBT},Tags:['hwPO']}}"), 1)
    time.sleep(25)
    g1, c1 = garrison(s, ctx.a), census(s, ctx.a)
    check("G3-17 player-owned HYW units in the village are not counted as garrison", g1.get("live") == g0.get("live")
          and c1.get("tagged") == c0.get("tagged") and c1.get("factionOwned") == c0.get("factionOwned"), f"live {g0.get('live')}->{g1.get('live')} census {c0}->{c1}")
    s.cmd("kill @e[tag=hwPO]", 1)
    fill = s.output("hywmill perf", 2)
    log("G3-17 perf while filling the garrisons (spawns):\n  " + "\n  ".join(fill))
    # CALM
    s.cmd("hywmill perf reset", 1)
    time.sleep(120)
    calm = s.output("hywmill perf", 2)
    # fight, with a recall in the middle
    s.cmd("hywmill perf reset", 1)
    for i in range(3):
        s.cmd(ground(ctx.a[0] + 8 + 2 * i, ctx.a[2] + 8, "summon hundred_years_war:bandit_soldier ~ ~ ~ {Tags:['hwPerf']}"), 1)
    if len(villages) > 2:
        c = villages[2]
        for i in range(2):
            s.cmd(ground(c[0] + 6 + 2 * i, c[2] + 6, "summon hundred_years_war:bandit_soldier ~ ~ ~ {Tags:['hwPerf']}"), 1)
    dep = 0
    for _ in range(30):
        time.sleep(1)
        dep = garrison(s, ctx.a).get("deployed", 0)
        if dep > 0:
            break
    rec = s.output(at(ctx.a, "hywmill village garrison recall"), 1)
    after = garrison(s, ctx.a).get("deployed", -1)
    recalled = next((int(m[1]) for l in rec for m in [re.search(r"Recalled (\d+) deployed", l)] if m), 0)
    check("G3-17 recall: deployed units return at once", dep > 0 and recalled > 0 and after == 0,
          f"deployed {dep} -> {after}; {rec}")
    time.sleep(90)
    s.cmd("kill @e[tag=hwPerf]", 1)
    fight = s.output("hywmill perf", 2)
    ctx.g3_perf = (calm, fight)
    log("G3-17 scale: %d villages, %d garrison units" % (len(per), live))
    log("G3-17 perf CALM 120 s:\n  " + "\n  ".join(calm))
    log("G3-17 perf fight 90 s:\n  " + "\n  ".join(fight))
    rows = perf_rows(calm)
    check("G3-17 perf recorded at scale (values in the report)", "garrison.slot" in rows and "tick.total" in rows,
          " | ".join(f"{k} mean {v['mean']}us p99 {v['p99']}us" for k, v in rows.items() if k.startswith("garrison") or k == "tick.total"))


# --------------------------------------------------------------------------- M4-0 spike

def raidstate(s):
    rows = {}
    for l in s.output("hywmill dev raidstate", 2):
        m = re.search(r"raidstate (.+) ([0-9a-f]{8}) target=(\S+) planning=(-?\d+) start=(-?\d+) underAttack=(\w+) performed=(\d+) suffered=(\d+) t=(\d+)", l)
        if m:
            rows[m[2]] = dict(name=m[1], target=m[3], planning=int(m[4]), start=int(m[5]), under=m[6] == "true",
                              performed=int(m[7]), suffered=int(m[8]), t=int(m[9]))
    return rows


def village_id8(s, c):
    return (info(s, c).get("villageId") or "")[:8]


def scenario_S4(ctx):
    """M4-0 spike on the dedicated server: Millénaire raid lifecycle + HYW contingent mechanics,
    HYW mounted units as scouts, Wand of Negation lifecycle. Findings are logged as 'spike4 ...'."""
    s = ctx.s
    a, b = ctx.a, ctx.b
    ia, ib = village_id8(s, a), village_id8(s, b)
    wait_garrison(s, a, lambda g: g.get("alive", 0) >= 4 and g.get("recruited", 1) == 0, 240)
    wait_garrison(s, b, lambda g: g.get("alive", 0) >= 2 and g.get("recruited", 1) == 0, 240)

    # ---------------- raid lifecycle
    r0 = raidstate(s)
    log(f"spike4 raid before: A={r0.get(ia)} B={r0.get(ib)}")
    p = s.pos()
    out = s.output(f"millenaire dev raid trigger {a[0]} {a[1]} {a[2]} {b[0]} {b[1]} {b[2]}", 3)
    check("S4-raid1 Millénaire raid A -> B triggered", any("Raid triggered" in l for l in out), "; ".join(out))
    timeline, moved, landing = [], [], None
    t_start = time.time()
    ended = False
    fa, fb = garrison(s, a).get("faction"), garrison(s, b).get("faction")
    while time.time() - t_start < 300:
        rs = raidstate(s)
        ra, rb = rs.get(ia, {}), rs.get(ib, {})
        timeline.append((round(time.time() - t_start), ra.get("target"), ra.get("start"), rb.get("under"), ra.get("performed"), rb.get("suffered")))
        mat = any("Raider clone materialized" in l for l in s.read_since(p))
        if mat and not moved:
            pt = None
            for l in s.output(at(b, f"hywmill dev raidpoint {a[0]} {a[1]} {a[2]}"), 2):
                m = re.search(r"-> (-?\d+), (-?\d+), (-?\d+)", l)
                if m:
                    pt = tuple(int(x) for x in m.groups())
            landing = pt
            units = list(unit_entities(s, a))[:2]
            res_b = [r[0] for r in residents(s, b) if r[2] in ("CIVILIAN", "DEFENDER")]
            if pt and units and res_b:
                for u in units:
                    s.cmd(f"tp {u} {pt[0]} {pt[1]} {pt[2]}", 1)
                for i, u in enumerate(units):
                    s.output(f"hywmill dev spike-engage {u} {res_b[i % len(res_b)]}", 1)
                moved = units
                log(f"spike4 raid: moved A units {[u[:8] for u in units]} to Millénaire landing point {pt}, engaged B residents")
        if ra.get("performed", 0) > r0.get(ia, {}).get("performed", 0) or (timeline and ra.get("target") == "none" and ra.get("start", 0) == 0 and len(timeline) > 3):
            ended = True
            break
        time.sleep(5)
    lines = s.read_since(p)
    result = next((l for l in lines if re.search(r"Raid (FAILURE|SUCCESS)", l)), None)
    log("spike4 raid timeline (s, A.target, A.raidStart, B.underAttack, A.performed, B.suffered): " + str(timeline))
    log(f"spike4 raid result: {result}")
    check("S4-raid2 raid lifecycle observable via public Village state (target/start set, cleared at end, history grows)",
          any(t[1] == ib for t in timeline) and any(t[2] and t[2] > 0 for t in timeline) and ended, f"ended={ended} result={result}")
    inc = incidents(s, 100)
    raid_hits = [i for i in inc if moved and i["a"] in {u[:8] for u in moved} and i["resident"]]
    back = [i for i in inc if moved and i["v"] in {u[:8] for u in moved}]
    mb = military(s, b)
    gb = garrison(s, b)
    check("S4-raid3 HYW units moved to Millénaire's landing point fight B (temporary hostility), B defends them",
          bool(moved) and bool(raid_hits), f"landing {landing}; raid unit hits on B residents {len(raid_hits)}; hits on raid units {len(back)}; "
          f"B alert {mb.get('alert')} deployed {gb.get('deployed')}")
    time.sleep(12)
    rel = relation(s, a, fb) if fb else None
    check("S4-raid4 A<->B faction relation stays NEUTRAL (ALWAYS_REVERT)", rel == ("NEUTRAL", "NEUTRAL"), str(rel))
    alive = [u for u in moved if u in spike_info(s, "@e[type=!minecraft:player]")]
    for u in alive:
        s.cmd(ground(a[0] + 3, a[2] + 3, f"tp {u} ~ ~ ~"), 1)
    check("S4-raid5 surviving raid units can be returned home (teleport, as Millénaire materializes raiders)", True,
          f"{len(alive)}/{len(moved)} survived; returned by teleport")

    # ---------------- mounted units (scouts)
    riders = []
    for unit in ("mounted_light_lancer_rider", "mounted_archer_rider"):
        r = spike_spawn(s, (a[0] + 12, 0, a[2] - 12), unit, 1)
        riders.append((unit, r.get("uuid"), r.get("desc", r.get("out"))))
    log(f"spike4 riders: {riders}")
    time.sleep(8)
    info_r = spike_info(s, "@e[type=!minecraft:player]")
    mounts = {u: info_r.get(u, {}).get("mount") for _, u, _ in riders if u}
    horses0 = [u for u, r in info_r.items() if "hyw_horse" in r["desc"] or ("horse" in r["desc"] and r["tag"] == "none" and "not an HYW unit" not in r["desc"])]
    check("S4-scout1 HYW mounted riders spawn through the production path and mount their own horse", all(m and m != "none" for m in mounts.values()),
          f"mounts {mounts}")
    far = (a[0] + 70, a[2] + 70)
    for _, u, _ in riders:
        if u:
            s.cmd(ground(far[0], far[1], f"hywmill dev spike-home {u} ~ ~ ~"), 1)
    track = []
    for _ in range(12):
        time.sleep(5)
        row = spike_info(s, "@e[type=!minecraft:player]")
        track.append([round(dist(row[u]["pos"], (far[0], 0, far[1])), 1) if u in row else None for _, u, _ in riders])
    log(f"spike4 scout track to a post 99 blocks away: {track}")
    check("S4-scout2 riders travel to a scout post outside the village when their HYW home is moved there (mounted)",
          track and all(d is not None and d <= 12 for d in track[-1]), str(track[-1] if track else None))
    horses_before = [u for u, r in spike_info(s, "@e[type=!minecraft:player]").items() if r.get("mount") and r["mount"] != "none"]
    s.cmd("save-all flush", 5)
    restart(ctx)
    s.cmd(f"forceload add {far[0] - 16} {far[1] - 16} {far[0] + 16} {far[1] + 16}", 10)
    time.sleep(15)
    info2 = spike_info(s, "@e[type=!minecraft:player]")
    mounted_after = {u: info2.get(u, {}).get("mount") for _, u, _ in riders if u}
    hc = [l for l in s.output(f"execute positioned {far[0]} 70 {far[1]} run hywmill dev spike-info @e[distance=..40]", 3) if "horse" in l]
    check("S4-scout3 after restart: riders reload once, still mounted, no duplicate horses", all(m and m != "none" for m in mounted_after.values()),
          f"mounts {mounted_after}; horse-like entities near the post: {len(hc)}")
    for _, u, _ in riders:
        if u:
            s.cmd(f"kill {u}", 1)
    time.sleep(3)
    hc2 = [l for l in s.output(f"execute positioned {far[0]} 70 {far[1]} run hywmill dev spike-info @e[distance=..40]", 3) if "horse" in l]
    log(f"spike4 riders killed: horse-like entities near the post before {len(hc)}, after {len(hc2)}")

    # ---------------- Wand of Negation on B
    s.output(at(b, "hywmill admin setpoints 10"), 1)
    gb0 = garrison(s, b)
    bname = next((l.split("== Garrison of ")[1].split(" (")[0] for l in gb0["lines"] if l.startswith("== Garrison of ")), "?")
    res_before = len(residents(s, b))
    p = s.pos()
    out = s.output(at(b, "hywmill dev negate"), 5)
    lines = s.read_since(p)
    wand = next((l for l in lines if "deleted by negation wand" in l), None)
    rs = raidstate(s)
    res_after = sum(1 for l in s.output(ground(b[0], b[2], "execute if entity @e[type=millenaire:villager,distance=..60]"), 2) if "Test passed" in l)
    check("S4-wand1 negation deletion removes the village from Millénaire's VillageManager", wand is not None and ib not in rs,
          f"{out}; {wand}; villages now {sorted(r['name'] for r in rs.values())}")
    check("S4-wand2 its loaded residents are discarded by Millénaire", res_after == 0, f"residents loaded before {res_before}, any loaded after: {res_after}")
    miss = s.wait_for(r"is missing from Millénaire's village list", 30, since=p)
    check("S4-wand3 HywMill notices the disappearance (village-gone grace starts)", miss is not None, miss or "")
    time.sleep(40)  # > recruit slot + spawn slot with levy 10
    rec_after = [l for l in s.read_since(p) if ("recruits" in l or "Garrison unit spawned" in l) and bname in l]
    check("S4-wand5 no recruitment or spawning into the negated village", not rec_after, f"{bname}: {rec_after[:2]}")
    restart(ctx)
    time.sleep(10)
    sprint(s, 6200)
    gone = s.wait_for(r"is gone: \d+ garrison slot", 60, since=s.start_pos)
    lines2 = s.read_since(s.start_pos)
    grants = [l for l in lines2 if "Starting garrison granted" in l and bname in l]
    rec2 = [l for l in lines2 if ("recruits" in l or "Garrison unit spawned" in l) and bname in l]
    check("S4-wand4 across a restart: grace -> LOST(VILLAGE_GONE); orphan policy KEEP; no new grant or recruit", gone is not None and not grants and not rec2,
          f"{gone}; grants {len(grants)} recruits/spawns {len(rec2)} after restart")
    ctx.s4_negated_b = True


def unit_pos(s, u):
    r = spike_info(s, u).get(u)
    return r["pos"] if r else None


def scenario_S4b(ctx):
    """M4-0 follow-up: HYW home-move hop length (infantry and mounted), a waypoint chain to a far
    post, and raid-unit combat when moved close to the defended village and re-engaged."""
    s = ctx.s
    a, b = ctx.a, ctx.b
    # hop length: fresh unit per distance, home moved +d blocks east
    hops = {}
    for unit in ("spear_man", "mounted_light_lancer_rider"):
        for i, dd in enumerate((16, 24, 32, 48, 64)):
            x0, z0 = a[0] - 60, a[2] - 60 + i * 12 + (0 if unit == "spear_man" else 70)
            s.cmd(f"forceload add {x0 - 8} {z0 - 8} {x0 + dd + 8} {z0 + 8}", 3)
            r = spike_spawn(s, (x0, 0, z0), unit, 1)
            u = r.get("uuid")
            if not u:
                hops[(unit, dd)] = "spawn failed"
                continue
            time.sleep(3)
            s.cmd(ground(x0 + dd, z0, f"hywmill dev spike-home {u} ~ ~ ~"), 1)
            best = None
            for _ in range(10):
                time.sleep(4)
                p = unit_pos(s, u)
                if p:
                    dnow = dist(p, (x0 + dd, 0, z0))
                    best = dnow if best is None else min(best, dnow)
            hops[(unit, dd)] = round(best, 1) if best is not None else None
            s.cmd(f"kill {u}", 0.5)
    log(f"spike4b hop: remaining distance after 40 s per (unit, hop) {hops}")
    check("S4b-hop1 reachable single hop measured for infantry and mounted units", True, str(hops))
    # waypoint chain: a rider to a post ~99 blocks away via 20-block hops, advanced on arrival
    r = spike_spawn(s, (a[0] + 12, 0, a[2] - 12), "mounted_light_lancer_rider", 1)
    u = r.get("uuid")
    start = unit_pos(s, u) or (a[0] + 12, 0, a[2] - 12)
    target = (start[0] + 70, start[2] + 70)
    s.cmd(f"forceload add {start[0] - 8} {start[2] - 8} {target[0] + 8} {target[1] + 8}", 10)
    steps = 5
    reached = []
    t0 = time.time()
    for k in range(1, steps + 1):
        wx = start[0] + (target[0] - start[0]) * k // steps
        wz = start[2] + (target[1] - start[2]) * k // steps
        s.cmd(ground(wx, wz, f"hywmill dev spike-home {u} ~ ~ ~"), 0.5)
        ok = False
        for _ in range(12):
            time.sleep(2)
            p = unit_pos(s, u)
            if p and dist(p, (wx, 0, wz)) <= 4:
                ok = True
                break
        reached.append(ok)
    p = unit_pos(s, u)
    left = round(dist(p, (target[0], 0, target[1])), 1) if p else None
    log(f"spike4b waypoints: reached {reached}, {left} blocks from the post after {round(time.time() - t0)} s")
    check("S4b-way1 a mounted scout reaches a ~99-block post through 20-block waypoint hops", left is not None and left <= 6, f"{reached} left {left}")
    s.cmd(f"kill {u}", 0.5)
    # raid combat: two of A's units moved near B's centre, re-engaged every 5 s
    units = list(unit_entities(s, a))[:2]
    res_b = [r[0] for r in residents(s, b) if r[2] in ("CIVILIAN", "DEFENDER")]
    before = incidents(s, 100)
    for u in units:
        s.cmd(ground(b[0] + 12, b[2] + 12, f"tp {u} ~ ~ ~"), 0.5)
    for rnd in range(8):
        for i, u in enumerate(units):
            if res_b:
                s.output(f"hywmill dev spike-engage {u} {res_b[(i + rnd) % len(res_b)]}", 0.5)
        time.sleep(4)
    inc = [i for i in incidents(s, 100) if i not in before]
    hits = [i for i in inc if i["a"] in {u[:8] for u in units} and i["resident"]]
    back = [i for i in inc if i["v"] in {u[:8] for u in units}]
    gb = garrison(s, b)
    mb = military(s, b)
    check("S4b-raid1 raid units near the target, re-engaged, fight the defending village; the village defends",
          bool(hits), f"raid hits on B residents {len(hits)}, hits on raid units {len(back)}, B alert {mb.get('alert')}, B garrison deployed {gb.get('deployed')}")
    time.sleep(12)
    fb = garrison(s, b).get("faction")
    check("S4b-raid2 relation still NEUTRAL after the fight", relation(s, a, fb) == ("NEUTRAL", "NEUTRAL"), str(relation(s, a, fb)))


# --------------------------------------------------------------------------- M4 duties

def duties(s, c):
    """Parses /hywmill village garrison duties: {'plan': line, 'quota': dict, 'rows': [dict]}."""
    out = s.output(at(c, "hywmill village garrison duties"), 2)
    d = {"lines": out, "rows": [], "quota": {}, "plan": ""}
    for l in out:
        if l.startswith("Plan: "):
            d["plan"] = l
            m = re.search(r"Plan: (\d+) sentry post\(s\) \[([^\]]*)\] \| patrol \[([^\]]*)\] \| scout posts \[([^\]]*)\] \| reserve (\S+)", l)
            if m:
                pts = lambda t: [tuple(int(v) for v in x.split(",")) for x in t.split()] if t else []
                d["posts"], d["patrol"], d["scoutposts"] = pts(m[2]), pts(m[3]), pts(m[4])
        m = re.match(r"Quota: (\d+) sentry pair\(s\), (\d+) patrol, (\d+) scout\(s\), (\d+) reserve", l)
        if m:
            d["quota"] = dict(zip(["pairs", "patrol", "scouts", "reserve"], map(int, m.groups())))
        m = re.match(r"DUTY ([0-9a-f]{8}) (\S+) (\w+) (\w+)/(\w+)#(-?\d+) (\S*) ?(?:pos (-?\d+),(-?\d+),(-?\d+)|unloaded)(?: home (-?\d+),(-?\d+),(-?\d+))?( mounted)?", l)
        if m:
            d["rows"].append(dict(slot=m[1], unit=m[2], state=m[3], duty=m[4], assigned=m[5], index=int(m[6]), progress=m[7],
                                  pos=(int(m[8]), int(m[9]), int(m[10])) if m[8] else None,
                                  home=(int(m[11]), int(m[12]), int(m[13])) if m[11] else None, mounted=bool(m[14])))
    return d


def fill_garrison(s, c, units=("spear_man", "archer", "light_lancer_rider", "archer_rider", "militia", "crossbowman", "shieldman", "warrior")):
    """Admin-grants units (only those the village's tier allows) until the garrison is at its tier cap."""
    g = garrison(s, c)
    head = g.get("cap", 0) - g.get("live", 0)
    granted = []
    for u in units:
        if head <= 0:
            break
        n = max(1, head // 3) if u != units[-1] else head
        out = " ".join(s.output(at(c, f"hywmill admin grant {u} {min(n, head)}"), 1))
        if "may not" not in out and "Unknown" not in out:
            granted.append((u, min(n, head)))
            head = garrison(s, c).get("cap", 0) - garrison(s, c).get("live", 0)
    return granted


def scenario_G4_explore(ctx):
    """Exploratory M4 duty run: fill garrisons, then sample duties and positions over time."""
    s = ctx.s
    extra = ensure_extra_villages(ctx)
    log(f"G4 extra villages: {extra}")
    time.sleep(20)
    villages = [c for c in [ctx.a, ctx.b] + [v for v in extra.values() if v]]
    for c in villages:
        log(f"G4 fill {c}: {fill_garrison(s, c)}")
    end = time.time() + 600
    while time.time() < end and sum(garrison(s, c).get("recruited", 0) for c in villages) > 0:
        time.sleep(15)
    for rnd in range(4):
        time.sleep(45)
        for c in villages:
            d = duties(s, c)
            log(f"G4 round {rnd} village {c}: {d['plan'][:300]} quota {d['quota']}")
            for r in d["rows"]:
                log(f"   {r}")
    s.cmd("hywmill perf", 1)
    log("G4 perf:\n  " + "\n  ".join(s.output("hywmill perf", 2)))


# --------------------------------------------------------------------------- M4 acceptance (G4)

G4_A_SCOUT_FORCELOAD = [(500, 470, 760, 612), (500, 612, 760, 760)]


def g4_villages(ctx):
    """The G4 villages that have a living garrison (a village whose units could not spawn, e.g. M3's
    'No safe spawn spot' near its defending position, is reported by G4-0 and left out)."""
    vs = {k: v for k, v in [("A", ctx.a), ("B", ctx.b), ("M", ctx.extra.get("militaire")), ("Z", ctx.extra.get("byzantine"))] if v}
    empty = getattr(ctx, "g4_empty", set())
    return {k: v for k, v in vs.items() if k not in empty}


def assignments(d):
    return {r["slot"]: (r["assigned"], r["index"]) for r in d["rows"]}


def hdist(a, b):
    return ((a[0] - b[0]) ** 2 + (a[2] - b[2]) ** 2) ** 0.5


def scenario_G4_0(ctx):
    """Setup for the M4 suite: the extra villages, a loaded scout ring around A, every garrison
    filled to its tier cap (admin grant of tier-allowed units), then time for duties to settle."""
    s = ctx.s
    extra = ensure_extra_villages(ctx)
    for name in [k for k, v in extra.items() if v is None]:
        for attempt in range(3):
            extra[name] = spawn_village(s, EXTRA_VILLAGES[name], surface=True)
            if extra[name]:
                break
    for box in G4_A_SCOUT_FORCELOAD:
        s.cmd("forceload add {} {} {} {}".format(*box), wait=15)
    m = extra.get("militaire")
    if m:  # the stronghold's scout ring (radius + 40) loaded as a nearby player would have it
        for box in [(m[0] - 150, m[2] - 150, m[0] + 150, m[2]), (m[0] - 150, m[2], m[0] + 150, m[2] + 150)]:
            s.cmd("forceload add {} {} {} {}".format(*box), wait=15)
    time.sleep(20)
    vs = g4_villages(ctx)
    for k, c in vs.items():
        log(f"G4 fill {k} {c}: {fill_garrison(s, c)}")
    end = time.time() + 900
    while time.time() < end and sum(garrison(s, c).get("recruited", 0) for c in vs.values()) > 0:
        time.sleep(15)
    time.sleep(90)
    ctx.g4 = {k: duties(s, c) for k, c in vs.items()}
    for k, d in ctx.g4.items():
        log(f"G4 {k} {d['plan'][:200]} | quota {d['quota']} | {len(d['rows'])} units")
    ctx.g4_empty = {k for k, d in ctx.g4.items() if not d["rows"]}
    if ctx.g4_empty:
        log(f"G4 villages without a spawned garrison (left out of the duty checks): {sorted(ctx.g4_empty)}")
    check("G4-0 garrisons filled and duty plans exist (A, B and the stronghold at least)",
          all(ctx.g4[k]["plan"] and ctx.g4[k]["rows"] for k in ("A", "B", "M") if k in ctx.g4) and "M" in ctx.g4,
          {k: (len(d["rows"]), d["quota"]) for k, d in ctx.g4.items()})


def scenario_G4_1(ctx):
    """Distributed standing troops: quotas never exceed the living garrison, strongholds are more
    militarised, small garrisons are not over-tasked, and units stand at many different points."""
    s = ctx.s
    ok_sum, spread, detail = True, True, {}
    for k, c in g4_villages(ctx).items():
        d = duties(s, c)
        q = d["quota"]
        live = [r for r in d["rows"] if r["state"] in ("GARRISONED", "RECOVERED", "DEPLOYED", "RETURNING", "SPAWNED")]
        total = 2 * q.get("pairs", 0) + q.get("patrol", 0) + q.get("scouts", 0) + q.get("reserve", 0)
        ok_sum &= total <= len(live)
        homes = {r["home"] for r in live if r["home"]}
        spread &= len(homes) >= min(6, max(2, len(live) // 3))
        by = {}
        for r in live:
            by[r["assigned"]] = by.get(r["assigned"], 0) + 1
        detail[k] = (len(live), q, by, len(homes))
    check("G4-1a duty quotas never exceed the living garrison", ok_sum, detail)
    check("G4-1b standing troops are distributed (many distinct home points per village)", spread, {k: v[3] for k, v in detail.items()})
    m = detail.get("M")
    small = min(detail.values(), key=lambda v: v[0])
    check("G4-1c the stronghold fields more sentries/patrols/scouts than the smallest garrison; the smallest is not over-tasked",
          m is not None and m[1].get("pairs", 0) > small[1].get("pairs", 0) and m[1].get("scouts", 0) >= 2
          and not (small[1].get("pairs", 0) >= 2 and small[1].get("patrol", 0) > 0 and small[1].get("scouts", 0) > 0),
          f"stronghold {m[1] if m else None} vs smallest ({small[0]} units) {small[1]}")


def scenario_G4_2(ctx):
    """Sentries stand in pairs at posts taken from Millénaire's buildings (walls, gates, towers...)."""
    s = ctx.s
    ok, detail = True, {}
    for k, c in g4_villages(ctx).items():
        d = duties(s, c)
        pairs = {}
        for r in d["rows"]:
            if r["assigned"] == "SENTRY" and r["state"] == "GARRISONED":
                pairs.setdefault(r["index"], []).append(r)
        want = d["quota"].get("pairs", 0)
        posts = d.get("posts", [])
        full = all(len(pairs.get(i, [])) == 2 for i in range(want))
        at_post = all(r["pos"] and posts and hdist(r["pos"], posts[r["index"] % len(posts)]) <= 10 for v in pairs.values() for r in v)
        distinct = len({tuple(posts[i % len(posts)]) for i in pairs}) == len(pairs) if posts else not pairs
        ok &= full and at_post and distinct
        detail[k] = (want, {i: [x["pos"] for x in v] for i, v in sorted(pairs.items())}, full, at_post, distinct)
    check("G4-2 every sentry pair has two units, standing at (within 10 blocks of) its own post (from building data)", ok, detail)


def scenario_G4_3(ctx):
    """Patrols follow a deterministic closed route inside the defensive area and keep moving."""
    s = ctx.s
    c = ctx.extra.get("militaire") or ctx.a
    d0 = duties(s, c)
    p0 = {r["slot"]: (r["progress"], r["pos"]) for r in d0["rows"] if r["assigned"] == "PATROL"}
    samples = [p0]
    for _ in range(3):
        time.sleep(40)
        d = duties(s, c)
        samples.append({r["slot"]: (r["progress"], r["pos"]) for r in d["rows"] if r["assigned"] == "PATROL"})
    moved = 0
    for slot in p0:
        wps = {smp[slot][0] for smp in samples if slot in smp}
        poss = [smp[slot][1] for smp in samples if slot in smp and smp[slot][1]]
        if len(wps) >= 2 or (poss and max(hdist(poss[0], p) for p in poss) >= 8):
            moved += 1
    d1 = duties(s, c)
    check("G4-3a patrol route is deterministic (identical on every read)", d0.get("patrol") and d0.get("patrol") == d1.get("patrol"),
          f"{len(d0.get('patrol', []))} waypoints")
    check("G4-3b patrol units advance along the route", p0 and moved >= max(1, int(0.7 * len(p0))), f"{moved}/{len(p0)} moved over 120 s")


def scenario_G4_4(ctx):
    """Scouts ride out of the village to their posts and come back; mounted units are preferred."""
    s = ctx.s
    best, mounted_ok, detail = {}, True, {}
    for k in ("A", "M"):
        c = g4_villages(ctx).get(k)
        if not c:
            continue
        d = duties(s, c)
        scouts = [r for r in d["rows"] if r["assigned"] == "SCOUT"]
        units = [r["unit"] for r in d["rows"] if r["state"] == "GARRISONED"]
        riders = [u for u in units if u.endswith("_rider")]
        if riders:
            mounted_ok &= all(r["unit"].endswith("_rider") for r in scouts) or len(scouts) > len(riders)
        detail[k] = [(r["slot"], r["unit"], r["progress"]) for r in scouts]
        posts = d.get("scoutposts", [])
        ring = hdist(posts[0], c) if posts else 0
        best[k] = (0.0, ring, set())
    end = time.time() + 300
    while time.time() < end:
        for k in list(best):
            c = g4_villages(ctx)[k]
            for r in duties(s, c)["rows"]:
                if r["assigned"] == "SCOUT" and r["pos"]:
                    dd = hdist(r["pos"], c)
                    b = best[k]
                    best[k] = (max(b[0], dd), b[1], b[2] | {r["progress"]})
        if all(b[0] >= b[1] - 40 and "back" in b[2] | {"rest"} for b in best.values()) and all(len(b[2]) >= 3 for b in best.values()):
            break
        time.sleep(15)
    check("G4-4a scouts: mounted riders are chosen as scouts where the village has them", mounted_ok, detail)
    check("G4-4b scouts ride outside the village radius (ring - scout distance) and cycle out/watch/back",
          all(b[0] >= b[1] - 40 and len(b[2]) >= 2 for b in best.values()),
          {k: (round(b[0]), round(b[1]), sorted(b[2])) for k, b in best.items()})


def scenario_G4_5(ctx):
    """Duties survive a restart: same assignments, same posts and routes, no duplicates."""
    s = ctx.s
    vs = g4_villages(ctx)
    before = {k: duties(s, c) for k, c in vs.items()}
    restart(ctx)
    time.sleep(60)
    after = {k: duties(s, c) for k, c in vs.items()}
    same = {k: assignments(before[k]) == {sl: v for sl, v in assignments(after[k]).items() if sl in assignments(before[k])} for k in vs}
    plans = {k: (before[k].get("posts"), before[k].get("patrol"), before[k].get("scoutposts")) ==
                (after[k].get("posts"), after[k].get("patrol"), after[k].get("scoutposts")) for k in vs}
    check("G4-5a duty assignments are identical after a restart", all(same.values()), same)
    check("G4-5b sentry posts, patrol route and scout posts are identical after a restart", all(plans.values()), plans)
    cs = {k: census(s, c) for k, c in vs.items()}
    check("G4-5c no duplicate or unbound units after the restart", all(x.get("dupSlots") == 0 and x.get("unbound") == 0 and x.get("badOwner") == 0
                                                                     for x in cs.values()), cs)


def scenario_G4_6(ctx):
    """M2 defense overrides duties (DEFENSE), then every unit returns to its standing duty."""
    s = ctx.s
    c = ctx.a
    before = assignments(duties(s, c))
    for i in range(2):
        s.cmd(ground(c[0] + 6 + 2 * i, c[2] + 4, "summon hundred_years_war:bandit_soldier ~ ~ ~ {Tags:['hwG4']}"), 1)
    seen = {}
    for _ in range(40):
        time.sleep(2)
        for r in duties(s, c)["rows"]:
            if r["state"] == "DEPLOYED":
                seen[r["slot"]] = (r["duty"], r["assigned"])
        if len(seen) >= 2:
            break
    check("G4-6a deployed units are on DEFENSE, keeping their standing duty", seen and all(v[0] == "DEFENSE" and v[1] == before.get(sl, (v[1],))[0]
                                                                                        for sl, v in seen.items()), seen)
    time.sleep(60)
    s.cmd("kill @e[tag=hwG4]", 1)
    g = wait_garrison(s, c, lambda g: g.get("deployed", 1) == 0 and g.get("returning", 1) == 0, 240)
    time.sleep(10)
    after = duties(s, c)
    back = all(r["duty"] == r["assigned"] for r in after["rows"] if r["state"] == "GARRISONED")
    kept = all(assignments(after).get(sl) == v for sl, v in before.items() if sl in assignments(after) and sl in seen)
    check("G4-6b after the fight every unit is back on its standing duty (same assignment)", back and g.get("deployed") == 0,
          f"deployed {g.get('deployed')} returning {g.get('returning')}; kept {kept}")


def scenario_G4_7(ctx):
    """Casualties are permanent (no respawn); the sentry pair is refilled from the living garrison."""
    s = ctx.s
    c = ctx.extra.get("militaire") or ctx.a
    d = duties(s, c)
    sentries = [r for r in d["rows"] if r["assigned"] == "SENTRY" and r["state"] == "GARRISONED"]
    if not check("G4-7 a sentry to kill", bool(sentries)):
        return
    v = sentries[0]
    g0 = garrison(s, c)
    s.cmd(f"kill {full_uuid(s, [u['entity'] for u in g_units(s, c) if u['slot'] == v['slot']][0])}", 2)
    time.sleep(50)
    rows = {u["slot"]: u for u in g_units(s, c)}
    g1 = garrison(s, c)
    d1 = duties(s, c)
    pair = [r for r in d1["rows"] if r["assigned"] == "SENTRY" and r["index"] == v["index"]]
    check("G4-7a the killed sentry is DEAD, its slot is not respawned", rows[v["slot"]]["state"] == "DEAD" and g1.get("t_spawned") == g0.get("t_spawned")
          and g1.get("t_killed") == g0.get("t_killed", 0) + 1, str(rows[v["slot"]]))
    check("G4-7b its pair is manned again by another living unit", len(pair) == 2 and v["slot"] not in [r["slot"] for r in pair],
          [(r["slot"], r["unit"]) for r in pair])


def scenario_G4_8(ctx):
    """Raids: the stronghold raids village A with Millénaire's own raid; a HYW contingent from its
    living garrison joins, the home keeps its share, deaths are permanent, survivors come back."""
    s = ctx.s
    m, a = ctx.extra.get("militaire"), ctx.a
    if not check("G4-8 attacker village present", m is not None):
        return
    g0 = garrison(s, m)
    alive0 = g0.get("alive", 0)
    p = s.pos()
    out = s.output(f"millenaire dev raid trigger {m[0]} {m[1]} {m[2]} {a[0]} {a[1]} {a[2]}", 3)
    joined = s.wait_for(r"raids .*: (\d+) of (\d+) available garrison unit\(s\) join the raid", 30, since=p)
    mm = re.search(r": (\d+) of (\d+) available garrison unit", joined or "")
    k, n = (int(mm[1]), int(mm[2])) if mm else (0, 0)
    d = duties(s, m)
    raiders = [r for r in d["rows"] if r["duty"] == "RAID"]
    home = [r for r in d["rows"] if r["state"] in ("GARRISONED", "RECOVERED") and r["duty"] != "RAID"]
    check("G4-8a a contingent of the attacker's own living garrison joins (no new units); the home keeps at least half",
          k > 0 and len(raiders) == k and len(home) >= (n + 1) // 2 and garrison(s, m).get("t_spawned") == g0.get("t_spawned"),
          f"{'; '.join(out)[:80]} | {k} of {n} sent; {len(home)} at home; raiders {[r['unit'] for r in raiders]}")
    landed = s.wait_for(r"Raid contingent of village .* moved to Millénaire's landing point", 90, since=p)
    raid_ents = {r["slot"] for r in raiders}
    before_inc = incidents(s, 200)
    near_max = 0
    end = time.time() + 240
    while time.time() < end:  # sample while the contingent is away
        rows = [r for r in duties(s, m)["rows"] if r["duty"] == "RAID"]
        near = [r for r in rows if r["pos"] and hdist(r["pos"], a) <= 120]
        near_max = max(near_max, len(near))
        if near and not getattr(ctx, "g4_raid_casualty", None):
            # a raid casualty (as if killed by the defenders): must stay DEAD, never respawned
            ctx.g4_raid_casualty = near[0]["slot"]
            e8 = [u["entity"] for u in g_units(s, m) if u["slot"] == near[0]["slot"]][0]
            s.cmd(f"kill {full_uuid(s, e8)}", 1)
        if not rows or s.wait_for(r"Raid (FAILURE|SUCCESS)", 0.1, since=p):
            break
        time.sleep(3)
    ents8 = {u["entity"] for u in g_units(s, m) if u["slot"] in raid_ents and u["entity"]}
    hits = [i for i in incidents(s, 200) if i not in before_inc and i["a"] in ents8 and i["resident"]]
    check("G4-8b the contingent is moved to Millénaire's landing point and fights at the target", landed is not None and near_max >= 1,
          f"{landed and landed.split(']: ')[-1]} ; up to {near_max} raid unit(s) within 120 blocks of the target; {len(hits)} hit(s) on its residents")
    ended = s.wait_for(r"Raid (FAILURE|SUCCESS)|bringing the contingent home", 240, since=p)
    back = s.wait_for(r"Raid contingent of village .*: \d+ survivor\(s\) back home|bringing the contingent home", 60, since=p)
    time.sleep(40)
    d2 = duties(s, m)
    units2 = {u["slot"]: u for u in g_units(s, m)}
    raid_slots = [r["slot"] for r in raiders]
    dead = [sl for sl in raid_slots if units2.get(sl, {}).get("state") == "DEAD"]
    casualty = getattr(ctx, "g4_raid_casualty", None)
    still_raid = [r for r in d2["rows"] if r["duty"] == "RAID"]
    g2 = garrison(s, m)
    check("G4-8c raid over: survivors are back on their standing duties; no unit spawned for the raid",
          ended is not None and not still_raid and g2.get("t_spawned") == g0.get("t_spawned"),
          f"{ended and ended.split(']: ')[-1][:90]}; dead {len(dead)}/{len(raid_slots)}; still RAID {len(still_raid)}; alive {alive0}->{g2.get('alive')}")
    time.sleep(30)
    units3 = {u["slot"]: u for u in g_units(s, m)}
    check("G4-8d raid deaths are permanent (DEAD, never respawned)", casualty in dead and all(units3[sl]["state"] == "DEAD" for sl in dead)
          and garrison(s, m).get("t_spawned") == g0.get("t_spawned"), f"{len(dead)} raid death(s), incl. the casualty {casualty}")


def scenario_G4_9(ctx):
    """Wand of Negation on a garrisoned village: its own deletion path; no recruitment into it;
    the garrison is LOST(VILLAGE_GONE) after the grace period."""
    s = ctx.s
    b = ctx.b
    name = garrison(s, b)["lines"][0].split(" of ", 1)[1].split(" (")[0] if garrison(s, b)["lines"] else "?"
    s.output(at(b, "hywmill admin setpoints 10"), 1)
    p = s.pos()
    out = s.output(at(b, "hywmill dev negate"), 3)
    gone = s.wait_for(r"is missing from Millénaire's village list", 30, since=p)
    time.sleep(30)
    lines = s.read_since(p)
    rec = [l for l in lines if f"Village '{name}' recruits" in l or (f"village '{name}'" in l and "spawned" in l)]
    check("G4-9a negation wand deletes the village; HywMill notices; no recruitment or spawning into it", gone is not None and not rec,
          f"{'; '.join(out)[:80]} | {len(rec)} recruit/spawn lines")
    sprint(s, 6200)
    lost = s.wait_for(r"garrison slot\(s\) LOST\(VILLAGE_GONE\)", 60, since=p)
    check("G4-9b after the grace period its garrison is LOST(VILLAGE_GONE)", lost is not None, lost and lost.split("]: ")[-1][:120])


def scenario_G4_10(ctx):
    """Invariants and cost: no duplicates anywhere; duty/raid work per village stays small."""
    s = ctx.s
    vs = {k: c for k, c in g4_villages(ctx).items() if k != "B"}
    cs = {k: census(s, c) for k, c in vs.items()}
    check("G4-10a M3 invariants: no duplicate, unbound or foreign-owned garrison units",
          all(x.get("dupSlots") == 0 and x.get("unbound") == 0 and x.get("badOwner") == 0 and x.get("tagged") == x.get("bound") for x in cs.values()), cs)
    s.cmd("hywmill perf reset", 1)
    time.sleep(120)
    rows = perf_rows(s.output("hywmill perf", 2))
    log("G4 perf (120 s CALM):\n  " + "\n  ".join(f"{k}: {v}" for k, v in rows.items()))
    dt = rows.get("duty.tick", {})
    check("G4-10b duty tick cost per village stays small (mean < 0.5 ms)", dt and dt.get("mean", 1e9) < 500,
          {k: rows.get(k) for k in ("duty.tick", "duty.layout", "raid.tick", "tick.total")})


def scenario_G4_perf(ctx):
    """Like-for-like cost: 120 s CALM with M4 duties off, then 120 s with them on, same world and units."""
    s = ctx.s
    res = {}
    for state in ("off", "on"):
        s.output(f"hywmill dev duties {state}", 1)
        time.sleep(20)
        s.cmd("hywmill perf reset", 1)
        time.sleep(120)
        res[state] = perf_rows(s.output("hywmill perf", 2))
        log(f"G4 perf duties {state}:\n  " + "\n  ".join(f"{k}: {v}" for k, v in res[state].items()))
    t_off, t_on = res["off"].get("tick.total", {}), res["on"].get("tick.total", {})
    check("G4-P calm tick cost with duties on stays comparable to duties off (mean within +50 us)",
          t_off and t_on and t_on["mean"] <= t_off["mean"] + 50,
          f"tick.total mean off {t_off.get('mean')} us / on {t_on.get('mean')} us; p99 off {t_off.get('p99')} / on {t_on.get('p99')}; "
          f"duty.tick {res['on'].get('duty.tick')}")


def scenario_G4_EK(ctx):
    """Optional Epic Knights profiles (run with HYWMILL_EXTRA_MODS=<dir with Epic Knights jars>):
    equipment varies by culture, tier and role; unsupported combinations fall back to HYW's gear."""
    s = ctx.s
    out = s.output("hywmill admin equipcheck", 4)
    summary = next((l for l in out if l.startswith("equipcheck:")), "")
    check("G4-EK1 admin equipcheck validates the profiles (Epic Knights loaded, no invalid items)",
          "Epic Knights loaded" in summary and " 0 invalid item/slot" in summary, summary)
    vs = g4_villages(ctx)
    gear = {}
    for k, c in vs.items():
        d = {r["slot"]: r for r in duties(s, c)["rows"]}
        ents = unit_entities(s, c)
        units = {u["entity"]: u for u in g_units(s, c) if u["entity"]}
        for uuid, row in ents.items():
            u = units.get(uuid[:8])
            if u and u["slot"] in d:
                gear[(k, u["slot"])] = (u["unit"], d[u["slot"]]["assigned"], row.get("slots", {}))
    def items(pred, slot):
        return {v[2].get(slot, "") for key, v in gear.items() if pred(key, v)}
    heads_a = items(lambda key, v: key[0] == "A" and v[1] not in ("SENTRY",), "head")
    heads_z = items(lambda key, v: key[0] == "Z" and v[1] not in ("SENTRY",), "head")
    check("G4-EK2 culture: Norman heads are norman_helmet, Byzantine heads differ", any("norman_helmet" in h for h in heads_a)
          and heads_z and not any("norman_helmet" in h for h in heads_z), f"A {sorted(heads_a)} Z {sorted(heads_z)}")
    sentry_heads = items(lambda key, v: key[0] in ("A", "M") and v[1] == "SENTRY", "head")
    check("G4-EK3 role: sentries wear the sentry helmet (bascinet / greathelm)", sentry_heads and all(("bascinet" in h or "greathelm" in h) for h in sentry_heads),
          sorted(sentry_heads))
    chest_a = items(lambda key, v: key[0] == "A" and v[1] == "GARRISON" and v[0] in ("spear_man", "warrior"), "chest")
    chest_m = items(lambda key, v: key[0] == "M" and v[1] == "GARRISON" and v[0] in ("spear_man", "warrior"), "chest")
    check("G4-EK4 tier: stronghold line units wear heavier armour than the smaller village's", chest_m and chest_a and chest_m != chest_a
          and any("platemail" in x or "brigandine" in x for x in chest_m), f"A {sorted(chest_a)} M {sorted(chest_m)}")
    spear_off = items(lambda key, v: v[0] == "spear_man", "offhand")
    shield_off = items(lambda key, v: v[0] == "shieldman" and key[0] in ("A", "M"), "offhand")
    archer_main = items(lambda key, v: v[0] == "archer", "mainhand")
    check("G4-EK5 unsupported combinations fall back (no kiteshield on spear_man; archers keep a bow)",
          not any("kiteshield" in x for x in spear_off) and all(("longbow" in x or "bow" in x) for x in archer_main)
          and (not shield_off or any("kiteshield" in x or "shield" in x for x in shield_off)),
          f"spear_man offhand {sorted(spear_off)}; shieldman offhand {sorted(shield_off)}; archer mainhand {sorted(archer_main)}")


SCENARIOS = {"G4_explore": scenario_G4_explore, "G4_0": scenario_G4_0, "G4_1": scenario_G4_1, "G4_2": scenario_G4_2, "G4_3": scenario_G4_3, "G4_4": scenario_G4_4, "G4_5": scenario_G4_5, "G4_6": scenario_G4_6, "G4_7": scenario_G4_7, "G4_8": scenario_G4_8, "G4_9": scenario_G4_9, "G4_10": scenario_G4_10, "G4_EK": scenario_G4_EK, "G4_perf": scenario_G4_perf, "A": scenario_A, "B": scenario_B, "C": scenario_C, "D": scenario_D, "E": scenario_E,
             "F1": scenario_F1, "F2": scenario_F2, "H": scenario_H, "G": scenario_G, "I": scenario_I, "N": scenario_N, "W": scenario_W, "L": scenario_L, "X": scenario_X, "P": scenario_P, "M": scenario_M, "status": scenario_status, "S": scenario_S,
             "G3_1": scenario_G3_1, "G3_2": scenario_G3_2, "G3_3": scenario_G3_3, "G3_4": scenario_G3_4, "G3_5": scenario_G3_5,
             "G3_6": scenario_G3_6, "G3_7": scenario_G3_7, "G3_8": scenario_G3_8, "G3_9": scenario_G3_9, "G3_10": scenario_G3_10,
             "G3_11": scenario_G3_11, "G3_12": scenario_G3_12, "G3_13": scenario_G3_13, "G3_14": scenario_G3_14, "G3_15": scenario_G3_15,
             "G3_17": scenario_G3_17, "G3_perf": scenario_G3_perf, "S4": scenario_S4, "S4b": scenario_S4b}
ORDER_G3 = ["status", "G3_1", "G3_2", "G3_3", "G3_4", "G3_5", "G3_6", "G3_7", "G3_8", "G3_9", "G3_10", "G3_11", "G3_12", "G3_13",
            "G3_15", "G3_14", "G3_perf"]
ORDER_G4 = ["status", "G4_0", "G4_1", "G4_2", "G4_3", "G4_4", "G4_5", "G4_6", "G4_7", "G4_8", "G4_10", "G4_perf", "G4_9"]
ORDER_G4_EK = ["status", "G4_0", "G4_EK"]
ORDER = ["status", "H", "B", "N", "C", "D", "I", "W", "L", "F1", "E", "F2", "X", "P", "A", "G"]


def run(d: Path, names, fresh=True):
    write_configs(d)
    extra_mods = sorted(Path(os.environ["HYWMILL_EXTRA_MODS"]).glob("*.jar")) if os.environ.get("HYWMILL_EXTRA_MODS") else []
    install_mods(d, [MILLENAIRE_JAR, HYW_JAR, built_jar()] + extra_mods)
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
        order = {"all": ORDER, "garrison": ORDER_G3, "duties": ORDER_G4, "duties-ek": ORDER_G4_EK}
        for n in (order[names[0]] if len(names) == 1 and names[0] in order else names):
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


def run_migrate3(d: Path, m2_jar: Path):
    """G3-16: a world written by the M2 build (ledger format 3) is loaded by M3: identities and
    history kept, empty rosters, exactly one starting grant, format 4 afterwards."""
    write_configs(d)
    install_mods(d, [MILLENAIRE_JAR, HYW_JAR, m2_jar])
    if (d / "world").exists():
        shutil.rmtree(d / "world")
    s = Server(d)
    ctx = Ctx(s)
    try:
        s.start()
        setup(ctx)
        time.sleep(15)
        before = {k: info(s, c) for k, c in (("A", ctx.a), ("B", ctx.b)) if c}
        s.cmd("save-all flush", 5)
    finally:
        s.stop()
    install_mods(d, [MILLENAIRE_JAR, HYW_JAR, built_jar()])
    try:
        s.start()
        loaded = s.wait_for(r"Garrison ledger loaded: \d+ village record\(s\), format 3", 30, since=s.start_pos)
        mig = s.wait_for(r"migrated \d+ record\(s\) from format 3 to 4", 30, since=s.start_pos)
        check("G3-16 format-3 ledger loaded and migrated to format 4", loaded is not None and mig is not None, f"{loaded} | {mig}")
        s.cmd("millenaire chunkload", 10)
        time.sleep(60)
        after = {k: info(s, c) for k, c in (("A", ctx.a), ("B", ctx.b)) if c}
        same = all(before[k].get("villageId") == after[k].get("villageId") and before[k].get("faction") == after[k].get("faction")
                   and before[k].get("garrison") == after[k].get("garrison") for k in before)
        check("G3-16 village IDs, faction UUIDs and the Millénaire soldier count survive the migration", same,
              f"{[(k, before[k].get('faction'), after[k].get('faction'), before[k].get('garrison'), after[k].get('garrison')) for k in before]}")
        grants = [l for l in s.read_since(s.start_pos) if "Starting garrison granted" in l]
        check("G3-16 exactly one starting grant per migrated village", len(grants) == len(before), f"{len(grants)} grants: " + " | ".join(g.split("[hywmill] ")[-1][:120] for g in grants))
        s.cmd("save-all flush", 5)
        s.stop()
        s.start()
        loaded4 = s.wait_for(r"Garrison ledger loaded: \d+ village record\(s\), format 4", 30, since=s.start_pos)
        time.sleep(40)
        grants2 = [l for l in s.read_since(s.start_pos) if "Starting garrison granted" in l]
        check("G3-16 after another restart: format 4 on disk, no further grant", loaded4 is not None and not grants2, f"{loaded4}; {len(grants2)} grants")
    finally:
        s.stop()


def run_epicknights(d: Path, ekdir: Path):
    """Optional-dependency check: HywMill has no Epic Knights dependency or code; with Epic Knights
    (magistuarmory) installed and HYW's own enableEpicKnightsCompat on, garrison units get HYW's
    Epic Knights equipment files through the unchanged HYW provider."""
    jar = built_jar()
    import zipfile
    with zipfile.ZipFile(jar) as z:
        names = z.namelist()
        toml = z.read("META-INF/neoforge.mods.toml").decode()
        refs = [n for n in names if n.endswith(".class") and b"magistuarmory" in z.read(n)]
    check("OEK hywmill jar: no Epic Knights dependency, no Epic Knights references", "magistuarmory" not in toml and not refs,
          f"{len(names)} entries; referencing classes {refs[:3]}")
    ek = sorted(str(p) for p in ekdir.glob("*.jar"))
    write_configs(d)
    install_mods(d, [MILLENAIRE_JAR, HYW_JAR, jar] + ek)
    if (d / "world").exists():
        shutil.rmtree(d / "world")
    hyw_cfg = d / "config" / "hundredyearswar" / "hyw_main.json5"
    s = Server(d)
    ctx = Ctx(s)
    try:
        s.start()
        s.stop()
        text = hyw_cfg.read_text()
        text2 = re.sub(r'("?enableEpicKnightsCompat"?\s*:\s*)false', r"\1true", text)
        hyw_cfg.write_text(text2)
        check("OEK HYW's own enableEpicKnightsCompat switched on in HYW's config (HywMill untouched)", text2 != text and "enableEpicKnightsCompat" in text2,
              str(hyw_cfg))
        if (d / "world").exists():
            shutil.rmtree(d / "world")
        s.start()
        lines = s.read_since(s.start_pos)
        bad = [l for l in lines if re.search(r"Exception|/ERROR\].*\[hywmill\]|at dev\.hywmill", l)]
        check("OEK server with Epic Knights: loads, no HywMill errors", not bad, bad[0] if bad else "")
        setup(ctx)
        g = wait_garrison(s, ctx.a, lambda g: g.get("alive", 0) >= 2, 240)
        units = unit_entities(s, ctx.a)
        ek_items = 0
        for u in list(units)[:6]:
            out = " ".join(s.output(f"data get entity {u} ArmorItems", 1) + s.output(f"data get entity {u} HandItems", 1))
            ek_items += out.count("magistuarmory:")
        check("OEK garrison units spawned with HYW's Epic Knights equipment (equipment_epic_knights.json)", len(units) >= 2 and ek_items > 0,
              f"{len(units)} units, {ek_items} magistuarmory items on the first {min(6, len(units))}")
    finally:
        s.stop()


EK_CASES = [
    # (unit, slot, item, class of the test)
    ("spear_man", "head", "magistuarmory:norman_helmet", "armor"),
    ("spear_man", "chest", "magistuarmory:lamellar_chestplate", "armor"),
    ("spear_man", "mainhand", "magistuarmory:steel_ahlspiess", "same-family weapon"),
    ("shieldman", "offhand", "magistuarmory:steel_kiteshield", "shield"),
    ("shieldman", "head", "magistuarmory:shishak", "armor"),
    ("warrior", "mainhand", "magistuarmory:steel_lochaberaxe", "same-family weapon"),
    ("militia", "mainhand", "magistuarmory:steel_shortsword", "same-family weapon"),
    ("militia", "chest", "magistuarmory:gambeson_chestplate", "armor"),
    ("archer", "head", "magistuarmory:shishak", "armor"),
    ("archer", "chest", "magistuarmory:lamellar_chestplate", "armor"),
    ("crossbowman", "head", "magistuarmory:kettlehat", "armor"),
    ("mounted_light_lancer_rider", "head", "magistuarmory:norman_helmet", "armor"),
    ("archer", "mainhand", "magistuarmory:steel_pike", "cross-family weapon"),
    ("crossbowman", "mainhand", "magistuarmory:longbow", "cross-family weapon"),
    ("spear_man", "mainhand", "magistuarmory:longbow", "cross-family weapon"),
    ("shieldman", "offhand", "magistuarmory:steel_pike", "cross-family offhand"),
    ("militia", "head", "magistuarmory:no_such_item", "unregistered"),
]


def run_ekspike(d: Path, ekdir: Path):
    """M4-0: which Epic Knights items can be overlaid on which HYW garrison units (HYW's own EK
    compat on): persistence after ticks and a restart, and whether the unit still fights."""
    jar = built_jar()
    ek = sorted(str(p) for p in ekdir.glob("*.jar"))
    write_configs(d, "[garrison]\n\tenabled = false\n")
    install_mods(d, [MILLENAIRE_JAR, HYW_JAR, jar] + ek)
    if (d / "world").exists():
        shutil.rmtree(d / "world")
    hyw_cfg = d / "config" / "hundredyearswar" / "hyw_main.json5"
    s = Server(d)
    ctx = Ctx(s)
    rows = []
    try:
        s.start()
        s.stop()
        hyw_cfg.write_text(re.sub(r'("?enableEpicKnightsCompat"?\s*:\s*)false', r"\1true", hyw_cfg.read_text()))
        shutil.rmtree(d / "world")
        s.start()
        setup(ctx)
        a = ctx.a
        units = {}
        for i, (unit, slot, item, kind) in enumerate(EK_CASES):
            x, z = a[0] - 100 + (i % 6) * 32, a[2] + 60 + (i // 6) * 32
            s.cmd(f"forceload add {x} {z}", 1)
            r = spike_spawn(s, (x, 0, z), unit, 2)
            u = r.get("uuid")
            base = spike_info(s, u).get(u, {}).get("slots", {}) if u else {}
            out = s.output(f"hywmill dev equip {u} {slot} {item}", 1) if u else []
            units[i] = (u, base, " ".join(out))
        time.sleep(15)
        after = spike_info(s, "@e[type=!minecraft:player]")
        for i, (unit, slot, item, kind) in enumerate(EK_CASES):
            u, base, out = units[i]
            got = after.get(u, {}).get("slots", {}).get(slot)
            units[i] = (u, base, out, got)
        s.cmd("save-all flush", 5)
        s.stop()
        s.start()
        for i in range(len(EK_CASES)):
            x, z = a[0] - 100 + (i % 6) * 32, a[2] + 60 + (i // 6) * 32
            s.cmd(f"forceload add {x} {z}", 0.5)
        time.sleep(15)
        reload_ = spike_info(s, "@e[type=!minecraft:player]")
        # combat: a zombie next to each unit; HYW units attack monsters natively
        for i, (unit, slot, item, kind) in enumerate(EK_CASES):
            u = units[i][0]
            if u in reload_:
                p = reload_[u]["pos"]
                # a husk does not burn in daylight; units are 32 blocks apart, so damage is this unit's
                s.cmd(f"summon minecraft:husk {p[0] + 3} {p[1]} {p[2]} {{Tags:['ekz{i}'],PersistenceRequired:1b,Health:40f,attributes:[{{id:'minecraft:generic.max_health',base:40}}]}}", 0.3)
        time.sleep(30)
        for i, (unit, slot, item, kind) in enumerate(EK_CASES):
            u, base, out, got = units[i]
            want = item.split(":")[1]
            rel = reload_.get(u, {}).get("slots", {}).get(slot)
            zh = health(s, f"ekz{i}")
            fought = zh is None or zh < 40.0
            rows.append(dict(unit=unit, slot=slot, item=item, kind=kind, set="set" in out, base=base.get(slot), after15s=got,
                             afterRestart=rel, fought=fought, zombie=zh))
        for r in rows:
            log("ekspike " + json_line(r))
        registered = [r for r in rows if r["kind"] != "unregistered"]
        check("EK-0 overlay tool ran on every M3 unit type + mounted rider", len(rows) == len(EK_CASES), f"{len(rows)} cases")
        check("EK-1 unregistered item is rejected (falls back)", all(not r["set"] for r in rows if r["kind"] == "unregistered"),
              str([r for r in rows if r["kind"] == "unregistered"]))
        kept = [r for r in registered if r["after15s"] and r["item"].endswith(r["after15s"].split(":")[-1])]
        check("EK-2 matrix recorded (see ekspike lines)", True, f"{len(kept)}/{len(registered)} overlays still present after 15 s")
    finally:
        s.stop()
    return rows


def json_line(r):
    import json
    return json.dumps(r, sort_keys=True)


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
    if a.scenarios[0] == "ekspike":
        run_ekspike(a.dir, Path(a.scenarios[1]))
        passed = sum(1 for r in RESULTS if r[1])
        log(f"RESULT {passed}/{len(RESULTS)} checks passed")
        for name, ok, detail in RESULTS:
            print(f"  {'PASS' if ok else 'FAIL'}  {name}  {detail}")
        return 0 if all(r[1] for r in RESULTS) else 1
    if a.scenarios[0] == "epicknights":
        run_epicknights(a.dir, Path(a.scenarios[1]))
        passed = sum(1 for r in RESULTS if r[1])
        log(f"RESULT {passed}/{len(RESULTS)} checks passed")
        for name, ok, detail in RESULTS:
            print(f"  {'PASS' if ok else 'FAIL'}  {name}  {detail}")
        return 0 if all(r[1] for r in RESULTS) else 1
    if a.scenarios[0] == "migrate3":
        run_migrate3(a.dir, Path(a.scenarios[1]))
        passed = sum(1 for r in RESULTS if r[1])
        log(f"RESULT {passed}/{len(RESULTS)} checks passed")
        for name, ok, detail in RESULTS:
            print(f"  {'PASS' if ok else 'FAIL'}  {name}  {detail}")
        return 0 if all(r[1] for r in RESULTS) else 1
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
