package com.birditch.escapefromcraftov.game.plugin.loot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LootLiveRefreshBoundaryTest {

  private static final Path SOURCE = Path.of(
      "src/main/java/com/birditch/escapefromcraftov/game/plugin/loot/CraftovLootListener.java");

  @Test
  void revealingASlotInvalidatesTheLiveMenuBeforeFeedback() throws IOException {
    String source = Files.readString(SOURCE);
    String reveal = method(source, "private void tickReveal", "private void completeReveal");

    int mutate = reveal.indexOf("expected.progress().reveal(slot)");
    int refresh = reveal.indexOf("refreshOpenViewOrStop(player)");
    int feedback = reveal.indexOf("playRevealFeedback");
    assertTrue(mutate >= 0 && refresh > mutate && feedback > refresh);
    assertTrue(reveal.contains("expected.progress().rollbackReveal(slot)"));
    assertFalse(reveal.contains("if (!openOrStop(player))"));
  }

  @Test
  void liveRefreshUsesTrMenusRealCacheResetAndKeepsDeferredCloseGuard() throws IOException {
    String source = Files.readString(SOURCE);
    String refresh = method(source, "private boolean refreshOpenViewOrStop",
        "private void open(Player");
    String display = method(source, "private void open(Player player, boolean refresh)",
        "private ItemStack menuItem");
    String closeEvent = method(source, "public void onInventoryClose", "@EventHandler");

    assertTrue(refresh.contains("open(player, true)"));
    assertFalse(refresh.contains("menus.close"));
    assertTrue(display.contains("menus.refresh(player, MENU_ID, arguments)"));
    assertTrue(display.contains("menus.open(player, MENU_ID, arguments)"));
    assertTrue(refresh.contains("stopView(player, false, true)"));
    assertTrue(refresh.contains("messages.raw(\"loot.open-failed\")"));
    assertTrue(closeEvent.contains("scheduler.runLater(Duration.ZERO"));
    assertTrue(closeEvent.contains("!menus.isViewing(player, MENU_ID)"));
  }

  @Test
  void sharedContainerMutationRefreshesEveryCurrentViewer() throws IOException {
    String source = Files.readString(SOURCE);
    String viewers = method(source, "private void refreshViewers", "private ValidatedView");

    assertTrue(viewers.contains("menus.isViewing(viewer, MENU_ID)"));
    assertTrue(viewers.contains("refreshOpenViewOrStop(viewer)"));
    assertFalse(viewers.contains("openOrStop(viewer)"));
  }

  @Test
  void pageChangesCannotBypassTheCacheResetThroughTheHiddenEndpoint() throws IOException {
    String source = Files.readString(SOURCE);
    String page = method(source, "public void page", "@Subscribe");

    assertTrue(page.contains("refreshOpenViewOrStop(player)"));
    assertFalse(page.contains("openOrStop(player)"));
  }

  private static String method(String source, String start, String next) {
    int from = source.indexOf(start);
    int to = source.indexOf(next, from + start.length());
    assertTrue(from >= 0 && to > from, "method boundary missing for " + start);
    return source.substring(from, to);
  }
}
