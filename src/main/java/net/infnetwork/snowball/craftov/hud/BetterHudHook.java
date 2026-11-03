package net.infnetwork.snowball.craftov.hud;

import org.bukkit.entity.Player;

/** Optional BetterHud integration; the no-op version is used when BetterHud is absent. */
public interface BetterHudHook {

    BetterHudHook NONE = new BetterHudHook() {
    };

    default boolean active() {
        return false;
    }

    default void popup(Player player, String popup) {
    }
}
