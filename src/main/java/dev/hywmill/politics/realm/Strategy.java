package dev.hywmill.politics.realm;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The realm's council (docs/realm-design.md §10): one mind for a whole realm. Each session it weighs the realm's army against
 * its enemies' and takes a stance; it holds back a reserve to defend (more when it is weaker, and every man of a threatened
 * member); and it divides the rest into field armies, as many as it can fill: each one a target, the members nearest it and
 * the member who leads it. It goes after what is worth most for what it costs: villages that rebelled against it, an enemy
 * whose army is away besieging, its own people under a foreign lord, villages it hates. A target it cannot yet take stays on
 * its list, waiting for men. At peace, a strong realm covets a neighbour it has a claim on. Pure.
 */
public final class Strategy {
    private Strategy() {}

    public enum Stance {
        AT_PEACE("at peace"), DEFENSIVE("on the defensive"), BALANCED("holding its ground"), AGGRESSIVE("on the attack");

        public final String label;

        Stance(String label) {
            this.label = label;
        }
    }

    /** What a goal is for: the aim a siege of it takes (a defence takes none). */
    public enum Kind {
        DEFEND("Defend"), RECLAIM("Take back"), STRIKE("Strike"), CONQUER("Annex"), SUBJUGATE("Subdue"), PUNISH("Punish");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /**
     * A member as the council sees it. {@code men}/{@code power}: what it pledges now (its share of its free garrison, and that
     * share's strength); {@code seatFree}: it can lead an army (its own host is home); {@code threatened}: a host is announced,
     * mustering or marching against it; {@code besieged}: one is at its walls.
     */
    public record Member(UUID id, int x, int z, int men, double power, boolean capital, boolean seatFree, boolean threatened, boolean besieged) {}

    /**
     * An enemy village (at war with the realm). {@code defense}: its strength at home (garrison, Millénaire fighters, walls);
     * {@code formerMember}: it rebelled against the realm or broke away; {@code hostAway}: its army is out besieging; {@code
     * kin}: of the capital's people, under another realm; {@code besieged}: a siege of it is under way already.
     */
    public record Target(UUID id, int x, int z, double defense, boolean formerMember, boolean hostAway, boolean hated, boolean kin, boolean besieged,
                         int tier) {}

    /** A field army: who leads it ({@code seat}), what it marches on, the members in it, its men and strength. */
    public record Army(UUID seat, UUID target, Kind kind, List<UUID> members, int men, double power, double needed) {}

    /** A goal on the council's list: an army raised for it ({@code ready}), or waiting for men; a defence. */
    public record Goal(Kind kind, UUID target, int men, double power, double needed, boolean ready) {}

    public record Plan(Stance stance, double ratio, int men, int reserve, List<Goal> goals, List<Army> armies) {}

    /** An army must be at least as strong as what it marches on (a village's own AI goes at 0.9). */
    public static final double MARGIN = 1.0;
    /** The smallest field army, in men. */
    public static final int MIN_ARMY = 10;
    public static final int MAX_ARMIES = 3;
    /** Distance at which a target's worth halves (blocks). */
    public static final double REACH = 1500;

    public static Stance stance(double ours, double theirs, boolean atWar) {
        if (!atWar) {
            return Stance.AT_PEACE;
        }
        double r = ratio(ours, theirs);
        return r >= 1.5 ? Stance.AGGRESSIVE : r >= 0.8 ? Stance.BALANCED : Stance.DEFENSIVE;
    }

    public static double ratio(double ours, double theirs) {
        return theirs <= 0 ? (ours <= 0 ? 1 : 99) : ours / theirs;
    }

    /** The share of the army held back to defend. */
    public static double reserveShare(Stance s) {
        return switch (s) {
            case AGGRESSIVE -> 0.2;
            case BALANCED -> 0.35;
            case DEFENSIVE -> 0.6;
            case AT_PEACE -> 1.0;
        };
    }

    /** What a siege of it would be for. */
    public static Kind kind(Target t) {
        if (t.formerMember()) {
            return Kind.RECLAIM;
        }
        if (t.hostAway()) {
            return Kind.STRIKE;
        }
        if (t.kin()) {
            return Kind.CONQUER;
        }
        return t.hated() ? Kind.PUNISH : Kind.SUBJUGATE;
    }

    /** What taking it is worth to the realm. */
    public static double worth(Target t) {
        return 1.0 + (t.formerMember() ? 2.0 : 0) + (t.hostAway() ? 1.5 : 0) + (t.hated() ? 1.0 : 0) + (t.kin() ? 0.75 : 0) + 0.2 * t.tier();
    }

