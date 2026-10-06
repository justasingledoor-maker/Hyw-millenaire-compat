package dev.hywmill.politics.war;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Siege mercenaries (post-M5). About a minute before a host arrives, there is a small chance that its village has struck a
 * deal with a free company: 18-35 hired soldiers join the host for this siege only. They wear their company's look (see
 * {@code hywmill_equipment/squad_looks.json}), are equipped a step below a garrison's regulars and leave when the siege ends.
 * Pure: every draw comes from the siege's seed.
 */
public final class Mercenaries {
    private Mercenaries() {}

    /** Chance per siege that a company is hired. */
    public static final double CHANCE = 0.45;
    public static final int MIN = 18, MAX = 35;
    /** The roll is made this long (ticks) before the host arrives. */
    public static final long LEAD = 1200;
    /** Salt of the siege seed for the mercenary draws. */
    public static final long SALT = 0x6D657263L;

    /** A free company: its name, its look and the units it fields (drawn from evenly). */
    public record Company(String name, String look, List<String> units) {}

    public static final List<Company> COMPANIES = List.of(
            new Company("the Brabançon routiers", "norman.brabancons", List.of("warrior", "spear_man")),
            new Company("a company of Genoese crossbowmen", "norman.genoese", List.of("crossbowman", "crossbowman", "shieldman")),
            new Company("a band of Turcopoles", "norman.turcopoles", List.of("archer_rider")),
            new Company("the Daylamite free lances", "seljuk.daylamites", List.of("shieldman", "spear_man")),
            new Company("a band of nobushi", "jp.nobushi", List.of("warrior", "spear_man")),
            new Company("a ronin band", "jp.ronin", List.of("warrior", "archer", "spear_man")),
            new Company("the Bhil hillmen", "in.bhil", List.of("warrior", "spear_man")),
            new Company("a Pindari war band", "in.pindari", List.of("light_lancer_rider")),
            new Company("the Turki horse archers", "in.turki_archers", List.of("archer_rider")));

    /** Whether a company is hired, and if so which and how many; null when none is. */
    public static Hire roll(long seed, double chance) {
        SplittableRandom r = new SplittableRandom(seed ^ SALT);
        if (r.nextDouble() >= chance) {
            return null;
        }
        return hire(seed);
    }

    /** The company and its soldiers for this seed (no chance roll: an admin forcing one). */
    public static Hire hire(long seed) {
        SplittableRandom r = new SplittableRandom(seed ^ SALT ^ 0x5DEECE66DL);
        Company c = COMPANIES.get(r.nextInt(COMPANIES.size()));
        int n = MIN + r.nextInt(MAX - MIN + 1);
        List<String> units = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            units.add(c.units().get(r.nextInt(c.units().size())));
        }
        return new Hire(c, List.copyOf(units));
    }

    public record Hire(Company company, List<String> units) {}
}
