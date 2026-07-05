package com.ninjaxxgames;

import com.ninjaxxgames.commands.NinjaxxCommand;
import com.ninjaxxgames.games.crowngame.CrownGameManager;
import com.ninjaxxgames.games.disaster.DisasterManager;
import com.ninjaxxgames.games.hotpotato.HotPotatoManager;
import com.ninjaxxgames.games.prophunt.PropHuntManager;
import com.ninjaxxgames.games.squidgame.SquidGameManager;
import com.ninjaxxgames.hooks.NinjaxxExpansion;
import com.ninjaxxgames.listeners.CrownGameListener;
import com.ninjaxxgames.listeners.DisasterListener;
import com.ninjaxxgames.listeners.HotPotatoListener;
import com.ninjaxxgames.listeners.PropHuntListener;
import com.ninjaxxgames.listeners.PlayerJoinListener;
import com.ninjaxxgames.listeners.PlayerMoveListener;
import com.ninjaxxgames.listeners.ProtectionListener;
import com.ninjaxxgames.listeners.SelectionListener;
import com.ninjaxxgames.managers.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class NinjaxxGames extends JavaPlugin {

    private PluginStateManager stateManager;
    private ZoneManager zoneManager;
    private LiftManager liftManager;
    private EventManager eventManager;
    private GameManager gameManager;
    private ScoreManager scoreManager;
    private PlayerSessionManager sessionManager;
    private SelectionManager selectionManager;
    private TabListManager tabListManager;
    private HubScoreboardManager hubScoreboardManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.stateManager = new PluginStateManager(this);
        this.zoneManager = new ZoneManager(this);
        this.liftManager = new LiftManager(this);
        this.scoreManager = new ScoreManager(this);
        this.sessionManager = new PlayerSessionManager();
        this.selectionManager = new SelectionManager();
        this.eventManager = new EventManager();
        this.gameManager = new GameManager(this);

        eventManager.register(new SquidGameManager(this));
        eventManager.register(new CrownGameManager(this));
        eventManager.register(new HotPotatoManager(this));
        eventManager.register(new PropHuntManager(this));
        eventManager.register(new DisasterManager(this));

        getCommand("ninjaxx").setExecutor(new NinjaxxCommand(this));
        getCommand("ninjaxx").setTabCompleter(new NinjaxxCommand(this));

        getServer().getPluginManager().registerEvents(new PlayerMoveListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new SelectionListener(this), this);
        getServer().getPluginManager().registerEvents(new CrownGameListener(this), this);
        getServer().getPluginManager().registerEvents(new HotPotatoListener(this), this);
        getServer().getPluginManager().registerEvents(new PropHuntListener(this), this);
        getServer().getPluginManager().registerEvents(new DisasterListener(this), this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);

        this.tabListManager = new TabListManager(this);
        tabListManager.start();

        this.hubScoreboardManager = new HubScoreboardManager(this);
        hubScoreboardManager.start();

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new NinjaxxExpansion(this).register();
            getLogger().info("Hook PlaceholderAPI enregistré (placeholders %ninjaxx_...%).");
        }

        getLogger().info("NinjaxxGames activé. État serveur : " + (stateManager.isOn() ? "ON" : "OFF"));
    }

    @Override
    public void onDisable() {
        if (eventManager != null) {
            eventManager.getAll().values().forEach(g -> {
                if (g.isRunning()) g.stop();
            });
        }
        if (tabListManager != null) {
            tabListManager.stop();
        }
        if (hubScoreboardManager != null) {
            hubScoreboardManager.stop();
        }
        saveConfig();
        getLogger().info("NinjaxxGames désactivé.");
    }

    public PluginStateManager getStateManager() { return stateManager; }
    public ZoneManager getZoneManager() { return zoneManager; }
    public LiftManager getLiftManager() { return liftManager; }
    public EventManager getEventManager() { return eventManager; }
    public GameManager getGameManager() { return gameManager; }
    public ScoreManager getScoreManager() { return scoreManager; }
    public PlayerSessionManager getSessionManager() { return sessionManager; }
    public SelectionManager getSelectionManager() { return selectionManager; }
    public TabListManager getTabListManager() { return tabListManager; }
    public HubScoreboardManager getHubScoreboardManager() { return hubScoreboardManager; }
}
