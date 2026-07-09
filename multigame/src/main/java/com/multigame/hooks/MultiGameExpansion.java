package com.multigame.hooks;

import com.multigame.MultiGame;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MultiGameExpansion extends PlaceholderExpansion {

    private final MultiGame plugin;

    public MultiGameExpansion(MultiGame plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "multigame";
    }

    @Override
    public String getAuthor() {
        return "MultiGame";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) return null;
        String p = params.toLowerCase();

        if (p.equals("points")) {
            return player == null ? "0"
                    : String.valueOf(plugin.getScoreManager().getPoints(player.getUniqueId()));
        }
        if (p.equals("rank")) {
            return player == null ? "-"
                    : String.valueOf(plugin.getScoreManager().getRank(player.getUniqueId()));
        }

        if (p.startsWith("top_")) {
            String[] parts = p.split("_");
            if (parts.length >= 3) {
                try {
                    int n = Integer.parseInt(parts[1]);
                    String field = parts[2];
                    if (n < 1) return "-";
                    List<Map.Entry<UUID, Integer>> top = plugin.getScoreManager().getTopScores(n);
                    if (top.size() < n) {
                        return field.equals("points") ? "0" : "-";
                    }
                    Map.Entry<UUID, Integer> entry = top.get(n - 1);
                    if (field.equals("points")) {
                        return String.valueOf(entry.getValue());
                    }
                    if (field.equals("name")) {
                        String name = plugin.getServer().getOfflinePlayer(entry.getKey()).getName();
                        return name != null ? name : "-";
                    }
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }

        return null;
    }
}
