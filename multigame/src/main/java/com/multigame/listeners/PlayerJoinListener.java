package com.multigame.listeners;

import com.multigame.MultiGame;
import com.multigame.games.disaster.DisasterManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class PlayerJoinListener implements Listener {

    private final MultiGame plugin;

    public PlayerJoinListener(MultiGame plugin) {
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
            applySaturation(player);
            if (plugin.getInterludeManager() != null) {
                plugin.getInterludeManager().sanitize(player);
            }
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
    public void onRegen(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED
                && event.getRegainReason() != EntityRegainHealthEvent.RegainReason.REGEN) {
            return;
        }
        String game = plugin.getSessionManager().getCurrentGame(player.getUniqueId());
        if (game == null) return;

        if (DisasterManager.ID.equals(game)) {
            double mult = plugin.getConfig().getDouble("disaster.health-regen-multiplier", 0.2);
            if (mult <= 0.0) {
                event.setCancelled(true);
            } else {
                event.setAmount(event.getAmount() * mult);
            }
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (plugin.getEventManager().get(DisasterManager.ID) instanceof DisasterManager dm
                && dm.isActive(player.getUniqueId())) {
            return;
        }
        if (event.getFoodLevel() < 20) {
            event.setFoodLevel(20);
        }
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

    public static void applySaturation(Player player) {
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SATURATION, Integer.MAX_VALUE, 0, false, false, false));
    }
}
