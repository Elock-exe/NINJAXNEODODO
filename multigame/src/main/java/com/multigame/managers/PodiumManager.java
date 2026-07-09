package com.multigame.managers;

import com.multigame.MultiGame;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class PodiumManager {

    public static final int MAX_RANKS = 14;

    private final MultiGame plugin;
    private final Random random = new Random();

    private final Map<Integer, Location> rankSpawns = new HashMap<>();
    private Location room;

    private record PodiumSpot(Location loc, int rank) {}

    public PodiumManager(MultiGame plugin) {
        this.plugin = plugin;
        load();
    }

    public boolean setRankSpawn(int rank, Location loc) {
        if (rank < 1 || rank > MAX_RANKS) return false;
        rankSpawns.put(rank, loc.clone());
        saveLoc("podium.ranks." + rank, loc);
        return true;
    }

    public void setRoom(Location loc) {
        this.room = loc.clone();
        saveLoc("podium.room", loc);
    }

    public boolean hasRoom() {
        return room != null;
    }

    public int configuredRankCount() {
        return rankSpawns.size();
    }

    public String runCeremony() {
        if (rankSpawns.isEmpty()) {
            return "aucun spawn de classement défini (/multigame setrankspawn <1-14>)";
        }

        List<Map.Entry<UUID, Integer>> top = plugin.getScoreManager().getTopScores(MAX_RANKS);
        List<PodiumSpot> spots = new ArrayList<>();
        List<UUID> placed = new ArrayList<>();

        int rank = 1;
        for (Map.Entry<UUID, Integer> entry : top) {
            Location spawn = rankSpawns.get(rank);
            Player p = plugin.getServer().getPlayer(entry.getKey());
            if (spawn != null && p != null) {
                p.teleport(spawn);
                placed.add(p.getUniqueId());
                spots.add(new PodiumSpot(spawn.clone(), rank));
            }
            rank++;
        }

        if (room != null) {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (!placed.contains(p.getUniqueId())) {
                    p.teleport(room);
                }
            }
        }

        announcePodium(top);
        startFireworkShow(spots);
        return null;
    }

    private void announcePodium(List<Map.Entry<UUID, Integer>> top) {
        List<String> lines = new ArrayList<>();
        lines.add("§8§m                                        ");
        lines.add("§6§l🏆 CÉRÉMONIE DU CLASSEMENT 🏆");
        int rank = 1;
        for (Map.Entry<UUID, Integer> entry : top) {
            String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
            if (name == null) name = entry.getKey().toString();
            String label = switch (rank) {
                case 1 -> "§6🥇 TOP 1";
                case 2 -> "§7🥈 TOP 2";
                case 3 -> "§c🥉 TOP 3";
                default -> "§f#" + rank;
            };
            lines.add(label + " §f- " + name + " §7(" + entry.getValue() + " pts)");
            rank++;
        }
        lines.add("§8§m                                        ");

        for (Player p : plugin.getServer().getOnlinePlayers()) {
            for (String line : lines) {
                p.sendMessage(line);
            }
        }
    }

    private void startFireworkShow(List<PodiumSpot> spots) {
        if (spots.isEmpty()) return;
        new BukkitRunnable() {
            int rounds = 0;

            @Override
            public void run() {
                if (rounds >= 8) {
                    cancel();
                    return;
                }
                for (PodiumSpot spot : spots) {
                    if (spot.loc().getWorld() != null) {
                        launchFirework(spot.loc(), spot.rank());
                    }
                }
                rounds++;
            }
        }.runTaskTimer(plugin, 0L, 12L);
    }

    private void launchFirework(Location loc, int rank) {
        World world = loc.getWorld();
        if (world == null) return;
        Firework fw = world.spawn(loc.clone().add(0.5, 1.0, 0.5), Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();

        FireworkEffect.Type type = rank <= 3 ? FireworkEffect.Type.BALL_LARGE : FireworkEffect.Type.BALL;
        Color main = rankColor(rank);
        meta.addEffect(FireworkEffect.builder()
                .withColor(main, Color.WHITE)
                .withFade(Color.YELLOW)
                .with(type)
                .flicker(true)
                .trail(true)
                .build());
        meta.setPower(rank <= 3 ? 2 : 1);
        fw.setFireworkMeta(meta);
    }

    private Color rankColor(int rank) {
        return switch (rank) {
            case 1 -> Color.fromRGB(255, 215, 0);
            case 2 -> Color.SILVER;
            case 3 -> Color.fromRGB(205, 127, 50);
            default -> Color.fromRGB(random.nextInt(256), random.nextInt(256), random.nextInt(256));
        };
    }

    private void saveLoc(String path, Location loc) {
        ConfigurationSection sec = plugin.getConfig().createSection(path);
        sec.set("world", loc.getWorld().getName());
        sec.set("x", loc.getX());
        sec.set("y", loc.getY());
        sec.set("z", loc.getZ());
        sec.set("yaw", loc.getYaw());
        sec.set("pitch", loc.getPitch());
        plugin.saveConfig();
    }

    private Location loadLoc(ConfigurationSection sec) {
        if (sec == null || sec.getString("world") == null) return null;
        World world = Bukkit.getWorld(sec.getString("world"));
        if (world == null) return null;
        return new Location(world,
                sec.getDouble("x"), sec.getDouble("y"), sec.getDouble("z"),
                (float) sec.getDouble("yaw"), (float) sec.getDouble("pitch"));
    }

    private void load() {
        ConfigurationSection ranks = plugin.getConfig().getConfigurationSection("podium.ranks");
        if (ranks != null) {
            for (String key : ranks.getKeys(false)) {
                try {
                    int r = Integer.parseInt(key);
                    Location loc = loadLoc(ranks.getConfigurationSection(key));
                    if (loc != null) rankSpawns.put(r, loc);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        room = loadLoc(plugin.getConfig().getConfigurationSection("podium.room"));
    }
}
