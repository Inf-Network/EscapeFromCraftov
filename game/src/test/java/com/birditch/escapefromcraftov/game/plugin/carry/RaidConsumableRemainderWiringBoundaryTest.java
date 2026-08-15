package com.birditch.escapefromcraftov.game.plugin.carry;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RaidConsumableRemainderWiringBoundaryTest {

  @Test
  void cargoStagesOnlyTheWhitelistedRemainderBeforeScanningCraftovIdentity() throws Exception {
    String source = controller();
    String synchronization = method(source, "private void synchronizePhysical",
        "private ItemGridLayout rebuildLayout");

    int accepted = synchronization.indexOf(
        "acceptedConsumableRemainder(placement.item(), physical)");
    assertTrue(accepted >= 0);
    assertTrue(accepted < synchronization.indexOf("scanSingle(physical)"),
        "the unmarked vanilla remainder must be classified before the strict Craftov scan");
    assertTrue(synchronization.contains("consumed.add(placement.item().instanceId())"));
    assertTrue(synchronization.contains("consumedRemainderSlots.add(slot)"));
    assertTrue(synchronization.contains(
        "consumedRemainderSlots.contains(cell.physicalSlot())"));
    assertTrue(synchronization.contains(
        "if (!acceptedConsumableRemainder(placement.item(), physical))"),
        "the staged remainder must be revalidated with the old projection");
    assertTrue(synchronization.contains("rebuildLayout(state.layout, updated, consumed)"));
    assertTrue(synchronization.contains("render(player, state, rebuilt)"),
        "rendering the consumed layout must clear the vanilla bottle and its old projection");
  }

  @Test
  void controllerRequiresAnUnmarkedStackAndSharesThePolicyWithEquipment() throws Exception {
    String source = controller();
    String helper = method(source, "private boolean acceptedConsumableRemainder",
        "private boolean expectedInstance");
    assertTrue(helper.contains("itemStacks.read(stack).isEmpty()"));
    assertTrue(helper.contains("RaidConsumableRemainderPolicy.accepted"));

    String equipment = method(source, "private void validateEquipment",
        "private void enforceEquipmentLocks");
    assertTrue(equipment.contains("acceptedConsumableRemainder(expected, stack)"));
    assertTrue(equipment.contains("inventory.setItem(slot, null)"));
  }

  private static String controller() throws Exception {
    return Files.readString(root().resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/carry/RaidCarryController.java"));
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
