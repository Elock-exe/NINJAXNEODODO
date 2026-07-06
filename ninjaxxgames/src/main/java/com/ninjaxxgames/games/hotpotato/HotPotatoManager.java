package com.ninjaxxgames.games.hotpotato;

import com.ninjaxxgames.NinjaxxGames;
import com.ninjaxxgames.games.GlowSidebar;
import com.ninjaxxgames.games.MiniGame;
import com.ninjaxxgames.managers.PlayerSessionManager;
import com.ninjaxxgames.models.Zone;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public class HotPotatoManager implements MiniGame {

    public static final String ID = "hotpotato";
    private static final String POTATO_ITEM_NAME = "§c🥔 Patate chaude";
    private static final String SB_TITLE = "§c§l🥔 PATATE CHAUDE";

    private final NinjaxxGames plugin;
    private final GlowSidebar scoreboard = new GlowSidebar(ChatColor.RED, "potato_glow", "potato_sb");
    private final Random random = new Random();

    private final Set<UUID> lobbyPlayers = new LinkedHashSet<>();
    private final Set<UUID> activePlayers = new LinkedHashSet<>();
    private final Set<UUID> spectators = new LinkedHashSet<>();
    private final List<UUID> eliminationOrder = new ArrayList<>();
    private final Map<UUID, GameMode> previousModes = new HashMap<>();

    private final Map<UUID, Integer> potatoTimers = new HashMap<>();

    private boolean running = false;
    private BukkitTask tickTask;

    public HotPotatoManager(NinjaxxGames plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Patate Chaude";
    }

    @Override
    public void addPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        plugin.getSessionManager().setCurrentGame(uuid, ID);

        if (running) {
            spectators.add(uuid);
            plugin.getSessionManager().setState(uuid, PlayerSessionManager.SessionState.SPECTATOR);
            scoreboard.attach(player);
            sendToSpectator(player);
            player.sendMessage("§c[Patate] §fUne partie est déjà en cours — tu es spectateur jusqu'à la prochaine.");
            return;
        }

        lobbyPlayers.add(uuid);
        plugin.getSessionManager().setState(uuid, PlayerSessionManager.SessionState.IN_GAME);

        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        if (arena != null) {
            teleportToZoneCenter(player, arena);
        }
        player.sendMessage("§c[Patate] §fEn attente du lancement... §7(" + lobbyPlayers.size() + " en attente)");
    }

    @Override
    public void removePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        boolean wasActive = activePlayers.contains(uuid);

        lobbyPlayers.remove(uuid);
        activePlayers.remove(uuid);
        spectators.remove(uuid);
        clearPotato(player);
        scoreboard.detach(player);
        restoreGameMode(player);
        plugin.getSessionManager().clear(uuid);
        sendToHub(player);

        if (running && wasActive && activePlayers.size() <= 1) {
            finishGame();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String start() {
        if (running) {
            return "une partie est déjà en cours";
        }
        if (!plugin.getZoneManager().hasZone(ID, "arena")) {
            return "zone manquante : arena (/ninjaxx setpotatozone)";
        }
        if (!plugin.getZoneManager().hasZone(ID, "spectator")) {
            return "zone manquante : spectator (/ninjaxx setpotatospectatorzone)";
        }
        if (lobbyPlayers.size() < 2) {
            return "il faut au moins 2 joueurs dans le lobby (passés par l'ascenseur)";
        }

        activePlayers.clear();
        activePlayers.addAll(lobbyPlayers);
        spectators.clear();
        previousModes.clear();
        potatoTimers.clear();
        eliminationOrder.clear();
        running = true;

        int participation = plugin.getConfig().getInt("hotpotato.points.participation", 10);
        for (UUID uuid : activePlayers) {
            plugin.getScoreManager().addPoints(uuid, participation);
        }

        for (UUID uuid : activePlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) scoreboard.attach(p);
        }
        broadcastIntro();
        distributePotatoes();

        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!running) {
                    cancel();
                    return;
                }
                tick();
            }
        }.runTaskTimer(plugin, 20L, 20L);

        updateScoreboards();
        return null;
    }

    private void tick() {

        int warning = Math.max(1, plugin.getConfig().getInt("hotpotato.warning-seconds", 10));
        List<UUID> exploded = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : new HashMap<>(potatoTimers).entrySet()) {
            int left = entry.getValue() - 1;
            if (left <= 0) {
                exploded.add(entry.getKey());
            } else {
                potatoTimers.put(entry.getKey(), left);
                if (left <= warning) {
                    Player holder = plugin.getServer().getPlayer(entry.getKey());
                    if (holder != null) {
                        float pitch = (left % 2 == 0) ? 1.4f : 0.9f;
                        holder.playSound(holder.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.4f, pitch);
                    }
                }
            }
        }

        for (UUID uuid : exploded) {
            potatoTimers.remove(uuid);
            Player p = plugin.getServer().getPlayer(uuid);
            String name = p != null ? p.getName() : "?";
            broadcast("§c💥 §fLa patate a explosé sur §e" + name + " §f— éliminé !");
            eliminate(uuid);
        }

        if (activePlayers.size() <= 1) {
            finishGame();
            return;
        }

        distributePotatoes();
        updateScoreboards();
    }

    private void distributePotatoes() {
        int desired = desiredPotatoCount();
        List<UUID> candidates = new ArrayList<>();
        for (UUID uuid : activePlayers) {
            if (!potatoTimers.containsKey(uuid)) candidates.add(uuid);
        }
        Collections.shuffle(candidates, random);

        int index = 0;
        while (potatoTimers.size() < desired && index < candidates.size()) {
            UUID uuid = candidates.get(index++);
            addPotato(uuid, randomTimer());
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) {
                p.sendMessage("§c🥔 [Patate] §fTu as la patate chaude ! §7Refile-la vite en tapant quelqu'un.");
            }
        }
    }

    private int desiredPotatoCount() {
        int active = activePlayers.size();
        int perPotato = Math.max(1, plugin.getConfig().getInt("hotpotato.players-per-potato", 8));
        int desired = Math.max(1, (int) Math.round(active / (double) perPotato));

        return Math.min(desired, Math.max(1, active - 1));
    }

    private int randomTimer() {
        int min = Math.max(1, plugin.getConfig().getInt("hotpotato.min-timer-seconds", 15));
        int max = Math.max(min, plugin.getConfig().getInt("hotpotato.max-timer-seconds", 40));
        return min + random.nextInt(max - min + 1);
    }

    public boolean isActiveParticipant(Player player) {
        return running && activePlayers.contains(player.getUniqueId());
    }

    public void handleHit(Player attacker, Player victim) {
        if (!running) return;
        UUID attackerId = attacker.getUniqueId();
        UUID victimId = victim.getUniqueId();
        if (!activePlayers.contains(attackerId) || !activePlayers.contains(victimId)) return;
        if (!potatoTimers.containsKey(attackerId)) return;
        if (potatoTimers.containsKey(victimId)) return;

        int remaining = potatoTimers.remove(attackerId);
        clearPotato(attacker);
        addPotato(victimId, remaining);

        applyWindBurst(attacker, victim);

        victim.sendMessage("§c🥔 [Patate] §e" + attacker.getName() + " §ft'a refilé la patate ! §7Vite, débarrasse-t'en !");
        attacker.sendMessage("§a[Patate] §fOuf, tu t'es débarrassé de la patate sur §e" + victim.getName() + " §f!");
        updateScoreboards();
    }

    private void applyWindBurst(Player from, Player victim) {
        Vector push = victim.getLocation().toVector().subtract(from.getLocation().toVector());
        push.setY(0);
        if (push.lengthSquared() < 1.0E-4) {
            push = victim.getLocation().getDirection().setY(0);
        }
        if (push.lengthSquared() > 1.0E-4) {
            push.normalize();
        }
        double strength = plugin.getConfig().getDouble("hotpotato.windburst-strength", 0.9);
        double lift = plugin.getConfig().getDouble("hotpotato.windburst-lift", 0.55);
        push.multiply(strength).setY(lift);
        victim.setVelocity(push);

        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.2f, 1.0f);
        victim.getWorld().spawnParticle(Particle.GUST, victim.getLocation().add(0, 1, 0), 1);
    }

    private void eliminate(UUID uuid) {
        activePlayers.remove(uuid);
        spectators.add(uuid);
        eliminationOrder.add(uuid);
        potatoTimers.remove(uuid);
        plugin.getSessionManager().setState(uuid, PlayerSessionManager.SessionState.ELIMINATED);

        Player p = plugin.getServer().getPlayer(uuid);
        if (p != null) {
            clearPotato(p);
            sendToSpectator(p);
            p.sendMessage("§c[Patate] §fTu es éliminé ! Direction la zone des éliminés.");
        }
    }

    @Override
    public void stop() {
        if (running || !lobbyPlayers.isEmpty() || !spectators.isEmpty()) {
            broadcast("§c[Patate] §fPartie arrêtée par un administrateur.");
        }
        running = false;
        cleanupAndReset();
    }

    private void finishGame() {
        running = false;
        UUID winner = activePlayers.isEmpty() ? null : activePlayers.iterator().next();
        if (winner != null) {
            Player wp = plugin.getServer().getPlayer(winner);
            String name = wp != null ? wp.getName() : "?";
            broadcast("§6🏆 [Patate] §e" + name + " §fest le dernier survivant, il gagne !");
        } else {
            broadcast("§c[Patate] §fFin de la partie, aucun gagnant.");
        }
        awardPlacementPoints();
        cleanupAndReset();
    }

    private void awardPlacementPoints() {
        List<UUID> standings = new ArrayList<>(activePlayers);
        List<UUID> elimReversed = new ArrayList<>(eliminationOrder);
        Collections.reverse(elimReversed);
        standings.addAll(elimReversed);

        int total = standings.size();
        int winBonus = plugin.getConfig().getInt("hotpotato.points.win-bonus", 100);

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

            Player p = plugin.getServer().getPlayer(uuid);
            String name = p != null ? p.getName() : uuid.toString();
            String rankLabel = switch (rank) {
                case 1 -> "§6🥇 1er";
                case 2 -> "§7🥈 2e";
                case 3 -> "§c🥉 3e";
                default -> "§f#" + rank;
            };
            String ptsLabel = bonus > 0 ? " §7(+" + bonus + " pts)" : "";
            broadcast(rankLabel + " §f- " + name + ptsLabel + " §8[" + rank + "/" + total + "]");
            rank++;
        }
    }

    private void cleanupAndReset() {
        cancelTasks();

        for (UUID uuid : new ArrayList<>(activePlayers)) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) {
                clearPotato(p);
                scoreboard.detach(p);
                sendToHub(p);
            }
        }
        for (UUID uuid : new ArrayList<>(spectators)) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) {
                scoreboard.detach(p);
                restoreGameMode(p);
                sendToHub(p);
            }
        }
        for (UUID uuid : new ArrayList<>(lobbyPlayers)) {
            if (activePlayers.contains(uuid) || spectators.contains(uuid)) continue;
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) {
                scoreboard.detach(p);
                sendToHub(p);
            }
        }
        resetState();
    }

    private void resetState() {
        running = false;
        lobbyPlayers.clear();
        activePlayers.clear();
        spectators.clear();
        eliminationOrder.clear();
        potatoTimers.clear();
        previousModes.clear();
    }

    private void cancelTasks() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    private void broadcastIntro() {
        broadcast("§8§m                                        ");
        broadcast("§c§l🥔 PATATE CHAUDE §7— bombe à retardement");
        broadcast("§7• Le porteur §c(glow rouge)§7 tient une patate à minuteur.");
        broadcast("§7• Frappe quelqu'un pour lui §erefiler la patate §7(le minuteur continue !).");
        broadcast("§7• Quand la patate §cexplose§7, le porteur est §céliminé§7.");
        broadcast("§7• Tes points au §eclassement général §7dépendent de ta §ePLACE finale§7.");
        broadcast("§7• Dernier survivant = §6gagnant §7!");
        broadcast("§8§m                                        ");
    }

    private void updateScoreboards() {
        if (!running) return;

        Set<String> holderNames = new HashSet<>();
        for (UUID uuid : potatoTimers.keySet()) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) holderNames.add(p.getName());
        }
        scoreboard.setHolders(holderNames);

        int alive = activePlayers.size();
        int potatoes = potatoTimers.size();

        for (UUID uuid : activePlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            boolean holder = potatoTimers.containsKey(uuid);
            String potatoLine = holder ? "§7Ta patate §c" + potatoTimers.get(uuid) + "s ⏳" : "§7Ta patate §8—";
            int points = plugin.getScoreManager().getPoints(uuid);
            String statut = holder ? "§c§lTU L'AS ! 🥔" : "§a§lSAFE ✔";
            scoreboard.render(p, SB_TITLE, List.of(
                    "§7Joueurs restants §f" + alive,
                    "§7Patates en jeu §f" + potatoes,
                    "§1",
                    potatoLine,
                    "§7Points §f" + points,
                    "§2",
                    statut
            ));
        }

        for (UUID uuid : spectators) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            int points = plugin.getScoreManager().getPoints(uuid);
            scoreboard.render(p, SB_TITLE, List.of(
                    "§c§lÉLIMINÉ",
                    "§1",
                    "§7Joueurs restants §f" + alive,
                    "§7Points §f" + points,
                    "§2",
                    "§7En attente de la fin"
            ));
        }
    }

    private void addPotato(UUID uuid, int timer) {
        potatoTimers.put(uuid, timer);
        Player p = plugin.getServer().getPlayer(uuid);
        if (p == null) return;
        if (!hasPotatoItem(p)) {
            p.getInventory().addItem(createPotatoItem());
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, Integer.MAX_VALUE, 0, false, false));
    }

    private void clearPotato(Player p) {
        potatoTimers.remove(p.getUniqueId());
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && isPotatoItem(item)) {
                p.getInventory().setItem(i, null);
            }
        }
        p.removePotionEffect(PotionEffectType.GLOWING);
    }

    private ItemStack createPotatoItem() {
        ItemStack item = new ItemStack(Material.POISONOUS_POTATO);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(POTATO_ITEM_NAME);
            meta.setLore(List.of(
                    "§7Refile-la en tapant un joueur !",
                    "§cElle finira par exploser..."
            ));
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isPotatoItem(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() && POTATO_ITEM_NAME.equals(meta.getDisplayName());
    }

    private boolean hasPotatoItem(Player p) {
        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && isPotatoItem(item)) return true;
        }
        return false;
    }

    private void teleportToZoneCenter(Player player, Zone zone) {
        World world = plugin.getServer().getWorld(zone.getWorld());
        if (world == null) return;
        double x = (zone.getMinX() + zone.getMaxX()) / 2.0;
        double y = zone.getMinY() + 1;
        double z = (zone.getMinZ() + zone.getMaxZ()) / 2.0;
        player.teleport(new Location(world, x, y, z));
    }

    private void sendToSpectator(Player player) {
        Zone spectator = plugin.getZoneManager().getZone(ID, "spectator");
        if (spectator != null) {
            teleportToZoneCenter(player, spectator);
        }
        previousModes.putIfAbsent(player.getUniqueId(), player.getGameMode());
        player.setGameMode(GameMode.ADVENTURE);
    }

    private void restoreGameMode(Player player) {
        GameMode prev = previousModes.remove(player.getUniqueId());
        if (prev != null) {
            player.setGameMode(prev);
        }
    }

    private void sendToHub(Player player) {
        plugin.sendToHub(player);
    }

    private void broadcast(String message) {
        Set<UUID> all = new LinkedHashSet<>(lobbyPlayers);
        all.addAll(activePlayers);
        all.addAll(spectators);
        for (UUID uuid : all) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.sendMessage(message);
        }
    }
}
