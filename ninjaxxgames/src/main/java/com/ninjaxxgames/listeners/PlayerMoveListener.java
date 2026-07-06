package com.ninjaxxgames.listeners;

import com.ninjaxxgames.NinjaxxGames;
import com.ninjaxxgames.games.squidgame.SquidGameManager;
import com.ninjaxxgames.models.Lift;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.time.Duration;

public class PlayerMoveListener implements Listener {

    private final NinjaxxGames plugin;

    public PlayerMoveListener(NinjaxxGames plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;

        // Joueurs gelés par /ninjaxx lineplayers : position verrouillée, on n'exécute rien d'autre.
        if (plugin.getFormationManager().handleMove(event)) {
            return;
        }

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

        announceGame(player, game.getDisplayName());
    }

    /** Gros titre au centre de l'écran indiquant le mode de jeu rejoint. */
    private void announceGame(Player player, String gameName) {
        Title title = Title.title(
                LegacyComponentSerializer.legacySection().deserialize("§6§l" + gameName),
                LegacyComponentSerializer.legacySection().deserialize("§7Bienvenue — bonne chance !"),
                Title.Times.times(
                        Duration.ofMillis(250),
                        Duration.ofMillis(2500),
                        Duration.ofMillis(500)));
        player.showTitle(title);
        player.sendMessage("§6🛗 [Ascenseur] §fTu es arrivé dans le mode §e§l" + gameName + " §f! Bonne chance !");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.4f);
    }
}
