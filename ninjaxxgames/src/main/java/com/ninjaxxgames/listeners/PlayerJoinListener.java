package com.ninjaxxgames.listeners;

import com.ninjaxxgames.NinjaxxGames;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public class PlayerJoinListener implements Listener {

    private final NinjaxxGames plugin;

    public PlayerJoinListener(NinjaxxGames plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (plugin.getZoneManager().hasHub()) {
                player.teleport(plugin.getZoneManager().getHub());
            }
            giveSteak(player);
            if (plugin.getHubScoreboardManager() != null) {
                plugin.getHubScoreboardManager().show(player);
            }
        }, 2L);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String gameId = plugin.getSessionManager().getCurrentGame(event.getPlayer().getUniqueId());
        if (gameId != null) {
            var game = plugin.getEventManager().get(gameId);
            if (game != null) {
                game.removePlayer(event.getPlayer());
            }
        }
    }

    private void giveSteak(Player player) {
        if (!player.getInventory().contains(Material.COOKED_BEEF)) {
            player.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 64));
        }
    }
}
