package com.ninjaxxgames.listeners;

import com.ninjaxxgames.NinjaxxGames;
import com.ninjaxxgames.games.hotpotato.HotPotatoManager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

public class HotPotatoListener implements Listener {

    private final NinjaxxGames plugin;

    public HotPotatoListener(NinjaxxGames plugin) {
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
                // Pas de dégâts / pas de perte de vie : on ne garde que le transfert + le wind burst.
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
