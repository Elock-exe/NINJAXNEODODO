package com.multigame.models;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

public class Zone {

    private final String world;
    private final double minX, minY, minZ;
    private final double maxX, maxY, maxZ;

    public Zone(String world, double x1, double y1, double z1, double x2, double y2, double z2) {
        this.world = world;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2) + 1;
        this.maxY = Math.max(y1, y2) + 1;
        this.maxZ = Math.max(z1, z2) + 1;
    }

    private Zone(String world, double minX, double minY, double minZ,
                 double maxX, double maxY, double maxZ, boolean alreadyNormalized) {
        this.world = world;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public static Zone fromLocations(Location a, Location b) {
        return new Zone(a.getWorld().getName(), a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    public boolean contains(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        if (!loc.getWorld().getName().equals(world)) return false;
        double x = loc.getX(), y = loc.getY(), z = loc.getZ();
        return x >= minX && x < maxX
                && y >= minY && y < maxY
                && z >= minZ && z < maxZ;
    }

    public boolean contains(Player player) {
        return contains(player.getLocation());
    }

    public String getWorld() {
        return world;
    }

    public double getMinX() { return minX; }
    public double getMinY() { return minY; }
    public double getMinZ() { return minZ; }
    public double getMaxX() { return maxX; }
    public double getMaxY() { return maxY; }
    public double getMaxZ() { return maxZ; }

    public void save(ConfigurationSection section) {
        section.set("world", world);
        section.set("minX", minX);
        section.set("minY", minY);
        section.set("minZ", minZ);
        section.set("maxX", maxX);
        section.set("maxY", maxY);
        section.set("maxZ", maxZ);
    }

    public static Zone load(ConfigurationSection section) {
        if (section == null) return null;
        String world = section.getString("world");
        if (world == null) return null;
        return new Zone(
                world,
                section.getDouble("minX"),
                section.getDouble("minY"),
                section.getDouble("minZ"),
                section.getDouble("maxX"),
                section.getDouble("maxY"),
                section.getDouble("maxZ"),
                true
        );
    }
}
