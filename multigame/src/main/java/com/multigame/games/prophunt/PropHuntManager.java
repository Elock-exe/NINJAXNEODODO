package com.multigame.games.prophunt;

import com.multigame.MultiGame;
import com.multigame.games.GlowSidebar;
import com.multigame.games.MiniGame;
import com.multigame.managers.PlayerSessionManager;
import com.multigame.models.Zone;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class PropHuntManager implements MiniGame {

    public static final String ID = "prophunt";
    private static final String TOOL_NAME = "§a§lCAMOUFLAGE §7(clic droit sur un bloc)";
    private static final String SEEKER_SWORD_NAME = "§c🗡 Traqueur";
    private static final String SB_TITLE = "§b§l🔍 PROP HUNT";
    private static final int SYNC_TICKS = 60;

    private record Taunt(Material material, String name, Sound sound) {}

    private static final List<Taunt> TAUNTS = List.of(
            new Taunt(Material.LEATHER, "§e🐄 Meuh", Sound.ENTITY_COW_AMBIENT),
            new Taunt(Material.FEATHER, "§e🐔 Cocorico", Sound.ENTITY_CHICKEN_AMBIENT),
            new Taunt(Material.PORKCHOP, "§e🐷 Groin", Sound.ENTITY_PIG_AMBIENT),
            new Taunt(Material.ROTTEN_FLESH, "§e🧟 Zombie", Sound.ENTITY_ZOMBIE_AMBIENT),
            new Taunt(Material.BONE, "§e🐺 Loup", Sound.ENTITY_WOLF_AMBIENT),
            new Taunt(Material.GUNPOWDER, "§e💥 Boum", Sound.ENTITY_GENERIC_EXPLODE),
            new Taunt(Material.ENDER_PEARL, "§e👾 Ender", Sound.ENTITY_ENDERMAN_AMBIENT)
    );

    private enum Phase { IDLE, HIDING, SEEKING }

    private final MultiGame plugin;
    private final Random random = new Random();

    private final GlowSidebar scoreboard = new GlowSidebar(ChatColor.WHITE, "ph_hiders", "ph_sb", true);

    private final Set<UUID> lobbyPlayers = new LinkedHashSet<>();
    private final Set<UUID> activePlayers = new LinkedHashSet<>();
    private final Set<UUID> seekers = new LinkedHashSet<>();
    private final Set<UUID> hiders = new LinkedHashSet<>();

    private final Map<UUID, BlockDisplay> disguises = new HashMap<>();
    private final Map<UUID, BlockDisplay> upperDisguises = new HashMap<>();
    private final Map<UUID, Location> lastLoc = new HashMap<>();
    private final Map<UUID, Integer> foundCount = new HashMap<>();
    private final Map<UUID, Integer> immobileTicks = new HashMap<>();
    private final Set<UUID> synchronizedHiders = new HashSet<>();
    private final Map<UUID, Integer> survivalEarned = new HashMap<>();
    private final Map<UUID, Integer> hiderHealth = new HashMap<>();

    private Phase phase = Phase.IDLE;
    private BossBar bossBar;
    private BukkitTask task;

    private int tickCounter;
    private int hideRemaining;
    private int hideDuration;
    private int seekRemaining;
    private int seekDuration;
    private int seekElapsed;
    private int totalHidersInitial;
    private int findValue;
    private int pointsPerSecond;
    private int hiderMaxHits;

    public PropHuntManager(MultiGame plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Prop Hunt";
    }

    @Override
    public boolean isRunning() {
        return phase != Phase.IDLE;
    }

    @Override
    public void addPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        plugin.getSessionManager().setCurrentGame(uuid, ID);

        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        if (arena != null) {
            teleportToZoneCenter(player, arena);
        }

        if (isRunning()) {

            activePlayers.add(uuid);
            seekers.add(uuid);
            plugin.getSessionManager().setState(uuid, PlayerSessionManager.SessionState.IN_GAME);
            if (bossBar != null) bossBar.addPlayer(player);
            scoreboard.attach(player);
            player.sendMessage("§b[Prop Hunt] §fPartie en cours — tu rejoins comme §cchercheur§f.");
            updateScoreboards();
            return;
        }

        lobbyPlayers.add(uuid);
        plugin.getSessionManager().setState(uuid, PlayerSessionManager.SessionState.IN_GAME);
        player.sendMessage("§b[Prop Hunt] §fEn attente du lancement... §7(" + lobbyPlayers.size() + " en attente)");
    }

    @Override
    public void removePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        boolean wasHider = hiders.contains(uuid);

        lobbyPlayers.remove(uuid);
        activePlayers.remove(uuid);
        seekers.remove(uuid);
        hiders.remove(uuid);
        clearDisguise(player);
        removeGameEffects(player);
        removeSeekerWeapon(player);
        foundCount.remove(uuid);
        hiderHealth.remove(uuid);
        scoreboard.detach(player);
        if (bossBar != null) bossBar.removePlayer(player);
        plugin.getSessionManager().clear(uuid);
        sendToHub(player);

        if (isRunning() && (hiders.isEmpty() || activePlayers.size() <= 1)) {
            endGame();
        } else if (wasHider) {
            broadcast("§b[Prop Hunt] §7Il reste §e" + hiders.size() + " §7caché(s).");
        }
    }

    @Override
    public String start() {
        if (isRunning()) {
            return "une partie est déjà en cours";
        }
        if (!plugin.getZoneManager().hasZone(ID, "arena")) {
            return "zone manquante : arena (/multigame setprophuntzone)";
        }
        if (lobbyPlayers.size() < 2) {
            return "il faut au moins 2 joueurs dans le lobby (passés par l'ascenseur)";
        }

        activePlayers.clear();
        activePlayers.addAll(lobbyPlayers);
        seekers.clear();
        hiders.clear();
        disguises.clear();
        lastLoc.clear();
        foundCount.clear();
        immobileTicks.clear();
        synchronizedHiders.clear();
        survivalEarned.clear();
        hiderHealth.clear();

        int total = activePlayers.size();
        int perSeeker = Math.max(1, plugin.getConfig().getInt("prophunt.seekers-per-player", 10));
        int seekerCount = Math.max(1, (int) Math.round(total / (double) perSeeker));
        seekerCount = Math.min(seekerCount, total - 1);

        List<UUID> shuffled = new ArrayList<>(activePlayers);
        Collections.shuffle(shuffled, random);
        for (int i = 0; i < shuffled.size(); i++) {
            if (i < seekerCount) seekers.add(shuffled.get(i));
            else hiders.add(shuffled.get(i));
        }

        totalHidersInitial = hiders.size();
        int winBonus = plugin.getConfig().getInt("prophunt.points.win-bonus", 100);
        findValue = Math.max(1, (int) Math.round(winBonus / (double) totalHidersInitial));
        pointsPerSecond = Math.max(0, plugin.getConfig().getInt("prophunt.points.per-second", 2));
        hiderMaxHits = Math.max(1, plugin.getConfig().getInt("prophunt.hider-hits", 3));

        int participation = plugin.getConfig().getInt("prophunt.points.participation", 10);
        for (UUID uuid : activePlayers) {
            plugin.getScoreManager().addPoints(uuid, participation);
        }

        bossBar = Bukkit.createBossBar("§bProp Hunt", BarColor.BLUE, BarStyle.SOLID);
        for (UUID uuid : activePlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) bossBar.addPlayer(p);
        }

        for (UUID uuid : activePlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) scoreboard.attach(p);
        }

        for (UUID uuid : hiders) {
            hiderHealth.put(uuid, hiderMaxHits);
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
            p.getInventory().addItem(createDisguiseTool());
            giveTauntItems(p);
            p.sendMessage("§a[Prop Hunt] §fTu es §aCACHÉ§f ! Clic droit sur un bloc pour te déguiser.");
            p.sendMessage("§7[Prop Hunt] Tu encaisses §e" + hiderMaxHits + " §7coup(s) avant d'être démasqué.");
            p.sendMessage("§7[Prop Hunt] Les objets §ejaunes §7jouent des §ebruits §7pour piéger les chercheurs (clic droit).");
        }
        for (UUID uuid : seekers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            freezeSeeker(p);
            giveSeekerWeapon(p);
            p.sendMessage("§c[Prop Hunt] §fTu es §cCHERCHEUR§f ! Tu es aveuglé pendant la phase de camouflage...");
        }

        phase = Phase.HIDING;
        hideDuration = Math.max(1, plugin.getConfig().getInt("prophunt.hide-duration-seconds", 45));
        hideRemaining = hideDuration;
        seekDuration = Math.max(1, plugin.getConfig().getInt("prophunt.seek-duration-seconds", 180));
        seekElapsed = 0;
        tickCounter = 0;

        broadcastIntro();
        updateScoreboards();

        task = new BukkitRunnable() {
            @Override
            public void run() {
                if (phase == Phase.IDLE) {
                    cancel();
                    return;
                }
                followDisguises();
                if (++tickCounter % 20 == 0) {
                    doSecond();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return null;
    }

    private void updateScoreboards() {
        if (!isRunning()) return;

        Set<String> hiderNames = new HashSet<>();
        for (UUID uuid : hiders) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) hiderNames.add(p.getName());
        }
        scoreboard.setHolders(hiderNames);

        String timeLine = phase == Phase.HIDING
                ? "§7Camouflage §f" + hideRemaining + "s"
                : "§7Temps §f" + formatTime(seekRemaining);

        for (UUID uuid : activePlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            int points = plugin.getScoreManager().getPoints(uuid);
            if (seekers.contains(uuid)) {
                int found = foundCount.getOrDefault(uuid, 0);
                scoreboard.render(p, SB_TITLE, List.of(
                        "§7Rôle §c§lCHERCHEUR",
                        timeLine,
                        "§1",
                        "§7Trouvés §a" + found,
                        "§7Cachés restants §e" + hiders.size(),
                        "§2",
                        "§7Récompense §f+" + findValue + " §7/trouvaille",
                        "§7Tes points §f" + points
                ));
            } else {
                String syncLine = synchronizedHiders.contains(uuid)
                        ? "§a✔ Synchronisé"
                        : "§e… reste immobile 3s";
                scoreboard.render(p, SB_TITLE, List.of(
                        "§7Rôle §a§lCACHÉ",
                        timeLine,
                        "§1",
                        "§7Survie §f" + seekElapsed + "s",
                        syncLine,
                        "§7Cachés restants §e" + hiders.size(),
                        "§2",
                        "§7Gagné §a+" + survivalEarned.getOrDefault(uuid, 0) + " §7(survie)",
                        "§7Tes points §f" + points
                ));
            }
        }
    }

    private void broadcastIntro() {
        broadcast("§8§m                                        ");
        broadcast("§b§l🔍 PROP HUNT §7— cache-toi ou trouve !");
        broadcast("§7• §aCACHÉS §7: clic droit sur un bloc pour prendre son apparence.");
        broadcast("§7  Immobile = fondu dans le décor, en mouvement = repérable !");
        broadcast("§7• §cCHERCHEURS §7: aveugles §e" + hideDuration + "s§7, puis frappez les blocs suspects.");
        broadcast("§7• Un caché trouvé §edevient chercheur§7.");
        broadcast("§7• Points : les cachés gagnent selon leur §etemps de survie§7,");
        broadcast("§7  les chercheurs gagnent à §echaque personne trouvée§7.");
        broadcast("§7• Fin : temps écoulé §7ou §7tous les cachés trouvés.");
        broadcast("§8§m                                        ");
    }

    private void doSecond() {
        if (phase == Phase.HIDING) {
            hideRemaining--;
            if (bossBar != null) {
                bossBar.setTitle("§eCamouflage §7— §f" + hideRemaining + "s");
                bossBar.setColor(BarColor.YELLOW);
                bossBar.setProgress(clamp01(hideRemaining / (double) hideDuration));
            }
            if (hideRemaining <= 0) {
                beginSeeking();
            } else {
                updateScoreboards();
            }
        } else if (phase == Phase.SEEKING) {
            seekElapsed++;
            seekRemaining--;
            if (pointsPerSecond > 0) {
                for (UUID uuid : hiders) {
                    plugin.getScoreManager().addPoints(uuid, pointsPerSecond);
                    survivalEarned.merge(uuid, pointsPerSecond, Integer::sum);
                }
            }
            if (bossBar != null) {
                bossBar.setTitle("§cChasse §7— §f" + formatTime(seekRemaining) + " §7| §e" + hiders.size() + " §7caché(s)");
                bossBar.setColor(BarColor.RED);
                bossBar.setProgress(clamp01(seekRemaining / (double) seekDuration));
            }
            int soundInterval = plugin.getConfig().getInt("prophunt.hider-sound-interval-seconds", 20);
            if (soundInterval > 0 && seekElapsed > 0 && seekElapsed % soundInterval == 0) {
                playHiderRevealSound();
            }
            if (seekRemaining <= 0) {
                endGame();
            } else {
                updateScoreboards();
            }
        }
    }

    private void playHiderRevealSound() {
        for (UUID uuid : hiders) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.4f, 1.0f);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.2f, 0.7f);
        }
    }

    private void beginSeeking() {
        phase = Phase.SEEKING;
        seekRemaining = seekDuration;
        for (UUID uuid : seekers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) unfreezeSeeker(p);
        }
        broadcast("§c§l🔍 LA CHASSE COMMENCE ! §fTrouvez les cachés en les frappant.");
        updateScoreboards();
    }

    public void handleHit(Player attacker, Player victim) {
        if (phase != Phase.SEEKING) return;
        UUID attackerId = attacker.getUniqueId();
        UUID victimId = victim.getUniqueId();
        if (!seekers.contains(attackerId)) return;
        if (!hiders.contains(victimId)) return;

        int remaining = hiderHealth.getOrDefault(victimId, 1) - 1;
        if (remaining <= 0) {
            hiderHealth.remove(victimId);
            found(victimId, attackerId);
            return;
        }

        hiderHealth.put(victimId, remaining);
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, 1.0f, 1.0f);
        attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 1.0f, 1.2f);
        attacker.sendMessage("§e[Prop Hunt] §fTouché ! Encore §c" + remaining + " §fcoup(s) pour le démasquer.");
        victim.sendMessage("§c[Prop Hunt] §fAïe ! Il te reste §e" + remaining + " §fPV — fuis !");
    }

    private void found(UUID hiderId, UUID seekerId) {
        hiders.remove(hiderId);
        hiderHealth.remove(hiderId);

        Player hider = plugin.getServer().getPlayer(hiderId);
        if (hider != null) {
            clearDisguise(hider);
            removeGameEffects(hider);
            removeTauntItems(hider);
            giveSeekerWeapon(hider);

            int earned = survivalEarned.getOrDefault(hiderId, 0);
            hider.sendMessage("§c[Prop Hunt] §fTrouvé ! Tu as survécu §e" + seekElapsed + "s §f→ §a+" + earned + " pts §fau total. Tu deviens chercheur.");
        }

        seekers.add(hiderId);

        Player seeker = plugin.getServer().getPlayer(seekerId);
        if (seeker != null) {
            foundCount.merge(seekerId, 1, Integer::sum);
            plugin.getScoreManager().addPoints(seekerId, findValue);
            seeker.sendMessage("§a[Prop Hunt] §fTrouvé §e" + (hider != null ? hider.getName() : "?") + " §f→ §a+" + findValue + " pts§f !");
        }

        String hiderName = hider != null ? hider.getName() : "?";
        String seekerName = seeker != null ? seeker.getName() : "?";
        broadcast("§c🔍 §e" + seekerName + " §fa trouvé §e" + hiderName + " §7(" + hiders.size() + " caché(s) restant(s))");

        if (hiders.isEmpty()) {
            endGame();
        } else {
            updateScoreboards();
        }
    }

    @Override
    public void stop() {
        if (isRunning() || !lobbyPlayers.isEmpty()) {
            broadcast("§b[Prop Hunt] §fPartie arrêtée par un administrateur.");
        }
        endGameInternal(false);
    }

    private void endGame() {
        endGameInternal(true);
    }

    private void endGameInternal(boolean awardSurvivors) {
        if (awardSurvivors) {
            int survivorBonus = plugin.getConfig().getInt("prophunt.points.survivor-bonus", 25);
            for (UUID uuid : hiders) {
                if (survivorBonus > 0) {
                    plugin.getScoreManager().addPoints(uuid, survivorBonus);
                    survivalEarned.merge(uuid, survivorBonus, Integer::sum);
                }
                int earned = survivalEarned.getOrDefault(uuid, 0);
                Player p = plugin.getServer().getPlayer(uuid);
                if (p != null) {
                    p.sendMessage("§a[Prop Hunt] §fTu as survécu jusqu'au bout ! §a+" + earned + " pts §7(dont +" + survivorBonus + " bonus)");
                }
            }
            broadcast("§b§l🔍 PROP HUNT §f— fin de la partie ! §e" + hiders.size() + " §fcaché(s) ont survécu.");
        }
        cleanupAndReset();
    }

    private void cleanupAndReset() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID uuid : new ArrayList<>(activePlayers)) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) {
                clearDisguise(p);
                removeGameEffects(p);
                removeToolItems(p);
                removeTauntItems(p);
                removeSeekerWeapon(p);
                scoreboard.detach(p);
                sendToHub(p);
            }
        }
        for (BlockDisplay display : disguises.values()) {
            if (display != null && !display.isDead()) display.remove();
        }
        for (BlockDisplay display : upperDisguises.values()) {
            if (display != null && !display.isDead()) display.remove();
        }
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar.setVisible(false);
            bossBar = null;
        }
        lobbyPlayers.clear();
        activePlayers.clear();
        seekers.clear();
        hiders.clear();
        disguises.clear();
        upperDisguises.clear();
        lastLoc.clear();
        foundCount.clear();
        immobileTicks.clear();
        synchronizedHiders.clear();
        survivalEarned.clear();
        hiderHealth.clear();
        phase = Phase.IDLE;
    }

    public void handleDisguise(Player player, Block clickedBlock) {
        if (phase != Phase.HIDING && phase != Phase.SEEKING) return;
        if (!hiders.contains(player.getUniqueId())) return;
        if (clickedBlock == null || clickedBlock.getType() == Material.AIR) return;

        disguiseAs(player, clickedBlock.getBlockData());
    }

    private void disguiseAs(Player player, BlockData data) {
        World world = player.getWorld();
        UUID uuid = player.getUniqueId();

        removeDisguiseEntities(uuid);

        BlockData lowerData = data;
        BlockData upperData = null;
        boolean twoTall = data instanceof Bisected
                && !(data instanceof org.bukkit.block.data.type.Stairs)
                && !(data instanceof org.bukkit.block.data.type.TrapDoor);
        if (twoTall) {
            Bisected low = (Bisected) data.clone();
            low.setHalf(Bisected.Half.BOTTOM);
            lowerData = low;
            Bisected up = (Bisected) data.clone();
            up.setHalf(Bisected.Half.TOP);
            upperData = up;
        }

        Location base = centeredLocation(player.getLocation());
        BlockDisplay display = world.spawn(base, BlockDisplay.class);
        display.setBlock(lowerData);
        display.setPersistent(false);
        disguises.put(uuid, display);

        if (upperData != null) {
            BlockDisplay upper = world.spawn(base.clone().add(0, 1, 0), BlockDisplay.class);
            upper.setBlock(upperData);
            upper.setPersistent(false);
            upperDisguises.put(uuid, upper);
        }

        lastLoc.put(uuid, player.getLocation());
        player.setCollidable(true);
        removeToolItems(player);
        hideHeldItem(player);
        player.sendMessage("§a[Prop Hunt] §fTu es déguisé en §e" + data.getMaterial().name().toLowerCase(Locale.ROOT) + "§f. Reste sous ton bloc pour te fondre dans le décor.");
    }

    private void hideHeldItem(Player player) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType() == Material.AIR) {
                player.getInventory().setHeldItemSlot(slot);
                return;
            }
        }
    }

    private void removeDisguiseEntities(UUID uuid) {
        BlockDisplay main = disguises.remove(uuid);
        if (main != null && !main.isDead()) main.remove();
        BlockDisplay upper = upperDisguises.remove(uuid);
        if (upper != null && !upper.isDead()) upper.remove();
    }

    private void followDisguises() {
        for (Map.Entry<UUID, BlockDisplay> entry : disguises.entrySet()) {
            UUID uuid = entry.getKey();
            BlockDisplay display = entry.getValue();
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null || display == null || display.isDead()) continue;

            Location current = p.getLocation();
            Location previous = lastLoc.get(uuid);
            boolean moved = previous == null
                    || previous.getWorld() != current.getWorld()
                    || previous.distanceSquared(current) > 0.0025;

            if (moved) {
                immobileTicks.put(uuid, 0);
                if (synchronizedHiders.remove(uuid)) {
                    p.getWorld().playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.2f);
                    updateScoreboards();
                }
                Location base = centeredLocation(current);
                display.teleport(base);
                BlockDisplay upper = upperDisguises.get(uuid);
                if (upper != null && !upper.isDead()) {
                    upper.teleport(base.clone().add(0, 1, 0));
                }
            } else if (!synchronizedHiders.contains(uuid)) {
                int ticks = immobileTicks.merge(uuid, 1, Integer::sum);
                if (ticks >= SYNC_TICKS) {
                    synchronize(p, display);
                } else {
                    int remaining = (int) Math.ceil((SYNC_TICKS - ticks) / 20.0);
                    actionBar(p, "§eSynchronisation dans §f§l" + remaining + "s§r §e...");
                }
            }
            lastLoc.put(uuid, current);
        }
    }

    private void synchronize(Player player, BlockDisplay display) {
        UUID uuid = player.getUniqueId();
        synchronizedHiders.add(uuid);

        Location snapped = snappedBlockLocation(player.getLocation());
        display.teleport(snapped);
        BlockDisplay upper = upperDisguises.get(uuid);
        if (upper != null && !upper.isDead()) {
            upper.teleport(snapped.clone().add(0, 1, 0));
        }

        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_STONE_PLACE, 1.0f, 1.4f);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 2.0f);
        actionBar(player, "§a§l✔ Camouflage synchronisé §r§7— fondu dans le décor, ne bouge plus !");
        updateScoreboards();
    }

    private Location snappedBlockLocation(Location loc) {
        return new Location(loc.getWorld(),
                Math.floor(loc.getX()), Math.floor(loc.getY()), Math.floor(loc.getZ()));
    }

    private void actionBar(Player player, String legacy) {
        player.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().deserialize(legacy));
    }

    private Location centeredLocation(Location loc) {
        return new Location(loc.getWorld(), loc.getX() - 0.5, loc.getY(), loc.getZ() - 0.5);
    }

    private void clearDisguise(Player player) {
        UUID uuid = player.getUniqueId();
        removeDisguiseEntities(uuid);
        lastLoc.remove(uuid);
        immobileTicks.remove(uuid);
        synchronizedHiders.remove(uuid);
    }

    private void freezeSeeker(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 250, false, false));
        p.setWalkSpeed(0f);
    }

    private void unfreezeSeeker(Player p) {
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        p.removePotionEffect(PotionEffectType.SLOWNESS);
        p.setWalkSpeed(0.2f);
    }

    private void removeGameEffects(Player p) {
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        p.removePotionEffect(PotionEffectType.SLOWNESS);
        p.setWalkSpeed(0.2f);
    }

    private void giveTauntItems(Player p) {
        for (Taunt t : TAUNTS) {
            ItemStack item = new ItemStack(t.material());
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(t.name());
                meta.setLore(List.of(
                        "§7Clic droit : joue ce bruit",
                        "§7Piège les chercheurs !"
                ));
                item.setItemMeta(meta);
            }
            p.getInventory().addItem(item);
        }
    }

    public boolean isTauntItem(ItemStack item) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() && isTauntName(meta.getDisplayName());
    }

    private boolean isTauntName(String name) {
        for (Taunt t : TAUNTS) {
            if (t.name().equals(name)) return true;
        }
        return false;
    }

    public void handleTaunt(Player player, ItemStack item) {
        if (!isRunning()) return;
        if (!hiders.contains(player.getUniqueId())) return;
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;
        String name = meta.getDisplayName();
        for (Taunt t : TAUNTS) {
            if (t.name().equals(name)) {
                player.getWorld().playSound(player.getLocation(), t.sound(), 1.6f, 1.0f);
                return;
            }
        }
    }

    private void removeTauntItems(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null && meta.hasDisplayName() && isTauntName(meta.getDisplayName())) {
                    p.getInventory().setItem(i, null);
                }
            }
        }
    }

    private ItemStack createSeekerSword() {
        ItemStack item = new ItemStack(Material.STONE_SWORD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(SEEKER_SWORD_NAME);
            meta.setLore(List.of(
                    "§7Frappe les blocs suspects",
                    "§7pour démasquer les cachés !"
            ));
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void giveSeekerWeapon(Player p) {
        if (!hasSeekerWeapon(p)) {
            p.getInventory().addItem(createSeekerSword());
        }
    }

    private boolean hasSeekerWeapon(Player p) {
        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null && SEEKER_SWORD_NAME.equals(meta.getDisplayName())) return true;
            }
        }
        return false;
    }

    private void removeSeekerWeapon(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null && SEEKER_SWORD_NAME.equals(meta.getDisplayName())) {
                    p.getInventory().setItem(i, null);
                }
            }
        }
    }

    private ItemStack createDisguiseTool() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(TOOL_NAME);
            meta.setLore(List.of(
                    "§7Clic droit sur un bloc pour prendre son apparence.",
                    "§7Immobile = fondu dans le décor."
            ));
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void removeToolItems(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null && TOOL_NAME.equals(meta.getDisplayName())) {
                    p.getInventory().setItem(i, null);
                }
            }
        }
    }

    private void teleportToZoneCenter(Player player, Zone zone) {
        World world = plugin.getServer().getWorld(zone.getWorld());
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
        for (UUID uuid : activePlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.sendMessage(message);
        }
        for (UUID uuid : lobbyPlayers) {
            if (activePlayers.contains(uuid)) continue;
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.sendMessage(message);
        }
    }

    private double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private String formatTime(int totalSeconds) {
        if (totalSeconds < 0) totalSeconds = 0;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" + seconds : seconds);
    }
}
