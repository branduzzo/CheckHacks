package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;

public class ConfigMerger {

    private static final String[] FILES = {
            "config.yml",
            "checkhacks.yml",
            "checklang.yml",
            "messages/en.yml",
            "messages/it.yml",
            "messages/br.yml",
            "messages/de.yml",
            "messages/es.yml",
            "messages/fr.yml",
            "messages/lolcat.yml",
            "messages/ru.yml",
            "messages/uwu.yml"
    };

    private static final Set<String> USER_OWNED_ROOTS = Set.of("command-rules");

    private final CheckHacksPlugin plugin;

    public ConfigMerger(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    public int mergeAll() {
        int added = 0;
        for (String name : FILES) added += mergeFile(name);
        return added;
    }

    private int mergeFile(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
            return 0;
        }
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) return 0;
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (raw.startsWith("\uFEFF")) raw = raw.substring(1);
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new StringReader(raw));
            YamlConfiguration user = YamlConfiguration.loadConfiguration(file);
            int added = copyMissing(defaults, user, "");
            if (added > 0) {
                backup(file);
                user.save(file);
            }
            return added;
        } catch (Exception e) {
            plugin.getLogger().warning("Config merger failed for " + name + ": " + e.getMessage());
            return 0;
        }
    }

    private int copyMissing(YamlConfiguration defaults, YamlConfiguration user, String root) {
        int added = 0;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (isUserOwned(key)) continue;
            if (user.contains(key)) continue;
            user.set(key, defaults.get(key));
            added++;
        }
        return added;
    }

    private boolean isUserOwned(String key) {
        if (key.equals("alerts.default-mode")) return true;
        for (String root : USER_OWNED_ROOTS) {
            if (key.equals(root) || key.startsWith(root + ".")) return true;
        }
        return false;
    }

    private void backup(File file) throws Exception {
        File backup = new File(file.getParentFile(), file.getName() + ".bak");
        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
