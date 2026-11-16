package me.branduzzo.checkHacks.managers;

import com.github.retrooper.packetevents.util.Vector3i;
import me.branduzzo.checkHacks.AlertMode;
import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.LangCheckData;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import me.branduzzo.checkHacks.utils.PacketSignProbe;
import me.branduzzo.checkHacks.utils.WebhookUtil;
import me.branduzzo.checkHacks.utils.WrappedTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LangCheckManager {

    private static final String LANG_KEY = "key.forward";
    private static final String LANG_FALLBACK = "\u27e6LANG_UNKNOWN\u27e7";

    private final CheckHacksPlugin plugin;
    private final Map<UUID, LangCheckData> activeChecks = new ConcurrentHashMap<>();
    private final Set<UUID> firstJoinChecked = ConcurrentHashMap.newKeySet();

    public LangCheckManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isChecking(UUID uuid) { return activeChecks.containsKey(uuid); }
    public Set<UUID> getFirstJoinChecked() { return firstJoinChecked; }

    public void startCheck(Player target, Player initiator, Map<String, String> languages) {
        UUID uuid = target.getUniqueId();

        if (activeChecks.containsKey(uuid)) {
            if (initiator != null) {
                initiator.sendMessage(plugin.getMessageManager().get("already-checking",
                        Map.of("player", target.getName())));
            }
            return;
        }

        LangCheckData data = new LangCheckData(uuid,
                initiator != null ? initiator.getUniqueId() : null, languages);
        activeChecks.put(uuid, data);

        if (initiator != null) {
            initiator.sendMessage(plugin.getMessageManager().get("lang-check-started",
                    Map.of("player", target.getName())));
        }

        FoliaScheduler.runAtEntity(plugin, target, () -> setupLangSign(target, data));
    }

    private void setupLangSign(Player target, LangCheckData data) {
        UUID uuid = target.getUniqueId();
        Location signLoc = PacketSignProbe.findProbeLocation(target);
        List<Component> lines = List.of(
                Component.translatable(LANG_KEY, LANG_FALLBACK),
                Component.empty(),
                Component.empty(),
                Component.empty());

        data.setSignLocation(signLoc);
        data.setAwaitingResponse(true);
        PacketSignProbe.open(target, signLoc, lines);
        PacketSignProbe.close(target);

        WrappedTask timeout = FoliaScheduler.runAtEntityLater(plugin, target, () -> {
            LangCheckData current = activeChecks.get(uuid);
            if (current != data || !current.beginResponse()) return;
            PacketSignProbe.restore(target, signLoc);
            current.setSignLocation(null);
            activeChecks.remove(uuid);
            Component msg = plugin.getMessageManager().get("lang-check-timeout",
                    Map.of("player", target.getName()));
            plugin.getMessageManager().broadcastAlerts(msg, false);
            notifyInitiator(data, msg);
        }, plugin.getConfigManager().getLangTimeoutTicks());

        data.setTimeoutTask(timeout);
    }

    public boolean acceptSignUpdate(Player target, Vector3i position, String[] lines) {
        return acceptSignUpdate(target, position.getX(), position.getY(), position.getZ(), lines);
    }

    public boolean acceptSignUpdate(Player target, int x, int y, int z, String[] lines) {
        LangCheckData data = activeChecks.get(target.getUniqueId());
        if (data == null) return false;
        Location loc = data.getSignLocation();
        if (loc == null) return false;
        if (loc.getBlockX() != x || loc.getBlockY() != y || loc.getBlockZ() != z) return false;
        if (!data.beginResponse()) return false;
        FoliaScheduler.runAtEntity(plugin, target, () -> handleResponse(target, data, lines));
        return true;
    }

    public void handleResponse(Player target, LangCheckData data, String[] lines) {
        UUID uuid = target.getUniqueId();
        if (!activeChecks.remove(uuid, data)) return;

        if (data.getTimeoutTask() != null) data.getTimeoutTask().cancel();
        Location loc = data.getSignLocation();
        if (loc != null) PacketSignProbe.restore(target, loc);
        data.setSignLocation(null);

        String response = lines.length > 0 ? lines[0].strip() : "";
        plugin.getLogger().info("[CheckHacks] LangCheck response from " + target.getName() + ": '" + response + "'");

        if (response.isEmpty() || response.equals(LANG_FALLBACK)) {
            Component msg = plugin.getMessageManager().get("lang-check-protected",
                    Map.of("player", target.getName()));
            plugin.getMessageManager().broadcastAlerts(msg, false);
            notifyInitiator(data, msg);
            return;
        }

        String detected = null;
        for (Map.Entry<String, String> entry : data.getLanguages().entrySet()) {
            if (entry.getValue().equalsIgnoreCase(response)) {
                detected = entry.getKey();
                break;
            }
        }

        String display = detected != null ? detected : "Unknown";
        String key = detected != null ? "lang-check-complete" : "lang-check-unknown";
        Component msg = plugin.getMessageManager().get(key,
                Map.of("player", target.getName(), "lang", display, "response", response));
        plugin.getMessageManager().broadcastAlerts(msg, false);
        notifyInitiator(data, msg);

        String checkerName = data.getInitiatorUUID() != null
                ? Optional.ofNullable(Bukkit.getPlayer(data.getInitiatorUUID()))
                .map(Player::getName).orElse("Console")
                : "AutoCheck";
        String language = detected != null ? detected : "Unknown";
        final String targetName = target.getName();
        final String targetUuid = target.getUniqueId().toString();
        final String responseText = response;

        FoliaScheduler.runAsync(plugin, () -> {
            long scanId = plugin.getDatabaseManager().saveScan("lang", targetName, targetUuid,
                    checkerName, "Lang check", false);
            if (scanId > 0) plugin.getDatabaseManager().saveLangResult(scanId, language, responseText);
        });

        ConfigManager cfg = plugin.getConfigManager();
        if (cfg.isLangDiscordEnabled()) {
            String description = cfg.getLangDiscordMessage()
                    .replace("&name&", target.getName())
                    .replace("&checker&", checkerName)
                    .replace("&lang&", detected != null ? detected : "Unknown (" + response + ")");
            WebhookUtil.sendRaw(cfg.getLangWebhookUrl(), cfg.getLangEmbedColor(),
                    description, cfg.isLangDiscordUseComponentsV2());
        }
    }

    private void notifyInitiator(LangCheckData data, Component msg) {
        if (data.getInitiatorUUID() == null) return;
        Player initiator = Bukkit.getPlayer(data.getInitiatorUUID());
        if (initiator == null || !initiator.isOnline()) return;
        boolean permission = initiator.hasPermission("checkhacks.alerts");
        if (!permission) {
            initiator.sendMessage(msg);
            return;
        }
        if (plugin.getAlertMode(initiator.getUniqueId()) == AlertMode.OFF) initiator.sendMessage(msg);
    }

    private void restoreSign(LangCheckData data) {
        Location loc = data.getSignLocation();
        if (loc == null) return;
        Player player = Bukkit.getPlayer(data.getTargetUUID());
        if (player != null && player.isOnline()) PacketSignProbe.restore(player, loc);
        data.setSignLocation(null);
        data.setAwaitingResponse(false);
    }

    public void forget(UUID uuid) {
        LangCheckData data = activeChecks.remove(uuid);
        if (data == null) return;
        if (data.getTimeoutTask() != null) data.getTimeoutTask().cancel();
        restoreSign(data);
    }

    public void cleanup() {
        for (LangCheckData data : activeChecks.values()) {
            if (data.getTimeoutTask() != null) data.getTimeoutTask().cancel();
            restoreSign(data);
        }
        activeChecks.clear();
    }
}
