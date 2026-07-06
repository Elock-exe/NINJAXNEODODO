package com.ninjaxxgames.managers;

import com.ninjaxxgames.NinjaxxGames;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class FormationManager {

    private static final double SIDE_SPACING = 2.0;
    private static final double ROW_SPACING = 2.0;
    private static final double START_OFFSET = 3.0;

    private final NinjaxxGames plugin;
    private final Map<UUID, Location> frozen = new HashMap<>();

    public FormationManager(NinjaxxGames plugin) {
        this.plugin = plugin;
    }

    public boolean isFrozen(UUID uuid) {
        return frozen.containsKey(uuid);
    }

    public boolean handleMove(PlayerMoveEvent event) {
        Location locked = frozen.get(event.getPlayer().getUniqueId());
        if (locked == null) return false;

        Location to = event.getTo();
        if (to != null && (to.getX() != locked.getX() || to.getY() != locked.getY() || to.getZ() != locked.getZ())) {
            Location fixed = locked.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            event.setTo(fixed);
        }
        return true;
    }

    public String toggle(Player leader, Integer forcedPerRow) {
        if (!frozen.isEmpty()) {
            int released = frozen.size();
            frozen.clear();
            return "§a[NinjaxxGames] §f" + released + " joueur(s) libéré(s) — ils peuvent à nouveau bouger.";
        }

        List<Player> others = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (!p.equals(leader)) others.add(p);
        }
        if (others.isEmpty()) {
            return "§e[NinjaxxGames] §fAucun autre joueur en ligne à placer.";
        }

        int n = others.size();
        int perRow = forcedPerRow != null
                ? Math.max(1, forcedPerRow)
                : Math.max(1, (int) Math.ceil(Math.sqrt(n)));

        Location origin = leader.getLocation();
        Vector forward = origin.getDirection();
        forward.setY(0);
        if (forward.lengthSquared() < 1.0E-6) {
            forward = new Vector(0, 0, 1);
        }
        forward.normalize();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());

        float faceYaw = (float) (Math.toDegrees(Math.atan2(-forward.getX(), forward.getZ())) + 180.0);

        for (int i = 0; i < n; i++) {
            int row = i / perRow;
            int col = i % perRow;
            int inThisRow = Math.min(perRow, n - row * perRow);

            double side = (col - (inThisRow - 1) / 2.0) * SIDE_SPACING;
            double fwd = START_OFFSET + row * ROW_SPACING;

            Location target = origin.clone()
                    .add(forward.clone().multiply(fwd))
                    .add(right.clone().multiply(side));
            target.setYaw(faceYaw);
            target.setPitch(0f);

            Player p = others.get(i);
            p.teleport(target);
            frozen.put(p.getUniqueId(), target.clone());
        }

        int rows = (int) Math.ceil(n / (double) perRow);
        return "§a[NinjaxxGames] §f" + n + " joueur(s) placés en grille de §e" + perRow
                + " §fpar rangée (§e" + rows + " §frangée(s)) et §cgelés§f. §7Refais la commande pour les libérer.";
    }

    public void clear() {
        frozen.clear();
    }
}
