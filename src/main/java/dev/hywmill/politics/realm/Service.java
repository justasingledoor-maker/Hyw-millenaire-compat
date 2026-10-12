package dev.hywmill.politics.realm;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A player in a realm's service (docs/realm-design.md §11): a volunteer (called, never bound), a mercenary (a paid contract,
 * a starting company once) or a knight (sworn into the realm: a stipend, orders, a voice in its council). Its orders, its
 * record of orders obeyed and ignored, and what it was last told. The rules are pure.
 */
public final class Service {
    public enum Role { VOLUNTEER, MERCENARY, KNIGHT }

    /** How an order was answered: at the rally point, at the fight elsewhere, or not at all. */
    public enum Answer { OBEYED, PRESENT, ABSENT }

    public final UUID player;
    public Role role;
    /** The realm served: its head (capital). */
    public UUID realm;
    public long since;
    /** A mercenary's contract ends then; Long.MAX_VALUE for the others. */
    public long until = Long.MAX_VALUE;
    public int obeyed, present, ignored;
    /** The last day (day number) the player was paid. */
    public long paidDay = -1;
    /** The last time the player proposed something to the council. */
    public long lastProposal = -1;
    public final List<Order> orders = new ArrayList<>();
    /** What the player was last told (newest first, at most {@link #NOTES}). */
    public final List<String> notes = new ArrayList<>();

    public static final int NOTES = 3;

    public Service(UUID player) {
        this.player = player;
    }

    /**
     * An order (or, for a volunteer, a call): for {@code siege}, to be at the rally point ({@code x}, {@code z}) when the fight
     * begins. {@code defend}: a siege of the realm to break. {@code atRally}/{@code near}: where the player was seen while it
     * was fought ({@code fought}: it came to blows).
     */
    public static final class Order {
        public final UUID siege;
        public final UUID target;
        public final boolean defend;
        public final int x, z;
        public final String side;
        public final long issued;
        public boolean atRally, near, fought;

        public Order(UUID siege, UUID target, boolean defend, int x, int z, String side, long issued) {
            this.siege = siege;
            this.target = target;
            this.defend = defend;
            this.x = x;
            this.z = z;
            this.side = side;
            this.issued = issued;
        }
    }

    public void note(String text) {
        notes.add(0, text);
        while (notes.size() > NOTES) {
            notes.remove(notes.size() - 1);
        }
    }

    // ------------------------------------------------------------------ rules

    /** The rally point counts within this distance (blocks). */
    public static final int RALLY = 40;
    /** "At the fight": within the target's radius plus this. */
    public static final int NEAR = 96;
    /** A mercenary's contract (days); signing again renews it without a second bonus. */
    public static final int CONTRACT_DAYS = 3;
    /** The one-time signing bonus (deniers: 1 denier or) and the company that comes with it. */
    public static final int SIGN_BONUS = 4096;
    public static final List<String> COMPANY = List.of("spear_man", "spear_man", "archer");
    /** Pay for an order obeyed, and for being at the fight elsewhere (deniers). */
    public static final int OBEY_PAY = 4096, PRESENT_PAY = 768;
    /** A knight is called before the council after this many orders ignored. */
    public static final int IGNORED_LIMIT = 3;
    /** Exile from a realm (days) after banishment; a mercenary who breaks his contract waits this long to sign again. */
    public static final int EXILE_DAYS = 14, BROKEN_DAYS = 7;

    /** Pay each dawn (deniers): a volunteer none, a mercenary (while the realm is at war) 4 argent and half an argent a village, a knight 6 argent and one a village. */
    public static int wage(Role role, int villages, boolean atWar) {
        return switch (role) {
            case VOLUNTEER -> 0;
            case MERCENARY -> atWar ? 256 + 32 * villages : 0;
            case KNIGHT -> 384 + 64 * villages;
        };
    }

    /** How an order was answered, from where the player was seen while it was fought. */
    public static Answer answer(boolean atRally, boolean near) {
        return atRally ? Answer.OBEYED : near ? Answer.PRESENT : Answer.ABSENT;
    }

    /** The pay for an answer (a volunteer is paid only for being at the rally point, at a third). */
    public static int pay(Role role, Answer a) {
        int p = switch (a) {
            case OBEYED -> OBEY_PAY;
            case PRESENT -> PRESENT_PAY;
            case ABSENT -> 0;
        };
        return role == Role.VOLUNTEER ? (a == Answer.OBEYED ? p / 3 : 0) : p;
    }

    /** A knight's title by the realm's culture. */
    public static String knightTitle(String culture) {
        return switch (culture == null ? "" : culture) {
            case "millenaire:norman" -> "Knight";
            case "millenaire:byzantines" -> "Archon";
            case "millenaire:japanese" -> "Samurai";
            case "millenaire:seljuk" -> "Sipahi";
            case "millenaire:indian" -> "Sardar";
            case "millenaire:mayan" -> "Nacom";
            case "millenaire:inuits" -> "Champion";
            default -> "Knight";
        };
    }

    public static String label(Role role, String culture) {
        return switch (role) {
            case VOLUNTEER -> "Volunteer";
            case MERCENARY -> "Mercenary";
            case KNIGHT -> knightTitle(culture);
        };
    }
}
