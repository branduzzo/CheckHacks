package me.branduzzo.checkHacks;

import me.branduzzo.checkHacks.commands.AlertsCommand;
import me.branduzzo.checkHacks.commands.CHReloadCommand;
import me.branduzzo.checkHacks.commands.CheckHacksCommand;
import me.branduzzo.checkHacks.commands.CheckLangCommand;
import me.branduzzo.checkHacks.commands.EditorCommand;
import me.branduzzo.checkHacks.commands.UpdateCommand;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import me.branduzzo.checkHacks.listeners.AntiCheatListener;
import me.branduzzo.checkHacks.listeners.JoinListener;
import me.branduzzo.checkHacks.listeners.PacketSignListener;
import me.branduzzo.checkHacks.managers.CheckManager;
import me.branduzzo.checkHacks.managers.ClientDataManager;
import me.branduzzo.checkHacks.managers.ConfigManager;
import me.branduzzo.checkHacks.managers.DatabaseManager;
import me.branduzzo.checkHacks.managers.LangCheckManager;
import me.branduzzo.checkHacks.managers.MessageManager;
import me.branduzzo.checkHacks.managers.UpdateManager;
import me.branduzzo.checkHacks.managers.WebServerManager;
import me.branduzzo.checkHacks.utils.PacketSignProbe;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.concurrent.ConcurrentHashMap;

public class CheckHacksPlugin extends JavaPlugin {

    private static CheckHacksPlugin instance;
    private ConfigManager configManager;
    private MessageManager messageManager;
    private DatabaseManager databaseManager;
    private WebServerManager webServerManager;
    private CheckManager checkManager;
    private LangCheckManager langCheckManager;
    private ClientDataManager clientDataManager;
    private UpdateManager updateManager;
    private boolean packetEventsEnabled;
    private boolean packetEventsReady;
    private final Map<UUID, AlertMode> alertModes = new ConcurrentHashMap<>();

    @Override
    public void onLoad() {
        packetEventsEnabled = isModernServer();
        if (!packetEventsEnabled) return;
        try {
            PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
            PacketEvents.getAPI().getSettings()
                    .checkForUpdates(false)
                    .debug(false);
            PacketEvents.getAPI().load();
            packetEventsReady = true;
        } catch (Throwable error) {
            getLogger().log(Level.SEVERE, "PacketEvents failed to load", error);
        }
    }

    @Override
    public void onEnable() {
        instance = this;
        if (packetEventsEnabled && !packetEventsReady) {
            getLogger().severe("CheckHacks requires PacketEvents. The plugin will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (packetEventsEnabled) {
            try {
                PacketEvents.getAPI().init();
            } catch (Throwable error) {
                getLogger().log(Level.SEVERE, "PacketEvents failed to initialize", error);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        PacketSignProbe.setPacketEventsEnabled(packetEventsReady);
        saveDefaultConfig();
        try {
            new Metrics(this, 31804);
        } catch (Throwable ignored) {
        }

        configManager = new ConfigManager(this);
        messageManager = new MessageManager(this);
        databaseManager = new DatabaseManager(this);
        clientDataManager = new ClientDataManager();
        checkManager = new CheckManager(this);
        langCheckManager = new LangCheckManager(this);

        if (configManager.isWebEditorEnabled()) {
            webServerManager = new WebServerManager(this);
        }

        CheckHacksCommand checkCmd = new CheckHacksCommand(this);
        getCommand("checkhacks").setExecutor(checkCmd);
        getCommand("checkhacks").setTabCompleter(checkCmd);
        getCommand("chreload").setExecutor(new CHReloadCommand(this));
        getCommand("chalerts").setExecutor(new AlertsCommand(this));
        CheckLangCommand langCmd = new CheckLangCommand(this);
        getCommand("checklang").setExecutor(langCmd);
        getCommand("checklang").setTabCompleter(langCmd);
        getCommand("cheditor").setExecutor(new EditorCommand(this));

        getServer().getPluginManager().registerEvents(new JoinListener(this), this);
        if (packetEventsReady) {
            PacketEvents.getAPI().getEventManager().registerListener(new PacketSignListener(this));
        }
        AntiCheatListener antiCheatListener = new AntiCheatListener(this);
        getServer().getPluginManager().registerEvents(antiCheatListener, this);
        antiCheatListener.registerHooks();

        getCommand("checkhacksupdate").setExecutor(new UpdateCommand(this));

        updateManager = new UpdateManager(this);
        updateManager.checkForUpdates();

        getLogger().info("CheckHacks v" + getDescription().getVersion() + " enabled.");
    }

    private boolean isModernServer() {
        String version = org.bukkit.Bukkit.getBukkitVersion().split("-")[0];
        try {
            return Integer.parseInt(version.split("\\.")[0]) >= 26;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    @Override
    public void onDisable() {
        if (checkManager != null) checkManager.cleanup();
        if (langCheckManager != null) langCheckManager.cleanup();
        if (webServerManager != null) webServerManager.stop();
        if (databaseManager != null) databaseManager.close();
        if (packetEventsReady && PacketEvents.getAPI() != null) {
            try {
                PacketEvents.getAPI().terminate();
            } catch (Throwable ignored) {
            }
        }
        getLogger().info("CheckHacks disabled.");
    }

    public static CheckHacksPlugin getInstance()    { return instance; }
    public ConfigManager     getConfigManager()     { return configManager; }
    public MessageManager    getMessageManager()    { return messageManager; }
    public DatabaseManager   getDatabaseManager()   { return databaseManager; }
    public CheckManager      getCheckManager()      { return checkManager; }
    public LangCheckManager  getLangCheckManager()  { return langCheckManager; }
    public ClientDataManager getClientDataManager() { return clientDataManager; }
    public UpdateManager     getUpdateManager()     { return updateManager; }

    public AlertMode getAlertMode(UUID uuid) {
        return alertModes.getOrDefault(uuid, configManager.getAlertDefaultMode());
    }

    public void setAlertMode(UUID uuid, AlertMode mode) {
        if (mode == null || mode == configManager.getAlertDefaultMode()) alertModes.remove(uuid);
        else alertModes.put(uuid, mode);
    }

    public void cycleAlertMode(UUID uuid) {
        setAlertMode(uuid, getAlertMode(uuid).next());
    }
}
