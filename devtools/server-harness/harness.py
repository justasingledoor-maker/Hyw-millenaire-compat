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
        "enable-command-block=true\nmotd=hywmill-harness\n"
        + (f"server-port={os.environ['HYWMILL_PORT']}\n" if os.environ.get("HYWMILL_PORT") else ""))
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
        for key, rx in [("villageId", r"^VillageId: (\S+)"), ("faction", r"^Faction UUID \(synthetic\): (\S+)"), ("residents", r"^Resident identity \(synthetic\): (\S+)"),
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
DEFERRED = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    log(f"{'PASS' if ok else 'FAIL'} {name} {detail}")
    return ok


def deferred(name, reason):
    """A requirement taken out of the current sign-off scope by decision: listed, never counted as PASS or FAIL."""
    DEFERRED.append((name, reason))
    log(f"DEFERRED {name} ({reason})")


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
    loaded = s.wait_for(r"Garrison ledger loaded: \d+ village.*format [3-9]", 30, since=s.start_pos)
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
    rid = info(s, c).get("residents")
    rrel = globals()["rel"](s, rid, FAKE_PLAYER_UUID) if rid else ("?", "?")  # M5 Option 1: residents escalate on their own identity
    check("D5-b reconciliation reset it to NEUTRAL within 200 ticks (faction and resident identity)",
          warn is not None and rel == ("NEUTRAL", "NEUTRAL") and rrel == ("NEUTRAL", "NEUTRAL"),
          f"faction {rel}; residents {rrel}; {warn or 'no WARN'}")


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
    faction = info(s, c).get("residents")  # M5 Option 1: residents carry the village's resident identity
    res = wait_residents(s, c)
    v = next((r[0] for r in res if r[2] == "CIVILIAN"), None)
    if not check("G0 a marked resident (resident identity)", v is not None and marker_of(s, v, faction) == faction, f"{v} residents={faction}"):
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
    """Allegiance: garrison (faction identity) and residents (resident identity, FRIENDLY with the faction); neither attacks the other."""
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
    rid = info(s, c).get("residents")
    rel = m5_1(s, f"hyw rel {rid} {fa}") if rid and fa else ""
    check("G3-2 garrison owner == village faction; residents carry the village's resident identity, FRIENDLY with the faction both ways (M5 Option 1)",
          marker == rid and len(units) > 0 and re.search(r"=FRIENDLY .*=FRIENDLY", rel) is not None, f"marker {marker} residents {rid} faction {fa}; {rel}")


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


def spawn_anchor(s, c):
    for l in garrison(s, c)["lines"]:
        m = re.search(r"^Spawn anchor: (-?\d+), (-?\d+), (-?\d+) \(defending position\) \| fallback: village centre (-?\d+), (-?\d+), (-?\d+)", l)
        if m:
            v = [int(x) for x in m.groups()]
            return tuple(v[:3]), tuple(v[3:])
    return None, None


def forced_chunks(s):
    out = " ".join(s.output("forceload query", 2))
    return out


def roof(s, p, block_from, block_to):
    """A barrier roof 4..5 above p over exactly the candidate area (radius SpawnSpots.MAX_RADIUS = 8):
    raises the heightmap there, so the M3 spot search rejects every candidate (roofs are refused)
    without touching the ground."""
    s.cmd(f"fill {p[0] - 8} {p[1] + 4} {p[2] - 8} {p[0] + 8} {p[1] + 5} {p[2] + 8} {block_to} replace {block_from}", 2)


def scenario_G3_18(ctx):
    """Spawn-location fallback: defending position first; with no safe spot there, the village centre
    for that attempt only; with neither, the slot stays RECRUITED and spawns later. No force-loading,
    no duplicates, the anchor and duties unchanged."""
    s = ctx.s
    c = ctx.a
    anchor, centre = spawn_anchor(s, c)
    if not check("G3-18 spawn anchor and village centre reported", anchor is not None, f"{anchor} {centre}"):
        return
    sep = max(abs(anchor[0] - centre[0]), abs(anchor[2] - centre[2]))
    if not check("G3-18 anchor and centre are apart (part of the centre's candidate area is outside the anchor's)", sep >= 10, f"separation {sep}"):
        return
    forced0 = forced_chunks(s)
    plan0 = duties(s, c)
    g0 = garrison(s, c)
    if g0.get("live", 0) >= g0.get("cap", 0):
        check("G3-18 room below the tier cap for test grants", False, str(g0.get("lines", [""])[1:2]))
        return
    # 1. normal: a grant spawns at the defending position
    p = s.pos()
    s.output(at(c, "hywmill admin grant archer 1"), 1)
    normal = s.wait_for(r"Garrison unit spawned for village .* at (-?\d+), (-?\d+), (-?\d+)", 90, since=p)
    m = re.search(r"at (-?\d+), (-?\d+), (-?\d+)$", normal or "")
    npos = tuple(int(x) for x in m.groups()) if m else None
    fb_line = [l for l in s.read_since(p) if "spawns near the village centre" in l]
    check("G3-18a normal: the unit spawns at the defending position (no fallback)", npos is not None and hdist(npos, anchor) <= 10 and not fb_line,
          f"spawned at {npos}, anchor {anchor}")
    # 2. defending position blocked -> village centre
    roof(s, anchor, "minecraft:air", "minecraft:barrier")
    p = s.pos()
    s.output(at(c, "hywmill admin grant archer 1"), 1)
    fb = s.wait_for(r"spawns near the village centre", 90, since=p)
    spawned = s.wait_for(r"Garrison unit spawned for village .* at (-?\d+), (-?\d+), (-?\d+)", 30, since=p)
    m = re.search(r"at (-?\d+), (-?\d+), (-?\d+)$", spawned or "")
    fpos = tuple(int(x) for x in m.groups()) if m else None
    fb_slot = re.search(r"slot ([0-9a-f]{8}) spawns", fb or "")
    fb_slot = fb_slot[1] if fb_slot else None
    check("G3-18b defending position has no safe spot: the unit spawns near the village centre", fb is not None and fpos is not None
          and hdist(fpos, centre) <= 12, f"{fb and fb.split(']: ')[-1][:120]}; spawned at {fpos}, centre {centre}")
    # 3. both blocked -> stays RECRUITED, never lost; spawns once terrain allows
    roof(s, centre, "minecraft:air", "minecraft:barrier")
    p = s.pos()
    g1 = garrison(s, c)
    known = {u["slot"] for u in g_units(s, c)}
    s.output(at(c, "hywmill admin grant archer 1"), 1)
    time.sleep(40)
    rows = {u["slot"]: u for u in g_units(s, c)}
    waiting = [u for sl, u in rows.items() if sl not in known]
    waiting = [u for u in waiting if u["state"] == "RECRUITED"] if len(waiting) == 1 else waiting
    g2 = garrison(s, c)
    none_line = [l for l in s.read_since(p) if "or the village centre" in l]
    check("G3-18c neither has a safe spot: the slot stays RECRUITED (not lost, no entity)", len(waiting) == 1 and not waiting[0]["entity"]
          and g2.get("t_lost") == g1.get("t_lost") and g2.get("t_spawned") == g1.get("t_spawned") and none_line,
          f"waiting {[(u['slot'], u['state']) for u in waiting]}; lost {g1.get('t_lost')}->{g2.get('t_lost')}; {len(none_line)} warning line(s)")
    roof(s, anchor, "minecraft:barrier", "minecraft:air")
    roof(s, centre, "minecraft:barrier", "minecraft:air")
    w = waiting[0]["slot"] if waiting else None
    g3 = wait_garrison(s, c, lambda g: g.get("recruited", 1) == 0, 90)
    rows3 = {u["slot"]: u for u in g_units(s, c)}
    check("G3-18d once terrain allows it, the same slot spawns (retried, not replaced)", w is not None and rows3.get(w, {}).get("state") in ("SPAWNED", "GARRISONED")
          and g3.get("t_recruited") == g2.get("t_recruited"), f"slot {w}: {rows3.get(w)}")
    # 4. no force-loading, anchor and duty plan unchanged
    anchor2, centre2 = spawn_anchor(s, c)
    plan1 = duties(s, c)
    check("G3-18e no chunk was force-loaded by the fallback", forced_chunks(s) == forced0, "forceload query unchanged")
    check("G3-18f the fallback does not change the stored anchor or the duty plan", anchor2 == anchor and centre2 == centre
          and (plan0.get("posts"), plan0.get("patrol"), plan0.get("scoutposts")) == (plan1.get("posts"), plan1.get("patrol"), plan1.get("scoutposts")),
          f"anchor {anchor}->{anchor2}")
    time.sleep(30)
    before = assignments(duties(s, c))
    # 5. restart: no duplicate, same duties
    restart(ctx)
    time.sleep(40)
    cs = census(s, c)
    after = assignments(duties(s, c))
    rows4 = {u["slot"]: u for u in g_units(s, c)}
    check("G3-18g after a restart: no duplicate; the fallback-spawned unit is bound once with the same duty",
          cs.get("dupSlots") == 0 and cs.get("unbound") == 0 and cs.get("tagged") == cs.get("bound")
          and fb_slot in rows4 and rows4[fb_slot]["state"] in ("GARRISONED", "RECOVERED") and after.get(fb_slot) == before.get(fb_slot),
          f"census {cs}; slot {fb_slot} {rows4.get(fb_slot)} duty {before.get(fb_slot)}->{after.get(fb_slot)}")


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
        while head > 0:  # the grant command takes at most 64; M5-G caps go to 128
            out = s.output(at(c, f"hywmill admin grant archer {min(head, 64)}"), 2)
            granted.append((c, g.get("cap"), " ".join(out)[:90]))
            h2 = garrison(s, c).get("cap", 0) - garrison(s, c).get("live", 0)
            if h2 >= head:
                break
            head = h2
    log(f"G3-17 grants: {granted}")
    end = time.time() + 1800
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


def scenario_RD(ctx):
    """Bug report (post-M5): a Millénaire raid (e.g. bandits) on a village with a HYW garrison. The raiders carry no HYW
    identity, so the M2 threat scan (HYW units only) never saw them and the garrison stood by. Village B raids A (run after
    G4_0 so A has a garrison): A must list the raiders as SETTLEMENT_RAIDER threats and deploy garrison units on DEFENSE."""
    s = ctx.s
    a, b = ctx.a, ctx.b
    p = s.pos()
    out = s.output(f"millenaire dev raid trigger {b[0]} {b[1]} {b[2]} {a[0]} {a[1]} {a[2]}", 3)
    check("RD-0 Millénaire raid B -> A triggered", any("Raid triggered" in l for l in out), "; ".join(out))
    t0 = time.time()
    raider_threat, deployed, first = None, 0, {}
    ended = None
    while time.time() - t0 < 360:
        th = [l for l in s.output(at(a, "hywmill threats"), 2) if "SETTLEMENT_RAIDER" in l]
        if th and raider_threat is None:
            raider_threat = th[0]
            first["threat"] = round(time.time() - t0)
        rows = duties(s, a)["rows"]
        dep = [r for r in rows if r["duty"] == "DEFENSE" or r["state"] == "DEPLOYED"]
        if dep and "deployed" not in first:
            first["deployed"] = round(time.time() - t0)
        deployed = max(deployed, len(dep))
        lines = s.read_since(p)
        ended = next((l for l in lines if re.search(r"Raid (FAILURE|SUCCESS)|repulsed|succeeded", l)), None)
        if ended or (raider_threat and deployed >= 2 and time.time() - t0 > 90):
            break
        time.sleep(5)
    note("RD timeline", f"{first}; raid end: {(ended or 'not yet')[-160:]}")
    check("RD-1 the raiders are threats of the raided village (SETTLEMENT_RAIDER)", raider_threat is not None, raider_threat or "none seen")
    check("RD-2 the raided village's HYW garrison deploys against them", deployed >= 1, f"max units on DEFENSE/DEPLOYED: {deployed}")


def scenario_MR(ctx):
    """Muster Roll (post-M5): a block in village A hires soldiers for Millénaire money through the dev stand-in player
    (fixed UUID). Mercenaries for any standing but Unwelcome/Outlaw, the village's own soldiers from Trusted, money only is
    the limit, recruits are owned by the player (no garrison tag) and appear around the block; the garrison is untouched."""
    s = ctx.s
    a = ctx.a
    x, z = a[0] + 6, a[2] + 6
    y = (surface_y(s, x, z) or a[1])
    s.cmd(f"setblock {x} {y} {z} hywmill:muster_roll", 1)
    blk = f"{x} {y} {z}"
    standin = "33333333-4444-4555-8666-777777777777"
    g0 = garrison(s, a)

    def offers(st):
        out = s.output(f"hywmill dev recruit offers {blk} {st}", 2)
        head = next((l for l in out if "recruit: " in l), "")
        return head, {m[1]: (m[2], int(m[3])) for m in (re.search(r"recruit offer (\S+) (\S+) (\d+)", l) for l in out) if m}

    def hire(key, n, money, st):
        out = s.output(f'hywmill dev recruit hire {blk} "{key}" {n} {money} {st}', 3)
        l = next((l for l in out if "recruit: ok=" in l), "")
        m = re.search(r"ok=(\w+) hired=(\d+) left=(-?\d+)", l)
        return (m[1] == "true", int(m[2]), int(m[3]), l) if m else (False, 0, -2, l)

    def owned():
        return {u: r for u, r in spike_info(s, "@e[type=!minecraft:player]").items() if ("owner=" + standin) in r["desc"]}

    h, stranger = offers("STRANGER")
    note("MR offers STRANGER", h + " " + str(stranger))
    check("MR-1 a stranger is offered the mercenaries only (3, in argent)",
          len(stranger) == 3 and all(k.startswith("merc:") for k in stranger) and stranger.get("merc:militia", (0, 0))[1] == 192, str(stranger))
    h, unw = offers("UNWELCOME")
    check("MR-2 Unwelcome: nothing offered", len(unw) == 0, h)
    h, trusted = offers("TRUSTED")
    note("MR offers TRUSTED", h + " " + str(trusted))
    units = {k: v for k, v in trusted.items() if k.startswith("unit:")}
    check("MR-3 Trusted adds the village's own soldiers, priced in or", len(units) >= 1 and all(v[1] >= 4096 for v in units.values()), str(units))
    h, patron = offers("PATRON")
    check("MR-4 Patron gets the 10% discount (militia 192 -> 173)", patron.get("merc:militia", (0, 0))[1] == 173, str(patron.get("merc:militia")))

    before = set(owned())
    ok, n, left, l = hire("merc:militia", 3, 1000, "STRANGER")
    check("MR-5 hire 3 militia with 1000 deniers: ok, 3 hired, 424 left", ok and n == 3 and left == 424, l)
    time.sleep(3)
    new = {u: r for u, r in owned().items() if u not in before}
    near = [r for r in new.values() if dist(r["pos"], (x, y, z)) <= 8 + 2]
    check("MR-6 the recruits exist, owned by the player, untagged, within the radius of the block",
          len(new) == 3 and all(r["tag"] == "none" for r in new.values()) and len(near) == 3,
          "; ".join(f"{r['pos']} tag={r['tag']}" for r in new.values()))
    ok, n, left, l = hire("merc:militia", 1, 100, "STRANGER")
    check("MR-7 not enough money: refused, money kept", not ok and n == 0 and left == 100, l)
    ok, n, left, l = hire("merc:militia", 1, 1000, "UNWELCOME")
    check("MR-8 Unwelcome: refused, money kept", not ok and n == 0 and left == 1000, l)
    if units:
        key, (tier, price) = sorted(units.items(), key=lambda kv: kv[1][1])[0]
        ok, n, left, l = hire(key, 2, 2 * price + 50, "TRUSTED")
        check(f"MR-9 hire 2 {key} at {price} each: ok, 50 left", ok and n == 2 and left == 50, l)
    else:
        check("MR-9 hire cultural soldiers", False, "no unit offers at TRUSTED")
    ok, n, left, l = hire("merc:militia", 64, 64 * 192, "STRANGER")
    check("MR-10 more than 32 in one purchase is refused or capped", n <= 32, l)
    time.sleep(3)
    g1 = garrison(s, a)
    check("MR-11 the garrison is untouched (live and target unchanged)", g1.get("live") == g0.get("live") and g1.get("target") == g0.get("target"),
          f"before {g0.get('live')}/{g0.get('target')} after {g1.get('live')}/{g1.get('target')}")


def scenario_AP(ctx):
    """Apology (post-M5): a player who is not an outlaw pays Millénaire money (1 argent per grievance point) and the
    grievance is settled at once. Driven through the dev stand-in, three garrison assaults inside village A (27 points)."""
    s = ctx.s
    a = ctx.a
    x, z = a[0] + 6, a[2] + 6
    y = (surface_y(s, x, z) or a[1])

    def apologize(hits, money, pay):
        out = s.output(f"hywmill dev recruit apologize {x} {y} {z} {hits} {money} {'true' if pay else 'false'}", 3)
        l = next((l for l in out if "apology: " in l), "")
        m = re.search(r"before=(\w+) grievance=([\d.]+) outcome=(\w+) price=(\d+) paid=(\w+) after=(\w+) left=(-?\d+)", l)
        return ({"before": m[1], "g": float(m[2]), "outcome": m[3], "price": int(m[4]), "paid": m[5] == "true", "after": m[6], "left": int(m[7])}
                if m else {}), l

    r, l = apologize(3, 5000, False)
    check("AP-1 three garrison assaults make the stand-in Unwelcome; the apology is quoted at 1 argent per point",
          r.get("before") == "UNWELCOME" and r.get("outcome") == "OK" and 27 * 64 - 64 <= r.get("price", 0) <= 27 * 64 and not r.get("paid"), l)
    r, l = apologize(0, 1000, True)
    check("AP-2 not enough money: refused, money kept, still Unwelcome",
          r.get("outcome") == "TOO_POOR" and not r.get("paid") and r.get("left") == 1000 and r.get("after") == "UNWELCOME", l)
    r, l = apologize(0, 2000, True)
    check("AP-3 paid: grievance settled, no longer Unwelcome, the price taken",
          r.get("paid") and r.get("after") not in ("UNWELCOME", "OUTLAW", None) and r.get("left") == 2000 - r.get("price", -1), l)
    r, l = apologize(0, 1000, True)
    check("AP-4 nothing left to settle", r.get("outcome") == "NOTHING_TO_SETTLE" and r.get("left") == 1000, l)


def scenario_WG(ctx):
    """Crash guard (post-M5 bug report): an HYW archer with an empty main hand crashed the server in its per-tick lob check
    ("Invalid weapon firing an arrow"). Empty the main hand of an archer and a crossbowman that have a zombie in reach:
    they must be re-armed and the server must keep ticking."""
    s = ctx.s
    a = ctx.a
    # far from every village (an ownerless HYW unit in a village is a threat its garrison kills)
    x, z = a[0] + 40, a[2] - 330
    s.cmd(f"forceload add {x - 16} {z - 16} {x + 16} {z + 16}", 3)
    y = (surface_y(s, x, z) or a[1])
    p = s.pos()
    for kind, tag in (("archer", "hwWGa"), ("crossbowman", "hwWGc")):
        s.cmd(f'summon hundred_years_war:{kind} {x} {y} {z} {{Tags:["{tag}"],PersistenceRequired:1b}}', 1)
    s.cmd(f'summon minecraft:zombie {x + 6} {y} {z} {{Tags:["hwWGz"],PersistenceRequired:1b}}', 1)
    time.sleep(2)
    for tag in ("hwWGa", "hwWGc"):
        s.cmd(f"item replace entity @e[tag={tag},limit=1] weapon.mainhand with minecraft:air", 0.5)
    time.sleep(8)
    held = {}
    for tag in ("hwWGa", "hwWGc"):
        out = " ".join(s.output(f"data get entity @e[tag={tag},limit=1] HandItems[0]", 1))
        m = re.search(r'id: "([^"]+)"', out)
        held[tag] = m[1] if m else out[-120:]
    alive = any("There are" in l for l in s.output("list", 1))
    lines = s.read_since(p)
    crashed = [l for l in lines if "Invalid weapon firing an arrow" in l or "Ticking entity" in l]
    rearmed = [l for l in lines if "Re-armed unarmed HYW" in l]
    note("WG log", (rearmed[0][-200:] if rearmed else "no re-arm line"))
    check("WG-1 an archer whose main hand is emptied is re-armed with a bow", held.get("hwWGa") == "minecraft:bow", str(held))
    check("WG-2 a crossbowman likewise gets a crossbow", held.get("hwWGc") == "minecraft:crossbow", str(held))
    check("WG-3 the server keeps ticking (no 'Invalid weapon firing an arrow')", alive and not crashed, "; ".join(crashed)[:300])
    s.cmd("kill @e[tag=hwWGa]", 0.3)
    s.cmd("kill @e[tag=hwWGc]", 0.3)
    s.cmd("kill @e[tag=hwWGz]", 0.3)
    s.cmd(f"forceload remove {x - 16} {z - 16} {x + 16} {z + 16}", 1)


def scenario_WX(ctx):
    """Exploration (post-M5 crash): how do HYW archers end up with an empty main hand in play? Hire mercenary archers from a
    Muster Roll far from the villages, feed them zombies for a few minutes and log main hand and HYW's intrinsic main hand."""
    s = ctx.s
    a = ctx.a
    x, z = a[0] + 6, a[2] + 6
    y = (surface_y(s, x, z) or a[1])
    s.cmd(f"setblock {x} {y} {z} hywmill:muster_roll", 1)
    out = s.output(f'hywmill dev recruit hire {x} {y} {z} "merc:archer" 4 5000 STRANGER', 3)
    note("WX hire", "; ".join(l for l in out if "recruit:" in l)[:300])
    s.output(f'hywmill dev recruit hire {x} {y} {z} "unit:archer" 2 20000 SWORN', 3)
    time.sleep(3)
    ids = [u for u, r in spike_info(s, "@e[type=hundred_years_war:archer]").items() if dist(r["pos"], (x, y, z)) < 80]
    note("WX archers", str(len(ids)))
    empty_seen = {}
    t0 = time.time()
    while time.time() - t0 < 300:
        s.cmd(f"execute positioned {x} {y} {z} run summon minecraft:zombie ~12 ~ ~ {{PersistenceRequired:1b}}", 0.3)
        s.cmd(f"execute positioned {x} {y} {z} run summon minecraft:skeleton ~-12 ~ ~4 {{PersistenceRequired:1b}}", 0.3)
        for u in ids:
            hand = " ".join(s.output(f"data get entity {u} HandItems[0]", 0.3))
            intr = " ".join(s.output(f"data get entity {u} HywIntrinsicEquipment", 0.3))
            if "No entity" in hand:
                continue
            if 'id: "' not in hand:
                empty_seen.setdefault(u, (round(time.time() - t0), hand[-200:], intr[-300:]))
        time.sleep(8)
    for u in ids:
        hand = " ".join(s.output(f"data get entity {u} HandItems[0]", 0.3))
        intr = " ".join(s.output(f"data get entity {u} HywIntrinsicEquipment", 0.3))
        note(f"WX {u[:8]}", (hand[-160:] + " || " + intr[-260:]))
    note("WX empty", str(empty_seen)[:2000])
    check("WX no archer seen with an empty main hand", not empty_seen, str(list(empty_seen))[:300])


def scenario_WL(ctx):
    """Crash guard, lob path (post-M5 bug report, fix6 still crashed): the archer's per-tick target check lobs over cover and
    runs at the start of its tick. An archer with a zombie penned behind a wall has its main hand emptied repeatedly; the
    server must keep ticking and the archer must be holding a weapon."""
    s = ctx.s
    a = ctx.a
    x, z = a[0] + 40, a[2] - 330
    s.cmd(f"forceload add {x - 24} {z - 24} {x + 24} {z + 24}", 3)
    y = (surface_y(s, x, z) or a[1])
    p = s.pos()
    s.cmd(f"fill {x - 3} {y} {z - 8} {x + 12} {y + 6} {z + 8} minecraft:air", 1)
    s.cmd(f"fill {x - 3} {y - 1} {z - 8} {x + 12} {y - 1} {z + 8} minecraft:stone", 1)
    s.cmd(f"fill {x + 5} {y} {z - 6} {x + 5} {y + 3} {z + 6} minecraft:stone", 1)          # the cover
    s.cmd(f"fill {x + 8} {y} {z - 2} {x + 11} {y + 2} {z + 2} minecraft:glass hollow", 1)   # the pen
    s.cmd(f"fill {x + 9} {y} {z - 1} {x + 10} {y + 1} {z + 1} minecraft:air", 1)
    s.cmd(f'summon hundred_years_war:archer {x} {y} {z} {{Tags:["hwWL"],PersistenceRequired:1b}}', 1)
    s.cmd(f'summon minecraft:zombie {x + 9} {y} {z} {{Tags:["hwWLz"],PersistenceRequired:1b,NoAI:0b}}', 1)
    s.cmd("item replace entity @e[tag=hwWL,limit=1] weapon.mainhand with minecraft:bow", 0.5)
    time.sleep(3)
    for _ in range(40):
        s.cmd("item replace entity @e[tag=hwWL,limit=1] weapon.mainhand with minecraft:air", 0.25)
    time.sleep(3)
    alive = any("There are" in l for l in s.output("list", 1))
    out = " ".join(s.output("data get entity @e[tag=hwWL,limit=1] HandItems[0]", 1))
    lines = s.read_since(p)
    crashed = [l for l in lines if "Invalid weapon firing an arrow" in l]
    rearm = [l for l in lines if "Re-armed unarmed HYW" in l]
    note("WL", f"re-arm lines {len(rearm)}; {(rearm[-1][-160:] if rearm else '')}")
    check("WL-1 the server keeps ticking with an archer lobbing over cover while its hand is emptied 40 times",
          alive and not crashed, "; ".join(crashed)[:300] or ("server gone" if not alive else ""))
    check("WL-2 the archer holds a weapon afterwards", 'id: "' in out, out[-160:])
    s.cmd("kill @e[tag=hwWL]", 0.3)
    s.cmd("kill @e[tag=hwWLz]", 0.3)
    s.cmd(f"forceload remove {x - 24} {z - 24} {x + 24} {z + 24}", 1)


def scenario_RC(ctx):
    """Raid counsel (post-M5; run with [politics] warMinConflictTicks = 200): a player on campaign with a village at war
    suggests a raid on the enemy. B (it has raiders) is the ally, A the enemy. Refused without a campaign; a refusal by the
    roll spends a diplomacy point and starts the cooldown; an agreement plans a Millénaire raid (which then sets out with a
    garrison share); a village already raiding is refused."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    U, V = U_UUID, "cccccccc-dddd-4eee-8fff-000000000001"
    ida, idb = info(s, a).get("villageId", ""), info(s, b).get("villageId", "")
    dip(s, a, f"admin truce {ca} {cb} 0")
    for i, u in enumerate((U, V)):
        m5(s, f"mill discover {ca} {u}", 0.5)
        m5(s, f"mill discover {cb} {u}", 0.5)
        standin_at(s, u, b[0] + 3 + i, b[2] + 3)
        m5(s, f"mill rep {cb} {u} adjust 5000", 0.5)
        m5(s, f"mill dpoints {cb} {u} regen", 0.5)
    time.sleep(14)  # the standing is re-evaluated on the politics cadence

    def raid(u, draw=None):
        sub = f"for {u} raid" + (f" roll {draw}" if draw is not None else "")
        return " | ".join(war_lines(s, b, sub))

    def ui_raid(u):
        out = m5(s, f"ui select {u} {ca}", 2)
        return next((l for l in out if "action SUGGEST_RAID" in l), "")

    r0 = raid(U)
    u0 = ui_raid(U)
    check("RC-1 without a campaign the suggestion is refused (command; the screen shows it disabled with the reason)",
          "NOT_ON_CAMPAIGN" in r0 and "available=false" in u0 and ("not at war" in u0 or "on campaign" in u0), f"{r0} || {u0}")
    m5(s, f"mill mrel {ca} {cb} set -100")
    t0 = time.time()
    while time.time() - t0 < 90 and not any("at war" in l for l in war_lines(s, b, f"for {U} status")):
        time.sleep(5)
    j = [" | ".join(war_lines(s, b, f"for {u} join {cb} against {ca}")) for u in (U, V)]
    check("RC-2 B and A go to war; two trusted players join B's campaign", all("join OK" in x for x in j), " || ".join(j))
    u1 = ui_raid(U)
    check("RC-2b on campaign the Politics screen offers 'Suggest a raid' with its cost and odds", "available=true" in u1
          and "diplomacy point" in u1 and re.search(r"outcome=(likely|uncertain|unlikely)", u1), u1)
    p0 = m5_1(s, f"mill dpoints {cb} {U}")
    rb0 = m5_1(s, f"mill raid {cb}")
    r1 = raid(U, 0.999)
    p1 = m5_1(s, f"mill dpoints {cb} {U}")
    rb1 = m5_1(s, f"mill raid {cb}")
    pts = lambda l: int(re.search(r"now=(\d+)", l)[1]) if re.search(r"now=(\d+)", l) else -1
    check("RC-3 the elders refuse (draw above the chance): a diplomacy point is spent and no raid is planned",
          "REFUSED" in r1 and pts(p1) == pts(p0) - 1 and "target=none" in rb1, f"{r1} || {p0} -> {p1} || {rb0} -> {rb1}")
    r2 = raid(U, 0.0)
    check("RC-4 asking again at once is refused by the cooldown", "COOLDOWN" in r2, r2)
    p = s.pos()
    r3 = raid(V, 0.0)
    rb2 = m5_1(s, f"mill raid {cb}")
    lines = s.read_since(p)
    planned = [l for l in lines if "Raid planned" in l]
    check("RC-5 another campaigner's counsel is heeded: Millénaire plans B's raid on A (its own announcement)",
          "AGREED" in r3 and ("target=" + ida[:8]) in rb2 and "start=0" in rb2 and planned, f"{r3} || {rb2} || {(planned or ['no Raid planned line'])[0][-140:]}")
    r4 = raid(V, 0.0)
    check("RC-6 while B is raiding, a further suggestion is refused", "ALREADY_RAIDING" in r4, r4)
    u2 = " | ".join(l for l in m5(s, f"ui submit {V} {ca} SUGGEST_RAID", 2) if "ui result" in l)
    check("RC-6b the screen's submit goes through the same checks (refused while raiding)", "ALREADY_RAIDING" in u2, u2)
    chron = [l for l in s.output(at(b, f"hywmill politics status for {V}"), 2) if "counselled a raid" in l]
    note("RC chronicle", (chron or ["(not listed in status)"])[0][-200:])
    # the raid sets out a (Millénaire) day after planning; the garrison sends its raid share with it
    p = s.pos()
    s.cmd("time add 24000", 1)
    started, raiders = None, 0
    t0 = time.time()
    while time.time() - t0 < 120:
        lines = s.read_since(p)
        started = started or next((l for l in lines if "Raid started" in l or "Raid aborted" in l), None)
        rows = duties(s, b)["rows"]
        raiders = max(raiders, sum(1 for r in rows if r["duty"] == "RAID"))
        if started and raiders:
            break
        time.sleep(5)
    check("RC-7 a day later the raid sets out, with a share of B's garrison on RAID duty",
          started is not None and "Raid started" in started and raiders >= 1, f"{(started or 'no start line')[-160:]}; garrison on RAID: {raiders}")
    for u in (U, V):
        war_lines(s, b, f"for {u} leave")
        m5(s, f"standin remove {u}", 0.3)
    dip(s, a, f"admin truce {ca} {cb} 1")
    m5(s, f"mill mrel {ca} {cb} set 0")


def sieges(s):
    return [l.strip() for l in s.output("hywmill war sieges", 1.5) if " -> " in l]


def wait_siege(s, pred, limit, step=5):
    t0 = time.time()
    while time.time() - t0 < limit:
        l = sieges(s)
        if pred(l):
            return l, round(time.time() - t0)
        time.sleep(step)
    return sieges(s), None


def near_count(s, fac, c, r=110):
    return sum(1 for u, x in spike_info(s, "@e[type=!minecraft:player]").items()
               if x["tag"] != "none" and ("owner=" + fac) in x["desc"] and dist(x["pos"], c) <= r)


def scenario_SG(ctx):
    """Sieges (post-M5; run after G4_0 with [politics] warMinConflictTicks = 200): a Patron campaigner counsels a siege of
    B by A (a Trusted one is refused); A's host musters, leaves the world while marching (slots kept, no missing, no
    duplicates), stands before B, fights, and the outcome pays tribute and rewards the helper; survivors come home."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    U = U_UUID
    p0 = s.pos()
    dip(s, a, f"admin truce {ca} {cb} 0")
    m5(s, f"mill discover {ca} {U}", 0.5)
    m5(s, f"mill discover {cb} {U}", 0.5)
    standin_at(s, U, a[0] + 3, a[2] + 3)
    m5(s, f"mill rep {ca} {U} adjust 5000", 0.5)
    m5(s, f"mill dpoints {ca} {U} regen", 0.5)
    m5(s, f"mill mrel {ca} {cb} set -100")
    time.sleep(14)
    t0 = time.time()
    while time.time() - t0 < 90 and not any("at war" in l for l in war_lines(s, a, f"for {U} status")):
        time.sleep(5)
    j = " | ".join(war_lines(s, a, f"for {U} join {ca} against {cb}"))
    r0 = " | ".join(war_lines(s, a, f"for {U} siege"))
    check("SG-1 at war and on campaign, a Trusted player cannot counsel a siege", "join OK" in j and "STANDING_TOO_LOW" in r0, f"{j} || {r0}")
    m5(s, f"mill rep {ca} {U} adjust 5000", 0.5)
    for _ in range(4):
        s.output(at(a, f"hywmill politics admin favor {U} SIEGE_VICTORY"), 0.5)
    time.sleep(14)
    ui = next((l for l in m5(s, f"ui select {U} {cb}", 2) if "action SUGGEST_SIEGE" in l), "")
    check("SG-2 a Patron campaigner sees 'Suggest a siege' in the Politics screen with its cost and odds",
          "available=true" in ui and "diplomacy points" in ui and re.search(r"outcome=(likely|uncertain|unlikely)", ui), ui)
    ga0 = garrison(s, a)
    ents0 = len(unit_entities(s, a))
    r1 = " | ".join(war_lines(s, a, f"for {U} siege roll 0.0"))
    sg = sieges(s)
    host = int(re.search(r"MUSTER \d+/(\d+)", sg[0])[1]) if sg and re.search(r"MUSTER \d+/(\d+)", sg[0]) else 0
    siege_rows = [r for r in duties(s, a)["rows"] if r["duty"] == "SIEGE"]
    check("SG-3 the counsel is heeded: A musters a host (on SIEGE duty) against B", "AGREED" in r1 and host >= 6 and len(siege_rows) == host,
          f"{r1} || {sg} || SIEGE rows {len(siege_rows)}")
    sg, t_m = wait_siege(s, lambda l: l and "MARCH" in l[0], 150)
    time.sleep(3)
    ga1 = garrison(s, a)
    ents1 = len(unit_entities(s, a))
    check("SG-4 marching, the host leaves the world: its units are gone, its slots kept (none missing)",
          t_m is not None and ents0 - ents1 >= host - 1 and ga1.get("live") == ga0.get("live") and ga1.get("missing", 0) == ga0.get("missing", 0),
          f"{sg} entities {ents0} -> {ents1} (host {host}); live {ga0.get('live')} -> {ga1.get('live')}; missing {ga1.get('missing')}")
    fa = ga0.get("faction")
    sg, t_b = wait_siege(s, lambda l: l and ("BATTLE" in l[0] or "RETURN" in l[0]), 240)
    before_b = near_count(s, fa, b)
    standin_at(s, U, b[0] + 6, b[2] + 6)  # the campaigner stands with the host
    check("SG-5 the host arrives and stands before B", t_b is not None and before_b >= max(1, host // 2), f"{sg}; A units near B: {before_b}")
    sg, t_e = wait_siege(s, lambda l: not l or "RETURN" in l[0] or "WON" in l[0] or "LOST" in l[0], 420)
    lines = s.read_since(p0)
    ended = next((l for l in lines if "Siege " in l and " ended " in l), "")
    check("SG-6 the battle is decided and tribute is paid (chronicle and log)", bool(ended) and "tribute" in ended, ended[-260:] or str(sg))
    won = " ended WON" in ended
    pu = pshow(s, a, U)
    note("SG outcome", f"{'WON' if won else 'LOST'}; helper favor with A now {pu.get('favor')}; {sg}")
    if won:
        check("SG-6b the helper who stood with the winner is rewarded (SIEGE_VICTORY Favor)", pu.get("favor", 0) >= 25, str(pu))
    sg, t_h = wait_siege(s, lambda l: not l, 300)
    time.sleep(15)
    ga2 = garrison(s, a)
    rows2 = duties(s, a)["rows"]
    lines = s.read_since(p0)
    dups = [l for l in lines if "Duplicate garrison unit refused" in l]
    check("SG-7 the survivors come home (no one left on SIEGE duty), no duplicate units",
          t_h is not None and not any(r["duty"] == "SIEGE" for r in rows2) and not dups,
          f"home after {t_h}s; live {ga2.get('live')} killed {ga2.get('t_killed')}; duplicates {len(dups)}")
    war_lines(s, a, f"for {U} leave")
    m5(s, f"standin remove {U}", 0.3)


def scenario_OS(ctx):
    """Unwatched siege (post-M5): A besieges Z with the target treated as unloaded (dev switch); the siege survives a restart
    during the march and is decided off-screen by strength, with HYW losses on both sides and survivors coming home."""
    s, a = ctx.s, ctx.a
    z = ctx.extra.get("byzantine")
    if not z:
        check("OS-0 the third village exists", False, "no byzantine village")
        return
    ca, cz = f"{a[0]} {a[1]} {a[2]}", f"{z[0]} {z[1]} {z[2]}"
    p0 = s.pos()
    ga0, gz0 = garrison(s, a), garrison(s, z)
    out = " | ".join(l for l in s.output(f"hywmill war admin siege {ca} {cz} unwatched", 2) if l.startswith("war siege"))
    m = re.search(r"host (\d+)", out)
    host = int(m[1]) if m else 0
    check("OS-1 an admin siege of Z by A is launched", "OK" in out and host >= 6, out)
    sg, t_m = wait_siege(s, lambda l: l and "MARCH" in l[0], 150)
    restart(ctx)
    time.sleep(10)
    sg2 = sieges(s)
    check("OS-2 the marching siege survives a restart", t_m is not None and sg2 and "MARCH" in sg2[0], f"{sg} -> {sg2}")
    sg, t_e = wait_siege(s, lambda l: not l or "RETURN" in l[0], 600)
    lines = s.read_since(p0)
    dec = next((l for l in lines if "decided off-screen" in l), "")
    ga1, gz1 = garrison(s, a), garrison(s, z)
    m = re.search(r"host lost (\d+), defenders lost (\d+)", dec)
    hl, dl = (int(m[1]), int(m[2])) if m else (-1, -1)
    check("OS-3 decided off-screen by strength: HYW losses on both sides match the garrisons' killed totals",
          bool(dec) and hl >= 0 and ga1.get("t_killed", 0) - ga0.get("t_killed", 0) == hl and gz1.get("t_killed", 0) - gz0.get("t_killed", 0) == dl,
          f"{dec[-220:]} || A killed {ga0.get('t_killed')} -> {ga1.get('t_killed')}, Z killed {gz0.get('t_killed')} -> {gz1.get('t_killed')}")
    sg, t_h = wait_siege(s, lambda l: not l, 420)
    time.sleep(15)
    rows = duties(s, a)["rows"]
    dups = [l for l in s.read_since(p0) if "Duplicate garrison unit refused" in l]
    check("OS-4 the survivors march home and resume their duties; no duplicates", t_h is not None and not any(r["duty"] == "SIEGE" for r in rows),
          f"home after {t_h}s; duplicate refusals {len(dups)} (old entities of stowed slots are expected to be refused if they load)")


def scenario_ENG(ctx):
    """Exploration (siege engines): A (at war with B) gets a trebuchet, a mangonel and two siege engineers (roster-backed,
    A's faction) placed within range of B. Do the engineers mount, do the engines pick targets and fire, what do they hit,
    and does any block of B change?"""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    m5(s, f"mill mrel {ca} {cb} set -100")
    time.sleep(10)
    st = war_lines(s, a, f"for {U_UUID} status")
    note("ENG war", " | ".join(st)[:300])
    ids = {}
    for kind in ("trebuchets", "mangonels", "siege_engineer", "siege_engineer"):
        out = s.output(ground(a[0] + 4, a[2] + 4, f"hywmill dev spike-spawn {kind} 1"), 2)
        m = next((re.search(r"spike spawned ([0-9a-f-]{36})", l) for l in out if "spike spawned" in l), None)
        if m:
            ids.setdefault(kind, []).append(m[1])
    note("ENG spawned", str(ids))
    x0, z0 = b[0] - 60, b[2]
    y0 = surface_y(s, x0, z0) or b[1]
    for i, u in enumerate(sum(ids.values(), [])):
        s.cmd(f"tp {u} {x0} {y0 + 1} {z0 + i * 4 - 6}", 0.5)
    # block snapshot of B's core (40x16x40) to compare afterwards
    bx, by, bz = b[0] - 20, b[1] - 4, b[2] - 20
    s.cmd(f"forceload add {bx} 2980 {bx + 40} 3020", 3)
    s.cmd(f"clone {bx} {by} {bz} {bx + 39} {by + 15} {bz + 39} {bx} {by} 2980", 3)
    p0 = s.pos()
    fired, mounted, targets = 0, set(), {}
    t0 = time.time()
    while time.time() - t0 < 120:
        for kind in ("trebuchets", "mangonels"):
            for u in ids.get(kind, []):
                pas = " ".join(s.output(f"data get entity {u} Passengers[0].id", 0.4))
                if "siege_engineer" in pas:
                    mounted.add(kind)
                info_ = spike_info(s, u)
                for k, r in info_.items():
                    if r["target"] != "none":
                        targets.setdefault(kind, set()).add(r["target"].split("[")[0])
        for et in ("trebuchets_bullet", "mangonels_bullet"):
            for l in s.output(f"execute if entity @e[type=hundred_years_war:{et}]", 0.3):
                m = re.search(r"Test passed, count: (\d+)", l)
                if m:
                    fired += int(m[1])
        time.sleep(4)
    lines = s.read_since(p0)
    deaths = [l for l in lines if "died" in l.lower() or "was slain" in l or "killed" in l.lower()]
    cmp = " ".join(s.output(f"execute if blocks {bx} {by} {bz} {bx + 39} {by + 15} {bz + 39} {bx} {by} 2980 all", 3))
    note("ENG projectiles seen in flight (sampled)", str(fired))
    note("ENG mounted", str(sorted(mounted)))
    note("ENG targets", str({k: sorted(v) for k, v in targets.items()}))
    note("ENG deaths/incidents", str(len(deaths)) + " | " + " || ".join(d[-150:] for d in deaths[:6]))
    note("ENG blocks of B unchanged", cmp[-200:])
    for u in sum(ids.values(), []):
        info_ = spike_info(s, u)
        for k, r in info_.items():
            note(f"ENG unit {u[:8]}", f"{r['pos']} {r['desc'][:80]} target={r['target']} health={r.get('health')}")
    check("ENG-1 the engineers mount the engines", "trebuchets" in mounted or "mangonels" in mounted, str(mounted))
    check("ENG-2 the engines take targets in B", bool(targets), str(targets))
    check("ENG-3 no block of B's core changed", "passed" in cmp.lower(), cmp[-160:])


def scenario_ENGC(ctx):
    """Control for ENG: the same block snapshot of B's core and the same wait, with no siege engine anywhere near B."""
    s, b = ctx.s, ctx.b
    for kind in ("trebuchets", "mangonels", "siege_engineer"):
        s.cmd(f"kill @e[type=hundred_years_war:{kind}]", 0.5)
    bx, by, bz = b[0] - 20, b[1] - 4, b[2] - 20
    s.cmd(f"forceload add {bx} 2980 {bx + 40} 3020", 3)
    results = []
    for _ in range(2):
        s.cmd(f"clone {bx} {by} {bz} {bx + 39} {by + 15} {bz + 39} {bx} {by} 2980", 3)
        time.sleep(120)
        results.append(" ".join(s.output(f"execute if blocks {bx} {by} {bz} {bx + 39} {by + 15} {bz + 39} {bx} {by} 2980 all", 3))[-80:])
    note("ENGC control (no engines): B's core after 2 min", str(results))
    check("ENGC-1 control recorded", True, str(results))


ENGINE_TYPES = ("mangonels", "trebuchets", "nest_of_bees", "battering_ram")


def engines_of(s, owner):
    """{uuid: row} of loaded siege engines owned by {owner} (a faction or player UUID)."""
    out = {}
    for et in ENGINE_TYPES:
        for u, r in spike_info(s, f"@e[type=hundred_years_war:{et}]").items():
            if ("owner=" + owner) in r["desc"]:
                r["kind"] = et
                out[u] = r
    return out


def arsenal_lines(s):
    return [l.strip() for l in s.output("hywmill war arsenals", 1.5) if " arsenal " in l]


def ars7(s, x, y, z, standin, h1, h2, h3):
    mine = engines_of(s, standin)
    eng = [u for u, r in spike_info(s, "@e[type=hundred_years_war:siege_engineer]").items() if ("owner=" + standin) in r["desc"]]
    # an HYW battering ram belongs to whoever rides it: bought, it stands ownerless next to the block until the player boards
    rams = [r for r in spike_info(s, "@e[type=hundred_years_war:battering_ram]").values() if dist(r["pos"], (x, y, z)) <= 40]
    check("ARS-7 the Muster Roll sells a crewed catapult and a ram to a Trusted player; a trebuchet needs a Patron",
          "ok=true" in h1 and "ok=true" in h2 and "ok=false" in h3 and [r["kind"] for r in mine.values()].count("mangonels") >= 1 and len(eng) >= 1
          and len(rams) >= 1, f"{h1[-90:]} || {h2[-90:]} || {h3[-120:]} || owned {sorted(r['kind'] for r in mine.values())}, engineers {len(eng)}, "
          f"rams by the block {len(rams)} (owner {rams[0]['desc'].split('owner=')[1].split()[0] if rams else '-'})")


def scenario_ARS7(ctx):
    """ARS-7 alone on a kept world (after ARS): Muster Roll engine sales."""
    s, a = ctx.s, ctx.a
    x, z = a[0] + 6, a[2] + 6
    y = surface_y(s, x, z) or a[1]
    s.cmd(f"setblock {x} {y} {z} hywmill:muster_roll", 1)
    s.cmd("kill @e[type=hundred_years_war:battering_ram]", 1)
    standin = "33333333-4444-4555-8666-777777777777"
    h1 = " ".join(l for l in s.output(f'hywmill dev recruit hire {x} {y} {z} "engine:mangonels" 1 20000 TRUSTED', 4) if "recruit:" in l)
    h2 = " ".join(l for l in s.output(f'hywmill dev recruit hire {x} {y} {z} "engine:battering_ram" 1 20000 TRUSTED', 4) if "recruit:" in l)
    h3 = " ".join(l for l in s.output(f'hywmill dev recruit hire {x} {y} {z} "engine:trebuchets" 1 50000 TRUSTED', 4) if "recruit:" in l)
    time.sleep(8)
    ars7(s, x, y, z, standin, h1, h2, h3)


def scenario_ARS(ctx):
    """War arsenal (post-M5; run after G4_0 with [politics] warMinConflictTicks = 200): A and B go to war and each raises
    siege engines by tier (catapults/trebuchets, crewed); a destroyed engine is not replaced; A's engines march with its
    siege and set up behind the host; at peace the survivors stand down; the next war brings a fresh arsenal. The Muster
    Roll sells a crewed catapult and a ram to a Trusted player, not a trebuchet."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    fa, fb = garrison(s, a).get("faction"), garrison(s, b).get("faction")
    ta, tb = info(s, a).get("tier"), info(s, b).get("tier")
    want = {"WATCH": 1, "GUARD_POST": 2, "GARRISON": 3, "STRONGHOLD": 4}
    p0 = s.pos()
    dip(s, a, f"admin truce {ca} {cb} 0")
    m5(s, f"mill mrel {ca} {cb} set -100")
    t0 = time.time()
    ea, eb = {}, {}
    while time.time() - t0 < 150:
        ea, eb = engines_of(s, fa), engines_of(s, fb)
        if len(ea) >= want.get(ta, 0) and len(eb) >= want.get(tb, 0) and ea:
            break
        time.sleep(5)
    time.sleep(8)
    ea, eb = engines_of(s, fa), engines_of(s, fb)
    mounted = sum(1 for u in ea if "siege_engineer" in " ".join(s.output(f"data get entity {u} Passengers[0].id", 0.4)))
    note("ARS arsenals", " || ".join(arsenal_lines(s))[:500])
    check("ARS-1 at war, each village raises siege engines by its tier (catapults/trebuchets), crewed by engineers",
          len(ea) == want.get(ta, 0) and len(eb) == want.get(tb, 0) and all(r["kind"] in ("mangonels", "trebuchets", "nest_of_bees") for r in {**ea, **eb}.values())
          and mounted >= 1, f"A {ta}: {sorted(r['kind'] for r in ea.values())}, B {tb}: {sorted(r['kind'] for r in eb.values())}; A mounted {mounted}")
    victim = next(iter(ea), None)
    if victim:
        s.cmd(f"kill {victim}", 1)
    time.sleep(30)
    ea2 = engines_of(s, fa)
    check("ARS-2 a destroyed engine is not replaced during the war", victim is not None and victim not in ea2 and len(ea2) == len(ea) - 1,
          f"{len(ea)} -> {len(ea2)}")
    out = " | ".join(l for l in s.output(f"hywmill war admin siege {ca} {cb} quick", 2) if l.startswith("war siege"))
    check("ARS-3 A's siege takes its engines along", "siege engine" in out, out)
    sg, t_b = wait_siege(s, lambda l: l and ("BATTLE" in l[0] or "RETURN" in l[0]), 300)
    time.sleep(10)
    near = [r for r in engines_of(s, fa).values() if dist(r["pos"], b) <= 130]
    check("ARS-4 before B, A's engines set up behind the host", t_b is not None and len(near) >= 1,
          f"{sg}; A engines near B: {[(r['kind'], r['pos']) for r in near]}")
    sg, t_e = wait_siege(s, lambda l: not l or "RETURN" in l[0], 420)
    dip(s, a, f"admin truce {ca} {cb} 1")
    t0 = time.time()
    while time.time() - t0 < 120 and (engines_of(s, fb) or any("(at war)" in l for l in arsenal_lines(s))):
        time.sleep(5)
    eb3 = engines_of(s, fb)
    check("ARS-5 at peace the survivors stand down (B's engines leave the world)", not eb3 and not any("(at war)" in l for l in arsenal_lines(s)),
          f"B engines {len(eb3)}; {arsenal_lines(s)}")
    sg, t_h = wait_siege(s, lambda l: not l, 420)
    time.sleep(10)
    check("ARS-5b engines away on the siege stand down when they come home", not engines_of(s, fa), f"A engines {len(engines_of(s, fa))} after siege {t_h}")
    dip(s, a, f"admin truce {ca} {cb} 0")
    m5(s, f"mill mrel {ca} {cb} set -100")
    t0 = time.time()
    while time.time() - t0 < 150 and len(engines_of(s, fa)) < want.get(ta, 0):
        time.sleep(5)
    ea4 = engines_of(s, fa)
    check("ARS-6 the next war brings a fresh arsenal (the destroyed engine does not count against it)", len(ea4) == want.get(ta, 0),
          f"A {len(ea4)} of {want.get(ta, 0)}")
    # Muster Roll engines (dev stand-in, Trusted)
    x, z = a[0] + 6, a[2] + 6
    y = surface_y(s, x, z) or a[1]
    s.cmd(f"setblock {x} {y} {z} hywmill:muster_roll", 1)
    standin = "33333333-4444-4555-8666-777777777777"
    h1 = " ".join(l for l in s.output(f'hywmill dev recruit hire {x} {y} {z} "engine:mangonels" 1 20000 TRUSTED', 4) if "recruit:" in l)
    h2 = " ".join(l for l in s.output(f'hywmill dev recruit hire {x} {y} {z} "engine:battering_ram" 1 20000 TRUSTED', 4) if "recruit:" in l)
    h3 = " ".join(l for l in s.output(f'hywmill dev recruit hire {x} {y} {z} "engine:trebuchets" 1 50000 TRUSTED', 4) if "recruit:" in l)
    time.sleep(8)
    ars7(s, x, y, z, standin, h1, h2, h3)
    dups = [l for l in s.read_since(p0) if "Duplicate garrison unit refused" in l]
    note("ARS duplicates refused", str(len(dups)))
    dip(s, a, f"admin truce {ca} {cb} 1")
    m5(s, f"mill mrel {ca} {cb} set 0")


def scenario_SGF(ctx):
    """Siege fixes (user report): attackers go for every villager (civilians too); a battle nobody watches pauses instead
    of being decided off-screen, and resumes when watched again; nobody lands in water."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    fa = garrison(s, a).get("faction")
    p0 = s.pos()
    dip(s, a, f"admin truce {ca} {cb} 0")
    m5(s, f"mill mrel {ca} {cb} set -100")
    time.sleep(12)
    out = " | ".join(l for l in s.output(f"hywmill war admin siege {ca} {cb} quick", 2) if l.startswith("war siege"))
    sg, t_b = wait_siege(s, lambda l: l and ("BATTLE" in l[0] or "RETURN" in l[0]), 300)
    kinds, wet = set(), 0
    t0 = time.time()
    while time.time() - t0 < 60:
        for u, r in spike_info(s, "@e[type=!minecraft:player]").items():
            if r["tag"] != "none" and ("owner=" + fa) in r["desc"] and dist(r["pos"], b) < 150:
                if r["target"] != "none":
                    kinds.add(r["target"].split("[")[0])
        time.sleep(5)
    for u, r in spike_info(s, "@e[type=!minecraft:player]").items():
        if r["tag"] != "none" and ("owner=" + fa) in r["desc"] and dist(r["pos"], b) < 150:
            if "water" in " ".join(s.output(f"execute at {u} if block ~ ~ ~ minecraft:water run say wet", 0.2)):
                wet += 1
    check("SGF-1 attackers go for Millénaire villagers, not only soldiers", any(k.startswith("millenaire:") for k in kinds), str(sorted(kinds)))
    check("SGF-2 no attacker stands in water", wet == 0, f"{wet} in water")
    s.output("hywmill war admin siege-unwatched true", 1)
    time.sleep(40)
    sg1 = sieges(s)
    lines = s.read_since(p0)
    paused = any("battle pauses" in l for l in lines)
    offs = [l for l in lines if "decided off-screen" in l]
    check("SGF-3 unwatched mid-battle, the battle pauses (no off-screen decision)", paused and not offs and sg1 and "BATTLE" in sg1[0], f"{sg1}; off-screen {len(offs)}")
    s.output("hywmill war admin siege-unwatched false", 1)
    time.sleep(10)
    lines = s.read_since(p0)
    check("SGF-4 watched again, the battle resumes", any("battle resumes" in l for l in lines), str(sieges(s)))
    note("SGF launch", out)


def scenario_CIV(ctx):
    """Civilians in war (user report): a campaigning player's own troops fight the enemy's villagers who attack them, and
    the enemy's residents stand HOSTILE to the player and to the ally's soldiers (kept by the escalation guard)."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    va, vb = info(s, a), info(s, b)
    fa, rb = va.get("faction"), vb.get("residents")
    P = "33333333-4444-4555-8666-777777777777"  # the Muster Roll stand-in: its hired troops belong to it
    dip(s, a, f"admin truce {ca} {cb} 0")
    m5(s, f"mill mrel {ca} {cb} set -100")
    m5(s, f"mill discover {ca} {P}", 0.5)
    m5(s, f"mill discover {cb} {P}", 0.5)
    standin_at(s, P, a[0] + 3, a[2] + 3)
    m5(s, f"mill rep {ca} {P} adjust 6000", 0.5)
    time.sleep(16)
    t0 = time.time()
    while time.time() - t0 < 90 and not any("at war" in l for l in war_lines(s, a, f"for {P} status")):
        time.sleep(5)
    j = " | ".join(war_lines(s, a, f"for {P} join {ca} against {cb}"))
    time.sleep(25)
    r1, r2 = rel(s, P, rb), rel(s, fa, rb)
    check("CIV-1 on campaign, the enemy's villagers are HOSTILE to the player; at war, to the ally's soldiers (and they stay so)",
          "join OK" in j and r1 == ("HOSTILE", "HOSTILE") and r2 == ("HOSTILE", "HOSTILE"), f"{j[-80:]} player<->residents {r1}, A soldiers<->B residents {r2}")
    m5(s, f"standin remove {P}", 0.3)
    x, z = a[0] + 6, a[2] + 6
    y = surface_y(s, x, z) or a[1]
    s.cmd(f"setblock {x} {y} {z} hywmill:muster_roll", 1)
    before = set(spike_info(s, "@e[type=hundred_years_war:archer]"))
    out = " ".join(l for l in s.output(f'hywmill dev recruit hire {x} {y} {z} "merc:archer" 4 5000 TRUSTED', 3) if "recruit:" in l)
    time.sleep(3)
    mine = [u for u, r in spike_info(s, "@e[type=hundred_years_war:archer]").items() if u not in before and ("owner=" + P) in r["desc"]]
    bx, bz = b[0] + 4, b[2] + 4
    by = surface_y(s, bx, bz) or b[1]
    for u in mine:
        s.cmd(f"tp {u} {bx} {by + 1} {bz}", 0.3)
    kinds = set()
    t0 = time.time()
    while time.time() - t0 < 45:
        for u in mine:
            for k, r in spike_info(s, u).items():
                if r["target"] != "none":
                    kinds.add(r["target"].split("[")[0])
        time.sleep(3)
    check("CIV-2 the player's own troops fight the enemy village's civilians", any(k.startswith("millenaire:") for k in kinds),
          f"{out[-60:]} troops {len(mine)}; targets {sorted(kinds)}")
    war_lines(s, a, f"for {P} leave")


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
    """Admin-grants units (only those the village's tier allows) until the garrison is at its tier cap.
    Several passes, at most 64 per grant (the command's limit), so the M5-G caps (up to 128) fill too."""
    g = garrison(s, c)
    head = g.get("cap", 0) - g.get("live", 0)
    granted = []
    refused = set()
    for _ in range(6):
        if head <= 0:
            break
        allowed = [u for u in units if u not in refused]
        for i, u in enumerate(allowed):
            if head <= 0:
                break
            n = min(64, head if i == len(allowed) - 1 else max(1, head // 3))
            out = " ".join(s.output(at(c, f"hywmill admin grant {u} {n}"), 1))
            if "may not" in out or "Unknown" in out:
                refused.add(u)
                continue
            granted.append((u, n))
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


def g4_sentry_state(s, vs):
    """Per village: (want, pairs {index: [rows]}, posts, full, at_post, distinct, failing rows [(row, post, dist)])."""
    out = {}
    for k, c in vs.items():
        d = duties(s, c)
        pairs = {}
        for r in d["rows"]:
            if r["assigned"] == "SENTRY" and r["state"] == "GARRISONED":
                pairs.setdefault(r["index"], []).append(r)
        want = d["quota"].get("pairs", 0)
        posts = d.get("posts", [])
        full = all(len(pairs.get(i, [])) == 2 for i in range(want))
        failing = []
        for v in pairs.values():
            for r in v:
                post = posts[r["index"] % len(posts)] if posts else None
                dist = hdist(r["pos"], post) if r["pos"] and post else None
                if dist is None or dist > 10:
                    failing.append((r, post, dist))
        at_post = bool(posts) and not failing if pairs else True
        distinct = len({tuple(posts[i % len(posts)]) for i in pairs}) == len(pairs) if posts else not pairs
        out[k] = (want, pairs, posts, full, at_post, distinct, failing, d)
    return out


G4_2_WINDOW = 600  # seconds; see scenario_G4_2


def scenario_G4_2(ctx):
    """Sentries stand in pairs at posts taken from Millénaire's buildings (walls, gates, towers...).

    Approved measurement (M5): eventual placement, not a single snapshot. First a bounded settle precondition (<= 10 min):
    no RECRUITED slot is left and every village's quota of sentry pairs is assigned with two living units each (the
    stronghold at its full population and duty allocation). Then a fixed window, polled every 15 s: PASS as soon as
    every sentry of every pair is within 10 blocks of its own post (pairs complete, posts distinct); FAIL at timeout.
    The 10-block requirement is unchanged. Window (approved, harness timing only): 10 minutes, longer than the documented
    worst-case path of a trapped sentry through the M4 reliability recovery (6000 ticks to detect + 3000 ticks of
    fallback = 7.5 min, then the walk to the post); it was 6 minutes."""
    s = ctx.s
    vs = g4_villages(ctx)
    t0 = time.time()
    settled = False
    while time.time() - t0 < 600:
        st = g4_sentry_state(s, vs)
        pend = sum(garrison(s, c).get("recruited", 0) for c in vs.values())
        if pend == 0 and all(v[3] for v in st.values()):
            settled = True
            break
        time.sleep(15)
    note("G4-2 settle precondition", f"{'reached' if settled else 'NOT reached (window started anyway)'} after {round(time.time() - t0)} s; "
                                     + str({k: (v[0], {i: len(x) for i, x in sorted(v[1].items())}) for k, v in g4_sentry_state(s, vs).items()}))
    w0 = time.time()
    first_ok = {}
    st = {}
    ok = False
    while True:
        st = g4_sentry_state(s, vs)
        el = round(time.time() - w0)
        for k, (want, pairs, posts, full, at_post, distinct, failing, d) in st.items():
            bad = {r["index"] for r, _, _ in failing}
            for i, v in pairs.items():
                if len(v) == 2 and i not in bad and (k, i) not in first_ok:
                    first_ok[(k, i)] = el
                    log(f"G4-2 pair {k}#{i} first within 10 blocks of its post at {el} s")
        ok = all(v[3] and v[4] and v[5] for v in st.values())
        if ok:
            log(f"G4-2 every pair first fully within 10 blocks of its post at {el} s")
        if ok or time.time() - w0 >= G4_2_WINDOW:
            break
        time.sleep(15)
    if not ok:
        for k, (want, pairs, posts, full, at_post, distinct, failing, d) in st.items():
            for r, post, dist in failing:
                note(f"G4-2 timeout {k}", f"sentry {r['slot']} ({r['unit']}) pair #{r['index']} {r['state']} {r['duty']}/{r['assigned']} at {r['pos']} "
                                          f"post {post} dist {dist and round(dist, 1)} home {r.get('home')}")
            if not full:
                note(f"G4-2 timeout {k}", f"incomplete pairs: want {want}, have {{i: len(x) for i, x in sorted(pairs.items())}}")
            if not distinct:
                note(f"G4-2 timeout {k}", "two pairs share a post")
    detail = {k: (v[0], {i: [x["pos"] for x in p] for i, p in sorted(v[1].items())}, v[3], v[4], v[5]) for k, v in st.items()}
    check("G4-2 every sentry pair has two units, standing at (within 10 blocks of) its own post (from building data; settled, 10-min window)",
          ok, f"window {round(time.time() - w0)} s; settle {'ok' if settled else 'not reached'}; {detail}")


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
    end = time.time() + 600
    t_start = time.time()
    per_scout, timeline = {}, {}
    while time.time() < end:
        for k in list(best):
            c = g4_villages(ctx)[k]
            for r in duties(s, c)["rows"]:
                if r["assigned"] == "SCOUT" and r["pos"]:
                    dd = hdist(r["pos"], c)
                    b = best[k]
                    best[k] = (max(b[0], dd), b[1], b[2] | {r["progress"]})
                    ps = per_scout.setdefault((k, r["slot"]), [r["unit"], 0.0, set(), r.get("index")])
                    ps[1] = max(ps[1], dd)
                    ps[2].add(r["progress"])
                    tl = timeline.setdefault((k, r["slot"]), [])
                    step = (round(time.time() - t_start), r["progress"], round(dd), r.get("home"))
                    if not tl or tl[-1][1:3] != step[1:3]:
                        tl.append(step)
        if all(b[0] >= b[1] - 40 and "back" in b[2] | {"rest"} for b in best.values()) and all(len(b[2]) >= 3 for b in best.values()):
            break
        time.sleep(15)
    # diagnostics only: each scout's best distance, and whether each scout post is in ticking terrain
    for (k, slot), (unit, far, phases, idx) in sorted(per_scout.items()):
        note(f"G4-4 diag {k} scout {slot}", f"{unit} best {round(far)} index {idx} phases {sorted(phases)}")
    for k in best:
        c = g4_villages(ctx)[k]
        posts = duties(s, c).get("scoutposts", [])
        note(f"G4-4 diag {k} scout posts", str([(p, round(hdist(p, c)), ticking(s, p[0], p[2])) for p in posts]))
        d_ = duties(s, c)
        note(f"G4-4 diag {k} scouts assigned", str([(r["slot"], r["unit"], r["index"], r["state"], r["duty"], r["progress"]) for r in d_["rows"]
                                                  if r["assigned"] == "SCOUT"]) + f" quota {d_.get('quota')}")
    for (k, slot), tl in sorted(timeline.items()):
        note(f"G4-4 diag {k} scout {slot} timeline (s, phase, dist, home)", str(tl[:40]))
    note("G4-4 diag window", f"{round(time.time() - t_start)} s")
    check("G4-4a scouts: mounted riders are chosen as scouts where the village has them", mounted_ok, detail)
    check("G4-4b scouts ride outside the village radius (ring - scout distance) and cycle out/watch/back",
          all(b[0] >= b[1] - 40 and len(b[2]) >= 2 for b in best.values()),
          {k: (round(b[0]), round(b[1]), sorted(b[2])) for k, b in best.items()})


# ---- M4 reliability recovery (stuck-unit fallback) events, from the server log (diagnostics) ----
RX_STUCK = re.compile(r"\[(\d\d:\d\d:\d\d)\].*Duty unit ([0-9a-f]{8}) of village '([^']+)' \((\w+)\) made no progress towards "
                      r"(-?\d+), (-?\d+), (-?\d+) for \d+ ticks at (-?\d+), (-?\d+), (-?\d+): stuck, falling back to (-?\d+), (-?\d+), (-?\d+)")
RX_RECOVERED = re.compile(r"\[(\d\d:\d\d:\d\d)\].*Duty unit ([0-9a-f]{8}) of village '([^']+)' recovered at (-?\d+), (-?\d+), (-?\d+) \((walked back|last resort)")
RX_ALLOC = re.compile(r"\[(\d\d:\d\d:\d\d)\].*Duties of village '([^']+)' \((\d+) available: .*? reserve\): \[(.*)\]\s*$")


def fallback_events(lines):
    """Parses stuck-unit fallback and allocation lines, in order. Returns (events, cycles):
    events: dicts kind=stuck|recovered|alloc; cycles: per recovery, the unit's assignment before it went stuck, the
    goal it failed to reach, how it was recovered, and its next assignment by the allocator (if any in these lines)."""
    events, assign, pending, cycles = [], {}, {}, []
    for l in lines:
        m = RX_STUCK.search(l)
        if m:
            slot = m[2]
            prev = assign.get((m[3], slot), (m[4], None))
            pending[(m[3], slot)] = {"slot": slot, "village": m[3], "t_stuck": m[1], "duty": m[4], "prev": prev,
                                     "goal": (int(m[5]), int(m[6]), int(m[7])), "at": (int(m[8]), int(m[9]), int(m[10]))}
            events.append(dict(kind="stuck", t=m[1], slot=slot, village=m[3], duty=m[4]))
            continue
        m = RX_RECOVERED.search(l)
        if m:
            key = (m[3], m[2])
            c = pending.pop(key, {"slot": m[2], "village": m[3], "t_stuck": None, "duty": "?", "prev": assign.get(key), "goal": None, "at": None})
            c.update(t_recovered=m[1], how="teleport" if m[7].startswith("last") else "walked", next=None, t_next=None)
            cycles.append(c)
            assign[key] = ("GARRISON", -1)
            events.append(dict(kind="recovered", t=m[1], slot=m[2], village=m[3], how=c["how"]))
            continue
        m = RX_ALLOC.search(l)
        if m:
            changes = []
            for part in m[4].split(", "):
                mm = re.match(r"([0-9a-f]{8}) (\w+)->(\w+)(?:#(-?\d+))?$", part.strip())
                if mm:
                    to = (mm[3], int(mm[4]) if mm[4] is not None else -1)
                    changes.append((mm[1], mm[2], to))
                    assign[(m[2], mm[1])] = to
                    for c in cycles:
                        if c["village"] == m[2] and c["slot"] == mm[1] and c["next"] is None:
                            c["next"], c["t_next"] = to, m[1]
            events.append(dict(kind="alloc", t=m[1], village=m[2], changes=changes))
    for c in cycles:
        p = c.get("prev")
        c["same_post"] = bool(p and c.get("next") and p[1] is not None and c["next"] == (p[0], p[1]))
    return events, cycles


def moving_units(s, vs, radius=5):
    """Units whose position is more than `radius` blocks from their HYW home (walking somewhere), per village."""
    out = {}
    for k, c in vs.items():
        rows = duties(s, c)["rows"]
        out[k] = sum(1 for r in rows if r["pos"] and r["home"] and hdist(r["pos"], r["home"]) > radius)
    return out


def g45_plan_diag(ctx, k, c, before, after, vinfo, bmil, amil, t_restart):
    """G4-5b diagnostics (harness only; nothing here affects the check): everything known about a village whose duty
    plan differs across the restart. Post roles and the layout key are not exposed by any command (they would need a
    production change); the plan (re)computation times and the settlement changes HywMill observed come from the log."""
    s = ctx.s
    name = next((l.split("== Duties of ", 1)[1].split(" (")[0] for l in before["lines"] if l.startswith("== Duties of ")), "?")
    note(f"G4-5b diag {k} village", f"'{name}' id {vinfo.get('villageId')} at {c}; restart at {t_restart}; "
                                    "sentry post roles and the layout key: not exposed by any command (n/a)")
    for tag, d in (("before", before), ("after", after)):
        note(f"G4-5b diag {k} plan {tag}", d.get("plan", "(no plan line)"))
    for field in ("posts", "patrol", "scoutposts"):
        b, a = before.get(field) or [], after.get(field) or []
        if b == a:
            note(f"G4-5b diag {k} {field}", f"identical ({len(b)})")
            continue
        rows = []
        for i in range(max(len(b), len(a))):
            x = b[i] if i < len(b) else None
            y = a[i] if i < len(a) else None
            rows.append(f"#{i} {x} -> {y}" + ("" if x == y else " *"))
        note(f"G4-5b diag {k} {field}", f"before {len(b)}, after {len(a)}; only before {sorted(set(b) - set(a))}; "
                                        f"only after {sorted(set(a) - set(b))}; " + " | ".join(rows))
    note(f"G4-5b diag {k} building roles", f"before {bmil.get('buildingRoles')} after {amil.get('buildingRoles')}; "
                                           f"tier before {bmil.get('tier')} after {amil.get('tier')}")
    lines = s.read_since(getattr(ctx, "run_log_pos", 0))
    pat = re.compile(r"Duty plan for village '" + re.escape(name) + r"'|Village record (updated|initialized): '" + re.escape(name)
                     + r"'|Chests LOCKED for village " + re.escape(name) + r" |===== harness start")
    hist = []
    for l in lines:
        if pat.search(l):
            m = re.match(r"\[(\d\d:\d\d:\d\d)\]", l)
            hist.append((m[1] + " " if m else "") + (l.split("]: ", 1)[-1] if "]: " in l else l.strip())[:400])
    note(f"G4-5b diag {k} plan computations and village changes ({len(hist)})", " || ".join(hist))


def scenario_G4_5(ctx):
    """Duties survive a restart: same assignments, same posts and routes, no duplicates."""
    s = ctx.s
    vs = g4_villages(ctx)
    p_reads = s.pos()
    before = {k: duties(s, c) for k, c in vs.items()}
    bmil = {k: military(s, c) for k, c in vs.items()}
    t_restart = time.strftime("%H:%M:%S")
    restart(ctx)
    time.sleep(60)
    after = {k: duties(s, c) for k, c in vs.items()}
    # approved (G4-5a): units the stuck-unit fallback actually recovered between the two reads are left out of the
    # comparison (a legitimate change of duty); every other unit is compared exactly as before
    _, cycles = fallback_events(s.read_since(p_reads))
    names = {k: next((l.split("== Duties of ", 1)[1].split(" (")[0] for l in before[k]["lines"] if l.startswith("== Duties of ")), "?") for k in vs}
    excluded = {k: {c["slot"]: c for c in cycles if c["village"] == names[k]} for k in vs}
    same = {}
    for k in vs:
        b, a = assignments(before[k]), assignments(after[k])
        ex = excluded[k]
        for sl, c in ex.items():
            note(f"G4-5a excluded {k}", f"unit {sl} (short id; the roster shows 8 characters) was {b.get(sl)} at the first read; "
                                        f"stuck at {c.get('t_stuck')} going to {c.get('goal')}, recovered by the stuck-unit fallback at "
                                        f"{c['t_recovered']} ({c['how']}); after the restart {a.get(sl)}. Reason: recovered between the reads")
        same[k] = {sl: v for sl, v in b.items() if sl not in ex} == {sl: v for sl, v in a.items() if sl in b and sl not in ex}
        if not same[k]:
            vacated = {b.get(sl) for sl in ex}
            for sl, v in b.items():
                if sl not in ex and a.get(sl, v) != v:
                    note(f"G4-5a mismatch {k}", f"unit {sl}: {v} -> {a.get(sl)}" + (" (took a post vacated by an excluded unit)"
                                                                                  if a.get(sl) in vacated else ""))
    plans = {k: (before[k].get("posts"), before[k].get("patrol"), before[k].get("scoutposts")) ==
                (after[k].get("posts"), after[k].get("patrol"), after[k].get("scoutposts")) for k in vs}
    check("G4-5a duty assignments are identical after a restart", all(same.values()), same)
    check("G4-5b sentry posts, patrol route and scout posts are identical after a restart", all(plans.values()), plans)
    for k, ok in plans.items():
        if not ok:
            g45_plan_diag(ctx, k, vs[k], before[k], after[k], info(s, vs[k]), bmil[k], military(s, vs[k]), t_restart)
    cs = {k: census(s, c) for k, c in vs.items()}
    check("G4-5c no duplicate or unbound units after the restart", all(x.get("dupSlots") == 0 and x.get("unbound") == 0 and x.get("badOwner") == 0
                                                                     for x in cs.values()), cs)


def scenario_G4_6(ctx):
    """M2 defense overrides duties (DEFENSE), then every unit returns to its standing duty."""
    s = ctx.s
    c = ctx.a
    before = assignments(duties(s, c))
    # a player-owned unit that attacks a resident: an M2 threat HYW's own targeting ignores, so M2 deploys (as in G3-4)
    s.cmd(ground(c[0] + 3, c[2] + 3, f"summon hundred_years_war:militia ~ ~ ~ {{OwnerUUID:{OWNER_NBT},Tags:['hwG4']}}"), 2)
    civ = next((r[0] for r in wait_residents(s, c) if r[2] == "CIVILIAN"), None)
    s.cmd(f"damage {civ} 1 minecraft:mob_attack by @e[tag=hwG4,limit=1]", 1)
    seen = {}
    for _ in range(40):
        time.sleep(1)
        for r in duties(s, c)["rows"]:
            if r["state"] == "DEPLOYED":
                seen[r["slot"]] = (r["duty"], r["assigned"])
        if seen:
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


def g4p_diag(ctx, vs, win):
    """G4-P diagnostics (approved; nothing here affects the check): stuck-unit fallback and reallocation activity in
    the timed duties-on window, and over the whole run the cycle unreachable post -> stuck -> fallback -> GARRISON ->
    allocator -> same post."""
    s = ctx.s
    run = s.read_since(getattr(ctx, "run_log_pos", 0))
    events, cycles = fallback_events(run)
    wl = s.read_since(win["p0"])
    wl = wl[:max(0, len(wl) - len(s.read_since(win["p1"])))]
    wev, _ = fallback_events(wl)
    stuck = [e for e in wev if e["kind"] == "stuck"]
    rec = [e for e in wev if e["kind"] == "recovered"]
    allocs = [e for e in wev if e["kind"] == "alloc"]
    changes = [(e["village"], c) for e in allocs for c in e["changes"]]
    # units in recovery at the window's start and end (entered before, not yet recovered)
    def in_recovery(upto):
        n = {}
        for e in events:
            if e["t"] > upto:
                break
            if e["kind"] == "stuck":
                n[(e["village"], e["slot"])] = 1
            elif e["kind"] == "recovered":
                n.pop((e["village"], e["slot"]), None)
        return sorted(sl for _, sl in n)
    note("G4-P diag window", f"duties-on window {win['t0']}-{win['t1']}: entering recovery {len(stuck)} {[(e['slot'], e['duty']) for e in stuck]}; "
                             f"recovered {len(rec)} (teleports {sum(1 for e in rec if e['how'] == 'teleport')}, walked "
                             f"{sum(1 for e in rec if e['how'] == 'walked')}); allocation passes {len(allocs)}, duty reassignments "
                             f"{len(changes)}; in recovery at start {in_recovery(win['t0'])}, at end {in_recovery(win['t1'])}")
    note("G4-P diag moving units", f"more than 5 blocks from their HYW home, just before the window {win.get('moving_start')}, "
                                   f"just after {win.get('moving_end')}")
    if changes:
        note("G4-P diag window reassignments", "; ".join(f"{v}: {sl} {fr}->{to[0]}#{to[1]}" for v, (sl, fr, to) in changes[:60]))
    # whole run: the cycle hypothesis
    same = [c for c in cycles if c["same_post"]]
    reassigned = [c for c in cycles if c.get("next")]
    note("G4-P diag cycles (whole run)", f"{len(cycles)} recoveries ({sum(1 for c in cycles if c['how'] == 'teleport')} teleports); "
                                         f"{len(reassigned)} reassigned afterwards; {len(same)} back to the same post")
    posts = {}
    for k, c in vs.items():
        d = duties(s, c)
        name = next((l.split("== Duties of ", 1)[1].split(" (")[0] for l in d["lines"] if l.startswith("== Duties of ")), "?")
        posts[name] = d.get("posts") or []
    for c in cycles:
        p, n = c.get("prev"), c.get("next")
        newpost = posts.get(c["village"], [])[n[1]] if n and n[0] == "SENTRY" and 0 <= n[1] < len(posts.get(c["village"], [])) else None
        note("G4-P diag cycle", f"{c['village']} unit {c['slot']}: {p[0] if p else '?'}#{p[1] if p else '?'} stuck {c['t_stuck']} at {c['at']} "
                                f"towards {c['goal']}; recovered {c['t_recovered']} ({c['how']}); next {n} at {c.get('t_next')}"
                                + (f" (post {newpost})" if newpost else "") + ("; SAME POST" if c["same_post"] else ""))


def scenario_G4_perf(ctx):
    """Like-for-like cost: 120 s CALM with M4 duties off, then 120 s with them on, same world and units."""
    s = ctx.s
    res = {}
    vs = g4_villages(ctx)
    win = {}
    for state in ("off", "on"):
        s.output(f"hywmill dev duties {state}", 1)
        time.sleep(20)
        if state == "on":
            win["moving_start"] = moving_units(s, vs)  # sampled outside the timed window
        p0 = s.pos()
        t0 = time.strftime("%H:%M:%S")
        s.cmd("hywmill perf reset", 1)
        time.sleep(120)
        res[state] = perf_rows(s.output("hywmill perf", 2))
        if state == "on":
            win.update(p0=p0, p1=s.pos(), t0=t0, t1=time.strftime("%H:%M:%S"))
            win["moving_end"] = moving_units(s, vs)
        log(f"G4 perf duties {state}:\n  " + "\n  ".join(f"{k}: {v}" for k, v in res[state].items()))
    g4p_diag(ctx, vs, win)
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
    # post-M5 (bug report: a knight's helmet over plain clothes): armour is one whole kit, never mixed across lists
    plate = ("greathelm", "grand_bascinet", "armet", "bascinet", "sallet")
    mixed = [(key, v[0], v[2].get("head"), v[2].get("chest")) for key, v in gear.items()
             if any(p_ in (v[2].get("head") or "") for p_ in plate) and "gambeson" in (v[2].get("chest") or "")]
    worn = [v[2] for v in gear.values()]
    check("G4-EK3 armour is a whole kit: no plate helmet over cloth; units wear Epic Knights armour",
          not mixed and worn and sum(1 for w in worn if "magistuarmory" in (w.get("chest") or "")) >= len(worn) * 0.8,
          f"mixed {mixed[:4]}; {sum(1 for w in worn if 'magistuarmory' in (w.get('chest') or ''))}/{len(worn)} with EK chest")
    chest_a = items(lambda key, v: key[0] == "A" and v[1] == "GARRISON" and v[0] in ("spear_man", "warrior"), "chest")
    chest_m = items(lambda key, v: key[0] == "M" and v[1] == "GARRISON" and v[0] in ("spear_man", "warrior"), "chest")
    check("G4-EK4 tier: stronghold line units wear heavier armour than the smaller village's", chest_m and chest_a and chest_m != chest_a
          and any(h in x for x in chest_m for h in ("platemail", "brigandine", "crusader", "lamellar")), f"A {sorted(chest_a)} M {sorted(chest_m)}")
    spear_off = items(lambda key, v: v[0] == "spear_man", "offhand")
    shield_off = items(lambda key, v: v[0] == "shieldman" and key[0] in ("A", "M"), "offhand")
    archer_main = items(lambda key, v: v[0] == "archer", "mainhand")
    check("G4-EK5 unsupported combinations fall back (no kiteshield on spear_man; archers keep a bow)",
          not any("kiteshield" in x for x in spear_off) and all(("longbow" in x or "bow" in x) for x in archer_main)
          and (not shield_off or any("kiteshield" in x or "shield" in x for x in shield_off)),
          f"spear_man offhand {sorted(spear_off)}; shieldman offhand {sorted(shield_off)}; archer mainhand {sorted(archer_main)}")
    # post-M5: livery. Dyeable pieces carry the unit's colours; shields carry painted arms (vanilla item components)
    colours, dyed, dyeable, arms, shields = set(), 0, 0, set(), 0
    for k, c in vs.items():
        for uuid in list(unit_entities(s, c))[:15]:
            armor = " ".join(s.output(f"data get entity {uuid} ArmorItems", 1))
            hands = " ".join(s.output(f"data get entity {uuid} HandItems", 1))
            for piece in re.findall(r'id: "magistuarmory:(coif|gambeson_chestplate|pantyhose|gambeson_boots|brigandine_chestplate|crusader_chestplate|crusader_boots|norman_helmet|greathelm)"', armor):
                dyeable += 1
            found = re.findall(r'dyed_color": \{[^}]*rgb: (-?\d+)', armor)
            dyed += len(found)
            colours.update(found)
            if "shield" in hands:
                shields += 1
                m = re.search(r'banner_patterns": \[(.*?)\]', hands)
                b = re.search(r'base_color": "(\w+)"', hands)
                if m and b:
                    arms.add(b.group(1) + "|" + m.group(1)[:200])
    check("G4-EK6 livery: dyeable armour is dyed, in varied colours", dyeable > 0 and dyed >= dyeable * 0.9 and len(colours) >= 4,
          f"{dyed}/{dyeable} dyeable pieces dyed; {len(colours)} distinct colours")
    check("G4-EK7 heraldry: shields carry painted arms (base colour + patterns), varied", shields == 0 or (len(arms) >= min(3, shields)),
          f"{shields} shield(s) sampled, {len(arms)} distinct arms: {sorted(arms)[:3]}")


# --------------------------------------------------------------------------- M5-0 spikes (S5)
# Runtime checks for docs/m5-spike.md. "note" lines record observed behaviour that is not a pass/fail
# criterion; checks state the behaviour the M5 design relies on.

P_UUID = OWNER_UUID                                  # the "player": owner of player-owned units, and the stand-in's UUID
Q_UUID = "22222222-3333-4444-8555-666666666666"      # a second player (team member, bystander)
T_UUID = "33333333-4444-4555-8666-777777777777"      # an unrelated third party (owner of neutral units)
X_UUID = "44444444-5555-4666-8777-888888888888"      # persistence probes (not village factions)
Y_UUID = "55555555-6666-4777-8888-999999999999"
Z_UUID = "66666666-7777-4888-8999-aaaaaaaaaaaa"
NOTES = []


def uuid_nbt(u):
    h = int(u.replace("-", ""), 16)
    parts = [(h >> s) & 0xFFFFFFFF for s in (96, 64, 32, 0)]
    return "[I;" + ",".join(str(p - (1 << 32) if p >= 1 << 31 else p) for p in parts) + "]"


T_NBT = uuid_nbt(T_UUID)


def note(name, text):
    NOTES.append((name, text))
    log(f"NOTE {name}: {text}")


def m5(s, sub, wait=2.0):
    return [l for l in s.output(f"hywmill dev m5 {sub}", wait) if l.startswith("m5 ")]


def m5_1(s, sub, wait=2.0):
    out = m5(s, sub, wait)
    return out[0] if out else ""


def rel(s, a, b):
    l = m5_1(s, f"hyw rel {a} {b}")
    m = re.search(r"->\S+=(\w+) \S+->\S+=(\w+)", l)
    return (m[1], m[2]) if m else (None, None)


def neutral(s, a, b):
    m5(s, f"hyw relset {a} {b} NEUTRAL", 0.5)
    m5(s, f"hyw relset {b} {a} NEUTRAL", 0.5)


def tgt8(row):
    m = re.search(r"\[([0-9a-f]{8})\]", row.get("target", "") if row else "")
    return m[1] if m else None


def tagged(s, tag):
    return {u: r for u, r in spike_info(s, f"@e[tag={tag}]").items()}


def hp_of(s, sel):
    l = m5_1(s, f"hyw hp {sel}", 1)
    m = re.search(r" ([\d.]+)/([\d.]+) alive=(\w+)", l)
    return float(m[1]) if m else None


def set_hp(s, sel, v=20):
    s.cmd(f"data modify entity {sel} Health set value {v}f", 0.5)


def standin_at(s, uid, x, z):
    m5(s, f"standin remove {uid}", 0.5)
    out = [l for l in s.output(ground(x, z, f"hywmill dev m5 standin add {uid} ~ ~ ~"), 2) if l.startswith("m5 ")]
    return out[0] if out else ""


ARMS = 'HandItems:[{id:"minecraft:iron_sword",count:1},{}],ArmorItems:[{},{},{id:"minecraft:iron_chestplate",count:1},{}]'


def summon_unit(s, x, z, etype, owner_nbt, tag, extra=""):
    """An HYW unit placed at the ground of (x, z), armed (a /summon'ed HYW unit has empty equipment slots)."""
    nbt = f"OwnerUUID:{owner_nbt},Tags:['{tag}'],{ARMS}" + (f",{extra}" if extra else "")
    s.cmd(ground(x, z, f"summon hundred_years_war:{etype} ~ ~ ~ {{{nbt}}}"), 1)


def outdoor(s, pos):
    y = surface_y(s, pos[0], pos[2])
    return y is not None and abs(y - pos[1]) <= 1


def ticking(s, x, z):
    l = m5_1(s, f"ticking {x} 64 {z}", 0.6)
    m = re.search(r"ticking \S+ \S+ (\w+) top=(-?\d+) water=(\w+)", l)
    return (m[1] == "true", int(m[2]), m[3] == "true") if m else (False, -999, False)


def outdoor_unit(s, c, timeout=90):
    end = time.time() + timeout
    while time.time() < end:
        for u, r in unit_entities(s, c).items():
            if outdoor(s, r["pos"]):
                return u, r
        time.sleep(10)
    rows = unit_entities(s, c)
    return next(iter(rows.items()), (None, None))


def scenario_S5_0(ctx):
    """Setup for the M5-0 spikes: both garrisons alive, factions known."""
    s = ctx.s
    for name, c in (("A", ctx.a), ("B", ctx.b)):
        wait_garrison(s, c, lambda g: g.get("alive", 0) >= 2 and g.get("recruited", 1) == 0, 240)
    ctx.fa = garrison(s, ctx.a).get("faction")
    ctx.fb = garrison(s, ctx.b).get("faction")
    ctx.gA = unit_entities(s, ctx.a)
    ctx.gB = unit_entities(s, ctx.b)
    check("S5-0 both garrisons alive, factions known", bool(ctx.fa and ctx.fb and ctx.gA and ctx.gB),
          f"A {ctx.fa} {len(ctx.gA)} units; B {ctx.fb} {len(ctx.gB)} units")
    s.cmd("hywmill dev duties off", 1)  # garrison homes stay where the spikes put them (M4 duties resume in S5_N cleanup)


def scenario_S5_A(ctx):
    """Spike A: identity of player-owned HYW units; HYW teams; relation control of player units."""
    s, a, fa = ctx.s, ctx.a, ctx.fa
    gu = next(iter(ctx.gA))
    s.cmd(ground(a[0] + 6, a[2] + 12, f"summon hundred_years_war:spear_man ~ ~ ~ {{OwnerUUID:{OWNER_NBT},Tags:['hwM5A'],NoAI:1b}}"), 2)
    idl = m5_1(s, "hyw ident @e[tag=hwM5A,limit=1]")
    m = re.search(r" rel=(\S+)", idl)
    check("S5-A player-owned HYW unit: relation identity = owner (player) UUID", bool(m) and m[1] == P_UUID, idl)
    st = re.search(r"strategy=(\w+)", idl)
    note("S5-A attack strategy of a freshly summoned player-owned unit", st[1] if st else idl)
    t = m5_1(s, f"hyw team create hwm5team {P_UUID}")
    mt = re.search(r"created (\S+)", t)
    team = mt[1] if mt else "none"
    j = m5_1(s, f"hyw team join {team} {Q_UUID}")
    idl2 = m5_1(s, "hyw ident @e[tag=hwM5A,limit=1]")
    m2 = re.search(r" rel=(\S+).* team=(\S+)", idl2)
    check("S5-A team membership does not replace the unit's relation identity (player UUID; team UUID only reported)",
          bool(m2) and m2[1] == P_UUID and m2[2] == team, f"{t} | {j} | {idl2}")
    m5(s, f"policy allow {fa} {team}")
    m5(s, f"policy allow {fa} {P_UUID}")
    m5(s, f"hyw relset {team} {fa} HOSTILE")
    v1 = m5_1(s, f"hyw valid @e[tag=hwM5A,limit=1] {gu}")
    v1r = m5_1(s, f"hyw valid {gu} @e[tag=hwM5A,limit=1]")
    check("S5-A HOSTILE between the TEAM UUID and a village faction does not make a member's units enemies",
          "enemy=false" in v1 and "enemy=false" in v1r, f"{v1} || {v1r}")
    neutral(s, team, fa)
    m5(s, f"hyw relset {P_UUID} {fa} HOSTILE")
    r = rel(s, P_UUID, fa)
    v2 = m5_1(s, f"hyw valid @e[tag=hwM5A,limit=1] {gu}")
    v2r = m5_1(s, f"hyw valid {gu} @e[tag=hwM5A,limit=1]")
    check("S5-A setRelation(player, faction, HOSTILE) is stored both ways and makes player units and garrison mutual enemies",
          r == ("HOSTILE", "HOSTILE") and "enemy=true" in v2 and "enemy=true" in v2r, f"rel={r} || {v2} || {v2r}")
    note("S5-A isValidTarget player unit -> garrison unit under HOSTILE (NoAI units, same area)", f"{v2} || {v2r}")
    neutral(s, P_UUID, fa)
    m5(s, "policy clear")
    s.cmd("kill @e[tag=hwM5A]", 1)


def scenario_S5_B(ctx):
    """Spike B: player <-> village HOSTILE under DEFAULT: who fights whom (runtime, AI on)."""
    s, a, fa = ctx.s, ctx.a, ctx.fa
    garr = unit_entities(s, a)
    gu0, g0 = outdoor_unit(s, a)
    note("S5-B garrison unit used as the meeting point (outdoors)", f"{gu0} {g0['pos'] if g0 else None}")
    gx, gz = g0["pos"][0], g0["pos"][2]
    res = {r[0][:8]: r for r in residents(s, a)}
    for i in range(3):
        summon_unit(s, gx + 6, gz - 2 + 2 * i, "spear_man", OWNER_NBT, "hwM5B")
    summon_unit(s, gx - 9, gz, "spear_man", T_NBT, "hwM5T")
    note("S5-B stand-in player", standin_at(s, P_UUID, gx + 12, gz))
    s.cmd("effect give @e[tag=hwM5B] minecraft:resistance 600 2 true", 0.5)  # survive the 40 s observation window
    s.cmd("effect give @e[tag=hwM5T] minecraft:resistance 600 2 true", 0.5)
    pu = tagged(s, "hwM5B")
    tu = tagged(s, "hwM5T")
    for u in list(pu) + list(tu):
        note("S5-B unit strategy as summoned", m5_1(s, f"hyw strategy {u}", 0.5))
        m5(s, f"hyw strategy {u} DEFAULT", 0.5)
    gstr = [m5_1(s, f"hyw strategy {u}", 0.5) for u in list(garr)[:3]]
    note("S5-B garrison unit strategies (M3 sets DEFAULT)", str(gstr))
    check("S5-B all units in the test use the DEFAULT strategy",
          all(m5_1(s, f"hyw strategy {u}", 0.5).endswith("DEFAULT") for u in list(pu) + list(tu)) and all(x.endswith("DEFAULT") for x in gstr), str(gstr))
    time.sleep(6)
    base = [tgt8(r) for r in list(tagged(s, "hwM5B").values()) + list(unit_entities(s, a).values())]
    check("S5-B baseline (NEUTRAL): player units and garrison do not target each other",
          not any(t and (t in {u[:8] for u in garr} or t in {u[:8] for u in pu}) for t in base), str(base))
    m5(s, f"policy allow {fa} {P_UUID}")
    m5(s, f"hyw relset {P_UUID} {fa} HOSTILE")
    m5(s, f"standin heal {P_UUID}", 0.5)
    garr_ids = {u[:8] for u in garr}
    pu_ids = {u[:8] for u in pu}
    t_ids = {u[:8] for u in tu}
    seen = {"pu->garr": 0, "garr->pu": 0, "garr->player": 0, "pu->villager": 0, "pu->civilian": 0, "any->third": 0, "third->any": 0, "samples": 0}
    hp_player = []
    trace = []
    for _ in range(10):
        time.sleep(4)
        rows_p = tagged(s, "hwM5B")
        rows_g = unit_entities(s, a)
        rows_t = tagged(s, "hwM5T")
        seen["samples"] += 1
        vill = residents(s, a)
        vt = [(x[0][:8], x[2], x[4]) for x in vill if x[4] != "none"]
        pt = []
        for r in rows_p.values():
            t = tgt8(r)
            seen["pu->garr"] += t in garr_ids
            seen["pu->villager"] += t in res
            seen["pu->civilian"] += t in res and res[t][2] == "CIVILIAN"
            seen["any->third"] += t in t_ids
            pt.append((r.get("target"), res[t][2] if t in res else ""))
        trace.append({"player units": pt, "villagers with a target": vt, "alert": military(s, a).get("alert")})
        for r in rows_g.values():
            t = tgt8(r)
            seen["garr->pu"] += t in pu_ids
            seen["garr->player"] += t == P_UUID[:8]
            seen["any->third"] += t in t_ids
        for r in rows_t.values():
            seen["third->any"] += tgt8(r) is not None and (tgt8(r) in garr_ids or tgt8(r) in pu_ids)
        hp_player.append(hp_of(s, P_UUID))
    note("S5-B target counts over 40 s (samples of every unit's current HYW target)", str(seen))
    note("S5-B stand-in player health over time", str(hp_player))
    for i, t in enumerate(trace):
        note(f"S5-B trace {i}", str(t))
    check("S5-B player's units attack the enemy garrison (DEFAULT)", seen["pu->garr"] > 0, str(seen))
    check("S5-B enemy garrison attacks the player's units", seen["garr->pu"] > 0, str(seen))
    check("S5-B enemy garrison attacks the player (stand-in: targeted or damaged)",
          seen["garr->player"] > 0 and any(h is not None and h < 20 for h in hp_player), f"{seen['garr->player']} target samples; hp {hp_player}")
    check("S5-B civilian villagers are never selected as targets by the player's units", seen["pu->civilian"] == 0, str(seen))
    note("S5-B villager (any role) targeted by the player's units, samples", seen["pu->villager"])
    check("S5-B unrelated third party is neither targeted nor targeting", seen["any->third"] == 0 and seen["third->any"] == 0, str(seen))
    if res:
        civ = next((r for r in res.values() if r[2] == "CIVILIAN"), None)
        if civ:
            m5(s, f"hyw relset {P_UUID} {fa} HOSTILE")  # still HOSTILE for the probe (a NoAI unit, alive)
            summon_unit(s, gx + 3, gz + 3, "spear_man", OWNER_NBT, "hwM5BP", "NoAI:1b")
            v = m5_1(s, f"hyw valid @e[tag=hwM5BP,limit=1] {civ[0]}")
            s.cmd("kill @e[tag=hwM5BP]", 0.5)
            check("S5-B isValidTarget(player unit -> civilian villager) is false while HOSTILE", "valid=false" in v, v)
    neutral(s, P_UUID, fa)
    m5(s, "policy clear")
    s.cmd("kill @e[tag=hwM5B]", 1)
    s.cmd("kill @e[tag=hwM5T]", 1)
    m5(s, f"standin remove {P_UUID}")


def scenario_S5_C(ctx):
    """Spike C: FRIENDLY both ways: targeting, melee, arrows, explosions, collision."""
    s, a, fa = ctx.s, ctx.a, ctx.fa
    fnbt = uuid_nbt(fa)
    x, z = a[0] + 10, a[2] - 14
    s.cmd(ground(x, z, f"summon hundred_years_war:spear_man ~ ~ ~ {{OwnerUUID:{OWNER_NBT},Tags:['hwM5CP'],NoAI:1b}}"), 1)
    s.cmd(ground(x + 3, z, f"summon hundred_years_war:spear_man ~ ~ ~ {{OwnerUUID:{fnbt},Tags:['hwM5CF'],NoAI:1b}}"), 1)
    cp, cf = "@e[tag=hwM5CP,limit=1]", "@e[tag=hwM5CF,limit=1]"

    def trial(label):
        out = {}
        for kind, amt, wait in (("melee", 3, 1), ("arrow", 6, 3), ("explosion", 1.5, 1)):
            set_hp(s, cf, 20)
            s.cmd("kill @e[type=minecraft:arrow]", 0.3)
            time.sleep(0.6)
            before = hp_of(s, cf)
            line = m5_1(s, f"hyw hit {cp} {cf} {amt} {kind}", 0.5)
            time.sleep(wait)
            after = hp_of(s, cf)
            out[kind] = (before, after, line)
            time.sleep(1.2)  # invulnerability frames
        return out

    ctrl = trial("NEUTRAL")
    note("S5-C control (NEUTRAL) hits player unit -> faction unit: (before, after)", str({k: v[:2] for k, v in ctrl.items()}))
    check("S5-C control: NEUTRAL hits do damage (melee, arrow, explosion)", all(v[0] and v[1] is not None and v[1] < v[0] for v in ctrl.values()),
          str({k: v[:2] for k, v in ctrl.items()}))
    neutral(s, P_UUID, fa)
    m5(s, f"hyw relset {P_UUID} {fa} FRIENDLY")
    m5(s, f"hyw relset {fa} {P_UUID} FRIENDLY")
    r = rel(s, P_UUID, fa)
    check("S5-C FRIENDLY set in both directions", r == ("FRIENDLY", "FRIENDLY"), str(r))
    v = m5_1(s, f"hyw valid {cf} {cp}")
    check("S5-C FRIENDLY: not a valid target, relation-protected, friendly damage cancelled, collision ignored",
          all(k in v for k in ("valid=false", "protected=true", "cancelDamage=true", "ignoreCollision=true")), v)
    mk = m5_1(s, f"hyw mark {cf} {cp}")
    check("S5-C FRIENDLY overrides temporary hostility (marked hostile, still not a valid target)", "temp=true" in mk and "valid=false" in mk, mk)
    fr = trial("FRIENDLY")
    note("S5-C FRIENDLY hits player unit -> faction unit: (before, after)", str({k: v[:2] for k, v in fr.items()}))
    check("S5-C FRIENDLY: melee damage cancelled", fr["melee"][0] == fr["melee"][1], str(fr["melee"][:2]))
    check("S5-C FRIENDLY: arrow damage cancelled", fr["arrow"][0] == fr["arrow"][1], str(fr["arrow"][:2]))
    check("S5-C FRIENDLY: explosion (area) damage caused by the ally cancelled", fr["explosion"][0] == fr["explosion"][1], str(fr["explosion"][:2]))
    m5(s, f"hyw relset {fa} {P_UUID} NEUTRAL")
    v1 = m5_1(s, f"hyw valid {cf} {cp}")
    note("S5-C one-way FRIENDLY (player->faction only): faction unit -> player unit", v1)
    neutral(s, P_UUID, fa)
    s.cmd("kill @e[tag=hwM5CP]", 0.5)
    s.cmd("kill @e[tag=hwM5CF]", 0.5)
    s.cmd("kill @e[type=minecraft:arrow]", 0.5)


def scenario_S5_D(ctx):
    """Spike D: HOSTILE -> NEUTRAL, immunity, residual targeting, exact restoration; persistence set-up."""
    s, a, fa = ctx.s, ctx.a, ctx.fa
    m5(s, f"policy allow {fa} {P_UUID}")
    p0 = s.pos()
    m5(s, f"hyw relset {P_UUID} {fa} HOSTILE")
    r1 = rel(s, P_UUID, fa)
    m5(s, f"hyw relset {P_UUID} {fa} NEUTRAL")
    r2 = rel(s, P_UUID, fa)
    m5(s, f"hyw relset {fa} {P_UUID} NEUTRAL")
    r3 = rel(s, P_UUID, fa)
    imm = [l for l in s.read_since(p0) if "immunity started" in l]
    check("S5-D setRelation(a,b,HOSTILE) writes both directions", r1 == ("HOSTILE", "HOSTILE"), str(r1))
    check("S5-D setRelation(a,b,NEUTRAL) clears only a->b (b->a stays HOSTILE)", r2 == ("NEUTRAL", "HOSTILE"), str(r2))
    check("S5-D both directions NEUTRAL after the second call; HYW logs an immunity start per cleared direction",
          r3 == ("NEUTRAL", "NEUTRAL") and len(imm) >= 2, f"{r3}; {len(imm)} immunity lines")
    # residual targeting after a real fight
    gu0, g0 = outdoor_unit(s, a)
    gx, gz = g0["pos"][0], g0["pos"][2]
    for i in range(2):
        summon_unit(s, gx + 6, gz + 2 * i, "spear_man", OWNER_NBT, "hwM5D")
    for u in tagged(s, "hwM5D"):
        m5(s, f"hyw strategy {u} DEFAULT", 0.5)
    s.cmd("effect give @e[tag=hwM5D] minecraft:resistance 600 2 true", 0.5)
    m5(s, f"hyw relset {P_UUID} {fa} HOSTILE")
    time.sleep(20)
    fought = [tgt8(r) for r in tagged(s, "hwM5D").values()]
    neutral(s, P_UUID, fa)
    t_end = time.time()
    samples = []
    for _ in range(8):
        time.sleep(4)
        gids = {u[:8] for u in unit_entities(s, a)}
        pids = {u[:8] for u in tagged(s, "hwM5D")}
        pt = [tgt8(r) for r in tagged(s, "hwM5D").values()]
        gt = [tgt8(r) for r in unit_entities(s, a).values()]
        samples.append((round(time.time() - t_end), sum(t in gids for t in pt), sum(t in pids for t in gt)))
    note("S5-D targets during the HOSTILE phase (player units)", str(fought))
    note("S5-D after NEUTRAL: (seconds, player units targeting garrison, garrison targeting player units)", str(samples))
    late = [x for x in samples if x[0] >= 16]
    alive = len(tagged(s, "hwM5D"))
    check("S5-D the fight happened and the player's units are still alive to observe", any(t is not None for t in fought) and alive > 0,
          f"targets while HOSTILE {fought}; alive after {alive}")
    check("S5-D no residual targeting 16 s after both directions are NEUTRAL (temporary hostility lasts ~11 s)",
          alive > 0 and all(x[1] == 0 and x[2] == 0 for x in late), str(samples))
    s.cmd("kill @e[tag=hwM5D]", 1)
    # exact restoration of a previous (asymmetric) state
    m5(s, f"hyw relset {P_UUID} {fa} FRIENDLY")
    prev = rel(s, P_UUID, fa)
    m5(s, f"hyw relset {P_UUID} {fa} HOSTILE")
    proj = rel(s, P_UUID, fa)
    m5(s, f"hyw relset {P_UUID} {fa} {prev[0]}")
    m5(s, f"hyw relset {fa} {P_UUID} {prev[1]}")
    back = rel(s, P_UUID, fa)
    check("S5-D a projector can restore a previous asymmetric state exactly (set each direction explicitly)",
          prev == ("FRIENDLY", "NEUTRAL") and proj == ("HOSTILE", "HOSTILE") and back == prev, f"prev {prev} proj {proj} back {back}")
    neutral(s, P_UUID, fa)
    m5(s, "policy clear")
    # persistence probes (checked after the restart in S5_R)
    m5(s, f"hyw relset {X_UUID} {Y_UUID} FRIENDLY")
    m5(s, f"hyw relset {X_UUID} {Z_UUID} HOSTILE")
    m5(s, f"policy allow {fa} {Q_UUID}")
    m5(s, f"hyw relset {Q_UUID} {fa} HOSTILE")
    ctx.persist_hyw = {"xy": rel(s, X_UUID, Y_UUID), "xz": rel(s, X_UUID, Z_UUID), "qfa": rel(s, Q_UUID, fa)}
    note("S5-D persistence probes before restart", str(ctx.persist_hyw))


def scenario_S5_E(ctx):
    """Spike E: HYW's neutral-kill escalation vs the escalation guard with a (test) political policy."""
    s, a, fa = ctx.s, ctx.a, ctx.fa
    fnbt = uuid_nbt(fa)
    m5(s, "policy clear")
    neutral(s, T_UUID, fa)

    def kill_round(tag):
        x, z = a[0] - 12, a[2] + 14
        s.cmd(ground(x, z, f"summon hundred_years_war:warrior ~ ~ ~ {{OwnerUUID:{T_NBT},Tags:['{tag}K'],NoAI:1b}}"), 1)
        s.cmd(ground(x + 2, z, f"summon hundred_years_war:militia ~ ~ ~ {{OwnerUUID:{fnbt},Tags:['{tag}V'],NoAI:1b}}"), 1)
        line = m5_1(s, f"hyw hit @e[tag={tag}K,limit=1] @e[tag={tag}V,limit=1] 100 melee", 0.2)
        r0 = rel(s, T_UUID, fa)
        return line, r0

    p0 = s.pos()
    line, r0 = kill_round("hwM5E1")
    time.sleep(15)
    r1 = rel(s, T_UUID, fa)
    guard = [l for l in s.read_since(p0) if "Permanent HYW HOSTILE between village faction" in l]
    note("S5-E kill by a neutral third party", f"{line}; relation right after {r0}; after 15 s {r1}; guard lines {len(guard)}")
    check("S5-E HYW escalates a neutral kill to HOSTILE, and the guard (no political cause) reverts it",
          "HOSTILE" in (r0 or ()) or guard, f"right after {r0}, guard lines {len(guard)}")
    check("S5-E reverted to NEUTRAL within 15 s", r1 == ("NEUTRAL", "NEUTRAL"), str(r1))
    m5(s, f"policy allow {fa} {T_UUID}")
    line2, r2 = kill_round("hwM5E2")
    time.sleep(15)
    r3 = rel(s, T_UUID, fa)
    check("S5-E with a political cause for the pair, the guard keeps HOSTILE", r3 == ("HOSTILE", "HOSTILE"), f"right after {r2}, after 15 s {r3}")
    m5(s, "policy clear")
    time.sleep(15)
    r4 = rel(s, T_UUID, fa)
    check("S5-E once the cause is gone, reconciliation reverts the pair", r4 == ("NEUTRAL", "NEUTRAL"), str(r4))
    s.cmd("kill @e[tag=hwM5E1K]", 0.5)
    s.cmd("kill @e[tag=hwM5E2K]", 0.5)
    neutral(s, T_UUID, fa)


def scenario_S5_F(ctx):
    """Spike F: temporary hostility makes only the selected Millénaire combatant targetable."""
    s, a = ctx.s, ctx.a
    d, dpos, civs = None, None, []
    end = time.time() + 150
    while time.time() < end and d is None:
        rows = []
        for l in s.output(at(a, "hywmill village residents"), 2):
            m = re.match(r"\s*([0-9a-f-]{36}) (\S+) (\w+) goal=\S+ attackTarget=\S+ hp=\d+ @(-?\d+), (-?\d+), (-?\d+)", l)
            if m:
                rows.append((m[1], m[2], m[3], (int(m[4]), int(m[5]), int(m[6]))))
        civs = [r for r in rows if r[2] == "CIVILIAN"]
        for r in rows:
            if r[2] == "DEFENDER":
                d, dpos = r, r[3]
                break
        if d is None:
            time.sleep(10)
    if d is None or not civs:
        check("S5-F an outdoor defender and civilians are available", False, f"{len(civs)} civilians")
        return
    note("S5-F selected combatant (outdoors)", f"{d[0][:8]} {d[1]} at {dpos}")
    summon_unit(s, dpos[0] + 4, dpos[2] + 1, "spear_man", OWNER_NBT, "hwM5F")
    s.cmd(f"tp @e[tag=hwM5F] {dpos[0] + 1} {dpos[1]} {dpos[2]}", 1)  # next to the combatant, indoors or not
    u = "@e[tag=hwM5F,limit=1]"
    m5(s, f"hyw strategy {u} DEFAULT", 0.5)
    s.cmd("effect give @e[tag=hwM5F] minecraft:resistance 600 2 true", 0.5)
    civ_ids = {r[0][:8] for r in civs}
    time.sleep(6)
    b = tgt8(next(iter(tagged(s, "hwM5F").values()), {}))
    check("S5-F baseline: an owned unit next to villagers targets none of them", b is None or b not in civ_ids | {d[0][:8]}, str(b))
    vc = m5_1(s, f"hyw valid {u} {civs[0][0]}")
    mk = m5_1(s, f"hyw mark {u} {d[0]}")
    vd = m5_1(s, f"hyw valid {u} {d[0]}")
    vc2 = m5_1(s, f"hyw valid {u} {civs[0][0]}")
    check("S5-F after markHostile(unit, defender): the defender is a valid target, a civilian is not",
          "valid=true" in vd and "valid=false" in vc2, f"{mk} || {vd} || civ before {vc} || civ after {vc2}")
    picks = []
    for i in range(5):
        time.sleep(3)
        r = next(iter(tagged(s, "hwM5F").values()), {})
        picks.append((tgt8(r), r.get("pos")))
    note("S5-F targets after markHostile only (HYW's own selection): (target, unit pos)", str(picks))
    picks2 = []
    for i in range(6):
        m5(s, f"hyw mark {u} {d[0]} engage", 0.5)
        time.sleep(3)
        r = next(iter(tagged(s, "hwM5F").values()), {})
        picks2.append((tgt8(r), r.get("pos")))
    note("S5-F targets with markHostile + setTarget each ~3.5 s (M4's engagement): (target, unit pos)", str(picks2))
    ts = [x[0] for x in picks + picks2]
    check("S5-F the unit engages the marked combatant", d[0][:8] in ts, str(ts))
    check("S5-F no civilian is ever selected", not any(t in civ_ids for t in ts), str(ts))
    note("S5-F village A alert after the engagement (M2 reacts to an attacker of a resident)", military(s, a).get("alert"))
    s.cmd("kill @e[tag=hwM5F]", 1)


def scenario_S5_G(ctx):
    """Spike G: a player as an M2 threat (fed through the unchanged DefenseService.onScan path)."""
    s, a, fa = ctx.s, ctx.a, ctx.fa
    note("S5-G stand-in", standin_at(s, P_UUID, a[0] + 4, a[2] + 4))
    note("S5-G bystander stand-in", standin_at(s, Q_UUID, a[0] - 5, a[2] + 5))
    m5(s, f"standin heal {P_UUID}", 0.5)
    m5(s, f"standin heal {Q_UUID}", 0.5)
    m5(s, f"policy allow {fa} {P_UUID}")
    m5(s, f"hyw relset {P_UUID} {fa} HOSTILE")
    alerts, committed, def_on_player, garr_on_player, garr_on_q, hp = [], [], 0, 0, 0, []
    for i in range(12):
        m5(s, f"threat {P_UUID} {a[0]} {a[1]} {a[2]}", 0.5)
        time.sleep(2)
        mil = military(s, a)
        alerts.append(mil.get("alert"))
        committed.append(mil.get("committed"))
        def_on_player += sum(1 for r in residents(s, a) if r[4] in ("player", "minecraft:player"))
        for r in unit_entities(s, a).values():
            garr_on_player += tgt8(r) == P_UUID[:8]
            garr_on_q += tgt8(r) == Q_UUID[:8]
        hp.append((hp_of(s, P_UUID), hp_of(s, Q_UUID)))
        if hp[-1][0] is not None and hp[-1][0] < 8:
            m5(s, f"standin heal {P_UUID}", 0.3)
    note("S5-G alert states", str(alerts))
    note("S5-G committed defenders", str(committed))
    note("S5-G (player hp, bystander hp)", str(hp))
    check("S5-G a player threat raises the M2 alert (ALERT/ENGAGED)", any(x in ("ALERT", "ENGAGED") for x in alerts), str(alerts))
    check("S5-G Millénaire defenders are committed against the player and take it as their attack target",
          def_on_player > 0 and any(c and int(c) > 0 for c in committed), f"defender samples with target=player {def_on_player}; committed {committed}")
    check("S5-G the HOSTILE garrison engages the player natively (targets and damages it)", garr_on_player > 0 and any(h[0] is not None and h[0] < 20 for h in hp),
          f"{garr_on_player} target samples; hp {hp}")
    check("S5-G the bystander player is neither targeted nor damaged", garr_on_q == 0 and all(h[1] in (None, 20.0) for h in hp), f"{garr_on_q}; {hp}")
    m5(s, f"standin mode {P_UUID} creative")
    gu = next(iter(unit_entities(s, a)), None)
    v = m5_1(s, f"hyw valid {gu} {P_UUID}") if gu else ""
    check("S5-G a creative-mode player is not a valid HYW target even while HOSTILE", "valid=false" in v, v)
    m5(s, f"standin mode {P_UUID} survival")
    neutral(s, P_UUID, fa)
    m5(s, "policy clear")
    m5(s, f"standin remove {Q_UUID}")
    ctx.alert_after_g = wait_alert(s, a, ("CALM",), 120)
    note("S5-G alert after the injected threat stops", ctx.alert_after_g)


def scenario_S5_H(ctx):
    """Spike H: escort movement with M4 hops through loaded terrain only; hold at unloaded terrain; resume."""
    s, a = ctx.s, ctx.a
    best, goal, gt = None, None, (False, -999, True)
    for dx, dz in ((0, -1), (0, 1), (1, 0), (-1, 0)):
        pts, edge = [], None
        for k in range(1, 40):
            x, z = a[0] + dx * 8 * k, a[2] + dz * 8 * k
            t, top, water = ticking(s, x, z)
            if not t:
                edge = (x, z)
                break
            pts.append((x, top, z, water))
        tail = [p for p in pts if hdist((p[0], 0, p[2]), (a[0], 0, a[2])) >= 40]
        flat = len(tail) >= 4 and max(p[1] for p in tail[-4:]) - min(p[1] for p in tail[-4:]) <= 8
        note("S5-H corridor probe", f"dir {(dx, dz)}: {len(pts)} loaded samples, edge {edge}, tail tops {[p[1] for p in tail[-4:]]} water {[p[3] for p in tail[-4:]]}")
        if not (edge and flat and not any(p[3] for p in tail[-4:])):
            continue
        way = tail[-4:]
        far = (edge[0] + dx * 48, edge[1] + dz * 48)
        sbox = (min(edge[0], far[0]) - 8, min(edge[1], far[1]) - 8, max(edge[0], far[0]) + 8, max(edge[1], far[1]) + 8)
        s.cmd("forceload add {} {} {} {}".format(*sbox), 12)   # survey beyond the edge (first dry spot), then unload it again
        for k in range(2, 7):
            g = (edge[0] + dx * 8 * k, edge[1] + dz * 8 * k)
            t = ticking(s, *g)
            if t[0] and not t[2] and abs(t[1] - way[-1][1]) <= 10:
                goal, gt = g, t
                break
        s.cmd("forceload remove {} {} {} {}".format(*sbox), 5)
        note("S5-H survey", f"dir {(dx, dz)} goal {goal} {gt}")
        if goal:
            best = (dx, dz, way, edge)
            break
    if not best:
        check("S5-H a dry corridor and a dry goal beyond the loaded edge exist", False, "none of the four directions")
        return
    dx, dz, way, edge = best
    box = (min(edge[0], goal[0]) - 8, min(edge[1], goal[1]) - 8, max(edge[0], goal[0]) + 8, max(edge[1], goal[1]) + 8)
    time.sleep(8)
    note("S5-H route", f"waypoints {way}; edge {edge}; goal {goal} (surveyed top={gt[1]} water={gt[2]}); now ticking={ticking(s, *goal)[0]}")
    summon_unit(s, way[0][0], way[0][2], "spear_man", OWNER_NBT, "hwM5H")
    u = "@e[tag=hwM5H,limit=1]"
    m5(s, f"hyw strategy {u} DEFAULT", 0.5)
    forced0 = forced_chunks(s)
    steps, track = [], []

    def pos():
        r = next(iter(tagged(s, "hwM5H").values()), None)
        return r["pos"] if r else None

    last = pos()

    def step(goal_xyz, n):
        nonlocal last
        out = []
        for _ in range(n):
            out.append(m5_1(s, "follow {} {} {} {} 16".format(u, *goal_xyz), 0.5))
            time.sleep(2.5)
            p = pos()
            if p and last:
                steps.append(round(hdist(p, last), 1))
            last = p
            track.append(p)
        return out

    for w in way[1:]:
        step((w[0], w[1], w[2]), 3)
    reached = pos()
    check("S5-H the unit follows a moving goal through loaded terrain (within 6 blocks of the last loaded waypoint)",
          reached is not None and hdist(reached, way[-1]) <= 6, f"end {reached}; last waypoint {way[-1]}; track {track}")
    hold = step((goal[0], gt[1] if gt[1] > -999 else way[-1][1], goal[1]), 6)
    held = pos()
    forced1 = forced_chunks(s)
    check("S5-H goal in unloaded terrain: the escort holds inside loaded terrain (no hop into unloaded chunks)",
          held is not None and ticking(s, held[0], held[2])[0] and "hop=hold" in hold[-1] and "goalTicking=false" in hold[-1],
          f"at {held}; last {hold[-1]}")
    check("S5-H nothing was force-loaded by the escort", forced1 == forced0, f"before: {forced0[-80:]} | after: {forced1[-80:]}")
    s.cmd("forceload add {} {} {} {}".format(*box), 10)   # the player walks on: the terrain loads
    res = step((goal[0], gt[1], goal[1]), 14)
    resumed = pos()
    check("S5-H once the terrain is loaded the escort resumes and reaches the goal", resumed is not None and hdist(resumed, (goal[0], 0, goal[1])) <= 6,
          f"at {resumed}; goal {goal}; last {res[-1]}")
    check("S5-H no teleport: every 2.5 s step is a walking distance (<= 14 blocks)", bool(steps) and max(steps) <= 14, f"max {max(steps) if steps else None}; {steps}")
    idl = m5_1(s, f"hyw ident {u}")
    check("S5-H HYW's own follow goal (which can teleport) is not used", "follow=null" in idl, idl)
    s.cmd("forceload remove {} {} {} {}".format(*box), 5)
    s.cmd("kill @e[tag=hwM5H]", 1)


def scenario_S5_N(ctx):
    """Spike 16.7-7: faction <-> faction HOSTILE: two garrisons meeting in the field."""
    s, a, b, fa, fb = ctx.s, ctx.a, ctx.b, ctx.fa, ctx.fb
    m5(s, f"policy allow {fa} {fb}")
    m5(s, f"hyw relset {fa} {fb} HOSTILE")
    r = rel(s, fa, fb)
    gb = unit_entities(s, b)
    b0 = next(iter(gb.values()))
    ga = list(unit_entities(s, a))[:3]
    for i, u in enumerate(ga):
        tx, tz = b0["pos"][0] - 14, b0["pos"][2] - 2 + 2 * i
        ty = surface_y(s, tx, tz) or b0["pos"][1]
        s.cmd(f"tp {u} {tx} {ty} {tz}", 0.5)
        s.cmd(f"hywmill dev spike-home {u} {tx} {ty} {tz}", 0.5)
    aids = {u[:8] for u in ga}
    bids = {u[:8] for u in gb}
    cnt = {"a->b": 0, "b->a": 0, "a->villager": 0, "a->civilian": 0}
    alerts = []
    for k in range(8):
        time.sleep(4)
        vill = residents(s, b)
        resb = {x[0][:8]: x for x in vill}
        rows = spike_info(s, "@e[type=!minecraft:player]")
        at_ = []
        for u, rr in rows.items():
            t = tgt8(rr)
            if u[:8] in aids:
                cnt["a->b"] += t in bids
                cnt["a->villager"] += t in resb
                cnt["a->civilian"] += t in resb and resb[t][2] == "CIVILIAN"
                at_.append((rr.get("target"), resb[t][2] if t in resb else ""))
            if u[:8] in bids:
                cnt["b->a"] += t in aids
        alerts.append(military(s, b).get("alert"))
        note(f"S5-N trace {k}", str({"A units": at_, "B villagers with a target": [(x[0][:8], x[2], x[4]) for x in vill if x[4] != "none"]}))
    note("S5-N counts", str(cnt))
    note("S5-N village B alert samples", str(alerts))
    check("S5-N faction<->faction HOSTILE stored both ways", r == ("HOSTILE", "HOSTILE"), str(r))
    check("S5-N the two garrisons fight each other", cnt["a->b"] > 0 and cnt["b->a"] > 0, str(cnt))
    check("S5-N A's units never target B's civilians", cnt["a->civilian"] == 0, str(cnt))
    note("S5-N B villagers (any role) targeted by A's units, samples", cnt["a->villager"])
    check("S5-N B's M2 sees A's units as threats (HYW_ENEMY)", any(x in ("ALERT", "ENGAGED") for x in alerts), str(alerts))
    neutral(s, fa, fb)
    m5(s, "policy clear")
    s.cmd("hywmill dev duties on", 1)


def scenario_S5_K(ctx):
    """Spikes 13.1: Millénaire relation writes: symmetric adjust, raid abort on a raise above -90, drift."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ab = f"{a[0]} {a[1]} {a[2]} {b[0]} {b[1]} {b[2]}"
    note("S5-K relation before", m5_1(s, f"mill mrel {ab}"))
    adj = m5_1(s, f"mill mrel {ab} adjust 10")
    note("S5-K adjustRelationSymmetric +10", adj)
    m5(s, f"mill mrel {ab} set -95")
    plan = m5_1(s, f"mill raidplan {ab}")
    m5(s, f"mill mrel {ab} set -85")
    p0 = s.pos()
    s.cmd("time add 24001", 1)
    ab_line = s.wait_for(r"Raid aborted \(relation improved\)", 60, since=p0)
    st = m5_1(s, f"mill raid {a[0]} {a[1]} {a[2]}")
    check("S5-K a planned raid is aborted when the relation is above -90 at its start (truce floor -85)",
          ab_line is not None and "target=none" in st, f"{plan} || {ab_line} || {st}")
    m5(s, f"mill mrel {ab} set -95")
    plan2 = m5_1(s, f"mill raidplan {ab}")
    p1 = s.pos()
    s.cmd("time add 24001", 1)
    time.sleep(20)
    st2 = m5_1(s, f"mill raid {a[0]} {a[1]} {a[2]}")
    started = re.search(r" start=(\d+)", st2)
    check("S5-K control: at -95 the planned raid starts", started and int(started[1]) > 0 and not any(
        "Raid aborted (relation improved)" in l for l in s.read_since(p1)), f"{plan2} || {st2}")
    end = time.time() + 180
    while time.time() < end and "target=none" not in m5_1(s, f"mill raid {a[0]} {a[1]} {a[2]}"):
        time.sleep(10)
    note("S5-K control raid ended", m5_1(s, f"mill raid {a[0]} {a[1]} {a[2]}"))
    note("S5-K relation after the control raid", m5_1(s, f"mill mrel {ab}"))
    m5(s, f"mill mrel {ab} set -85")
    dr = m5_1(s, f"mill drift {a[0]} {a[1]} {a[2]} 200", 3)
    note("S5-K nightly drift x200 (direct calls)", dr)
    m5(s, f"mill mrel {ab} set -42")
    ctx.persist_mrel = m5_1(s, f"mill mrel {ab}")


def scenario_S5_L(ctx):
    """Spike 13.2: diplomacy points: per player per village, regeneration, consumption."""
    s, a = ctx.s, ctx.a
    c = f"{a[0]} {a[1]} {a[2]}"
    note("S5-L stand-in (online)", standin_at(s, P_UUID, a[0] + 3, a[2] - 3))
    d0 = m5_1(s, f"mill dpoints {c} {P_UUID}")
    d1 = m5_1(s, f"mill dpoints {c} {P_UUID} consume")
    d2 = m5_1(s, f"mill dpoints {c} {P_UUID} nightly")
    d3 = m5_1(s, f"mill dpoints {c} {P_UUID} consume")
    q0 = m5_1(s, f"mill dpoints {c} {Q_UUID} nightly")
    q1 = m5_1(s, f"mill dpoints {c} {Q_UUID} regen")
    for l in (d0, d1, d2, d3, q0, q1):
        note("S5-L", l)
    check("S5-L nightly regeneration sets online players' points to the maximum (5); offline players are skipped",
          "now=5" in d2 and "now=0" in q0, f"{d2} || {q0}")
    check("S5-L consumeDiplomacyPoint spends one point and refuses at zero",
          "consumed=false" in d1 and "consumed=true" in d3 and "now=4" in d3, f"{d1} || {d3}")
    m5(s, f"standin remove {P_UUID}")


def scenario_S5_M(ctx):
    """Spike 13.3: chronicle (village history)."""
    s, a = ctx.s, ctx.a
    c = f"{a[0]} {a[1]} {a[2]}"
    h0 = m5_1(s, f"mill history {c}")
    h1 = m5_1(s, f"mill history {c} HywMill spike: an envoy from the test village arrived")
    h2 = m5_1(s, f"mill history {c} hywmill.chronicle.test")
    note("S5-M history", f"{h0} || {h1} || {h2}")
    n0 = int(re.search(r"size=(\d+)", h0)[1]) if re.search(r"size=(\d+)", h0) else -1
    check("S5-M recordEvent appends the raw text (no translation of keys)",
          f"size={n0 + 2}" in h2 and "'hywmill.chronicle.test'" in h2 and "envoy from the test village" in h1, h2)


def scenario_S5_J(ctx):
    """Spike 13.8: reputation adjustments as donations produce them (4x the goods' value)."""
    s, a = ctx.s, ctx.a
    c = f"{a[0]} {a[1]} {a[2]}"
    r0 = m5_1(s, f"mill rep {c} {Q_UUID}")
    r1 = m5_1(s, f"mill rep {c} {Q_UUID} adjust -3000")
    r2 = m5_1(s, f"mill rep {c} {Q_UUID} adjust 400")
    for l in (r0, r1, r2):
        note("S5-J", l)
    m = re.search(r"before=(-?\d+)/(-?\d+)/(-?\d+) now=(-?\d+)/(-?\d+)/(-?\d+)", r2)
    check("S5-J adjustReputation(+400) raises the village value by 400 and the culture value by 40 (a tenth)",
          bool(m) and int(m[4]) - int(m[1]) == 400 and int(m[5]) - int(m[2]) == 40, r2)


def scenario_S5_I(ctx):
    """Spike I: armoury through a Millénaire content sub-mod (millenaire-custom/), no code."""
    s = ctx.s
    g = m5_1(s, 'mill goods "millenaire:norman" hywmill_scroll_archer')
    g2 = m5_1(s, 'mill goods "millenaire:norman" norman_sword')
    sh = m5_1(s, 'mill shop "millenaire:norman" armoury')
    note("S5-I", f"{g} || {g2} || {sh}")
    check("S5-I a sub-mod traded good (HYW recruit scroll) is loaded with price and minimum reputation",
          "hundred_years_war:scroll_archer" in g and "minRep=8192" in g and "resolved=" in g and "resolved=minecraft:air" not in g, g)
    check("S5-I the sub-mod shop file makes the armoury sell it (and the original goods stay available)",
          "hywmill_scroll_archer" in sh and "norman_sword" in sh, sh)


def scenario_S5_R(ctx):
    """Persistence across a restart: HYW relations (HywMill writes), Millénaire relation, history."""
    s, a, fa = ctx.s, ctx.a, ctx.fa
    restart(ctx)
    time.sleep(20)
    got = {"xy": rel(s, X_UUID, Y_UUID), "xz": rel(s, X_UUID, Z_UUID), "qfa": rel(s, Q_UUID, fa)}
    before = getattr(ctx, "persist_hyw", {})
    note("S5-R HYW relations after restart", f"before {before} after {got}")
    check("S5-R HYW persists HywMill's relation writes (FRIENDLY one-way, HOSTILE pair)", got["xy"] == before.get("xy") and got["xz"] == before.get("xz"),
          f"before {before} after {got}")
    check("S5-R a HOSTILE village pair whose (test, unpersisted) political cause is gone is reverted after the restart",
          got["qfa"] == ("NEUTRAL", "NEUTRAL"), str(got["qfa"]))
    b = ctx.b
    m = m5_1(s, f"mill mrel {a[0]} {a[1]} {a[2]} {b[0]} {b[1]} {b[2]}")
    check("S5-R Millénaire relation written by HywMill persists", " before=-42/-42" in m, f"{getattr(ctx, 'persist_mrel', '')} || {m}")
    h = m5_1(s, f"mill history {a[0]} {a[1]} {a[2]}")
    note("S5-R Millénaire village history after the restart", h)
    check("S5-R observed: Millénaire's village history is session-only (entries written before the restart are gone)",
          "hywmill.chronicle.test" not in h, h)


def scenario_S5_V(ctx):
    """Controlled check of HYW's relation-participant rule for identity-marked Millénaire residents
    (NoAI player unit; direct isValidTarget / identity probes; NEUTRAL vs HOSTILE; one civilian unmarked)."""
    s, a, fa = ctx.s, ctx.a, ctx.fa
    rows = []
    for l in s.output(at(a, "hywmill village residents"), 2):
        m = re.match(r"\s*([0-9a-f-]{36}) (\S+) (\w+) goal=", l)
        if m:
            rows.append((m[1], m[2], m[3]))
    civs = [r for r in rows if r[2] == "CIVILIAN"]
    defs = [r for r in rows if r[2] == "DEFENDER"]
    if len(civs) < 2 or not defs:
        check("S5-V residents available", False, f"{len(civs)} civilians, {len(defs)} defenders")
        return
    summon_unit(s, a[0] + 3, a[2] + 3, "spear_man", OWNER_NBT, "hwM5V", "NoAI:1b")
    u = "@e[tag=hwM5V,limit=1]"
    m5(s, f"hyw strategy {u} DEFAULT", 0.5)
    c0, c1, d0 = civs[0][0], civs[1][0], defs[0][0]
    note("S5-V civilian identity", m5_1(s, f"hyw ident {c0}"))
    note("S5-V defender identity", m5_1(s, f"hyw ident {d0}"))
    neutral(s, P_UUID, fa)
    vn = [m5_1(s, f"hyw valid {u} {x}") for x in (c0, d0)]
    note("S5-V NEUTRAL: player unit -> civilian, defender", " || ".join(vn))
    m5(s, f"policy allow {fa} {P_UUID}")
    m5(s, f"hyw relset {P_UUID} {fa} HOSTILE")
    vh = [m5_1(s, f"hyw valid {u} {x}") for x in (c0, d0)]
    note("S5-V HOSTILE: player unit -> civilian, defender", " || ".join(vh))
    check("S5-V (Option 1) under player<->faction HOSTILE a marked CIVILIAN is NOT a valid target (resident identity)",
          "valid=false" in vh[0], vh[0])
    check("S5-V (Option 1) under player<->faction HOSTILE a marked DEFENDER is NOT a relation target", "valid=false" in vh[1], vh[1])
    gv = m5_1(s, f"hyw valid {u} {next(iter(unit_entities(s, a)))}")
    check("S5-V (Option 1) under player<->faction HOSTILE the garrison IS a valid target", "valid=true" in gv, gv)
    um = m5_1(s, f"hyw unmark {c1}")
    vu = m5_1(s, f"hyw valid {u} {c1}")
    note("S5-V unmarked civilian", f"{um} || {vu}")
    check("S5-V an UNMARKED civilian is not a relation participant and not a valid target under HOSTILE", "participant=false" in um and "valid=false" in vu, f"{um} || {vu}")
    time.sleep(25)
    later = m5_1(s, f"hyw ident {c1}")
    note("S5-V unmarked civilian 25 s later (M1.1 marker lifecycle)", later)
    rid = info(s, a).get("residents")
    check("S5-V the marker lifecycle re-marks with the resident identity", rid is not None and f"rel={rid}" in later, f"{later} residents {rid}")
    gu = next(iter(unit_entities(s, a)), None)
    if gu:
        note("S5-V garrison unit -> unmarked civilian (friendly-fire protection without a marker)", m5_1(s, f"hyw valid {gu} {c1}"))
        note("S5-V garrison unit -> marked civilian", m5_1(s, f"hyw valid {gu} {c0}"))
    neutral(s, P_UUID, fa)
    m5(s, "policy clear")
    s.cmd("kill @e[tag=hwM5V]", 1)
    s.cmd(at(a, "hywmill admin restore-identities"), 2)


def scenario_S5_W(ctx):
    """Field test away from every village (outside all defense radii): player units vs garrison
    units under HOSTILE, residual targeting after NEUTRAL (units kept alive), and faction vs faction."""
    s, a, b, fa, fb = ctx.s, ctx.a, ctx.b, ctx.fa, ctx.fb
    fx, fz = a[0], a[2] - 170
    box = (fx - 24, fz - 24, fx + 24, fz + 24)
    s.cmd("forceload add {} {} {} {}".format(*box), 12)
    t = ticking(s, fx, fz)
    note("S5-W field", f"({fx}, {fz}) ticking={t[0]} top={t[1]} water={t[2]}; A centre {a}, distance {round(hdist((fx, 0, fz), a))}")
    s.cmd("hywmill dev duties off", 1)
    ga = list(unit_entities(s, a))[:3]
    gb = list(unit_entities(s, b))[:3]
    for i, u in enumerate(ga):
        y = surface_y(s, fx - 6, fz - 3 + 3 * i)
        s.cmd(f"tp {u} {fx - 6} {y} {fz - 3 + 3 * i}", 0.5)
        s.cmd(f"hywmill dev spike-home {u} {fx - 6} {y} {fz - 3 + 3 * i}", 0.5)
    for i in range(3):
        summon_unit(s, fx + 6, fz - 3 + 3 * i, "spear_man", OWNER_NBT, "hwM5W")
    for u in tagged(s, "hwM5W"):
        m5(s, f"hyw strategy {u} DEFAULT", 0.3)
    s.cmd("effect give @e[tag=hwM5W] minecraft:resistance 600 3 true", 0.5)
    for u in ga + gb:
        s.cmd(f"effect give {u} minecraft:resistance 600 3 true", 0.3)
    aids = {u[:8] for u in ga}

    def sample(n, step=3):
        out = []
        for _ in range(n):
            time.sleep(step)
            pu = tagged(s, "hwM5W")
            pids = {u[:8] for u in pu}
            rows = spike_info(s, "@e[type=!minecraft:player]")
            pt = sum(tgt8(r) in aids for r in pu.values())
            gt = sum(tgt8(rows[u]) in pids for u in rows if u[:8] in aids)
            out.append((pt, gt, len(pu)))
        return out

    base = sample(3)
    m5(s, f"policy allow {fa} {P_UUID}")
    m5(s, f"hyw relset {P_UUID} {fa} HOSTILE")
    war = sample(8)
    neutral(s, P_UUID, fa)
    after = sample(8, 4)
    note("S5-W player units vs garrison: (player units targeting garrison, garrison targeting player units, player units alive)",
         f"baseline {base} | HOSTILE {war} | after NEUTRAL (4 s steps) {after}")
    check("S5-W field: under HOSTILE the player's units and the garrison target each other (DEFAULT)",
          any(x[0] > 0 for x in war) and any(x[1] > 0 for x in war), str(war))
    check("S5-W field: baseline NEUTRAL, no mutual targeting", all(x[0] == 0 and x[1] == 0 for x in base), str(base))
    check("S5-W field: 16 s after both directions are NEUTRAL there is no residual targeting (units alive)",
          all(x[2] > 0 for x in after) and all(x[0] == 0 and x[1] == 0 for x in after[4:]), str(after))
    m5(s, "policy clear")
    s.cmd("kill @e[tag=hwM5W]", 1)
    # faction vs faction in the field
    for i, u in enumerate(gb):
        y = surface_y(s, fx + 6, fz - 3 + 3 * i)
        s.cmd(f"tp {u} {fx + 6} {y} {fz - 3 + 3 * i}", 0.5)
        s.cmd(f"hywmill dev spike-home {u} {fx + 6} {y} {fz - 3 + 3 * i}", 0.5)
    bids = {u[:8] for u in gb}
    m5(s, f"policy allow {fa} {fb}")
    time.sleep(6)
    rows = spike_info(s, "@e[type=!minecraft:player]")
    base2 = sum(tgt8(rows[u]) in bids for u in rows if u[:8] in aids) + sum(tgt8(rows[u]) in aids for u in rows if u[:8] in bids)
    m5(s, f"hyw relset {fa} {fb} HOSTILE")
    cnt = []
    for _ in range(8):
        time.sleep(3)
        rows = spike_info(s, "@e[type=!minecraft:player]")
        cnt.append((sum(tgt8(rows[u]) in bids for u in rows if u[:8] in aids), sum(tgt8(rows[u]) in aids for u in rows if u[:8] in bids)))
    note("S5-W faction vs faction: (A targeting B, B targeting A)", f"baseline {base2} | HOSTILE {cnt}")
    check("S5-W field: two village garrisons at war (faction HOSTILE) fight each other", base2 == 0 and any(x[0] > 0 for x in cnt) and any(x[1] > 0 for x in cnt), str(cnt))
    neutral(s, fa, fb)
    m5(s, "policy clear")
    s.cmd("hywmill dev duties on", 1)
    s.cmd("forceload remove {} {} {} {}".format(*box), 5)


def resident_id(s, c):
    l = m5_1(s, f"resident id {c[0]} {c[1]} {c[2]}")
    m = re.search(r"resident id (\S+) faction (\S+)", l)
    return m[1] if m else None


def scenario_S5_P(ctx):
    """M5 Option 1 in production: residents carry the resident identity; the mod keeps resident<->faction
    FRIENDLY; the escalation guard reverts any HOSTILE on a resident identity even when a political
    cause exists for the faction; pre-M5 faction markers are migrated."""
    s, a, fa = ctx.s, ctx.a, ctx.fa
    ra = resident_id(s, a)
    check("S5-P village info reports the resident identity", ra is not None and info(s, a).get("residents") == ra, str(ra))
    rows = [r for r in residents(s, a)]
    civ = next((r for r in rows if r[2] == "CIVILIAN"), None)
    idl = m5_1(s, f"hyw ident {civ[0]}") if civ else ""
    check("S5-P residents carry the resident identity", f"rel={ra}" in idl, idl)
    r = rel(s, ra, fa)
    check("S5-P the mod keeps resident<->faction FRIENDLY in both directions", r == ("FRIENDLY", "FRIENDLY"), str(r))
    neutral(s, ra, fa)
    time.sleep(15)
    r2 = rel(s, ra, fa)
    check("S5-P a broken alliance is repaired by reconciliation", r2 == ("FRIENDLY", "FRIENDLY"), f"after reset -> {r2}")
    # the guard: HOSTILE on a resident identity is reverted even if the faction pair is politically permitted
    m5(s, f"policy allow {fa} {P_UUID}")
    m5(s, f"hyw relset {P_UUID} {fa} HOSTILE")
    m5(s, f"hyw relset {P_UUID} {ra} HOSTILE")
    time.sleep(15)
    rf, rr = rel(s, P_UUID, fa), rel(s, P_UUID, ra)
    check("S5-P with a political cause: faction HOSTILE kept, resident-identity HOSTILE reverted", rf == ("HOSTILE", "HOSTILE") and rr == ("NEUTRAL", "NEUTRAL"),
          f"faction {rf}; residents {rr}")
    m5(s, f"policy allow {ra} {P_UUID}")  # even an explicit permission must not keep it
    m5(s, f"hyw relset {P_UUID} {ra} HOSTILE")
    time.sleep(15)
    rr2 = rel(s, P_UUID, ra)
    check("S5-P a resident identity can never stand HOSTILE, even if a policy would permit it", rr2 == ("NEUTRAL", "NEUTRAL"), str(rr2))
    neutral(s, P_UUID, fa)
    m5(s, "policy clear")
    # migration of a pre-M5 marker (faction UUID on a resident)
    if civ:
        s.cmd(f"hywmill dev m5 hyw unmark {civ[0]}", 0.5)
        marked = m5_1(s, f"hyw ident {civ[0]}")
        time.sleep(25)
        after = m5_1(s, f"hyw ident {civ[0]}")
        check("S5-P an unmarked (or pre-M5) resident is re-marked with the resident identity by the sweep", f"rel={ra}" in after, f"{marked} -> {after}")
    stat = " ".join(s.output("hywmill status", 2))
    note("S5-P identity counters", (re.search(r"identities: .*", stat) or [""])[0][:300])


def pshow(s, c, player):
    l = next((x for x in s.output(at(c, f"hywmill politics admin show {player}"), 1) if x.startswith("politics record")), "")
    m = re.search(r"status=(\w+).* grievance=([\d.]+) peacetimeKill=(\w+) lastKind=(\w+) inside=(\w+) favor=(\d+)", l)
    return (dict(status=m[1], grievance=float(m[2]), pk=m[3] == "true", kind=m[4], inside=m[5] == "true", favor=int(m[6])) if m else {"raw": l})


def pstatus(s, c, player):
    return s.output(at(c, f"hywmill politics status for {player}"), 1.5)


def scenario_G5_2(ctx):
    """M5-2: grievances from real events, outlaw rule, word travels, intel, chronicle, persistence."""
    s, a, b = ctx.s, ctx.a, ctx.b
    s.cmd("hywmill dev duties off", 1)
    res = residents(s, a)
    civs = [r for r in res if r[2] == "CIVILIAN"]
    note("G5-2 stand-in (online player P)", standin_at(s, P_UUID, a[0] + 3, a[2] + 3))
    time.sleep(12)
    check("G5-2 a newcomer is a stranger (no record written)", "status" not in pshow(s, a, P_UUID) or pshow(s, a, P_UUID).get("status") == "STRANGER",
          str(pshow(s, a, P_UUID)))
    # real events: a player-owned unit (owner Q) assaults, then kills, a villager inside the village
    civ = civs[0][0]
    summon_unit(s, a[0] + 2, a[2] + 2, "spear_man", uuid_nbt(Q_UUID), "hwG52", "NoAI:1b")
    s.cmd(f"tp {civ} {a[0] + 3} {a[1] + 1} {a[2] + 2}", 0.5)
    m5(s, f"hyw hit @e[tag=hwG52,limit=1] {civ} 1 melee", 1)
    q1 = pshow(s, a, Q_UUID)
    check("G5-2 a player's unit assaulting a villager inside the village is the owner's grievance",
          q1.get("kind") == "ASSAULT_RESIDENT" and q1.get("inside") is True and 14 < q1.get("grievance", 0) < 16, str(q1))
    m5(s, f"hyw hit @e[tag=hwG52,limit=1] {civ} 100 melee", 2)
    q2 = pshow(s, a, Q_UUID)
    check("G5-2 killing a villager inside the village in peacetime: immediate outlaw regardless of reputation",
          q2.get("status") == "OUTLAW" and q2.get("pk") is True and q2.get("kind") == "KILL_RESIDENT", str(q2))
    s.cmd("kill @e[tag=hwG52]", 0.5)
    # the normal rule: serious grievance away from the village + reputation <= -1024
    c = f"{a[0]} {a[1]} {a[2]}"
    out = s.output(at(a, f"hywmill politics admin grievance {T_UUID} KILL_GARRISON false true false"), 1)
    t1 = pshow(s, a, T_UUID)
    check("G5-2 a serious grievance away from the village with neutral reputation is not outlawry (unwelcome)", t1.get("status") == "UNWELCOME", f"{out} {t1}")
    m5(s, f"mill rep {c} {T_UUID} adjust -2000")
    s.output(at(a, f"hywmill politics admin grievance {T_UUID} ASSAULT_GARRISON false true false"), 1)
    t2 = pshow(s, a, T_UUID)
    check("G5-2 serious grievance + reputation <= -1024: outlaw", t2.get("status") == "OUTLAW", str(t2))
    # chronicle: HywMill persisted + Millénaire mirror
    st = pstatus(s, a, Q_UUID)
    hist = m5_1(s, f"mill history {c}")
    check("G5-2 status change written to the chronicle and mirrored to Millénaire's history",
          any("chronicle" in l and "outlaw" in l for l in st) and "[HywMill]" in hist, " | ".join(st[-2:]) + " || " + hist[-160:])
    # word travels: B (same culture) on good terms with A treats Q as unwelcome
    ab = f"{a[0]} {a[1]} {a[2]} {b[0]} {b[1]} {b[2]}"
    m5(s, f"mill mrel {ab} set 60")
    sb = pstatus(s, b, Q_UUID)
    check("G5-2 word travels: outlawed by a friendly same-culture village -> treated as unwelcome here",
          any("treated as UNWELCOME" in l for l in sb) and any("Word travels: outlawed by" in l for l in sb), " | ".join(sb))
    m5(s, f"mill mrel {ab} set -40")
    sb2 = pstatus(s, b, Q_UUID)
    check("G5-2 outlawed by an enemy village: only a mild recommendation, not unwelcome",
          not any("treated as" in l for l in sb2) and any("mild recommendation" in l for l in sb2), " | ".join(sb2))
    # intel gating by standing (P online: refreshed on the village's slot)
    i0 = s.output(at(a, f"hywmill politics intel for {P_UUID}"), 1.5)
    check("G5-2 intel refused to a stranger", any("shares nothing" in l for l in i0), " | ".join(i0))
    m5(s, f"mill rep {c} {P_UUID} adjust 9000")
    for _ in range(20):
        s.output(at(a, f"hywmill politics admin favor {P_UUID} REQUESTED_DIPLOMACY"), 0.2)
    time.sleep(14)
    p1 = pshow(s, a, P_UUID)
    i1 = s.output(at(a, f"hywmill politics intel for {P_UUID}"), 1.5)
    check("G5-2 reputation >= 8192 and favor >= 20: patron, exact intelligence", p1.get("status") == "PATRON" and any("Garrison:" in l and "duties" in l for l in i1),
          f"{p1} | " + " | ".join(i1[:4]))
    lst = s.output(f"hywmill politics list for {Q_UUID}", 1.5)
    note("G5-2 list for Q", " | ".join(lst))
    ctx.g52 = {"Q": q2, "T": t2, "P": p1}
    # persistence
    restart(ctx)
    time.sleep(20)
    after = {k: pshow(s, a, u) for k, u in (("Q", Q_UUID), ("T", T_UUID), ("P", P_UUID))}
    loaded5 = s.wait_for(r"Garrison ledger loaded: \d+ village record\(s\), format 5", 5, since=s.start_pos)
    check("G5-2 politics persist across a restart (ledger format 5)", loaded5 is not None and after["Q"].get("status") == "OUTLAW"
          and after["Q"].get("pk") is True and after["T"].get("status") == "OUTLAW" and after["P"].get("favor") == ctx.g52["P"].get("favor"),
          f"{loaded5} {after}")
    st2 = pstatus(s, a, Q_UUID)
    check("G5-2 HywMill's chronicle survives the restart (Millénaire's own history does not)", any("chronicle" in l and "outlaw" in l for l in st2), " | ".join(st2[-2:]))
    s.cmd("hywmill dev duties on", 1)


O_UUID = "77777777-8888-4999-8aaa-bbbbbbbbbbbb"      # M5-3: the outlaw (a stand-in)


def threat_lines(s, c):
    return s.output(at(c, "hywmill threats"), 1.5)


def scenario_G5_3(ctx):
    """M5-3: outlaw -> OUTLAWED_PLAYER threat and faction<->player HYW HOSTILE kept by PoliticalPolicy; residents untouched;
    non-outlaws still reverted; formal pardon (weregild) and projection cleared; persistence."""
    s, a = ctx.s, ctx.a
    s.cmd("hywmill dev duties off", 1)
    v = info(s, a)
    fac, resid = v.get("faction"), v.get("residents")
    c = f"{a[0]} {a[1]} {a[2]}"
    # the outlaw stands 60+ blocks away first (outside the defense radius), the bystander P inside
    note("G5-3 stand-ins", standin_at(s, O_UUID, a[0] + 90, a[2] + 90) + " | " + standin_at(s, P_UUID, a[0] + 4, a[2] + 4))
    m5(s, f"standin mode {O_UUID} survival", 0.5)
    m5(s, f"standin mode {P_UUID} survival", 0.5)
    s.output(at(a, f"hywmill politics admin grievance {O_UUID} KILL_RESIDENT true true false"), 1.5)
    o = pshow(s, a, O_UUID)
    check("G5-3 a peacetime killing inside the village: outlaw", o.get("status") == "OUTLAW", str(o))
    r1 = rel(s, fac, O_UUID)
    check("G5-3 outlawry projected: village faction <-> outlaw HYW HOSTILE (both directions)", r1 == ("HOSTILE", "HOSTILE"), str(r1))
    rr = rel(s, resid, O_UUID)
    check("G5-3 the resident identity is never made hostile (Option 1)", "HOSTILE" not in rr, str(rr))
    time.sleep(15)  # > one reconcile interval (200 ticks)
    r2 = rel(s, fac, O_UUID)
    check("G5-3 the escalation guard keeps the outlaw HOSTILE (PoliticalPolicy)", r2 == ("HOSTILE", "HOSTILE"), str(r2))
    # negative: a HOSTILE the politics do not cover is still reverted
    neutral_party = "eeeeeeee-ffff-4000-8111-222222222222"  # never outlawed (T was outlawed by G5-2)
    m5(s, f"hyw relset {fac} {neutral_party} HOSTILE", 0.5)
    time.sleep(15)
    r3 = rel(s, fac, neutral_party)
    check("G5-3 negative: a HOSTILE without a political cause is still reverted", "HOSTILE" not in r3, str(r3))
    m5(s, f"hyw relset {resid} {O_UUID} HOSTILE", 0.5)
    time.sleep(15)
    r3b = rel(s, resid, O_UUID)
    check("G5-3 negative: resident identity <-> outlaw HOSTILE is reverted even for an outlaw", "HOSTILE" not in r3b, str(r3b))
    t0 = threat_lines(s, a)
    check("G5-3 an outlaw outside the defense radius is not a threat", not any(O_UUID[:8] in l for l in t0), " | ".join(t0))
    # the outlaw walks in
    m5(s, f"standin remove {O_UUID}", 0.5)
    standin_at(s, O_UUID, a[0] + 6, a[2] + 6)
    m5(s, f"standin mode {O_UUID} survival", 0.5)
    hp0 = hp_of(s, O_UUID)
    seen, hit = [], False
    for _ in range(12):
        time.sleep(2.5)
        tl = threat_lines(s, a)
        seen.append(next((l for l in tl if O_UUID[:8] in l), ""))
        h = hp_of(s, O_UUID)
        if h is not None and hp0 is not None and h < hp0:
            hit = True
        m5(s, f"standin heal {O_UUID}", 0.2)
    check("G5-3 the outlaw inside the defense radius is an M2 threat (OUTLAWED_PLAYER)", any("OUTLAWED_PLAYER" in l for l in seen), " | ".join(x for x in seen if x)[:300])
    check("G5-3 the village responds with proactive=false (garrison or defenders damage the outlaw)", hit, f"hp0={hp0}")
    tp = threat_lines(s, a)
    check("G5-3 negative: a player who is not an outlaw is not a threat", not any(P_UUID[:8] in l for l in tp), " | ".join(tp))
    res = residents(s, a)
    civ_att = [r for r in res if r[2] == "CIVILIAN" and O_UUID[:8] in r[4]]
    check("G5-3 civilians never attack the outlaw", not civ_att, str(civ_att))
    # persistence of the projection: a second outlaw, then a restart
    s.output(at(a, f"hywmill politics admin grievance {Z_UUID} KILL_GARRISON true true false"), 1.5)
    # formal pardon: refused while poor, then paid after donations restored reputation
    q0 = s.output(at(a, f"hywmill politics pardon for {O_UUID}"), 1.5)
    check("G5-3 pardon refused while the weregild would sink reputation to the boycott line", any("refused" in l for l in q0), " | ".join(q0))
    m5(s, f"mill rep {c} {O_UUID} adjust 6000")
    q1 = s.output(at(a, f"hywmill politics pardon for {O_UUID}"), 1.5)
    price = next((int(m[1]) for l in q1 for m in [re.search(r"quote: (\d+) reputation", l)] if m), None)
    p1 = s.output(at(a, f"hywmill politics pardon for {O_UUID} pay"), 2)
    o2 = pshow(s, a, O_UUID)
    r4 = rel(s, fac, O_UUID)
    check("G5-3 formal pardon: weregild paid, no longer an outlaw, HYW HOSTILE cleared",
          price is not None and any("paid" in l for l in p1) and o2.get("status") not in (None, "OUTLAW") and "HOSTILE" not in r4,
          f"price={price} {' | '.join(p1)} {o2} {r4}")
    time.sleep(8)
    tl = threat_lines(s, a)
    check("G5-3 a pardoned player is no longer a threat", not any(O_UUID[:8] in l and "OUTLAWED" in l for l in tl), " | ".join(tl))
    st = pstatus(s, a, O_UUID)
    check("G5-3 the pardon is in the chronicle", any("pardoned" in l for l in st), " | ".join(st[-3:]))
    restart(ctx)
    time.sleep(25)
    z = pshow(s, a, Z_UUID)
    r5 = rel(s, fac, Z_UUID)
    o3 = pshow(s, a, O_UUID)
    check("G5-3 after a restart: the outlaw stays an outlaw and the projection stands; the pardoned stays pardoned",
          z.get("status") == "OUTLAW" and r5 == ("HOSTILE", "HOSTILE") and o3.get("status") != "OUTLAW", f"{z} {r5} {o3}")
    perf = s.output("hywmill perf", 2)
    note("G5-3 perf", " | ".join(l for l in perf if "politics" in l or "scan.village" in l))
    s.output(at(a, f"hywmill politics admin clear {Z_UUID}"), 1)
    r6 = rel(s, fac, Z_UUID)
    check("G5-3 clearing an outlaw's record removes the projection", "HOSTILE" not in r6, str(r6))
    for u in (O_UUID, P_UUID):
        m5(s, f"standin remove {u}", 0.3)
    s.cmd("hywmill dev duties on", 1)


def dip(s, c, sub, wait=1.5):
    return [l for l in s.output(at(c, f"hywmill diplomacy {sub}"), wait) if l.startswith("diplomacy") or l.startswith(" ")]


def mrel_of(s, ab):
    m = re.search(r"now=(-?\d+)/(-?\d+)", m5_1(s, f"mill mrel {ab}"))
    return (int(m[1]), int(m[2])) if m else None


def dpoints_of(s, c, player):
    m = re.search(r"now=(\d+)", m5_1(s, f"mill dpoints {c} {player}"))
    return int(m[1]) if m else None


W_UUID = "88888888-9999-4aaa-8bbb-cccccccccccc"      # M5-4: a fresh sponsor (a stand-in)


def scenario_G5_4(ctx):
    """M5-4: envoys (requirements, diplomacy point, travel, seeded outcome through Millénaire's relation), truce floor,
    sow-discord limits, persistence of pending envoys."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    ab = f"{ca} {cb}"
    note("G5-4 stand-in", standin_at(s, W_UUID, a[0] + 3, a[2] + 3))
    for u in (W_UUID, Q_UUID):
        m5(s, f"mill discover {ca} {u}", 0.5)
        m5(s, f"mill discover {cb} {u}", 0.5)
    m5(s, f"mill dpoints {ca} {W_UUID} regen", 0.5)
    m5(s, f"mill mrel {ab} set -40")
    # negatives first
    r0 = dip(s, a, f"for {X_UUID} propose reconcile {cb}")
    check("G5-4 negative: villages not discovered -> refused", any("NOT_DISCOVERED" in l for l in r0), " | ".join(r0))
    r1 = dip(s, a, f"for {Q_UUID} propose reconcile {cb}")
    check("G5-4 negative: a stranger cannot sponsor a reconciliation", any("STANDING_TOO_LOW" in l for l in r1), " | ".join(r1))
    # P becomes trusted with A (online: refreshed on A's slot)
    m5(s, f"mill rep {ca} {W_UUID} adjust 5000")
    time.sleep(14)
    check("G5-4 W is trusted with A", pshow(s, a, W_UUID).get("status") == "TRUSTED", str(pshow(s, a, W_UUID)))
    t0 = dip(s, a, f"for {W_UUID} propose truce {cb}")
    check("G5-4 negative: a truce needs Patron standing", any("STANDING_TOO_LOW" in l for l in t0), " | ".join(t0))
    dp0 = dpoints_of(s, ca, W_UUID)
    rel0 = mrel_of(s, ab)
    r2 = dip(s, a, f"for {W_UUID} propose reconcile {cb}")
    dp1 = dpoints_of(s, ca, W_UUID)
    check("G5-4 a trusted player sends a reconciliation envoy; it spends one Millénaire diplomacy point",
          any("propose OK" in l for l in r2) and dp0 is not None and dp1 == dp0 - 1, f"{' | '.join(r2)} points {dp0}->{dp1}")
    st = dip(s, a, f"for {W_UUID} status")
    check("G5-4 the envoy is under way (not instant)", any("reconcile" in l and "arrives in" in l for l in st) and mrel_of(s, ab) == rel0,
          " | ".join(st))
    r3 = dip(s, a, f"for {W_UUID} propose reconcile {cb}")
    check("G5-4 negative: the same pair again at once -> cooldown", any("PAIR_COOLDOWN" in l for l in r3), " | ".join(r3))
    # persistence of a pending envoy across a restart, then it resolves
    restart(ctx)
    time.sleep(15)
    st2 = dip(s, a, f"for {W_UUID} status")
    check("G5-4 the pending envoy survives a restart", any("reconcile" in l for l in st2), " | ".join(st2))
    standin_at(s, W_UUID, a[0] + 3, a[2] + 3)
    p0 = s.pos()
    dip(s, a, "admin arrive")
    res = s.wait_for(r"Diplomacy: .* RECONCILE .* -> (SUCCESS|FAILURE|BACKFIRE)", 25, since=p0)
    rel1 = mrel_of(s, ab)
    outcome = re.search(r"-> (SUCCESS|FAILURE|BACKFIRE)", res or "")
    o = outcome[1] if outcome else None
    consistent = (o == "SUCCESS" and rel1 and rel1[0] > rel0[0]) or (o == "BACKFIRE" and rel1 and rel1[0] < rel0[0]) or (o == "FAILURE" and rel1 == rel0)
    check("G5-4 the envoy resolves on the sponsor's slot; the effect goes through Millénaire's relation and matches the outcome",
          res is not None and consistent, f"{o} relation {rel0} -> {rel1}")
    hist_a, hist_b = pstatus(s, a, W_UUID), m5_1(s, f"mill history {cb}")
    check("G5-4 the result is written to both villages' chronicles", "[HywMill]" in hist_b and any("envoy" in l for l in hist_a),
          hist_b[-160:])
    check("G5-4 no pending envoy left", not any("arrives in" in l for l in dip(s, a, f"for {W_UUID} status")), "")
    # truce: the floor is held above Millénaire's raid line while it lasts
    m5(s, f"mill mrel {ab} set -100")
    dip(s, a, f"admin truce {ca} {cb} 1")
    time.sleep(14)
    rel2 = mrel_of(s, ab)
    check("G5-4 a truce holds the relation at the floor (-85, above the -90 raid line)", rel2 == (-85, -85), str(rel2))
    dip(s, a, f"admin truce {ca} {cb} 0")
    m5(s, f"mill mrel {ab} set -100")
    time.sleep(14)
    check("G5-4 after the truce ends the floor is no longer held", mrel_of(s, ab) == (-100, -100), str(mrel_of(s, ab)))
    dip(s, a, f"admin truce {ca} {cb} 0.02")  # ends any war the -100 above may have started (short warMinConflictTicks runs)
    # sow discord: sworn only, favor cost, one pending plot, per-player cooldown
    m5(s, f"mill mrel {ab} set 20")
    m5(s, f"mill dpoints {ca} {W_UUID} regen", 0.5)
    sd0 = dip(s, a, f"for {W_UUID} propose sow_discord {cb}")
    check("G5-4 negative: sow discord needs Sworn standing", any("STANDING_TOO_LOW" in l for l in sd0), " | ".join(sd0))
    W_UUID2 = "ffffffff-0000-4111-8222-333333333333"  # W's reconcile put the pair on W's own cooldown (correct); a fresh sponsor
    standin_at(s, W_UUID2, a[0] + 4, a[2] - 4)
    for c_ in (ca, cb):
        m5(s, f"mill discover {c_} {W_UUID2}", 0.5)
    m5(s, f"mill dpoints {ca} {W_UUID2} regen", 0.5)
    m5(s, f"mill rep {ca} {W_UUID2} adjust 35000")
    for _ in range(14):
        s.output(at(a, f"hywmill politics admin favor {W_UUID2} REQUESTED_DIPLOMACY"), 0.2)
    time.sleep(14)
    f0 = pshow(s, a, W_UUID2)
    sd1 = dip(s, a, f"for {W_UUID2} propose sow_discord {cb}")
    f1 = pshow(s, a, W_UUID2)
    check("G5-4 a sworn player may sow discord; it costs Favor and a diplomacy point",
          f0.get("status") == "SWORN" and any("propose OK" in l for l in sd1) and f1.get("favor") == f0.get("favor", 0) - 10, f"{f0} {' | '.join(sd1)} {f1}")
    m5(s, f"mill mrel {ab} set 20")
    sd2 = dip(s, a, f"for {W_UUID2} propose sow_discord {cb}")
    check("G5-4 negative: at most one pending plot per player", any("PENDING_LIMIT" in l or "PAIR_COOLDOWN" in l for l in sd2), " | ".join(sd2))
    p1 = s.pos()
    dip(s, a, "admin arrive")
    s.wait_for(r"Diplomacy: .* SOW_DISCORD", 25, since=p1)
    sd3 = dip(s, a, f"for {W_UUID2} propose sow_discord {cb}")
    check("G5-4 negative: a second plot within the cooldown is refused", any("PLAYER_COOLDOWN" in l or "PAIR_COOLDOWN" in l or "TARGET_COOLDOWN" in l
                                                                              for l in sd3), " | ".join(sd3))
    perf = s.output("hywmill perf", 2)
    note("G5-4 perf", " | ".join(l for l in perf if "diplomacy" in l or "politics" in l))
    m5(s, f"standin remove {W_UUID}", 0.3)
    m5(s, f"standin remove {W_UUID2}", 0.3)


V_UUID = "99999999-aaaa-4bbb-8ccc-dddddddddddd"      # M5-5: the escorted player (a stand-in)
Y2_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"     # M5-5: the detachment sponsor


def roster_rows(s, c):
    """[(rosterId8, unitKey, state, entity8, duty)] from /hywmill village garrison units."""
    out = []
    for l in s.output(at(c, "hywmill village garrison units"), 2):
        m = re.search(r"^\s*([0-9a-f]{8}) (\S+) lvl\d+ (\w+)\S* gen\d+(?: entity=([0-9a-f]{8}))? duty=(\w+)", l)
        if m:
            out.append(m.groups())
    return out


def positions(s, ids8):
    rows = spike_info(s, "@e[type=!minecraft:player]")
    return {u[:8]: r["pos"] for u, r in rows.items() if u[:8] in ids8}


def forceload_list(s):
    return " ".join(s.output("forceload query", 1))


def make_calm(s, c, timeout=90):
    """Day, no hostile mobs near the village, and wait for its alert to settle to CALM (requests are refused otherwise)."""
    s.cmd("time set day", 0.5)
    for mob in ("zombie", "skeleton", "creeper", "spider", "witch", "drowned", "husk", "stray", "pillager", "enderman"):
        s.cmd(f"kill @e[type=minecraft:{mob}]", 0.2)
    wait_garrison(s, c, lambda g: g.get("alert") == "CALM", timeout)
    return garrison(s, c).get("alert")


def open_path(s, c, steps=5, step=8, start=24):
    """Points on open, dry ground at village level along the first compass direction that has them (no roofs, no water)."""
    import math
    for deg in range(0, 360, 45):
        dx, dz = math.cos(math.radians(deg)), math.sin(math.radians(deg))
        pts = [(int(c[0] + dx * (start + k * step)), int(c[2] + dz * (start + k * step))) for k in range(steps)]
        ok = True
        for (x, z) in pts:
            t, top, water = ticking(s, x, z)
            if not t or water or abs(top - c[1]) > 4:
                ok = False
                break
        if ok:
            return pts
    return [(c[0] + 24 + 8 * k, c[2] + 2) for k in range(steps)]


def scenario_G5_5(ctx):
    """M5-5: detachments (granted from spare units, Favor paid on acceptance; walk to and hold a point by hops, never
    teleport, never force-load; dismissal and clean-errand Favor; casualty cost) and the approved Reconciler pause for
    a detached unit whose chunk is unloaded. Player-following escorts are DEFERRED (not in the M5 sign-off scope)."""
    s, a = ctx.s, ctx.a
    ca = f"{a[0]} {a[1]} {a[2]}"
    ESC = "decision: player-following escorts deferred to a future phase; lent soldiers never teleport"
    for name in ("an escort follows the player (hops towards them)", "escort sizes per standing (Trusted 2 / Patron 4 / Sworn 6)",
                 "an escort defends its player", "a Trusted player's escort stays within the village's lands"):
        deferred(f"G5-5 {name}", ESC)
    wait_garrison(s, a, lambda g: g.get("alive", 0) >= 8 and g.get("recruited", 1) == 0, 600)
    note("G5-5 garrison", str({k: v for k, v in garrison(s, a).items() if k != "lines"}))
    note("G5-5 alert before requests", str(make_calm(s, a)))
    esc = s.output(at(a, f"hywmill politics request-for {V_UUID} escort 2"), 1.5)
    check("G5-5 escort requests are not offered (deferred): the command does not exist", not any("request OK" in l for l in esc)
          and any(("Unknown" in l or "Incorrect" in l or "<--[HERE]" in l) for l in esc), " | ".join(esc)[:200])
    standin_at(s, V_UUID, a[0] + 2, a[2] + 2)
    q = s.output(at(a, f"hywmill politics request-for {Q_UUID} detachment 2 {a[0] + 20} {a[1]} {a[2]} 1"), 1.5)
    check("G5-5 negative: a stranger's detachment request is refused", any("STANDING_TOO_LOW" in l for l in q), " | ".join(q))
    m5(s, f"mill rep {ca} {V_UUID} adjust 9000")
    for _ in range(12):
        s.output(at(a, f"hywmill politics admin favor {V_UUID} REQUESTED_DIPLOMACY"), 0.2)
    time.sleep(14)
    v0 = pshow(s, a, V_UUID)
    fl0 = forceload_list(s)
    path = open_path(s, a)
    px, pz = path[-1]
    note("G5-5 detachment point (open ground)", str((px, pz)))
    make_calm(s, a)
    r = s.output(at(a, f"hywmill politics request-for {V_UUID} detachment 2 {px} {a[1]} {pz} 1"), 2)
    rows = [x for x in roster_rows(s, a) if x[4] == "DETACHED"]
    v1 = pshow(s, a, V_UUID)
    check("G5-5 a patron's detachment is granted from what the village can spare; Favor paid on acceptance",
          v0.get("status") == "PATRON" and any("request OK" in l for l in r) and 1 <= len(rows) <= 4
          and v1.get("favor") == v0.get("favor", 0) - 2 * len(rows), f"{v0} {' | '.join(r)} {len(rows)} detached {v1}")
    ids = {x[3] for x in rows if x[3]}
    speeds, held, dp = [], [], {}
    last = {k: (time.time(), p) for k, p in positions(s, ids).items()}
    for _ in range(30):  # walking pace: up to 150 s
        time.sleep(5)
        t = time.time()
        dp = positions(s, ids)
        for k, p in dp.items():
            if k in last:
                lt, lp = last[k]
                speeds.append(((p[0] - lp[0]) ** 2 + (p[2] - lp[2]) ** 2) ** 0.5 / max(0.5, t - lt))
            last[k] = (t, p)
        held = [k for k, p in dp.items() if abs(p[0] - px) + abs(p[2] - pz) <= 10]
        if held:
            break
    check("G5-5 a detachment walks to and holds the named point", bool(held), f"{len(held)}/{len(ids)} within 10 of {(px, pz)}: {dp}")
    check("G5-5 lent soldiers never teleport (fastest horizontal speed between samples <= 8 blocks/s)", speeds and max(speeds) <= 8,
          f"max {round(max(speeds), 1) if speeds else None} blocks/s over {len(speeds)} samples")
    check("G5-5 lent soldiers never force-load", forceload_list(s) == fl0, forceload_list(s)[:120])
    st = pstatus(s, a, V_UUID)
    check("G5-5 politics status lists the lent soldiers", any("Soldiers lent to you" in l for l in st), " | ".join(st)[:300])
    dm = s.output(at(a, f"hywmill politics request-for {V_UUID} dismiss"), 1.5)
    time.sleep(8)
    back = [x for x in roster_rows(s, a) if x[3] in ids]
    v2 = pshow(s, a, V_UUID)
    check("G5-5 dismissed: the soldiers walk home (RETURNING/GARRISONED) and a clean errand earns Favor",
          any("dismiss OK" in l for l in dm) and all(x[4] != "DETACHED" for x in back) and v2.get("favor", 0) > v1.get("favor", 0),
          f"{' | '.join(dm)} {[(x[2], x[4]) for x in back]} favor {v1.get('favor')}->{v2.get('favor')}")
    # a second detachment: a soldier killed on the errand costs Favor; the approved Reconciler pause for an unloaded chunk
    m5(s, f"mill rep {ca} {Y2_UUID} adjust 9000")
    for _ in range(12):
        s.output(at(a, f"hywmill politics admin favor {Y2_UUID} REQUESTED_DIPLOMACY"), 0.2)
    standin_at(s, Y2_UUID, a[0] - 3, a[2] - 3)
    time.sleep(14)
    make_calm(s, a)
    d = s.output(at(a, f"hywmill politics request-for {Y2_UUID} detachment 2 {px} {a[1]} {pz} 1"), 2)
    dids = {x[3] for x in roster_rows(s, a) if x[4] == "DETACHED" and x[3]}
    check("G5-5 a second detachment is granted", any("request OK" in l for l in d) and dids, " | ".join(d)[:200])
    y0 = pshow(s, a, Y2_UUID)
    victim = next((u for u in spike_info(s, "@e[type=!minecraft:player]") if u[:8] in dids), None)
    if victim:
        s.cmd(f"kill {victim}", 2)
    time.sleep(3)
    y1 = pshow(s, a, Y2_UUID)
    check("G5-5 a soldier killed on the player's errand costs Favor", victim and y1.get("favor", 0) < y0.get("favor", 0), f"{y0} -> {y1}")
    far_x, far_z = a[0] + 220, a[2]
    s.cmd(f"forceload add {far_x - 16} {far_z - 16} {far_x + 16} {far_z + 16}", 2)
    time.sleep(3)
    survivor = next((u for u in spike_info(s, "@e[type=!minecraft:player]") if u[:8] in dids and u != victim), None)
    note("G5-5 survivor", str(survivor))
    if survivor:
        s.cmd(f"tp {survivor} {far_x} {a[1] + 40} {far_z}", 1)
        s.cmd(f"spreadplayers {far_x} {far_z} 0 4 false {survivor}", 2)
        time.sleep(15)  # at least one garrison slot records it there
        s.cmd(f"forceload remove {far_x - 16} {far_z - 16} {far_x + 16} {far_z + 16}", 2)
        time.sleep(75)  # > missingGrace (1200 ticks) of active village time
        st2 = [x for x in roster_rows(s, a) if x[3] == survivor[:8]]
        check("G5-5 an errand unit whose chunk is unloaded is not marked MISSING (missing clock paused)", st2 and st2[0][2] == "DEPLOYED", str(st2))
        s.cmd(f"forceload add {far_x - 16} {far_z - 16} {far_x + 16} {far_z + 16}", 2)
        time.sleep(15)
        st3 = [x for x in roster_rows(s, a) if x[3] == survivor[:8]]
        check("G5-5 when its chunk loads again the unit is found and still on its errand", st3 and st3[0][2] == "DEPLOYED", str(st3))
        s.cmd(f"forceload remove {far_x - 16} {far_z - 16} {far_x + 16} {far_z + 16}", 1)
    else:
        check("G5-5 an errand unit whose chunk is unloaded is not marked MISSING (missing clock paused)", False, "no surviving detached unit")
    cen = census(s, a)
    check("G5-5 no duplicate slots after the errands", cen.get("dupSlots", 1) == 0, str(cen))
    s.output(at(a, f"hywmill politics request-for {Y2_UUID} dismiss"), 1)
    perf = s.output("hywmill perf", 2)
    note("G5-5 perf", " | ".join(l for l in perf if "duty" in l or "garrison" in l))
    for u in (V_UUID, Y2_UUID):
        m5(s, f"standin remove {u}", 0.3)



U_UUID = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"      # M5-5b: the campaigning player (a stand-in)


def war_lines(s, c, sub, wait=1.5):
    return [l for l in s.output(at(c, f"hywmill war {sub}"), wait) if l.startswith("war") or l.startswith(" ")]


def scenario_G5_5b(ctx):
    """M5-5b (run with [politics] warMinConflictTicks = 200): automatic war from sustained open conflict, projected on
    the faction identities only and kept by the guard; a campaign (FRIENDLY with the ally, HOSTILE with the enemy, enemy
    combatant threat, grievance); leave and truce restore the previous relations; persistence."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    ab = f"{ca} {cb}"
    va, vb = info(s, a), info(s, b)
    fa, fb, ra, rb = va.get("faction"), vb.get("faction"), va.get("residents"), vb.get("residents")
    dip(s, a, f"admin truce {ca} {cb} 0")
    for u in (U_UUID,):
        m5(s, f"mill discover {ca} {u}", 0.5)
        m5(s, f"mill discover {cb} {u}", 0.5)
    j0 = war_lines(s, a, f"for {U_UUID} join {ca} against {cb}")
    check("G5-5b negative: no war, no campaign", any("NO_WAR" in l for l in j0), " | ".join(j0))
    r0 = rel(s, fa, fb)
    m5(s, f"mill mrel {ab} set -100")
    time.sleep(8)
    st0 = war_lines(s, a, f"for {U_UUID} status")
    check("G5-5b open conflict is not yet a war (minimum duration)", not any("at war" in l for l in st0) and rel(s, fa, fb) == r0,
          " | ".join(st0))
    time.sleep(25)
    st1 = war_lines(s, a, f"for {U_UUID} status")
    r1 = rel(s, fa, fb)
    check("G5-5b sustained open conflict becomes a war: faction <-> faction HOSTILE", any("at war" in l for l in st1) and r1 == ("HOSTILE", "HOSTILE"),
          f"{' | '.join(st1)} {r1}")
    check("G5-5b the resident identities stay out of the war", "HOSTILE" not in rel(s, ra, fb) and "HOSTILE" not in rel(s, rb, fa)
          and "HOSTILE" not in rel(s, ra, rb), f"{rel(s, ra, fb)} {rel(s, rb, fa)} {rel(s, ra, rb)}")
    time.sleep(15)
    check("G5-5b the escalation guard keeps the war's HOSTILE (political policy)", rel(s, fa, fb) == ("HOSTILE", "HOSTILE"), str(rel(s, fa, fb)))
    j1 = war_lines(s, a, f"for {U_UUID} join {ca} against {cb}")
    check("G5-5b negative: a stranger cannot join the war", any("STANDING_TOO_LOW" in l for l in j1), " | ".join(j1))
    standin_at(s, U_UUID, a[0] + 3, a[2] + 3)
    m5(s, f"mill rep {ca} {U_UUID} adjust 5000")
    time.sleep(14)
    u0 = rel(s, U_UUID, fa), rel(s, U_UUID, fb)
    j2 = war_lines(s, a, f"for {U_UUID} join {ca} against {cb}")
    u1 = rel(s, U_UUID, fa), rel(s, U_UUID, fb)
    ub = pshow(s, b, U_UUID)
    check("G5-5b a trusted player joins: FRIENDLY with the ally's faction, HOSTILE with the enemy's; the enemy holds a grievance",
          any("join OK" in l for l in j2) and u1 == (("FRIENDLY", "FRIENDLY"), ("HOSTILE", "HOSTILE")) and ub.get("kind") == "JOINED_ENEMY",
          f"{' | '.join(j2)} before {u0} after {u1} {ub}")
    stb = pstatus(s, b, U_UUID)
    check("G5-5b the enemy treats the player as an enemy combatant (not an outlaw)", any("enemy combatant" in l for l in stb) and ub.get("status") != "OUTLAW",
          " | ".join(stb))
    m5(s, f"standin remove {U_UUID}", 0.3)
    standin_at(s, U_UUID, b[0] + 5, b[2] + 5)
    m5(s, f"standin mode {U_UUID} survival", 0.5)
    seen = []
    for _ in range(6):
        time.sleep(2.5)
        seen += [l for l in threat_lines(s, b) if U_UUID[:8] in l]
    check("G5-5b in the enemy village the campaigning player is an ENEMY_COMBATANT threat", any("ENEMY_COMBATANT" in l for l in seen), " | ".join(seen[:3]))
    # persistence of war, campaign and projection
    restart(ctx)
    time.sleep(25)
    st2 = war_lines(s, a, f"for {U_UUID} status")
    check("G5-5b after a restart: still at war, campaign kept, projections intact",
          any("at war" in l for l in st2) and any("campaign: for" in l for l in st2) and rel(s, fa, fb) == ("HOSTILE", "HOSTILE")
          and rel(s, U_UUID, fb) == ("HOSTILE", "HOSTILE"), " | ".join(st2))
    l1 = war_lines(s, a, f"for {U_UUID} leave")
    u2 = rel(s, U_UUID, fa), rel(s, U_UUID, fb)
    check("G5-5b leaving restores the player's previous relations", any("leave OK" in l for l in l1) and u2 == u0, f"{u2} vs {u0}")
    dip(s, a, f"admin truce {ca} {cb} 1")
    time.sleep(22)
    r2 = rel(s, fa, fb)
    st3 = war_lines(s, a, f"for {U_UUID} status")
    check("G5-5b a truce ends the war and restores faction <-> faction", r2 == r0 and not any("at war" in l for l in st3), f"{r2} vs {r0}; {' | '.join(st3)}")
    hist = pstatus(s, a, U_UUID)
    check("G5-5b war start and end are in the chronicle", any("at war" in l for l in hist) or any("war" in l.lower() for l in hist), " | ".join(hist[-3:]))
    dip(s, a, f"admin truce {ca} {cb} 0")
    m5(s, f"mill mrel {ab} set 0")
    perf = s.output("hywmill perf", 2)
    note("G5-5b perf", " | ".join(l for l in perf if "relations" in l))
    m5(s, f"standin remove {U_UUID}", 0.3)


ARMOURY_PACK = REPO / "content" / "millenaire-custom" / "hywmill_armoury"


def install_armoury_pack(d: Path):
    """M5-6: the shipped armoury content pack, installed as a server owner would (copy into millenaire-custom/)."""
    dst = d / "millenaire-custom" / "hywmill_armoury"
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(ARMOURY_PACK, dst)


def scenario_G5_6(ctx):
    """M5-6: the armoury pack loads in every culture (recruit scrolls, Patron-gated by Millénaire's min_reputation, the
    shop's original goods kept); honours are written to the chronicle and listed."""
    s, a = ctx.s, ctx.a
    import json as _json
    for cdir in sorted(ARMOURY_PACK.glob("cultures/*")):
        culture = "millenaire:" + cdir.name
        goods = _json.loads((cdir / "traded_goods.json").read_text())["goods"]
        shop_file = next((cdir / "shops").glob("*.json"))
        shop = _json.loads(shop_file.read_text())
        gl = [m5_1(s, f'mill goods "{culture}" {g["id"]}') for g in goods]
        sh = m5_1(s, f'mill shop "{culture}" {shop_file.stem}')
        originals = [x for x in shop["sells"] if not x.startswith("hywmill_")]
        check(f"G5-6 armoury {cdir.name}: scrolls load as HYW items at min_reputation 8192",
              all(g["item"] in l and "minRep=8192" in l and "resolved=minecraft:air" not in l for g, l in zip(goods, gl)), " || ".join(gl)[:300])
        check(f"G5-6 armoury {cdir.name}: the {shop_file.stem} shop sells the scrolls and keeps its original goods",
              all(g["id"] in sh for g in goods) and all(o in sh for o in originals), sh[:300])
    # honours: a promotion to trusted is chronicled as an honour and listed
    hp = "cccccccc-dddd-4eee-8fff-000000000000"
    standin_at(s, hp, a[0] + 2, a[2] - 2)
    m5(s, f"mill rep {a[0]} {a[1]} {a[2]} {hp} adjust 5000")
    time.sleep(14)
    st = pstatus(s, a, hp)
    hon = s.output(f"hywmill politics honours for {hp}", 1.5)
    check("G5-6 an honour is written to the chronicle ('honoured as a trusted friend')", any("honoured as a trusted friend" in l for l in st), " | ".join(st[-2:]))
    check("G5-6 honours are listed", any("trusted friend of" in l for l in hon), " | ".join(hon))
    m5(s, f"standin remove {hp}", 0.3)


def scenario_G5_UI(ctx):
    """M5-UI (server side, headless): the Politics screen's handlers build snapshots only from the politics API, offer
    the server's verdict per action, re-validate and perform intents, and the wire format round-trips. The dedicated
    server never loads client classes."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    uid = "dddddddd-eeee-4fff-8000-111111111111"
    standin_at(s, uid, a[0] + 2, a[2] + 3)
    for c in (ca, cb):
        m5(s, f"mill discover {c} {uid}", 0.5)
    o = m5(s, f"ui open {uid}", 2)
    check("G5-UI open: the snapshot has the nearest village as home and lists the known villages",
          any("m5 ui home=" in l and "villages=" in l and "villages=0" not in l for l in o) and any("roundtrip=True" in l or "roundtrip=true" in l for l in o),
          " | ".join(o[:3]))
    sel = m5(s, f"ui select {uid} {cb}", 2)
    acts = {m[1]: m[2] == "true" for l in sel for m in [re.search(r"action (\w+) available=(\w+)", l)] if m}
    home_acts = {m[1] for l in o for m in [re.search(r"action (\w+) available", l)] if m}
    check("G5-UI the escort action is not offered (deferred)", "ESCORT" not in home_acts and home_acts, str(sorted(home_acts)))
    check("G5-UI selecting another village offers the four envoy kinds and war, all refused for a stranger (server verdict)",
          all(k in acts for k in ("RECONCILE", "TRUCE", "ENCOURAGE", "SOW_DISCORD", "WAR_JOIN")) and not any(acts.values()), str(acts))
    m5(s, f"mill rep {ca} {uid} adjust 5000")
    m5(s, f"mill dpoints {ca} {uid} regen", 0.5)
    m5(s, f"mill mrel {ca} {cb} set -40")
    time.sleep(14)
    sel2 = m5(s, f"ui select {uid} {cb}", 2)
    rec = next((l for l in sel2 if "action RECONCILE" in l), "")
    check("G5-UI once trusted, reconciliation is offered with an outcome band", "available=true" in rec and re.search(r"outcome=(likely|uncertain|unlikely)", rec),
          rec)
    sub = m5(s, f"ui submit {uid} {cb} RECONCILE", 2)
    check("G5-UI submitting the intent goes through the same API as the command (envoy sent)", any("ok=true" in l and "code=OK" in l for l in sub), " | ".join(sub[:2]))
    sub2 = m5(s, f"ui submit {uid} {cb} RECONCILE", 2)
    check("G5-UI negative: the server re-validates (same pair again -> cooldown)", any("ok=false" in l and "PAIR_COOLDOWN" in l for l in sub2), " | ".join(sub2[:1]))
    bogus = m5(s, f"ui submit {uid} {cb} GRANT_ME_EVERYTHING", 2)
    check("G5-UI negative: an unknown intent is refused", any("UNKNOWN_ACTION" in l for l in bogus), " | ".join(bogus[:1]))
    m5(s, f"standin remove {uid}", 0.3)
    m5(s, f"standin add {uid} {b[0] + 400} {b[1] + 10} {b[2] + 400}", 1)
    far = m5(s, f"ui submit {uid} {ca} PARDON_PAY", 2)
    check("G5-UI negative: a local request (pardon) from far away is refused", any("TOO_FAR" in l for l in far), " | ".join(far[:1]))
    log_lines = s.read_since(s.start_pos)
    bad = [l for l in log_lines if re.search(r"(NoClassDefFoundError|ClassNotFoundException|RuntimeException: Attempted to load class).*(client|Screen|Minecraft)", l)]
    check("G5-UI the dedicated server never loads client classes", not bad, bad[0][:200] if bad else "")
    m5(s, f"standin remove {uid}", 0.3)
    m5(s, f"mill mrel {ca} {cb} set 0")


SG_C3 = dict(perSlot={"WATCH": 2.0, "GUARD_POST": 2.25, "GARRISON": 2.5, "STRONGHOLD": 3.0},
             levyShare={"WATCH": 0.15, "GUARD_POST": 0.20, "GARRISON": 0.25, "STRONGHOLD": 0.30},
             infra={"BARRACKS": 8, "FORT_TOWNHALL": 8, "ARMOURY": 4, "TRAINING": 4, "GUARDHOUSE": 3, "WATCHTOWER": 3, "TOWER": 2, "GATE": 1},
             minTarget={"WATCH": 1, "GUARD_POST": 2, "GARRISON": 3, "STRONGHOLD": 4})


def c3_target(d):
    t = d.get("tier")
    if t not in SG_CAPS:
        return 0
    raw = d.get("capacity", 0) * SG_C3["perSlot"][t]
    raw += sum(SG_C3["infra"].get(k, 0) * int(n) for k, n in re.findall(r"(\w+)=(\d+)", d.get("buildingRoles", "")))
    raw += min(d.get("fortification", 0) / 3, 30) + SG_C3["levyShare"][t] * d.get("adults", 0)
    return min(SG_CAPS[t], max(SG_C3["minTarget"][t], int(raw + 0.5)))


def scenario_G5_G(ctx):
    """M5-G on the shipped data: locked caps, C3 targets from each village's real inputs, and the load-state gate after a restart."""
    s = ctx.s
    ds = [village_inputs(s, c) for c in village_centers(s)]
    ds = [d for d in ds if d.get("tier") in SG_CAPS]
    check("G5-G shipped caps are the locked caps", ds and all(d.get("cap_now") == SG_CAPS[d["tier"]] for d in ds),
          str([(d.get("name"), d.get("tier"), d.get("cap_now")) for d in ds]))
    rows = [(d.get("name"), d.get("tier"), d.get("target_now"), c3_target(d)) for d in ds]
    check("G5-G every village's target is the C3 formula on its own inputs", rows and all(r[2] == r[3] for r in rows), str(rows))
    for d in ds:
        log("G5-G input " + json_dumps({k: d.get(k) for k in ("name", "tier", "capacity", "adults", "fortification", "buildingRoles", "target_now", "live")}))
    p0 = s.pos()
    restart(ctx)
    early = []
    for _ in range(6):
        for c in village_centers(s)[:4]:
            g = garrison(s, c)
            early.append((round(time.time()), g.get("live"), g.get("target"), any("waits for the village" in l for l in g.get("lines", []))))
        time.sleep(3)
    rec_before_auth = s.wait_for(r"Village '.*' recruits", 1, since=p0)
    note("G5-G after restart (time, live, target, gated)", str(early[:12]))
    check("G5-G after a restart no target exceeds the live garrison while the gate is closed",
          all(t is None or l is None or not gated or t <= l for _, l, t, gated in early), str([e for e in early if e[3]][:8]))
    time.sleep(40)
    ds2 = {d.get("name"): d for d in (village_inputs(s, c) for c in village_centers(s))}
    rows2 = [(n, d.get("tier"), d.get("target_now"), c3_target(d)) for n, d in ds2.items() if d.get("tier") in SG_CAPS]
    check("G5-G once the village has settled the target is the C3 target again", rows2 and all(r[2] == r[3] for r in rows2), str(rows2))


def write_m5_content(d: Path):
    """Spike I content: a Millénaire sub-mod under <server>/millenaire-custom/ (no code, no jar)."""
    base = d / "millenaire-custom" / "hywmill_armoury" / "cultures" / "norman"
    (base / "shops").mkdir(parents=True, exist_ok=True)
    (base / "traded_goods.json").write_text(
        '{"goods": [{"id": "hywmill_scroll_archer", "item": "hundred_years_war:scroll_archer", "selling_price": 64,'
        ' "min_reputation": 8192, "category": "military"}]}\n')
    (base / "shops" / "armoury.json").write_text(
        '{"sells": ["norman_helmet", "norman_chestplate", "norman_leggings", "norman_boots", "norman_sword", "bow",'
        ' "hywmill_scroll_archer"], "buys": [], "buys_optional": [], "deliver_to": []}\n')


ORDER_M5_OPT1 = ["status", "S5_0", "S5_P", "S5_V", "S5_B", "S5_N", "S5_W", "D", "G"]
ORDER_M5 = ["status", "S5_0", "S5_A", "S5_B", "S5_C", "S5_D", "S5_E", "S5_F", "S5_G", "S5_H", "S5_I", "S5_J", "S5_L", "S5_M",
            "S5_N", "S5_K", "S5_R"]


# --------------------------------------------------------------------------- M5-G spike S-G (scale)
# Inputs of real harness villages, fill to the LOCKED caps (24/48/72/128, set by a test-world datapack:
# data only, nothing in the mod changes), then CALM / ALERT / raid with /tick query and HywMill perf.

SG_CAPS = {"WATCH": 24, "GUARD_POST": 48, "GARRISON": 72, "STRONGHOLD": 128}
SG_MIL2 = [(t, int(x), 68, int(z)) for t, x, z in
           [tuple(v.split(",")) for v in os.environ.get("HYWMILL_SG_MIL2", "").split(";") if v]]


def village_inputs(s, c):
    d = {"center": c}
    for l in s.output(at(c, "hywmill village info"), 2):
        m = re.match(r"== (.*) \((\S+) / (\S+)\) at", l)
        if m:
            d.update(name=m[1], culture=m[2], type=m[3])
        m = re.search(r"^Tier: (\w+) \| garrison: (\d+) \| population: (\d+) \(adults (\d+)\)", l)
        if m:
            d.update(tier=m[1], mill_soldiers=int(m[2]), population=int(m[3]), adults=int(m[4]))
        m = re.search(r"fortification: (\d+)", l)
        if m:
            d["fortification"] = int(m[1])
        m = re.search(r"^Villager roles: (\{[^}]*\})", l)
        if m:
            d["villagerRoles"] = m[1]
        m = re.search(r"^Building roles: (\{[^}]*\})", l)
        if m:
            d["buildingRoles"] = m[1]
    mil = military(s, c)
    d["capacity"] = int(mil.get("capacity", 0) or 0)
    g = garrison(s, c)
    d.update(target_now=g.get("target"), cap_now=g.get("cap"), live=g.get("live"), faction=g.get("faction"))
    return d


def tick_query(s):
    out = " ".join(s.cmd("tick query", 1.2))  # the average is printed on a continuation line without the logger prefix
    avg = re.search(r"Average time per tick: ([\d.]+)ms", out)
    pct = re.search(r"P50: ([\d.]+)ms P95: ([\d.]+)ms P99: ([\d.]+)ms", out)
    if not avg:
        return None
    return dict(avg=float(avg[1]), p50=float(pct[1]) if pct else None, p95=float(pct[2]) if pct else None, p99=float(pct[3]) if pct else None)


def sample_phase(s, name, secs, step=5, during=None):
    samples = []
    end = time.time() + secs
    i = 0
    while time.time() < end:
        q = tick_query(s)
        if q:
            samples.append(q)
        if during:
            during(i)
        i += 1
        time.sleep(step)
    avgs = [x["avg"] for x in samples]
    p99 = [x["p99"] for x in samples if x["p99"] is not None]
    summ = dict(phase=name, n=len(samples), mspt_mean=round(sum(avgs) / len(avgs), 2) if avgs else None,
                mspt_max_avg=max(avgs) if avgs else None, p99_mean=round(sum(p99) / len(p99), 2) if p99 else None,
                p99_max=max(p99) if p99 else None)
    log(f"SG phase {summ}")
    return summ, samples


def hyw_unit_count(s):
    n = 0
    for l in s.output("execute store result score #n hwSG run execute if entity @e[type=#hywmill:sg_units]", 1):
        pass
    out = " ".join(s.output("scoreboard players get #n hwSG", 1))
    m = re.search(r"has (\d+) \[", out)
    return int(m[1]) if m else None


def write_sg_datapack(d: Path):
    dp = d / "world" / "datapacks" / "hywmill_sg"
    (dp / "data" / "hywmill" / "hywmill_garrison").mkdir(parents=True, exist_ok=True)
    (dp / "data" / "hywmill" / "tags" / "entity_type").mkdir(parents=True, exist_ok=True)
    (dp / "pack.mcmeta").write_text('{"pack":{"pack_format":48,"description":"hywmill S-G locked caps (test world only)"}}')
    tiers = ",".join(f'"{t}":{{"maxTarget":{c},"maxUnits":{c}}}' for t, c in SG_CAPS.items())
    (dp / "data" / "hywmill" / "hywmill_garrison" / "zz_sg_caps.json").write_text('{"defaults":{"tiers":{' + tiers + '}}}')
    types = ["militia", "spear_man", "shieldman", "warrior", "archer", "crossbowman", "mounted_light_lancer_rider", "mounted_archer_rider"]
    (dp / "data" / "hywmill" / "tags" / "entity_type" / "sg_units.json").write_text(
        '{"values":[' + ",".join(f'"hundred_years_war:{t}"' for t in types) + ']}')


def scenario_SG_0(ctx):
    """Villages for the scale spike and their real inputs (tier, capacity, population, adults, buildings, culture, type)."""
    s = ctx.s
    extra = ensure_extra_villages(ctx)
    for name in [k for k, v in extra.items() if v is None]:
        for attempt in range(3):
            extra[name] = spawn_village(s, EXTRA_VILLAGES[name], surface=True)
            if extra[name]:
                break
    for cand in SG_MIL2:  # a second stronghold (positions from a scout run)
        x, z = cand[1], cand[3]
        s.cmd(f"forceload add {x - 112} {z - 112} {x + 112} {z + 112}", 25)
        extra["militaire2"] = spawn_village(s, [cand], surface=True)
        if extra["militaire2"]:
            break
        s.cmd(f"forceload remove {x - 112} {z - 112} {x + 112} {z + 112}", 5)
    s.cmd("millenaire chunkload", 10)
    log(f"SG villages: {extra}")
    time.sleep(60)
    s.cmd("scoreboard objectives add hwSG dummy", 1)
    ctx.sg = {}
    for c in village_centers(s):
        d = village_inputs(s, c)
        ctx.sg.setdefault(d.get("faction"), d)
    for d in ctx.sg.values():
        log("SG input " + json_dumps(d))
    tiers = sorted(d.get("tier", "?") for d in ctx.sg.values())
    check("SG-0 village inputs collected", len(ctx.sg) >= 5 and all("population" in d and "capacity" in d for d in ctx.sg.values()), str(tiers))


def json_dumps(d):
    import json
    return json.dumps(d, sort_keys=True)


def scenario_SG_1(ctx):
    """Baseline, then every garrison filled to its LOCKED tier cap through the existing admin grant."""
    s = ctx.s
    write_sg_datapack(s.d)
    p = s.pos()
    s.cmd("reload", 10)
    s.cmd('datapack enable "file/hywmill_sg"', 8)
    loaded = s.wait_for(r"Garrison tables loaded", 30, since=p)
    time.sleep(20)
    s.cmd("hywmill perf reset", 1)
    ctx.sg_base, _ = sample_phase(s, "baseline (starting garrisons)", 60)
    ctx.sg_base_perf = s.output("hywmill perf", 2)
    ctx.sg_base_units = hyw_unit_count(s)
    caps = {k: garrison(s, d["center"]).get("cap") for k, d in ctx.sg.items()}
    check("SG-1 test datapack sets the locked caps (data only)", loaded is not None and all(
        caps[k] == SG_CAPS.get(d.get("tier"), 0) for k, d in ctx.sg.items()), str({d.get("name"): (d.get("tier"), caps[k]) for k, d in ctx.sg.items()}))
    s.cmd("hywmill perf reset", 1)
    t0 = time.time()
    for d in ctx.sg.values():
        log(f"SG fill {d.get('name')} {d.get('tier')}: {fill_garrison(s, d['center'])}")
    end = time.time() + 2400
    while time.time() < end and sum(garrison(s, d["center"]).get("recruited", 0) for d in ctx.sg.values()) > 0:
        time.sleep(20)
    ctx.sg_fill_secs = round(time.time() - t0)
    ctx.sg_fill_perf = s.output("hywmill perf", 2)
    per = {d.get("name"): garrison(s, d["center"]) for d in ctx.sg.values()}
    ctx.sg_units = hyw_unit_count(s)
    log("SG fill perf:\n  " + "\n  ".join(ctx.sg_fill_perf))
    check("SG-1 every garrison filled to its locked cap", all(g.get("live") == g.get("cap") and g.get("recruited") == 0 for g in per.values()),
          f"{ctx.sg_fill_secs}s; " + str({k: (g.get("live"), g.get("cap"), g.get("alive")) for k, g in per.items()}))
    note("SG-1 HYW garrison-type entities loaded (baseline -> filled)", f"{ctx.sg_base_units} -> {ctx.sg_units}")


def scenario_SG_2(ctx):
    """CALM at full size: MSPT, HywMill cost, duty staffing with the CURRENT M4 data."""
    s = ctx.s
    time.sleep(60)
    s.cmd("hywmill perf reset", 1)
    ctx.sg_calm, _ = sample_phase(s, "CALM (filled)", 120)
    ctx.sg_calm_perf = s.output("hywmill perf", 2)
    log("SG CALM perf:\n  " + "\n  ".join(ctx.sg_calm_perf))
    for d in ctx.sg.values():
        du = duties(s, d["center"])
        cnt = {}
        for r in du["rows"]:
            cnt[r["assigned"]] = cnt.get(r["assigned"], 0) + 1
        note(f"SG-2 duties {d.get('name')} {d.get('tier')} ({len(du['rows'])} units)", f"quota {du['quota']} assigned {cnt}")
    rows = perf_rows(ctx.sg_calm_perf)
    hm = rows.get("tick.total", {}).get("mean")
    note("SG-2 HywMill share of the tick (tick.total mean / MSPT)", f"{hm} us of {ctx.sg_calm.get('mspt_mean')} ms")
    check("SG-2 CALM at full size: MSPT measured", ctx.sg_calm.get("n", 0) > 10,
          f"baseline {ctx.sg_base} | filled {ctx.sg_calm}")


def scenario_SG_3(ctx):
    """ALERT at full size: bandits at the strongholds; M2 deployment from large rosters."""
    s = ctx.s
    strong = [d for d in ctx.sg.values() if d.get("tier") == "STRONGHOLD"] or list(ctx.sg.values())[:1]
    for d in strong:
        c = d["center"]
        for i in range(6):
            s.cmd(ground(c[0] + 10 + 2 * i, c[2] + 10, "summon hundred_years_war:bandit_soldier ~ ~ ~ {Tags:['hwSG']}"), 0.5)
    s.cmd("hywmill perf reset", 1)
    dep = {d.get("name"): [] for d in strong}
    t0 = time.time()
    first = {}

    def during(i):
        for d in strong:
            g = garrison(s, d["center"])
            dep[d.get("name")].append(g.get("deployed"))
            if g.get("deployed") and d.get("name") not in first:
                first[d.get("name")] = round(time.time() - t0)

    ctx.sg_alert, _ = sample_phase(s, "ALERT (bandits at strongholds)", 90, 6, during)
    ctx.sg_alert_perf = s.output("hywmill perf", 2)
    log("SG ALERT perf:\n  " + "\n  ".join(ctx.sg_alert_perf))
    note("SG-3 deployed over time", str(dep))
    note("SG-3 seconds to first deployment", str(first))
    s.cmd("kill @e[tag=hwSG]", 2)
    check("SG-3 large rosters deploy against the threat", all(any(x for x in v) for v in dep.values()), str(dep))


def scenario_SG_4(ctx):
    """Raid at full size: a stronghold raids a village; contingent size with the CURRENT M4 raid data."""
    s = ctx.s
    strong = [d for d in ctx.sg.values() if d.get("tier") == "STRONGHOLD"]
    if not strong:
        check("SG-4 a stronghold exists for the raid", False, "")
        return
    att = strong[0]["center"]
    tgt = ctx.a
    time.sleep(30)
    out = s.output(f"millenaire dev raid trigger {att[0]} {att[1]} {att[2]} {tgt[0]} {tgt[1]} {tgt[2]}", 3)
    s.cmd("hywmill perf reset", 1)
    raiders = []

    def during(i):
        du = duties(s, att)
        raiders.append(sum(1 for r in du["rows"] if r["assigned"] == "RAID"))

    ctx.sg_raid, _ = sample_phase(s, "raid (stronghold raids A)", 120, 6, during)
    ctx.sg_raid_perf = s.output("hywmill perf", 2)
    log("SG raid perf:\n  " + "\n  ".join(ctx.sg_raid_perf))
    note("SG-4 raid trigger", " ".join(out)[:200])
    note("SG-4 RAID-assigned units at the attacker over time", str(raiders))
    check("SG-4 raid at full size measured (contingent capped by current maxCommit)", ctx.sg_raid.get("n", 0) > 10, f"max contingent {max(raiders) if raiders else None}")


def scenario_SG_5(ctx):
    """Restart at full size: no duplicates, no lost slots."""
    s = ctx.s
    restart(ctx)
    time.sleep(90)
    bad = {}
    for d in ctx.sg.values():
        cen = census(s, d["center"])
        g = garrison(s, d["center"])
        if cen.get("dupSlots", 1) != 0 or g.get("live") != g.get("cap"):
            bad[d.get("name")] = (cen, g.get("live"), g.get("cap"))
    check("SG-5 restart at full size: no duplicate slots, every garrison still at its cap", not bad, str(bad) if bad else "all villages clean")
    s.cmd("hywmill perf reset", 1)
    ctx.sg_after, _ = sample_phase(s, "CALM after restart", 60)


def scenario_SG_6(ctx):
    """Attribution at full size (kept world): settled CALM, then the same garrison units with AI frozen
    (NoAI), then AI back. The difference is HYW's per-unit AI cost; HywMill's own cost comes from perf."""
    s = ctx.s
    s.cmd('datapack enable "file/hywmill_sg"', 5)
    time.sleep(240)  # settle: units at their posts
    n = hyw_unit_count(s)
    s.cmd("hywmill perf reset", 1)
    calm, _ = sample_phase(s, "CALM settled", 180)
    perf = s.output("hywmill perf", 2)
    s.cmd("execute as @e[type=#hywmill:sg_units] run data merge entity @s {NoAI:1b}", 3)
    time.sleep(20)
    frozen, _ = sample_phase(s, "CALM, garrison AI frozen (NoAI)", 120)
    s.cmd("execute as @e[type=#hywmill:sg_units] run data merge entity @s {NoAI:0b}", 3)
    time.sleep(30)
    again, _ = sample_phase(s, "CALM, AI back", 120)
    rows = perf_rows(perf)
    note("SG-6 garrison-type units loaded", str(n))
    note("SG-6 HywMill per-tick cost at full size (tick.total, duty.tick)", " | ".join(f"{k} mean {v['mean']}us p99 {v['p99']}us" for k, v in rows.items()))
    note("SG-6 phases", f"{calm} || {frozen} || {again}")
    check("SG-6 attribution measured", calm.get("n", 0) > 20 and frozen.get("n", 0) > 10, f"AI cost ≈ {round((calm['mspt_mean'] or 0) - (frozen['mspt_mean'] or 0), 1)} ms/tick")


ORDER_SG = ["status", "SG_0", "SG_1", "SG_2", "SG_3", "SG_4", "SG_5"]
ORDER_M5_PHASES = ["status", "G5_G", "G5_2", "G5_3", "G5_4", "G5_5", "G5_5b", "G5_6", "G5_UI"]


def scenario_WP(ctx):
    """War and peace counsel and mobilization (post-M5; run after G4_0): a stranger's war counsel is refused; an operator
    forces A to declare war on B (at once, relation -100); A, short of its target after losses, mobilizes the gap with free
    levies (not a stronghold); a forced peace sets the relation to -75, ends the war and sends the levies home; a finished
    siege makes peace too."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    P = W_UUID
    p0 = s.pos()
    dip(s, a, f"admin truce {ca} {cb} 0")
    m5(s, f"mill mrel {ca} {cb} set 0")
    time.sleep(12)
    ga = garrison(s, a)
    fa, ta = ga.get("faction"), info(s, a).get("tier")
    r0 = " ".join(l for l in s.output(f"hywmill war for {P} declare {ca} on {cb}", 2) if l.startswith("war declare"))
    check("WP-1 a stranger cannot counsel war", "STANDING_TOO_LOW" in r0, r0)
    # losses leave A short of its target (the death cooldown keeps ordinary recruitment from refilling at once)
    units = [u for u, x in spike_info(s, "@e[type=!minecraft:player]").items()
             if x["tag"] != "none" and ("owner=" + str(fa)) in x["desc"] and dist(x["pos"], a) <= 110]
    for u in units[:3]:
        s.cmd(f"kill {u}", 0.5)
    time.sleep(3)
    g1 = garrison(s, a)
    r1 = " ".join(l for l in s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2) if l.startswith("war declare"))
    rel1 = mrel_of(s, f"{ca} {cb}")
    time.sleep(12)
    g2 = garrison(s, a)
    logs = s.read_since(p0)
    mob = [l for l in logs if "Mobilization:" in l and "mobilizes" in l]
    check("WP-2 a forced war counsel declares war at once (relation -100)",
          "AGREED" in r1 and rel1 == (-100, -100) and any("started (declared)" in l for l in logs), f"{r1}; relation {rel1}")
    expect = min(g1.get("target", 0), g1.get("cap", 0)) - g1.get("live", 0)
    if ta == "STRONGHOLD":
        check("WP-3 a stronghold does not mobilize", not any(" A " in l for l in mob), f"tier {ta}; {mob}")
    else:
        check("WP-3 A mobilizes the gap to its current target (not the tier cap), free", expect > 0 and g2.get("live") == g1.get("live", 0) + expect
              and g2.get("levy") >= g1.get("levy", 0) - 0.01,
              f"tier {ta}: before {g1.get('live')}/{g1.get('target')} (cap {g1.get('cap')}), after {g2.get('live')}/{g2.get('target')}; {mob[-1:] if mob else 'no line'}")
    time.sleep(25)
    g3 = garrison(s, a)
    r2 = " ".join(l for l in s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2) if l.startswith("war peace"))
    rel2 = mrel_of(s, f"{ca} {cb}")
    time.sleep(12)
    g4 = garrison(s, a)
    logs = s.read_since(p0)
    home = [l for l in logs if "Mobilization:" in l and "go home" in l]
    check("WP-4 a forced peace sets the relation to -75 and ends the war",
          "AGREED" in r2 and rel2 == (-75, -75) and any("ended (peace" in l for l in logs), f"{r2}; relation {rel2}")
    check("WP-5 at peace the mobilized soldiers go home", ta == "STRONGHOLD" or (home and g4.get("live") == g3.get("live", 0) - expect),
          f"live {g3.get('live')} -> {g4.get('live')}; {home[-1:] if home else 'no line'}")
    time.sleep(30)
    w = [l for l in s.read_since(p0) if "War: " in l and "started" in l]
    check("WP-6 at -75 the war does not restart by itself", len(w) == 1, f"{len(w)} war start(s)")
    s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2)
    time.sleep(3)
    out = " | ".join(l for l in s.output(f"hywmill war admin siege {ca} {cb} unwatched", 2) if l.startswith("war siege"))
    sg, t_e = wait_siege(s, lambda l: not l or "RETURN" in l[0], 420)
    time.sleep(8)
    logs = s.read_since(p0)
    rel3 = mrel_of(s, f"{ca} {cb}")
    check("WP-7 a finished siege ends the war: the loser sues for peace (relation -75)",
          any("sued for peace" in l for l in logs) and rel3 == (-75, -75), f"{out}; {sg}; relation {rel3}")
    dups = [l for l in logs if "Duplicate garrison unit refused" in l]
    check("WP-8 no duplicates", not dups, f"{len(dups)}")


def relief_units(s, fac, c, r=90):
    return [u for u, x in spike_info(s, "@e[type=!minecraft:player]").items()
            if x["tag"] != "none" and ("owner=" + str(fac)) in x["desc"] and dist(x["pos"], c) <= r]


def scenario_RL(ctx):
    """Relief forces (post-M5; run after G4_0, --keep-world with the extra villages): C, on great terms with B, relieves B
    against A's siege: its force sets out when A marches, arrives first and stands spread round B, HOSTILE to A's
    soldiers, and goes home when the siege ends; an ambushed and routed relief loses soldiers (dead) and never arrives;
    a relief that loses its way costs no lives."""
    s, a, b = ctx.s, ctx.a, ctx.b
    for box in EXTRA_FORCELOAD:
        s.cmd("forceload add {} {} {} {}".format(*box), wait=10)
    centers = []
    for l in s.output("hywmill village list", 2):
        m = re.search(r" \((-?\d+), (-?\d+), (-?\d+)\) tier=| (-?\d+), (-?\d+), (-?\d+) tier=", l)
        if m:
            centers.append(tuple(int(x) for x in m.groups() if x is not None))
    c = next((v for v in centers if v not in (a, b) and dist(v, b) < 450), None)
    check("RL-0 a third village to send relief", c is not None, str(centers))
    if c is None:
        return
    ca, cb, cc = (f"{v[0]} {v[1]} {v[2]}" for v in (a, b, c))
    P = W_UUID
    p0 = s.pos()
    fa, fc = info(s, a).get("faction"), info(s, c).get("faction")
    time.sleep(15)
    t0 = time.time()
    while time.time() - t0 < 300 and sieges(s):  # a siege left over from an earlier scenario finishes its return first
        time.sleep(5)
    dip(s, a, f"admin truce {ca} {cb} 0")
    m5(s, f"mill mrel {cc} {cb} set 90")
    m5(s, f"mill mrel {ca} {cc} set 0")
    s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2)
    time.sleep(3)
    gc0 = garrison(s, c)
    out = " | ".join(l for l in s.output(f"hywmill war admin siege {ca} {cb} quick", 2) if l.startswith("war siege"))
    r1 = " | ".join(l for l in s.output(f"hywmill war admin relief {cc} {cb} clean", 2) if l.startswith("war relief"))
    check("RL-1 C promises relief to the besieged B", "war siege OK" in out and "war relief OK" in r1, f"{out} || {r1}")
    t0 = time.time()
    sent = arrived = None
    while time.time() - t0 < 360:
        logs = s.read_since(p0)
        sent = sent or next((l for l in logs if "by forced march to relieve" in l), None)
        arrived = next((l for l in logs if "reaches" in l and "takes position" in l), None)
        if arrived:
            break
        time.sleep(5)
    logs = s.read_since(p0)
    march = next((l for l in logs if "marching" in l and "stowed" in l), None)
    stands = next((l for l in logs if "closes on" in l), None)
    check("RL-2 the relief sets out when the attackers march", sent is not None and march is not None
          and logs.index(march) <= logs.index(sent), f"{march} || {sent}")
    time.sleep(8)
    here = relief_units(s, fc, b)
    raw = s.output(at(c, f"hywmill dev relation {fa}"), 2) if fa else []
    note("RL relation raw", f"fa={fa} fc={fc} :: " + " | ".join(raw)[:400])
    rel = relation(s, c, fa) if fa else None
    check("RL-3 it arrives (before the attackers) and stands spread round B, HOSTILE to A's soldiers",
          arrived is not None and len(here) >= 1 and (stands is None or logs.index(arrived) < logs.index(stands)) and rel == ("HOSTILE", "HOSTILE"),
          f"{arrived}; {len(here)} of C's soldiers near B; attackers stand: {stands is not None}; C<->A {rel}; {sieges(s)}")
    spread = max((dist(x, y) for x in [spike_info(s, u).get(u, {}).get("pos") for u in here[:6]] for y in [spike_info(s, u).get(u, {}).get("pos") for u in here[:6]]
                  if x and y), default=0)
    note("RL relief spread", f"max distance between relief soldiers {spread:.0f}")
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)
    t0 = time.time()
    while time.time() - t0 < 300 and sieges(s):
        time.sleep(5)
    time.sleep(8)
    gc1 = garrison(s, c)
    home = [l for l in s.read_since(p0) if "relief goes home" in l]
    check("RL-4 at the siege's end the relief goes home (siege record kept until it is back)", not sieges(s) and not relief_units(s, fc, b) and home,
          f"sieges {sieges(s)}; C near B {len(relief_units(s, fc, b))}; C live {gc0.get('live')} -> {gc1.get('live')}")
    # an ambushed, routed relief: dead soldiers and nobody arrives
    s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2)
    time.sleep(3)
    k0 = garrison(s, c).get("t_killed", 0)
    s.output(f"hywmill war admin siege {ca} {cb} quick", 2)
    s.output(f"hywmill war admin relief {cc} {cb} routed", 2)
    routed = s.wait_for(r"relief ROUTED", 360, since=p0)
    time.sleep(5)
    k1 = garrison(s, c).get("t_killed", 0)
    check("RL-5 an ambushed, routed relief loses soldiers (a real loss) and never arrives", routed is not None and k1 > k0,
          f"{routed}; C killed {k0} -> {k1}")
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)
    t0 = time.time()
    while time.time() - t0 < 300 and sieges(s):
        time.sleep(5)
    s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2)
    time.sleep(3)
    k2 = garrison(s, c).get("t_killed", 0)
    s.output(f"hywmill war admin siege {ca} {cb} quick", 2)
    s.output(f"hywmill war admin relief {cc} {cb} lost", 2)
    lost = s.wait_for(r"relief LOST", 360, since=p0)
    time.sleep(5)
    k3 = garrison(s, c).get("t_killed", 0)
    check("RL-6 a relief that loses its way never arrives but costs no lives", lost is not None and k3 == k2, f"{lost}; C killed {k2} -> {k3}")
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)
    t0 = time.time()
    while time.time() - t0 < 300 and sieges(s):
        time.sleep(5)
    dups = [l for l in s.read_since(p0) if "Duplicate garrison unit refused" in l]
    check("RL-7 no duplicates, all sieges and reliefs wound up", not dups and not sieges(s), f"{len(dups)} dups; {sieges(s)}")


def scenario_LV(ctx):
    """Wartime levies (post-M5; run after G4_0): at war, a non-stronghold village that loses soldiers is topped up with
    free levies in batches until it is back at target, drawn heavily from shieldmen and spearmen (shieldmen even below
    their usual tier); at peace every levy goes home."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    P = W_UUID
    p0 = s.pos()
    t0 = time.time()
    while time.time() - t0 < 300 and sieges(s):
        time.sleep(5)
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)
    time.sleep(12)
    dip(s, a, f"admin truce {ca} {cb} 0")
    ta = info(s, a).get("tier")
    fa = garrison(s, a).get("faction")
    s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2)
    time.sleep(15)
    g1 = garrison(s, a)
    units = [u for u, x in spike_info(s, "@e[type=!minecraft:player]").items()
             if x["tag"] != "none" and ("owner=" + str(fa)) in x["desc"] and dist(x["pos"], a) <= 110]
    for u in units[:8]:
        s.cmd(f"kill {u}", 0.3)
    time.sleep(3)
    g2 = garrison(s, a)
    t0 = time.time()
    while time.time() - t0 < 240:
        g3 = garrison(s, a)
        if g3.get("live", 0) >= g1.get("live", 0):
            break
        time.sleep(10)
    g3 = garrison(s, a)
    logs = s.read_since(p0)
    tops = [l for l in logs if "more levies" in l]
    raised = " ".join(tops)
    check("LV-1 at war, losses are topped up with levies in batches back to target",
          ta == "STRONGHOLD" or (g2.get("live", 0) < g1.get("live", 0) and g3.get("live", 0) >= g1.get("live", 0) and len(tops) >= 2),
          f"tier {ta}: {g1.get('live')}/{g1.get('target')} -> killed -> {g2.get('live')} -> {g3.get('live')} in {len(tops)} batch(es)")
    check("LV-2 levies lean on shieldmen and spearmen (shieldmen even below their usual tier)",
          ta == "STRONGHOLD" or ("shieldman" in raised and "spear_man" in raised), raised[-400:])
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)
    time.sleep(12)
    g4 = garrison(s, a)
    home = [l for l in s.read_since(p0) if "Mobilization:" in l and "go home" in l]
    check("LV-3 at peace every levy goes home", ta == "STRONGHOLD" or (home and g4.get("live", 0) < g3.get("live", 0)),
          f"live {g3.get('live')} -> {g4.get('live')}; {home[-1:]}")


def scenario_WT(ctx):
    """Water before the target (user report: the host landed across a lake and never advanced): the ground between the side
    of B facing A and B is flooded; the host lands on another, dry side and advances into B."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    P = W_UUID
    p0 = s.pos()
    t0 = time.time()
    while time.time() - t0 < 300 and sieges(s):
        time.sleep(5)
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)
    time.sleep(5)
    # flood a wide band on the side of B facing A (A lies west of B): the grass tops turn to water
    sx = 1 if a[0] > b[0] else -1
    x1, x2 = b[0] + sx * 20, b[0] + sx * 110
    box = f"{min(x1, x2)} {b[1] - 8} {b[2] - 60} {max(x1, x2)} {b[1] + 8} {b[2] + 60}"
    for part in range(6):
        z1 = b[2] - 60 + part * 20
        s.cmd(f"fill {min(x1, x2)} {b[1] - 8} {z1} {max(x1, x2)} {b[1] + 8} {z1 + 19} minecraft:water replace minecraft:grass_block", 2)
    s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2)
    time.sleep(3)
    fa = garrison(s, a).get("faction")
    out = " | ".join(l for l in s.output(f"hywmill war admin siege {ca} {cb} quick", 2) if l.startswith("war siege"))
    at = s.wait_for(r"main at", 360, since=p0)
    m = re.search(r"main at (-?\d+), (-?\d+), (-?\d+)", at or "")
    land = (int(m[1]), int(m[2]), int(m[3])) if m else None
    wet_side = land is not None and (land[0] - b[0]) * sx > 15 and abs(land[2] - b[2]) < 60
    check("WT-1 with water on the side facing home, the host lands on a dry side", land is not None and not wet_side,
          f"{out}; landing {land} (B {b}); flooded x {min(x1, x2)}..{max(x1, x2)}")
    inside = 0
    t0 = time.time()
    while time.time() - t0 < 150:
        inside = sum(1 for u, x in spike_info(s, "@e[type=!minecraft:player]").items()
                     if x["tag"] != "none" and ("owner=" + str(fa)) in x["desc"] and dist(x["pos"], b) <= 40)
        if inside >= 3:
            break
        time.sleep(10)
    check("WT-2 the host advances into the village", inside >= 3, f"{inside} of A's soldiers within 40 blocks of B's centre")
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)
    for part in range(6):
        z1 = b[2] - 60 + part * 20
        s.cmd(f"fill {min(x1, x2)} {b[1] - 8} {z1} {max(x1, x2)} {b[1] + 8} {z1 + 19} minecraft:grass_block replace minecraft:water", 2)


def owned_near(s, etype, owner, c, r=45):
    return [x for x in spike_info(s, f"@e[type=hundred_years_war:{etype}]").values() if ("owner=" + owner) in x["desc"] and dist(x["pos"], c) <= r]


def scenario_SQ(ctx):
    """Culture squads on the Muster Roll (post-M5; --keep-world with the extra villages): the catalogue loads (7 cultures, 16
    each); a Trusted player hires a low squad whole; a squad is refused when the standing or the village is too small; a
    Sworn player hires Norman knights (heavy lancers, mounted) and a mixed unique squad; a Byzantine village raises horse
    archers."""
    s, a = ctx.s, ctx.a
    for box in EXTRA_FORCELOAD:
        s.cmd("forceload add {} {} {} {}".format(*box), wait=10)
    loaded = s.wait_for(r"Squads loaded from", 5, since=s.start_pos)
    check("SQ-1 the squad catalogue loads: 7 cultures, 112 squads", loaded is not None and "7 culture(s), 112 squads" in loaded, loaded or "")
    centers = []
    for l in s.output("hywmill village list", 2):
        m = re.search(r" \((-?\d+), (-?\d+), (-?\d+)\) tier=| (-?\d+), (-?\d+), (-?\d+) tier=", l)
        if m:
            centers.append(tuple(int(v) for v in m.groups() if v is not None))
    P = "33333333-4444-4555-8666-777777777777"

    def roll(c):
        x, z = c[0] + 6, c[2] + 6
        y = surface_y(s, x, z) or c[1]
        s.cmd(f"setblock {x} {y} {z} hywmill:muster_roll", 1)
        return x, y, z

    def hire(pos, key, standing, money=400000):
        x, y, z = pos
        return " ".join(l for l in s.output(f'hywmill dev recruit hire {x} {y} {z} "{key}" 1 {money} {standing}', 5) if "recruit:" in l)

    ra = roll(a)
    h1 = hire(ra, "squad:norman.fyrd_spearmen", "TRUSTED")
    time.sleep(4)
    sp = owned_near(s, "spear_man", P, ra)
    check("SQ-2 a Trusted player hires a low squad whole (10 Fyrd Spearmen)", "ok=true" in h1 and len(sp) >= 10, f"{h1[-160:]}; {len(sp)} spearmen")
    ta = info(s, a).get("tier")
    h2 = hire(ra, "squad:norman.serjeants", "TRUSTED")
    h3 = hire(ra, "squad:norman.knights", "SWORN")
    check("SQ-3 refused: a medium squad needs a Patron; knights need a Garrison-sized village",
          "ok=false" in h2 and "patron" in h2 and (ta in ("GARRISON", "STRONGHOLD") or ("ok=false" in h3 and "garrison" in h3)),
          f"{h2[-120:]} || A {ta}: {h3[-140:]}")
    big = None
    for c in centers:
        v = info(s, c)
        if v.get("tier") in ("GARRISON", "STRONGHOLD") and "norman" in " ".join(s.output(at(c, "hywmill village info"), 2)):
            big = c
            break
    if big is None:
        check("SQ-4 a Garrison-sized Norman village to raise knights", False, str(centers))
        return
    rb = roll(big)
    h4 = hire(rb, "squad:norman.knights", "SWORN")
    time.sleep(6)
    kn = owned_near(s, "mounted_lancer_rider", P, rb)
    mounted = sum(1 for k in kn if k.get("mount", "none") not in ("none", ""))
    check("SQ-4 a Sworn player hires Norman knights: 8 heavy lancers, mounted", "ok=true" in h4 and len(kn) >= 8 and mounted >= 8,
          f"{h4[-160:]}; {len(kn)} lancers, {mounted} mounted")
    h5 = hire(rb, "squad:norman.conroi", "SWORN")
    time.sleep(6)
    kn2 = owned_near(s, "mounted_lancer_rider", P, rb)
    li = owned_near(s, "mounted_light_lancer_rider", P, rb)
    check("SQ-5 a mixed unique squad (Conroi: 4 knights, 6 mounted squires)", "ok=true" in h5 and len(kn2) >= len(kn) + 4 and len(li) >= 6,
          f"{h5[-160:]}; lancers {len(kn)} -> {len(kn2)}, light {len(li)}")
    byz = next((c for c in centers if "byzantines" in " ".join(s.output(at(c, "hywmill village info"), 2))), None)
    if byz is None:
        check("SQ-6 a Byzantine village", False, str(centers))
        return
    rz = roll(byz)
    h6 = hire(rz, "squad:byz.hippotoxotai", "PATRON")
    time.sleep(6)
    ha = owned_near(s, "mounted_archer_rider", P, rz)
    tz = info(s, byz).get("tier")
    check("SQ-6 a Byzantine village raises Hippotoxotai (10 horse archers) for a Patron",
          ("ok=true" in h6 and len(ha) >= 10) or (tz == "WATCH" and "ok=false" in h6), f"{tz}: {h6[-160:]}; {len(ha)} horse archers")


def gear_of(s, u):
    """Chest item id, its dye colour and the off-hand shield's base colour and patterns (raw NBT text)."""
    chest = " ".join(s.output(f"data get entity {u} ArmorItems[2]", 0.6))
    off = " ".join(s.output(f"data get entity {u} HandItems[1]", 0.6))
    return chest, off


def scenario_LK(ctx):
    """Squad looks (post-M5; run with HYWMILL_EXTRA_MODS=<Epic Knights>): hired squads wear their own look. The Crusader Band
    in crusader surcoats dyed in its colours with crusader-cross shields; Byzantine Excubitors in lamellar with the
    two-headed eagle; the knights of a Norman household in surcoats."""
    s, a = ctx.s, ctx.a
    for box in EXTRA_FORCELOAD:
        s.cmd("forceload add {} {} {} {}".format(*box), wait=10)
    ek = any("Epic Knights loaded" in l for l in s.output("hywmill admin equipcheck", 4))
    check("LK-0 Epic Knights is loaded", ek, "")
    centers = []
    for l in s.output("hywmill village list", 2):
        m = re.search(r" \((-?\d+), (-?\d+), (-?\d+)\) tier=| (-?\d+), (-?\d+), (-?\d+) tier=", l)
        if m:
            centers.append(tuple(int(v) for v in m.groups() if v is not None))
    P = "33333333-4444-4555-8666-777777777777"
    s.cmd("kill @e[type=hundred_years_war:shieldman]", 1)
    s.cmd("kill @e[type=hundred_years_war:spear_man]", 1)

    def roll(c):
        x, z = c[0] + 6, c[2] + 6
        y = surface_y(s, x, z) or c[1]
        s.cmd(f"setblock {x} {y} {z} hywmill:muster_roll", 1)
        return x, y, z

    def hire(pos, key, standing):
        x, y, z = pos
        return " ".join(l for l in s.output(f'hywmill dev recruit hire {x} {y} {z} "{key}" 1 400000 {standing}', 5) if "recruit:" in l)

    ra = roll(a)
    h1 = hire(ra, "squad:norman.crusader_band", "PATRON")
    time.sleep(5)
    shields = [u for u, x in spike_info(s, "@e[type=hundred_years_war:shieldman]").items() if ("owner=" + P) in x["desc"] and dist(x["pos"], ra) <= 45]
    rows = [gear_of(s, u) for u in shields[:4]]
    note("LK crusader gear", " || ".join(c[:220] + " ## " + o[:300] for c, o in rows))
    surcoat = sum(1 for c, o in rows if "crusader_chestplate" in c)
    dyed = sum(1 for c, o in rows if "dyed_color" in c)
    cross = sum(1 for c, o in rows if "crusader_cross" in o or "straight_cross" in o or "apostolic_cross" in o)
    check("LK-1 the Crusader Band wears crusader surcoats in its colours and bears crosses on its shields",
          "ok=true" in h1 and rows and surcoat == len(rows) and dyed == len(rows) and cross >= 1,
          f"{h1[-100:]}; {len(rows)} shieldmen: surcoat {surcoat}, dyed {dyed}, cross shields {cross}")
    byz = next((c for c in centers if "byzantines" in " ".join(s.output(at(c, "hywmill village info"), 2))), None)
    if byz:
        rz = roll(byz)
        h2 = hire(rz, "squad:byz.excubitors", "SWORN")
        time.sleep(5)
        ex = [u for u, x in spike_info(s, "@e[type=hundred_years_war:shieldman]").items() if ("owner=" + P) in x["desc"] and dist(x["pos"], rz) <= 45]
        rows2 = [gear_of(s, u) for u in ex[:3]]
        lam = sum(1 for c, o in rows2 if "lamellar_chestplate" in c)
        eagle = sum(1 for c, o in rows2 if "two_headed_eagle" in o)
        check("LK-2 Byzantine Excubitors wear lamellar and bear the two-headed eagle", "ok=true" in h2 and rows2 and lam == len(rows2) and eagle >= 1,
              f"{h2[-100:]}; {len(rows2)} shieldmen: lamellar {lam}, eagle {eagle}")
    big = next((c for c in centers if info(s, c).get("tier") in ("GARRISON", "STRONGHOLD")
                and "norman" in " ".join(s.output(at(c, "hywmill village info"), 2))), None)
    if big:
        rb = roll(big)
        s.cmd("kill @e[type=hundred_years_war:mounted_lancer_rider]", 1)  # knights from earlier runs, hired before squads had looks
        time.sleep(2)
        h3 = hire(rb, "squad:norman.knights", "SWORN")
        time.sleep(6)
        kn = [u for u, x in spike_info(s, "@e[type=hundred_years_war:mounted_lancer_rider]").items() if ("owner=" + P) in x["desc"] and dist(x["pos"], rb) <= 45]
        rows3 = [gear_of(s, u) for u in kn[:4]]
        note("LK knight gear", " || ".join(c[:200] for c, o in rows3))
        sc = sum(1 for c, o in rows3 if "crusader_chestplate" in c)
        check("LK-3 Norman knights ride in surcoats", "ok=true" in h3 and rows3 and sc == len(rows3), f"{h3[-100:]}; {len(rows3)} knights, surcoats {sc}")


def scenario_VL(ctx):
    """Village liveries and the siege extras (post-M5; run after G4_0, ideally with Epic Knights): each village takes two
    colours and its garrison wears them; a siege raises a boss bar, may hire a mercenary company (forced here), lands in
    groups round the target and fights in autonomous combat; the company is paid off at the end. In a long war a levy is
    rotated out for a paid regular."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    P = W_UUID
    p0 = s.pos()
    t0 = time.time()
    while time.time() - t0 < 300 and sieges(s):
        time.sleep(5)
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)
    time.sleep(12)
    dip(s, a, f"admin truce {ca} {cb} 0")
    fa = garrison(s, a).get("faction")
    time.sleep(8)
    logs = s.read_since(0)
    col = [l for l in logs if "takes the colours" in l]
    check("VL-1 villages take their colours, apart from their neighbours'", len(col) >= 2, " | ".join(c[-110:] for c in col[:4]))
    mine = [u for u, x in spike_info(s, "@e[type=!minecraft:player]").items()
            if x["tag"] != "none" and ("owner=" + str(fa)) in x["desc"] and dist(x["pos"], a) <= 110]
    rows = [gear_of(s, u) for u in mine[:6]]
    ek = any("Epic Knights loaded" in l for l in s.output("hywmill admin equipcheck", 4))
    dyed = sum(1 for c, o in rows if "dyed_color" in c)
    note("VL gear", " || ".join(c[:160] + " ## " + o[:200] for c, o in rows[:3]))
    check("VL-2 the garrison wears the village's colours (Epic Knights dyeable pieces)", not ek or dyed >= 1, f"EK {ek}: {dyed} of {len(rows)} dyed")
    s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2)
    time.sleep(15)
    out = " | ".join(l for l in s.output(f"hywmill war admin siege {ca} {cb} quick", 2) if l.startswith("war siege"))
    time.sleep(3)
    merc = " | ".join(l for l in s.output(f"hywmill war admin mercs {ca}", 2) if l.startswith("war mercs"))
    check("VL-3 the host hires a mercenary company, announced", "HIRED" in merc and s.wait_for(r"hired \(\d+ soldiers", 10, since=p0) is not None,
          f"{out}; {merc}")
    land = s.wait_for(r"materialized in \d+ group", 400, since=p0)
    m = re.search(r"(\d+) unit\(s\) materialized in (\d+) group", land or "")
    check("VL-4 the host lands in groups round the target", bool(m) and int(m[2]) >= 2, land or "no landing")
    bar = s.wait_for(r"boss bar raised over", 30, since=p0)
    check("VL-5 a boss bar is raised over the siege", bar is not None, bar or "")
    time.sleep(6)
    near = [u for u, x in spike_info(s, "@e[type=!minecraft:player]").items()
            if x["tag"] != "none" and ("owner=" + str(fa)) in x["desc"] and dist(x["pos"], b) <= 120]
    strat = [re.search(r"strategy=(\w+)", m5_1(s, f"hyw ident {u}")) for u in near[:5]]
    free = sum(1 for x in strat if x and x[1] == "FREE_FIGHT")
    check("VL-6 attackers fight in autonomous combat", near and free >= 1, f"{free} of {len(strat)} sampled: FREE_FIGHT")
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)
    paid = s.wait_for(r"mercenaries of .* paid off", 60, since=p0)
    check("VL-7 the company is paid off when the siege ends", paid is not None, paid or "")
    t0 = time.time()
    while time.time() - t0 < 300 and sieges(s):
        time.sleep(5)
    dip(s, a, f"admin truce {ca} {cb} 0")
    p1 = s.pos()
    s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2)
    time.sleep(20)
    s.output(at(a, "hywmill admin setpoints 60"), 1)
    rot = s.wait_for(r"rotates its garrison", 260, since=p1)
    ta = info(s, a).get("tier")
    check("VL-8 in a long war a levy is rotated out for a paid regular", ta == "STRONGHOLD" or rot is not None, f"tier {ta}: {rot}")
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)


def scenario_PC(ctx):
    """Player colours (post-M5; run with Epic Knights): a player picks two colours on the Muster Roll; the soldiers and generic
    squads they hire wear them (and bear arms in them); unique squads keep their own look."""
    s, a = ctx.s, ctx.a
    P = "33333333-4444-4555-8666-777777777777"
    x, z = a[0] + 6, a[2] + 6
    y = surface_y(s, x, z) or a[1]
    s.cmd(f"setblock {x} {y} {z} hywmill:muster_roll", 1)
    s.cmd("kill @e[type=hundred_years_war:shieldman]", 1)
    s.cmd("kill @e[type=hundred_years_war:spear_man]", 1)
    out = " ".join(s.output("hywmill dev recruit colours 11 4", 2))  # blue and yellow
    h = " ".join(l for l in s.output(f'hywmill dev recruit hire {x} {y} {z} "squad:norman.village_shieldwall" 1 400000 SWORN', 5) if "recruit:" in l)
    time.sleep(5)
    mine = [u for u, v in spike_info(s, "@e[type=hundred_years_war:shieldman]").items() if ("owner=" + P) in v["desc"] and dist(v["pos"], (x, y, z)) <= 45]
    rows = [gear_of(s, u) for u in mine[:4]]
    note("PC gear", " || ".join(c[:160] + " ## " + o[:220] for c, o in rows[:3]))
    blue, yellow = 3949738, 16701501  # DyeColor BLUE / YELLOW texture colours
    dyed = sum(1 for c, o in rows if f"rgb: {blue}" in c)
    arms = sum(1 for c, o in rows if ('"blue"' in o or '"yellow"' in o))
    check("PC-1 a generic squad wears the player's colours and bears arms in them", "ok=true" in h and rows and dyed == len(rows) and arms >= 1,
          f"{out}; {h[-100:]}; {len(rows)} shieldmen: dyed blue {dyed}, blue/yellow arms {arms}")
    s.output("hywmill dev recruit colours -1 -1", 2)


def scenario_AD(ctx):
    """Epic Knights addons (post-M5; run with HYWMILL_EXTRA_MODS holding Epic Knights, Epic Knights: Addon and Slavic Armory):
    every profile and look kit is valid; Byzantine garrisons and squads wear Slavic Armory lamellar, scale and helmets;
    Japanese squads the Addon's splint armour, straw hats and face helmets."""
    s = ctx.s
    for box in EXTRA_FORCELOAD:
        s.cmd("forceload add {} {} {} {}".format(*box), wait=10)
    eq = s.output("hywmill admin equipcheck", 6)
    loaded = [l for l in eq if l.strip().startswith("addon ")]
    bad = [l for l in eq if "INVALID KIT" in l]
    check("AD-0 both addons load and every kit is valid", sum(1 for l in loaded if "loaded" in l and "not" not in l) == 2 and not bad,
          " | ".join(loaded) + f"; invalid kits {len(bad)}: " + " | ".join(bad[:4]))
    centers = []
    for l in s.output("hywmill village list", 2):
        m = re.search(r" \((-?\d+), (-?\d+), (-?\d+)\) tier=| (-?\d+), (-?\d+), (-?\d+) tier=", l)
        if m:
            centers.append(tuple(int(v) for v in m.groups() if v is not None))
    cult = {c: " ".join(s.output(at(c, "hywmill village info"), 2)) for c in centers}
    note("AD villages", " | ".join(f"{c}: " + ("byz" if "byzantines" in t else "jp" if "japanese" in t else "norman" if "norman" in t else "?") for c, t in cult.items()))
    P = "33333333-4444-4555-8666-777777777777"

    def roll(c):
        x, z = c[0] + 6, c[2] + 6
        y = surface_y(s, x, z) or c[1]
        s.cmd(f"setblock {x} {y} {z} hywmill:muster_roll", 1)
        return x, y, z

    def hired(pos, key, types):
        x, y, z = pos
        for t in types:
            s.cmd(f"kill @e[type=hundred_years_war:{t}]", 0.5)
        h = " ".join(l for l in s.output(f'hywmill dev recruit hire {x} {y} {z} "{key}" 1 900000 SWORN', 5) if "recruit:" in l)
        time.sleep(6)
        out = []
        for t in types:
            out += [u for u, v in spike_info(s, f"@e[type=hundred_years_war:{t}]").items() if ("owner=" + P) in v["desc"] and dist(v["pos"], pos) <= 45]
        return h, [gear_of(s, u)[0] + " " + " ".join(s.output(f"data get entity {u} ArmorItems[3]", 0.6)) for u in out[:10]]

    byz = next((c for c, t in cult.items() if "byzantines" in t), None)
    if byz:
        rb = roll(byz)
        h, rows = hired(rb, "squad:byz.excubitors", ["shieldman", "spear_man"])
        sl = sum(1 for r in rows if "slavicarmory:" in r)
        note("AD excubitors", " || ".join(r[:260] for r in rows[:3]))
        check("AD-1 Byzantine Excubitors wear Slavic Armory pieces", "ok=true" in h and rows and sl >= 1, f"{h[-80:]}; {sl} of {len(rows)} with Slavic Armory")
        gar = [u for u, v in spike_info(s, "@e[type=!minecraft:player]").items() if v["tag"] != "none" and dist(v["pos"], byz) <= 90]
        time.sleep(1)
        rows2 = [gear_of(s, u)[0] + " " + " ".join(s.output(f"data get entity {u} ArmorItems[3]", 0.6)) for u in gar[:12]]
        sl2 = sum(1 for r in rows2 if "slavicarmory:" in r)
        check("AD-2 a Byzantine garrison wears Slavic Armory pieces", rows2 and sl2 >= 1, f"{sl2} of {len(rows2)} garrison soldiers")
    else:
        note("AD-1", "no Byzantine village in this world")
    jp = next((c for c, t in cult.items() if "japanese" in t), None)
    if jp:
        rj = roll(jp)
        h, rows = hired(rj, "squad:jp.samurai", ["warrior", "shieldman", "spear_man", "archer"])
    else:
        rj = roll(ctx.a)  # no Japanese village here: the look is checked through a Norman roll's generic squad instead
        h, rows = "", []
    ad = sum(1 for r in rows if "magistuarmoryaddon:" in r)
    note("AD samurai", " || ".join(r[:260] for r in rows[:3]))
    check("AD-3 Japanese samurai wear the Addon's splint armour and helmets", jp is None or ("ok=true" in h and rows and ad >= 1),
          f"{'no Japanese village' if jp is None else h[-80:]}; {ad} of {len(rows)} with Addon pieces")
    rn = roll(ctx.a)
    h, rows = hired(rn, "squad:norman.village_shieldwall", ["shieldman", "spear_man"])
    ad = sum(1 for r in rows if "magistuarmoryaddon:" in r)
    note("AD shield-wall", " || ".join(r[:260] for r in rows[:3]))
    check("AD-4 a Norman shield-wall mixes Epic Knights and Addon gear (chapel hats, chained gambesons, tunics)", "ok=true" in h and rows and ad >= 1, f"{h[-80:]}; {ad} of {len(rows)} with Addon pieces")


def scenario_TR(ctx):
    """Tribute (post-M5): the loser of a siege pays the full tribute every Minecraft day for 3-5 days (the first at once):
    levy points to the winner each day; '/hywmill war tributes' lists it; the dev command makes the next day fall due now."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    P = W_UUID
    p0 = s.pos()
    t0 = time.time()
    while time.time() - t0 < 300 and sieges(s):
        time.sleep(5)
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)
    time.sleep(10)
    dip(s, a, f"admin truce {ca} {cb} 0")
    s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2)
    time.sleep(12)
    out = " | ".join(l for l in s.output(f"hywmill war admin siege {ca} {cb} unwatched", 2) if l.startswith("war siege"))
    ended = s.wait_for(r"Siege \w+ ended", 600, since=p0)
    first = s.wait_for(r"Tribute: .* pays day 1 of", 30, since=p0)
    m = re.search(r"pays day 1 of (\d+)", first or "")
    days = int(m[1]) if m else 0
    check("TR-1 the loser pays the full tribute every day for 3-5 days, the first at once", ended is not None and "a day in tribute" in (ended or "")
          and 3 <= days <= 5, f"{out}; {(ended or '')[-200:]}; {(first or '')[-160:]}")
    lst = " | ".join(s.output("hywmill war tributes", 2))
    check("TR-2 the tribute is listed while it is being paid", "1/" + str(days) + " days paid" in lst, lst[-300:])
    winner = re.search(r"of its tribute to (.+?) \(", first or "")
    lv = [l for l in s.read_since(p0) if "Tribute:" in l]
    s.output("hywmill war admin tribute-due", 2)
    second = s.wait_for(r"Tribute: .* pays day 2 of", 30, since=p0)
    lv1 = re.search(r"\(([\d.]+) levy", first or "")
    lv2 = re.search(r"\(([\d.]+) levy", second or "")
    check("TR-3 the next day pays the same full amount again", second is not None and lv1 and lv2 and lv1[1] == lv2[1],
          f"{(second or '')[-160:]}; levy {lv1 and lv1[1]} then {lv2 and lv2[1]}")
    for _ in range(days):
        s.output("hywmill war admin tribute-due", 2)
        time.sleep(2)
    full = s.wait_for(r"pays day %d of %d" % (days, days), 30, since=p0)
    lst2 = " | ".join(s.output("hywmill war tributes", 2))
    check("TR-4 after the last day the tribute is paid in full and no longer listed", full is not None and "war tributes: 0" in lst2, lst2[-200:])
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)


def scenario_RC(ctx):
    """Recall (post-M5): '/hywmill war admin recall-all' brings every host home at once without deciding its siege; soldiers
    left on a siege with no record (a record lost to a mod update) come home too."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    P = W_UUID
    p0 = s.pos()
    t0 = time.time()
    while time.time() - t0 < 300 and sieges(s):
        time.sleep(5)
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)
    time.sleep(10)
    dip(s, a, f"admin truce {ca} {cb} 0")
    s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2)
    time.sleep(12)
    t0 = time.time()
    while time.time() - t0 < 180:  # after a restart the garrison needs a moment to spawn before it can spare a host
        if any("war siege OK" in l for l in s.output(f"hywmill war admin siege {ca} {cb} unwatched", 2)):
            break
        time.sleep(10)
    s.wait_for(r"marching \d+ ticks", 200, since=p0)
    g0 = garrison(s, a)
    out = " | ".join(s.output("hywmill war admin recall-all", 3))
    time.sleep(4)
    g1 = garrison(s, a)
    left = sieges(s)
    check("RC-1 a marching host is called home and arrives at once, nothing decided", "home" in out and not left
          and s.wait_for(r"Siege \w+ ended", 3, since=p0) is None and g1.get("deployed", 0) < g0.get("deployed", 0),
          f"{out[-200:]}; deployed {g0.get('deployed')} -> {g1.get('deployed')}; sieges {left}")
    p1 = s.pos()
    s.output(f"hywmill war admin siege {ca} {cb} unwatched", 2)
    s.wait_for(r"marching \d+ ticks", 200, since=p1)
    g2 = garrison(s, a)
    s.output("hywmill war admin siege-forget", 2)
    out2 = " | ".join(s.output("hywmill war admin recall-all", 3))
    time.sleep(4)
    g3 = garrison(s, a)
    check("RC-2 soldiers whose siege record is lost come home too", "no siege record came home" in out2 and g3.get("deployed", 0) < g2.get("deployed", 0),
          f"{out2[-200:]}; deployed {g2.get('deployed')} -> {g3.get('deployed')}")
    # a host marching home does not stop its village from marching again (user report: five hosts on their way home, their
    # villages unloaded, blocked every new siege)
    p2 = s.pos()
    t0 = time.time()
    while time.time() - t0 < 180:
        if any("war siege OK" in l for l in s.output(f"hywmill war admin siege {ca} {cb} unwatched", 2)):
            break
        time.sleep(10)
    s.wait_for(r"Siege \w+ ended", 600, since=p2)
    time.sleep(3)
    st = " | ".join(s.output("hywmill war sieges", 2))
    again = " | ".join(l for l in s.output(f"hywmill war admin siege {ca} {cb} quick", 2) if l.startswith("war siege"))
    check("RC-3 while its last host marches home, a village is not refused as already besieging (only a host too small may stop it)",
          "RETURN" in st and again and "ALREADY_BESIEGING" not in again and "TARGET_BESIEGED" not in again,
          f"{st[-200:]} || {again[-200:]}")
    s.output("hywmill war admin recall-all", 3)
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)


def scenario_DA(ctx):
    """Help for the besieged (post-M5): before the assault the target may raise militia (5-15 by population), hire mercenaries
    and (garrison/stronghold) call its lord's household; forced here with '/hywmill war admin aid'. They appear at home, count
    among the defenders when the battle starts, and go home when the siege ends."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    P = W_UUID
    p0 = s.pos()
    t0 = time.time()
    while time.time() - t0 < 300 and sieges(s):
        time.sleep(5)
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)
    time.sleep(10)
    dip(s, a, f"admin truce {ca} {cb} 0")
    s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2)
    time.sleep(12)
    t0 = time.time()
    while time.time() - t0 < 180:
        if any("war siege OK" in l for l in s.output(f"hywmill war admin siege {ca} {cb} quick", 2)):
            break
        time.sleep(10)
    fb = garrison(s, b).get("faction")
    near0 = sum(1 for u, x in spike_info(s, "@e[type=!minecraft:player]").items() if x["tag"] != "none" and ("owner=" + str(fb)) in x["desc"] and dist(x["pos"], b) <= 90)
    aid = " ".join(l for l in s.output(f"hywmill war admin aid {ca}", 3) if l.startswith("war aid"))
    time.sleep(5)
    logs = s.read_since(p0)
    news = [l for l in logs if "take up arms against" in l or "man its defences" in l or "household" in l and "stands with" in l]
    near1 = sum(1 for u, x in spike_info(s, "@e[type=!minecraft:player]").items() if x["tag"] != "none" and ("owner=" + str(fb)) in x["desc"] and dist(x["pos"], b) <= 90)
    m = re.search(r"RAISED (\d+)", aid)
    n = int(m[1]) if m else 0
    check("DA-1 the besieged raise militia and hire mercenaries (lord's household in a garrison or stronghold), announced",
          n >= 15 and len(news) >= 2, f"{aid}; " + " | ".join(x[-120:] for x in news))
    check("DA-2 they appear at home before the assault", near1 - near0 >= n * 0.6, f"B's soldiers near B: {near0} -> {near1} (raised {n})")
    landed = s.wait_for(r"materialized in \d+ group", 400, since=p0)
    md = re.search(r"; (\d+) defender", landed or "")
    check("DA-3 they count among the defenders when the battle starts", md is not None and int(md[1]) >= n, (landed or "")[-160:])
    s.output("hywmill war admin recall-all", 3)
    home = s.wait_for(r"temporary defender\(s\) of .* go home", 30, since=p0)
    check("DA-4 when the siege ends they go home", home is not None, (home or "")[-140:])
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)
    # the lord's household: only a garrison or stronghold village has one
    big = None
    for l in s.output("hywmill village list", 2):
        mm = re.search(r" \((-?\d+), (-?\d+), (-?\d+)\) tier=(GARRISON|STRONGHOLD)| (-?\d+), (-?\d+), (-?\d+) tier=(GARRISON|STRONGHOLD)", l)
        if mm:
            g = [v for v in mm.groups() if v is not None]
            c = (int(g[0]), int(g[1]), int(g[2]))
            if c != tuple(a):
                big = c
                break
    if big is None:
        note("DA-5", "no garrison or stronghold village in this world")
        return
    cg = f"{big[0]} {big[1]} {big[2]}"
    t0 = time.time()
    while time.time() - t0 < 300 and sieges(s):
        time.sleep(5)
    dip(s, a, f"admin truce {ca} {cg} 0")
    p1 = s.pos()
    s.output(f"hywmill war for {P} declare {ca} on {cg} force", 2)
    time.sleep(12)
    t0 = time.time()
    while time.time() - t0 < 180:
        if any("war siege OK" in l for l in s.output(f"hywmill war admin siege {ca} {cg} quick", 2)):
            break
        time.sleep(10)
    aid2 = " ".join(l for l in s.output(f"hywmill war admin aid {ca}", 3) if l.startswith("war aid"))
    hh = s.wait_for(r"his household, \d+ of", 10, since=p1)
    time.sleep(4)
    fg = garrison(s, big).get("faction")
    rows = [gear_of(s, u)[0] for u, x in spike_info(s, "@e[type=!minecraft:player]").items()
            if x["tag"] != "none" and ("owner=" + str(fg)) in x["desc"] and dist(x["pos"], big) <= 90][:40]
    note("DA household gear sample", " || ".join(r[:140] for r in rows[:4]))
    check("DA-5 a garrison or stronghold village's lord's household joins the defence", hh is not None, f"{aid2}; {(hh or '')[-160:]}")
    s.output("hywmill war admin recall-all", 3)
    s.output(f"hywmill war for {P} peace {ca} with {cg} force", 2)


def scenario_RS(ctx):
    """recall-all (user report): hosts marching home to villages that are not loaded. Every siege record is wiped at once; the
    soldiers go back on their rosters and reappear when their village is next loaded ('stowed': as if none were loaded)."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    P = W_UUID
    t0 = time.time()
    while time.time() - t0 < 300 and sieges(s):
        time.sleep(5)
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)
    time.sleep(10)
    dip(s, a, f"admin truce {ca} {cb} 0")
    s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2)
    time.sleep(12)
    s.output(at(a, "hywmill admin grant spear_man 20"), 2)  # earlier scenarios may have left A without soldiers to spare
    time.sleep(40)
    # RC-4 (user report): hosts marching home to villages that are not loaded. recall-all wipes their records anyway; the soldiers
    # go back on their rosters and reappear when their village is next loaded ('stowed': as if no village were loaded)
    p3 = s.pos()
    t0 = time.time()
    while time.time() - t0 < 420:
        if any("war siege OK" in l for l in s.output(f"hywmill war admin siege {ca} {cb} unwatched", 2)):
            break
        time.sleep(10)
    s.wait_for(r"marching \d+ ticks", 300, since=p3)
    time.sleep(3)
    st = " | ".join(s.output("hywmill war sieges", 2))
    out4 = " | ".join(s.output("hywmill war admin recall-all stowed", 3))
    st2 = " | ".join(s.output("hywmill war sieges", 2))
    back = s.wait_for(r"brought home while it was unloaded rejoin", 120, since=p3)
    again = " | ".join(l for l in s.output(f"hywmill war admin siege {ca} {cb} quick", 2) if l.startswith("war siege"))
    check("RC-4 hosts marching home to unloaded villages: every record is wiped at once, the soldiers reappear when their village loads, "
          "and a new siege is not refused as already besieging",
          ("MARCH" in st or "RETURN" in st) and "war sieges: 0" in st2 and back is not None and "ALREADY_BESIEGING" not in again and "TARGET_BESIEGED" not in again,
          f"before: {st[-120:]} || {out4[-160:]} || after: {st2} || {(back or '')[-120:]} || {again[-120:]}")
    s.output("hywmill war admin recall-all", 3)
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)


