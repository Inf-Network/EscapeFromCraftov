package net.infnetwork.snowball.craftov.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The craftov:ui font (tools/art/build.py, build_screens): menu titles in the 10 px pixel font, which
 * stays sharp at every GUI scale, and the designed screen backgrounds, each one glyph that the title
 * draws behind the window.
 * <p>
 * A container title starts 8 px right of the window's left edge. A designed title moves the pen back
 * to the edge (shift characters), draws the background glyph, moves forward to where the title text
 * belongs and writes the text. The glyphs' positions and advances come from ui/screens.json, which the
 * build writes next to the font.
 */
public final class UiFont {

    public static final Key FONT = Key.key("craftov", "ui");
    /** Where vanilla starts a container title, from the window's left edge. */
    private static final int TITLE_X = 8;
    /** The text sits right of the amber tick that every window draws in front of it. */
    private static final int TEXT_PAD = 2;
    private static final char SHIFT_NEG = '';
    private static final char SHIFT_POS = '';

    private record Glyph(String ch, int advance, int left) {
    }

    private static final Map<String, Glyph> SCREENS = load();

    private UiFont() {
    }

    private static Map<String, Glyph> load() {
        Map<String, Glyph> out = new HashMap<>();
        try (InputStream in = UiFont.class.getResourceAsStream("/ui/screens.json")) {
            if (in == null) {
                return out;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String id : root.keySet()) {
                JsonObject o = root.getAsJsonObject(id);
                out.put(id, new Glyph(o.get("char").getAsString(), o.get("advance").getAsInt(), o.get("left").getAsInt()));
            }
        } catch (Exception ignored) {
            // A jar without the generated file still opens every menu, just without backgrounds.
        }
        return out;
    }

    /** Characters that move the pen by {@code px} (negative is left) without drawing anything. */
    public static String shift(int px) {
        StringBuilder sb = new StringBuilder();
        char base = px < 0 ? SHIFT_NEG : SHIFT_POS;
        int left = Math.abs(px);
        for (int bit = 9; bit >= 0; bit--) {
            while (left >= 1 << bit) {
                sb.append((char) (base + bit));
                left -= 1 << bit;
            }
        }
        return sb.toString();
    }

    /**
     * A black curtain over the whole screen, shown as a title around a teleport so the world loading
     * never shows: in for {@code in} ticks, held {@code stay}, out over {@code out}.
     */
    public static void blackout(org.bukkit.entity.Player player, int in, int stay, int out) {
        player.showTitle(net.kyori.adventure.title.Title.title(
                Component.text("", NamedTextColor.WHITE).font(FONT), Component.empty(),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(in * 50L),
                        java.time.Duration.ofMillis(stay * 50L), java.time.Duration.ofMillis(out * 50L))));
    }

    public static boolean has(@Nullable String screen) {
        return screen != null && SCREENS.containsKey(screen);
    }

    /**
     * A container title: the screen's background (if it has one) and the text in the pixel font. The
     * text keeps its own colours; uncoloured parts are drawn in the primary text colour.
     */
    public static Component title(@Nullable String screen, Component text) {
        Glyph glyph = screen == null ? null : SCREENS.get(screen);
        Component body = text.colorIfAbsent(Style.color(Style.TEXT)).decoration(TextDecoration.ITALIC, false);
        if (glyph == null) {
            return Component.text().font(FONT).append(Component.text(shift(TEXT_PAD))).append(body).build();
        }
        int back = -(TITLE_X + glyph.left());
        String prefix = shift(back) + glyph.ch() + shift(-glyph.advance() - back + TEXT_PAD);
        return Component.text().font(FONT)
                .append(Component.text(prefix, NamedTextColor.WHITE))
                .append(body).build();
    }
}
