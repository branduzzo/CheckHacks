package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Map;

public class MessageManager {

    private final CheckHacksPlugin plugin;
    private FileConfiguration messages;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public MessageManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        String lang = plugin.getConfigManager().getLanguage();
        ensureFile("messages/en.yml");
        ensureFile("messages/it.yml");
        ensureFile("messages/br.yml");
        ensureFile("messages/de.yml");
        ensureFile("messages/es.yml");
        ensureFile("messages/fr.yml");
        ensureFile("messages/lolcat.yml");
        ensureFile("messages/ru.yml");
        ensureFile("messages/vi.yml");
        ensureFile("messages/uwu.yml");
        File file = new File(plugin.getDataFolder(), "messages/" + lang + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("messages/" + lang + ".yml not found, falling back to en.yml");
            file = new File(plugin.getDataFolder(), "messages/en.yml");
        }
        messages = YamlConfiguration.loadConfiguration(file);
    }

    private void ensureFile(String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.exists()) {
            f.getParentFile().mkdirs();
            plugin.saveResource(name, false);
        }
    }

    public String getRaw(String key) {
        return messages.getString(key, "<red>Missing message: " + key);
    }

    public Component get(String key, Map<String, String> placeholders) {
        String prefix = plugin.getConfigManager().getPrefix();
        String raw = getRaw(key).replace("{prefix}", prefix);
        for (Map.Entry<String, String> e : placeholders.entrySet())
            raw = raw.replace("{" + e.getKey() + "}", e.getValue());
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) raw = applyPapi(null, raw);
        return MM.deserialize(raw);
    }

    public Component get(String key) {
        return get(key, Map.of());
    }

    public void broadcastAlerts(Component msg) {
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.hasPermission("checkhacks.alerts") && plugin.hasAlertsEnabled(p.getUniqueId()))
                p.sendMessage(msg);
        Bukkit.getConsoleSender().sendMessage(msg);
    }

    private String applyPapi(Player player, String text) {
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            return (String) papi.getMethod("setPlaceholders", Player.class, String.class)
                    .invoke(null, player, text);
        } catch (Exception e) {
            return text;
        }
    }
}