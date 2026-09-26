package dev.hywmill.config;

import dev.hywmill.garrison.GarrisonSettings;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Common config ({@code config/hywmill-common.toml}). */
public final class HywMillConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue VERBOSE_LOGGING;
    public static final ModConfigSpec.BooleanValue DEV_COMMANDS;

    public static final ModConfigSpec.IntValue LEDGER_UPDATE_INTERVAL;

    public static final ModConfigSpec.BooleanValue MARK_VILLAGERS;
    public static final ModConfigSpec.BooleanValue ENGAGE_BRIDGE;
    public static final ModConfigSpec.BooleanValue HUNT_BRIDGE;
    public static final ModConfigSpec.BooleanValue HIDE_BRIDGE;
    public static final ModConfigSpec.BooleanValue RESERVE_BRIDGE;
    public static final ModConfigSpec.IntValue THREAT_SCAN_INTERVAL;
    public static final ModConfigSpec.IntValue THREAT_MARGIN;
    public static final ModConfigSpec.IntValue RECENT_ATTACK_WINDOW;
    public static final ModConfigSpec.BooleanValue PREVENT_PERMANENT_ESCALATION;
    public static final ModConfigSpec.BooleanValue GUARDS_ASSIST_PLAYERS;
    public static final ModConfigSpec.IntValue ASSIST_MIN_REPUTATION;
    public static final ModConfigSpec.BooleanValue ASSIST_PROVOKING_PLAYER;

    public static final ModConfigSpec.BooleanValue GARRISON_ENABLED;
    public static final ModConfigSpec.IntValue GARRISON_SPAWNS_PER_SLOT;
    public static final ModConfigSpec.IntValue GARRISON_SPAWNS_PER_TICK;
    public static final ModConfigSpec.IntValue GARRISON_SETTLE_INTERVALS;
    public static final ModConfigSpec.IntValue GARRISON_RECRUIT_INTERVAL;
    public static final ModConfigSpec.IntValue GARRISON_DEATH_COOLDOWN;
    public static final ModConfigSpec.IntValue GARRISON_WIPEOUT_COOLDOWN;
    public static final ModConfigSpec.IntValue GARRISON_MISSING_GRACE;
    public static final ModConfigSpec.IntValue GARRISON_LOST_TIMEOUT;
    public static final ModConfigSpec.IntValue GARRISON_VILLAGE_GONE_GRACE;
    public static final ModConfigSpec.IntValue GARRISON_RETURN_TIMEOUT;
    public static final ModConfigSpec.EnumValue<GarrisonSettings.OrphanPolicy> GARRISON_ORPHAN_POLICY;
    public static final ModConfigSpec.BooleanValue GARRISON_EQUIPMENT_DROPS;
    public static final ModConfigSpec.BooleanValue DUTIES_ENABLED;
    public static final ModConfigSpec.IntValue DUTY_INTERVAL;
    public static final ModConfigSpec.IntValue DUTY_LAYOUT_RECHECK;
    public static final ModConfigSpec.BooleanValue RAIDS_ENABLED;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("general");
        VERBOSE_LOGGING = b.comment("Log bridge diagnostics at INFO instead of DEBUG.")
                .define("verboseLogging", false);
        DEV_COMMANDS = b.comment("Enable /hywmill dev ... test commands (op level 2). Keep off in normal play.")
                .define("devCommands", false);
        b.pop();

        b.push("ledger");
        LEDGER_UPDATE_INTERVAL = b.comment("Ticks between garrison ledger updates of active Millénaire villages.")
                .defineInRange("updateIntervalTicks", 200, 20, 24000);
        b.pop();

        b.push("bridge");
        MARK_VILLAGERS = b.comment("Give Millénaire villagers their village's synthetic HYW relation identity.")
                .define("markVillagers", true);
        ENGAGE_BRIDGE = b.comment("Decorate millenaire:engage_target so defenders can fight hostile HYW units. Read at server start.")
                .define("engageBridge", true);
        HUNT_BRIDGE = b.comment("Decorate millenaire:hunt_monster so idle defenders go after the HYW threat the defense coordinator assigned them",
                        "(M2: reactive threats only, unless the village doctrine is proactive). Read at server start.")
                .define("huntBridge", true);
        HIDE_BRIDGE = b.comment("Decorate millenaire:hide so civilians shelter while hostile HYW units are in the village. Read at server start.")
                .define("hideBridge", true);
        RESERVE_BRIDGE = b.comment("Decorate millenaire:defend_village so the doctrine reserve holds Millénaire's defending position",
                        "during HywMill ALERT/ENGAGED/RECOVERY (real Millénaire raids are unchanged). Read at server start.")
                .define("reserveBridge", true);
        THREAT_SCAN_INTERVAL = b.comment("Ticks between threat scans of active villages.")
                .defineInRange("threatScanIntervalTicks", 20, 5, 200);
        THREAT_MARGIN = b.comment("Blocks added above and below a village's building bounds when scanning for threats.",
                        "(M2: the horizontal extent is the doctrine's defenseRadius around the village center.)")
                .defineInRange("threatMarginBlocks", 16, 0, 64);
        RECENT_ATTACK_WINDOW = b.comment("Ticks an entity stays 'recently attacked this village' after hitting a resident.")
                .defineInRange("recentAttackWindowTicks", 600, 20, 12000);
        PREVENT_PERMANENT_ESCALATION = b.comment(
                        "HYW permanently sets two NEUTRAL identities HOSTILE after repeated damage.",
                        "When true, any such HOSTILE relation involving a Millénaire village faction is reset to NEUTRAL (logged).")
                .define("preventPermanentEscalation", true);
        GUARDS_ASSIST_PLAYERS = b.comment("Master switch: defenders may engage HYW units attacking a player inside the defense radius, as the",
                        "village doctrine allows (assistPlayers, assistMinReputation, assistProvokingPlayer, assistController).")
                .define("guardsAssistPlayers", true);
        ASSIST_MIN_REPUTATION = b.comment("UNUSED since M2: superseded by the doctrine fields assistPlayers/assistMinReputation",
                        "(see /hywmill doctrine). Kept so existing config files stay valid.")
                .defineInRange("assistMinReputation", 0, -1_000_000, 1_000_000);
        ASSIST_PROVOKING_PLAYER = b.comment("UNUSED since M2: superseded by the doctrine field assistProvokingPlayer.",
                        "Kept so existing config files stay valid.")
                .define("assistProvokingPlayer", false);
        b.pop();

        b.push("garrison");
        GARRISON_ENABLED = b.comment("M3 village-owned HYW garrisons. false stops recruitment, spawning and deployment;",
                        "existing units stay and are still reconciled. Sizes, levy and compositions are datapack data",
                        "(data/<ns>/hywmill_garrison/). There is deliberately no server-wide unit cap.")
                .define("enabled", true);
        GARRISON_SPAWNS_PER_SLOT = b.comment("Most units one village spawns in one garrison slot (one slot per village per ledger interval).")
                .defineInRange("spawnsPerSlot", 2, 1, 16);
        GARRISON_SPAWNS_PER_TICK = b.comment("Most garrison units spawned server-wide in one tick (throughput only; excess waits for the next slot).")
                .defineInRange("spawnsPerTick", 2, 1, 16);
        GARRISON_SETTLE_INTERVALS = b.comment("Ledger intervals a village must be active (and the server up) before its garrison spawns units",
                        "or marks unseen units MISSING, so units in freshly loaded chunks rejoin first.")
                .defineInRange("settleIntervals", 2, 1, 20);
        GARRISON_RECRUIT_INTERVAL = b.comment("Minimum ticks between two paid recruits of one village.")
                .defineInRange("recruitIntervalTicks", 2400, 20, 240000);
        GARRISON_DEATH_COOLDOWN = b.comment("Recruitment cooldown (ticks) after a garrison unit dies.")
                .defineInRange("deathCooldownTicks", 1200, 0, 240000);
        GARRISON_WIPEOUT_COOLDOWN = b.comment("Recruitment cooldown (ticks) when at least 75% of the target died during one alert.")
                .defineInRange("wipeoutCooldownTicks", 24000, 0, 2400000);
        GARRISON_MISSING_GRACE = b.comment("Active ticks a unit may go unseen before its slot is MISSING (unloaded chunks elsewhere are not death).")
                .defineInRange("missingGraceTicks", 1200, 200, 240000);
        GARRISON_LOST_TIMEOUT = b.comment("Further active ticks before a MISSING slot is LOST and may be replaced by a new paid recruit.")
                .defineInRange("lostTimeoutTicks", 72000, 1200, 2400000);
        GARRISON_VILLAGE_GONE_GRACE = b.comment("Ticks a village may be absent from Millénaire's village list before its garrison is LOST(VILLAGE_GONE).")
                .defineInRange("villageGoneGraceTicks", 6000, 200, 240000);
        GARRISON_RETURN_TIMEOUT = b.comment("Ticks after which a returning unit counts as garrisoned even if not back at its post.")
                .defineInRange("returnTimeoutTicks", 1200, 20, 24000);
        GARRISON_ORPHAN_POLICY = b.comment("Units of a deleted village: KEEP (they stay as ordinary HYW units, untagged) or DISCARD.")
                .defineEnum("orphanPolicy", GarrisonSettings.OrphanPolicy.KEEP);
        GARRISON_EQUIPMENT_DROPS = b.comment("Whether garrison units drop their equipment on death (false: no gear farming).")
                .define("equipmentDrops", false);
        b.pop();

        b.push("duties");
        DUTIES_ENABLED = b.comment("M4 standing duties (sentries, patrols, scouts, reserve) of village garrisons. false leaves every unit",
                        "on GARRISON duty at the spawn anchor as in M3. Quotas are datapack data (data/<ns>/hywmill_duties/).")
                .define("enabled", true);
        DUTY_INTERVAL = b.comment("Ticks between two duty updates of one village (staggered per village). Must divide the ledger interval.")
                .defineInRange("intervalTicks", 40, 20, 400);
        DUTY_LAYOUT_RECHECK = b.comment("Ticks between two reads of a village's building layout (duty posts and routes are recomputed only if it changed).")
                .defineInRange("layoutRecheckTicks", 1200, 200, 24000);
        b.pop();

        b.push("raids");
        RAIDS_ENABLED = b.comment("M4: HYW garrison contingents join their village's own Millénaire raids (sizes: hywmill_duties 'raid').")
                .define("enabled", true);
        b.pop();

        SPEC = b.build();
    }

    private HywMillConfig() {}

    /** Current garrison settings (config values are read each slot, so /reload-free edits apply). */
    public static GarrisonSettings garrison() {
        return new GarrisonSettings(GARRISON_ENABLED.get(), LEDGER_UPDATE_INTERVAL.get(), GARRISON_SPAWNS_PER_SLOT.get(),
                GARRISON_SPAWNS_PER_TICK.get(), GARRISON_SETTLE_INTERVALS.get(), GARRISON_RECRUIT_INTERVAL.get(),
                GARRISON_DEATH_COOLDOWN.get(), GARRISON_WIPEOUT_COOLDOWN.get(), GARRISON_MISSING_GRACE.get(), GARRISON_LOST_TIMEOUT.get(),
                GARRISON_VILLAGE_GONE_GRACE.get(), GARRISON_RETURN_TIMEOUT.get(), GARRISON_ORPHAN_POLICY.get(),
                GARRISON_EQUIPMENT_DROPS.get(), 24000L);
    }
}
