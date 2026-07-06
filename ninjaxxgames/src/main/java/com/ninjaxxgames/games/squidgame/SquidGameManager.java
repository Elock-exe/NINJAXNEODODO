package com.ninjaxxgames.games.squidgame;

import com.ninjaxxgames.NinjaxxGames;
import com.ninjaxxgames.games.GlowSidebar;
import com.ninjaxxgames.games.MiniGame;
import com.ninjaxxgames.managers.PlayerSessionManager;
import com.ninjaxxgames.models.Zone;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;

public class SquidGameManager implements MiniGame {

    public static final String ID = "squidgame";

    private long redLightStartMs;
    private boolean redIncoming;

    private final NinjaxxGames plugin;
    private final GlowSidebar scoreboard = new GlowSidebar(ChatColor.GREEN, "sg_team", "sg_sb");

    private SquidGamePhase phase = SquidGamePhase.IDLE;

    private final Set<UUID> lobbyPlayers = new LinkedHashSet<>();
    private final Set<UUID> activePlayers = new LinkedHashSet<>();
    private final List<UUID> finishOrder = new ArrayList<>();
    private final Set<UUID> eliminatedPlayers = new HashSet<>();
    private final List<UUID> eliminationOrder = new ArrayList<>();
    private final Map<UUID, Location> lastLocations = new HashMap<>();

    private BukkitTask mainTask;
    private BukkitTask lightTask;
    private BukkitTask timerTask;
    private BukkitTask actionBarTask;
    private int remainingRoundSeconds;

    public SquidGameManager(NinjaxxGames plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Squid Game";
    }

    @Override
    public void addPlayer(Player player) {
        lobbyPlayers.add(player.getUniqueId());
        plugin.getSessionManager().setState(player.getUniqueId(), PlayerSessionManager.SessionState.IN_GAME);
        plugin.getSessionManager().setCurrentGame(player.getUniqueId(), ID);

        Zone prepZone = plugin.getZoneManager().getZone("preparation");
        if (prepZone != null) {
            teleportToZoneCenter(player, prepZone);
        }
        player.sendMessage("§a[NinjaxxGames] §fBienvenue dans le lobby Squid Game. En attente du lancement...");
    }

