package com.ninjaxxgames.commands;

import com.ninjaxxgames.NinjaxxGames;
import com.ninjaxxgames.games.disaster.DisasterManager;
import com.ninjaxxgames.models.Zone;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

public class NinjaxxCommand implements CommandExecutor, TabCompleter {

    private final NinjaxxGames plugin;
    private static final List<String> SUBCOMMANDS = List.of(
            "toggle", "sethub", "wand", "status",
            "setliftzone", "setliftdestination", "setliftspawn", "enablelift", "disablelift", "deletelift",
            "setpreparationzone", "setgamezone", "setfinishline", "seteliminatedzone",
            "setarenazone", "setspectatorzone",
            "setpotatozone", "setpotatospectatorzone",
            "setprophuntzone",
            "setdisasterzone", "setdisasterspectatorzone",
            "savedisastermap", "regendisastermap",
            "start", "stop", "interlude", "leaderboard", "resetleaderboard",
            "lineplayers",
            "setrankspawn", "setclassementroom", "classement"
    );

    public NinjaxxCommand(NinjaxxGames plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e[NinjaxxGames] §fUtilise /ninjaxx <sous-commande>. Voir : " + String.join(", ", SUBCOMMANDS));
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "toggle" -> handleToggle(sender);
            case "sethub" -> handleSetHub(sender);
            case "wand" -> handleWand(sender);
            case "status" -> handleStatus(sender);
            case "setliftzone" -> handleSetLiftZone(sender, args);
            case "setliftdestination" -> handleSetLiftDestination(sender, args);
            case "setliftspawn" -> handleSetLiftSpawn(sender, args);
            case "enablelift" -> handleEnableLift(sender, args);
            case "disablelift" -> handleDisableLift(sender, args);
            case "deletelift" -> handleDeleteLift(sender, args);
            case "setpreparationzone" -> handleSetNamedZone(sender, "preparation");
            case "setgamezone" -> handleSetNamedZone(sender, "game");
            case "setfinishline" -> handleSetNamedZone(sender, "finish");
            case "seteliminatedzone" -> handleSetNamedZone(sender, "eliminated");
            case "setarenazone" -> handleSetNamedZone(sender, "crowngame", "arena");
            case "setspectatorzone" -> handleSetNamedZone(sender, "crowngame", "spectator");
            case "setpotatozone" -> handleSetNamedZone(sender, "hotpotato", "arena");
            case "setpotatospectatorzone" -> handleSetNamedZone(sender, "hotpotato", "spectator");
            case "setprophuntzone" -> handleSetNamedZone(sender, "prophunt", "arena");
            case "setdisasterzone" -> handleSetNamedZone(sender, "disaster", "arena");
            case "setdisasterspectatorzone" -> handleSetNamedZone(sender, "disaster", "spectator");
            case "savedisastermap" -> handleSaveDisasterMap(sender);
            case "regendisastermap" -> handleRegenDisasterMap(sender);
            case "start" -> handleStart(sender, args);
            case "stop" -> handleStop(sender, args);
            case "interlude" -> handleInterlude(sender);
            case "leaderboard" -> handleLeaderboard(sender);
            case "resetleaderboard" -> handleResetLeaderboard(sender);
            case "lineplayers" -> handleLinePlayers(sender, args);
            case "setrankspawn" -> handleSetRankSpawn(sender, args);
            case "setclassementroom" -> handleSetClassementRoom(sender);
            case "classement" -> handleClassement(sender);
            default -> sender.sendMessage("§c[NinjaxxGames] Sous-commande inconnue : " + sub);
        }
        return true;
    }

    private void handleToggle(CommandSender sender) {
        plugin.getStateManager().toggle();
        boolean on = plugin.getStateManager().isOn();
        sender.sendMessage((on ? "§a" : "§c") + "[NinjaxxGames] Serveur maintenant : " + (on ? "ON" : "OFF"));
    }

    private void handleSetHub(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        plugin.getZoneManager().setHub(player.getLocation());
        player.sendMessage("§a[NinjaxxGames] §fHub défini à ta position actuelle.");
    }

    private void handleWand(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        player.getInventory().addItem(plugin.getSelectionManager().createWand());
        player.sendMessage("§a[NinjaxxGames] §fBaguette de sélection reçue. Clic gauche = pos1, clic droit = pos2.");
    }

    private void handleSetLiftZone(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length < 2) {
            player.sendMessage("§c[NinjaxxGames] Usage : /ninjaxx setliftzone <id>");
            return;
        }
        Zone zone = requireSelection(player);
        if (zone == null) return;

        String id = args[1];
        plugin.getLiftManager().setZone(id, zone);
        player.sendMessage("§a[NinjaxxGames] §fZone de l'ascenseur '" + id + "' définie.");
    }

    private void handleSetLiftDestination(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c[NinjaxxGames] Usage : /ninjaxx setliftdestination <id> <destination>");
            return;
        }
        String id = args[1];
        String destination = args[2].toLowerCase();

        if (!plugin.getEventManager().exists(destination)) {
            String valid = String.join(", ", plugin.getEventManager().getAll().keySet());
            sender.sendMessage("§c[NinjaxxGames] Destination inconnue : '" + destination
                    + "'. Destinations valides : " + (valid.isEmpty() ? "(aucune enregistrée)" : valid));
            return;
        }

        plugin.getLiftManager().setDestination(id, destination);
        sender.sendMessage("§a[NinjaxxGames] §fDestination de l'ascenseur '" + id + "' définie : " + destination);
    }

    private void handleSetLiftSpawn(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length < 2) {
            player.sendMessage("§c[NinjaxxGames] Usage : /ninjaxx setliftspawn <id>");
            return;
        }
        String id = args[1];
        plugin.getLiftManager().setSpawn(id, player.getLocation());
        player.sendMessage("§a[NinjaxxGames] §fPoint d'arrivée de l'ascenseur '" + id
                + "' défini à ta position exacte (les joueurs seront TP ici précisément).");
    }

    private void handleEnableLift(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c[NinjaxxGames] Usage : /ninjaxx enablelift <id>");
            return;
        }
        String id = args[1];
        boolean ok = plugin.getLiftManager().enable(id);
        if (ok) {
            sender.sendMessage("§a[NinjaxxGames] §fAscenseur '" + id + "' activé.");
        } else {
            sender.sendMessage("§c[NinjaxxGames] Impossible d'activer '" + id + "' : zone et/ou destination manquantes.");
        }
    }

    private void handleDisableLift(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c[NinjaxxGames] Usage : /ninjaxx disablelift <id>");
            return;
        }
        String id = args[1];
        plugin.getLiftManager().disable(id);
        sender.sendMessage("§a[NinjaxxGames] §fAscenseur '" + id + "' désactivé.");
    }

    private void handleSetNamedZone(CommandSender sender, String key) {
        handleSetNamedZone(sender, "squidgame", key);
    }

    private void handleSetNamedZone(CommandSender sender, String gameId, String key) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        Zone zone = requireSelection(player);
        if (zone == null) return;
        plugin.getZoneManager().setZone(gameId, key, zone);
        player.sendMessage("§a[NinjaxxGames] §fZone '" + key + "' (" + gameId + ") définie.");
    }

    private void handleSaveDisasterMap(CommandSender sender) {
        if (!(plugin.getEventManager().get(DisasterManager.ID) instanceof DisasterManager d)) {
            sender.sendMessage("§c[NinjaxxGames] Mode disaster indisponible.");
            return;
        }
        String err = d.saveBaseline();
        if (err == null) {
            sender.sendMessage("§a[NinjaxxGames] §fÉtat propre de l'arène Disaster sauvegardé. Restaure-le quand tu veux avec /ninjaxx regendisastermap.");
        } else {
            sender.sendMessage("§c[NinjaxxGames] Échec : " + err);
        }
    }

    private void handleRegenDisasterMap(CommandSender sender) {
        if (!(plugin.getEventManager().get(DisasterManager.ID) instanceof DisasterManager d)) {
            sender.sendMessage("§c[NinjaxxGames] Mode disaster indisponible.");
            return;
        }
        String err = d.regenFromBaseline();
        if (err == null) {
            sender.sendMessage("§a[NinjaxxGames] §fArène Disaster régénérée.");
        } else {
            sender.sendMessage("§c[NinjaxxGames] Échec : " + err);
        }
    }

    private void handleStart(CommandSender sender, String[] args) {
        if (args.length < 2) {
            listGames(sender);
            return;
        }
        String gameId = args[1].toLowerCase();
        var result = plugin.getGameManager().start(gameId);
        if (result.success()) {
            sender.sendMessage("§a[NinjaxxGames] §fMode '" + gameId + "' démarré.");
        } else {
            sender.sendMessage("§c[NinjaxxGames] Impossible de démarrer '" + gameId + "' : " + result.failureReason());
        }
    }

    private void handleDeleteLift(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c[NinjaxxGames] Usage : /ninjaxx deletelift <id>");
            return;
        }
        String id = args[1];
        boolean removed = plugin.getLiftManager().delete(id);
        if (removed) {
            sender.sendMessage("§a[NinjaxxGames] §fAscenseur '" + id + "' supprimé.");
        } else {
            sender.sendMessage("§c[NinjaxxGames] Aucun ascenseur avec l'id '" + id + "'.");
        }
    }

    private void handleStatus(CommandSender sender) {
        boolean on = plugin.getStateManager().isOn();
        sender.sendMessage("§6=== Statut NinjaxxGames ===");
        sender.sendMessage((on ? "§a" : "§c") + "État serveur : " + (on ? "ON" : "OFF"));
        sender.sendMessage(plugin.getZoneManager().hasHub() ? "§aHub : défini" : "§cHub : non défini");

        sender.sendMessage("§eZones Squid Game :");
        for (String key : List.of("preparation", "game", "finish", "eliminated")) {
            boolean has = plugin.getZoneManager().hasZone("squidgame", key);
            sender.sendMessage((has ? "  §a✔ " : "  §c✘ ") + key);
        }

        sender.sendMessage("§eZones Crown Game :");
        for (String key : List.of("arena", "spectator")) {
            boolean has = plugin.getZoneManager().hasZone("crowngame", key);
            sender.sendMessage((has ? "  §a✔ " : "  §c✘ ") + key);
        }

        sender.sendMessage("§eZones Patate Chaude :");
        for (String key : List.of("arena", "spectator")) {
            boolean has = plugin.getZoneManager().hasZone("hotpotato", key);
            sender.sendMessage((has ? "  §a✔ " : "  §c✘ ") + key);
        }

        sender.sendMessage("§eZone Prop Hunt :");
        boolean hasPh = plugin.getZoneManager().hasZone("prophunt", "arena");
        sender.sendMessage((hasPh ? "  §a✔ " : "  §c✘ ") + "arena");

        sender.sendMessage("§eZones Disaster :");
        for (String key : List.of("arena", "spectator")) {
            boolean has = plugin.getZoneManager().hasZone("disaster", key);
            sender.sendMessage((has ? "  §a✔ " : "  §c✘ ") + key);
        }

        var lifts = plugin.getLiftManager().getAll();
        sender.sendMessage("§eAscenseurs (" + lifts.size() + ") :");
        if (lifts.isEmpty()) {
            sender.sendMessage("  §7Aucun ascenseur configuré.");
        }
        for (var lift : lifts.values()) {
            String zoneInfo = lift.getZone() == null ? "§cpas de zone" : "§7zone: " + lift.getZone().getWorld();
            String destInfo = lift.getDestination() == null ? "§cpas de destination"
                    : (plugin.getEventManager().exists(lift.getDestination())
                        ? "§7dest: " + lift.getDestination()
                        : "§cdest invalide: " + lift.getDestination());
            String enabledInfo = lift.isEnabled() ? "§aON" : "§7OFF";
            String spawnInfo = lift.getSpawn() == null ? "§7arrivée: centre zone" : "§aarrivée: point exact";
            sender.sendMessage("  §f- " + lift.getId() + " [" + enabledInfo + "§f] " + zoneInfo + " §f| " + destInfo + " §f| " + spawnInfo);
        }

        if (sender instanceof Player player) {
            var overlapping = plugin.getLiftManager().findAllOverlapping(player);
            if (overlapping.size() > 1) {
                sender.sendMessage("§c⚠ Tu es actuellement dans " + overlapping.size()
                        + " zones d'ascenseur superposées : "
                        + overlapping.stream().map(l -> l.getId()).reduce((a, b) -> a + ", " + b).orElse(""));
                sender.sendMessage("§c  → Supprime les doublons avec /ninjaxx deletelift <id>");
            } else if (overlapping.size() == 1) {
                sender.sendMessage("§7Tu es actuellement dans la zone de l'ascenseur : " + overlapping.get(0).getId());
            }
        }
    }

    private void listGames(CommandSender sender) {
        var games = plugin.getEventManager().getAll();
        sender.sendMessage("§6=== Jeux disponibles ===");
        if (games.isEmpty()) {
            sender.sendMessage("§7Aucun jeu enregistré.");
            return;
        }
        for (var game : games.values()) {
            String state = game.isRunning() ? "§aEN COURS" : "§7prêt";
            sender.sendMessage("§e- " + game.getId() + " §7(" + game.getDisplayName() + ") §8[" + state + "§8]");
        }
        sender.sendMessage("§7Usage : §f/ninjaxx start <jeu> §7ou §f/ninjaxx stop <jeu>");
    }

    private void handleStop(CommandSender sender, String[] args) {
        if (args.length < 2) {
            listGames(sender);
            return;
        }
        String gameId = args[1].toLowerCase();
        boolean ok = plugin.getGameManager().stop(gameId);
        if (ok) {
            sender.sendMessage("§a[NinjaxxGames] §fMode '" + gameId + "' arrêté.");
        } else {
            sender.sendMessage("§c[NinjaxxGames] Mode inconnu : " + gameId);
        }
    }

    private void handleInterlude(CommandSender sender) {
        var interlude = plugin.getInterludeManager();
        if (interlude.isRunning()) {
            interlude.end();
            sender.sendMessage("§a[NinjaxxGames] §fInterlude arrêtée — épées retirées, points attribués.");
            return;
        }
        String error = interlude.start();
        if (error != null) {
            sender.sendMessage("§c[NinjaxxGames] Impossible de lancer l'interlude : " + error);
        } else {
            sender.sendMessage("§a[NinjaxxGames] §fInterlude PvP lancée ! Relance la commande pour l'arrêter avant la fin.");
        }
    }

    private void handleLeaderboard(CommandSender sender) {
        var top = plugin.getScoreManager().getTopScores(10);
        if (top.isEmpty()) {
            sender.sendMessage("§e[NinjaxxGames] §fAucun score enregistré pour le moment.");
            return;
        }
        sender.sendMessage("§6=== Classement NinjaxxGames ===");
        int rank = 1;
        for (var entry : top) {
            String name = Optional.ofNullable(plugin.getServer().getOfflinePlayer(entry.getKey()).getName())
                    .orElse(entry.getKey().toString());
            sender.sendMessage("§e#" + rank + " §f" + name + " §7- " + entry.getValue() + " pts");
            rank++;
        }
    }

    private void handleResetLeaderboard(CommandSender sender) {
        int count = plugin.getScoreManager().reset();
        sender.sendMessage("§a[NinjaxxGames] §fClassement réinitialisé — " + count + " joueur(s) remis à §e0§f.");
    }

    private void handleLinePlayers(CommandSender sender, String[] args) {
        Player leader = requirePlayer(sender);
        if (leader == null) return;

        Integer perRow = null;
        if (args.length >= 2) {
            try {
                perRow = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException e) {
                leader.sendMessage("§c[NinjaxxGames] Nombre par rangée invalide : '" + args[1] + "' (entier attendu).");
                return;
            }
        }

        leader.sendMessage(plugin.getFormationManager().toggle(leader, perRow));
    }

    private void handleSetRankSpawn(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length < 2) {
            player.sendMessage("§c[NinjaxxGames] Usage : /ninjaxx setrankspawn <1-"
                    + com.ninjaxxgames.managers.PodiumManager.MAX_RANKS + ">");
            return;
        }
        int rank;
        try {
            rank = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§c[NinjaxxGames] Rang invalide : '" + args[1] + "' (entier attendu).");
            return;
        }
        if (!plugin.getPodiumManager().setRankSpawn(rank, player.getLocation())) {
            player.sendMessage("§c[NinjaxxGames] Le rang doit être entre 1 et "
                    + com.ninjaxxgames.managers.PodiumManager.MAX_RANKS + ".");
            return;
        }
        player.sendMessage("§a[NinjaxxGames] §fSpawn du §eTOP " + rank
                + " §fdéfini à ta position exacte. §7(" + plugin.getPodiumManager().configuredRankCount()
                + " rang(s) configuré(s))");
    }

    private void handleSetClassementRoom(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        plugin.getPodiumManager().setRoom(player.getLocation());
        player.sendMessage("§a[NinjaxxGames] §fSalle de classement définie à ta position. "
                + "§7Les joueurs hors top " + com.ninjaxxgames.managers.PodiumManager.MAX_RANKS + " y seront téléportés.");
    }

    private void handleClassement(CommandSender sender) {
        String err = plugin.getPodiumManager().runCeremony();
        if (err != null) {
            sender.sendMessage("§c[NinjaxxGames] Impossible : " + err);
            return;
        }
        sender.sendMessage("§a[NinjaxxGames] §fCérémonie de classement lancée ! §e🎆");
    }

    private Player requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c[NinjaxxGames] Cette commande doit être exécutée par un joueur.");
            return null;
        }
        return player;
    }

    private Zone requireSelection(Player player) {
        if (!plugin.getSelectionManager().hasSelection(player)) {
            player.sendMessage("§c[NinjaxxGames] Fais une sélection avec la baguette (/ninjaxx wand) avant cette commande.");
            return null;
        }
        return plugin.getSelectionManager().buildZone(player);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setliftdestination")) {
            return plugin.getEventManager().getAll().keySet().stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("enablelift") || args[0].equalsIgnoreCase("disablelift")
                || args[0].equalsIgnoreCase("deletelift") || args[0].equalsIgnoreCase("setliftdestination")
                || args[0].equalsIgnoreCase("setliftspawn") || args[0].equalsIgnoreCase("setliftzone"))) {
            return new ArrayList<>(plugin.getLiftManager().getAll().keySet());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("stop"))) {
            return plugin.getEventManager().getAll().keySet().stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setrankspawn")) {
            List<String> ranks = new ArrayList<>();
            for (int i = 1; i <= com.ninjaxxgames.managers.PodiumManager.MAX_RANKS; i++) {
                ranks.add(String.valueOf(i));
            }
            return ranks.stream().filter(s -> s.startsWith(args[1])).toList();
        }
        return Collections.emptyList();
    }
}
