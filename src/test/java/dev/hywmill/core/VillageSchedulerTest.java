package dev.hywmill.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageSchedulerTest {
    @Test
    void eachVillageRunsExactlyOncePerInterval() {
        VillageScheduler s = new VillageScheduler();
        for (int i = 0; i < 50; i++) {
            UUID v = UUID.randomUUID();
            int due = 0;
            for (long t = 0; t < 200; t++) {
                if (s.isDue(v, t, 40)) {
                    due++;
                }
            }
            assertEquals(5, due);
        }
    }

    @Test
    void phasesAreSpread() {
        int[] buckets = new int[20];
        for (int i = 0; i < 2000; i++) {
            buckets[VillageScheduler.phaseOf(UUID.randomUUID(), 20)]++;
        }
        for (int b : buckets) {
            assertTrue(b > 40, "phase bucket underfilled: " + b);
        }
    }
}
