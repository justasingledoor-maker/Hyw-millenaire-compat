package dev.hywmill.politics.realm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * A realm's officials (docs/realm-design.md §11): the Marshal, the Chancellor, the Steward and the Spymaster, each with a name
 * of the capital's people, a temper, and an opinion of each player in the realm's service. They vote on what its knights
 * propose, take (or refuse) bribes, call a knight before the council who ignores his orders, and lean the council's own
 * judgement a little: a hawkish Marshal sends armies with less to spare, a cautious one holds more men back. Pure.
 */
public final class Officials {
    private Officials() {}

    public enum Office {
        MARSHAL("Marshal"), CHANCELLOR("Chancellor"), STEWARD("Steward"), SPYMASTER("Spymaster");

        public final String label;

        Office(String label) {
            this.label = label;
        }
    }

    public enum Temper {
        HAWK("hawkish"), DOVE("peaceable"), GREEDY("greedy"), HONOURABLE("honourable"), CAUTIOUS("cautious");

        public final String label;

        Temper(String label) {
            this.label = label;
        }
    }

    public static final class Official {
        public final String name;
        public final Office office;
        public final Temper temper;
        /** Opinion of each player in service, -100..100 (0: none yet). */
        public final Map<UUID, Integer> opinion = new LinkedHashMap<>();

        public Official(String name, Office office, Temper temper) {
            this.name = name;
            this.office = office;
            this.temper = temper;
        }

        public int of(UUID player) {
            return opinion.getOrDefault(player, 0);
        }

        public void change(UUID player, int delta) {
            opinion.put(player, Math.max(-100, Math.min(100, of(player) + delta)));
        }

        public String title() {
            return "the " + office.label + " " + name;
        }
    }

    static final Map<String, List<String>> NAMES = Map.of(
            "millenaire:norman", List.of("Guillaume", "Raoul", "Eudes", "Hugues", "Gautier", "Baudouin", "Thibaut", "Enguerrand"),
            "millenaire:byzantines", List.of("Nikephoros", "Ioannes", "Basileios", "Leon", "Theodoros", "Michael", "Konstantinos", "Andronikos"),
            "millenaire:japanese", List.of("Kagenori", "Tadaoki", "Masatsune", "Yoshimune", "Hidenaga", "Morikiyo", "Nagahide", "Kiyotaka"),
            "millenaire:seljuk", List.of("Arslan", "Tughril", "Kilij", "Suleiman", "Mahmud", "Yusuf", "Kaykhusraw", "Malik"),
            "millenaire:indian", List.of("Devaraja", "Vikrama", "Harsha", "Chandra", "Rudra", "Bhima", "Somadeva", "Anantavarman"),
            "millenaire:mayan", List.of("K'inich", "Itzamnaaj", "Chak Tok", "Yax Pas", "B'alam", "K'an Joy", "Uk'it", "Kan Mo'"),
            "millenaire:inuits", List.of("Aklak", "Nanuq", "Tulugaq", "Amaruq", "Siku", "Qannik", "Pukiq", "Kunik"));
    static final List<String> OTHER = List.of("Aldric", "Bertram", "Cedric", "Dunstan", "Edmund", "Godric", "Osric", "Wulfric");

    /** The four officials of the realm whose capital is {@code head}: the same every time for that capital. */
    public static List<Official> raise(UUID head, String culture) {
        Random rnd = new Random(head.getMostSignificantBits() ^ 0x0FF1C1A1L);
        List<String> names = new ArrayList<>(NAMES.getOrDefault(culture == null ? "" : culture, OTHER));
        List<Official> out = new ArrayList<>();
        for (Office o : Office.values()) {
            String name = names.remove(rnd.nextInt(names.size()));
            out.add(new Official(name, o, Temper.values()[rnd.nextInt(Temper.values().length)]));
        }
        return out;
    }

    public static Official of(List<Official> all, Office office) {
        return all.stream().filter(o -> o.office == office).findFirst().orElse(null);
    }

    public static double average(List<Official> all, UUID player) {
        return all.stream().mapToInt(o -> o.of(player)).average().orElse(0);
    }

    // ------------------------------------------------------------------ their opinion of a player

    /** An order answered: obeyed pleases them (the Marshal most), ignored displeases them. */
    public static void answered(List<Official> all, UUID player, Service.Answer a) {
        for (Official o : all) {
            int d = switch (a) {
                case OBEYED -> o.office == Office.MARSHAL ? 15 : 10;
                case PRESENT -> 3;
                case ABSENT -> o.office == Office.MARSHAL ? -15 : -10;
            };
            o.change(player, d);
        }
    }

