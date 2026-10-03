package me.branduzzo.checkHacks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every kind of client check CheckHacks can run.
 * <p>
 * A check is either {@code instant} (it only reads data the server already
 * knows, like the client brand or the registered plugin channels) or it needs
 * signs (the hack and language checks).
 */
public enum CheckType {

    BRAND("brand", "Client Brand", "checkhacks.checkbrand", true,
            "Reads the client brand string (vanilla, fabric, forge, lunar, wurst...)"),

    MODLIST("modlist", "Mod List", "checkhacks.checkmods", true,
            "Lists every mod that can be mapped from the plugin channels"),

    HACK("hack", "Mod / Hack Scan", "checkhacks.check", false,
            "Sign scan that matches every configured hack/mod"),

    LANG("lang", "Language", "checkhacks.checklang", false,
            "Sign scan that detects the client language");

    private final String id;
    private final String displayName;
    private final String permission;
    private final boolean instant;
    private final String description;

    CheckType(String id, String displayName, String permission, boolean instant, String description) {
        this.id          = id;
        this.displayName = displayName;
        this.permission  = permission;
        this.instant     = instant;
        this.description = description;
    }

    public String getId()          { return id; }
    public String getDisplayName() { return displayName; }
    public String getPermission()  { return permission; }
    public boolean isInstant()     { return instant; }
    public String getDescription() { return description; }

    public static CheckType byId(String id) {
        if (id == null) return null;
        String norm = id.trim().toLowerCase(Locale.ROOT);
        for (CheckType type : values())
            if (type.id.equals(norm)) return type;
        return null;
    }

    public static List<CheckType> byIds(List<String> ids) {
        List<CheckType> types = new ArrayList<>();
        if (ids == null) return types;
        for (String id : ids) {
            CheckType type = byId(id);
            if (type != null && !types.contains(type)) types.add(type);
        }
        return types;
    }

    /** Comma separated list of every check id, used in messages and tab completion. */
    public static String idList() {
        StringBuilder sb = new StringBuilder();
        for (CheckType type : values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(type.id);
        }
        return sb.toString();
    }

    public static List<String> ids() {
        List<String> out = new ArrayList<>(values().length);
        for (CheckType type : values()) out.add(type.id);
        return out;
    }
}
