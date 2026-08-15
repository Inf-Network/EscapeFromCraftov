package com.birditch.escapefromcraftov.game.plugin;

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
      "trmenu/escapefromcraftov_editor_toolbar.yml",
      "trmenu/escapefromcraftov_editor_object.yml",
      "trmenu/escapefromcraftov_loot.yml",
      "trmenu/escapefromcraftov_secure.yml",
      "trmenu/escapefromcraftov_carry.yml");
  private static final Pattern LOOT_SOURCE =
      Pattern.compile("source:CRAFTOVCORELOOT:slot-(\\d+)");

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
    String yaml = read("trmenu/escapefromcraftov_loot.yml");
    Matcher matcher = LOOT_SOURCE.matcher(yaml);
    int count = 0;
    while (matcher.find()) {
      assertEquals(Integer.toString(count), matcher.group(1));
      count++;
    }
    assertEquals(45, count);
    for (int slot = 0; slot < 45; slot++) {
      assertTrue(yaml.contains("command: craftov ui loot take " + slot),
          "missing Loot action for slot " + slot);
    }
    assertTrue(yaml.contains("Hide-Player-Inventory: true"));
    assertTrue(yaml.contains("Default-Arguments: [ '1', '1', 'SUPPLY', '1' ]"));
    assertTrue(yaml.contains("{2} &7T{3}"), "Loot title must expose container type and tier");
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
  void secureTemplateHasThreeProtectedSlotsAndValidatedActions() throws IOException {
    String yaml = read("trmenu/escapefromcraftov_secure.yml");
    for (int slot = 0; slot < 3; slot++) {
      assertTrue(yaml.contains("source:CRAFTOVCORESECURE:slot-" + slot));
      assertTrue(yaml.contains("command: craftov ui secure take " + slot));
    }
    assertTrue(yaml.contains("command: craftov ui secure store"));
    assertTrue(yaml.contains("Hide-Player-Inventory: true"));
  }

  @Test
  void carryTemplateNonceBindsGridCellsAndExposesTheValidatedSecureEntry()
      throws IOException {
    String yaml = read("trmenu/escapefromcraftov_carry.yml");
    assertTrue(yaml.contains("<shift:-8><image:escapefromcraftov:carry_gui>"));
    for (int slot = 0; slot < 51; slot++) {
      assertTrue(yaml.contains("source:CRAFTOVCORECARRY:cell-" + slot));
      assertTrue(yaml.contains("craftov ui carry left {0} " + slot));
      assertTrue(yaml.contains("craftov ui carry right {0} " + slot));
    }
    assertTrue(yaml.contains("source:CRAFTOVCORECARRY:cell-51"));
    assertTrue(yaml.contains("actions: { all: 'command: craftov secure' }"));
    assertFalse(yaml.contains("craftov ui carry left {0} 51"));
    assertFalse(yaml.contains("craftov ui carry right {0} 51"));
    assertTrue(yaml.contains("source:CRAFTOVCORECARRY:summary"));
    assertTrue(yaml.contains("Default-Arguments: [ 'invalid' ]"));
    assertFalse(yaml.contains("{1}"), "formatted weight must not become an unsafe TrMenu arg");
    assertTrue(yaml.contains("Hide-Player-Inventory: true"));
  }

  @Test
  void startupInstallsAndRequiresEveryShippedGameMenu() throws IOException {
    Path application = Path.of("..").toAbsolutePath().normalize()
        .resolve("game/src/main/java/com/birditch/escapefromcraftov/game/plugin/GameApplication.java");
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
    String yaml = read("trmenu/escapefromcraftov_editor_object.yml");

    assertFalse(yaml.contains("[ 'close', 'command: craftov editor property teleport"));
    assertFalse(yaml.contains("[ 'close', 'command: craftov editor property delete"));
  }

  @Test
  void editorToolbarExposesEventsThroughThePublicValidatedCommand() throws IOException {
    String yaml = read("trmenu/escapefromcraftov_editor_toolbar.yml");

    assertTrue(yaml.contains("command: craftov editor event list"));
    assertFalse(yaml.contains("editor property event"),
        "non-spatial events must not masquerade as selected map objects");
  }

  private static String read(String resource) throws IOException {
    try (InputStream stream = TrMenuTemplateTest.class.getClassLoader()
        .getResourceAsStream(resource)) {
      assertNotNull(stream, resource);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
