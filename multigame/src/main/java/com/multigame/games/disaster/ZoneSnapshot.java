package com.multigame.games.disaster;

import com.multigame.models.Zone;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class ZoneSnapshot {

    private final String worldName;
    private final int x0, y0, z0;
    private final int sizeX, sizeY, sizeZ;
    private final BlockData[] data;

    private ZoneSnapshot(String worldName, int x0, int y0, int z0, int sizeX, int sizeY, int sizeZ, BlockData[] data) {
        this.worldName = worldName;
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.data = data;
    }

    public static ZoneSnapshot capture(World world, Zone zone, long maxBlocks) {
        if (world == null || zone == null) return null;

        int x0 = (int) Math.floor(zone.getMinX());
        int y0 = (int) Math.floor(zone.getMinY());
        int z0 = (int) Math.floor(zone.getMinZ());
        int x1 = (int) Math.ceil(zone.getMaxX()) - 1;
        int y1 = (int) Math.ceil(zone.getMaxY()) - 1;
        int z1 = (int) Math.ceil(zone.getMaxZ()) - 1;

        int sizeX = x1 - x0 + 1;
        int sizeY = y1 - y0 + 1;
        int sizeZ = z1 - z0 + 1;
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) return null;

        long total = (long) sizeX * sizeY * sizeZ;
        if (total <= 0 || total > maxBlocks) return null;

        BlockData[] data = new BlockData[(int) total];
        int idx = 0;
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dy = 0; dy < sizeY; dy++) {
                for (int dz = 0; dz < sizeZ; dz++) {
                    data[idx++] = world.getBlockAt(x0 + dx, y0 + dy, z0 + dz).getBlockData();
                }
            }
        }
        return new ZoneSnapshot(world.getName(), x0, y0, z0, sizeX, sizeY, sizeZ, data);
    }

    public String getWorldName() {
        return worldName;
    }

    public boolean restore() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return false;
        restore(world);
        return true;
    }

    public void restore(World world) {
        if (world == null) return;
        int idx = 0;
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dy = 0; dy < sizeY; dy++) {
                for (int dz = 0; dz < sizeZ; dz++) {
                    BlockData want = data[idx++];
                    Block block = world.getBlockAt(x0 + dx, y0 + dy, z0 + dz);
                    if (!block.getBlockData().equals(want)) {
                        block.setBlockData(want, false);
                    }
                }
            }
        }
    }

    public void restoreProgressive(org.bukkit.plugin.Plugin plugin, World world, int blocksPerTick) {
        if (world == null) return;
        final long total = (long) sizeX * sizeY * sizeZ;
        final int perTick = Math.max(1, blocksPerTick);
        new org.bukkit.scheduler.BukkitRunnable() {
            long idx = 0;

            @Override
            public void run() {
                int done = 0;
                while (idx < total && done < perTick) {
                    int i = (int) idx;
                    int dx = i / (sizeY * sizeZ);
                    int rem = i % (sizeY * sizeZ);
                    int dy = rem / sizeZ;
                    int dz = rem % sizeZ;
                    BlockData want = data[i];
                    Block block = world.getBlockAt(x0 + dx, y0 + dy, z0 + dz);
                    if (!block.getBlockData().equals(want)) {
                        block.setBlockData(want, false);
                    }
                    idx++;
                    done++;
                }
                if (idx >= total) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public void saveToFile(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(new java.io.FileOutputStream(file)), StandardCharsets.UTF_8))) {
            w.write(worldName);
            w.write('\n');
            w.write(x0 + " " + y0 + " " + z0 + " " + sizeX + " " + sizeY + " " + sizeZ);
            w.write('\n');
            for (BlockData bd : data) {
                w.write(bd.getAsString());
                w.write('\n');
            }
        }
    }

    public static ZoneSnapshot loadFromFile(File file) throws IOException {
        if (!file.exists()) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new java.io.FileInputStream(file)), StandardCharsets.UTF_8))) {
            String worldName = r.readLine();
            String dims = r.readLine();
            if (worldName == null || dims == null) return null;
            String[] parts = dims.trim().split(" ");
            if (parts.length != 6) return null;
            int x0 = Integer.parseInt(parts[0]);
            int y0 = Integer.parseInt(parts[1]);
            int z0 = Integer.parseInt(parts[2]);
            int sizeX = Integer.parseInt(parts[3]);
            int sizeY = Integer.parseInt(parts[4]);
            int sizeZ = Integer.parseInt(parts[5]);
            long total = (long) sizeX * sizeY * sizeZ;
            if (total <= 0 || total > Integer.MAX_VALUE) return null;

            BlockData[] data = new BlockData[(int) total];
            for (int i = 0; i < total; i++) {
                String line = r.readLine();
                if (line == null) return null;
                data[i] = Bukkit.createBlockData(line);
            }
            return new ZoneSnapshot(worldName, x0, y0, z0, sizeX, sizeY, sizeZ, data);
        }
    }
}