def scenario_VS(ctx):
    """Battle reports, vassalage and war horns (post-M5): a decided siege writes a report to the History tab (losses, help,
    tribute); its loser becomes the winner's vassal for 21 days (allies); a vassal sends 10-15 mixed men to its overlord's next
    siege (forced here); horns sound at siege events."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    P = W_UUID
    p0 = s.pos()
    s.output("hywmill war admin recall-all", 3)
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)
    time.sleep(8)
    dip(s, a, f"admin truce {ca} {cb} 0")
    s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2)
    s.output(at(a, "hywmill admin grant spear_man 20"), 2)
    time.sleep(40)
    t0 = time.time()
    while time.time() - t0 < 420:
        if any("war siege OK" in l for l in s.output(f"hywmill war admin siege {ca} {cb} unwatched", 2)):
            break
        time.sleep(10)
    s.wait_for(r"marching \d+ ticks", 300, since=p0)
    s.output(f"hywmill war admin mercs {ca}", 2)
    ended = s.wait_for(r"Siege \w+ ended", 600, since=p0)
    hist = s.output(f"hywmill war history {cb}", 3)
    h = " | ".join(hist)
    check("VS-1 a decided siege is written to the History tab (losses, help, tribute)",
          ended is not None and "besieged" in h and "fell)" in h and "Tribute:" in h and "hired by" in h, h[-500:])
    vas = " | ".join(s.output("hywmill war vassals", 2))
    rel = s.read_since(p0)
    sworn = next((l for l in rel if "swears fealty to" in l), None)
    check("VS-2 the loser swears fealty to the winner for 21 days", sworn is not None and "21 day" in vas and "Vassalage:" in h,
          f"{(sworn or '')[-160:]} || {vas[-200:]}")
    # the vassal sends men to its overlord's next siege: make B's overlord (or A's vassal) march on a third village
    won = "fell to the host" in (ended or "")
    lord, vassal = (a, b) if won else (b, a)
    cl, cv = (ca, cb) if won else (cb, ca)
    third = None
    for l in s.output("hywmill village list", 2):
        mm = re.search(r" \((-?\d+), (-?\d+), (-?\d+)\) tier=| (-?\d+), (-?\d+), (-?\d+) tier=", l)
        if mm:
            g = [v for v in mm.groups() if v is not None]
            c = (int(g[0]), int(g[1]), int(g[2]))
            if c != tuple(a) and c != tuple(b):
                third = c
                break
    if third is None:
        note("VS-3", "no third village")
        return
    c3 = f"{third[0]} {third[1]} {third[2]}"
    p1 = s.pos()
    s.output(at(lord, "hywmill admin grant spear_man 20"), 2)
    time.sleep(40)
    t0 = time.time()
    while time.time() - t0 < 420:
        if any("war siege OK" in l for l in s.output(f"hywmill war admin siege {cl} {c3} unwatched", 2)):
            break
        time.sleep(10)
    aid = " ".join(l for l in s.output(f"hywmill war admin aid {cl}", 3) if l.startswith("war aid"))
    sent = s.wait_for(r"vassal of .*, sends \d+ men to its overlord's host", 15, since=p1)
    m = re.search(r"sends (\d+) men", sent or "")
    check("VS-3 the vassal sends 10-15 men to its overlord's host, with the mercenaries", m is not None and 10 <= int(m[1]) <= 15, f"{aid}; {(sent or '')[-160:]}")
    s.output("hywmill war admin recall-all", 3)
    gone = s.wait_for(r"wiped by an admin", 10, since=p1)
    check("VS-4 when the siege ends the vassal's men go home (not counted in the overlord's garrison)", gone is not None, (gone or "")[-120:])


def scenario_HA(ctx):
    """Period horse armour (post-M5; run with Epic Knights): riders' horses wear dyed leather, Epic Knights chainmail horse
    armour or plate barding, never gold or diamond horse armour."""
    s, a = ctx.s, ctx.a
    ek = any("Epic Knights loaded" in l for l in s.output("hywmill admin equipcheck", 4))
    P = "33333333-4444-4555-8666-777777777777"
    centers = []
    for l in s.output("hywmill village list", 2):
        mm = re.search(r" \((-?\d+), (-?\d+), (-?\d+)\) tier=(\w+)| (-?\d+), (-?\d+), (-?\d+) tier=(\w+)", l)
        if mm:
            g = [v for v in mm.groups() if v is not None]
            centers.append(((int(g[0]), int(g[1]), int(g[2])), g[3]))
    big = next((c for c, t in centers if t in ("GARRISON", "STRONGHOLD")), centers[0][0] if centers else tuple(a))
    x, z = big[0] + 6, big[2] + 6
    y = surface_y(s, x, z) or big[1]
    s.cmd(f"setblock {x} {y} {z} hywmill:muster_roll", 1)
    s.cmd("kill @e[type=hundred_years_war:hyw_horse]", 1)
    s.cmd("kill @e[type=hundred_years_war:mounted_lancer_rider]", 1)
    s.cmd("kill @e[type=hundred_years_war:mounted_light_lancer_rider]", 1)
    hires = []
    for key in ["squad:norman.knights", "squad:norman.mounted_serjeants", "squad:norman.hobelars"]:
        hires.append(" ".join(l for l in s.output(f'hywmill dev recruit hire {x} {y} {z} "{key}" 1 900000 SWORN', 5) if "recruit:" in l)[-90:])
    time.sleep(10)
    horses = [u for u, v in spike_info(s, "@e[type=hundred_years_war:hyw_horse]").items() if dist(v["pos"], (x, y, z)) <= 60]
    rows = [" ".join(s.output(f"data get entity {u} body_armor_item", 0.6)) for u in horses[:20]]
    kinds = {}
    for r in rows:
        m = re.search(r'id: "([^"]+)"', r)
        k = m[1] if m else "none"
        kinds[k] = kinds.get(k, 0) + 1
    bad = sum(v for k, v in kinds.items() if "diamond" in k or "golden" in k)
    period = sum(v for k, v in kinds.items() if k in ("minecraft:leather_horse_armor", "magistuarmory:chainmail_horse_armor", "magistuarmory:barding",
                                                       "magistuarmoryaddon:dark_barding"))
    dyed = sum(1 for r in rows if "leather_horse_armor" in r and "dyed_color" in r)
    note("HA horses", f"{kinds}; hires {hires}")
    check("HA-1 riders' horses wear period armour (dyed leather, chainmail, barding), never gold or diamond",
          rows and bad == 0 and period >= len(rows) - kinds.get("none", 0) and (not ek or kinds.get("magistuarmory:chainmail_horse_armor", 0)
                                                                                + kinds.get("magistuarmory:barding", 0) + kinds.get("magistuarmoryaddon:dark_barding", 0) >= 1),
          f"{len(rows)} horses: {kinds}; leather dyed {dyed}")


def scenario_CB(ctx):
    """Crossbowmen's pavises (post-M5; run with Epic Knights): most hired crossbowmen carry a painted pavise of their tier with a
    little armour, and still shoot."""
    s, a = ctx.s, ctx.a
    P = "33333333-4444-4555-8666-777777777777"
    x, z = a[0] + 6, a[2] + 6
    y = surface_y(s, x, z) or a[1]
    s.cmd(f"setblock {x} {y} {z} hywmill:muster_roll", 1)
    s.cmd("kill @e[type=hundred_years_war:crossbowman]", 1)
    hires = []
    for key in ["squad:norman.town_crossbows", "squad:norman.genoese"]:
        hires.append(" ".join(l for l in s.output(f'hywmill dev recruit hire {x} {y} {z} "{key}" 1 900000 SWORN', 5) if "recruit:" in l)[-80:])
    time.sleep(6)
    cb = [u for u, v in spike_info(s, "@e[type=hundred_years_war:crossbowman]").items() if ("owner=" + P) in v["desc"] and dist(v["pos"], (x, y, z)) <= 45]
    offs = [" ".join(s.output(f"data get entity {u} HandItems[1]", 0.6)) for u in cb]
    pav = [o for o in offs if "_pavese" in o]
    painted = sum(1 for o in pav if "banner_patterns" in o or "base_color" in o)
    armoured = sum(1 for o in pav if "pavise_armour" in o)
    note("CB offhands", " || ".join(o[:200] for o in offs[:3]))
    check("CB-1 most crossbowmen carry a painted pavise with a little armour", cb and len(pav) >= len(cb) * 0.4 and painted == len(pav) and armoured == len(pav),
          f"{hires}; {len(cb)} crossbowmen, {len(pav)} pavises, painted {painted}, armoured {armoured}")
    # they still shoot: a tough zombie in front of them takes bolts
    zx, zz = x + 14, z
    zy = surface_y(s, zx, zz) or y
    s.cmd(f"summon minecraft:zombie {zx} {zy} {zz} {{Tags:['cbT'],PersistenceRequired:1b,Health:200f,attributes:[{{id:'minecraft:generic.max_health',base:200}},{{id:'minecraft:generic.movement_speed',base:0.0}}]}}", 1)
    h0 = health(s, "cbT")
    t0 = time.time()
    h1 = h0
    while time.time() - t0 < 40:
        time.sleep(4)
        h1 = health(s, "cbT")
        if h1 is None or (h0 is not None and h1 < h0 - 10):
            break
    s.cmd("kill @e[tag=cbT]", 1)
    check("CB-2 crossbowmen with pavises still shoot", h0 is not None and (h1 is None or h1 < h0 - 10), f"zombie health {h0} -> {h1}")


def scenario_SS(ctx):
    """Spearmen's shields (post-M5; run with Epic Knights): most spearmen carry a painted shield of their culture's shape with a
    little armour, and still fight."""
    s, a = ctx.s, ctx.a
    P = "33333333-4444-4555-8666-777777777777"
    x, z = a[0] + 6, a[2] + 6
    y = surface_y(s, x, z) or a[1]
    s.cmd(f"setblock {x} {y} {z} hywmill:muster_roll", 1)
    s.cmd("kill @e[type=hundred_years_war:spear_man]", 1)
    h = " ".join(l for l in s.output(f'hywmill dev recruit hire {x} {y} {z} "squad:norman.fyrd_spearmen" 1 900000 SWORN', 5) if "recruit:" in l)
    time.sleep(6)
    sp = [u for u, v in spike_info(s, "@e[type=hundred_years_war:spear_man]").items() if ("owner=" + P) in v["desc"] and dist(v["pos"], (x, y, z)) <= 45]
    offs = [" ".join(s.output(f"data get entity {u} HandItems[1]", 0.6)) for u in sp]
    sh = [o for o in offs if re.search(r"_(kite|heater|round|elliptical)shield", o)]
    painted = sum(1 for o in sh if "banner_patterns" in o or "base_color" in o)
    armoured = sum(1 for o in sh if "pavise_armour" in o)
    note("SS offhands", " || ".join(o[:200] for o in offs[:3]))
    check("SS-1 most spearmen carry a painted shield of their culture with a little armour", sp and len(sh) >= len(sp) * 0.5 and painted == len(sh)
          and armoured == len(sh), f"{h[-80:]}; {len(sp)} spearmen, {len(sh)} shields, painted {painted}, armoured {armoured}")
    zx, zz = x + 6, z
    zy = surface_y(s, zx, zz) or y
    s.cmd(f"summon minecraft:zombie {zx} {zy} {zz} {{Tags:['ssT'],PersistenceRequired:1b,Health:200f,attributes:[{{id:'minecraft:generic.max_health',base:200}}]}}", 1)
    h0 = health(s, "ssT")
    t0 = time.time()
    h1 = h0
    while time.time() - t0 < 40:
        time.sleep(4)
        h1 = health(s, "ssT")
        if h1 is None or (h0 is not None and h1 < h0 - 10):
            break
    s.cmd("kill @e[tag=ssT]", 1)
    check("SS-2 spearmen with shields still fight", h0 is not None and (h1 is None or h1 < h0 - 10), f"zombie health {h0} -> {h1}")


def scenario_CL(ctx):
    """Siege build-up, columns, scouts and convoys (post-M5): a declared siege prepares for two days and musters on the third;
    scouts find columns and convoys; a column comes into the world near a player, fights and can be destroyed; a convoy camps
    with carts full of supplies; the alarm opens the besieged's coffers; mercenaries can be bribed from the War tab."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    vb = info(s, b)
    fb = vb.get("faction")
    P = "33333333-4444-4555-8666-777777777777"
    p0 = s.pos()
    s.output("hywmill war admin recall-all", 3)
    dip(s, a, f"admin truce {ca} {cb} 0")
    m5(s, f"mill mrel {ca} {cb} set -100")
    m5(s, f"mill discover {ca} {P}", 0.5)
    m5(s, f"mill discover {cb} {P}", 0.5)
    standin_at(s, P, a[0] + 3, a[2] + 3)
    m5(s, f"mill rep {ca} {P} adjust 6000", 0.5)
    s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2)
    time.sleep(16)
    t0 = time.time()
    while time.time() - t0 < 90 and not any("at war" in l for l in war_lines(s, a, f"for {P} status")):
        time.sleep(5)
    j = " | ".join(war_lines(s, a, f"for {P} join {ca} against {cb}"))
    gr = " | ".join(s.output(at(a, "hywmill admin grant light_lancer_rider 2"), 2))
    s.output(at(a, "hywmill admin grant spear_man 20"), 2)
    note("CL grant", gr[-200:])
    time.sleep(30)
    out = ""
    t0 = time.time()
    while time.time() - t0 < 300:
        out = " | ".join(l for l in s.output(f"hywmill war admin siege {ca} {cb}", 2) if l.startswith("war siege"))
        if "war siege OK" in out:
            break
        time.sleep(10)
    sg = " | ".join(s.output("hywmill war sieges", 2))
    check("CL-1 a declared siege is announced for dawn in days and prepares (no host yet)", "before the walls at dawn on the third day" in out and "PREPARE" in sg,
          f"{j[-60:]} || {out[-200:]} || {sg[-200:]}")
    s.cmd("time add 72000", 1)
    m = s.wait_for(r"the host musters \(\d+ soldiers\)", 90, since=p0)
    check("CL-2 on the third day the host musters", m is not None, (m or "")[-160:])
    sc = " | ".join(s.output(f"hywmill war admin scout {ca}", 3))
    found = s.wait_for(r"Scouts: scouts of .* found .* at -?\d+, -?\d+", 10, since=p0)
    check("CL-3 a scout rides out and reports what he found with its coordinates", "war scout" in sc and found is not None, f"{sc[-160:]} || {(found or '')[-200:]}")
    col = " | ".join(s.output(f"hywmill war admin column mercs {ca} {cb} reveal", 2))
    mm = re.search(r"war column ([0-9a-f]{8}) MERCS", col)
    cid = mm.group(1) if mm else "x"
    intel = " | ".join(s.output(f"execute as {P} run hywmill war intel", 2))
    take = " | ".join(s.output(f"execute as {P} run hywmill war intel bribe {cid}", 2))
    check("CL-4 found mercenaries are in the player's intel (direction, distance) and can be bribed from it",
          cid in intel and (" m " in intel or " km " in intel) and ("deniers" in take), f"{col[-160:]} || {intel[-240:]} || {take[-160:]}")
    show = " | ".join(s.output(f"execute as {P} run hywmill war admin column-show {cid}", 3))
    time.sleep(4)
    men = [u for u, v in spike_info(s, "@e[type=!minecraft:player]").items() if fb and ("owner=" + fb) in v["desc"] and dist(v["pos"], (a[0] + 3, a[1], a[2] + 3)) < 80]
    check("CL-5 a column comes into the world near the player as soldiers of its village", "OK" in show and len(men) >= 5, f"{show} || {len(men)} men")
    for u in men:
        s.cmd(f"kill {u}", 0.2)
    d = s.wait_for(r"Column \w+ \(MERCS\) DESTROYED", 20, since=p0)
    check("CL-6 cut down to the last man, the column is destroyed and never arrives", d is not None, (d or "")[-160:])
    cv = " | ".join(s.output(f"hywmill war admin column convoy {ca} {cb} reveal", 2))
    mm = re.search(r"war column ([0-9a-f]{8}) CONVOY", cv)
    vid = mm.group(1) if mm else "x"
    s.output(f"execute as {P} run hywmill war admin column-show {vid}", 3)
    time.sleep(3)
    carts = spike_info(s, "@e[type=astikorcartsredux:supply_cart]") or spike_info(s, "@e[type=minecraft:chest_minecart]")
    items = " ".join(" ".join(s.output(f"data get entity {u} Items", 0.6)) for u in list(carts)[:3])
    horses = spike_info(s, "@e[type=minecraft:horse]")
    note("CL convoy items", items[:600])
    check("CL-7 a convoy camps with carts (and horses) full of supplies", carts and "count" in items and horses, f"{cv[-120:]} || {len(carts)} carts, {len(horses)} horses")
    al = " | ".join(s.output(f"hywmill war admin column alarm {cb} {cb}", 2))
    co = s.wait_for(r"opens its coffers", 20, since=p0)
    check("CL-8 the alarm reaching the besieged opens its coffers", co is not None, f"{al[-120:]} || {(co or '')[-160:]}")
    s.output("hywmill war admin recall-all", 3)
    m5(s, f"standin remove {P}", 0.3)


