package me.branduzzo.checkHacks;

/**
 * Client flavour of a player. The old hack check only knew vanilla/fabric/
 * forge, the brand check can do a lot better now, so everything is kept in
 * this enum and every new flavour is just one more constant.
 */
public enum ClientType {

    VANILLA("Vanilla"),
    FABRIC("Fabric"),
    FORGE("Forge"),
    NEOFORGE("NeoForge"),
    QUILT("Quilt"),
    LUNAR("Lunar Client"),
    BADLION("Badlion Client"),
    FEATHER("Feather Client"),
    LABYMOD("LabyMod"),
    BEDROCK("Bedrock"),
    UNKNOWN("Unknown");

    private final String display;

    ClientType(String display) {
        this.display = display;
    }

    public String getDisplay() { return display; }
}
