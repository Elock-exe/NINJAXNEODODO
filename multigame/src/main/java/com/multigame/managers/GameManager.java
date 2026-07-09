package com.multigame.managers;

import com.multigame.MultiGame;
import com.multigame.games.MiniGame;

public class GameManager {

    private final MultiGame plugin;

    public GameManager(MultiGame plugin) {
        this.plugin = plugin;
    }

    public StartResult start(String gameId) {
        if (plugin.getInterludeManager() != null && plugin.getInterludeManager().isRunning()) {
            return new StartResult(false, "une interlude PvP est en cours — attends la fin ou /multigame interlude pour l'arrêter");
        }
        MiniGame game = plugin.getEventManager().get(gameId);
        if (game == null) {
            return new StartResult(false, "mode inconnu : " + gameId);
        }
        String failureReason = game.start();
        if (failureReason == null) {
            return new StartResult(true, null);
        }
        return new StartResult(false, failureReason);
    }

    public record StartResult(boolean success, String failureReason) {}

    public boolean stop(String gameId) {
        MiniGame game = plugin.getEventManager().get(gameId);
        if (game == null) return false;
        game.stop();
        return true;
    }

    public void stopAll() {
        plugin.getEventManager().getAll().values().forEach(MiniGame::stop);
    }
}
