package com.birditch.escapefromcraftov.game.plugin.ownership;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LootOwnershipWiringBoundaryTest {

  private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();

  @Test
  void capacityGuardRunsBeforeOwnershipCanClearPhysicalDropTags() throws IOException {
    String application = read(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/GameApplication.java");
    int carry = application.indexOf("registerEvents(wiring.carry(), plugin)");
    int downed = application.indexOf("registerEvents(wiring.downedPlayers(), plugin)");
    int bridge = application.indexOf("registerEvents(wiring.bridge(), plugin)");
    int ownership = application.indexOf("registerEvents(wiring.lootOwnership(), plugin)");

    assertTrue(carry >= 0 && downed > carry && bridge > downed && ownership > bridge,
        "all same-priority pickup guards must run before ownership's final commit");
  }

  @Test
  void combatWritesClaimBeforeDeathCrateMonitorAndAiRegistersSpawnedItems() throws IOException {
    String combat = read(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/listener/GameCombatListener.java");
    String application = read(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/GameApplication.java");
    String crates = read(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/deathcrate/DeathCrateListener.java");
    String ownership = read(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/ownership/LootOwnershipController.java");

    int combatDeath = combat.indexOf("public void onDeath(PlayerDeathEvent event)");
    int combatPriority = combat.lastIndexOf("EventPriority.HIGHEST", combatDeath);
    int crateDeath = crates.indexOf("public void onDeath(PlayerDeathEvent event)");
    int cratePriority = crates.lastIndexOf("EventPriority.MONITOR", crateDeath);
    assertTrue(combatPriority >= 0 && combatDeath - combatPriority < 300);
    assertTrue(cratePriority >= 0 && crateDeath - cratePriority < 300);
    assertTrue(combat.contains("public void onPlayerRevived(PlayerRevivedEvent event)"));
    assertTrue(combat.contains(
        "public void onEffectiveFriendlyDamage(EntityDamageByEntityEvent event)"));
    assertTrue(combat.contains("public void onPlayerDowned(PlayerDownedEvent event)"));
    int friendlyMonitor = combat.lastIndexOf(
        "EventPriority.MONITOR, ignoreCancelled = false",
        combat.indexOf("public void onEffectiveFriendlyDamage"));
    assertTrue(friendlyMonitor >= 0);
    assertTrue(application.contains(
        "combatSubscription = context.eventBus().register(combatListener)"));
    assertTrue(ownership.contains("public void onItemSpawn(ItemSpawnEvent event)"));
    assertTrue(ownership.contains("physicalClaims.put(event.getEntity().getUniqueId(), claim)"));
    assertTrue(ownership.contains("public void onInventoryPickup(InventoryPickupItemEvent event)"));
    assertTrue(ownership.contains("if (!(event.getEntity() instanceof Player player))"));
    assertTrue(ownership.contains("public void onPickupCommitted(EntityPickupItemEvent event)"));
    assertTrue(ownership.contains("EventPriority.MONITOR, ignoreCancelled = true"));
  }

  private static String read(String path) throws IOException {
    return Files.readString(REPOSITORY.resolve(path));
  }
}
