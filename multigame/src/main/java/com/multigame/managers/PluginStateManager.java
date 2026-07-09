package com.multigame.managers;

import com.multigame.MultiGame;

public class PluginStateManager {

    private final MultiGame plugin;
    private boolean serverOn;

    public PluginStateManager(MultiGame plugin) {
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
