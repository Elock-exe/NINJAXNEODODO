package com.ninjaxxgames.managers;

import com.ninjaxxgames.NinjaxxGames;

public class PluginStateManager {

    private final NinjaxxGames plugin;
    private boolean serverOn;

    public PluginStateManager(NinjaxxGames plugin) {
        this.plugin = plugin;
        String state = plugin.getConfig().getString("server-state", "OFF");
        this.serverOn = state.equalsIgnoreCase("ON");
    }

    public boolean isOn() {
        return serverOn;
    }

    public boolean isOff() {
        return !serverOn;
    }

    public void toggle() {
        setState(!serverOn);
    }

    public void setState(boolean on) {
        this.serverOn = on;
        plugin.getConfig().set("server-state", on ? "ON" : "OFF");
        plugin.saveConfig();
    }
}