    /**
     * A bribe of {@code deniers}: a greedy official is bought cheaply (30 a denier or), others less (12); an honourable one
     * refuses and thinks less of the player. Returns false if refused (the money is not taken).
     */
    public static boolean bribe(Official o, UUID player, int deniers) {
        if (o.temper == Temper.HONOURABLE) {
            o.change(player, -15);
            return false;
        }
        double or = deniers / 4096.0;
        o.change(player, (int) Math.round(or * (o.temper == Temper.GREEDY ? 30 : 12)));
        return true;
    }

    // ------------------------------------------------------------------ votes

    public record Vote(Official official, boolean yes) {}

    public record Verdict(boolean passed, List<Vote> votes) {
        public String describe() {
            StringBuilder b = new StringBuilder();
            for (Vote v : votes) {
                b.append(b.isEmpty() ? "" : "; ").append(v.official().office.label).append(" ").append(v.official().name).append(" (")
                        .append(v.official().temper.label).append(") ").append(v.yes() ? "for" : "against");
            }
            return b.toString();
        }
    }

    /** One official's chance to back a knight's proposal of war ({@code war}) or peace. */
    public static double support(Official o, UUID player, boolean war) {
        double p = 0.5 + o.of(player) / 200.0;
        p += switch (o.temper) {
            case HAWK -> war ? 0.3 : -0.3;
            case DOVE -> war ? -0.3 : 0.3;
            case CAUTIOUS -> war ? -0.15 : 0.1;
            case GREEDY, HONOURABLE -> 0;
        };
        if (o.office == Office.MARSHAL && war) {
            p += 0.05;
        }
        return Math.max(0.05, Math.min(0.95, p));
    }

    /** The council votes on a proposal: a majority of the officials (the Marshal breaks a tie in war, the Chancellor in peace). */
    public static Verdict vote(List<Official> all, UUID player, boolean war, long seed) {
        Random rnd = new Random(seed);
        List<Vote> votes = new ArrayList<>();
        int yes = 0;
        for (Official o : all) {
            boolean y = rnd.nextDouble() < support(o, player, war);
            votes.add(new Vote(o, y));
            yes += y ? 1 : 0;
        }
        boolean passed = yes * 2 > all.size();
        if (yes * 2 == all.size()) {
            Office tie = war ? Office.MARSHAL : Office.CHANCELLOR;
            passed = votes.stream().anyMatch(v -> v.official().office == tie && v.yes());
        }
        return new Verdict(passed, votes);
    }

    /** A knight called before the council: banished if he has ignored too many orders and they do not think well enough of him, or if they despise him. */
    public static boolean banish(int ignored, double average) {
        return (ignored >= Service.IGNORED_LIMIT && average < 25) || average <= -50;
    }

    // ------------------------------------------------------------------ their weight in the council

    /** How much stronger than its target an army must be: a hawkish Marshal asks less, a cautious one more. */
    public static double marginFactor(List<Official> all) {
        Official m = of(all, Office.MARSHAL);
        return m == null ? 1 : m.temper == Temper.HAWK ? 0.9 : m.temper == Temper.CAUTIOUS ? 1.15 : 1;
    }

    /** 1.8: how bold the realm's Marshal is in the field: 1 hawkish, -1 cautious or peaceable, else 0. */
    public static int boldness(List<Official> all) {
        Official m = of(all, Office.MARSHAL);
        return m == null ? 0 : m.temper == Temper.HAWK ? 1 : m.temper == Temper.CAUTIOUS || m.temper == Temper.DOVE ? -1 : 0;
    }

    /** Added to the share held back to defend: a cautious Marshal or a peaceable Chancellor hold more men home. */
    public static double reserveDelta(List<Official> all) {
        double d = 0;
        Official m = of(all, Office.MARSHAL), c = of(all, Office.CHANCELLOR);
        if (m != null) {
            d += m.temper == Temper.CAUTIOUS ? 0.1 : m.temper == Temper.HAWK ? -0.05 : 0;
        }
        if (c != null && c.temper == Temper.DOVE) {
            d += 0.05;
        }
        return d;
    }

    /** At peace: a hawkish or greedy Chancellor covets with less to spare; a peaceable one only with much more. */
    public static double covetFactor(List<Official> all) {
        Official c = of(all, Office.CHANCELLOR);
        return c == null ? 1 : c.temper == Temper.HAWK || c.temper == Temper.GREEDY ? 0.85 : c.temper == Temper.DOVE ? 1.25 : 1;
    }
}
