package com.veneer.hacks;

import com.github.retrooper.packetevents.PacketEvents;
import com.veneer.VeneerPlugin;
import com.veneer.core.FoliaScheduler;
import com.veneer.core.VeneerModule;
import com.veneer.hacks.commands.HackCheckCommand;
import com.veneer.hacks.commands.HackEditorCommand;
import com.veneer.hacks.commands.HackLangCommand;
import com.veneer.hacks.listeners.AntiCheatListener;
import com.veneer.hacks.listeners.JoinListener;
import com.veneer.hacks.listeners.SignListener;
import com.veneer.hacks.listeners.SignPacketListener;
import com.veneer.hacks.managers.CheckManager;
import com.veneer.hacks.managers.ConfigManager;
import com.veneer.hacks.managers.LangCheckManager;
import com.veneer.hacks.managers.WebServerManager;
import com.veneer.hacks.utils.SignUtil;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Mod detection (from CheckHacks): finds installed client mods through the sign translation vulnerability
 * (MC-265322), keeps the scan history and serves the web panel.
 */
public class HacksModule extends VeneerModule {

    private ConfigManager config;
    private CheckManager checks;
    private JoinListener joinChecks;
    private LangCheckManager langChecks;
    private WebServerManager web;
    private SignPacketListener signPackets;

    public HacksModule(VeneerPlugin plugin) {
        super(plugin);
    }

    @Override public String id() { return "hacks"; }

    @Override public List<String> commandNames() { return List.of("hackcheck", "hacklang", "hackeditor"); }

    @Override
    public void playerJoin(org.bukkit.entity.Player player) {
        if (joinChecks != null) joinChecks.onJoin(player);
    }

    /** A scan of a player who left is dropped: no results, no punishment, the probe blocks are put back. */
    @Override
    public void playerQuit(UUID uuid) {
        if (checks != null) checks.abort(uuid);
        if (langChecks != null) langChecks.abort(uuid);
    }

    @Override
    protected void enable() {
        SignUtil.clearLeftovers(plugin);
        config = new ConfigManager(plugin);
        checks = new CheckManager(plugin);
        langChecks = new LangCheckManager(plugin);
        if (config.isWebEditorEnabled()) web = new WebServerManager(plugin);

        command("hackcheck", new HackCheckCommand(plugin));
        command("hacklang", new HackLangCommand(plugin));
        command("hackeditor", new HackEditorCommand(plugin));

        listen(new SignListener(plugin));
        joinChecks = new JoinListener(plugin);
        AntiCheatListener anticheat = listen(new AntiCheatListener(plugin));
        anticheat.registerHooks();
        // PacketEvents runs when the spoof module (started before this one) or the PacketEvents plugin started it
        if (PacketEvents.getAPI() != null && PacketEvents.getAPI().isInitialized()) {
            signPackets = new SignPacketListener(plugin);
            PacketEvents.getAPI().getEventManager().registerListener(signPackets);
        }
    }

    @Override
    protected void disable() {
        if (signPackets != null && PacketEvents.getAPI() != null)
            PacketEvents.getAPI().getEventManager().unregisterListener(signPackets);
        signPackets = null;
        checks.cleanup();
        langChecks.cleanup();
        SignUtil.saveLeftovers(plugin);
        if (web != null) web.stop();
        web = null;
    }

    @Override
    protected void reload() {
        config.reload();
        if (config.isWebEditorEnabled() != (web != null))
            plugin.getLogger().info("hacks.web-editor.enabled changed: restart the server to apply it.");
    }

    public ConfigManager config() { return config; }
    public CheckManager checks() { return checks; }
    public LangCheckManager langChecks() { return langChecks; }
    /** Whether sign answers can be read from the packet, uncut (see {@link SignPacketListener}). */
    public boolean readsRawSigns() { return signPackets != null; }

    /**
     * Queues a mod scan for a player the spoof module just flagged. Safe to call from any thread.
     * Uses the {@code detect-flag.hacks} list (or the default list) and respects the flag cooldown.
     */
    public void onSpoofFlag(Player player, String reason) {
        if (!running() || player == null || !config.isSpoofFlagScanEnabled()) return;
        // Bypasses are checked where an automatic scan starts (here and in the anticheat and join listeners):
        // the join check needs veneer.bypass.hacks.join, the managers cannot tell the two apart
        if (plugin.bypasses(player, "hacks", false)) return;
        UUID uuid = player.getUniqueId();
        if (checks.isChecking(uuid)) return;

        List<HackDefinition> hacks = config.getFlagCheckHacks();
        if (hacks.isEmpty()) hacks = config.getDefaultCheckHacks();
        if (hacks.isEmpty() || !checks.claimAutoCheck(uuid)) return;

        List<HackDefinition> toCheck = hacks;
        plugin.getLogger().info("Spoof detection flagged " + player.getName() + ", queuing a mod scan.");
        FoliaScheduler.runAtEntity(plugin, player, () ->
                checks.startCheck(player, null, toCheck, true, "Spoof flag: " + reason));
    }
}
