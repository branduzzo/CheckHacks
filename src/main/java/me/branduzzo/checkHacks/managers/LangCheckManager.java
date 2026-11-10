package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.LangCheckData;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import me.branduzzo.checkHacks.utils.SignUtil;
import me.branduzzo.checkHacks.utils.WebhookUtil;
import me.branduzzo.checkHacks.utils.SignChallenge;
import me.branduzzo.checkHacks.utils.SignPlacement;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LangCheckManager {

    private static final String LANG_KEY      = "key.forward";
    public static final String LANG_FALLBACK = "\u27e6LANG_UNKNOWN\u27e7";

    private final CheckHacksPlugin plugin;
    private final Map<UUID, LangCheckData> activeChecks     = new ConcurrentHashMap<>();
    private final Set<UUID>                firstJoinChecked = ConcurrentHashMap.newKeySet();

    public LangCheckManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isChecking(UUID uuid)   { return activeChecks.containsKey(uuid); }
    public Set<UUID> getFirstJoinChecked() { return firstJoinChecked; }

    public void startCheck(Player target, Player initiator, Map<String, String> languages) {
        UUID uuid = target.getUniqueId();

        if (activeChecks.containsKey(uuid) || plugin.getCheckManager().isChecking(uuid)) {
            if (initiator != null)
                initiator.sendMessage(plugin.getMessageManager().get("already-checking",
                        Map.of("player", target.getName())));
            return;
        }

        LangCheckData data = new LangCheckData(uuid,
                initiator != null ? initiator.getUniqueId() : null, languages);
        data.setLayout(plugin.getConfigManager().getLangSignLayout());
        activeChecks.put(uuid, data);

        if (initiator != null)
            initiator.sendMessage(plugin.getMessageManager().get("lang-check-started",
                    Map.of("player", target.getName())));

        FoliaScheduler.runAtEntity(plugin, target, () -> setupLangSign(target, data));
    }

    private void setupLangSign(Player target, LangCheckData data) {
        UUID uuid = target.getUniqueId();
        if (activeChecks.get(uuid) != data || !target.isOnline()) return;
        SignPlacement.Lease lease = SignPlacement.place(target,
                plugin.getConfigManager().getSignPlacementSettings(), plugin.getLogger()::warning);
        if (lease == null) { activeChecks.remove(uuid, data); return; }
        Location signLoc = lease.location();
        Sign sign = lease.sign();
        data.setSignLease(lease);
        SignChallenge challenge = plugin.getConfigManager().isSignResponseValidationEnabled() ? new SignChallenge() : null;
        data.setChallenge(challenge);
        for (int row = 0; row < 4; row++) {
            String type = data.getLayout().rows().get(row);
            Component content;
            if (type.equals("language")) content = Component.translatable(LANG_KEY, LANG_FALLBACK);
            else if (type.equals("control")) content = Component.keybind(LANG_KEY);
            else content = Component.text(type.startsWith("text:") ? type.substring(5) : "");
            sign.getSide(Side.FRONT).line(row, challenge == null ? content : challenge.wrap(row, content));
        }
        sign.update(true, false);
        SignUtil.setAllowedEditor(signLoc, uuid, plugin);
        data.setTimeoutTask(FoliaScheduler.runAtEntityLater(plugin, target, () -> {
            if (data.getSignLease() != lease || !activeChecks.remove(uuid, data)) return;
            restoreSign(data);
            Component msg = plugin.getMessageManager().get("lang-check-timeout",
                    Map.of("player", target.getName()));
            plugin.getMessageManager().broadcastAlerts(msg);
            notifyInitiator(data, msg);
        }, plugin.getConfigManager().getLangTimeoutTicks()));
        FoliaScheduler.runAtEntityLater(plugin, target, () -> {
            if (activeChecks.get(uuid) != data || data.getSignLease() != lease || !target.isOnline()) return;
            if (!SignUtil.sendSignSnapshot(target, signLoc, sign, plugin)) {
                if (data.getTimeoutTask() != null) data.getTimeoutTask().cancel();
                restoreSign(data);
                activeChecks.remove(uuid, data);
                return;
            }
            FoliaScheduler.runAtEntityLater(plugin, target, () -> {
                if (activeChecks.get(uuid) != data || data.getSignLease() != lease || !target.isOnline()) return;
                data.setAcceptingResponse(true);
                SignUtil.sendOpenSignPacket(target, signLoc, plugin);
                target.sendBlockChange(signLoc, Material.AIR.createBlockData());
            }, 1L);
        }, 1L);
    }


    /** Accepts only the current editor's block while its first response is pending. */
    public boolean isExpectedSign(UUID uuid, Location location) {
        LangCheckData data = activeChecks.get(uuid);
        return data != null && data.isAcceptingResponse() && SignUtil.isSameBlock(data.getSignLocation(), location);
    }

    public void handleResponse(Player target, String[] lines) {
        UUID uuid = target.getUniqueId();
        LangCheckData data = activeChecks.get(uuid);
        if (data == null || !data.isAcceptingResponse()) return;

        data.setAcceptingResponse(false);
        if (data.getTimeoutTask() != null) data.getTimeoutTask().cancel();
        String[] reply = lines == null ? null : lines.clone();
        if (activeChecks.remove(uuid, data)) processResponse(target, data, reply);
    }

    private void processResponse(Player target, LangCheckData data, String[] lines) {
        restoreSign(data);

        String[] decoded = data.getChallenge() == null ? lines : data.getChallenge().unwrap(lines);
        if (decoded == null || decoded.length != 4) {
            plugin.getLogger().info("[CheckHacks] Ignored edited or stale language reply from " + target.getName());
            return;
        }
        for (int row = 0; data.getChallenge() != null && row < 4; row++) {
            String type = data.getLayout().rows().get(row);
            if ((type.equals("blank") && !decoded[row].isEmpty())
                    || (type.startsWith("text:") && !decoded[row].equals(type.substring(5)))) return;
        }
        String response = decoded[data.getLayout().rows().indexOf("language")].strip();
        recordResponse(target, data.getInitiatorUUID(), data.getLanguages(), response);
    }

    /**
     * Shares language reporting/persistence between standalone probes and the
     * optional language row in a hack scan. Callers validate the sign envelope
     * first; this method owns no temporary block or active-check registration.
     */
    public void recordResponse(Player target, UUID initiator, Map<String, String> languages, String response) {
        LangCheckData data = new LangCheckData(target.getUniqueId(), initiator, languages);
        plugin.getLogger().info("[CheckHacks] LangCheck response from " + target.getName() + ": '" + response + "'");

        if (response.isEmpty() || response.equals(LANG_FALLBACK)) {
            Component msg = plugin.getMessageManager().get("lang-check-protected",
                    Map.of("player", target.getName()));
            plugin.getMessageManager().broadcastAlerts(msg);
            notifyInitiator(data, msg);
            plugin.getDatabaseManager().saveScan("lang", target.getName(),
                    target.getUniqueId().toString(),
                    data.getInitiatorUUID() != null
                            ? Optional.ofNullable(Bukkit.getPlayer(data.getInitiatorUUID()))
                            .map(Player::getName).orElse("AutoCheck")
                            : "AutoCheck",
                    "Lang check", false);
            return;
        }

        String detected = null;
        for (Map.Entry<String, String> entry : data.getLanguages().entrySet()) {
            if (entry.getValue().equalsIgnoreCase(response)) { detected = entry.getKey(); break; }
        }

        String display = detected != null ? detected : "Unknown";
        String msgKey  = detected != null ? "lang-check-complete" : "lang-check-unknown";
        Component msg  = plugin.getMessageManager().get(msgKey,
                Map.of("player", target.getName(), "lang", display, "response", response));
        plugin.getMessageManager().broadcastAlerts(msg);
        notifyInitiator(data, msg);

        String checkerName = data.getInitiatorUUID() != null
                ? Optional.ofNullable(Bukkit.getPlayer(data.getInitiatorUUID()))
                .map(Player::getName).orElse("Console")
                : "AutoCheck";

        long scanId = plugin.getDatabaseManager().saveScan("lang", target.getName(),
                target.getUniqueId().toString(), checkerName, "Lang check", false);
        plugin.getDatabaseManager().saveLangResult(scanId, display, response);

        ConfigManager cfg = plugin.getConfigManager();
        if (cfg.isLangDiscordEnabled()) {
            String description = cfg.getLangDiscordMessage()
                    .replace("&name&",    target.getName())
                    .replace("&checker&", checkerName)
                    .replace("&lang&",    detected != null ? detected : "Unknown (" + response + ")");
            WebhookUtil.sendRaw(cfg.getLangWebhookUrl(), cfg.getLangEmbedColor(), description, cfg.isLangDiscordUseComponentsV2());
        }
    }

    private void notifyInitiator(LangCheckData data, Component msg) {
        if (data.getInitiatorUUID() == null) return;
        Player ini = Bukkit.getPlayer(data.getInitiatorUUID());
        if (ini == null || !ini.isOnline()) return;
        boolean gets = ini.hasPermission("checkhacks.alerts") && plugin.hasAlertsEnabled(ini.getUniqueId());
        if (!gets) ini.sendMessage(msg);
    }

    private void restoreSign(LangCheckData data) {
        // Invalidate queued entity callbacks before restoring on the block's
        // region, which may differ after the player moves or disconnects.
        data.setAcceptingResponse(false);
        SignPlacement.Lease lease = data.getSignLease();
        data.setSignLease(null);
        if (lease == null) return;
        Runnable restore = lease::restore;
        if ((!FoliaScheduler.isFolia() && Bukkit.isPrimaryThread())
                || (FoliaScheduler.isFolia() && Bukkit.getServer().isOwnedByCurrentRegion(lease.location()))) restore.run();
        else {
            try { FoliaScheduler.runAtLocation(plugin, lease.location(), restore); }
            catch (RuntimeException ex) {
                plugin.getLogger().severe("Cannot restore temporary language sign at " + lease.location()
                        + ": " + ex.getMessage());
            }
        }
    }

    /** Aborts the pending probe without broadcasting a timeout or language result. */
    public void cancelCheck(UUID uuid) {
        LangCheckData data = activeChecks.remove(uuid);
        if (data == null) return;
        if (data.getTimeoutTask() != null) data.getTimeoutTask().cancel();
        restoreSign(data);
    }

    public void cleanup() {
        for (LangCheckData d : activeChecks.values()) {
            if (d.getTimeoutTask() != null) d.getTimeoutTask().cancel();
            restoreSign(d);
        }
        activeChecks.clear();
    }
}