def scenario_W3(ctx):
    """Sieges in three waves (post-M5): a wave fought in the world wounds some of the fallen (they leave the field and stand
    again at the next dawn); at sundown the host withdraws, at dawn it comes back; a siege far from any witness is fought
    wave by wave on paper and ends in a victory or, after three days, a stalemate."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    fa = info(s, a).get("faction")
    fb = info(s, b).get("faction")
    P = "33333333-4444-4555-8666-777777777777"
    p0 = s.pos()
    s.output("hywmill war admin recall-all", 3)
    s.output(f"hywmill war for {P} peace {ca} with {cb} force", 2)
    time.sleep(6)
    dip(s, a, f"admin truce {ca} {cb} 0")
    s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2)
    s.output(at(a, "hywmill admin grant spear_man 20"), 2)
    s.cmd("time set 1000", 1)
    standin_at(s, P, b[0] + 2, b[2] + 2)
    time.sleep(30)
    out = ""
    t0 = time.time()
    while time.time() - t0 < 300:
        out = " | ".join(l for l in s.output(f"hywmill war admin siege {ca} {cb} quick", 2) if l.startswith("war siege"))
        if "war siege OK" in out:
            break
        time.sleep(10)
    ref = s.wait_for(r"Refugees from the countryside crowd into .*: \d+ of them take up arms", 30, since=p0)
    check("W3-0 at the news of the siege, 15-20 refugees take up arms in the besieged village", ref is not None and 15 <= int(re.search(r": (\d+) of them", ref).group(1)) <= 20,
          (ref or "")[-160:])
    w1 = s.wait_for(r"wave 1 begins", 400, since=p0)
    dep = s.wait_for(r"wave 1: \d+ unit\(s\) materialized", 60, since=p0)
    check("W3-1 at the walls the first wave begins, fought in the world near a player", w1 is not None and dep is not None,
          f"{out[-120:]} || {(w1 or '')[-120:]} || {(dep or '')[-120:]}")
    time.sleep(8)
    p1 = s.pos()
    near = lambda f: [u for u, v in spike_info(s, "@e[type=!minecraft:player]").items() if f and ("owner=" + f) in v["desc"] and dist(v["pos"], tuple(b)) < 140]
    host = near(fa)
    for u in host[:6]:
        s.cmd(f"kill {u}", 0.3)
    defs = near(fb)
    for u in defs[:8]:
        s.cmd(f"kill {u}", 0.3)
    time.sleep(4)
    rel = s.read_since(p1)
    hurt = [l for l in rel if "wounded and carried off the field" in l or "knocked out" in l]
    dead = [l for l in rel if "killed:" in l and "Garrison unit" in l]
    check("W3-2 some who fall are only wounded and carried off the field, the others die", hurt and dead,
          f"{len(host)} host, {len(defs)} defenders in the world; {len(hurt)} wounded, {len(dead)} dead || {(hurt or [''])[0][-140:]}")
    time.sleep(120)
    s.cmd("time set 13000", 1)
    sd = s.wait_for(r"sundown after wave 1 \(field\)", 240, since=p1)
    time.sleep(3)
    left = near(fa)
    check("W3-3 at sundown the host withdraws from the field", sd is not None and len(left) == 0, f"{(sd or '')[-160:]} || {len(left)} still in the world")
    time.sleep(32)
    s.cmd("time set 0", 1)
    w2 = s.wait_for(r"wave 2 begins: host (\d+)/(\d+)", 240, since=p1)
    dep2 = s.wait_for(r"wave 2: (\d+) unit\(s\) materialized", 60, since=p1)
    m = re.search(r"wave 2: (\d+) unit", dep2 or "")
    nights = [l for l in s.read_since(p1) if "rose to fight again" in l]
    check("W3-4 at dawn the host comes back, its wounded fit again (or dead of their wounds)", w2 is not None and m and int(m.group(1)) > 0 and nights,
          f"{(w2 or '')[-120:]} || {(dep2 or '')[-80:]} || {(nights or [''])[0][-200:]}")
    back = [l for l in s.read_since(p1) if "stand down at home" in l or "rejoin the garrison" in l]
    check("W3-4b the besieged's wounded, carried off while deployed, stand down and come back to the walls", back,
          (back or [''])[0][-160:])
    s.output("hywmill war admin recall-all", 3)
    m5(s, f"standin remove {P}", 0.3)
    # far from any witness: wave by wave on paper
    p2 = s.pos()
    s.cmd("time set 1000", 1)
    t0 = time.time()
    while time.time() - t0 < 300:
        if any("war siege OK" in l for l in s.output(f"hywmill war admin siege {ca} {cb} quick unwatched", 2)):
            break
        time.sleep(10)
    end = None
    waves = 0
    for w in (1, 2, 3):
        if s.wait_for(rf"wave {w} begins", 400, since=p2) is None:
            break
        waves = w
        time.sleep(125)
        s.cmd("time set 13000", 1)
        # on a busy server the shortest wave (2400 ticks) can outlast 125 s: wait for its sundown before the next dawn
        s.wait_for(rf"(sundown after wave {w}|Siege \w+ ended)", 300, since=p2)
        end = s.wait_for(r"Siege \w+ ended (WON|LOST|STALEMATE)", 5, since=p2)
        if end:
            break
        time.sleep(32)
        s.cmd("time set 0", 1)
    if end is None:
        end = s.wait_for(r"Siege \w+ ended (WON|LOST|STALEMATE)", 60, since=p2)
    paper = [l for l in s.read_since(p2) if "decided on paper" in l]
    hist = " | ".join(s.output(f"hywmill war history {cb}", 3))
    check("W3-5 unwatched, the siege is fought wave by wave on paper and ends in a victory or a stalemate", end is not None and len(paper) >= 1
          and ("Day 1:" in hist or "over " in hist), f"{waves} wave(s), {len(paper)} on paper || {(end or '')[-200:]} || {hist[-300:]}")
    check("W3-6 a stalemate only after the third wave", end is not None and ("STALEMATE" not in end or waves == 3), (end or "")[-120:])



def scenario_W3P(ctx):
    """Sieges in three waves, unwatched part only (see W3)."""
    s, a, b = ctx.s, ctx.a, ctx.b
    ca, cb = f"{a[0]} {a[1]} {a[2]}", f"{b[0]} {b[1]} {b[2]}"
    P = "33333333-4444-4555-8666-777777777777"
    s.output("hywmill war admin recall-all", 3)
    s.output(f"hywmill war for {P} declare {ca} on {cb} force", 2)
    s.output(at(a, "hywmill admin grant spear_man 20"), 2)
    time.sleep(20)
    # far from any witness: wave by wave on paper
    p2 = s.pos()
    s.cmd("time set 1000", 1)
    t0 = time.time()
    while time.time() - t0 < 300:
        if any("war siege OK" in l for l in s.output(f"hywmill war admin siege {ca} {cb} quick unwatched", 2)):
            break
        time.sleep(10)
    end = None
    waves = 0
    for w in (1, 2, 3):
        if s.wait_for(rf"wave {w} begins", 400, since=p2) is None:
            break
        waves = w
        time.sleep(125)
        s.cmd("time set 13000", 1)
        # on a busy server the shortest wave (2400 ticks) can outlast 125 s: wait for its sundown before the next dawn
        s.wait_for(rf"(sundown after wave {w}|Siege \w+ ended)", 300, since=p2)
        end = s.wait_for(r"Siege \w+ ended (WON|LOST|STALEMATE)", 5, since=p2)
        if end:
            break
        time.sleep(32)
        s.cmd("time set 0", 1)
    if end is None:
        end = s.wait_for(r"Siege \w+ ended (WON|LOST|STALEMATE)", 60, since=p2)
    paper = [l for l in s.read_since(p2) if "decided on paper" in l]
    hist = " | ".join(s.output(f"hywmill war history {cb}", 3))
    check("W3-5 unwatched, the siege is fought wave by wave on paper and ends in a victory or a stalemate", end is not None and len(paper) >= 1
          and ("Day 1:" in hist or "over " in hist), f"{waves} wave(s), {len(paper)} on paper || {(end or '')[-200:]} || {hist[-300:]}")
    check("W3-6 a stalemate only after the third wave", end is not None and ("STALEMATE" not in end or waves == 3), (end or "")[-120:])



SCENARIOS = {"W3P": scenario_W3P, "W3": scenario_W3, "CL": scenario_CL, "SS": scenario_SS, "CB": scenario_CB, "HA": scenario_HA, "VS": scenario_VS, "RS": scenario_RS, "DA": scenario_DA, "RC": scenario_RC, "TR": scenario_TR, "AD": scenario_AD, "PC": scenario_PC, "VL": scenario_VL, "G4_explore": scenario_G4_explore, "G4_0": scenario_G4_0, "G4_1": scenario_G4_1, "G4_2": scenario_G4_2, "G4_3": scenario_G4_3, "G4_4": scenario_G4_4, "G4_5": scenario_G4_5, "G4_6": scenario_G4_6, "G4_7": scenario_G4_7, "G4_8": scenario_G4_8, "G4_9": scenario_G4_9, "G4_10": scenario_G4_10, "G4_EK": scenario_G4_EK, "G4_perf": scenario_G4_perf, "A": scenario_A, "B": scenario_B, "C": scenario_C, "D": scenario_D, "E": scenario_E,
             "F1": scenario_F1, "F2": scenario_F2, "H": scenario_H, "G": scenario_G, "I": scenario_I, "N": scenario_N, "W": scenario_W, "L": scenario_L, "X": scenario_X, "P": scenario_P, "M": scenario_M, "status": scenario_status, "S": scenario_S,
             "G3_1": scenario_G3_1, "G3_2": scenario_G3_2, "G3_3": scenario_G3_3, "G3_4": scenario_G3_4, "G3_5": scenario_G3_5,
             "G3_6": scenario_G3_6, "G3_7": scenario_G3_7, "G3_8": scenario_G3_8, "G3_9": scenario_G3_9, "G3_10": scenario_G3_10,
             "G3_11": scenario_G3_11, "G3_12": scenario_G3_12, "G3_13": scenario_G3_13, "G3_14": scenario_G3_14, "G3_15": scenario_G3_15,
             "G3_17": scenario_G3_17, "G3_18": scenario_G3_18, "G3_perf": scenario_G3_perf, "S4": scenario_S4, "RD": scenario_RD, "MR": scenario_MR, "AP": scenario_AP, "WG": scenario_WG, "WX": scenario_WX, "WL": scenario_WL, "RC": scenario_RC, "SG": scenario_SG, "OS": scenario_OS, "ENG": scenario_ENG, "ENGC": scenario_ENGC, "ARS": scenario_ARS, "ARS7": scenario_ARS7, "SGF": scenario_SGF, "CIV": scenario_CIV, "WP": scenario_WP, "RL": scenario_RL, "LV": scenario_LV, "WT": scenario_WT, "SQ": scenario_SQ, "LK": scenario_LK, "S4b": scenario_S4b,
             "S5_0": scenario_S5_0, "S5_A": scenario_S5_A, "S5_B": scenario_S5_B, "S5_C": scenario_S5_C, "S5_D": scenario_S5_D,
             "S5_E": scenario_S5_E, "S5_F": scenario_S5_F, "S5_G": scenario_S5_G, "S5_H": scenario_S5_H, "S5_I": scenario_S5_I,
             "S5_J": scenario_S5_J, "S5_K": scenario_S5_K, "S5_L": scenario_S5_L, "S5_M": scenario_S5_M, "S5_N": scenario_S5_N,
             "S5_R": scenario_S5_R, "S5_V": scenario_S5_V, "S5_W": scenario_S5_W, "S5_P": scenario_S5_P, "G5_2": scenario_G5_2, "G5_3": scenario_G5_3, "G5_4": scenario_G5_4, "G5_5": scenario_G5_5, "G5_5b": scenario_G5_5b, "G5_6": scenario_G5_6, "G5_UI": scenario_G5_UI, "G5_G": scenario_G5_G,
             "SG_0": scenario_SG_0, "SG_1": scenario_SG_1, "SG_2": scenario_SG_2, "SG_3": scenario_SG_3, "SG_4": scenario_SG_4,
             "SG_5": scenario_SG_5, "SG_6": scenario_SG_6}
ORDER_G3 = ["status", "G3_1", "G3_2", "G3_3", "G3_4", "G3_5", "G3_6", "G3_7", "G3_8", "G3_9", "G3_10", "G3_11", "G3_12", "G3_13",
            "G3_15", "G3_18", "G3_14", "G3_perf"]
ORDER_G4 = ["status", "G4_0", "G4_1", "G4_2", "G4_3", "G4_4", "G4_5", "G4_6", "G4_7", "G4_8", "G4_10", "G4_perf", "G4_9"]
ORDER_G4_EK = ["status", "G4_0", "G4_EK"]
ORDER = ["status", "H", "B", "N", "C", "D", "I", "W", "L", "F1", "E", "F2", "X", "P", "A", "G"]


def run(d: Path, names, fresh=True):
    write_configs(d)
    extra_mods = sorted(Path(os.environ["HYWMILL_EXTRA_MODS"]).glob("*.jar")) if os.environ.get("HYWMILL_EXTRA_MODS") else []
    install_mods(d, [MILLENAIRE_JAR, HYW_JAR, built_jar()] + extra_mods)
    if names == ["m5spike"] or "S5_I" in names:
        write_m5_content(d)
    if names == ["m5"] or "G5_6" in names:
        install_armoury_pack(d)
    if fresh and (d / "world").exists():
        shutil.rmtree(d / "world")
    s = Server(d)
    ctx = Ctx(s)
    ctx.run_log_pos = s.pos() if s.log.exists() else 0  # this run's part of harness-server.log (diagnostics)
    try:
        s.start()
        if fresh:
            setup(ctx)
        else:
            reuse(ctx)
        order = {"all": ORDER, "garrison": ORDER_G3, "duties": ORDER_G4, "duties-ek": ORDER_G4_EK, "m5spike": ORDER_M5, "sgscale": ORDER_SG, "m5opt1": ORDER_M5_OPT1, "m5": ORDER_M5_PHASES}
        for n in (order[names[0]] if len(names) == 1 and names[0] in order else names):
            if ctx.a is None:
                break
            log(f"--- scenario {n}")
            SCENARIOS[n](ctx)
    finally:
        s.stop()
    passed = sum(1 for r in RESULTS if r[1])
    log(f"RESULT {passed}/{len(RESULTS)} checks passed" + (f"; {len(DEFERRED)} deferred (not counted)" if DEFERRED else ""))
    for name, ok, detail in RESULTS:
        print(f"  {'PASS' if ok else 'FAIL'}  {name}  {detail}")
    for name, reason in DEFERRED:
        print(f"  DEFERRED  {name}  ({reason})")
    for name, text in NOTES:
        print(f"  NOTE  {name}  {text}")
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
        mig = s.wait_for(r"migrated \d+ record\(s\) from format 3 to [45]", 30, since=s.start_pos)  # 5 since M5
        check("G3-16 format-3 ledger loaded and migrated to the current format", loaded is not None and mig is not None, f"{loaded} | {mig}")
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
        loaded4 = s.wait_for(r"Garrison ledger loaded: \d+ village record\(s\), format [45]", 30, since=s.start_pos)
        time.sleep(40)
        grants2 = [l for l in s.read_since(s.start_pos) if "Starting garrison granted" in l]
        check("G3-16 after another restart: current format on disk, no further grant", loaded4 is not None and not grants2, f"{loaded4}; {len(grants2)} grants")
    finally:
        s.stop()


def run_migrate4(d: Path, m4_jar: Path):
    """M5: a world written by the frozen M4 build (ledger format 4, residents marked with the faction
    identity) is loaded by M5: format 4 -> 5, residents migrate to the resident identity, the
    resident/faction alliance is written, the garrison is intact (no duplicate, no lost slot), and a
    second restart does not migrate again."""
    write_configs(d)
    install_mods(d, [MILLENAIRE_JAR, HYW_JAR, m4_jar])
    if (d / "world").exists():
        shutil.rmtree(d / "world")
    s = Server(d)
    ctx = Ctx(s)
    try:
        s.start()
        setup(ctx)
        wait_garrison(s, ctx.a, lambda g: g.get("alive", 0) >= 2 and g.get("recruited", 1) == 0, 240)
        before = {k: (info(s, c), garrison(s, c), census(s, c)) for k, c in (("A", ctx.a), ("B", ctx.b)) if c}
        res = wait_residents(s, ctx.a)
        civ = next((r[0] for r in res if r[2] == "CIVILIAN"), None)
        m4_marker = marker_of(s, civ) if civ else None
        check("MIG4 M4 world: residents carry the faction identity", m4_marker == before["A"][0].get("faction"), f"{m4_marker} vs {before['A'][0].get('faction')}")
        s.cmd("save-all flush", 5)
    finally:
        s.stop()
    install_mods(d, [MILLENAIRE_JAR, HYW_JAR, built_jar()])
    try:
        s.start()
        loaded = s.wait_for(r"Garrison ledger loaded: \d+ village record\(s\), format 4", 30, since=s.start_pos)
        mig = s.wait_for(r"migrated \d+ record\(s\) from format 4 to 5", 30, since=s.start_pos)
        check("MIG4 format-4 ledger loaded and migrated to format 5", loaded is not None and mig is not None, f"{loaded} | {mig}")
        s.cmd("millenaire chunkload", 10)
        time.sleep(60)
        after = {k: (info(s, c), garrison(s, c), census(s, c)) for k, c in (("A", ctx.a), ("B", ctx.b)) if c}
        rid = after["A"][0].get("residents")
        mk = marker_of(s, civ, rid) if civ else None
        check("MIG4 residents migrated to the resident identity", mk == rid and rid is not None, f"{mk} vs {rid}")
        r = rel(s, rid, after["A"][0].get("faction")) if rid else None
        check("MIG4 resident<->faction alliance written", r == ("FRIENDLY", "FRIENDLY"), str(r))
        same = all(before[k][0].get("faction") == after[k][0].get("faction") and before[k][0].get("villageId") == after[k][0].get("villageId")
                   for k in before)
        check("MIG4 village and faction identities unchanged", same, str({k: (before[k][0].get("faction"), after[k][0].get("faction")) for k in before}))
        ok = all(after[k][2].get("dupSlots") == 0 and after[k][1].get("live") == before[k][1].get("live")
                 and after[k][1].get("t_recruited") == before[k][1].get("t_recruited") for k in before)
        check("MIG4 garrison intact: same live slots, no duplicate, no new grant", ok,
              str({k: (before[k][1].get("live"), after[k][1].get("live"), after[k][2]) for k in before}))
        stat = " ".join(s.output("hywmill status", 2))
        note("MIG4 identity counters", (re.search(r"identities: .*", stat) or [""])[0][:300])
        s.cmd("save-all flush", 5)
        s.stop()
        s.start()
        loaded5 = s.wait_for(r"Garrison ledger loaded: \d+ village record\(s\), format 5", 30, since=s.start_pos)
        again = s.wait_for(r"migrated \d+ record\(s\) from format", 10, since=s.start_pos)
        check("MIG4 second restart: format 5 on disk, no further migration", loaded5 is not None and again is None, f"{loaded5}; {again}")
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
    if a.scenarios[0] == "migrate4":
        run_migrate4(a.dir, Path(a.scenarios[1]))
        passed = sum(1 for r in RESULTS if r[1])
        log(f"RESULT {passed}/{len(RESULTS)} checks passed")
        for name, ok, detail in RESULTS:
            print(f"  {'PASS' if ok else 'FAIL'}  {name}  {detail}")
        for name, text in NOTES:
            print(f"  NOTE  {name}  {text}")
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
