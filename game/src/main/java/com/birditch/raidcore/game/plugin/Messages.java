package com.birditch.raidcore.game.plugin;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/// MiniMessage-backed message catalog loaded from `messages.yml`.
///
/// A missing key renders as a red `Missing message: <key>` component so broken
/// configs are visible in-game instead of failing silently.
public final class Messages {

  private static final String PREFIX_KEY = "prefix";

  private final Map<String, String> messages;
  private final MiniMessage miniMessage = MiniMessage.miniMessage();

  public Messages(Map<String, String> messages) {
    this.messages = Map.copyOf(Preconditions.checkNotNull(messages, "messages"));
  }

  /// Prefixed message: the `prefix` entry followed by the body under `key`.
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
      return Component.text("Missing message: " + key, NamedTextColor.RED);
    }
    return miniMessage.deserialize(format, resolvers);
  }
}
