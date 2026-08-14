package com.birditch.escapefromcraftov.game.plugin.carry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RaidCarryGridWiringBoundaryTest {

  @Test
  void everyVirtualLootSourceUsesTheAtomicGridTransactionInsteadOfVanillaInsertion()
      throws Exception {
    for (String path : new String[] {
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/loot/CraftovLootListener.java",
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/deathcrate/DeathCrateListener.java",
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/secure/SecureContainerController.java"
    }) {
      String source = Files.readString(root().resolve(path));
      assertTrue(source.contains("carry.transferIntoCargo("), path);
      assertFalse(source.contains("getInventory().addItem("), path);
    }
  }

  @Test
  void settlementReconcilesAnchorsAndDropAndSecureHaveAuthoritativeRemovalPaths()
      throws Exception {
    String source = Files.readString(root().resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/carry/RaidCarryController.java"));
    String refresh = method(source, "public RaidCarrySnapshot refresh", "public RaidCarrySnapshot settlementSnapshot");
    assertTrue(refresh.contains("synchronizePhysical(player, state)"));
    assertTrue(source.contains("dropCargoAnchor(player, state"));
    assertTrue(source.contains("dropContainer(player, state"));
    assertTrue(source.contains("public CargoRemoval transferOutOfCargo"));
    assertTrue(source.contains("!data.has(CraftovItemStacks.ITEM_ID_KEY"),
        "ghost/lock projections must never carry a real Craftov identity");
    String ghost = method(source, "private ItemStack ghost()", "public boolean placeholder");
    assertFalse(ghost.contains("UUID"), "ghosts must not copy an anchor identity under another key");
    assertTrue(source.contains("@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)\n"
        + "  public void onInventoryDropCommit"));
    assertTrue(source.contains("@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)\n"
        + "  public void onDropGuard"));
    assertTrue(source.contains("@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)\n"
        + "  public void onDropCommit"));

    String cargoDrop = method(source, "private boolean dropCargoAnchor", "private boolean dropEquipped");
    String containerDrop = method(source, "private boolean dropContainer", "private Optional<LocatedPlacement>");
    assertTrue(cargoDrop.indexOf("applySnapshot(player, snapshot)")
        < cargoDrop.indexOf("dropItemNaturally"));
    assertTrue(cargoDrop.contains("assignFreshIdentity(dropped)"),
        "DROP_ONE must give the separated ground stack a fresh durable identity");
    assertTrue(containerDrop.indexOf("applySnapshot(player, snapshot)")
        < containerDrop.indexOf("dropItemNaturally"));
  }

  @Test
  void activeCarryOwnsPartialDropIdentitySplitting() throws Exception {
    String source = Files.readString(root().resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/instance/InstanceWorldBridge.java"));
    String dropHandler = method(source, "public void onCraftovItemDrop", "public void onCraftovDeathDrops");
    assertTrue(dropHandler.contains("deps.carry().active(event.getPlayer().getUniqueId())"));
  }

  @Test
  void namedEquipmentAuthorityComesFromDurableRowsAndEquipmentTransferHasTwoPhases()
      throws Exception {
    String source = Files.readString(root().resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/carry/RaidCarryController.java"));
    String install = method(source, "public void install", "/** Rebuilds");
    assertTrue(install.contains("expectedEquipmentBaselines(claimed)"));
    String baselines = method(source, "private Map<Integer, ItemInstance> expectedEquipmentBaselines",
        "private void captureLegacyEquipment");
    assertTrue(baselines.contains("new ItemInstance(item.instanceId(), item.itemId()"));
    assertFalse(baselines.contains("scanSingle(player"),
        "named rows must not trust a possibly altered Bukkit stack as their authority");

    String transfer = method(source, "public CargoRemoval transferOutOfEquipment",
        "private void restoreEquipmentTransfer");
    assertTrue(transfer.indexOf("projected = true")
        < transfer.indexOf("state.expectedEquipment.remove(slot)"));
    assertTrue(transfer.indexOf("projected = false", transfer.indexOf("if (!committed)"))
        < transfer.indexOf("applySnapshot(player, afterSnapshot)"));
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
