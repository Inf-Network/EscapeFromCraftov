package net.infnetwork.snowball.craftov.util;

import net.infnetwork.snowball.craftov.gui.Menu;
import net.infnetwork.snowball.craftov.gui.UiSound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One channel per kind of message. Short feedback goes to the action bar, which holds one line at
 * a time, so lines have a priority: critical (downed, revive, extraction countdowns) over results
 * (what a click or a key just did) over hints over status lines (the matchmaking timer). A line
 * stays up at least {@link #HOLD} ticks before one of the same or lower priority may replace it; a
 * later line waits its turn (only the newest one waits), a lower one is dropped once the hold is
 * over. Lines with a key (a countdown) replace their own earlier version at once. A status line is
 * sent again and again and never waits: anything else takes the bar from it at once, and while
 * something else is up it is simply skipped until its next update.
 * <p>
 * While a menu is open the action bar is hidden behind the window, so results also go into the
 * menu: the clicked button's tooltip gets a red (or green) line for two seconds, or the title does
 * when the click was in the player's own inventory ({@link Menu#flash}). Only the answer to the
 * click being handled goes there: a line that comes on its own while a menu happens to be open (an
 * injury, a reminder) stays on the action bar instead of sticking to a button it has nothing to do
 * with, and a result whose button is gone after the redraw (an item sold) moves to the title.
 * Errors always play the interface error sound. Nothing here writes to chat: chat is for things
 * that happen to a player without their doing (a sale, an insurance payout, a contract taken) and
 * for the raid record.
 */
public final class Notify {

    public enum Priority { STATUS, HINT, RESULT, CRITICAL }

    /** Minimum time on screen before a line of the same or lower priority may replace it. */
    public static final int HOLD = 30;

    private record Line(Priority priority, @Nullable String key, Component text, long at) {
    }

    private static final Map<UUID, Line> SHOWN = new HashMap<>();
    private static final Map<UUID, Line> WAITING = new HashMap<>();

    private Notify() {
    }

    /** Sends waiting lines once the one on screen has been up long enough. Main thread, every tick. */
    public static void start(Plugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (WAITING.isEmpty()) {
                return;
            }
            long now = Bukkit.getCurrentTick();
            WAITING.entrySet().removeIf(e -> {
                Player player = Bukkit.getPlayer(e.getKey());
                if (player == null) {
                    return true;
                }
                Line shown = SHOWN.get(e.getKey());
                if (shown != null && now - shown.at() < HOLD) {
                    return false;
                }
                show(player, e.getValue());
                return true;
            });
        }, 1L, 1L);
    }

    public static void forget(Player player) {
        SHOWN.remove(player.getUniqueId());
        WAITING.remove(player.getUniqueId());
    }

    /**
     * A standing display that the caller refreshes (the matchmaking timer, every second): the least
     * important line, which gives way to everything and comes back on its next refresh.
     */
    public static void status(Player player, String key, String miniMessage) {
        bar(player, Priority.STATUS, key, Text.mm(miniMessage));
    }

    /** A passing hint (how to do something, why nothing happened). */
    public static void hint(Player player, String miniMessage) {
        bar(player, Priority.HINT, null, Text.mm("<muted>" + miniMessage));
    }

    /** The neutral result of an action (shown in the menu, too, when one is open). */
    public static void info(Player player, String miniMessage) {
        result(player, miniMessage, null);
    }

    /** An action worked: in a menu the clicked button says so, otherwise the action bar. */
    public static void ok(Player player, String miniMessage) {
        result(player, "<ok>" + miniMessage, true);
    }

    /** An action failed: in a menu the clicked button flashes the reason in red; always the error sound. */
    public static void error(Player player, String miniMessage) {
        UiSound.ERROR.play(player);
        result(player, "<danger>" + miniMessage, false);
    }

    /**
     * A line that must be seen (downed, revive, an extraction countdown): it replaces anything else at
     * once. Lines with the same key replace each other, so a countdown can update every tick.
     */
    public static void critical(Player player, String key, String miniMessage) {
        bar(player, Priority.CRITICAL, key, Text.mm(miniMessage));
    }

    private static void result(Player player, String miniMessage, @Nullable Boolean good) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu && menu.viewer() == player) {
            menu.flash(miniMessage, good);
        }
        // The action bar gets it as well: it is what the player sees on closing the menu right after.
        bar(player, Priority.RESULT, null, Text.mm(miniMessage));
    }

    private static void bar(Player player, Priority priority, @Nullable String key, Component text) {
        UUID id = player.getUniqueId();
        long now = Bukkit.getCurrentTick();
        Line line = new Line(priority, key, text, now);
        Line shown = SHOWN.get(id);
        boolean free = shown == null || now - shown.at() >= HOLD || priority.compareTo(shown.priority()) > 0
                || key != null && key.equals(shown.key());
        if (free) {
            WAITING.remove(id);
            show(player, line);
            return;
        }
        // A critical line keeps the bar until it stops updating; nothing waits behind it for long.
        // A status line never waits: its next refresh comes soon enough.
        if (shown.priority() == Priority.CRITICAL && priority != Priority.CRITICAL || priority == Priority.STATUS) {
            return;
        }
        Line waiting = WAITING.get(id);
        if (waiting == null || priority.compareTo(waiting.priority()) >= 0) {
            WAITING.put(id, line);
        }
    }

    private static void show(Player player, Line line) {
        SHOWN.put(player.getUniqueId(), new Line(line.priority(), line.key(), line.text(), Bukkit.getCurrentTick()));
        player.sendActionBar(line.text());
    }
}
