package com.multigame.games;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class GlowSidebar {

    private final ChatColor glowColor;
    private final String teamName;
    private final String objectiveName;
    private final boolean hideHolderNameTags;

    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    public GlowSidebar(ChatColor glowColor, String teamName, String objectiveName) {
        this(glowColor, teamName, objectiveName, false);
    }

    public GlowSidebar(ChatColor glowColor, String teamName, String objectiveName, boolean hideHolderNameTags) {
        this.glowColor = glowColor;
        this.teamName = teamName;
        this.objectiveName = objectiveName;
        this.hideHolderNameTags = hideHolderNameTags;
    }

    public void attach(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Team team = board.registerNewTeam(teamName);
        team.setColor(glowColor);
        if (hideHolderNameTags) {
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        }
        boards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
    }

    public void detach(Player player) {
        boards.remove(player.getUniqueId());
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    public void setHolders(Set<String> holderNames) {
        for (Scoreboard board : boards.values()) {
            Team team = board.getTeam(teamName);
            if (team == null) continue;
            for (String entry : new HashSet<>(team.getEntries())) {
                team.removeEntry(entry);
            }
            for (String name : holderNames) {
                team.addEntry(name);
            }
        }
    }

    public void render(Player player, String title, List<String> lines) {
        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null) return;

        Objective previous = board.getObjective(objectiveName);
        if (previous != null) {
            previous.unregister();
        }

        @SuppressWarnings("deprecation")
        Objective objective = board.registerNewObjective(objectiveName, "dummy", title);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        int score = lines.size();
        for (String line : lines) {
            objective.getScore(line).setScore(score--);
        }
    }
}
