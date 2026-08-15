package com.birditch.escapefromcraftov.game.plugin.carry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.inventory.GridPlacementFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RaidCarryMenuWiringBoundaryTest {

  @Test
  void onlyReservedContainerSlotsCanRequestARegionAndFailuresExplainContiguousSpace() {
    assertEquals(RaidCarryGridPlanner.CHEST_RIG,
        RaidCarryMenuController.equipmentRegion(RaidCarryLayout.CHEST_RIG_SLOT));
    assertEquals(RaidCarryGridPlanner.BACKPACK,
        RaidCarryMenuController.equipmentRegion(RaidCarryLayout.BACKPACK_SLOT));
    assertNull(RaidCarryMenuController.equipmentRegion(RaidCarryLayout.MAIN_HAND_SLOT));

    assertTrue(RaidCarryMenuController.failureDetail(
        GridPlacementFailure.OUT_OF_BOUNDS, 2, 3, "背包").contains("2×3"));
    assertTrue(RaidCarryMenuController.failureDetail(
        GridPlacementFailure.OUT_OF_BOUNDS, 2, 3, "背包").contains("背包边界"));
    assertTrue(RaidCarryMenuController.failureDetail(
        GridPlacementFailure.OCCUPIED, 2, 3, "胸挂").contains("已有其他物品"));
    assertTrue(RaidCarryMenuController.failureDetail(
        GridPlacementFailure.COMPARTMENT_BOUNDARY, 2, 2, "口袋").contains("彼此独立"));
  }

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
    String refresh = method(source, "private void refreshMenuBestEffort",
        "/** Dynamic source");
    assertTrue(refresh.contains("menus.refresh(player, MENU_ID, session.nonce)"));
    assertTrue(refresh.contains("closeBlocked(player.getUniqueId())"),
        "a cache reset failure must close instead of leaving a stale actionable view");
    assertFalse(refresh.contains("openMenu(player, session)"));
    assertTrue(source.contains("itemStacks.fromInstance(placement.item()).getFirst().clone()"));
    assertTrue(source.contains("scheduler.runLater(Duration.ZERO"),
        "TrMenu reset must not be mistaken for a genuine close in the same tick");
    assertFalse(source.contains("getInventory().setItem("),
        "TrMenu adapter must never become another inventory authority");
    assertFalse(source.contains("Bukkit.createInventory"));
  }

  @Test
  void equipmentRightClickCoalescesThenReauthorizesTheFocusedLiveView() throws Exception {
    String source = Files.readString(root().resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/carry/"
            + "RaidCarryMenuController.java"));
    String held = method(source, "private void requestHeldEquipmentOpen",
        "private void requestFocusedOpen");
    assertTrue(held.contains("carry.active(player.getUniqueId())"));
    assertTrue(held.contains("getHeldItemSlot()"));
    assertTrue(held.contains("equipmentStackMatches"));

    String request = method(source, "private void requestFocusedOpen",
        "private boolean equipmentRegionAvailable");
    assertTrue(request.contains("scheduler.runLater(Duration.ZERO"));
    assertTrue(request.contains("pendingFocusedOpens.remove(playerId, pending)"));
    assertTrue(request.contains("openFocused(player"));

    String focused = method(source, "private void openFocused", "private void open(Player player");
    assertTrue(focused.indexOf("MenuView view = currentView(player)")
        < focused.indexOf("equipmentRegionAvailable"));
    String available = method(source, "private boolean equipmentRegionAvailable",
        "private boolean equipmentStackMatches");
    assertTrue(available.contains("view.regions().stream()"));
    assertTrue(available.contains("equipmentStackMatches"));
    assertTrue(source.contains("event.getHand() != EquipmentSlot.HAND"));
  }

  @Test
  void coloredPlacementPreviewIsReadOnlyAndCommitStillUsesCarryAuthority() throws Exception {
    String source = Files.readString(root().resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/carry/"
            + "RaidCarryMenuController.java"));
    String preview = method(source, "private @Nullable PlacementPreview placementPreview",
        "private void rejectPreview");
    assertTrue(preview.contains("grids.transfer(session.view.layout()"));
    assertFalse(preview.contains("carry.mutateFromMenu"));
    assertTrue(source.contains("Material.LIME_STAINED_GLASS_PANE"));
    assertTrue(source.contains("Material.RED_STAINED_GLASS_PANE"));
    assertTrue(source.contains(
        "NamespacedKey.fromString(\"escapefromcraftov:secure_case\")"));
    assertTrue(source.contains("Material.ENDER_CHEST"),
        "secure entry must stay recognizable without the resource pack");
    assertTrue(source.contains("carry.mutateFromMenu(player, mutation)"));
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
