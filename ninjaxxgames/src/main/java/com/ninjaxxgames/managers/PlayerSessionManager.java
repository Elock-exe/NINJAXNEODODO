package com.ninjaxxgames.managers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerSessionManager {

    public enum SessionState {
        HUB,
        IN_GAME,
        ELIMINATED,
        SPECTATOR
    }

    private final Map<UUID, SessionState> states = new HashMap<>();
    private final Map<UUID, String> currentGame = new HashMap<>();

    public void setState(UUID uuid, SessionState state) {
        states.put(uuid, state);
    }

    public SessionState getState(UUID uuid) {
        return states.getOrDefault(uuid, SessionState.HUB);
    }

    public void setCurrentGame(UUID uuid, String gameId) {
        currentGame.put(uuid, gameId);
    }

    public String getCurrentGame(UUID uuid) {
        return currentGame.get(uuid);
    }

    public void clear(UUID uuid) {
        states.remove(uuid);
        currentGame.remove(uuid);
    }

    public boolean isInGame(UUID uuid) {
        SessionState s = getState(uuid);
        return s == SessionState.IN_GAME;
    }

    public boolean isEliminated(UUID uuid) {
        return getState(uuid) == SessionState.ELIMINATED;
    }
}
