package net.infnetwork.snowball.craftov.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.infnetwork.snowball.craftov.gui.Style;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * MiniMessage helpers. Every component built here has italics disabled so item lore stays clean,
 * and the design tokens of {@link Style} work as tags ({@code <muted>}, {@code <accent>} ...).
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.builder()
            .tags(TagResolver.resolver(TagResolver.standard(), Style.tags())).build();

    private Text() {
    }

    public static Component mm(String miniMessage) {
        return MM.deserialize(miniMessage).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> mm(List<String> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(mm(line));
        }
        return out;
    }

    public static String escape(String raw) {
        return MM.escapeTags(raw);
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** A chat line. No "[Craftov]" in front: in a Chinese game it was noise on every line. */
    public static void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(mm(miniMessage));
    }

    /** 1,234,567. Stateless, so any thread may call it (a shared DecimalFormat is not thread-safe). */
    public static String money(long amount) {
        return String.format(java.util.Locale.ROOT, "%,d", amount);
    }

    /** Short money format: 12.3万 / 1.25亿. */
    public static String moneyShort(long amount) {
        long abs = Math.abs(amount);
        if (abs >= 100_000_000L) {
            return trim(amount / 100_000_000.0) + "亿";
        }
        if (abs >= 10_000L) {
            return trim(amount / 10_000.0) + "万";
        }
        return Long.toString(amount);
    }

    /** A plain number with at most two decimals and no trailing zeros: 3, 1.5, 0.85. */
    public static String decimal(double value) {
        return trim(value);
    }

    private static String trim(double value) {
        String s = String.format("%.2f", value);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static String duration(long seconds) {
        seconds = Math.max(0, seconds);
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * Breaks a MiniMessage line into lines of at most {@code width} units (a CJK character is two,
     * anything else one), for tooltips: after punctuation or a space where one is near the end,
     * anywhere otherwise. Tags still open at a break are opened again on the next line, so each line
     * parses on its own with the colours it had.
     */
    public static List<String> wrap(String miniMessage, int width) {
        List<String> lines = new ArrayList<>();
        List<String> open = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int used = 0;
        int breakAt = -1; // index in line just after the last good break point
        List<String> openAtBreak = List.of();
        int usedAtBreak = 0;
        for (int i = 0; i < miniMessage.length(); i++) {
            char c = miniMessage.charAt(i);
            if (c == '<') {
                int end = miniMessage.indexOf('>', i);
                if (end > i) {
                    String tag = miniMessage.substring(i + 1, end);
                    if (tag.startsWith("/")) {
                        int at = open.lastIndexOf(tag.substring(1));
                        if (at >= 0) {
                            open.remove(at);
                        }
                    } else {
                        open.add(tag);
                    }
                    line.append(miniMessage, i, end + 1);
                    i = end;
                    continue;
                }
            }
            String glyph = c == '\\' && i + 1 < miniMessage.length() ? miniMessage.substring(i, i + 2) : String.valueOf(c);
            int w = glyph.charAt(glyph.length() - 1) >= 0x2E80 ? 2 : 1;
            if (used + w > width && used > 0) {
                String rest = "";
                List<String> carried = new ArrayList<>(open);
                if (breakAt > 0 && usedAtBreak >= width * 2 / 3) {
                    rest = line.substring(breakAt);
                    carried = new ArrayList<>(openAtBreak);
                    line.setLength(breakAt);
                }
                lines.add(line.toString());
                line = new StringBuilder();
                for (String tag : carried) {
                    line.append('<').append(tag).append('>');
                }
                line.append(rest);
                used = width(rest);
                breakAt = -1;
            }
            line.append(glyph);
            used += w;
            i += glyph.length() - 1;
            if ("，。；：、）！？ ".indexOf(c) >= 0) { // not "," or ".": they sit inside figures (◈50,000)
                breakAt = line.length();
                openAtBreak = new ArrayList<>(open);
                usedAtBreak = used;
            }
        }
        if (used > 0 || lines.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** The display width of MiniMessage text in {@link #wrap} units, tags left out. */
    private static int width(String miniMessage) {
        int w = 0;
        for (int i = 0; i < miniMessage.length(); i++) {
            char c = miniMessage.charAt(i);
            if (c == '\\' && i + 1 < miniMessage.length()) {
                c = miniMessage.charAt(++i); // an escaped character counts as itself
            } else if (c == '<' && miniMessage.indexOf('>', i) > i) {
                i = miniMessage.indexOf('>', i);
                continue;
            }
            w += c >= 0x2E80 ? 2 : 1;
        }
        return w;
    }

    /**
     * Text progress bar of {@code |} marks, the filled ones in one colour and the rest in the other;
     * colours are token tag names ({@code ok}, {@code dim}) or {@code #RRGGBB}. The pack's text font
     * leaves {@code |} to vanilla's 1 px bitmap (tools/art/textfont.py), so a mark advances 2 px.
     */
    public static String bar(double fraction, int width, String filledColor, String emptyColor) {
        fraction = Math.max(0, Math.min(1, fraction));
        int filled = (int) Math.round(fraction * width);
        StringBuilder sb = new StringBuilder();
        sb.append('<').append(filledColor).append('>');
        sb.append("|".repeat(filled));
        sb.append("</").append(filledColor).append('>');
        sb.append('<').append(emptyColor).append('>');
        sb.append("|".repeat(width - filled));
        sb.append("</").append(emptyColor).append('>');
        return sb.toString();
    }
}
