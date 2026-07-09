package com.multigame;

import com.multigame.commands.MultiGameCommand;
import com.multigame.games.crowngame.CrownGameManager;
import com.multigame.games.disaster.DisasterManager;
import com.multigame.games.hotpotato.HotPotatoManager;
import com.multigame.games.prophunt.PropHuntManager;
import com.multigame.games.squidgame.SquidGameManager;
import com.multigame.hooks.MultiGameExpansion;
import com.multigame.listeners.CrownGameListener;
import com.multigame.listeners.DisasterListener;
import com.multigame.listeners.HotPotatoListener;
import com.multigame.listeners.PropHuntListener;
import com.multigame.listeners.PlayerJoinListener;
import com.multigame.listeners.PlayerMoveListener;
import com.multigame.listeners.ProtectionListener;
import com.multigame.listeners.SelectionListener;
import com.multigame.managers.*;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class MultiGame extends JavaPlugin {

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
    private BackgroundMusicManager backgroundMusicManager;
    private FormationManager formationManager;
    private PodiumManager podiumManager;
    private InterludeManager interludeManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.stateManager = new PluginStateManager(this);
        this.zoneManager = new ZoneManager(this);
        this.liftManager = new LiftManager(this);
        this.scoreManager = new ScoreManager(this);
        this.sessionManager = new PlayerSessionManager();
        this.selectionManager = new SelectionManager();
        this.formationManager = new FormationManager(this);
        this.podiumManager = new PodiumManager(this);
        this.eventManager = new EventManager();
        this.gameManager = new GameManager(this);
        this.interludeManager = new InterludeManager(this);

        eventManager.register(new SquidGameManager(this));
        eventManager.register(new CrownGameManager(this));
        eventManager.register(new HotPotatoManager(this));
        eventManager.register(new PropHuntManager(this));
        eventManager.register(new DisasterManager(this));

        getCommand("multigame").setExecutor(new MultiGameCommand(this));
        getCommand("multigame").setTabCompleter(new MultiGameCommand(this));

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

        this.backgroundMusicManager = new BackgroundMusicManager(this);
        backgroundMusicManager.start();

        getServer().getScheduler().runTaskTimer(this, () -> {
            var disaster = eventManager.get(DisasterManager.ID);
            for (var player : getServer().getOnlinePlayers()) {
                if (disaster instanceof DisasterManager dm && dm.isActive(player.getUniqueId())) {
                    continue;
                }
                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.SATURATION, 400, 0, false, false, false));
            }
        }, 100L, 100L);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new MultiGameExpansion(this).register();
            getLogger().info("Hook PlaceholderAPI enregistré (placeholders %multigame_...%).");
        }

        getLogger().info("MultiGame activé. État serveur : " + (stateManager.isOn() ? "ON" : "OFF"));
    }

    @Override
    public void onDisable() {
        if (interludeManager != null && interludeManager.isRunning()) {
            interludeManager.end();
        }
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
        if (backgroundMusicManager != null) {
            backgroundMusicManager.stop();
        }
        saveConfig();
        getLogger().info("MultiGame désactivé.");
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
    public BackgroundMusicManager getBackgroundMusicManager() { return backgroundMusicManager; }
    public FormationManager getFormationManager() { return formationManager; }
    public PodiumManager getPodiumManager() { return podiumManager; }
    public InterludeManager getInterludeManager() { return interludeManager; }

    public void sendToHub(Player player) {
        sessionManager.clear(player.getUniqueId());
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        if (zoneManager.hasHub()) {
            player.teleport(zoneManager.getHub());
        }
        if (!player.getInventory().contains(Material.COOKED_BEEF)) {
            player.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 64));
        }
        PlayerJoinListener.applySaturation(player);
        if (hubScoreboardManager != null) {
            hubScoreboardManager.show(player);
        }
    }
}
