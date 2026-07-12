package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.LangCheckData;
import me.branduzzo.checkHacks.model.LangResultRow;
import me.branduzzo.checkHacks.session.SignSession;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import me.branduzzo.checkHacks.utils.WebhookUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LangCheckManager {

    private static final String LANG_KEY = "key.forward";
    private static final String LANG_FALLBACK = "\u27e6LANG_UNKNOWN\u27e7";

    private final CheckHacksPlugin plugin;
    private final Map<UUID, LangCheckData> activeChecks = new ConcurrentHashMap<>();

    public LangCheckManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isChecking(UUID uuid) {
        return activeChecks.containsKey(uuid);
    }

    public Location getActiveSignLocation(UUID uuid) {
        LangCheckData data = activeChecks.get(uuid);
        if (data == null || data.getSignSession() == null) return null;
        return data.getSignSession().getSignLocation();
    }

    public void startCheck(Player target, Player initiator, Map<String, String> languages) {
        UUID uuid = target.getUniqueId();

        if (plugin.hasActiveSignSession(uuid)) {
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

    public void abort(UUID uuid) {
        LangCheckData data = activeChecks.remove(uuid);
        if (data == null) return;
        if (data.getSignSession() != null) data.getSignSession().restore();
    }

    private void setupLangSign(Player target, LangCheckData data) {
        UUID uuid = target.getUniqueId();
        List<Component> lines = List.of(Component.translatable(LANG_KEY, LANG_FALLBACK));

        Optional<SignSession> session = SignSession.open(
                plugin,
                target,
                lines,
                plugin.getConfigManager().getLangTimeoutTicks(),
                () -> activeChecks.containsKey(uuid),
                () -> {
                    if (activeChecks.remove(uuid) == null) return;
                    Component msg = plugin.getMessageManager().get("lang-check-timeout",
                            Map.of("player", target.getName()));
                    plugin.getMessageManager().broadcastAlerts(msg);
                    notifyInitiator(data, msg);
                });

        if (session.isEmpty()) {
            activeChecks.remove(uuid);
            return;
        }
        data.setSignSession(session.get());
    }

    public void handleResponse(Player target, String[] lines) {
        UUID uuid = target.getUniqueId();
        LangCheckData data = activeChecks.get(uuid);
        if (data == null) return;

        SignSession session = data.getSignSession();
        if (session != null && !session.claimForResponse()) return;
        if (activeChecks.remove(uuid) == null) return;

        if (session != null) {
            session.restore();
            data.setSignSession(null);
        }

        String response = lines.length > 0 ? lines[0].strip() : "";
        plugin.getLogger().info("[CheckHacks] LangCheck response from " + target.getName()
                + ": '" + response + "'");

        String checkerName = resolveCheckerName(data);

        if (response.isEmpty() || response.equals(LANG_FALLBACK)) {
            Component msg = plugin.getMessageManager().get("lang-check-protected",
                    Map.of("player", target.getName()));
            plugin.getMessageManager().broadcastAlerts(msg);
            notifyInitiator(data, msg);
            plugin.getDatabaseManager().runAsync(() -> plugin.getDatabaseManager().saveLangScan(
                    target.getName(),
                    target.getUniqueId().toString(),
                    checkerName,
                    "Lang check",
                    false,
                    null));
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
        String msgKey = detected != null ? "lang-check-complete" : "lang-check-unknown";
        Component msg = plugin.getMessageManager().get(msgKey,
                Map.of("player", target.getName(), "lang", display, "response", response));
        plugin.getMessageManager().broadcastAlerts(msg);
        notifyInitiator(data, msg);

        final String finalDisplay = display;
        plugin.getDatabaseManager().runAsync(() -> plugin.getDatabaseManager().saveLangScan(
                target.getName(),
                target.getUniqueId().toString(),
                checkerName,
                "Lang check",
                false,
                new LangResultRow(finalDisplay, response)));

        ConfigManager cfg = plugin.getConfigManager();
        if (cfg.isLangDiscordEnabled()) {
            String description = cfg.getLangDiscordMessage()
                    .replace("&name&", target.getName())
                    .replace("&checker&", checkerName)
                    .replace("&lang&", detected != null ? detected : "Unknown (" + response + ")");
            WebhookUtil.sendRaw(cfg.getLangWebhookUrl(), cfg.getLangEmbedColor(), description);
        }
    }

    private String resolveCheckerName(LangCheckData data) {
        if (data.getInitiatorUUID() != null) {
            return Optional.ofNullable(Bukkit.getPlayer(data.getInitiatorUUID()))
                    .map(Player::getName).orElse("Console");
        }
        return "AutoCheck";
    }

    private void notifyInitiator(LangCheckData data, Component msg) {
        if (data.getInitiatorUUID() == null) return;
        Player ini = Bukkit.getPlayer(data.getInitiatorUUID());
        if (ini == null || !ini.isOnline()) return;
        boolean getsAlerts = ini.hasPermission("checkhacks.alerts")
                && plugin.hasAlertsEnabled(ini.getUniqueId());
        if (!getsAlerts) ini.sendMessage(msg);
    }

    public void cleanup() {
        for (UUID uuid : List.copyOf(activeChecks.keySet())) {
            abort(uuid);
        }
    }
}
