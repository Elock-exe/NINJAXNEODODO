package com.ninjaxxgames.managers;

import com.ninjaxxgames.NinjaxxGames;
import com.ninjaxxgames.models.Zone;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;

public class ZoneManager {

    private final NinjaxxGames plugin;
    private Location hub;
    private final Map<String, Zone> namedZones = new HashMap<>();

    public ZoneManager(NinjaxxGames plugin) {
        this.plugin = plugin;
        load();
    }

    private static String composite(String gameId, String key) {
        return gameId.toLowerCase() + ":" + key.toLowerCase();
    }

    public void setHub(Location loc) {
        this.hub = loc.clone();
        ConfigurationSection sec = plugin.getConfig().createSection("hub");
        sec.set("world", loc.getWorld().getName());
        sec.set("x", loc.getX());
        sec.set("y", loc.getY());
        sec.set("z", loc.getZ());
        sec.set("yaw", loc.getYaw());
        sec.set("pitch", loc.getPitch());
        plugin.saveConfig();
    }

    public Location getHub() {
        return hub;
    }

    public boolean hasHub() {
        return hub != null;
    }

    public void setZone(String gameId, String key, Zone zone) {
        namedZones.put(composite(gameId, key), zone);
        ConfigurationSection sec = plugin.getConfig().createSection(
                "zones." + gameId.toLowerCase() + "." + key.toLowerCase());
        zone.save(sec);
        plugin.saveConfig();
    }

    public Zone getZone(String gameId, String key) {
        return namedZones.get(composite(gameId, key));
    }

    public boolean hasZone(String gameId, String key) {
        return getZone(gameId, key) != null;
    }

    public java.util.Set<String> getZoneKeys(String gameId) {
        String prefix = gameId.toLowerCase() + ":";
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        for (String comp : namedZones.keySet()) {
            if (comp.startsWith(prefix)) {
                result.add(comp.substring(prefix.length()));
            }
        }
        return result;
    }

    public void setZone(String key, Zone zone) {
        setZone("squidgame", key, zone);
    }

    public Zone getZone(String key) {
        return getZone("squidgame", key);
    }

    public boolean hasZone(String key) {
        return hasZone("squidgame", key);
    }

    public boolean isInAnyZone(Location loc) {
        if (loc == null) return false;
        for (Zone zone : namedZones.values()) {
            if (zone.contains(loc)) return true;
        }
        return false;
    }

    private void load() {
        ConfigurationSection hubSec = plugin.getConfig().getConfigurationSection("hub");
        if (hubSec != null && hubSec.getString("world") != null) {
            World world = Bukkit.getWorld(hubSec.getString("world"));
            if (world != null) {
                hub = new Location(
                        world,
                        hubSec.getDouble("x"),
                        hubSec.getDouble("y"),
                        hubSec.getDouble("z"),
                        (float) hubSec.getDouble("yaw"),
                        (float) hubSec.getDouble("pitch")
                );
            }
        }

        ConfigurationSection zonesRoot = plugin.getConfig().getConfigurationSection("zones");
        if (zonesRoot != null) {
            for (String gameId : zonesRoot.getKeys(false)) {
                ConfigurationSection gameSec = zonesRoot.getConfigurationSection(gameId);
                if (gameSec == null) continue;
                for (String key : gameSec.getKeys(false)) {
                    Zone zone = Zone.load(gameSec.getConfigurationSection(key));
                    if (zone != null) {
                        namedZones.put(composite(gameId, key), zone);
                    }
                }
            }
        }

        ConfigurationSection legacySec = plugin.getConfig().getConfigurationSection("squidgame.zones");
        if (legacySec != null) {
            for (String key : legacySec.getKeys(false)) {
                String comp = composite("squidgame", key);
                if (namedZones.containsKey(comp)) continue;
                Zone zone = Zone.load(legacySec.getConfigurationSection(key));
                if (zone != null) {
                    namedZones.put(comp, zone);
                }
            }
        }
    }
}
