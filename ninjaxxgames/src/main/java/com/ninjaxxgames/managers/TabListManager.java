package com.ninjaxxgames.managers;

import com.ninjaxxgames.NinjaxxGames;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public class TabListManager {

    private final NinjaxxGames plugin;
    private BukkitTask task;

    public TabListManager(NinjaxxGames plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = new BukkitRunnable() {
            @Override
            public void run() {
                updateAll();
            }
        }.runTaskTimer(plugin, 40L, 100L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.setPlayerListName(null);
        }
    }

    public void updateAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            update(player);
        }
    }

    public void update(Player player) {
        int rank = plugin.getScoreManager().getRank(player.getUniqueId());
        int points = plugin.getScoreManager().getPoints(player.getUniqueId());
        String rankColor = switch (rank) {
            case 1 -> "§6";
            case 2 -> "§7";
            case 3 -> "§c";
            default -> "§8";
        };
        player.setPlayerListName(rankColor + "#" + rank + " §f" + player.getName() + " §7(" + points + ")");
    }
}
