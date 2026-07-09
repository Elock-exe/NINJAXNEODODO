package com.multigame.managers;

import com.multigame.MultiGame;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class ScoreManager {

    private final MultiGame plugin;
    private final Map<UUID, Integer> scores = new LinkedHashMap<>();

    public ScoreManager(MultiGame plugin) {
        this.plugin = plugin;
        load();
    }

    public void addPoints(UUID uuid, int amount) {
        scores.merge(uuid, amount, Integer::sum);
        save();
    }

    public void addPointsBatch(Map<UUID, Integer> deltas) {
        if (deltas.isEmpty()) return;
        for (Map.Entry<UUID, Integer> entry : deltas.entrySet()) {
            scores.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
        save();
    }

    public int getPoints(UUID uuid) {
        return scores.getOrDefault(uuid, 0);
    }

    public int reset() {
        int count = scores.size();
        scores.clear();
        plugin.getConfig().set("scores", null);
        plugin.getConfig().createSection("scores");
        plugin.saveConfig();
        return count;
    }

    public int getRank(UUID uuid) {
        int myScore = getPoints(uuid);
        int higher = 0;
        for (int value : scores.values()) {
            if (value > myScore) higher++;
        }
        return higher + 1;
    }

    public List<Map.Entry<UUID, Integer>> getTopScores(int limit) {
        return scores.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(limit)
                .collect(Collectors.toList());
    }

    private void save() {
        ConfigurationSection sec = plugin.getConfig().createSection("scores");
        for (Map.Entry<UUID, Integer> entry : scores.entrySet()) {
            sec.set(entry.getKey().toString(), entry.getValue());
        }
        plugin.saveConfig();
    }

    private void load() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("scores");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                scores.put(uuid, sec.getInt(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
}
