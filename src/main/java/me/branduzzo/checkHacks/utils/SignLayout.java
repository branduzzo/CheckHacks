package me.branduzzo.checkHacks.utils;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.function.Consumer;

/** Four physical sign rows; mod slots consume the current batch in order. */
public record SignLayout(List<String> rows) {
    public static final SignLayout HACK_DEFAULT = new SignLayout(List.of("mod", "mod", "mod", "control"));
    public static final SignLayout LANG_DEFAULT = new SignLayout(List.of("language", "blank", "blank", "blank"));

    public SignLayout {
        rows = List.copyOf(rows);
    }

    /**
     * Reads exactly four row roles, preserving literal text after {@code text:}.
     * Hack layouts need a mod slot to make batching progress; language layouts
     * need a language row to locate the response. Missing settings use legacy
     * defaults silently, while malformed explicit layouts also emit a warning.
     */
    public static SignLayout from(ConfigurationSection config, String path, boolean hacks, Consumer<String> warning) {
        SignLayout fallback = hacks ? HACK_DEFAULT : LANG_DEFAULT;
        if (config == null || !config.contains(path)) return fallback;
        List<String> rows = config.getStringList(path);
        boolean valid = rows.size() == 4 && rows.stream().allMatch(row ->
                row.equals("blank") || row.equals("language") || row.equals("control")
                        || (hacks && row.equals("mod")) || row.startsWith("text:"));
        if (hacks) valid &= rows.contains("mod");
        else valid &= rows.contains("language");
        if (!valid) {
            warning.accept("Invalid sign-layout." + path + "; using the default four rows.");
            return fallback;
        }
        return new SignLayout(rows);
    }

    public int modSlots() {
        return (int) rows.stream().filter("mod"::equals).count();
    }
}