    static double dist(int x1, int z1, int x2, int z2) {
        return Math.sqrt((double) (x1 - x2) * (x1 - x2) + (double) (z1 - z2) * (z1 - z2));
    }

    /** Its worth for what it costs: weaker, nearer, worthier first. */
    public static double score(Target t, List<Member> members) {
        double d = members.stream().mapToDouble(m -> dist(m.x(), m.z(), t.x(), t.z())).min().orElse(REACH);
        return worth(t) / Math.max(1, t.defense() * MARGIN) / (1 + d / REACH);
    }

    /**
     * The council's plan. Threatened and besieged members keep their men home to defend; the others hold back the stance's
     * reserve. The rest is divided among the best targets, nearest members first, while each army can be filled (at least
     * {@link #MIN_ARMY} men and {@link #MARGIN} times the target's defence) and led by a member whose host is home.
     */
    public static Plan plan(List<Member> members, List<Target> targets, double enemyPower, boolean atWar) {
        int men = members.stream().mapToInt(Member::men).sum();
        double power = members.stream().mapToDouble(Member::power).sum();
        Stance stance = stance(power, enemyPower, atWar);
        double hold = reserveShare(stance);
        List<Goal> goals = new ArrayList<>();
        int reserve = 0;
        int[] freeMen = new int[members.size()];
        double[] freePow = new double[members.size()];
        for (int i = 0; i < members.size(); i++) {
            Member m = members.get(i);
            if (m.threatened() || m.besieged()) {
                goals.add(new Goal(Kind.DEFEND, m.id(), m.men(), m.power(), 0, true));
                reserve += m.men();
                continue;
            }
            int keep = (int) Math.round(m.men() * hold);
            reserve += keep;
            freeMen[i] = m.men() - keep;
            freePow[i] = m.men() <= 0 ? 0 : m.power() * freeMen[i] / m.men();
        }
        List<Army> armies = new ArrayList<>();
        if (stance != Stance.AT_PEACE) {
            List<Target> order = new ArrayList<>(targets);
            order.removeIf(Target::besieged);
            order.sort(Comparator.comparingDouble((Target t) -> -score(t, members)).thenComparing(Target::id));
            Set<Integer> used = new HashSet<>();
            for (Target t : order) {
                double needed = t.defense() * MARGIN;
                List<Integer> near = new ArrayList<>();
                for (int i = 0; i < members.size(); i++) {
                    if (!used.contains(i) && freeMen[i] > 0) {
                        near.add(i);
                    }
                }
                near.sort(Comparator.comparingDouble(i -> dist(members.get(i).x(), members.get(i).z(), t.x(), t.z())));
                List<Integer> group = new ArrayList<>();
                int gMen = 0;
                double gPow = 0;
                for (int i : near) {
                    if (gPow >= needed && gMen >= MIN_ARMY) {
                        break;
                    }
                    group.add(i);
                    gMen += freeMen[i];
                    gPow += freePow[i];
                }
                Integer seat = group.stream().filter(i -> members.get(i).seatFree()).max(Comparator.comparingInt(i -> freeMen[i])).orElse(null);
                boolean ready = armies.size() < MAX_ARMIES && gPow >= needed && gMen >= MIN_ARMY && seat != null;
                goals.add(new Goal(kind(t), t.id(), gMen, gPow, needed, ready));
                if (ready) {
                    used.addAll(group);
                    List<UUID> ids = new ArrayList<>();
                    group.forEach(i -> ids.add(members.get(i).id()));
                    armies.add(new Army(members.get(seat).id(), t.id(), kind(t), ids, gMen, gPow, needed));
                }
            }
        }
        return new Plan(stance, ratio(power, enemyPower), men, reserve, goals, armies);
    }

    /**
     * At peace: the neighbour a strong realm covets, if any: one it has a claim on (it rebelled or broke away, the realm hates
     * it, or it is of the capital's people under another lord), that four fifths of the army could take; the worthiest for its
     * cost. Null: none.
     */
    @Nullable
    public static Target covet(List<Member> members, List<Target> neighbours) {
        double power = members.stream().mapToDouble(Member::power).sum() * (1 - reserveShare(Stance.AGGRESSIVE));
        return neighbours.stream()
                .filter(t -> (t.formerMember() || t.hated() || t.kin()) && !t.besieged() && power >= t.defense() * MARGIN)
                .max(Comparator.comparingDouble((Target t) -> score(t, members)).thenComparing(Target::id))
                .orElse(null);
    }
}
