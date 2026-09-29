package dev.hywmill.politics.war;

import dev.hywmill.politics.PoliticsTables;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** The pure rules of a village's war arsenal (post-M5): which engines it fields when it goes to war. */
public final class ArsenalPlan {
    private ArsenalPlan() {}

    public static final String ENGINEER = "siege_engineer";

    /**
     * The engines (HYW entity id paths) a village of {@code tier} fields for a war, drawn with {@code seed} (stable per
     * village and war): {@code engines(tier)} of them from {@code types}; a stronghold draws one from {@code strongholdTypes}.
     */
    public static List<String> engines(PoliticsTables.MilitaryTierKey tier, PoliticsTables.ArsenalRule r, long seed) {
        List<String> out = new ArrayList<>();
        int n = r.enabled() ? r.engines(tier) : 0;
        if (n <= 0 || r.types().isEmpty()) {
            return out;
        }
        Random rnd = new Random(seed);
        if (tier == PoliticsTables.MilitaryTierKey.STRONGHOLD && !r.strongholdTypes().isEmpty()) {
            out.add(r.strongholdTypes().get(rnd.nextInt(r.strongholdTypes().size())));
        }
        while (out.size() < n) {
            out.add(r.types().get(rnd.nextInt(r.types().size())));
        }
        return out;
    }

    /** Whether an arsenal entry is an engine (the rest are their engineers). */
    public static boolean isEngine(String key) {
        return !ENGINEER.equals(key);
    }

    /** The fortification multiplier left after {@code engines} attacking engines each cancel {@code fortCut} of the bonus. */
    public static double fortificationLeft(double fortMultiplier, int engines, PoliticsTables.ArsenalRule r) {
        double bonus = Math.max(0, fortMultiplier - 1);
        return 1 + bonus * Math.max(0, 1 - r.fortCut() * engines);
    }
}
