package com.ninjaxxgames.listeners;

import com.ninjaxxgames.NinjaxxGames;
import com.ninjaxxgames.games.disaster.DisasterManager;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.Sound;
import org.bukkit.block.BlockFace;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

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
    public void onSlimeJump(PlayerMoveEvent event) {
        DisasterManager manager = manager();
        if (manager == null || !manager.isActive(event.getPlayer().getUniqueId())) return;
        Player player = event.getPlayer();
        if (!player.isOnGround()) return;
        if (player.getLocation().getBlock().getRelative(BlockFace.DOWN).getType() != Material.SLIME_BLOCK) return;

        double strength = plugin.getConfig().getDouble("disaster.supply.slime-jump-strength", 1.1);
        Vector velocity = player.getVelocity();
        velocity.setY(strength);
        player.setFallDistance(0f);
        player.setVelocity(velocity);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_SLIME_JUMP, 1f, 0.8f);
    }

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        DisasterManager manager = manager();
        if (manager == null || !manager.isActive(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
        event.getPlayer().setFlying(false);
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent event) {
        DisasterManager manager = manager();
        if (manager == null || !manager.isRunning()) return;
        if (!manager.isMeteor(event.getEntity().getUniqueId())) return;
        manager.handleMeteorExplode(event);
    }

    @EventHandler
    public void onAnvilLand(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock)) return;
        DisasterManager manager = manager();
        if (manager == null || !manager.isRunning()) return;
        manager.handleAnvilLand(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onZombieDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) return;
        DisasterManager manager = manager();
        if (manager == null || !manager.isRunning()) return;
        if (!manager.isDisasterZombie(zombie.getUniqueId())) return;
        switch (event.getCause()) {
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION, LIGHTNING, FIRE, FIRE_TICK, FALLING_BLOCK ->
                    event.setCancelled(true);
            default -> { }
        }
    }

    @EventHandler
    public void onZombieDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) return;
        DisasterManager manager = manager();
        if (manager == null) return;
        if (manager.handleZombieDeath(zombie.getUniqueId())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        DisasterManager manager = manager();
        if (manager == null || !manager.isActive(event.getPlayer().getUniqueId())) return;
        ItemStack held = event.getPlayer().getInventory().getItem(event.getHand());
        if (!manager.isSupplyBucket(held)) return;
        event.setItemStack(new ItemStack(Material.AIR));
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
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        DisasterManager manager = manager();
        if (manager == null || !manager.isActive(player.getUniqueId())) return;
        if (!manager.isSupplyItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        DisasterManager manager = manager();
        if (manager == null || !manager.isActive(event.getPlayer().getUniqueId())) return;
        if (plugin.getConfig().getBoolean("disaster.prevent-block-break", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        DisasterManager manager = manager();
        if (manager == null || !manager.isActive(player.getUniqueId())) return;

        if (event instanceof EntityDamageByEntityEvent byEntity
                && plugin.getConfig().getBoolean("disaster.prevent-pvp", true)) {
            Entity damager = byEntity.getDamager();
            boolean fromPlayer = damager instanceof Player
                    || (damager instanceof Projectile proj && proj.getShooter() instanceof Player);
            if (fromPlayer) {
                event.setCancelled(true);
                return;
            }
        }

        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            double factor = plugin.getConfig().getDouble("disaster.meteor.damage-multiplier", 0.35);
            event.setDamage(event.getDamage() * factor);
        }

        if (player.getHealth() - event.getFinalDamage() <= 0.0) {
            event.setCancelled(true);
            manager.handleFatalDamage(player);
        }
    }
}
