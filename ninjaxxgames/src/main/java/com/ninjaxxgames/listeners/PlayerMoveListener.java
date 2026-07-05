package com.ninjaxxgames.listeners;

import com.ninjaxxgames.NinjaxxGames;
import com.ninjaxxgames.games.squidgame.SquidGameManager;
import com.ninjaxxgames.models.Lift;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public class PlayerMoveListener implements Listener {

    private final NinjaxxGames plugin;

    public PlayerMoveListener(NinjaxxGames plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        if (event.getFrom().getX() == event.getTo().getX()
                && event.getFrom().getY() == event.getTo().getY()
                && event.getFrom().getZ() == event.getTo().getZ()) {
            return;
        }

        Player player = event.getPlayer();

        Object gameObj = plugin.getEventManager().get(SquidGameManager.ID);
        if (gameObj instanceof SquidGameManager squidGame) {
            squidGame.onPlayerMove(event);
            if (event.isCancelled()) {
                return;
            }
        }

        if (plugin.getStateManager().isOff()) {
            return;
        }

        Lift lift = plugin.getLiftManager().findTriggeredLift(player);
        if (lift != null) {
            triggerLift(player, lift);
        }
    }

    private void triggerLift(Player player, Lift lift) {
        var game = plugin.getEventManager().get(lift.getDestination());
        if (game == null) {
            player.sendMessage("§c[NinjaxxGames] Destination inconnue : " + lift.getDestination());
            plugin.getLogger().warning("Ascenseur '" + lift.getId() + "' déclenché par " + player.getName()
                    + " mais destination invalide : '" + lift.getDestination() + "'");
            return;
        }
        game.addPlayer(player);

        if (lift.getSpawn() != null) {
            player.teleport(lift.getSpawn());
        }
    }
}
