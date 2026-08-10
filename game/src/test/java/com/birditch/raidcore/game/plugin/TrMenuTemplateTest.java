package com.birditch.raidcore.game.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Regression checks for the TrMenu templates shipped with the Game plugin. */
class TrMenuTemplateTest {

  private static final List<String> MENUS = List.of(
      "trmenu/raidcore_editor_toolbar.yml",
      "trmenu/raidcore_editor_object.yml",
      "trmenu/raidcore_loot.yml");
  private static final Pattern LOOT_SOURCE =
      Pattern.compile("source:RAIDCORELOOT:slot-(\\d+)");

  @Test
  void everyGameMenuIsAProtectedTrMenuVirtualInventory() throws IOException {
    for (String resource : MENUS) {
      String yaml = read(resource);
      assertTrue(yaml.contains("Hide-Player-Inventory: true"), resource);
      assertTrue(yaml.contains("Layout:"), resource);
      assertTrue(yaml.contains("Icons:"), resource);
      assertFalse(yaml.contains("Bukkit.createInventory"), resource);
      assertFalse(yaml.contains("TextDisplay"), resource);
      assertFalse(yaml.contains("ArmorStand"), resource);
    }
  }

  @Test
  void lootTemplateHasEveryReadOnlySlotAndActionEndpoint() throws IOException {
    String yaml = read("trmenu/raidcore_loot.yml");
    Matcher matcher = LOOT_SOURCE.matcher(yaml);
    int count = 0;
    while (matcher.find()) {
      assertEquals(Integer.toString(count), matcher.group(1));
      count++;
    }
    assertEquals(45, count);
    for (int slot = 0; slot < 45; slot++) {
      assertTrue(yaml.contains("command: raid ui loot take " + slot),
          "missing Loot action for slot " + slot);
    }
    assertTrue(yaml.contains("Hide-Player-Inventory: true"));
  }

  @Test
  void gameDeclaresTrMenuAndDecentHologramsAsHardDependencies() throws IOException {
    String yaml = read("paper-plugin.yml");

    assertTrue(yaml.contains("    TrMenu:\n      load: BEFORE\n      required: true\n"
        + "      join-classpath: false"));
    assertTrue(yaml.contains("    DecentHolograms:\n      load: BEFORE\n      required: true\n"
        + "      join-classpath: true"));
  }

  @Test
  void startupInstallsAndRequiresEveryShippedGameMenu() throws IOException {
    Path application = Path.of("..").toAbsolutePath().normalize()
        .resolve("game/src/main/java/com/birditch/raidcore/game/plugin/GameApplication.java");
    String java = Files.readString(application);

    for (String resource : MENUS) {
      String fileName = resource.substring(resource.lastIndexOf('/') + 1);
      String menuId = fileName.substring(0, fileName.length() - 4);
      assertTrue(java.contains('"' + resource + '"'), "menu is not installed: " + resource);
      assertTrue(java.contains('"' + menuId + '"'), "menu is not required: " + menuId);
    }
  }

  @Test
  void editorObjectActionsLetTheValidatedBackendCloseTheMenu() throws IOException {
    String yaml = read("trmenu/raidcore_editor_object.yml");

    assertFalse(yaml.contains("[ 'close', 'command: raid editor property teleport"));
    assertFalse(yaml.contains("[ 'close', 'command: raid editor property delete"));
  }

  private static String read(String resource) throws IOException {
    try (InputStream stream = TrMenuTemplateTest.class.getClassLoader()
        .getResourceAsStream(resource)) {
      assertNotNull(stream, resource);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
