package net.infnetwork.snowball.craftov.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/** MiniMessage helpers. Every component built here has italics disabled so item lore stays clean. */
public final class Text {

    public static final String PREFIX = "<#3A3F47>[</#3A3F47><gradient:#F2C94C:#F2994A>Craftov</gradient><#3A3F47>]</#3A3F47> ";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DecimalFormat MONEY = new DecimalFormat("#,###");

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

    public static void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(mm(PREFIX + miniMessage));
    }

    public static String money(long amount) {
        return MONEY.format(amount);
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

    /** Text progress bar such as ▮▮▮▮▯▯▯. */
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
