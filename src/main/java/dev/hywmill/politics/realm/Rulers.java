package dev.hywmill.politics.realm;

/** Who rules a village and how its people call him (1.7): the ruler's title by culture. Pure. */
public final class Rulers {
    private Rulers() {}

    /** The title of a realm's ruler by its capital's culture: the King, the Basileus, the Daimyo... */
    public static String title(String culture) {
        return switch (culture == null ? "" : culture) {
            case "millenaire:norman" -> "King";
            case "millenaire:byzantines" -> "Basileus";
            case "millenaire:japanese" -> "Daimyo";
            case "millenaire:seljuk" -> "Sultan";
            case "millenaire:indian" -> "Raja";
            case "millenaire:mayan" -> "Ajaw";
            case "millenaire:inuits" -> "Chief";
            default -> "Lord";
        };
    }

    /** The title of a village's own headman (a village that rules no realm). */
    public static String headman(String culture) {
        return switch (culture == null ? "" : culture) {
            case "millenaire:norman" -> "Seigneur";
            case "millenaire:byzantines" -> "Strategos";
            case "millenaire:japanese" -> "Bugyo";
            case "millenaire:seljuk" -> "Bey";
            case "millenaire:indian" -> "Thakur";
            case "millenaire:mayan" -> "Batab";
            case "millenaire:inuits" -> "Elder";
            default -> "Headman";
        };
    }
}
