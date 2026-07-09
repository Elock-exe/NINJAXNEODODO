package com.multigame.listeners;

import com.multigame.MultiGame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

public class SelectionListener implements Listener {

    private final MultiGame plugin;

    public SelectionListener(MultiGame plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getSelectionManager().isWand(player.getInventory().getItemInMainHand())) {
            return;
        }
        if (event.getClickedBlock() == null) {
            return;
        }

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            plugin.getSelectionManager().setPos1(player, event.getClickedBlock().getLocation());
            player.sendMessage("§a[MultiGame] §fPosition 1 définie : "
                    + format(event.getClickedBlock().getLocation()));
            event.setCancelled(true);
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            plugin.getSelectionManager().setPos2(player, event.getClickedBlock().getLocation());
            player.sendMessage("§a[MultiGame] §fPosition 2 définie : "
                    + format(event.getClickedBlock().getLocation()));
            event.setCancelled(true);
        }
    }

    private String format(org.bukkit.Location loc) {
        return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }
}
