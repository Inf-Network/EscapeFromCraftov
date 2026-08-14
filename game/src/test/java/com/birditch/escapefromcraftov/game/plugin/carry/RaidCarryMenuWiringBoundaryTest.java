package com.birditch.escapefromcraftov.game.plugin.carry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RaidCarryMenuWiringBoundaryTest {

  @Test
  void hiddenActionsAuthenticateTheLiveTrMenuBindingAndClearStaleSelection() throws Exception {
    String source = Files.readString(root().resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/carry/"
            + "RaidCarryMenuController.java"));
    String validation = method(source, "private ValidatedAction validateAction",
        "private MenuView currentView");
    assertTrue(validation.contains("displayIndex < 0"));
    assertTrue(validation.contains("return null"));
    assertTrue(validation.contains("!session.nonce.equals(nonce)"));
    assertTrue(validation.contains("!viewing(player)"));
    assertTrue(validation.contains("MenuView live = currentView(player)"));
    assertTrue(validation.contains("live.epoch() != session.view.epoch()"));
    assertTrue(validation.contains("live.version() != session.view.version()"));
    assertTrue(validation.contains("session.selection = null"));
    assertTrue(source.contains("if (!mutationGate.allows(player.getUniqueId())) return null"));
    String open = method(source, "private boolean openMenu", "private void refreshMenuBestEffort");
    assertTrue(open.contains("menus.open(player, MENU_ID, session.nonce)"));
    assertFalse(open.contains("weightLabel"), "formatted text is not a valid TrMenu argument");
    assertTrue(source.contains("itemStacks.fromInstance(placement.item()).getFirst().clone()"));
    assertTrue(source.contains("scheduler.runLater(Duration.ZERO"),
        "TrMenu reset must not be mistaken for a genuine close in the same tick");
    assertFalse(source.contains("getInventory().setItem("),
        "TrMenu adapter must never become another inventory authority");
    assertFalse(source.contains("Bukkit.createInventory"));
  }

  @Test
  void controllerReconcilesThenCasAndRelocatesTheServerSelectedIdentity() throws Exception {
    String source = Files.readString(root().resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/carry/"
            + "RaidCarryController.java"));
    String mutation = method(source, "public MenuMutationResult mutateFromMenu",
        "/** Non-mutating admission projection");
    assertTrue(mutation.indexOf("synchronizePhysical(player, state)")
        < mutation.indexOf("state.version != mutation.version()"));
    assertTrue(mutation.indexOf("validateEquipment(player, state)")
        < mutation.indexOf("state.version != mutation.version()"));
    assertTrue(mutation.indexOf("locate(state, mutation.instanceId())")
        < mutation.indexOf("grids.transfer("));
    assertTrue(mutation.indexOf("render(player, state, changed.layout())")
        < mutation.indexOf("state.layout = changed.layout()"));
    assertTrue(mutation.contains("state.version++"));
  }

  private static String method(String source, String startText, String endText) {
    int start = source.indexOf(startText);
    int end = source.indexOf(endText, start + 1);
    if (start < 0 || end < 0) throw new IllegalStateException("method boundary not found");
    return source.substring(start, end);
  }

  private static Path root() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
      current = current.getParent();
    }
    if (current == null) throw new IllegalStateException("project root not found");
    return current;
  }
}
