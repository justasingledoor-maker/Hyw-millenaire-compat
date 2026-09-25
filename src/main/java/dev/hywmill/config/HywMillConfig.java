package dev.hywmill.config;

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
    public static final ModConfigSpec.IntValue THREAT_SCAN_INTERVAL;
    public static final ModConfigSpec.IntValue THREAT_MARGIN;
    public static final ModConfigSpec.IntValue RECENT_ATTACK_WINDOW;
    public static final ModConfigSpec.BooleanValue PREVENT_PERMANENT_ESCALATION;
    public static final ModConfigSpec.BooleanValue GUARDS_ASSIST_PLAYERS;
    public static final ModConfigSpec.IntValue ASSIST_MIN_REPUTATION;
    public static final ModConfigSpec.BooleanValue ASSIST_PROVOKING_PLAYER;

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
        HUNT_BRIDGE = b.comment("Decorate millenaire:hunt_monster so defenders proactively engage hostile HYW units in the village. Read at server start.")
                .define("huntBridge", true);
        HIDE_BRIDGE = b.comment("Decorate millenaire:hide so civilians shelter while hostile HYW units are in the village. Read at server start.")
                .define("hideBridge", true);
        THREAT_SCAN_INTERVAL = b.comment("Ticks between threat scans of active villages.")
                .defineInRange("threatScanIntervalTicks", 20, 5, 200);
        THREAT_MARGIN = b.comment("Blocks added around a village's building bounds when scanning for threats.")
                .defineInRange("threatMarginBlocks", 16, 0, 64);
        RECENT_ATTACK_WINDOW = b.comment("Ticks an entity stays 'recently attacked this village' after hitting a resident.")
                .defineInRange("recentAttackWindowTicks", 600, 20, 12000);
        PREVENT_PERMANENT_ESCALATION = b.comment(
                        "HYW permanently sets two NEUTRAL identities HOSTILE after repeated damage.",
                        "When true, any such HOSTILE relation involving a Millénaire village faction is reset to NEUTRAL (logged).")
                .define("preventPermanentEscalation", true);
        GUARDS_ASSIST_PLAYERS = b.comment("Defenders may engage HYW units that are attacking a player inside the village.")
                .define("guardsAssistPlayers", true);
        ASSIST_MIN_REPUTATION = b.comment("Minimum Millénaire village reputation a player needs to receive assistance.")
                .defineInRange("assistMinReputation", 0, -1_000_000, 1_000_000);
        ASSIST_PROVOKING_PLAYER = b.comment("Assist a player even if the incident ledger shows the player struck the HYW unit first.")
                .define("assistProvokingPlayer", false);
        b.pop();

        SPEC = b.build();
    }

    private HywMillConfig() {}
}
