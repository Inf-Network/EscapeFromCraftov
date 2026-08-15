package com.birditch.escapefromcraftov.game.plugin.hud;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;

/// Optional CraftEngine-backed tactical icon theme. Every decorated line keeps
/// its complete text, so gameplay remains readable when the resource pack is
/// rejected or CraftEngine is absent.
final class CraftovHudTheme {

  private static final Key HUD_FONT = Key.key("escapefromcraftov", "hud");
  private static final String CRAFT_ENGINE = "CraftEngine";

  private CraftovHudTheme() {
  }

  static boolean available() {
    return Bukkit.getPluginManager().isPluginEnabled(CRAFT_ENGINE);
  }

  static Component title(boolean available, String text) {
    Component title = Component.text(text, NamedTextColor.GOLD)
        .decorate(TextDecoration.BOLD);
    return available ? decorated(glyph('\ue001'), title) : title;
  }

  static Component line(boolean available, Icon icon, Component text) {
    return available ? decorated(glyph(icon.codepoint()), text) : text;
  }

  private static Component decorated(Component glyph, Component readableText) {
    // Keep the custom-font glyph and readable text as siblings. Appending text to the glyph would
    // make Adventure inherit escapefromcraftov:hud for Chinese characters that the bitmap font
    // intentionally does not contain.
    return Component.empty().append(glyph).append(Component.space()).append(readableText);
  }

  private static Component glyph(char value) {
    return Component.text(String.valueOf(value), NamedTextColor.WHITE)
        .font(HUD_FONT).decoration(TextDecoration.ITALIC, false);
  }

  enum Icon {
    MAP('\ue010'), TIME('\ue011'), SQUAD('\ue012'), COMBAT('\ue013'),
    CARRY('\ue014'), WEIGHT('\ue015'), VALUE('\ue016'), TARGET('\ue017'),
    EXTRACTION('\ue018');

    private final char codepoint;

    Icon(char codepoint) {
      this.codepoint = codepoint;
    }

    private char codepoint() {
      return codepoint;
    }
  }
}
