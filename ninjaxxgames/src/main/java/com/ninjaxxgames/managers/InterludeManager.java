package com.ninjaxxgames.managers;

import com.ninjaxxgames.NinjaxxGames;
import com.ninjaxxgames.games.MiniGame;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.*;

public class InterludeManager {

    private static final String SWORD_NAME = "§6⚔ Épée d'interlude";

    private final NinjaxxGames plugin;
    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Map<UUID, Integer> kills = new HashMap<>();

    private boolean running = false;
    private BukkitTask tickTask;
    private int secondsLeft;

    public InterludeManager(NinjaxxGames plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isParticipant(UUID uuid) {
        return running && participants.contains(uuid);
    }

    public String start() {
        if (running) {
            return "une interlude est déjà en cours";
        }
        for (MiniGame game : plugin.getEventManager().getAll().values()) {
            if (game.isRunning()) {
                return "un mini-jeu est en cours (" + game.getId() + "), arrête-le d'abord";
            }
        }

        participants.clear();
        kills.clear();
        int points = pointsPerKill();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (plugin.getSessionManager().getCurrentGame(p.getUniqueId()) != null) continue;
            participants.add(p.getUniqueId());
            p.getInventory().addItem(createSword());
            sendTitle(p, "§4§l⚔ INTERLUDE", "§fChacun pour soi ! §e+" + points + " pts §fpar kill");
            p.sendMessage("§4⚔ [Interlude] §fPériode de chaos : tape tout le monde avec ton épée en bois !");
            p.sendMessage("§4⚔ [Interlude] §fChaque kill rapporte §e" + points + " points §fajoutés à la fin.");
            p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1f, 1f);
        }
        if (participants.isEmpty()) {
            return "aucun joueur disponible";
        }

        secondsLeft = Math.max(10, plugin.getConfig().getInt("interlude.duration-seconds", 120));
        running = true;
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        return null;
    }

    private void tick() {
        if (!running) return;
        secondsLeft--;
        if (secondsLeft <= 0) {
            end();
            return;
        }
        String time = String.format("%d:%02d", secondsLeft / 60, secondsLeft % 60);
        for (UUID uuid : participants) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            p.sendActionBar(Component.text("§4⚔ Interlude §f" + time
                    + " §7| §fTes kills : §e" + kills.getOrDefault(uuid, 0)));
            if (secondsLeft <= 5) {
                sendTitle(p, "§e" + secondsLeft, "§7Fin de l'interlude...");
            }
        }
    }

    public void handleFatalHit(Player victim, Player killer) {
        if (!running) return;
        kills.merge(killer.getUniqueId(), 1, Integer::sum);

        victim.setHealth(maxHealth(victim));
        victim.setFireTicks(0);
        victim.setVelocity(new Vector(0, 0, 0));
        if (plugin.getZoneManager().hasHub()) {
            victim.teleport(plugin.getZoneManager().getHub());
        }
        sendTitle(victim, "§c☠ Éliminé", "§7par §e" + killer.getName());
        victim.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1f, 1f);
        killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);

        broadcast("§4⚔ §e" + killer.getName() + " §fa tué §e" + victim.getName()
                + " §7(+" + pointsPerKill() + " pts à la fin)");
    }

    public void end() {
        if (!running) return;
        running = false;
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }

        int points = pointsPerKill();
        List<Map.Entry<UUID, Integer>> ranking = new ArrayList<>(kills.entrySet());
        ranking.sort(Map.Entry.<UUID, Integer>comparingByValue().reversed());

        for (Map.Entry<UUID, Integer> entry : ranking) {
            plugin.getScoreManager().addPoints(entry.getKey(), entry.getValue() * points);
        }

        String topLine;
        if (ranking.isEmpty()) {
            topLine = "§7Aucun kill... tout le monde s'aime.";
        } else {
            Player top = plugin.getServer().getPlayer(ranking.get(0).getKey());
            topLine = "§e" + (top != null ? top.getName() : "?") + " §f- " + ranking.get(0).getValue() + " kills";
        }

        for (UUID uuid : participants) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) continue;
            removeSword(p);
            p.setHealth(maxHealth(p));
            sendTitle(p, "§a§lINTERLUDE TERMINÉE", topLine);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
        }

        int rank = 1;
        for (Map.Entry<UUID, Integer> entry : ranking) {
            if (rank > 3) break;
            Player p = plugin.getServer().getPlayer(entry.getKey());
            String name = p != null ? p.getName() : entry.getKey().toString();
            broadcast("§4⚔ §f#" + rank + " §e" + name + " §7— " + entry.getValue()
                    + " kills §f(+" + entry.getValue() * points + " pts)");
            rank++;
        }

        participants.clear();
        kills.clear();
    }

    public void sanitize(Player player) {
        if (!running || !participants.contains(player.getUniqueId())) {
            removeSword(player);
        }
    }

    private int pointsPerKill() {
        return Math.max(1, plugin.getConfig().getInt("interlude.points-per-kill", 30));
    }

    private ItemStack createSword() {
        ItemStack item = new ItemStack(Material.WOODEN_SWORD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(SWORD_NAME);
            meta.setLore(List.of("§7Chaque kill = §e+" + pointsPerKill() + " points"));
            meta.setUnbreakable(true);
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void removeSword(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null) continue;
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName() && SWORD_NAME.equals(meta.getDisplayName())) {
                p.getInventory().setItem(i, null);
            }
        }
    }

    private void broadcast(String message) {
        for (UUID uuid : participants) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) p.sendMessage(message);
        }
    }

    private void sendTitle(Player p, String title, String subtitle) {
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
        p.showTitle(Title.title(
                legacy.deserialize(title == null ? "" : title),
                legacy.deserialize(subtitle == null ? "" : subtitle),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1600), Duration.ofMillis(400))));
    }

    @SuppressWarnings("deprecation")
    private double maxHealth(Player p) {
        return p.getMaxHealth();
    }
}
