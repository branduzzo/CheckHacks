package com.veneer.core;

import com.veneer.VeneerPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translatable chat messages from {@code messages/<language>.yml}, formatted with MiniMessage.
 *
 * <p>{@code plugin.messages()} speaks {@code general.language}; {@link #in(CommandSender)} gives the same API
 * in the language a player picked with {@code /veneer lang} (stored in {@code data.db}), so text sent to one
 * player goes through {@code in(player)}, and broadcasts render per recipient ({@link Alerts}).
 */
public class Messages {

    private static final List<String> BUNDLED = List.of(
            "en", "it", "br", "de", "es", "fr", "lolcat", "ru", "uwu");
    /** A player's choice that follows the language their client is set to. */
    public static final String AUTO = "auto";

    /** Loaded files, the per-language views and the players' choices, shared by the server-wide instance and its views. */
    private static final class Shared {
        final Map<String, FileConfiguration> files = new ConcurrentHashMap<>();
        final Map<String, Messages> views = new ConcurrentHashMap<>();
        final Map<UUID, String> chosen = new ConcurrentHashMap<>();
        volatile java.util.Set<String> available = java.util.Set.of();
        Messages root;
    }

    private final VeneerPlugin plugin;
    private final Shared shared;
    /** This view's language; null for the server-wide instance, which follows {@code general.language}. */
    private final String lang;

    public Messages(VeneerPlugin plugin) {
        this(plugin, new Shared(), null);
        shared.root = this;
        load();
    }

    private Messages(VeneerPlugin plugin, Shared shared, String lang) {
        this.plugin = plugin;
        this.shared = shared;
        this.lang = lang;
    }

    public void load() {
        for (String code : BUNDLED) ensureFile("messages/" + code + ".yml");
        shared.files.clear();
        shared.views.clear();
        shared.available = java.util.Set.copyOf(languages());
        if (!exists(plugin.config().language()))
            plugin.getLogger().warning("messages/" + plugin.config().language() + ".yml not found, falling back to en.yml");
    }

    /** Reads the players' language choices; called once the database is open. */
    public void loadChoices() {
        shared.chosen.clear();
        plugin.database().settings().getLanguageChoices().forEach((uuid, code) -> {
            try {
                shared.chosen.put(UUID.fromString(uuid), code);
            } catch (IllegalArgumentException ignored) {
                // not a uuid, skip the row
            }
        });
    }

    /** The language codes there is a file for in {@code plugins/Veneer/messages/}. */
    public List<String> languages() {
        List<String> codes = new ArrayList<>();
        File[] files = new File(plugin.getDataFolder(), "messages").listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) for (File f : files) codes.add(f.getName().substring(0, f.getName().length() - 4));
        codes.sort(null);
        return codes;
    }

    /** Whether there was a file for {@code code} at the last (re)load. */
    public boolean exists(String code) {
        return shared.available.contains(code);
    }

    /** The code this instance speaks. */
    public String language() {
        String code = lang != null ? lang : plugin.config().language();
        return exists(code) ? code : "en";
    }

    /** The player's choice: a language code, {@link #AUTO}, or null for the server language. */
    public String choice(UUID player) {
        return shared.chosen.get(player);
    }

    /** Stores the player's choice ({@code null} goes back to the server language). */
    public void choose(UUID player, String code) {
        if (code == null) shared.chosen.remove(player);
        else shared.chosen.put(player, code);
        FoliaScheduler.runAsync(plugin, () -> plugin.database().settings().saveLanguageChoice(player.toString(), code));
    }

    /** The language the client is set to, as a file code (pt_br is {@code br}, lol_us {@code lolcat}), or null without a file. */
    @SuppressWarnings("deprecation")
    public String clientLanguage(Player player) {
        String locale = player.getLocale().toLowerCase(Locale.ROOT);
        String code = locale.contains("_") ? locale.substring(0, locale.indexOf('_')) : locale;
        if (code.equals("pt")) code = "br";
        else if (code.equals("lol")) code = "lolcat";
        return exists(code) ? code : null;
    }

    /** Messages in the language {@code to} reads: their {@code /veneer lang} choice, else the server language. */
    public Messages in(CommandSender to) {
        if (!(to instanceof Player player)) return shared.root;
        String code = shared.chosen.get(player.getUniqueId());
        if (code == null) return shared.root;
        if (code.equals(AUTO)) code = clientLanguage(player);
        if (code == null || !exists(code)) return shared.root;
        return shared.views.computeIfAbsent(code, c -> new Messages(plugin, shared, c));
    }

    private FileConfiguration file() {
        return shared.files.computeIfAbsent(language(), code -> {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages/" + code + ".yml"));
            yaml.setDefaults(bundled(code + ".yml"));
            return yaml;
        });
    }

    /** The jar's copy of a language file laid over the jar's en.yml, so a key an edited file lacks still resolves. */
    private YamlConfiguration bundled(String fileName) {
        YamlConfiguration merged = new YamlConfiguration();
        for (String name : new String[]{"en.yml", fileName}) {
            try (java.io.InputStream in = plugin.getResource("messages/" + name)) {
                if (in == null) continue;
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                for (String key : yaml.getKeys(true))
                    if (!yaml.isConfigurationSection(key)) merged.set(key, yaml.get(key));
            } catch (java.io.IOException ignored) {
                // keep what was read so far
            }
        }
        return merged;
    }

    private void ensureFile(String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.exists()) {
            f.getParentFile().mkdirs();
            plugin.saveResource(name, false);
        }
    }

    public String getRaw(String key) {
        String text = file().getString(key);   // without a fallback argument, so the bundled defaults apply
        return text != null ? text : "<red>Missing message: " + key;
    }

    /** The message for {@code key} with {@code {prefix}} and {@code {name}} placeholders filled in; values are escaped. */
    public Component get(String key, Map<String, String> placeholders) {
        Map<String, String> escaped = new java.util.HashMap<>();
        placeholders.forEach((k, v) -> escaped.put(k, escape(v)));
        return format(getRaw(key), escaped);
    }

    public Component get(String key) {
        return get(key, Map.of());
    }

    /**
     * Formats an arbitrary MiniMessage string the same way as a message from the file. Placeholder values are
     * inserted as given (escape them first); PlaceholderAPI runs on the template before, so values are not expanded.
     */
    public Component format(String raw, Map<String, String> placeholders) {
        raw = raw.replace("{prefix}", plugin.config().prefix());
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) raw = applyPapi(raw);
        for (Map.Entry<String, String> e : placeholders.entrySet())
            raw = raw.replace("{" + e.getKey() + "}", e.getValue());
        return Text.mini(raw);
    }

    /** A placeholder value that is already MiniMessage; any other value is escaped so players cannot inject tags. */
    public record Raw(String text) {}

    /** The text of {@code key} as a placeholder value that is not escaped. */
    public Raw raw(String key) {
        return new Raw(getRaw(key));
    }

    /** Escapes text from players or the network (brands, channels, names) for use inside MiniMessage. */
    public static String escape(String text) {
        return MiniMessage.miniMessage().escapeTags(text);
    }

    private Map<String, String> values(Object... pairs) {
        Map<String, String> map = new java.util.HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object v = pairs[i + 1];
            map.put(String.valueOf(pairs[i]), v instanceof Raw r ? r.text() : escape(String.valueOf(v)));
        }
        return map;
    }

    /** The message {@code key} with placeholders given as name, value, name, value... */
    public Component with(String key, Object... pairs) {
        return format(getRaw(key), values(pairs));
    }

    /** The message {@code key} as plain text with {@code {name}} placeholders filled in (no prefix, no formatting pass). */
    public String plain(String key, Object... pairs) {
        String text = getRaw(key);
        for (Map.Entry<String, String> e : values(pairs).entrySet()) text = text.replace("{" + e.getKey() + "}", e.getValue());
        return text;
    }

    /** Sends {@code key} in the language of {@code to}. */
    public void send(CommandSender to, String key, Object... pairs) {
        to.sendMessage(in(to).with(key, pairs));
    }

    /** Prefixed one-liner for commands that have no entry in the language files. */
    public Component prefixed(String miniMessage) {
        return format("{prefix}" + miniMessage, Map.of());
    }

    private static String applyPapi(String text) {
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            return (String) papi.getMethod("setPlaceholders", org.bukkit.entity.Player.class, String.class)
                    .invoke(null, null, text);
        } catch (Exception e) {
            return text;
        }
    }
}
