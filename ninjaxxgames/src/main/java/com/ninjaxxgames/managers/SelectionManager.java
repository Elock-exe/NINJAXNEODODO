package com.ninjaxxgames.managers;

import com.ninjaxxgames.models.Zone;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SelectionManager {

    public static final Material WAND_MATERIAL = Material.GOLDEN_AXE;

    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();

    public void setPos1(Player player, Location loc) {
        pos1.put(player.getUniqueId(), loc.clone());
    }

    public void setPos2(Player player, Location loc) {
        pos2.put(player.getUniqueId(), loc.clone());
    }

    public Location getPos1(Player player) {
        return pos1.get(player.getUniqueId());
    }

    public Location getPos2(Player player) {
        return pos2.get(player.getUniqueId());
    }

    public boolean hasSelection(Player player) {
        return pos1.containsKey(player.getUniqueId()) && pos2.containsKey(player.getUniqueId());
    }

    public Zone buildZone(Player player) {
        if (!hasSelection(player)) return null;
        return Zone.fromLocations(pos1.get(player.getUniqueId()), pos2.get(player.getUniqueId()));
    }

    public boolean isWand(ItemStack item) {
        if (item == null || item.getType() != WAND_MATERIAL) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName()
                && meta.getDisplayName().equals("§6Baguette NinjaxxGames");
    }

    public ItemStack createWand() {
        ItemStack item = new ItemStack(WAND_MATERIAL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§6Baguette NinjaxxGames");
        meta.setLore(java.util.List.of(
                "§7Clic gauche : position 1",
                "§7Clic droit : position 2"
        ));
        item.setItemMeta(meta);
        return item;
    }
}
