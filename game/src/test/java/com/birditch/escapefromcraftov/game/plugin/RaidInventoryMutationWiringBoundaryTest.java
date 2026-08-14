package com.birditch.escapefromcraftov.game.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RaidInventoryMutationWiringBoundaryTest {

  @Test
  void commandBackedLootDeathAndSecureMutationsAllPassTheSharedFinalGate()
      throws Exception {
    String loot = read("loot/CraftovLootListener.java");
    String death = read("deathcrate/DeathCrateListener.java");
    String secure = read("secure/SecureContainerController.java");

    assertTrue(method(loot, "public void take", "public void page")
        .contains("ValidatedView validated = validateView(player)"));
    assertTrue(method(loot, "public void page", "@Subscribe")
        .contains("ValidatedView validated = validateView(player)"));
    assertTrue(method(loot, "public void take", "public void page")
        .contains("() -> mutationGate.allows(player.getUniqueId())"));
    assertTrue(loot.contains("!mutationGate.allows(playerId)"),
        "the async search tick must stop as soon as final authority changes");

    assertTrue(method(death, "public void take", "public void page")
        .contains("ValidatedView validated = validateView(player)"));
    assertTrue(method(death, "public void page", "@EventHandler")
        .contains("ValidatedView validated = validateView(player)"));
    assertTrue(method(death, "public void take", "public void page")
        .contains("() -> mutationGate.allows(player.getUniqueId())"));

    assertTrue(method(secure, "public void storeHeld", "private void rollbackManualStore")
        .contains("ValidatedView view = validateView(player)"));
    assertTrue(method(secure, "public void take", "/** Seals")
        .contains("ValidatedView view = validateView(player)"));
    assertTrue(method(secure, "public void take", "/** Seals")
        .contains("() -> mutationGate.allows(player.getUniqueId())"));

    for (String source : new String[] {loot, death, secure}) {
      assertTrue(source.contains("onPlayerDowned(PlayerDownedEvent event)"));
      assertTrue(source.contains("onExtractionLocked(PlayerExtractionLockedEvent event)"));
      assertTrue(source.contains("menus.close("));
    }
  }

  @Test
  void deathOpenAndGroundPickupCommitOnlyAtMonitorAfterAllOrdinaryVetoes()
      throws Exception {
    String death = read("deathcrate/DeathCrateListener.java");
    String pickup = read("carry/RaidCarryPickupListener.java");
    String ownership = read("ownership/LootOwnershipController.java");

    assertTrue(death.contains("EventPriority.LOWEST, ignoreCancelled = false)\n"
        + "  public void onInteractMarker"));
    assertTrue(death.contains("EventPriority.MONITOR, ignoreCancelled = false)\n"
        + "  public void onInteractCommit"));

    String guard = method(pickup, "public void onPickupGuard", "@EventHandler");
    String commit = method(pickup, "public void onPickupCommit", "}");
    assertFalse(guard.contains("transferIntoCargo"), "HIGHEST may freeze only");
    assertTrue(commit.contains("carry.transferIntoCargo"));
    assertTrue(pickup.contains("EventPriority.MONITOR, ignoreCancelled = true)\n"
        + "  public void onPickupCommit"), "late HIGHEST cancellation must skip source mutation");
    assertTrue(ownership.contains("if (manualCarry.test(player.getUniqueId())) return;"));
  }

  @Test
  void secureManualStoreAndOwnershipRemovalHaveNoThrowingPostCommitGap() throws Exception {
    String secure = read("secure/SecureContainerController.java");
    String store = method(secure, "public void storeHeld", "private void rollbackManualStore");
    int transaction = store.indexOf("try {");
    int target = store.indexOf("slot = view.state().store(storedItem)", transaction);
    int clear = store.indexOf("player.getInventory().setItemInMainHand(null)", target);
    int rollback = store.indexOf("rollbackManualStore(", clear);
    assertTrue(transaction >= 0 && target > transaction && clear > target && rollback > clear);
    String rollbackMethod = method(secure, "private void rollbackManualStore", "/** Returns");
    assertTrue(rollbackMethod.contains("state.takeIfSame(slot, storedItem)"));

    String ownership = read("ownership/LootOwnershipController.java");
    String commit = method(ownership, "public boolean commitManualPickup", "/** Adds");
    int remove = commit.indexOf("entity.remove()");
    int presentation = commit.indexOf("try {", remove);
    int success = commit.lastIndexOf("return true");
    assertTrue(remove >= 0 && presentation > remove && success > presentation);
    assertTrue(commit.contains("catch (RuntimeException presentationFailure)"));
  }

  @Test
  void secureSettlementFreezesOrRemovesAuthorityBeforeClosingItsMenu() throws Exception {
    String secure = read("secure/SecureContainerController.java");
    String begin = method(secure, "public List<ItemInstance> beginSettlement", "/** Current");
    assertTrue(begin.indexOf(".beginSettlement()")
        < begin.indexOf("closeSettlementMenuBestEffort"));
    String complete = method(secure, "public void completeSettlement", "private void closeSettlementMenuBestEffort");
    assertTrue(complete.indexOf("states.remove(key)")
        < complete.indexOf("closeSettlementMenuBestEffort"));
    String close = method(secure, "private void closeSettlementMenuBestEffort",
        "private static void logBestEffort");
    assertTrue(close.contains("catch (RuntimeException failure)"));
  }

  @Test
  void productionUsesOneFullGateAndActiveCarryOwnershipCoordination() throws Exception {
    String application = Files.readString(root().resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/GameApplication.java"));
    assertTrue(application.contains("new RaidInventoryMutationGate(combat,"));
    assertTrue(application.contains("pending != null && pending.isPending(playerId)"));
    assertTrue(application.contains("session.state() == ExtractionState.SETTLING"));
    assertTrue(application.contains("carryController::active"));
    assertTrue(application.contains("carryController, mutationGate,"));
    assertTrue(application.contains("wiring.itemStacks(), wiring.mutationGate())"));
  }

  private static String read(String relative) throws Exception {
    return Files.readString(root().resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/" + relative));
  }

  private static String method(String source, String startText, String endText) {
    int start = source.indexOf(startText);
    int end = source.indexOf(endText, start + startText.length());
    if (start < 0 || end < 0) throw new IllegalStateException(
        "method boundary not found: " + startText + " -> " + endText);
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
