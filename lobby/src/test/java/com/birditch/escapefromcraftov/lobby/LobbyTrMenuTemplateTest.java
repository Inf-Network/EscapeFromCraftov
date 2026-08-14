package com.birditch.escapefromcraftov.lobby;

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

/** Regression checks for the TrMenu templates shipped with the Lobby plugin. */
class LobbyTrMenuTemplateTest {

  private static final List<String> MENUS = List.of(
      "trmenu/escapefromcraftov_lobby_main.yml",
      "trmenu/escapefromcraftov_lobby_settings.yml",
      "trmenu/escapefromcraftov_lobby_cosmetics.yml",
      "trmenu/escapefromcraftov_lobby_warehouse.yml",
      "trmenu/escapefromcraftov_lobby_preparation.yml",
      "trmenu/escapefromcraftov_lobby_vendor.yml",
      "trmenu/escapefromcraftov_lobby_vendor_sell.yml",
      "trmenu/escapefromcraftov_lobby_market.yml",
      "trmenu/escapefromcraftov_lobby_market_mine.yml",
      "trmenu/escapefromcraftov_lobby_market_list.yml",
      "trmenu/escapefromcraftov_lobby_market_confirm.yml");
  private static final Pattern WAREHOUSE_SOURCE =
      Pattern.compile("source:CRAFTOVCORELOBBY:warehouse-(\\d+)");

