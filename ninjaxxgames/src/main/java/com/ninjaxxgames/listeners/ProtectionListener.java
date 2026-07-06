package com.ninjaxxgames.listeners;

import com.ninjaxxgames.NinjaxxGames;
import com.ninjaxxgames.games.disaster.DisasterManager;
import com.ninjaxxgames.managers.InterludeManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

public class ProtectionListener implements Listener {

    private final NinjaxxGames plugin;

    public ProtectionListener(NinjaxxGames plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isProtected(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isProtected(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (isProtected(event.getPlayer(), event.getBlockClicked().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (isProtected(event.getPlayer(), event.getBlockClicked().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        String game = plugin.getSessionManager().getCurrentGame(player.getUniqueId());
        if (DisasterManager.ID.equals(game)) return;

        InterludeManager interlude = plugin.getInterludeManager();
        if (interlude != null && interlude.isParticipant(player.getUniqueId())
                && event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Player attacker
                && interlude.isParticipant(attacker.getUniqueId())) {
            if (player.getHealth() - event.getFinalDamage() <= 0.0) {
                event.setCancelled(true);
                interlude.handleFatalHit(player, attacker);
            }
            return;
        }

        event.setCancelled(true);
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID && plugin.getZoneManager().hasHub()) {
            player.teleport(plugin.getZoneManager().getHub());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (plugin.getSessionManager().getCurrentGame(player.getUniqueId()) == null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!plugin.getConfig().getBoolean("disable-mob-spawning", true)) {
            return;
        }
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.CUSTOM
                || reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                || reason == CreatureSpawnEvent.SpawnReason.COMMAND) {
            return;
        }
        event.setCancelled(true);
    }

    private boolean isProtected(Player player, Location loc) {
        if (player.hasPermission("ninjaxxgames.admin")) {
            return false;
        }
        if (plugin.getEventManager().get(DisasterManager.ID) instanceof DisasterManager disaster
                && disaster.isActive(player.getUniqueId()) && disaster.isInArena(loc)) {
            return false;
        }
        if (plugin.getSessionManager().getCurrentGame(player.getUniqueId()) != null) {
            return true;
        }
        return plugin.getConfig().getBoolean("protect-zone-blocks", true)
                && plugin.getZoneManager().isInAnyZone(loc);
    }
}
