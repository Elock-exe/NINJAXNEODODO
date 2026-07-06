package com.ninjaxxgames.games.crowngame;

import com.ninjaxxgames.NinjaxxGames;
import com.ninjaxxgames.games.GlowSidebar;
import com.ninjaxxgames.games.MiniGame;
import com.ninjaxxgames.managers.PlayerSessionManager;
import com.ninjaxxgames.models.Zone;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
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

public class CrownGameManager implements MiniGame {

    public static final String ID = "crowngame";
    private static final String CROWN_ITEM_NAME = "§6👑 Couronne";
    private static final String SB_TITLE = "§6§l⚔ CROWN GAME";

    private final NinjaxxGames plugin;
    private final GlowSidebar scoreboard = new GlowSidebar(ChatColor.YELLOW, "crown_glow", "crown_sb");

    private final Set<UUID> lobbyPlayers = new LinkedHashSet<>();
    private final Set<UUID> activePlayers = new LinkedHashSet<>();
    private final Set<UUID> spectators = new LinkedHashSet<>();
    private final Set<UUID> crownHolders = new LinkedHashSet<>();

    private final List<UUID> eliminationOrder = new ArrayList<>();

    private final Map<UUID, Integer> crownTime = new HashMap<>();

    private final Map<UUID, GameMode> previousModes = new HashMap<>();

    private boolean running = false;
    private BukkitTask tickTask;
    private int remainingSeconds;
    private int currentRound;
    private int nextEventIn;
    private final Random random = new Random();

    public CrownGameManager(NinjaxxGames plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Crown Game";
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
            player.sendMessage("§e[Crown Game] §fUn tournoi est déjà en cours — tu es spectateur jusqu'à la prochaine partie.");
            return;
        }

