package com.multigame.managers;

import com.multigame.MultiGame;
import com.xxmicloxx.NoteBlockAPI.model.RepeatMode;
import com.xxmicloxx.NoteBlockAPI.model.Song;
import com.xxmicloxx.NoteBlockAPI.model.SoundCategory;
import com.xxmicloxx.NoteBlockAPI.songplayer.RadioSongPlayer;
import com.xxmicloxx.NoteBlockAPI.utils.NBSDecoder;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BackgroundMusicManager {

    private final MultiGame plugin;
    private BukkitTask task;

    private RadioSongPlayer hubSong;
    private RadioSongPlayer gameSong;

    private final Map<UUID, String> currentContext = new HashMap<>();

    public BackgroundMusicManager(MultiGame plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("music.enabled", true)) {
            return;
        }
        if (plugin.getServer().getPluginManager().getPlugin("NoteBlockAPI") == null) {
            plugin.getLogger().warning("[Musique] Plugin 'NoteBlockAPI' introuvable — musique de fond desactivee. "
                    + "Installe NoteBlockAPI (https://www.spigotmc.org/resources/noteblockapi.19287/) puis redemarre.");
            return;
        }

        hubSong = loadSong("hub");
        gameSong = loadSong("game");

        if (hubSong == null && gameSong == null) {
            plugin.getLogger().warning("[Musique] Aucune chanson .nbs chargee. Depose tes fichiers dans '"
                    + songsFolder().getPath() + "'.");
            return;
        }

        task = new BukkitRunnable() {
            @Override
            public void run() {
                updateListeners();
            }
        }.runTaskTimer(plugin, 40L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (hubSong != null) {
            hubSong.destroy();
            hubSong = null;
        }
        if (gameSong != null) {
            gameSong.destroy();
            gameSong = null;
        }
        currentContext.clear();
    }

    private RadioSongPlayer loadSong(String context) {
        String fileName = plugin.getConfig().getString("music." + context + ".file", context + ".nbs");
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        File file = new File(songsFolder(), fileName);
        if (!file.exists()) {
            plugin.getLogger().warning("[Musique] Fichier introuvable pour '" + context + "' : " + file.getPath());
            return null;
        }
        Song song = NBSDecoder.parse(file);
        if (song == null) {
            plugin.getLogger().warning("[Musique] Fichier .nbs illisible : " + file.getPath());
            return null;
        }
        RadioSongPlayer rsp = new RadioSongPlayer(song, SoundCategory.MUSIC);
        rsp.setAutoDestroy(false);
        rsp.setRepeatMode(RepeatMode.ALL);
        int vol = plugin.getConfig().getInt("music." + context + ".volume", 50);
        rsp.setVolume((byte) Math.max(0, Math.min(100, vol)));
        rsp.setPlaying(true);
        plugin.getLogger().info("[Musique] Chanson '" + context + "' chargee : " + fileName);
        return rsp;
    }

    private void updateListeners() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            String context = plugin.getSessionManager().getCurrentGame(uuid) != null ? "game" : "hub";
            if (context.equals(currentContext.get(uuid))) {
                continue;
            }
            currentContext.put(uuid, context);

            RadioSongPlayer want = context.equals("game") ? gameSong : hubSong;
            RadioSongPlayer other = context.equals("game") ? hubSong : gameSong;
            if (other != null) {
                other.removePlayer(p);
            }
            if (want != null) {
                want.addPlayer(p);
            }
        }
        currentContext.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
    }

    private File songsFolder() {
        File folder = new File(plugin.getDataFolder(), "songs");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return folder;
    }
}
