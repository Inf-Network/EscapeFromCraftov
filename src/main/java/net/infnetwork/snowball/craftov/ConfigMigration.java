package net.infnetwork.snowball.craftov;

import net.infnetwork.snowball.craftov.util.AtomicFiles;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings a server's config.yml up to a new jar's defaults. The file is copied from the jar only
 * when it is missing, and a key it already names never falls back to the jar, so a changed default
 * (the damage numbers, the tier colours, the match wait...) would otherwise never reach a server
 * set up before the change, and the owner would have to edit the file by hand.
 * <p>
 * config.yml starts with {@code config-revision}, raised with every update that changes a default,
 * and {@link #CHANGES} records each such change: the revision that made it, the key, the values it
 * had before and the new one. On enable, a server copy below the jar's revision is checked against
 * every change above its own: a key still holding one of the old defaults (nobody changed it) gets
 * the new one, a value someone edited stays, and the file is stamped with the jar's revision, so a
 * later start changes nothing, even a value later set back to an old default by hand.
 * <p>
 * The file is edited line by line: comments, order, quoting and blank lines stay as they were (the
 * Bukkit writer would drop the comments). The result is parsed again and compared with the old
 * file before it is written; if any other value would differ, nothing is written. The old file is
 * kept as {@code config.yml.rev<n>} and the log lists each key moved and each edited one kept.
 */
final class ConfigMigration {

    /** Default {@code now} replaced {@code old} (any of the values older jars wrote) in {@code revision}. */
    private record Change(int revision, String path, List<?> old, Object now) {
    }

    /**
     * Every default changed since config.yml had no revision. When a default changes, raise
     * config-revision in src/main/resources/config.yml and add a line here with the new revision,
     * the value it had and the new one; a key changed twice gets a second line, from the first
     * line's new value. Keys written in a flow map ({@code { A: 1 }}) or over several lines cannot
     * be edited and are reported as kept.
     */
    private static final List<Change> CHANGES = List.of(
            // Revision 1 (#101): everything the jar changed since the first config.yml was written.
            new Change(1, "tiers.NORMAL.color", List.of("#5BD16B"), "#7FD1AE"), // #99: no rarity colours
            new Change(1, "tiers.CONFIDENTIAL.color", List.of("#4FA3FF"), "#8AB4F8"),
            new Change(1, "tiers.SECRET.color", List.of("#FF4D4D"), "#E5484D"),
            new Change(1, "tiers.CONFIDENTIAL.entry-fee", List.of(8000), 32000), // #80 (and the fix for it that read max-shift)
            new Change(1, "tiers.SECRET.entry-fee", List.of(20000), 43000),
            new Change(1, "match.wait-seconds", List.of(15), 30), // #11: one raid per queue window
            new Change(1, "combat.damage-numbers", List.of(true), false), // #99
            new Change(1, "combat.lag-compensation.extra-ms", List.of(100, 150), 200),
            new Change(1, "combat.zone-multipliers.arm", List.of(0.5), 0.65), // the half-damage limbs, undone
            new Change(1, "combat.zone-multipliers.leg", List.of(0.5), 0.7),
            new Change(1, "weapons.recoil.muzzle-max", List.of(3.0), 6.0),
            new Change(1, "hud.crosshair.pixels-per-degree", List.of(5.5), 4.0));

    private static final String REVISION_KEY = "config-revision";
    private static final Pattern REVISION = Pattern.compile("(?m)^" + REVISION_KEY + ":[ \\t]*(\\d+)");
    /** A mapping line: indent, key (plain or quoted), and whatever follows the colon. */
    private static final Pattern KEY_LINE = Pattern.compile(
            "^( *)(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^']|'')*'|[^\\s#'\"\\-?][^:#]*?)[ \\t]*:(?:[ \\t]+(.*))?$");

    private ConfigMigration() {
    }

    /**
     * Migrates {@code dataFolder/config.yml} to the revision of {@code jarConfig} (the jar's own
     * config.yml text). Returns whether the file was rewritten, so the caller reloads it.
     */
    static boolean migrate(File dataFolder, @Nullable InputStream jarConfig, Logger log) {
        String jarText;
        try (InputStream in = jarConfig) {
            if (in == null) {
                return false;
            }
            jarText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return false;
        }
        int target = revision(jarText);
        File file = new File(dataFolder, "config.yml");
        String text;
        try {
            text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return false; // missing or unreadable: Bukkit reports that itself
        }
        YamlConfiguration before = new YamlConfiguration();
        try {
            before.loadFromString(text);
        } catch (InvalidConfigurationException ex) {
            if (revision(text) < target) {
                log.warning("config.yml is not valid YAML, so it was not brought up to revision " + target
                        + ": fix it and restart.");
            }
            return false;
        }
        // Read as YAML, not by pattern, so a quoted key or a BOM before it still counts.
        int local = before.getInt(REVISION_KEY, 0);
        if (local >= target) {
            return false;
        }
        String bom = text.startsWith("\uFEFF") ? "\uFEFF" : "";
        String newline = text.contains("\r\n") ? "\r\n" : "\n";

        List<String> lines = new ArrayList<>(List.of(text.substring(bom.length()).split("\r?\n", -1)));
        Map<String, Object> values = new HashMap<>(); // each key's value so far
        Map<String, Object> latest = new LinkedHashMap<>(); // each key's newest default
        Map<String, Object> moved = new LinkedHashMap<>();
        Set<String> stuck = new HashSet<>();
        List<String> report = new ArrayList<>();
        for (Change change : CHANGES) {
            if (change.revision() <= local || change.revision() > target || !before.isSet(change.path())
                    || before.isConfigurationSection(change.path())) {
                continue; // a missing key already falls back to the jar's default
            }
            Object current = values.computeIfAbsent(change.path(), before::get);
            latest.put(change.path(), change.now());
            if (change.old().stream().noneMatch(old -> same(old, current))) {
                continue;
            }
            if (!replace(lines, change.path(), yaml(change.now()))) {
                stuck.add(change.path());
                continue;
            }
            values.put(change.path(), change.now());
            moved.put(change.path(), change.now());
            report.add(change.path() + " " + yaml(current) + " -> " + yaml(change.now()));
        }
        List<String> kept = new ArrayList<>();
        latest.forEach((path, now) -> {
            if (!same(values.get(path), now)) {
                kept.add(path + " " + yaml(values.get(path)) + " (" + (stuck.contains(path)
                        ? "not on a line of its own, edit it by hand" : "edited") + "; the new default is " + yaml(now) + ")");
            }
        });
        stamp(lines, target, jarText);

        String result = bom + String.join(newline, lines);
        String mismatch = verify(before, result, moved, target);
        if (mismatch != null) {
            log.warning("config.yml was not brought up to revision " + target + " (" + mismatch
                    + "); it stays as it is. Changed defaults: " + String.join(", ", report));
            return false;
        }
        String backupName = null;
        try {
            if (!moved.isEmpty()) {
                File backup = new File(dataFolder, "config.yml.rev" + local);
                for (int n = 2; backup.exists(); n++) {
                    backup = new File(dataFolder, "config.yml.rev" + local + "-" + n);
                }
                Files.copy(file.toPath(), backup.toPath());
                backupName = backup.getName();
            }
            AtomicFiles.write(file.toPath(), result);
        } catch (IOException ex) {
            log.log(Level.WARNING, "Could not bring config.yml up to revision " + target, ex);
            return false;
        }
        log.info("config.yml revision " + local + " -> " + target + ": " + moved.size() + " old default"
                + (moved.size() == 1 ? "" : "s") + " updated, " + kept.size() + " edited value"
                + (kept.size() == 1 ? "" : "s") + " kept" + (backupName == null ? "" : "; the old file is " + backupName) + ".");
        for (String line : report) {
            log.info("config.yml: " + line);
        }
        for (String line : kept) {
            log.info("config.yml: kept " + line);
        }
        return true;
    }

    private static int revision(String text) {
        Matcher m = REVISION.matcher(text);
        if (!m.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE; // too many digits for an int: newer than anything
        }
    }

    /**
     * Writes {@code value} as the value of {@code path} on that key's line, keeping the indent, the
     * key's spelling and a trailing comment. Returns false when the key is not on a line of its own
     * with a single-line scalar value.
     */
    private static boolean replace(List<String> lines, String path, String value) {
        int index = find(lines, path);
        if (index < 0) {
            return false;
        }
        Matcher m = KEY_LINE.matcher(lines.get(index));
        if (!m.matches() || m.group(3) == null) {
            return false;
        }
        String rest = m.group(3);
        int end = scalarEnd(rest);
        if (end <= 0) {
            return false;
        }
        String line = lines.get(index);
        int start = m.start(3);
        lines.set(index, line.substring(0, start) + value + line.substring(start + end));
        return true;
    }

    /** Index of the line that holds {@code path} as a mapping key, or -1. */
    private static int find(List<String> lines, String path) {
        List<Integer> indents = new ArrayList<>(); // the keys enclosing the current line, outermost first
        List<String> keys = new ArrayList<>();
        int blockIndent = -1; // inside a block scalar (| or >) started by a key at this indent
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            int indent = line.length() - line.stripLeading().length();
            if (blockIndent >= 0) {
                if (trimmed.isEmpty() || indent > blockIndent) {
                    continue;
                }
                blockIndent = -1;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-") || trimmed.equals("---")) {
                continue; // comments, list items (paths never go through a list) and document markers
            }
            Matcher m = KEY_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            while (!indents.isEmpty() && indents.getLast() >= indent) {
                indents.removeLast();
                keys.removeLast();
            }
            indents.add(indent);
            keys.add(unquote(m.group(2).strip()));
            if (String.join(".", keys).equals(path)) {
                return i;
            }
            String rest = m.group(3) == null ? "" : m.group(3).strip();
            if (rest.startsWith("|") || rest.startsWith(">")) {
                blockIndent = indent;
            }
        }
        return -1;
    }

    /**
     * Length of the single-line scalar at the start of {@code rest}, before any trailing comment,
     * or -1 for anything else (a flow map or list, a block scalar, an anchor or alias, a tag, or a
     * quoted string that goes on to the next line).
     */
    private static int scalarEnd(String rest) {
        if (rest.isEmpty() || "{[|>&*!#%@`".indexOf(rest.charAt(0)) >= 0) {
            return -1;
        }
        char first = rest.charAt(0);
        if (first == '"') {
            for (int i = 1; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    return i + 1;
                }
            }
            return -1;
        }
        if (first == '\'') {
            for (int i = 1; i < rest.length(); i++) {
                if (rest.charAt(i) == '\'') {
                    if (i + 1 < rest.length() && rest.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        return i + 1;
                    }
                }
            }
            return -1;
        }
        int comment = rest.indexOf(" #");
        int tab = rest.indexOf("\t#");
        if (tab >= 0 && (comment < 0 || tab < comment)) {
            comment = tab;
        }
        return (comment < 0 ? rest : rest.substring(0, comment)).stripTrailing().length();
    }

    /**
     * Sets the top-level config-revision line to {@code revision}, or adds it (with the comment the
     * jar's config.yml has above it) after the file's opening comment block.
     */
    private static void stamp(List<String> lines, int revision, String jarText) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = KEY_LINE.matcher(lines.get(i));
            if (m.matches() && m.group(1).isEmpty() && unquote(m.group(2).strip()).equals(REVISION_KEY)) {
                replace(lines, REVISION_KEY, Integer.toString(revision));
                return;
            }
        }
        List<String> block = new ArrayList<>();
        List<String> jar = List.of(jarText.split("\r?\n", -1));
        for (int i = 0; i < jar.size(); i++) {
            if (jar.get(i).startsWith(REVISION_KEY + ":")) {
                int from = i;
                while (from > 0 && jar.get(from - 1).startsWith("#")) {
                    from--;
                }
                block.addAll(jar.subList(from, i));
                break;
            }
        }
        block.add(REVISION_KEY + ": " + revision);
        // After the file's opening comment when a blank line ends it (otherwise it belongs to the
        // first key), else at the very top.
        int at = 0;
        while (at < lines.size() && lines.get(at).startsWith("#")) {
            at++;
        }
        if (at > 0 && at < lines.size() && lines.get(at).isBlank()) {
            block.addFirst("");
        } else {
            at = 0;
        }
        if (at < lines.size() && !lines.get(at).isBlank()) {
            block.add("");
        }
        lines.addAll(at, block);
    }

    /**
     * Null when {@code result} holds the same values as {@code before} except the moved ones (now at
     * their new defaults) and the revision, which must now read {@code revision} (or the next start
     * would migrate again); otherwise what differs.
     */
    private static @Nullable String verify(YamlConfiguration before, String result, Map<String, Object> moved, int revision) {
        YamlConfiguration after = new YamlConfiguration();
        try {
            after.loadFromString(result);
        } catch (InvalidConfigurationException ex) {
            return "the edited file would not parse: " + ex.getMessage();
        }
        if (after.getInt(REVISION_KEY, -1) != revision) {
            return REVISION_KEY + " would not read " + revision + ": set it by hand";
        }
        Set<String> paths = new LinkedHashSet<>(before.getKeys(true));
        paths.addAll(after.getKeys(true));
        for (String path : paths) {
            if (path.equals(REVISION_KEY) || before.isConfigurationSection(path) || after.isConfigurationSection(path)) {
                continue;
            }
            Object expected = moved.containsKey(path) ? moved.get(path) : before.get(path);
            if (!same(expected, after.get(path))) {
                return path + " would read " + yaml(after.get(path)) + " instead of " + yaml(expected);
            }
        }
        return null;
    }

    /** Equal YAML values: numbers by value (5.5 and 5.50, 6 and 6.0), anything else by equals. */
    private static boolean same(@Nullable Object a, @Nullable Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            try {
                return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
            } catch (NumberFormatException ex) {
                return x.doubleValue() == y.doubleValue(); // NaN or infinity
            }
        }
        return Objects.equals(a, b);
    }

    /** A value as config.yml writes it: strings double-quoted, numbers and booleans plain. */
    private static String yaml(@Nullable Object value) {
        if (value instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return String.valueOf(value);
    }

    private static String unquote(String key) {
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            return key.substring(1, key.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (key.length() >= 2 && key.startsWith("'") && key.endsWith("'")) {
            return key.substring(1, key.length() - 1).replace("''", "'");
        }
        return key;
    }
}
