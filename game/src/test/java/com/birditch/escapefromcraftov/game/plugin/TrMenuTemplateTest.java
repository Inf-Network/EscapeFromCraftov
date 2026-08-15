package com.birditch.escapefromcraftov.game.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
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
    assertTrue(yaml.contains("Title: '&f<shift:-8><image:escapefromcraftov:carry_gui>'"));
    assertFalse(yaml.contains("<white>"),
        "TrMenu treats MiniMessage color tags as literal title text and offsets the GUI image");
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
  void carryBackgroundShipsItsExactNegativeEightPixelShiftGlyph() throws IOException {
    Path shiftFont = Path.of("..").toAbsolutePath().normalize()
        .resolve("craftengine/resources/escapefromcraftov/resourcepack/assets/minecraft/font/default.json");
    String json = Files.readString(shiftFont);

    assertTrue(json.contains("\"type\": \"space\""));
    assertTrue(json.contains("\"\\uf807\": -8"),
        "CraftEngine <shift:-8> resolves to U+F807 and must not render as a missing glyph");
  }

  @Test
  void basicRigUsesAnUntintedHighVisibilityItemModel() throws IOException {
    Path craftEngine = Path.of("..").toAbsolutePath().normalize()
        .resolve("craftengine/resources/escapefromcraftov");
    String items = Files.readString(craftEngine.resolve("configuration/items.yml"));
    String expected = """
          escapefromcraftov:basic_rig:
            material: leather_chestplate
            item_model: escapefromcraftov:basic_rig
            model:
              type: minecraft:model
              path: escapefromcraftov:item/basic_rig
              generation:
                parent: minecraft:item/generated
                textures:
                  layer0: escapefromcraftov:item/basic_rig
        """;
    assertTrue(items.contains(expected));
    assertFalse(items.substring(items.indexOf("escapefromcraftov:basic_rig:"),
        items.indexOf("escapefromcraftov:patrol_backpack:")).contains("tint"),
        "leather dye tint must not make the tactical rig icon render black");

    BufferedImage image = ImageIO.read(craftEngine.resolve(
        "resourcepack/assets/escapefromcraftov/textures/item/basic_rig.png").toFile());
    assertNotNull(image);
    int visible = 0;
    double luminance = 0;
    int minX = image.getWidth();
    int minY = image.getHeight();
    int maxX = -1;
    int maxY = -1;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        int argb = image.getRGB(x, y);
        if ((argb >>> 24) < 32) continue;
        int red = (argb >>> 16) & 0xff;
        int green = (argb >>> 8) & 0xff;
        int blue = argb & 0xff;
        luminance += 0.2126 * red + 0.7152 * green + 0.0722 * blue;
        visible++;
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
      }
    }
    assertTrue(visible > 0);
    assertTrue(maxX - minX + 1 >= 44, "rig must remain legible in a 16px hotbar slot");
    assertTrue(maxY - minY + 1 >= 54, "rig must fill the item frame vertically");
    assertTrue(luminance / visible >= 75.0,
        "night-time inventory shading must not hide the rig icon");
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
