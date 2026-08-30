package me.branduzzo.checkHacks;

import me.branduzzo.checkHacks.commands.*;
import me.branduzzo.checkHacks.listeners.*;
import me.branduzzo.checkHacks.managers.*;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class CheckHacksPlugin extends JavaPlugin {

    private static CheckHacksPlugin instance;
    private ConfigManager     configManager;
    private MessageManager    messageManager;
    private DatabaseManager   databaseManager;
    private WebServerManager  webServerManager;
    private CheckManager      checkManager;
    private LangCheckManager  langCheckManager;
    private ClientDataManager clientDataManager;
    private UpdateManager     updateManager;
    private final Set<UUID>   alertsDisabled = new HashSet<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        new Metrics(this, 31804);

        configManager     = new ConfigManager(this);
        messageManager    = new MessageManager(this);
        databaseManager   = new DatabaseManager(this);
        clientDataManager = new ClientDataManager();
        checkManager      = new CheckManager(this);
        langCheckManager  = new LangCheckManager(this);

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

        getServer().getPluginManager().registerEvents(new SignListener(this), this);
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);
        AntiCheatListener acListener = new AntiCheatListener(this);
        getServer().getPluginManager().registerEvents(acListener, this);
        acListener.registerHooks();

        getCommand("checkhacksupdate").setExecutor(new UpdateCommand(this));

        updateManager = new UpdateManager(this);
        updateManager.checkForUpdates();

        getLogger().info("CheckHacks v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (checkManager     != null) checkManager.cleanup();
        if (langCheckManager != null) langCheckManager.cleanup();
        if (webServerManager != null) webServerManager.stop();
        if (databaseManager  != null) databaseManager.close();
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

    public boolean hasAlertsEnabled(UUID uuid) {
        boolean def = getConfig().getBoolean("alerts.default-enabled", true);
        return def ? !alertsDisabled.contains(uuid) : alertsDisabled.contains(uuid);
    }

    public void toggleAlerts(UUID uuid) {
        if (!alertsDisabled.remove(uuid)) alertsDisabled.add(uuid);
    }
}