  @Test
  void everyLobbyMenuHidesTheRealPlayerInventory() throws IOException {
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
  void settingsAndCosmeticsOnlyCallValidatedEscapeFromCraftovActions() throws IOException {
    String settings = read("trmenu/escapefromcraftov_lobby_settings.yml");
    String cosmetics = read("trmenu/escapefromcraftov_lobby_cosmetics.yml");

    assertTrue(settings.contains("command: lobby setting toggle visibility"));
    assertTrue(settings.contains("command: lobby setting toggle queue_messages"));
    for (int index = 0; index < 4; index++) {
      assertTrue(cosmetics.contains("command: lobby cosmetic equip " + index));
    }
    assertTrue(cosmetics.contains("command: lobby cosmetic unequip"));
  }

  @Test
  void warehouseTemplateHasLoadoutAndAllPagedWarehouseSources() throws IOException {
    String yaml = read("trmenu/escapefromcraftov_lobby_warehouse.yml");
    Matcher matcher = WAREHOUSE_SOURCE.matcher(yaml);
    int count = 0;
    while (matcher.find()) {
      assertEquals(Integer.toString(count), matcher.group(1));
      count++;
    }
    assertEquals(36, count);
    for (int slot = 0; slot < 36; slot++) {
      assertTrue(yaml.contains("command: lobby warehouse move " + slot),
          "missing warehouse action for slot " + slot);
    }
    assertTrue(yaml.contains("slots: [ 9-17 ]"));
    assertTrue(yaml.contains("上方 9 格: 具名配装位"));
    assertTrue(yaml.contains("旧版无槽位配装仍会完整显示并兼容入局"));
    assertTrue(yaml.contains("安全箱为内建 3 格"));
    assertTrue(yaml.contains("Hide-Player-Inventory: true"));
  }

  @Test
  void vendorShowsEveryOfferAndOnlySellsPagedWarehouseRows() throws IOException {
    String vendor = read("trmenu/escapefromcraftov_lobby_vendor.yml");
    String sell = read("trmenu/escapefromcraftov_lobby_vendor_sell.yml");

    for (int offer = 0; offer < 13; offer++) {
      assertTrue(vendor.contains("source:CRAFTOVCORELOBBY:vendor-offer-" + offer));
      assertTrue(vendor.contains("command: lobby vendor buy " + offer + " 1"));
      assertTrue(vendor.contains("command: lobby vendor buy " + offer + " 8"));
      assertTrue(vendor.contains("command: lobby vendor buy " + offer + " 16"));
    }
    for (int slot = 0; slot < 27; slot++) {
      assertTrue(sell.contains("source:CRAFTOVCORELOBBY:vendor-sell-" + slot));
      assertTrue(sell.contains("command: lobby vendor sell " + slot));
    }
    assertTrue(sell.contains("只可出售长期仓库物品"));
    assertTrue(sell.contains("出战栏和行动中物品不会显示"));
  }

  @Test
  void lobbyDeclaresTrMenuAsAHardDependency() throws IOException {
    String yaml = read("paper-plugin.yml");

    assertTrue(yaml.contains("    TrMenu:\n      load: BEFORE\n      required: true\n"
        + "      join-classpath: false"));
  }

  @Test
  void marketMenusExposeBrowseMineListingAndSellerPriceConfirmation() throws IOException {
    String browse = read("trmenu/escapefromcraftov_lobby_market.yml");
    String mine = read("trmenu/escapefromcraftov_lobby_market_mine.yml");
    String list = read("trmenu/escapefromcraftov_lobby_market_list.yml");
    String confirm = read("trmenu/escapefromcraftov_lobby_market_confirm.yml");
    for (int slot = 0; slot < 36; slot++) {
      assertTrue(browse.contains("source:CRAFTOVMARKET:browse-" + slot));
      assertTrue(browse.contains("command: lobby market buy " + slot));
      assertTrue(mine.contains("command: lobby market mine cancel " + slot));
      assertTrue(list.contains("command: lobby market list select " + slot));
    }
    assertTrue(confirm.contains("command: lobby market list adjust -1000"));
    assertTrue(confirm.contains("command: lobby market list confirm"));
    assertTrue(confirm.contains("挂单有效期 24 小时"));
  }

  @Test
  void mainMenuExposesPlayerMarketAndRecentRaidReports() throws IOException {
    String main = read("trmenu/escapefromcraftov_lobby_main.yml");
    assertTrue(main.contains("command: lobby raid"));
    assertFalse(main.contains("command: play"));
    assertTrue(main.contains("command: lobby market"));
    assertTrue(main.contains("command: lobby reports"));
  }

  @Test
  void preparationCyclesEveryChoiceAndRoutesStartThroughValidatedLobbyActions()
      throws IOException {
    String yaml = read("trmenu/escapefromcraftov_lobby_preparation.yml");

    for (String source : List.of("map", "mode", "difficulty", "loadout", "start")) {
      assertTrue(yaml.contains("source:CRAFTOVPREPARATION:" + source));
    }
    assertTrue(yaml.contains("command: lobby raid cycle map"));
    assertTrue(yaml.contains("command: lobby raid cycle mode"));
    assertTrue(yaml.contains("command: lobby raid cycle difficulty"));
    assertTrue(yaml.contains("command: lobby raid start"));
    assertTrue(yaml.contains("command: lobby warehouse"));
    assertTrue(read("messages.yml").contains("<weight>"));
    assertFalse(yaml.contains("command: play"));
  }

  @Test
  void startupInstallsAndRequiresEveryShippedLobbyMenu() throws IOException {
    Path application = Path.of("..").toAbsolutePath().normalize()
        .resolve("lobby/src/main/java/com/birditch/escapefromcraftov/lobby/LobbyApplication.java");
    String java = Files.readString(application);

    for (String resource : MENUS) {
      String fileName = resource.substring(resource.lastIndexOf('/') + 1);
      String menuId = fileName.substring(0, fileName.length() - 4);
      assertTrue(java.contains('"' + resource + '"'), "menu is not installed: " + resource);
      assertTrue(java.contains('"' + menuId + '"'), "menu is not required: " + menuId);
    }
  }

  private static String read(String resource) throws IOException {
    try (InputStream stream = LobbyTrMenuTemplateTest.class.getClassLoader()
        .getResourceAsStream(resource)) {
      assertNotNull(stream, resource);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
