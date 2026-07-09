package com.multigame.listeners;

import com.multigame.MultiGame;
import com.multigame.games.hotpotato.HotPotatoManager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

public class HotPotatoListener implements Listener {

    private final MultiGame plugin;

    public HotPotatoListener(MultiGame plugin) {
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

        if (plugin.getEventManager().get(HotPotatoManager.ID) instanceof HotPotatoManager hotPotato) {
            if (hotPotato.isActiveParticipant(victim)) {
                event.setCancelled(true);
            }
            hotPotato.handleHit(attacker, victim);
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
