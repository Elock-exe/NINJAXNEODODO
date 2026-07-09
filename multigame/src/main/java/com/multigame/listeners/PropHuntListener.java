package com.multigame.listeners;

import com.multigame.MultiGame;
import com.multigame.games.prophunt.PropHuntManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public class PropHuntListener implements Listener {

    private final MultiGame plugin;

    public PropHuntListener(MultiGame plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        if (!right) return;

        if (!(plugin.getEventManager().get(PropHuntManager.ID) instanceof PropHuntManager propHunt)
                || !propHunt.isRunning()) {
            return;
        }

        if (propHunt.isTauntItem(event.getItem())) {
            event.setCancelled(true);
            propHunt.handleTaunt(event.getPlayer(), event.getItem());
            return;
        }

        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            propHunt.handleDisguise(event.getPlayer(), event.getClickedBlock());
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (attacker.equals(victim)) return;

        if (plugin.getEventManager().get(PropHuntManager.ID) instanceof PropHuntManager propHunt
                && propHunt.isRunning()) {
            event.setCancelled(true);
            propHunt.handleHit(attacker, victim);
        }
    }
}
