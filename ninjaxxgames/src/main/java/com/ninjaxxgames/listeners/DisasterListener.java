package com.ninjaxxgames.listeners;

import com.ninjaxxgames.NinjaxxGames;
import com.ninjaxxgames.games.disaster.DisasterManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public class DisasterListener implements Listener {

    private final NinjaxxGames plugin;

    public DisasterListener(NinjaxxGames plugin) {
        this.plugin = plugin;
    }

    private DisasterManager manager() {
        return plugin.getEventManager().get(DisasterManager.ID) instanceof DisasterManager m ? m : null;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        DisasterManager manager = manager();
        if (manager == null || !manager.isRunning()) return;
        if (!manager.isDashItem(event.getItem())) return;

        event.setCancelled(true);
        manager.handleDash(event.getPlayer());
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent event) {
        DisasterManager manager = manager();
        if (manager == null || !manager.isRunning()) return;
        if (!manager.isMeteor(event.getEntity().getUniqueId())) return;
        manager.handleMeteorExplode(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        DisasterManager manager = manager();
        if (manager != null && manager.isRunning()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        DisasterManager manager = manager();
        if (manager != null && manager.isRunning() && event.getSource().getType() == Material.FIRE) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        DisasterManager manager = manager();
        if (manager == null || !manager.isActive(player.getUniqueId())) return;

        if (player.getHealth() - event.getFinalDamage() <= 0.0) {
            event.setCancelled(true);
            manager.handleFatalDamage(player);
        }
    }
}