        lobbyPlayers.add(uuid);
        plugin.getSessionManager().setState(uuid, PlayerSessionManager.SessionState.IN_GAME);

        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        if (arena != null) {
            teleportToZoneCenter(player, arena);
        }
        player.sendMessage("§e[Crown Game] §fEn attente du lancement du tournoi... §7(" + lobbyPlayers.size() + " en attente)");
    }

    @Override
    public void removePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        boolean wasActive = activePlayers.contains(uuid);

        lobbyPlayers.remove(uuid);
        activePlayers.remove(uuid);
        spectators.remove(uuid);
        crownTime.remove(uuid);
        if (crownHolders.contains(uuid)) {
            clearCrownVisuals(player);
        }
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
            return "un tournoi est déjà en cours";
        }
        if (!plugin.getZoneManager().hasZone(ID, "arena")) {
            return "zone manquante : arena (/ninjaxx setarenazone)";
        }
        if (!plugin.getZoneManager().hasZone(ID, "spectator")) {
            return "zone manquante : spectator (/ninjaxx setspectatorzone)";
        }
        if (lobbyPlayers.size() < 2) {
            return "il faut au moins 2 joueurs dans le lobby (passés par l'ascenseur)";
        }

        activePlayers.clear();
        activePlayers.addAll(lobbyPlayers);
        spectators.clear();
        previousModes.clear();
        crownHolders.clear();
        crownTime.clear();
        eliminationOrder.clear();
        running = true;
        currentRound = 0;

        int participation = plugin.getConfig().getInt("crowngame.points.participation", 10);
        for (UUID uuid : activePlayers) {
            plugin.getScoreManager().addPoints(uuid, participation);
        }

        for (UUID uuid : activePlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) scoreboard.attach(p);
        }
        broadcastIntro();
        startRound();

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

        return null;
    }

    private void tick() {

        for (UUID holder : crownHolders) {
            crownTime.merge(holder, 1, Integer::sum);
        }

        if (--nextEventIn <= 0) {
            triggerChaosEvent();
            nextEventIn = plugin.getConfig().getInt("crowngame.event-interval-seconds", 25);
        }

        remainingSeconds--;
        updateScoreboards();
        if (remainingSeconds <= 0) {
            endRound();
        }
    }

    private void triggerChaosEvent() {
        if (!plugin.getConfig().getBoolean("crowngame.events-enabled", true)) return;
        if (activePlayers.size() < 2) return;

        int duration = plugin.getConfig().getInt("crowngame.event-duration-seconds", 8) * 20;
        switch (random.nextInt(4)) {
            case 0 -> chaosEffect("§b§l☁ BASSE GRAVITÉ !", "§7Tout le monde flotte...",
                    Sound.ENTITY_PHANTOM_FLAP, duration,
                    new PotionEffect(PotionEffectType.SLOW_FALLING, duration, 0, false, false),
                    new PotionEffect(PotionEffectType.JUMP_BOOST, duration, 2, false, false));
            case 1 -> chaosEffect("§e§l⚡ RAPIDITÉ !", "§7Attrapez la couronne, vite !",
                    Sound.ENTITY_EXPERIENCE_ORB_PICKUP, duration,
                    new PotionEffect(PotionEffectType.SPEED, duration, 1, false, false));
            case 2 -> chaosEffect("§a§l🦘 SUPER SAUT !", "§7Bondissez partout !",
                    Sound.ENTITY_RABBIT_JUMP, duration,
                    new PotionEffect(PotionEffectType.JUMP_BOOST, duration, 4, false, false));
            default -> chaosWindGust();
        }
    }

    private Component legacy(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    private void chaosEffect(String title, String subtitle, Sound sound, int duration, PotionEffect... effects) {
        for (UUID uuid : activePlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            for (PotionEffect e : effects) p.addPotionEffect(e);
            p.showTitle(Title.title(legacy(title), legacy(subtitle)));
            p.playSound(p.getLocation(), sound, 1.2f, 1.0f);
        }
        broadcast("§6[Crown Game] " + title + " §f— " + subtitle);
    }

    private void chaosWindGust() {
        broadcast("§6[Crown Game] §f§l💨 RAFALE DE VENT ! §7— tenez-vous prêts !");
        for (UUID uuid : activePlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            World world = p.getWorld();
            world.spawnParticle(Particle.GUST, p.getLocation().add(0, 1, 0), 15, 0.8, 0.5, 0.8, 0.1);
            world.playSound(p.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.3f, 1.0f);
            Vector push = new Vector((random.nextDouble() - 0.5) * 1.2, 0.8, (random.nextDouble() - 0.5) * 1.2);
            p.setVelocity(p.getVelocity().add(push));
        }
    }

    private void startRound() {
        currentRound++;

        for (UUID uuid : new ArrayList<>(crownHolders)) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) clearCrownVisuals(p);
        }
        crownHolders.clear();
        crownTime.clear();
        for (UUID uuid : activePlayers) {
            crownTime.put(uuid, 0);
        }

        int total = activePlayers.size();
        double ratio = plugin.getConfig().getDouble("crowngame.crown-ratio", 0.5);
        int crownCount = Math.max(1, (int) Math.round(total * ratio));
        crownCount = Math.min(crownCount, total);

        List<UUID> shuffled = new ArrayList<>(activePlayers);
        Collections.shuffle(shuffled);
        for (int i = 0; i < crownCount; i++) {
            giveCrown(shuffled.get(i));
        }

        remainingSeconds = plugin.getConfig().getInt("crowngame.round-duration-seconds", 180);
        nextEventIn = plugin.getConfig().getInt("crowngame.event-interval-seconds", 25);
        broadcast("§6[Crown Game] §fManche §e" + currentRound + " §f— §e" + total
                + " §fjoueurs, §e" + crownCount + " §fcouronne(s). Garde la couronne pour survivre !");
        updateScoreboards();
    }

    private void endRound() {
        int total = activePlayers.size();
        int eliminateCount = total / 2;

        if (eliminateCount > 0) {

            List<UUID> ranking = new ArrayList<>(activePlayers);
            Collections.shuffle(ranking);
            ranking.sort(Comparator.comparingInt(u -> crownTime.getOrDefault(u, 0)));

            List<UUID> eliminated = new ArrayList<>(ranking.subList(0, eliminateCount));
            for (UUID uuid : eliminated) {
                eliminate(uuid);
            }
        }

        for (UUID uuid : new ArrayList<>(crownHolders)) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) clearCrownVisuals(p);
        }
        crownHolders.clear();

        if (activePlayers.size() <= 1) {
            finishGame();
        } else {
            startRound();
        }
    }

    private void eliminate(UUID uuid) {
        activePlayers.remove(uuid);
        spectators.add(uuid);
        eliminationOrder.add(uuid);
        plugin.getSessionManager().setState(uuid, PlayerSessionManager.SessionState.ELIMINATED);

        Player p = plugin.getServer().getPlayer(uuid);
        if (p != null) {
            int held = crownTime.getOrDefault(uuid, 0);
            if (crownHolders.contains(uuid)) clearCrownVisuals(p);
            sendToSpectator(p);
            p.sendMessage("§c[Crown Game] §fÉliminé ! Couronne gardée §e" + held + "s§f cette manche. Direction les gradins.");
        }
    }

    @Override
    public void stop() {
        if (running || !lobbyPlayers.isEmpty() || !spectators.isEmpty()) {
            broadcast("§6[Crown Game] §fPartie arrêtée par un administrateur.");
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
            broadcast("§6👑 [Crown Game] §e" + name + " §fremporte le tournoi ! §7(manche " + currentRound + ")");
        } else {
            broadcast("§6[Crown Game] §fFin de la partie, aucun gagnant.");
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
        int winBonus = plugin.getConfig().getInt("crowngame.points.win-bonus", 100);

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
                if (crownHolders.contains(uuid)) clearCrownVisuals(p);
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
        currentRound = 0;
        remainingSeconds = 0;
        lobbyPlayers.clear();
        activePlayers.clear();
        spectators.clear();
        crownHolders.clear();
        crownTime.clear();
        eliminationOrder.clear();
        previousModes.clear();
    }

    private void cancelTasks() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    public boolean isCrownHolder(UUID uuid) {
        return crownHolders.contains(uuid);
    }

    public boolean isActivePlayer(UUID uuid) {
        return activePlayers.contains(uuid);
    }

    public void handleHit(Player attacker, Player victim) {
        if (!running) return;
        UUID attackerId = attacker.getUniqueId();
        UUID victimId = victim.getUniqueId();
        if (!activePlayers.contains(attackerId) || !activePlayers.contains(victimId)) return;
        if (!crownHolders.contains(victimId)) return;
        if (crownHolders.contains(attackerId)) return;

        clearCrownVisuals(victim);
        windBurstHit(attacker, victim);
        giveCrown(attackerId);
        updateScoreboards();

        broadcast("§6👑 §e" + attacker.getName() + " §fa volé la couronne de §e" + victim.getName() + " §f!");
    }

    private void giveCrown(UUID uuid) {
        crownHolders.add(uuid);
        crownTime.putIfAbsent(uuid, 0);
        Player p = plugin.getServer().getPlayer(uuid);
        if (p == null) return;

        p.getInventory().setHelmet(createCrownItem());
        p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, Integer.MAX_VALUE, 0, false, false));
        windBurst(p);
        p.sendMessage("§6👑 [Crown Game] §fTu as la couronne ! §7Garde-la pour survivre à la manche.");
    }

    private void windBurstHit(Player attacker, Player victim) {
        World world = victim.getWorld();
        Location center = victim.getLocation();

        world.playSound(center, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.6f, 0.8f);
        world.playSound(center, Sound.ITEM_MACE_SMASH_GROUND, 1.2f, 1.0f);
        world.spawnParticle(Particle.GUST_EMITTER_LARGE, center.clone().add(0, 1, 0), 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.GUST, center.clone().add(0, 1, 0), 25, 1.4, 1.0, 1.4, 0.1);

        Vector push = victim.getLocation().toVector().subtract(attacker.getLocation().toVector());
        if (push.lengthSquared() < 0.0001) push = new Vector(0, 1, 0);
        push.normalize().multiply(1.4);
        push.setY(0.9);
        victim.setVelocity(victim.getVelocity().add(push));
    }

    private void windBurst(Player p) {
        World world = p.getWorld();
        Location center = p.getLocation();

        world.playSound(center, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.4f, 0.9f);
        world.playSound(center, Sound.ITEM_MACE_SMASH_AIR, 1.2f, 1.0f);
        world.spawnParticle(Particle.GUST_EMITTER_LARGE, center.clone().add(0, 1, 0), 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.GUST, center.clone().add(0, 1, 0), 20, 1.2, 0.8, 1.2, 0.1);

        double radius = 4.0;
        for (UUID uuid : activePlayers) {
            if (uuid.equals(p.getUniqueId())) continue;
            Player other = plugin.getServer().getPlayer(uuid);
            if (other == null || other.getWorld() != world) continue;
            double dist = other.getLocation().distance(center);
            if (dist > radius) continue;
            Vector push = other.getLocation().toVector().subtract(center.toVector());
            if (push.lengthSquared() < 0.0001) push = new Vector(0, 1, 0);
            push.normalize().multiply(1.2 * (1.0 - dist / radius));
            push.setY(0.5);
            other.setVelocity(other.getVelocity().add(push));
        }
    }

    private void clearCrownVisuals(Player p) {
        crownHolders.remove(p.getUniqueId());
        ItemStack helmet = p.getInventory().getHelmet();
        if (helmet != null && isCrownItem(helmet)) {
            p.getInventory().setHelmet(null);
        }
        p.removePotionEffect(PotionEffectType.GLOWING);
    }

    private ItemStack createCrownItem() {
        ItemStack item = new ItemStack(Material.GOLDEN_HELMET);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(CROWN_ITEM_NAME);
            meta.setLore(List.of(
                    "§7Garde-la le plus longtemps possible !",
                    "§7Le moins de temps couronne = éliminé"
            ));
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isCrownItem(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() && CROWN_ITEM_NAME.equals(meta.getDisplayName());
    }

    public boolean isCrown(ItemStack item) {
        return item != null && item.getType() == Material.GOLDEN_HELMET && isCrownItem(item);
    }

    private void broadcastIntro() {
        int roundSeconds = plugin.getConfig().getInt("crowngame.round-duration-seconds", 180);
        broadcast("§8§m                                        ");
        broadcast("§6§l⚔ CROWN GAME §7— tournoi par manches");
        broadcast("§7• Vole la couronne §e(casque doré)§7 en frappant celui qui la porte.");
        broadcast("§7• Garde-la pour accumuler du §etemps de couronne§7.");
        broadcast("§7• Fin de manche §c(" + formatTime(roundSeconds) + ")§7 : la moitié qui a gardé");
        broadcast("§7  la couronne le §cmoins §7longtemps est §céliminée §7(spectateur).");
        broadcast("§7• Tes points au §eclassement général §7dépendent de ta §ePLACE finale§7.");
        broadcast("§7• On continue jusqu'au §6dernier survivant §7= le gagnant !");
        broadcast("§8§m                                        ");
    }

    private void updateScoreboards() {
        if (!running) return;

        Set<String> holderNames = new HashSet<>();
        for (UUID uuid : crownHolders) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) holderNames.add(p.getName());
        }
        scoreboard.setHolders(holderNames);

        int total = activePlayers.size();
        int safeCount = total - (total / 2);

        List<UUID> ranked = new ArrayList<>(activePlayers);
        ranked.sort((a, b) -> Integer.compare(
                crownTime.getOrDefault(b, 0), crownTime.getOrDefault(a, 0)));
        Map<UUID, Integer> rankOf = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            rankOf.put(ranked.get(i), i + 1);
        }

        String timeStr = formatTime(remainingSeconds);

        for (UUID uuid : activePlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            int rank = rankOf.getOrDefault(uuid, total);
            int held = crownTime.getOrDefault(uuid, 0);
            int points = plugin.getScoreManager().getPoints(uuid);
            boolean holder = crownHolders.contains(uuid);
            boolean safe = rank <= safeCount;
            String statut = holder ? "§6§lPORTEUR 👑"
                    : (safe ? "§a§lQUALIFIÉ ✔" : "§c§lEN DANGER ✘");
            scoreboard.render(p, SB_TITLE, List.of(
                    "§7Manche §f" + currentRound,
                    "§7Temps restant §f" + timeStr,
                    "§1",
                    "§7Couronne §f" + held + "s",
                    "§7Position §f#" + rank + "§7/" + total,
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
                    "§7Manche §f" + currentRound,
                    "§7Temps restant §f" + timeStr,
                    "§7Points §f" + points,
                    "§2",
                    "§7Tu es spectateur"
            ));
        }
    }

    private String formatTime(int totalSeconds) {
        if (totalSeconds < 0) totalSeconds = 0;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" + seconds : seconds);
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
