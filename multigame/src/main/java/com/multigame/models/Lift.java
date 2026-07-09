package com.multigame.models;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

public class Lift {

    private final String id;
    private Zone zone;
    private String destination;
    private Location spawn;
    private boolean enabled;

    public Lift(String id) {
        this.id = id;
        this.enabled = false;
    }

    public String getId() { return id; }

    public Zone getZone() { return zone; }
    public void setZone(Zone zone) { this.zone = zone; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public Location getSpawn() { return spawn; }
    public void setSpawn(Location spawn) { this.spawn = spawn == null ? null : spawn.clone(); }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isFullyConfigured() {
        return zone != null && destination != null;
    }

    public void save(ConfigurationSection section) {
        if (zone != null) {
            zone.save(section.createSection("zone"));
        }
        section.set("destination", destination);
        section.set("enabled", enabled);
        if (spawn != null && spawn.getWorld() != null) {
            ConfigurationSection s = section.createSection("spawn");
            s.set("world", spawn.getWorld().getName());
            s.set("x", spawn.getX());
            s.set("y", spawn.getY());
            s.set("z", spawn.getZ());
            s.set("yaw", spawn.getYaw());
            s.set("pitch", spawn.getPitch());
        }
    }

    public static Lift load(String id, ConfigurationSection section) {
        Lift lift = new Lift(id);
        if (section == null) return lift;
        lift.zone = Zone.load(section.getConfigurationSection("zone"));
        lift.destination = section.getString("destination");
        lift.enabled = section.getBoolean("enabled", false);
        ConfigurationSection s = section.getConfigurationSection("spawn");
        if (s != null && s.getString("world") != null) {
            World world = Bukkit.getWorld(s.getString("world"));
            if (world != null) {
                lift.spawn = new Location(world, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                        (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
            }
        }
        return lift;
    }
}
