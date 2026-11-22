package net.infnetwork.snowball.craftov.gui;

import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Craftov design tokens, shared with the resource pack art (tools/art/palette.py).
 * <p>
 * One dark palette with a single accent: amber marks what is selected, what costs money and the
 * main action of a screen. Text comes in three steps (primary, muted, dim) and three semantic
 * colours carry state (ok, danger, info). Rarity colours belong to items only ({@code Rarity});
 * buttons and messages never borrow them.
 * <p>
 * Every MiniMessage string built through {@code Text.mm} understands the tokens as tags:
 * {@code <text> <muted> <dim> <accent> <ok> <danger> <info>}. Strings that BetterHud parses
 * itself (HUD placeholders) cannot use the tags and take {@link #tag} instead.
 */
public final class Style {

    public static final String BG = "#0D1014";
    public static final String PANEL = "#151A20";
    public static final String RAISED = "#1C222A";
    public static final String LINE = "#2C333D";
    public static final String ACCENT = "#F2B544";
    public static final String TEXT = "#E9E5DA";
    public static final String MUTED = "#A4AAB3";
    public static final String DIM = "#69707A";
    public static final String OK = "#7FD1AE";
    public static final String DANGER = "#E5484D";
    public static final String INFO = "#8AB4F8";

    /** State marks: a tick, a bang and a cross, always in their semantic colour. */
    public static final String MARK_OK = "<ok>✔</ok> ";
    public static final String MARK_WARN = "<accent>!</accent> ";
    public static final String MARK_FAIL = "<danger>✖</danger> ";

    private static final TagResolver TAGS = TagResolver.resolver(
            color("text", TEXT), color("muted", MUTED), color("dim", DIM), color("accent", ACCENT),
            color("ok", OK), color("danger", DANGER), color("info", INFO));

    private Style() {
    }

    /** The token tags for a MiniMessage parser. */
    public static TagResolver tags() {
        return TAGS;
    }

    /** A plain {@code <#RRGGBB>} opening tag, for text parsed outside Adventure (BetterHud). */
    public static String tag(String hex) {
        return "<" + hex + ">";
    }

    public static TextColor color(String hex) {
        return TextColor.fromHexString(hex);
    }

    private static TagResolver color(String name, String hex) {
        return TagResolver.resolver(name, Tag.styling(TextColor.fromHexString(hex)));
    }
}