    @Override
    public void removePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        lobbyPlayers.remove(uuid);
        activePlayers.remove(uuid);
        finishOrder.remove(uuid);
        eliminatedPlayers.remove(uuid);
        lastLocations.remove(uuid);
        scoreboard.detach(player);
        plugin.getSessionManager().clear(uuid);
        sendToHub(player);
    }

    @Override
    public boolean isRunning() {
        return phase != SquidGamePhase.IDLE && phase != SquidGamePhase.FINISHED;
    }

    @Override
    public String start() {
        if (isRunning()) {
            return "une partie est déjà en cours";
        }
        if (lobbyPlayers.isEmpty()) {
            return "aucun joueur dans le lobby (personne n'est passé par l'ascenseur)";
        }
        List<String> missing = new ArrayList<>();
        if (!plugin.getZoneManager().hasZone("preparation")) missing.add("preparation");
        if (!plugin.getZoneManager().hasZone("game")) missing.add("game");
        if (!plugin.getZoneManager().hasZone("finish")) missing.add("finish");
        if (!plugin.getZoneManager().hasZone("eliminated")) missing.add("eliminated");
        if (!missing.isEmpty()) {
            return "zones manquantes : " + String.join(", ", missing);
        }

        activePlayers.clear();
        activePlayers.addAll(lobbyPlayers);
        finishOrder.clear();
        eliminatedPlayers.clear();
        eliminationOrder.clear();
        lastLocations.clear();

        int participation = plugin.getConfig().getInt("squidgame.points.participation", 10);
        for (UUID uuid : activePlayers) {
            plugin.getScoreManager().addPoints(uuid, participation);
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) scoreboard.attach(p);
        }

        phase = SquidGamePhase.PREPARATION;
        broadcastIntro();
        updateScoreboard();
        broadcast("§e[Squid Game] §fLa partie va commencer. Restez dans la zone de préparation.");
        runCountdown(10);
        return null;
    }

    private void broadcastIntro() {
        int participation = plugin.getConfig().getInt("squidgame.points.participation", 10);
        int winBonus = plugin.getConfig().getInt("squidgame.points.win-bonus", 100);
        broadcast("§8§m                                        ");
        broadcast("§a§l1, 2, 3 SOLEIL");
        broadcast("§7• §a§l🟢 VERT §7(gros titre vert à l'écran) : §aAVANCE !");
        broadcast("§7• §c§l🔴 ROUGE §7(gros titre rouge à l'écran) : §cSTOP, ne bouge plus !");
        broadcast("§7  Si tu bouges pendant le §cROUGE§7, tu es §céliminé§7.");
        broadcast("§7• Un §e⚠ avertissement §7apparaît juste avant chaque ROUGE.");
        broadcast("§7• Franchis la ligne d'arrivée le plus vite possible !");
        broadcast("§7• Points : §e" + participation + " §fpour tous §7+ jusqu'à §e" + winBonus
                + " §fselon ta place (§e" + (participation + winBonus) + " §fpour le 1er).");
        broadcast("§8§m                                        ");
    }

    private void updateScoreboard() {
        if (phase == SquidGamePhase.IDLE || phase == SquidGamePhase.FINISHED) return;

        int participation = plugin.getConfig().getInt("squidgame.points.participation", 10);
        int winBonus = plugin.getConfig().getInt("squidgame.points.win-bonus", 100);

        String feu = switch (phase) {
            case GREEN_LIGHT -> "§a● VERT";
            case RED_LIGHT -> "§c● ROUGE";
            default -> "§7— (préparation)";
        };
        String tempsLine = (phase == SquidGamePhase.GREEN_LIGHT || phase == SquidGamePhase.RED_LIGHT)
                ? "§7Temps §f" + formatTime(remainingRoundSeconds)
                : "§7Temps §f--:--";

        for (UUID uuid : activePlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            int points = plugin.getScoreManager().getPoints(uuid);
            scoreboard.render(p, "§a§l1,2,3 SOLEIL", List.of(
                    "§7Feu " + feu,
                    tempsLine,
                    "§7En course §f" + activePlayers.size(),
                    "§1",
                    "§7Récompenses :",
                    "§f " + participation + " §7+ jusqu'à §e" + winBonus + " §7(place)",
                    "§2",
                    "§7Tes points §f" + points
            ));
        }

        for (UUID uuid : finishOrder) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            int points = plugin.getScoreManager().getPoints(uuid);
            int pos = finishOrder.indexOf(uuid) + 1;
            scoreboard.render(p, "§a§l1,2,3 SOLEIL", List.of(
                    "§a§lARRIVÉ ✔",
                    "§7Place provisoire §f#" + pos,
                    tempsLine,
                    "§1",
                    "§7En course §f" + activePlayers.size(),
                    "§7Attends la fin de la partie...",
                    "§2",
                    "§7Tes points §f" + points
            ));
        }
    }

    private String formatTime(int totalSeconds) {
        if (totalSeconds < 0) totalSeconds = 0;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" + seconds : seconds);
    }

    private void runCountdown(int seconds) {
        phase = SquidGamePhase.COUNTDOWN;
        final int[] remaining = {seconds};

        mainTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (remaining[0] <= 0) {
                    cancel();
                    openGame();
                    return;
                }
                broadcast("§e[Squid Game] Départ dans §c" + remaining[0]);
                for (UUID uuid : activePlayers) {
                    Player p = plugin.getServer().getPlayer(uuid);
                    if (p != null) {
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                    }
                }
                remaining[0]--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void openGame() {
        broadcast("§a[Squid Game] §fGo ! La course est ouverte.");
        for (UUID uuid : activePlayers) {
            lastLocations.put(uuid, getPlayerLoc(uuid));
        }

        remainingRoundSeconds = plugin.getConfig().getInt("squidgame.round-duration-seconds", 120);
        timerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (phase == SquidGamePhase.FINISHED) {
                    cancel();
                    return;
                }
                remainingRoundSeconds--;
                updateScoreboard();
                if (remainingRoundSeconds <= 0) {
                    cancel();
                    endGame();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);

        actionBarTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (phase == SquidGamePhase.FINISHED) {
                    cancel();
                    return;
                }
                Component bar = currentLightActionBar();
                if (bar == null) return;
                for (UUID uuid : activePlayers) {
                    Player p = plugin.getServer().getPlayer(uuid);
                    if (p != null) p.sendActionBar(bar);
                }
            }
        }.runTaskTimer(plugin, 5L, 5L);

        scheduleGreenLight();
    }

    private Component currentLightActionBar() {
        return switch (phase) {
            case GREEN_LIGHT -> redIncoming
                    ? legacy("§6§l⚠ ATTENTION §r§7— §ele ROUGE arrive, prépare-toi à t'arrêter !")
                    : legacy("§a§l🟢 VERT §r§7— §a§lAVANCE !");
            case RED_LIGHT -> legacy("§c§l🔴 ROUGE §r§7— §c§lSTOP, NE BOUGE PLUS !");
            default -> null;
        };
    }

    private void scheduleGreenLight() {
        if (phase == SquidGamePhase.FINISHED) return;
        phase = SquidGamePhase.GREEN_LIGHT;
        redIncoming = false;
        broadcastTitle("§a§l🟢 VERT", "§a§lAVANCE !", 2, 24, 4);
        broadcastActionBar("§a§l➤ AVANCE ! ➤");
        broadcastSound(Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f, 2.0f);
        broadcastSound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4f, 1.4f);
        updateScoreboard();
        for (UUID uuid : activePlayers) {
            lastLocations.put(uuid, getPlayerLoc(uuid));
        }

        int min = plugin.getConfig().getInt("squidgame.green-light-min-seconds", 3);
        int max = plugin.getConfig().getInt("squidgame.green-light-max-seconds", 6);
        int duration = min + new Random().nextInt(Math.max(1, max - min + 1));

        double warnSeconds = plugin.getConfig().getDouble("squidgame.red-light-warning-seconds", 1.5);
        long warnTicks = Math.max(10L, (long) (warnSeconds * 20));
        long greenTicks = Math.max(warnTicks + 10L, duration * 20L);

        lightTask = new BukkitRunnable() {
            @Override
            public void run() {
                warnRedLight(warnTicks);
            }
        }.runTaskLater(plugin, greenTicks - warnTicks);
    }

    private void warnRedLight(long delayTicks) {
        if (phase == SquidGamePhase.FINISHED) return;
        redIncoming = true;
        broadcastActionBar("§e§l⚠ ATTENTION — le ROUGE arrive... prépare-toi à t'arrêter !");
        broadcastSound(Sound.BLOCK_NOTE_BLOCK_HAT, 1.2f, 0.8f);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (phase == SquidGamePhase.GREEN_LIGHT) {
                    broadcastActionBar("§6§l⚠ STOP dans un instant !");
                    broadcastSound(Sound.BLOCK_NOTE_BLOCK_HAT, 1.2f, 1.2f);
                }
            }
        }.runTaskLater(plugin, Math.max(1L, delayTicks / 2));

        lightTask = new BukkitRunnable() {
            @Override
            public void run() {
                scheduleRedLight();
            }
        }.runTaskLater(plugin, delayTicks);
    }

    private void scheduleRedLight() {
        if (phase == SquidGamePhase.FINISHED) return;
        phase = SquidGamePhase.RED_LIGHT;
        redIncoming = false;
        redLightStartMs = System.currentTimeMillis();
        broadcastTitle("§c§l🔴 ROUGE", "§c§lSTOP — NE BOUGE PLUS !", 0, 30, 4);
        broadcastActionBar("§c§l✋ STOP ! NE BOUGE PLUS ✋");
        broadcastSound(Sound.BLOCK_NOTE_BLOCK_BASS, 1.6f, 0.5f);
        broadcastSound(Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f);
        updateScoreboard();
        for (UUID uuid : activePlayers) {
            lastLocations.put(uuid, getPlayerLoc(uuid));
        }

        int min = plugin.getConfig().getInt("squidgame.red-light-min-seconds", 3);
        int max = plugin.getConfig().getInt("squidgame.red-light-max-seconds", 6);
        int duration = min + new Random().nextInt(Math.max(1, max - min + 1));

        lightTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (phase == SquidGamePhase.FINISHED) return;
                scheduleGreenLight();
            }
        }.runTaskLater(plugin, duration * 20L);
    }

    @Override
    public void stop() {
        cancelTasks();
        Set<UUID> activeOrFinished = new LinkedHashSet<>(activePlayers);
        activeOrFinished.addAll(finishOrder);
        for (UUID uuid : activeOrFinished) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) {
                scoreboard.detach(p);
                sendToHub(p);
            }
        }
        for (UUID uuid : new ArrayList<>(lobbyPlayers)) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) {
                scoreboard.detach(p);
                sendToHub(p);
            }
        }
        resetState();
    }

    private void endGame() {
        phase = SquidGamePhase.FINISHED;
        cancelTasks();
        broadcast("§6[Squid Game] §fLe temps est écoulé ! Calcul du classement...");

        List<UUID> standings = new ArrayList<>(finishOrder);
        for (UUID uuid : activePlayers) {
            if (!standings.contains(uuid)) standings.add(uuid);
        }
        List<UUID> elimReversed = new ArrayList<>(eliminationOrder);
        Collections.reverse(elimReversed);
        standings.addAll(elimReversed);

        int total = standings.size();
        int winBonus = plugin.getConfig().getInt("squidgame.points.win-bonus", 100);

        int rank = 1;
        for (UUID uuid : standings) {
            int bonus;
            if (total <= 1) {
                bonus = winBonus;
            } else {
                double share = (double) (total - rank) / (total - 1);
                bonus = (int) Math.round(winBonus * share);
            }
            if (bonus > 0) {
                plugin.getScoreManager().addPoints(uuid, bonus);
            }
            announceRank(uuid, rank, bonus);
            rank++;
        }

        Set<UUID> toHub = new LinkedHashSet<>(activePlayers);
        toHub.addAll(finishOrder);
        for (UUID uuid : toHub) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) {
                scoreboard.detach(p);
                sendToHub(p);
            }
        }

        resetState();
    }

    private void announceRank(UUID uuid, int rank, int points) {
        Player p = plugin.getServer().getPlayer(uuid);
        String name = p != null ? p.getName() : uuid.toString();
        String rankLabel = switch (rank) {
            case 1 -> "§6🥇 1er";
            case 2 -> "§7🥈 2e";
            case 3 -> "§c🥉 3e";
            default -> "§f#" + rank;
        };
        String ptsLabel = points > 0 ? " §7(+" + points + " pts)" : "";
        broadcast(rankLabel + " §f- " + name + ptsLabel);
    }

    private void resetState() {
        phase = SquidGamePhase.IDLE;
        lobbyPlayers.clear();
        activePlayers.clear();
        finishOrder.clear();
        eliminatedPlayers.clear();
        eliminationOrder.clear();
        lastLocations.clear();
    }

    private void cancelTasks() {
        if (mainTask != null) mainTask.cancel();
        if (lightTask != null) lightTask.cancel();
        if (timerTask != null) timerTask.cancel();
        if (actionBarTask != null) actionBarTask.cancel();
    }

    public SquidGamePhase getPhase() {
        return phase;
    }

    public boolean isActivePlayer(UUID uuid) {
        return activePlayers.contains(uuid);
    }

    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Location to = event.getTo();
        if (to == null || eliminatedPlayers.contains(uuid)) {
            return;
        }

        boolean tracked = lobbyPlayers.contains(uuid) || activePlayers.contains(uuid);
        if (!tracked) {
            return;
        }

        if (phase != SquidGamePhase.RED_LIGHT && phase != SquidGamePhase.GREEN_LIGHT) {
            Zone prepZone = plugin.getZoneManager().getZone("preparation");
            if (prepZone != null && !prepZone.contains(to)) {
                event.setCancelled(true);
            }
            return;
        }

        if (!activePlayers.contains(uuid)) {
            return;
        }

        Zone gameZone = plugin.getZoneManager().getZone("game");
        Zone finishZone = plugin.getZoneManager().getZone("finish");
        Zone prepZone = plugin.getZoneManager().getZone("preparation");

        if (finishZone != null && finishZone.contains(to)) {
            finishPlayer(player);
            return;
        }

        boolean stillInPrep = prepZone != null && prepZone.contains(to);
        if (gameZone != null && !gameZone.contains(to) && !stillInPrep) {
            eliminatePlayer(player, "§c[Squid Game] §fTu es sorti de la zone de jeu !");
            return;
        }

        if (phase == SquidGamePhase.RED_LIGHT) {
            double graceSeconds = plugin.getConfig().getDouble("squidgame.red-light-grace-seconds", 0.8);
            boolean inGrace = System.currentTimeMillis() - redLightStartMs < (long) (graceSeconds * 1000);

            Location last = lastLocations.get(uuid);
            if (!inGrace && last != null && last.getWorld() != null && to.getWorld() != null
                    && last.getWorld().equals(to.getWorld())) {
                double threshold = plugin.getConfig().getDouble("squidgame.move-threshold", 0.25);
                double dx = last.getX() - to.getX();
                double dz = last.getZ() - to.getZ();
                double distSq = dx * dx + dz * dz;
                if (distSq > threshold * threshold) {
                    eliminatePlayer(player, "§c[Squid Game] §fÉliminé : mouvement pendant le ROUGE !");
                    return;
                }
            }
        }

        lastLocations.put(uuid, to.clone());
    }

    private void finishPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        activePlayers.remove(uuid);
        finishOrder.add(uuid);

        int pos = finishOrder.size();
        player.sendMessage("§a[Squid Game] §fFélicitations, tu as franchi la ligne d'arrivée en §e#" + pos
                + " §f! Attends la fin de la partie.");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
        updateScoreboard();
    }

    private void eliminatePlayer(Player player, String reason) {
        UUID uuid = player.getUniqueId();
        activePlayers.remove(uuid);
        eliminatedPlayers.add(uuid);
        eliminationOrder.add(uuid);
        plugin.getSessionManager().setState(uuid, PlayerSessionManager.SessionState.ELIMINATED);
        player.sendMessage(reason);
        scoreboard.detach(player);

        Zone eliminatedZone = plugin.getZoneManager().getZone("eliminated");
        if (eliminatedZone != null) {
            teleportToZoneCenter(player, eliminatedZone);
        }
    }

    private Location getPlayerLoc(UUID uuid) {
        Player p = plugin.getServer().getPlayer(uuid);
        return p != null ? p.getLocation().clone() : null;
    }

    private void teleportToZoneCenter(Player player, Zone zone) {
        org.bukkit.World world = plugin.getServer().getWorld(zone.getWorld());
        if (world == null) return;
        double x = (zone.getMinX() + zone.getMaxX()) / 2.0;
        double y = zone.getMinY() + 1;
        double z = (zone.getMinZ() + zone.getMaxZ()) / 2.0;
        player.teleport(new Location(world, x, y, z));
    }

    private void sendToHub(Player player) {
        plugin.sendToHub(player);
    }

    private void broadcast(String message) {
        for (UUID uuid : allInvolved()) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.sendMessage(message);
        }
    }

    private void broadcastSound(Sound sound, float pitch) {
        broadcastSound(sound, 1f, pitch);
    }

    private void broadcastSound(Sound sound, float volume, float pitch) {
        for (UUID uuid : allInvolved()) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) {
                p.playSound(p.getLocation(), sound, volume, pitch);
            }
        }
    }

    private void broadcastTitle(String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        Title t = Title.title(
                legacy(title),
                legacy(subtitle),
                Title.Times.times(
                        Duration.ofMillis(fadeInTicks * 50L),
                        Duration.ofMillis(stayTicks * 50L),
                        Duration.ofMillis(fadeOutTicks * 50L)));
        for (UUID uuid : allInvolved()) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.showTitle(t);
        }
    }

    private void broadcastActionBar(String message) {
        Component component = legacy(message);
        for (UUID uuid : allInvolved()) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.sendActionBar(component);
        }
    }

    private Component legacy(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    private Set<UUID> allInvolved() {
        Set<UUID> all = new HashSet<>(lobbyPlayers);
        all.addAll(activePlayers);
        all.addAll(finishOrder);
        all.addAll(eliminatedPlayers);
        return all;
    }
}
