package net.infnetwork.snowball.craftov.gui;

import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * The interface sound kit (tools/art/sounds.py, "ui/*"): one timbre for every menu, so opening a
 * screen, pressing a button, switching a tab, confirming, failing and being rewarded each have a
 * sound of their own that still sounds like the same device. Played to the player only.
 */
public enum UiSound {
    OPEN("craftov:ui.open", 0.6f),
    CLOSE("craftov:ui.close", 0.5f),
    CLICK("craftov:ui.click", 0.55f),
    TAB("craftov:ui.tab", 0.55f),
    CONFIRM("craftov:ui.confirm", 0.65f),
    ERROR("craftov:ui.error", 0.7f),
    REWARD("craftov:ui.reward", 0.75f),
    /** Raid alerts: an extract opening, the clock running low, the final seconds of a countdown. */
    EXTRACT_OPEN("craftov:extract.open", 0.9f),
    WARNING("craftov:raid.warning", 0.9f),
    FINAL("craftov:extract.final", 0.8f);

    private final String key;
    private final float volume;

    UiSound(String key, float volume) {
        this.key = key;
        this.volume = volume;
    }

    public void play(Player player) {
        play(player, 1f);
    }

    public void play(Player player, float pitch) {
        player.playSound(player, key, SoundCategory.MASTER, volume, pitch);
    }

    public String key() {
        return key;
    }
}
