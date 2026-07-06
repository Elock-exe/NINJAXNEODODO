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
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

public class DisasterManager implements MiniGame {

    public static final String ID = "disaster";
    private static final String SB_TITLE = "§6§l☄ DISASTER";
    private static final String DASH_ITEM_NAME = "§b🪶 Dash";
    private static final String SUPPLY_BUCKET_NAME = "§b💧 Seau d'eau §7(usage unique)";
    private static final String SUPPLY_POTION_NAME = "§d✚ Potion de soin";
    private static final String SUPPLY_SLIME_NAME = "§a✦ Bloc de slime";
    private static final String SUPPLY_STEAK_NAME = "§6✦ Steak";
    private static final String SUPPLY_GAPPLE_NAME = "§e✦ Pomme dorée";

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
    private final Set<UUID> anvilEntities = new HashSet<>();
    private final Set<UUID> zombieEntities = new HashSet<>();
    private final Set<UUID> supplyItems = new HashSet<>();
    private boolean acidWeatherActive = false;

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
    private final List<Debris> tornadoDebris = new ArrayList<>();

    private static final class Vortex {
        Location center;
        Location target;
        Vortex(Location center) { this.center = center; }
    }

    private static final class Debris {
        final BlockDisplay display;
        double angle;
        double radius;
        double height;
        int age;
        Debris(BlockDisplay display, double angle, double radius, double height) {
            this.display = display;
            this.angle = angle;
            this.radius = radius;
            this.height = height;
        }
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
        clearGameItems(player);
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
        anvilEntities.clear();
        zombieEntities.clear();
        supplyItems.clear();
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
            configured = List.of("meteor", "tornado", "lightning", "acidrain", "anvilrain", "zombies");
        }
        Set<String> known = Set.of("meteor", "tornado", "lightning", "acidrain", "anvilrain", "zombies");
        List<String> valid = new ArrayList<>();
        for (String d : configured) {
            String key = d.toLowerCase(Locale.ROOT).trim();
            if (known.contains(key)) {
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
                    case "acidrain" -> acidRainTick();
                    case "anvilrain" -> anvilRainTick();
                    case "zombies" -> zombieTick();
                    default -> { }
                }
            }
        }

        supplyTick();
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

        String intensityTag = waveNumber > available ? " §c§l⚠ x" + intensity : "";
        broadcast("§6§lVAGUE " + waveNumber + " §7— " + describeActive() + intensityTag);
        broadcastTitle("§6§lVAGUE " + waveNumber + intensityTag, describeActive());
        broadcastSound(Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f);
    }

    private void endCurrentWave() {
        clearMeteors();
        clearTornadoDebris();
        clearAnvils();
        clearZombies();
        stopAcidWeather();
        phase = Phase.INTERMISSION;
        secondsLeft = Math.max(1, plugin.getConfig().getInt("disaster.intermission-seconds", 5));
        broadcastTitle("§a§lACCALMIE", "§7La prochaine vague sera pire...");
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

        Location center = event.getLocation();
        World world = center.getWorld();
        double blast = plugin.getConfig().getDouble("disaster.meteor.power", 4.0) + 1.0;
        double blastSq = blast * blast;

        for (UUID itemId : new ArrayList<>(supplyItems)) {
            if (plugin.getServer().getEntity(itemId) instanceof Item item
                    && item.getWorld().equals(world)
                    && item.getLocation().distanceSquared(center) <= blastSq) {
                item.remove();
                supplyItems.remove(itemId);
            }
        }

        if (world != null) {
            for (FallingBlock fb : world.getEntitiesByClass(FallingBlock.class)) {
                if (!anvilEntities.contains(fb.getUniqueId())) continue;
                if (fb.getLocation().distanceSquared(center) > blastSq) continue;
                anvilEntities.remove(fb.getUniqueId());
                world.spawnParticle(Particle.BLOCK, fb.getLocation(), 20, 0.4, 0.4, 0.4,
                        Material.ANVIL.createBlockData());
                world.playSound(fb.getLocation(), Sound.BLOCK_ANVIL_BREAK, 0.8f, 1.1f);
                fb.remove();
            }
        }

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

    private void updateTornadoDebris(World world, Zone arena) {
        if (vortices.isEmpty()) {
            clearTornadoDebris();
            return;
        }

        double baseY = arena.getMinY();
        double maxHeight = Math.max(7.0, arena.getMaxY() - arena.getMinY() + 4);
        int lifetime = 160;

        Iterator<Debris> it = tornadoDebris.iterator();
        while (it.hasNext()) {
            Debris d = it.next();
            if (d.display == null || d.display.isDead() || !d.display.isValid() || d.age++ > lifetime) {
                if (d.display != null && !d.display.isDead()) d.display.remove();
                it.remove();
                continue;
            }
            Vortex v = nearestVortex(d.display.getLocation());
            if (v == null) continue;
            d.angle += 0.3;
            d.height = Math.min(maxHeight, d.height + 0.18);
            d.radius = 1.0 + (d.height / maxHeight) * 3.0;
            double px = v.center.getX() + Math.cos(d.angle) * d.radius - 0.5;
            double pz = v.center.getZ() + Math.sin(d.angle) * d.radius - 0.5;
            d.display.teleport(new Location(world, px, baseY + d.height, pz));
        }

        int maxTotal = 100 * vortices.size();
        if (tornadoDebris.size() < maxTotal) {
            for (Vortex v : vortices) {
                for (int k = 0; k < 8; k++) {
                    double angle = random.nextDouble() * Math.PI * 2;
                    double radius = 1.0 + random.nextDouble() * 1.5;
                    double startH = random.nextDouble() * 2.0;
                    double px = v.center.getX() + Math.cos(angle) * radius - 0.5;
                    double pz = v.center.getZ() + Math.sin(angle) * radius - 0.5;
                    Material ground = surfaceMaterialAt(world, arena,
                            (int) Math.floor(px + 0.5), (int) Math.floor(pz + 0.5));
                    Location loc = new Location(world, px, baseY + startH, pz);
                    BlockDisplay display = world.spawn(loc, BlockDisplay.class);
                    display.setBlock(ground.createBlockData());
                    display.setPersistent(false);
                    tornadoDebris.add(new Debris(display, angle, radius, startH));
                }
            }
        }
    }

    private Material surfaceMaterialAt(World world, Zone arena, int x, int z) {
        int yTop = Math.min(world.getHighestBlockYAt(x, z), (int) arena.getMaxY() - 1);
        for (int y = yTop; y >= (int) arena.getMinY(); y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (m.isAir() || !m.isSolid()) continue;
            if (m == Material.BEDROCK || m == Material.BARRIER) continue;
            return m;
        }
        return Material.DIRT;
    }

    private void clearTornadoDebris() {
        for (Debris d : tornadoDebris) {
            if (d.display != null && !d.display.isDead()) d.display.remove();
        }
        tornadoDebris.clear();
    }

    private void tornadoTick() {
        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        World world = arena == null ? null : plugin.getServer().getWorld(arena.getWorld());
        if (world == null) return;

        double speed = plugin.getConfig().getDouble("disaster.tornado.move-speed", 0.35);

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

            double funnelHeight = height * 2.0;
            for (double h = 0; h < funnelHeight; h += 0.9) {
                double r = 2.0 + h * 0.35;
                for (int k = 0; k < 3; k++) {
                    double ang = tickCounter * 0.5 + h * 0.6 + (Math.PI * 2.0 / 3.0) * k;
                    double px = v.center.getX() + Math.cos(ang) * r;
                    double pz = v.center.getZ() + Math.sin(ang) * r;
                    Location pLoc = new Location(world, px, baseY + h, pz);
                    world.spawnParticle(Particle.CLOUD, pLoc, 1, 0.2, 0.2, 0.2, 0.0);
                }
            }
            for (double h = 0; h < funnelHeight; h += 1.6) {
                Location core = new Location(world, v.center.getX(), baseY + h, v.center.getZ());
                world.spawnParticle(Particle.LARGE_SMOKE, core, 1, 0.4, 0.2, 0.4, 0.02);
            }
            if (tickCounter % 20 == 0) {
                world.playSound(v.center, Sound.ENTITY_PHANTOM_FLAP, 2.0f, 0.4f);
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
            p.setAllowFlight(true);
            p.setFlying(false);
            p.setFallDistance(0f);
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

    private void acidRainTick() {
        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        World world = arena == null ? null : plugin.getServer().getWorld(arena.getWorld());
        if (world == null) return;

        if (!acidWeatherActive || !world.hasStorm()) {
            world.setStorm(true);
            world.setWeatherDuration(20 * 600);
            acidWeatherActive = true;
        }
        if (tickCounter % 20 == 0) {
            for (UUID uuid : allInvolved()) {
                Player p = plugin.getServer().getPlayer(uuid);
                if (p != null) p.setPlayerWeather(WeatherType.DOWNFALL);
            }
        }

        if (tickCounter % 40 == 0) {
            world.playSound(new Location(world,
                    (arena.getMinX() + arena.getMaxX()) / 2, arena.getMaxY(),
                    (arena.getMinZ() + arena.getMaxZ()) / 2), Sound.WEATHER_RAIN, 1.2f, 1.3f);
        }

        int damageInterval = Math.max(1, (int) Math.round(
                plugin.getConfig().getDouble("disaster.acidrain.damage-interval-seconds", 2.0) * 20));
        if (tickCounter % damageInterval == 0) {
            double damage = plugin.getConfig().getDouble("disaster.acidrain.damage-amount", 1.0)
                    + (intensity - 1) * 0.5;
            for (UUID uuid : new ArrayList<>(activePlayers)) {
                Player p = plugin.getServer().getPlayer(uuid);
                if (p == null) continue;
                Location pl = p.getLocation();
                if (!arena.contains(pl)) continue;
                if (world.getHighestBlockYAt(pl) > pl.getBlockY()) continue;
                p.damage(damage);
                p.sendActionBar(net.kyori.adventure.text.Component.text("§a☔ Pluie acide ! §fAbrite-toi sous un toit !"));
                p.playSound(pl, Sound.BLOCK_LAVA_EXTINGUISH, 0.25f, 1.8f);
            }
        }

        int baseInterval = Math.max(10, plugin.getConfig().getInt("disaster.acidrain.dissolve-interval-ticks", 30));
        int interval = Math.max(10, baseInterval - (intensity - 1) * 4);
        if (tickCounter % interval != 0) return;

        int perCycle = Math.max(1, plugin.getConfig().getInt("disaster.acidrain.dissolve-per-cycle", 2)) + (intensity - 1);
        for (int i = 0; i < perCycle; i++) {
            int x = (int) Math.floor(arena.getMinX() + random.nextDouble() * (arena.getMaxX() - arena.getMinX()));
            int z = (int) Math.floor(arena.getMinZ() + random.nextDouble() * (arena.getMaxZ() - arena.getMinZ()));
            int yTop = Math.min(world.getHighestBlockYAt(x, z), (int) arena.getMaxY() - 1);
            for (int y = yTop; y >= (int) arena.getMinY(); y--) {
                Block block = world.getBlockAt(x, y, z);
                if (block.getType().isAir() || block.isLiquid()) continue;
                Location center = block.getLocation().add(0.5, 1.0, 0.5);
                world.playSound(center, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.6f);
                block.setType(Material.AIR, false);
                break;
            }
        }
    }

    private void stopAcidWeather() {
        if (!acidWeatherActive) return;
        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        World world = arena == null ? null : plugin.getServer().getWorld(arena.getWorld());
        if (world != null) {
            world.setStorm(false);
            world.setThundering(false);
        }
        for (UUID uuid : allInvolved()) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.resetPlayerWeather();
        }
        acidWeatherActive = false;
    }

    private void anvilRainTick() {
        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        World world = arena == null ? null : plugin.getServer().getWorld(arena.getWorld());
        if (world == null) return;

        impactLandedAnvils(world);

        int baseInterval = Math.max(1, plugin.getConfig().getInt("disaster.anvilrain.interval-ticks", 10));
        int interval = Math.max(2, baseInterval - (intensity - 1) * 2);
        if (tickCounter % interval != 0) return;

        int baseCount = Math.max(1, plugin.getConfig().getInt("disaster.anvilrain.count-per-wave", 6));
        int count = Math.min(baseCount + (intensity - 1) * 2, baseCount + 24);
        float damagePerBlock = (float) plugin.getConfig().getDouble("disaster.anvilrain.damage-per-block", 4.0);
        int maxDamage = plugin.getConfig().getInt("disaster.anvilrain.max-damage", 20);
        double spawnHeight = plugin.getConfig().getDouble("disaster.anvilrain.spawn-height", 22.0);
        double fallSpeed = plugin.getConfig().getDouble("disaster.anvilrain.fall-speed", 1.6);

        for (int i = 0; i < count; i++) {
            double x = arena.getMinX() + random.nextDouble() * (arena.getMaxX() - arena.getMinX());
            double z = arena.getMinZ() + random.nextDouble() * (arena.getMaxZ() - arena.getMinZ());
            double y = Math.min(arena.getMaxY() + spawnHeight, world.getMaxHeight() - 2.0);
            Location loc = new Location(world, x, y, z);

            FallingBlock anvil = world.spawnFallingBlock(loc, Material.ANVIL.createBlockData());
            anvil.setVelocity(new Vector(0, -fallSpeed, 0));
            anvil.setHurtEntities(true);
            anvil.setDamagePerBlock(damagePerBlock);
            anvil.setMaxDamage(maxDamage);
            anvil.setDropItem(false);
            anvilEntities.add(anvil.getUniqueId());
        }
        world.playSound(world.getSpawnLocation(), Sound.BLOCK_ANVIL_PLACE, 0.3f, 0.5f);
    }

    private void impactLandedAnvils(World world) {
        for (FallingBlock fb : world.getEntitiesByClass(FallingBlock.class)) {
            if (!anvilEntities.contains(fb.getUniqueId())) continue;
            Location l = fb.getLocation();
            boolean solidBelow = world.getBlockAt(l.getBlockX(),
                    (int) Math.floor(l.getY() - 0.1), l.getBlockZ()).getType().isSolid();
            if (fb.isOnGround() || solidBelow) {
                anvilImpact(fb, l);
            }
        }
    }

    private void anvilImpact(org.bukkit.entity.Entity anvil, Location impact) {
        if (!anvilEntities.remove(anvil.getUniqueId())) return;
        World world = impact.getWorld();
        if (world == null) {
            anvil.remove();
            return;
        }

        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        int radius = Math.max(0, plugin.getConfig().getInt("disaster.anvilrain.break-radius", 1));
        if (arena != null) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        Block b = world.getBlockAt(impact.getBlockX() + dx,
                                impact.getBlockY() + dy, impact.getBlockZ() + dz);
                        if (b.getType().isAir()) continue;
                        if (!arena.contains(b.getLocation().add(0.5, 0.5, 0.5))) continue;
                        b.setType(Material.AIR, false);
                    }
                }
            }
        }
        world.playSound(impact, Sound.BLOCK_ANVIL_LAND, 1f, 0.8f);
        world.spawnParticle(Particle.BLOCK, impact, 30, 0.5, 0.3, 0.5, Material.ANVIL.createBlockData());
        anvil.remove();
    }

    public void handleAnvilLand(org.bukkit.event.entity.EntityChangeBlockEvent event) {
        if (!anvilEntities.contains(event.getEntity().getUniqueId())) return;
        event.setCancelled(true);
        anvilImpact(event.getEntity(), event.getBlock().getLocation().add(0.5, 0.5, 0.5));
    }

    private void clearAnvils() {
        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        World world = arena == null ? null : plugin.getServer().getWorld(arena.getWorld());
        if (world != null) {
            for (FallingBlock fb : world.getEntitiesByClass(FallingBlock.class)) {
                if (anvilEntities.contains(fb.getUniqueId())) {
                    fb.remove();
                }
            }
        }
        anvilEntities.clear();
    }

    private void zombieTick() {
        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        World world = arena == null ? null : plugin.getServer().getWorld(arena.getWorld());
        if (world == null) return;

        List<Zombie> alive = new ArrayList<>();
        for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
            if (zombieEntities.contains(zombie.getUniqueId())) {
                alive.add(zombie);
            }
        }

        if (tickCounter % 20 == 0) {
            for (Zombie zombie : alive) {
                if (!arena.contains(zombie.getLocation())) {
                    zombie.remove();
                    zombieEntities.remove(zombie.getUniqueId());
                    continue;
                }
                if (!(zombie.getTarget() instanceof Player target) || !isActive(target.getUniqueId())) {
                    Player nearest = nearestActivePlayer(zombie.getLocation());
                    if (nearest != null) {
                        zombie.setTarget(nearest);
                    }
                }
            }
        }

        int baseInterval = Math.max(20, plugin.getConfig().getInt("disaster.zombies.spawn-interval-ticks", 30));
        int interval = Math.max(20, baseInterval - (intensity - 1) * 5);
        if (tickCounter % interval != 0) return;

        int maxAlive = Math.max(1, plugin.getConfig().getInt("disaster.zombies.max-alive", 18)) + (intensity - 1) * 3;
        int perSpawn = Math.max(1, plugin.getConfig().getInt("disaster.zombies.per-spawn", 4));
        int aliveCount = alive.size();

        for (int i = 0; i < perSpawn && aliveCount < maxAlive; i++) {
            Location loc = randomSurfacePoint(arena, world);
            if (loc == null) continue;

            Zombie zombie = world.spawn(loc, Zombie.class, z -> {
                z.setAdult();
                if (z.getEquipment() != null) {
                    z.getEquipment().setHelmet(new ItemStack(Material.IRON_HELMET));
                    z.getEquipment().setHelmetDropChance(0f);
                }
                z.setShouldBurnInDay(false);
                z.setCanPickupItems(false);
                z.setPersistent(true);
                z.setRemoveWhenFarAway(false);
            });
            zombieEntities.add(zombie.getUniqueId());
            aliveCount++;

            Player nearest = nearestActivePlayer(loc);
            if (nearest != null) {
                zombie.setTarget(nearest);
            }
            world.spawnParticle(Particle.LARGE_SMOKE, loc.clone().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.01);
            world.playSound(loc, Sound.ENTITY_ZOMBIE_AMBIENT, 1f, 0.9f);
        }
    }

    public boolean handleZombieDeath(UUID entityId) {
        return zombieEntities.remove(entityId);
    }

    public boolean isDisasterZombie(UUID entityId) {
        return zombieEntities.contains(entityId);
    }

    private void clearZombies() {
        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        World world = arena == null ? null : plugin.getServer().getWorld(arena.getWorld());
        if (world != null) {
            for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
                if (zombieEntities.contains(zombie.getUniqueId())) {
                    zombie.remove();
                }
            }
        }
        zombieEntities.clear();
    }

    private Player nearestActivePlayer(Location from) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (UUID uuid : activePlayers) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null || !p.getWorld().equals(from.getWorld())) continue;
            double d = p.getLocation().distanceSquared(from);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    private Location randomSurfacePoint(Zone arena, World world) {
        int x = (int) Math.floor(arena.getMinX() + random.nextDouble() * (arena.getMaxX() - arena.getMinX()));
        int z = (int) Math.floor(arena.getMinZ() + random.nextDouble() * (arena.getMaxZ() - arena.getMinZ()));
        double y = world.getHighestBlockYAt(x, z) + 1.0;
        if (y < arena.getMinY()) y = arena.getMinY() + 1;
        if (y >= arena.getMaxY()) return null;
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    private void supplyTick() {
        if (!plugin.getConfig().getBoolean("disaster.supply.enabled", true)) return;
        int intervalTicks = Math.max(1, plugin.getConfig().getInt("disaster.supply.interval-seconds", 12)) * 20;
        if (tickCounter % intervalTicks != 0) return;

        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        World world = arena == null ? null : plugin.getServer().getWorld(arena.getWorld());
        if (world == null) return;

        supplyItems.removeIf(id -> {
            var e = plugin.getServer().getEntity(id);
            return e == null || !e.isValid();
        });
        int maxOnGround = Math.max(1, plugin.getConfig().getInt("disaster.supply.max-on-ground", 12));
        if (supplyItems.size() >= maxOnGround) return;

        int perCycle = Math.max(1, plugin.getConfig().getInt("disaster.supply.per-cycle", 3));
        for (int i = 0; i < perCycle && supplyItems.size() < maxOnGround; i++) {
            Location loc = randomSurfacePoint(arena, world);
            if (loc == null) continue;

            Item drop = world.dropItem(loc.clone().add(0, 0.5, 0), randomSupplyItem());
            drop.setVelocity(new Vector(0, 0.15, 0));
            drop.setGlowing(true);
            supplyItems.add(drop.getUniqueId());

            world.spawnParticle(Particle.FIREWORK, loc, 20, 0.3, 0.4, 0.3, 0.05);
            world.playSound(loc, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.4f);
        }
    }

    private ItemStack randomSupplyItem() {
        return switch (random.nextInt(5)) {
            case 0 -> namedItem(new ItemStack(Material.WATER_BUCKET), SUPPLY_BUCKET_NAME,
                    "§7Pose de l'eau §f(consommé après usage)");
            case 1 -> healPotion();
            case 2 -> namedItem(new ItemStack(Material.SLIME_BLOCK), SUPPLY_SLIME_NAME,
                    "§7Pose-le : il devient un jump pad !");
            case 3 -> namedItem(new ItemStack(Material.GOLDEN_APPLE), SUPPLY_GAPPLE_NAME,
                    "§7Absorption + régénération");
            default -> namedItem(new ItemStack(Material.COOKED_BEEF, 2), SUPPLY_STEAK_NAME,
                    "§7Un petit encas de survie");
        };
    }

    private ItemStack healPotion() {
        ItemStack item = new ItemStack(Material.POTION);
        if (item.getItemMeta() instanceof PotionMeta meta) {
            meta.setBasePotionType(PotionType.STRONG_HEALING);
            meta.setDisplayName(SUPPLY_POTION_NAME);
            meta.setLore(List.of("§7Bois-la pour te soigner"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack namedItem(ItemStack item, String name, String lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(List.of(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isSupplyBucket(ItemStack item) {
        if (item == null || item.getType() != Material.WATER_BUCKET) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() && SUPPLY_BUCKET_NAME.equals(meta.getDisplayName());
    }

    public boolean isSupplyItem(ItemStack item) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return false;
        String name = meta.getDisplayName();
        return SUPPLY_BUCKET_NAME.equals(name) || SUPPLY_POTION_NAME.equals(name)
                || SUPPLY_SLIME_NAME.equals(name) || SUPPLY_STEAK_NAME.equals(name)
                || SUPPLY_GAPPLE_NAME.equals(name);
    }

    public boolean isInArena(Location loc) {
        Zone arena = plugin.getZoneManager().getZone(ID, "arena");
        return arena != null && arena.contains(loc);
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
            clearGameItems(p);
            p.setFireTicks(0);
            p.setHealth(maxHealth(p));
            p.setFlying(false);
            p.setAllowFlight(false);
            sendToSpectator(p);
            sendTitle(p, "§c§lÉLIMINÉ", "§7Direction la zone des spectateurs");
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
            broadcastTitle("§c§lFIN", "§7Personne n'a survécu...");
        } else if (activePlayers.size() == 1) {
            Player wp = plugin.getServer().getPlayer(activePlayers.iterator().next());
            String name = wp != null ? wp.getName() : "?";
            broadcast("§6🏆 [Disaster] §e" + name + " §fest le seul survivant, il gagne !");
            broadcastTitle("§6🏆 §e" + name, "§fseul survivant, il gagne !");
        } else {
            broadcast("§6🏆 [Disaster] §e" + activePlayers.size() + " §fsurvivants ont tenu jusqu'au bout !");
            broadcastTitle("§6🏆 §e" + activePlayers.size() + " survivants", "§fils ont tenu jusqu'au bout !");
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
        clearAnvils();
        clearZombies();
        stopAcidWeather();

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
                clearGameItems(p);
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
                clearGameItems(p);
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
        anvilEntities.clear();
        zombieEntities.clear();
        supplyItems.clear();
        acidWeatherActive = false;
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
        broadcast("§7• §6✦ Ravitaillements §7: des objets apparaissent sur la map (§beau§7, §dsoin§7, §aslime jump pad§7, §esteak§7, §epomme dorée§7).");
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
                case "acidrain" -> "§a☔";
                case "anvilrain" -> "§7⚒";
                case "zombies" -> "§2☠";
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
            case "acidrain" -> "§a☔ Pluie acide";
            case "anvilrain" -> "§7⚒ Pluie d'enclumes";
            case "zombies" -> "§2☠ Invasion de zombies";
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

    private void clearGameItems(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && (isDashItem(contents[i]) || isSupplyItem(contents[i]))) {
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
        plugin.sendToHub(player);
    }

    private void broadcast(String message) {
        for (UUID uuid : allInvolved()) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.sendMessage(message);
        }
    }

    private void broadcastTitle(String title, String subtitle) {
        Title t = buildTitle(title, subtitle);
        for (UUID uuid : allInvolved()) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.showTitle(t);
        }
    }

    private void sendTitle(Player p, String title, String subtitle) {
        p.showTitle(buildTitle(title, subtitle));
    }

    private Title buildTitle(String title, String subtitle) {
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
        return Title.title(
                legacy.deserialize(title == null ? "" : title),
                legacy.deserialize(subtitle == null ? "" : subtitle),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1600), Duration.ofMillis(400)));
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
