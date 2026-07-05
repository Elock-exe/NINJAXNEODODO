package com.ninjaxxgames.games;

import org.bukkit.entity.Player;

public interface MiniGame {

    String getId();

    String getDisplayName();

    void addPlayer(Player player);

    void removePlayer(Player player);

    String start();

    void stop();

    boolean isRunning();
}
