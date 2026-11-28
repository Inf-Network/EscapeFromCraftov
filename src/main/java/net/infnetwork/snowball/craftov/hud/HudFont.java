package net.infnetwork.snowball.craftov.hud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How wide the BetterHud HUD's text font draws a line, in GUI pixels, so the server can break the
 * alert banner into rows that fit its panel (#178) and cut any placeholder down to the room its panel
 * has for it (#192). The advances come from ui/hud_font.json, which tools/art/build.py writes from the
 * same glyphs it gives BetterHud: every character not listed advances {@code default} pixels (a
 * hanzi's), and anything inside {@code <...>} is a colour tag. Its {@code fit} section gives, per
 * placeholder, the widest the value may be: the build measures it from the layout (panel width, where
 * the text starts, the fixed words around it).
 */
public final class HudFont {

    private static final int DEFAULT_ADVANCE = 11;
    private static final Map<Integer, Integer> ADVANCES = new HashMap<>();
    private static final Map<String, Integer> BUDGETS = new HashMap<>();
    private static int fallback = DEFAULT_ADVANCE;

    static {
        try (InputStream in = HudFont.class.getResourceAsStream("/ui/hud_font.json")) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                fallback = root.get("default").getAsInt();
                JsonObject advances = root.getAsJsonObject("advances");
                for (String ch : advances.keySet()) {
                    ADVANCES.put(ch.codePointAt(0), advances.get(ch).getAsInt());
                }
                JsonObject fit = root.getAsJsonObject("fit");
                if (fit != null) {
                    for (String name : fit.keySet()) {
                        BUDGETS.put(name, fit.get(name).getAsInt());
                    }
                }
            }
        } catch (Exception ignored) {
            // Without the table every character counts as a hanzi: lines break early, never late.
        }
    }

    private HudFont() {
    }

    public static int advance(int codePoint) {
        return ADVANCES.getOrDefault(codePoint, fallback);
    }

    /** The room the layout leaves for a placeholder's value, in px, or {@code fallback} if it sets none. */
    public static int budget(String placeholder, int fallback) {
        return BUDGETS.getOrDefault(placeholder, fallback);
    }

    /** The text as it is, or cut to end in "…" within {@code maxWidth} px. */
    public static String fit(String text, int maxWidth) {
        return width(text) <= maxWidth ? text : ellipsis(text, maxWidth);
    }

    /**
     * {@code head + tail} within {@code maxWidth} px, cutting only the head (a name), so the tail (the
     * direction and distance, a state) always shows.
     */
    public static String fit(String head, String tail, int maxWidth) {
        return fit(head, Math.max(0, maxWidth - width(tail))) + tail;
    }

    /** The drawn width of BetterHud text, tags left out. */
    public static int width(String text) {
        int w = 0;
        for (int i = 0; i < text.length(); ) {
            if (text.charAt(i) == '<') {
                int end = text.indexOf('>', i);
                if (end > 0) {
                    i = end + 1;
                    continue;
                }
            }
            int cp = text.codePointAt(i);
            w += advance(cp);
            i += Character.charCount(cp);
        }
        return w;
    }

    /**
     * Breaks BetterHud text into at most {@code maxLines} rows no wider than {@code maxWidth}, at a
     * space or after Chinese punctuation where one is close, else between any two characters. Every
     * row after the first starts with the colour that was open where it begins, and the last row ends
     * in "…" when the text did not fit.
     */
    public static List<String> wrap(String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        Deque<String> open = new ArrayDeque<>();
        StringBuilder line = new StringBuilder();
        int width = 0;
        int breakAt = -1;            // index in `line` after which the row may end
        int widthAtBreak = 0;
        String colourAtBreak = "";
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '<') {
                int end = text.indexOf('>', i);
                if (end > 0) {
                    String tag = text.substring(i, end + 1);
                    if (tag.startsWith("</")) {
                        if (!open.isEmpty()) {
                            open.pop();
                        }
                    } else {
                        open.push(tag);
                    }
                    line.append(tag);
                    i = end + 1;
                    continue;
                }
            }
            int cp = text.codePointAt(i);
            int adv = advance(cp);
            if (width + adv > maxWidth && width > 0) {
                if (lines.size() == maxLines - 1) {
                    lines.add(ellipsis(line.toString(), maxWidth));
                    return lines;
                }
                String rest;
                String colour;
                if (breakAt > 0 && widthAtBreak >= maxWidth / 2) {
                    rest = line.substring(breakAt).stripLeading();
                    lines.add(line.substring(0, breakAt).stripTrailing());
                    colour = colourAtBreak;
                } else {
                    rest = "";
                    lines.add(line.toString());
                    colour = open.isEmpty() ? "" : open.peek();
                }
                line.setLength(0);
                line.append(colour).append(rest);
                width = width(rest);
                breakAt = -1;
                if (cp == ' ') {
                    i++;
                    continue;
                }
            }
            line.appendCodePoint(cp);
            width += adv;
            i += Character.charCount(cp);
            if (cp == ' ' || "，、。；：·！？".indexOf(cp) >= 0) {
                breakAt = line.length();
                widthAtBreak = width;
                colourAtBreak = open.isEmpty() ? "" : open.peek();
            }
        }
        if (width > 0 || lines.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static String ellipsis(String line, int maxWidth) {
        StringBuilder out = new StringBuilder(line);
        while (out.length() > 0 && width(out.toString()) + advance('…') > maxWidth) {
            int cut = out.length() - 1;
            if (out.charAt(cut) == '>') {
                int start = out.lastIndexOf("<");
                out.setLength(Math.max(0, start));
                continue;
            }
            out.setLength(Character.isLowSurrogate(out.charAt(cut)) ? cut - 1 : cut);
        }
        return out + "…";
    }
}
