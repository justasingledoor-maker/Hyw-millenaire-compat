package dev.hywmill.recruit;

import dev.hywmill.garrison.tables.GarrisonTable;
import dev.hywmill.garrison.tables.UnitClass;
import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.military.MilitaryTier;
import dev.hywmill.politics.Standing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a village's Muster Roll offers one player (pure).
 * <ul>
 *   <li>Outlaw or Unwelcome: nothing.</li>
 *   <li>Mercenaries ({@link RecruitTables#mercs}): to anyone else, in the culture's light (WATCH) gear, priced in argent.</li>
 *   <li>Cultural soldiers: the village's own unit types (its culture's garrison composition, not levies), at gear tier
 *       {@code min(village tier, the standing's cap)}; a type is offered only if that tier allows its class and minimum
 *       tier. Priced in or: {@code (soldierBase + soldierPerCost × cost) × tierFactor(gear tier)}.</li>
 * </ul>
 * Every price is rounded to whole argent, then the standing's discount applies (to the denier).
 */
public final class RecruitOffers {
    private RecruitOffers() {}

    /**
     * @param key      "merc:&lt;unit&gt;" or "unit:&lt;unit&gt;" (what the client sends back)
     * @param gearTier the tier the unit is equipped at
     * @param price    deniers per unit, after the discount
     */
    public record Offer(String key, UnitSpec unit, MilitaryTier gearTier, int price, boolean merc) {
        public String label() {
            String n = unit.key().replace('_', ' ');
            n = Character.toUpperCase(n.charAt(0)) + n.substring(1);
            return merc ? n + " (mercenary)" : n;
        }
    }

    public static boolean refused(Standing s) {
        return s == Standing.OUTLAW || s == Standing.UNWELCOME;
    }

    /** The best gear tier this player can hire at from this village (NONE: mercenaries only). */
    public static MilitaryTier gearTier(Standing s, MilitaryTier village, RecruitTables t) {
        MilitaryTier cap = t.standingCap().get(s);
        if (cap == null || refused(s)) {
            return MilitaryTier.NONE;
        }
        return village.ordinal() < cap.ordinal() ? village : cap;
    }

    public static List<Offer> offers(Standing s, MilitaryTier village, GarrisonTable table, Map<String, UnitSpec> units, RecruitTables t) {
        List<Offer> out = new ArrayList<>();
        if (refused(s)) {
            return out;
        }
        double off = t.discount().getOrDefault(s, 0.0);
        for (Map.Entry<String, Double> m : t.mercs().entrySet()) {
            UnitSpec u = units.get(m.getKey());
            if (u != null && u.enabled()) {
                out.add(new Offer("merc:" + u.key(), u, MilitaryTier.WATCH, discounted(argent(m.getValue()), off), true));
            }
        }
        MilitaryTier gear = gearTier(s, village, t);
        if (gear.ordinal() < MilitaryTier.GUARD_POST.ordinal()) {
            return out;
        }
        for (String key : table.composition().keySet()) {
            UnitSpec u = units.get(key);
            if (u == null || !u.enabled() || table.composition().getOrDefault(key, 0) <= 0 || u.unitClass() == UnitClass.LEVY
                    || u.minTier().ordinal() > gear.ordinal() || !table.tier(gear).classes().contains(u.unitClass())) {
                continue;
            }
            double or = (t.soldierBase() + t.soldierPerCost() * u.cost()) * t.tierFactor().getOrDefault(gear, 1.0);
            out.add(new Offer("unit:" + u.key(), u, gear, discounted(argent(or * RecruitTables.DENIER_OR / RecruitTables.DENIER_ARGENT), off),
                    false));
        }
        return out;
    }

    /** Whole argent, in deniers (at least one argent). */
    static int argent(double argent) {
        return (int) Math.max(1, Math.round(argent)) * RecruitTables.DENIER_ARGENT;
    }

    /** The standing's discount on a whole-argent price, in whole deniers (so small prices keep their discount). */
    static int discounted(int deniers, double off) {
        return (int) Math.max(1, Math.round(deniers * (1 - off)));
    }

    /** "2 or 13 argent" style (deniers below an argent never occur in prices, but are shown if present). */
    public static String money(long deniers) {
        long or = deniers / RecruitTables.DENIER_OR;
        long ag = deniers % RecruitTables.DENIER_OR / RecruitTables.DENIER_ARGENT;
        long d = deniers % RecruitTables.DENIER_ARGENT;
        StringBuilder b = new StringBuilder();
        if (or > 0) {
            b.append(or).append(" or");
        }
        if (ag > 0) {
            b.append(b.isEmpty() ? "" : " ").append(ag).append(" argent");
        }
        if (d > 0 || b.isEmpty()) {
            b.append(b.isEmpty() ? "" : " ").append(d).append(" denier").append(d == 1 ? "" : "s");
        }
        return b.toString();
    }
}
