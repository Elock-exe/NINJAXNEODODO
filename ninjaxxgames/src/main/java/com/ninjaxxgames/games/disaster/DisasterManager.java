package com.ninjaxxgames.games.disaster;

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
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class DisasterManager implements MiniGame {

    public static final String ID = "disaster";
    private static final String SB_TITLE = "§6§l☄ DISASTER";
    private static final String DASH_ITEM_NAME = "§b🪶 Dash";

    private final NinjaxxGames plugin;
    private final GlowSidebar scoreboard = new GlowSidebar(ChatColor.GOLD, "disaster_glow", "disaster_sb");
    private final Random random = new Random();

    private final Set<UUID> lobbyPlayers = new LinkedHashSet<>();
    private final Set<UUID> activePlayers = new LinkedHashSet<>();
    private final Set<UUID> spectators = new LinkedHashSet<>();
    private final List<UUID> eliminationOrder = new ArrayList<>();
    private final Map<UUID, GameMode> previousModes = new HashMap<>();
    private final Map<UUID, Long> lastDash = new HashMap<>();
    private final Set<UUID> meteorEntities = new HashSet<>();
    private final Set<FallingBlock> tornadoDebris = new HashSet<>();

    private boolean running = false;
    private BukkitTask tickTask;
    private long tickCounter;
    private ZoneSnapshot snapshot;

    private enum Phase { INTERMISSION, DISASTER }
    private Phase phase = Phase.INTERMISSION;
    private List<String> roundSequence = new ArrayList<>();
    private final List<String> activeDisasters = new ArrayList<>();
    private int waveNumber = 0;
    private int intensity = 1;
    private int secondsLeft;

    private final List<Vortex> vortices = new ArrayList<>();

    private static final class Vortex {
        Location center;
        Location target;
        Vortex(Location center) { this.center = center; }
    }

    public DisasterManager(NinjaxxGames plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Disaster";
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
            player.sendMessage("§6[Disaster] §fUne partie est déjà en cours — tu es spectateur jusqu'à la prochaine.");
            return;
        }

        lobbyPlayers.add(uuid);
        plugin.getSessionManager().setState(uuid, PlayerSessionManager.SessionState.IN_GAME);

        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        if (arena != null) {
            teleportToZoneCenter(player, arena);
        }
        player.sendMessage("§6[Disaster] §fEn attente du lancement... §7(" + lobbyPlayers.size() + " en attente)");
    }

    @Override
    public void removePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        boolean wasActive = activePlayers.contains(uuid);

        lobbyPlayers.remove(uuid);
        activePlayers.remove(uuid);
        spectators.remove(uuid);
        clearDashItems(player);
        player.setFlying(false);
        player.setAllowFlight(false);
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
            return "zone manquante : arena (/ninjaxx setdisasterzone)";
        }
        if (!plugin.getZoneManager().hasZone(ID, "spectator")) {
            return "zone manquante : spectator (/ninjaxx setdisasterspectatorzone)";
        }
        if (lobbyPlayers.size() < 1) {
            return "aucun joueur dans le lobby (personne n'est passé par l'ascenseur)";
        }

        roundSequence = resolveRoundSequence();
        if (roundSequence.isEmpty()) {
            return "aucune catastrophe valide configurée (disaster.rounds)";
        }

        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        World world = plugin.getServer().getWorld(arena.getWorld());
        if (world == null) {
            return "monde de l'arène introuvable : " + arena.getWorld();
        }

        activePlayers.clear();
        activePlayers.addAll(lobbyPlayers);
        spectators.clear();
        previousModes.clear();
        lastDash.clear();
        eliminationOrder.clear();
        meteorEntities.clear();
        activeDisasters.clear();
        waveNumber = 0;
        intensity = 1;
        tickCounter = 0;
        running = true;

        if (plugin.getConfig().getBoolean("disaster.regen-after-game", true)) {
            long maxBlocks = plugin.getConfig().getLong("disaster.max-regen-blocks", 2_000_000L);
            snapshot = ZoneSnapshot.capture(world, arena, maxBlocks);
            if (snapshot == null) {
                plugin.getLogger().warning("[Disaster] Zone trop grande (> " + maxBlocks
                        + " blocs) : la régénération est désactivée pour cette partie.");
            }
        } else {
            snapshot = null;
        }

        int participation = plugin.getConfig().getInt("disaster.points.participation", 10);
        boolean dashEnabled = plugin.getConfig().getBoolean("disaster.dash.enabled", true);
        for (UUID uuid : activePlayers) {
            plugin.getScoreManager().addPoints(uuid, participation);
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) {
                scoreboard.attach(p);
                previousModes.putIfAbsent(uuid, p.getGameMode());
                p.setGameMode(GameMode.SURVIVAL);
                p.setHealth(maxHealth(p));
                p.setFoodLevel(20);
                // Evite le kick "Flying is not enabled" quand la tornade soulève le joueur.
                p.setAllowFlight(true);
                p.setFlying(false);
                if (dashEnabled) {
                    p.getInventory().addItem(createDashItem());
                }
            }
        }

        broadcastIntro();

        phase = Phase.INTERMISSION;
        secondsLeft = Math.max(1, plugin.getConfig().getInt("disaster.intermission-seconds", 5));

        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!running) {
                    cancel();
                    return;
                }
                tick();
            }
        }.runTaskTimer(plugin, 1L, 1L);

        updateScoreboards();
        return null;
    }

    private List<String> resolveRoundSequence() {
        List<String> configured = plugin.getConfig().getStringList("disaster.rounds");
        if (configured.isEmpty()) {
            configured = List.of("meteor", "tornado", "lightning");
        }
        List<String> valid = new ArrayList<>();
        for (String d : configured) {
            String key = d.toLowerCase(Locale.ROOT).trim();
            if (key.equals("meteor") || key.equals("tornado") || key.equals("lightning")) {
                valid.add(key);
            } else {
                plugin.getLogger().warning("[Disaster] Catastrophe inconnue ignorée : '" + d + "'");
            }
        }
        return valid;
    }

    private void tick() {
        tickCounter++;

        if (tickCounter % 20 == 0) {
            secondTick();
            if (!running) return;
        }

        if (phase == Phase.DISASTER) {
            for (String d : activeDisasters) {
                switch (d) {
                    case "meteor" -> meteorTick();
                    case "tornado" -> tornadoTick();
                    case "lightning" -> lightningTick();
                    default -> { }
                }
            }
        }

        checkOutOfBounds();
    }

    private void secondTick() {
        secondsLeft--;
        if (phase == Phase.INTERMISSION) {
            if (secondsLeft <= 0) {
                beginNextWave();
            } else if (secondsLeft <= 3) {
                broadcastTitle("§e" + secondsLeft, "§7La tempête s'intensifie...");
            }
        } else {
            if (secondsLeft <= 0) {
                endCurrentWave();
            }
        }
        updateScoreboards();
    }

    private void beginNextWave() {
        waveNumber++;
        int available = roundSequence.size();
        int activeCount = Math.min(waveNumber, available);
        activeDisasters.clear();
        for (int i = 0; i < activeCount; i++) {
            activeDisasters.add(roundSequence.get(i));
        }
        intensity = Math.max(1, waveNumber - available + 1);

        phase = Phase.DISASTER;
        secondsLeft = Math.max(5, plugin.getConfig().getInt("disaster.round-duration-seconds", 45));
        vortices.clear();

        broadcast("§8§m                                        ");
        broadcast("§6§lVAGUE " + waveNumber + " §7— " + describeActive());
        if (waveNumber > available) {
            broadcast("§c§l⚠ INTENSITÉ x" + intensity + " §7— ça va faire mal.");
        }
        broadcast("§8§m                                        ");
        broadcastTitle("§6§lVAGUE " + waveNumber, describeActive());
        broadcastSound(Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f);
    }

    private void endCurrentWave() {
        clearMeteors();
        clearTornadoDebris();
        phase = Phase.INTERMISSION;
        secondsLeft = Math.max(1, plugin.getConfig().getInt("disaster.intermission-seconds", 5));
        broadcast("§a[Disaster] §fAccalmie... la prochaine vague sera pire.");
        broadcastSound(Sound.BLOCK_NOTE_BLOCK_PLING, 1.5f);
    }

    private void meteorTick() {
        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        World world = arena == null ? null : plugin.getServer().getWorld(arena.getWorld());
        if (world == null) return;

        detonateLandedMeteors(world);

        for (TNTPrimed tnt : world.getEntitiesByClass(TNTPrimed.class)) {
            if (!meteorEntities.contains(tnt.getUniqueId())) continue;
            world.spawnParticle(Particle.FLAME, tnt.getLocation(), 6, 0.15, 0.15, 0.15, 0.01);
            world.spawnParticle(Particle.LARGE_SMOKE, tnt.getLocation(), 3, 0.1, 0.1, 0.1, 0.0);
        }

        int baseInterval = Math.max(1, plugin.getConfig().getInt("disaster.meteor.interval-ticks", 8));
        int interval = Math.max(2, baseInterval - (intensity - 1) * 2);
        if (tickCounter % interval != 0) return;

        int baseCount = Math.max(1, plugin.getConfig().getInt("disaster.meteor.count-per-wave", 4));
        int count = Math.min(baseCount + (intensity - 1) * 2, baseCount + 24);
        float power = (float) plugin.getConfig().getDouble("disaster.meteor.power", 4.0);

        int fuse = Math.max(60, plugin.getConfig().getInt("disaster.meteor.fuse-ticks", 100));
        double spawnHeight = plugin.getConfig().getDouble("disaster.meteor.spawn-height", 22.0);
        boolean fire = plugin.getConfig().getBoolean("disaster.meteor.set-fire", true);

        for (int i = 0; i < count; i++) {
            double x = arena.getMinX() + random.nextDouble() * (arena.getMaxX() - arena.getMinX());
            double z = arena.getMinZ() + random.nextDouble() * (arena.getMaxZ() - arena.getMinZ());
            double y = arena.getMaxY() + spawnHeight;
            Location loc = new Location(world, x, y, z);

            TNTPrimed tnt = world.spawn(loc, TNTPrimed.class);
            tnt.setFuseTicks(fuse);
            tnt.setYield(power);
            tnt.setIsIncendiary(fire);
            tnt.setVelocity(new Vector(0, -1.6, 0));
            meteorEntities.add(tnt.getUniqueId());

            world.spawnParticle(Particle.FLAME, loc, 20, 0.3, 0.3, 0.3, 0.02);
            world.spawnParticle(Particle.LARGE_SMOKE, loc, 10, 0.3, 0.3, 0.3, 0.01);
        }
        world.playSound(world.getSpawnLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 0.6f);
    }

    private void detonateLandedMeteors(World world) {
        for (TNTPrimed tnt : world.getEntitiesByClass(TNTPrimed.class)) {
            if (!meteorEntities.contains(tnt.getUniqueId())) continue;
            if (tnt.getFuseTicks() <= 1) continue;
            Location l = tnt.getLocation();
            boolean solidBelow = world.getBlockAt(l.getBlockX(),
                    (int) Math.floor(l.getY() - 0.1), l.getBlockZ()).getType().isSolid();
            if (tnt.isOnGround() || solidBelow) {
                tnt.setFuseTicks(1);
            }
        }
    }

    public void handleMeteorExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        UUID id = event.getEntity().getUniqueId();
        if (!meteorEntities.remove(id)) return;
        event.setYield(0f);
        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        if (arena == null) return;

        event.blockList().removeIf(block -> !arena.contains(block.getLocation()));
    }

    public boolean isMeteor(UUID entityId) {
        return meteorEntities.contains(entityId);
    }

    private void clearMeteors() {
        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        World world = arena == null ? null : plugin.getServer().getWorld(arena.getWorld());
        if (world != null) {
            for (TNTPrimed tnt : world.getEntitiesByClass(TNTPrimed.class)) {
                if (meteorEntities.contains(tnt.getUniqueId())) {
                    tnt.remove();
                }
            }
        }
        meteorEntities.clear();
    }

    /** Les tornades aspirent des blocs du sol (visuel, sans casser la map) et les font tournoyer avec elles. */
    private void updateTornadoDebris(World world, Zone arena) {
        if (vortices.isEmpty()) return;

        // Faire tourbillonner les débris existants autour de la tornade la plus proche.
        Iterator<FallingBlock> it = tornadoDebris.iterator();
        while (it.hasNext()) {
            FallingBlock fb = it.next();
            if (fb == null || fb.isDead() || !fb.isValid() || fb.getTicksLived() > 45) {
                if (fb != null) fb.remove();
                it.remove();
                continue;
            }
            Vortex v = nearestVortex(fb.getLocation());
            if (v == null) continue;
            double dx = v.center.getX() - fb.getLocation().getX();
            double dz = v.center.getZ() - fb.getLocation().getZ();
            Vector toCenter = new Vector(dx, 0, dz);
            if (toCenter.lengthSquared() > 0) toCenter.normalize();
            Vector swirl = new Vector(-toCenter.getZ(), 0, toCenter.getX());
            Vector vel = toCenter.multiply(0.25).add(swirl.multiply(0.5));
            vel.setY(0.28);
            fb.setVelocity(vel);
        }

        // En aspirer de nouveaux autour de chaque tornade, façon terre arrachée au sol.
        int maxTotal = 12 * vortices.size();
        if (tornadoDebris.size() < maxTotal && tickCounter % 6 == 0) {
            for (Vortex v : vortices) {
                Material ground = world.getBlockAt(v.center.getBlockX(),
                        (int) Math.floor(arena.getMinY()), v.center.getBlockZ()).getType();
                if (ground.isAir() || !ground.isSolid()) {
                    ground = Material.DIRT;
                }
                for (int k = 0; k < 2; k++) {
                    double ang = random.nextDouble() * Math.PI * 2;
                    double r = random.nextDouble() * 2.0;
                    double x = v.center.getX() + Math.cos(ang) * r;
                    double z = v.center.getZ() + Math.sin(ang) * r;
                    Location loc = new Location(world, x, arena.getMinY() + 0.5, z);
                    FallingBlock fb = world.spawnFallingBlock(loc, ground.createBlockData());
                    fb.setDropItem(false);
                    fb.setHurtEntities(false);
                    fb.setCancelDrop(true);
                    fb.setPersistent(false);
                    fb.setVelocity(new Vector(0, 0.6, 0));
                    tornadoDebris.add(fb);
                }
            }
        }
    }

    private void clearTornadoDebris() {
        for (FallingBlock fb : tornadoDebris) {
            if (fb != null && !fb.isDead()) fb.remove();
        }
        tornadoDebris.clear();
    }

    private void tornadoTick() {
        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        World world = arena == null ? null : plugin.getServer().getWorld(arena.getWorld());
        if (world == null) return;

        double speed = plugin.getConfig().getDouble("disaster.tornado.move-speed", 0.35);

        // Nombre de tornades = intensité (fin de partie = plusieurs), plafonné par la config.
        int maxCount = Math.max(1, plugin.getConfig().getInt("disaster.tornado.max-count", 4));
        int desiredCount = Math.min(Math.max(1, intensity), maxCount);
        while (vortices.size() < desiredCount) {
            vortices.add(new Vortex(randomArenaPoint(arena, world)));
        }

        double height = Math.max(7.0, arena.getMaxY() - arena.getMinY() + 4);
        double baseY = arena.getMinY();

        for (Vortex v : vortices) {
            if (v.target == null || horizontalDistance(v.center, v.target) < 1.5) {
                v.target = randomArenaPoint(arena, world);
            }
            double mdx = v.target.getX() - v.center.getX();
            double mdz = v.target.getZ() - v.center.getZ();
            double md = Math.sqrt(mdx * mdx + mdz * mdz);
            if (md > 0.001) {
                v.center.add((mdx / md) * speed, 0, (mdz / md) * speed);
            }
            v.center.setX(clamp(v.center.getX(), arena.getMinX(), arena.getMaxX()));
            v.center.setZ(clamp(v.center.getZ(), arena.getMinZ(), arena.getMaxZ()));

            for (double h = 0; h < height; h += 0.5) {
                double r = 0.8 + h * 0.18;
                for (int k = 0; k < 3; k++) {
                    double ang = tickCounter * 0.5 + h * 0.8 + (Math.PI * 2.0 / 3.0) * k;
                    double px = v.center.getX() + Math.cos(ang) * r;
                    double pz = v.center.getZ() + Math.sin(ang) * r;
                    Location pLoc = new Location(world, px, baseY + h, pz);
                    world.spawnParticle(Particle.CLOUD, pLoc, 1, 0.02, 0.02, 0.02, 0.0);
                    if (((int) (h * 2)) % 3 == 0) {
                        world.spawnParticle(Particle.LARGE_SMOKE, pLoc, 1, 0.0, 0.0, 0.0, 0.0);
                    }
                }
            }
            if (tickCounter % 20 == 0) {
                world.playSound(v.center, Sound.ENTITY_PHANTOM_FLAP, 1.4f, 0.4f);
            }
        }

        updateTornadoDebris(world, arena);

        if (tickCounter % 3 != 0) return;

        double radius = plugin.getConfig().getDouble("disaster.tornado.radius", 7.0) + (intensity - 1);
        double pull = plugin.getConfig().getDouble("disaster.tornado.pull-strength", 0.6);
        double lift = plugin.getConfig().getDouble("disaster.tornado.lift-strength", 0.55);
        double dmgPerSecond = plugin.getConfig().getDouble("disaster.tornado.damage-per-second", 2.0);

        for (UUID uuid : new ArrayList<>(activePlayers)) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            Location pl = p.getLocation();
            Vortex near = nearestVortex(pl);
            if (near == null) continue;
            double dx = near.center.getX() - pl.getX();
            double dz = near.center.getZ() - pl.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > radius) continue;

            double factor = 1.0 - (dist / radius);
            Vector toCenter = new Vector(dx, 0, dz);
            if (toCenter.lengthSquared() > 0) toCenter.normalize();

            Vector swirl = new Vector(-toCenter.getZ(), 0, toCenter.getX());

            Vector velocity = toCenter.multiply(pull * factor)
                    .add(swirl.multiply(pull * factor));
            velocity.setY(lift * factor);
            p.setVelocity(p.getVelocity().multiply(0.3).add(velocity));

            p.damage(dmgPerSecond * 3.0 / 20.0);
        }
    }

    private Vortex nearestVortex(Location loc) {
        Vortex best = null;
        double bestSq = Double.MAX_VALUE;
        for (Vortex v : vortices) {
            double dx = v.center.getX() - loc.getX();
            double dz = v.center.getZ() - loc.getZ();
            double sq = dx * dx + dz * dz;
            if (sq < bestSq) {
                bestSq = sq;
                best = v;
            }
        }
        return best;
    }

    private void lightningTick() {
        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        World world = arena == null ? null : plugin.getServer().getWorld(arena.getWorld());
        if (world == null) return;

        int baseInterval = Math.max(10, plugin.getConfig().getInt("disaster.lightning.interval-ticks", 30));
        int interval = Math.max(8, baseInterval - (intensity - 1) * 3);
        if (tickCounter % interval != 0) return;

        int baseStrikes = Math.max(1, plugin.getConfig().getInt("disaster.lightning.strikes-per-wave", 2));
        int strikes = Math.min(baseStrikes + (intensity - 1), baseStrikes + 10);
        boolean targetPlayers = plugin.getConfig().getBoolean("disaster.lightning.target-players", false);

        for (int i = 0; i < strikes; i++) {
            Location loc;
            Player victim = targetPlayers && random.nextBoolean() ? randomActivePlayer() : null;
            if (victim != null) {
                double ox = (random.nextDouble() - 0.5) * 4.0;
                double oz = (random.nextDouble() - 0.5) * 4.0;
                loc = victim.getLocation().add(ox, 0, oz);
            } else {
                loc = randomArenaPoint(arena, world);
            }
            world.strikeLightning(loc);
        }
    }

    private Location randomArenaPoint(Zone arena, World world) {
        double x = arena.getMinX() + random.nextDouble() * (arena.getMaxX() - arena.getMinX());
        double z = arena.getMinZ() + random.nextDouble() * (arena.getMaxZ() - arena.getMinZ());
        return new Location(world, x, arena.getMinY(), z);
    }

    private double horizontalDistance(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private Player randomActivePlayer() {
        List<Player> online = new ArrayList<>();
        for (UUID uuid : activePlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) online.add(p);
        }
        return online.isEmpty() ? null : online.get(random.nextInt(online.size()));
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    public void handleDash(Player player) {
        if (!running) return;
        UUID uuid = player.getUniqueId();
        if (!activePlayers.contains(uuid)) return;
        if (!plugin.getConfig().getBoolean("disaster.dash.enabled", true)) return;

        long cooldownMs = Math.max(0, plugin.getConfig().getInt("disaster.dash.cooldown-seconds", 6)) * 1000L;
        long now = System.currentTimeMillis();
        Long last = lastDash.get(uuid);
        if (last != null && now - last < cooldownMs) {
            long remaining = (cooldownMs - (now - last) + 999) / 1000;
            player.sendActionBar(net.kyori.adventure.text.Component.text("§7Dash prêt dans §e" + remaining + "s"));
            return;
        }
        lastDash.put(uuid, now);

        double strength = plugin.getConfig().getDouble("disaster.dash.strength", 1.4);
        double up = plugin.getConfig().getDouble("disaster.dash.up", 0.35);
        Vector dir = player.getLocation().getDirection();
        dir.setY(0);
        if (dir.lengthSquared() > 0) dir.normalize();
        dir.multiply(strength).setY(up);
        player.setVelocity(dir);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BREEZE_SHOOT, 1f, 1.2f);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 15, 0.2, 0.1, 0.2, 0.05);
    }

    public void handleFatalDamage(Player player) {
        if (!running) return;
        if (!activePlayers.contains(player.getUniqueId())) return;
        player.setHealth(maxHealth(player));
        eliminate(player.getUniqueId(), "§c💥 §e" + player.getName() + " §fn'a pas survécu à la catastrophe !");
    }

    public boolean isActive(UUID uuid) {
        return running && activePlayers.contains(uuid);
    }

    private void checkOutOfBounds() {
        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        if (arena == null) return;
        double floor = arena.getMinY() - 4;
        for (UUID uuid : new ArrayList<>(activePlayers)) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            if (p.getLocation().getY() < floor) {
                eliminate(uuid, "§c💥 §e" + p.getName() + " §fa été emporté hors de l'arène !");
            }
        }
    }

    private void eliminate(UUID uuid, String broadcastMessage) {
        if (!activePlayers.remove(uuid)) return;
        spectators.add(uuid);
        eliminationOrder.add(uuid);
        plugin.getSessionManager().setState(uuid, PlayerSessionManager.SessionState.ELIMINATED);

        Player p = plugin.getServer().getPlayer(uuid);
        if (p != null) {
            clearDashItems(p);
            p.setFireTicks(0);
            p.setHealth(maxHealth(p));
            p.setFlying(false);
            p.setAllowFlight(false);
            sendToSpectator(p);
            p.sendMessage("§c[Disaster] §fTu es éliminé ! Direction la zone des spectateurs.");
        }
        broadcast(broadcastMessage);

        if (running && activePlayers.size() <= 1) {
            finishGame();
        }
    }

    @Override
    public void stop() {
        if (running || !lobbyPlayers.isEmpty() || !spectators.isEmpty()) {
            broadcast("§6[Disaster] §fPartie arrêtée par un administrateur.");
        }
        running = false;
        cleanupAndReset();
    }

    private void finishGame() {
        if (!running) return;
        running = false;

        if (activePlayers.isEmpty()) {
            broadcast("§c[Disaster] §fPersonne n'a survécu... aucune catastrophe n'a fait de quartier.");
        } else if (activePlayers.size() == 1) {
            Player wp = plugin.getServer().getPlayer(activePlayers.iterator().next());
            broadcast("§6🏆 [Disaster] §e" + (wp != null ? wp.getName() : "?") + " §fest le seul survivant, il gagne !");
        } else {
            broadcast("§6🏆 [Disaster] §e" + activePlayers.size() + " §fsurvivants ont tenu jusqu'au bout !");
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
        int winBonus = plugin.getConfig().getInt("disaster.points.win-bonus", 100);

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
        clearMeteors();
        clearTornadoDebris();

        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        World world = arena == null ? null : plugin.getServer().getWorld(arena.getWorld());
        if (world != null && arena != null) {

            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (arena.contains(item.getLocation())) item.remove();
            }
        }

        for (UUID uuid : new ArrayList<>(activePlayers)) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) {
                clearDashItems(p);
                p.setFireTicks(0);
                p.setFlying(false);
                p.setAllowFlight(false);
                scoreboard.detach(p);
                restoreGameMode(p);
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
                clearDashItems(p);
                scoreboard.detach(p);
                restoreGameMode(p);
                sendToHub(p);
            }
        }

        if (world != null) {
            int blocksPerTick = Math.max(500, plugin.getConfig().getInt("disaster.regen-blocks-per-tick", 20000));
            boolean regenerated = false;
            File baseline = baselineFile();
            if (baseline.exists()) {
                try {
                    ZoneSnapshot base = ZoneSnapshot.loadFromFile(baseline);
                    if (base != null) {
                        base.restoreProgressive(plugin, world, blocksPerTick);
                        regenerated = true;
                        plugin.getLogger().info("[Disaster] Régénération progressive depuis la sauvegarde...");
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[Disaster] Sauvegarde illisible : " + e.getMessage());
                }
            }
            if (!regenerated && snapshot != null) {
                snapshot.restoreProgressive(plugin, world, blocksPerTick);
                plugin.getLogger().info("[Disaster] Régénération progressive (instantané de lancement)...");
            }
        }
        snapshot = null;

        resetState();
    }

    private void resetState() {
        running = false;
        lobbyPlayers.clear();
        activePlayers.clear();
        spectators.clear();
        eliminationOrder.clear();
        previousModes.clear();
        lastDash.clear();
        meteorEntities.clear();
        roundSequence = new ArrayList<>();
        activeDisasters.clear();
        waveNumber = 0;
        intensity = 1;
        vortices.clear();
        phase = Phase.INTERMISSION;
    }

    private void cancelTasks() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    private void broadcastIntro() {
        broadcast("§8§m                                        ");
        broadcast("§6§l☄ DISASTER §7— survis aux catastrophes !");
        broadcast("§7• Chaque vague §cAJOUTE §7une catastrophe et §cs'intensifie§7.");
        broadcast("§7• Elles se §ecombinent §7(météorites + tornade + éclairs...) jusqu'au §6dernier survivant§7.");
        broadcast("§7• §b🪶 Plume §7: §eclic droit §7= petit dash pour esquiver.");
        broadcast("§7• Meurs = §céliminé§7. Escalade des catastrophes : §f" + describeSequence());
        broadcast("§7• Tes points dépendent de ta §ePLACE finale§7.");
        broadcast("§8§m                                        ");
    }

    private String describeSequence() {
        List<String> labels = new ArrayList<>();
        for (String d : roundSequence) labels.add(disasterLabel(d));
        return String.join(" §7→ ", labels);
    }

    private String describeActive() {
        if (activeDisasters.isEmpty()) return "§7—";
        List<String> labels = new ArrayList<>();
        for (String d : activeDisasters) labels.add(disasterLabel(d));
        return String.join(" §7+ ", labels);
    }

    private String activeIcons() {
        StringBuilder sb = new StringBuilder();
        for (String d : activeDisasters) {
            sb.append(switch (d) {
                case "meteor" -> "§c☄";
                case "tornado" -> "§b🌪";
                case "lightning" -> "§e⚡";
                default -> "";
            });
        }
        return sb.toString();
    }

    private String disasterLabel(String disaster) {
        return switch (disaster) {
            case "meteor" -> "§c☄ Météorites";
            case "tornado" -> "§b🌪 Tornade";
            case "lightning" -> "§e⚡ Éclairs";
            default -> "§7" + disaster;
        };
    }

    private void updateScoreboards() {
        if (!running) return;

        String phaseLine;
        if (phase == Phase.INTERMISSION) {
            phaseLine = "§7Vague suivante §f" + secondsLeft + "s";
        } else {
            phaseLine = activeIcons() + " §f" + secondsLeft + "s";
        }
        int alive = activePlayers.size();
        String mancheLine = "§7Vague §f" + waveNumber + (intensity > 1 ? " §c(x" + intensity + ")" : "");

        for (UUID uuid : activePlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            int points = plugin.getScoreManager().getPoints(uuid);
            scoreboard.render(p, SB_TITLE, List.of(
                    mancheLine,
                    phaseLine,
                    "§1",
                    "§7Survivants §f" + alive,
                    "§7Points §f" + points,
                    "§2",
                    "§a§lEN VIE ✔"
            ));
        }
        for (UUID uuid : spectators) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            int points = plugin.getScoreManager().getPoints(uuid);
            scoreboard.render(p, SB_TITLE, List.of(
                    "§c§lÉLIMINÉ",
                    "§1",
                    mancheLine,
                    "§7Survivants §f" + alive,
                    "§7Points §f" + points,
                    "§2",
                    "§7En attente de la fin"
            ));
        }
    }

    private ItemStack createDashItem() {
        ItemStack item = new ItemStack(Material.FEATHER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(DASH_ITEM_NAME);
            meta.setLore(List.of(
                    "§7Clic droit : §fpetit dash vers l'avant",
                    "§7Esquive les catastrophes !"
            ));
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isDashItem(ItemStack item) {
        if (item == null || item.getType() != Material.FEATHER) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() && DASH_ITEM_NAME.equals(meta.getDisplayName());
    }

    private void clearDashItems(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && isDashItem(contents[i])) {
                p.getInventory().setItem(i, null);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private double maxHealth(Player p) {

        return p.getMaxHealth();
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
        plugin.getSessionManager().clear(player.getUniqueId());
        if (plugin.getZoneManager().hasHub()) {
            player.teleport(plugin.getZoneManager().getHub());
        }
    }

    private void broadcast(String message) {
        for (UUID uuid : allInvolved()) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.sendMessage(message);
        }
    }

    private void broadcastTitle(String title, String subtitle) {
        String msg = (subtitle == null || subtitle.isEmpty()) ? title : title + " §7— " + subtitle;
        for (UUID uuid : allInvolved()) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.sendActionBar(net.kyori.adventure.text.Component.text(msg));
        }
    }

    private void broadcastSound(Sound sound, float pitch) {
        for (UUID uuid : allInvolved()) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.playSound(p.getLocation(), sound, 1f, pitch);
        }
    }

    private Set<UUID> allInvolved() {
        Set<UUID> all = new LinkedHashSet<>(lobbyPlayers);
        all.addAll(activePlayers);
        all.addAll(spectators);
        return all;
    }

    private File baselineFile() {
        return new File(plugin.getDataFolder(), "disaster-arena.dat");
    }

    public String saveBaseline() {
        if (running) return "arrête d'abord la partie en cours (/ninjaxx stop disaster)";
        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        if (arena == null) return "zone manquante : arena (/ninjaxx setdisasterzone)";
        World world = plugin.getServer().getWorld(arena.getWorld());
        if (world == null) return "monde de l'arène introuvable : " + arena.getWorld();
        long maxBlocks = plugin.getConfig().getLong("disaster.max-regen-blocks", 2_000_000L);
        ZoneSnapshot snap = ZoneSnapshot.capture(world, arena, maxBlocks);
        if (snap == null) return "arène trop grande (> " + maxBlocks + " blocs) ou invalide";
        try {
            snap.saveToFile(baselineFile());
        } catch (IOException e) {
            return "erreur d'écriture : " + e.getMessage();
        }
        return null;
    }

    public String regenFromBaseline() {
        if (running) return "arrête d'abord la partie en cours (/ninjaxx stop disaster)";
        File file = baselineFile();
        if (!file.exists()) return "aucune sauvegarde — fais d'abord /ninjaxx savedisastermap";
        ZoneSnapshot snap;
        try {
            snap = ZoneSnapshot.loadFromFile(file);
        } catch (Exception e) {
            return "erreur de lecture : " + e.getMessage();
        }
        if (snap == null) return "sauvegarde illisible";
        if (!snap.restore()) return "monde introuvable : " + snap.getWorldName();
        return null;
    }
}
