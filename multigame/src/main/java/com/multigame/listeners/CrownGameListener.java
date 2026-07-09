package com.multigame.listeners;

import com.multigame.MultiGame;
import com.multigame.games.crowngame.CrownGameManager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

public class CrownGameListener implements Listener {

    private final MultiGame plugin;

    public CrownGameListener(MultiGame plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = resolveAttacker(event);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }

        if (plugin.getEventManager().get(CrownGameManager.ID) instanceof CrownGameManager crownGame) {
            crownGame.handleHit(attacker, victim);
        }
    }

    private CrownGameManager crownGame() {
        return plugin.getEventManager().get(CrownGameManager.ID) instanceof CrownGameManager cg ? cg : null;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        CrownGameManager crownGame = crownGame();
        if (crownGame == null || !crownGame.isRunning()) return;
        if (!crownGame.isActivePlayer(player.getUniqueId())) return;

        boolean touchesHelmet = event.getSlotType() == InventoryType.SlotType.ARMOR
                && crownGame.isCrown(event.getCurrentItem());
        boolean hotbarSwap = (event.getAction() == InventoryAction.HOTBAR_SWAP
                || event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD)
                && event.getSlotType() == InventoryType.SlotType.ARMOR;
        boolean movesCrown = crownGame.isCrown(event.getCurrentItem()) || crownGame.isCrown(event.getCursor());

        if (touchesHelmet || hotbarSwap || movesCrown) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        CrownGameManager crownGame = crownGame();
        if (crownGame == null || !crownGame.isRunning()) return;
        if (!crownGame.isActivePlayer(player.getUniqueId())) return;

        if (crownGame.isCrown(event.getOldCursor()) || crownGame.isCrown(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        CrownGameManager crownGame = crownGame();
        if (crownGame == null || !crownGame.isRunning()) return;
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (crownGame.isCrown(dropped)) {
            event.setCancelled(true);
        }
    }

    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) {
            return p;
        }
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player p) {
                return p;
            }
        }
        return null;
    }
}
