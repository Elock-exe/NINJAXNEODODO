package com.ninjaxxgames.managers;

import com.ninjaxxgames.NinjaxxGames;
import com.ninjaxxgames.models.Lift;
import com.ninjaxxgames.models.Zone;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LiftManager {

    private final NinjaxxGames plugin;
    private final Map<String, Lift> lifts = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public LiftManager(NinjaxxGames plugin) {
        this.plugin = plugin;
        load();
    }

    public Lift getOrCreate(String id) {
        return lifts.computeIfAbsent(id, Lift::new);
    }

    public Lift get(String id) {
        return lifts.get(id);
    }

    public boolean exists(String id) {
        return lifts.containsKey(id);
    }

    public Map<String, Lift> getAll() {
        return lifts;
    }

    public void setZone(String id, Zone zone) {
        getOrCreate(id).setZone(zone);
        save();
    }

    public void setDestination(String id, String destination) {
        getOrCreate(id).setDestination(destination);
        save();
    }

    public void setSpawn(String id, org.bukkit.Location spawn) {
        getOrCreate(id).setSpawn(spawn);
        save();
    }

    public boolean enable(String id) {
        Lift lift = lifts.get(id);
        if (lift == null || !lift.isFullyConfigured()) {
            return false;
        }
        lift.setEnabled(true);
        save();
        return true;
    }

    public void disable(String id) {
        Lift lift = lifts.get(id);
        if (lift != null) {
            lift.setEnabled(false);
            save();
        }
    }

    public Lift findTriggeredLift(Player player) {
        for (Lift lift : lifts.values()) {
            if (lift.getZone() == null) continue;
            if (!lift.getZone().contains(player)) continue;

            if (!lift.isEnabled()) {
                continue;
            }

            long cooldownMs = plugin.getConfig().getLong("lift-cooldown-seconds", 3) * 1000L;
            long now = System.currentTimeMillis();
            Long last = cooldowns.get(player.getUniqueId());
            if (last != null && (now - last) < cooldownMs) {
                continue;
            }
            cooldowns.put(player.getUniqueId(), now);
            return lift;
        }
        return null;
    }

    public boolean delete(String id) {
        boolean removed = lifts.remove(id) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public java.util.List<Lift> findAllOverlapping(Player player) {
        java.util.List<Lift> result = new java.util.ArrayList<>();
        for (Lift lift : lifts.values()) {
            if (lift.getZone() != null && lift.getZone().contains(player)) {
                result.add(lift);
            }
        }
        return result;
    }

    private void save() {
        ConfigurationSection root = plugin.getConfig().createSection("lifts");
        for (Lift lift : lifts.values()) {
            lift.save(root.createSection(lift.getId()));
        }
        plugin.saveConfig();
    }

    private void load() {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("lifts");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            Lift lift = Lift.load(id, root.getConfigurationSection(id));
            lifts.put(id, lift);
        }
    }
}
