package com.birditch.escapefromcraftov.bukkit;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/// MiniMessage-backed message catalog loaded from `messages.yml`.
///
/// A missing key renders as a red `缺失消息:<key>` component so broken
/// configs are visible in-game instead of failing silently.
public final class Messages {

  private static final String PREFIX_KEY = "prefix";

  private final Map<String, String> messages;
  private final MiniMessage miniMessage = MiniMessage.miniMessage();

  public Messages(Map<String, String> messages) {
    this.messages = Map.copyOf(Preconditions.checkNotNull(messages, "messages"));
  }

  /// Prefixed message: the `prefix` entry followed by the body under `key`.
  /// Reserved for admin/debug command output; gameplay flow uses [#raw].
  public Component msg(String key, TagResolver... resolvers) {
    Component body = raw(key, resolvers);
    String prefix = messages.get(PREFIX_KEY);
    return prefix == null ? body : miniMessage.deserialize(prefix).append(body);
  }

  /// Unprefixed message body under `key`.
  public Component raw(String key, TagResolver... resolvers) {
    Preconditions.checkNotNull(key, "key");
    String format = messages.get(key);
    if (format == null) {
      return missing(key);
    }
    return miniMessage.deserialize(format, resolvers);
  }

  /// Whether the catalog defines `key`; lets callers fall back gracefully for
  /// optional entries (for example per-map display names).
  public boolean contains(String key) {
    Preconditions.checkNotNull(key, "key");
    return messages.containsKey(key);
  }

  /// Multi-line message: the value under `key` split on `|`, one component per
  /// segment (used for menu lore). Missing keys yield a single red line.
  public List<Component> lines(String key, TagResolver... resolvers) {
    Preconditions.checkNotNull(key, "key");
    String format = messages.get(key);
    if (format == null) {
      return List.of(missing(key));
    }
    return Arrays.stream(format.split("\\|"))
        .map(segment -> miniMessage.deserialize(segment, resolvers))
        .toList();
  }

  /// Red fallback for an undefined key so broken configs stay visible in-game.
  private static Component missing(String key) {
    return Component.text("缺失消息:" + key, NamedTextColor.RED);
  }
}
