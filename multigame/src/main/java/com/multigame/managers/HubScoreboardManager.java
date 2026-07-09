package com.multigame.managers;

import com.multigame.MultiGame;
import com.multigame.games.GlowSidebar;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class HubScoreboardManager {

    private final MultiGame plugin;
    private final GlowSidebar sidebar = new GlowSidebar(ChatColor.AQUA, "hub_team", "hub_sb");
    private final Set<UUID> attached = new HashSet<>();
    private BukkitTask task;

    public HubScoreboardManager(MultiGame plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = new BukkitRunnable() {
            @Override
            public void run() {
                updateAll();
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (attached.contains(player.getUniqueId())) {
                sidebar.detach(player);
            }
        }
        attached.clear();
    }

    public void updateAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            update(player);
        }
    }

    public void update(Player player) {
        UUID uuid = player.getUniqueId();
        boolean inGame = plugin.getSessionManager().getCurrentGame(uuid) != null;

        if (inGame) {

            attached.remove(uuid);
            return;
        }

        if (!attached.contains(uuid)) {
            sidebar.attach(player);
            attached.add(uuid);
        }

        int points = plugin.getScoreManager().getPoints(uuid);
        int rank = plugin.getScoreManager().getRank(uuid);
        boolean serverOn = plugin.getStateManager().isOn();

        sidebar.render(player, "§b§lMULTIGAME", List.of(
                "§7Bienvenue, §f" + player.getName(),
                "§1",
                "§7Tes points §f" + points,
                "§7Classement §e#" + rank,
                "§2",
                "§7Serveur : " + (serverOn ? "§aON" : "§cOFF"),
                "§3",
                "§7Prends un ascenseur",
                "§7pour jouer ! §a▲"
        ));
    }

    public void show(Player player) {
        update(player);
    }
}
