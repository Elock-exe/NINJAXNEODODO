package com.ninjaxxgames.listeners;

import com.ninjaxxgames.NinjaxxGames;
import com.ninjaxxgames.games.disaster.DisasterManager;
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
            applySaturation(player);
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
        // La saturation permanente déclenche une régen ultra-rapide (SATIATED).
        if (event.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED
                && event.getRegainReason() != EntityRegainHealthEvent.RegainReason.REGEN) {
            return;
        }
        String game = plugin.getSessionManager().getCurrentGame(player.getUniqueId());
        if (game == null) return; // Au hub : régen normale.

        // Disaster : on garde une régen très réduite pour que les catastrophes comptent vraiment.
        if (DisasterManager.ID.equals(game)) {
            double mult = plugin.getConfig().getDouble("disaster.health-regen-multiplier", 0.2);
            if (mult <= 0.0) {
                event.setCancelled(true);
            } else {
                event.setAmount(event.getAmount() * mult);
            }
            return;
        }

        // Autres mini-jeux : pas de régen naturelle, les dégâts comptent.
        event.setCancelled(true);
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        // Saturation partout : personne n'a faim, la barre de nourriture reste pleine.
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

    /** Saturation permanente : plus personne n'a faim, partout. */
    public static void applySaturation(Player player) {
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SATURATION, Integer.MAX_VALUE, 0, false, false, false));
    }
}
