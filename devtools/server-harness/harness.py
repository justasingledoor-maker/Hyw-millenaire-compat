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
    (cfg / "hywmill-common.toml").write_text(
        "[general]\n\tverboseLogging = true\n\tdevCommands = true\n" + hywmill_extra)
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


def spawn_village(s, candidates):
    for vtype, x, y, z in candidates:
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
                        ("defending", r"Defending strength \(Mill.naire\): (\d+)")]:
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


def scenario_A(ctx):
    s = ctx.s
    time.sleep(12)  # at least one ledger update
    before = info(s, ctx.a)
    check("A1-3 village info populated", all(k in before for k in ("villageId", "faction", "tier", "garrison", "fortification")), str(before))
    s.cmd("save-all flush", 5)
    s.stop()
    s.start()
    time.sleep(15)
    loaded = s.wait_for(r"Garrison ledger loaded: \d+ village", 30, since=s.start_pos)
    after = info(s, ctx.a)
    check("A4 ledger reloaded from disk", loaded is not None, loaded or "")
    check("A5-6 same VillageId and faction after restart",
          before.get("villageId") == after.get("villageId") and before.get("faction") == after.get("faction"),
          f"{before.get('faction')} vs {after.get('faction')}")
    ctx.info_after_restart = after


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


def scenario_status(ctx):
    out = ctx.s.output("hywmill status", 2)
    check("status: goal bridge installed", any("engage_target=bridged" in l and "hide=bridged" in l for l in out), "; ".join(out[:3]))


SCENARIOS = {"A": scenario_A, "B": scenario_B, "C": scenario_C, "D": scenario_D, "E": scenario_E,
             "F1": scenario_F1, "F2": scenario_F2, "status": scenario_status}
ORDER = ["status", "B", "C", "D", "F1", "E", "F2", "A"]


def run(d: Path, names, fresh=True):
    write_configs(d)
    install_mods(d, [MILLENAIRE_JAR, HYW_JAR, built_jar()])
    if fresh and (d / "world").exists():
        shutil.rmtree(d / "world")
    s = Server(d)
    ctx = Ctx(s)
    try:
        s.start()
        setup(ctx)
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
    return 0 if run(a.dir, a.scenarios, fresh=not a.keep_world) else 1


if __name__ == "__main__":
    sys.exit(main())
