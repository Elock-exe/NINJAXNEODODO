package com.multigame.managers;

import com.multigame.games.MiniGame;

import java.util.HashMap;
import java.util.Map;

public class EventManager {

    private final Map<String, MiniGame> games = new HashMap<>();

    public void register(MiniGame game) {
        games.put(game.getId().toLowerCase(), game);
    }

    public MiniGame get(String id) {
        if (id == null) return null;
        return games.get(id.toLowerCase());
    }

    public boolean exists(String id) {
        return id != null && games.containsKey(id.toLowerCase());
    }

    public Map<String, MiniGame> getAll() {
        return games;
    }
}
