package dev.hywmill.core;

import com.mojang.logging.LogUtils;
import dev.hywmill.config.HywMillConfig;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Logging helper: "diag" messages are DEBUG unless verboseLogging is on; throttled variants avoid per-tick spam. */
public final class HmLog {
    public static final Logger LOG = LogUtils.getLogger();
    private static final String PREFIX = "[hywmill] ";
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();

    private HmLog() {}

    public static void info(String msg, Object... args) {
        LOG.info(PREFIX + msg, args);
    }

    public static void warn(String msg, Object... args) {
        LOG.warn(PREFIX + msg, args);
    }

    public static void error(String msg, Object... args) {
        LOG.error(PREFIX + msg, args);
    }

    public static void diag(String msg, Object... args) {
        if (verbose()) {
            LOG.info(PREFIX + msg, args);
        } else {
            LOG.debug(PREFIX + msg, args);
        }
    }

    /** INFO at most once per {@code intervalMs} for {@code key}. */
    public static void infoThrottled(String key, long intervalMs, String msg, Object... args) {
        if (allow(key, intervalMs)) {
            info(msg, args);
        }
    }

    public static void diagThrottled(String key, long intervalMs, String msg, Object... args) {
        if (allow(key, intervalMs)) {
            diag(msg, args);
        }
    }

    public static void warnThrottled(String key, long intervalMs, String msg, Object... args) {
        if (allow(key, intervalMs)) {
            warn(msg, args);
        }
    }

    private static boolean allow(String key, long intervalMs) {
        long now = System.currentTimeMillis();
        Long prev = LAST.get(key);
        if (prev != null && now - prev < intervalMs) {
            return false;
        }
        LAST.put(key, now);
        if (LAST.size() > 4096) {
            LAST.entrySet().removeIf(e -> now - e.getValue() > 600_000L);
        }
        return true;
    }

    private static boolean verbose() {
        try {
            return HywMillConfig.VERBOSE_LOGGING.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }
}
