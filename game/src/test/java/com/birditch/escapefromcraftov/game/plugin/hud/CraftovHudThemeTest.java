package com.birditch.escapefromcraftov.game.plugin.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

class CraftovHudThemeTest {

  @Test
  void textFallbackIsUnchangedWithoutCraftEngine() {
    Component text = Component.text("携行 8 / 18 格", NamedTextColor.GRAY);

    assertEquals(text, CraftovHudTheme.line(false, CraftovHudTheme.Icon.CARRY, text));
  }

  @Test
  void tacticalGlyphUsesThePackFontWithoutReplacingReadableText() {
    Component text = Component.text("撤离点", NamedTextColor.AQUA);
    Component themed = CraftovHudTheme.line(true, CraftovHudTheme.Icon.EXTRACTION, text);

    assertNull(themed.font());
    assertEquals(Key.key("escapefromcraftov", "hud"), themed.children().getFirst().font());
    assertNull(themed.children().getLast().font());
    assertEquals("\ue018 撤离点", plain(themed));
  }

  private static String plain(Component component) {
    StringBuilder result = new StringBuilder(component instanceof net.kyori.adventure.text.TextComponent
        text ? text.content() : "");
    for (Component child : component.children()) result.append(plain(child));
    return result.toString();
  }
}
