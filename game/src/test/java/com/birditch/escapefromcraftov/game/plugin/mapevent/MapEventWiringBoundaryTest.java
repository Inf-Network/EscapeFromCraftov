package com.birditch.escapefromcraftov.game.plugin.mapevent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MapEventWiringBoundaryTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

  @Test
  void bossOwnershipChecksGameZoneEventAndMultipliers() throws IOException {
    String ai = read("game/src/main/java/com/birditch/escapefromcraftov/game/plugin/ai/CraftovAiRuntime.java");
    String owned = method(ai, "private @Nullable AgentHandle ownedAgent", "private boolean taggedAi");
    assertTrue(owned.contains("ownedBy(mob, handle.runtime().gameId())"));
    assertTrue(owned.contains("get(zoneKey, PersistentDataType.STRING)"));
    assertTrue(owned.contains("ownedBossEvent(handle, mob)"));
    assertTrue(owned.contains("MapEventBossPolicy.matchesPdc"));
    assertTrue(owned.contains("eventRuntime.ownsActive"));
  }

  @Test
  void everyLootPhaseRechecksEventCacheIncludingAtomicCommit() throws IOException {
    String loot = read(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/loot/CraftovLootListener.java");
    assertTrue(method(loot, "private void beginSearch", "private void tickSearch")
        .contains("cacheAllowed"));
    assertTrue(method(loot, "private void tickSearch", "private void finishSearch")
        .contains("cacheAllowed"));
    assertTrue(method(loot, "private void finishSearch", "private void cancelSearch")
        .contains("cacheAllowed"));
    assertTrue(method(loot, "private void tickReveal", "private void completeReveal")
        .contains("validViewLocation"));
    assertTrue(method(loot, "private void open(Player", "private ItemStack menuItem")
        .contains("cacheAllowed"));
    assertTrue(method(loot, "private ItemStack menuItem", "private void refreshViewers")
        .contains("cacheAllowed"));
    assertTrue(method(loot, "private ValidatedView validateView", "private boolean validViewLocation")
        .contains("validViewLocation"));
    assertTrue(method(loot, "public void take", "public void page")
        .contains("cacheAllowed(validated.game(), validated.point(), player, false)"));
  }

  @Test
  void eventZoneNeverUsesOrdinaryRefillAndMessagesStayInsideTheGame() throws IOException {
    String ai = read("game/src/main/java/com/birditch/escapefromcraftov/game/plugin/ai/CraftovAiRuntime.java");
    assertTrue(ai.contains("if (!eventOnly) enqueueRefill(runtime, zone)"));
    assertTrue(ai.contains("if (zone.eventOnly() || zone.refillTask != null"));
    String death = method(ai, "public void onAiDeath", "private void reward");
    int boss = death.indexOf("if (handle.eventId() != null)");
    int refill = death.indexOf("scheduleRefill", boss);
    int bossReturn = death.indexOf("return;", boss);
    assertTrue(boss >= 0 && bossReturn >= 0 && refill > bossReturn,
        "Boss completion exits before the ordinary respawn path");
    String announce = method(ai, "private void announce", "@Override\n  public void close");
    assertTrue(announce.contains("for (CraftovPlayer participant : game.players())"));
    assertTrue(announce.contains("runtime.worldName().equals(online.getWorld().getName())"));
  }

  @Test
  void applicationRegistersEventStateBeforeStartingCoordinatorAndAi() throws IOException {
    String application = read(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/GameApplication.java");
    int events = application.indexOf("mapEventSubscription = context.eventBus().register(eventRuntime)");
    int starts = application.indexOf("starts.start()", events);
    int ai = application.indexOf("aiSubscription = context.eventBus().register(instanceAi)", starts);
    assertTrue(events >= 0 && starts > events && ai > starts);
    assertTrue(application.contains("mapEventRuntime.close()"));
  }

  private static String read(String path) throws IOException {
    return Files.readString(ROOT.resolve(path));
  }

  private static String method(String source, String start, String next) {
    int from = source.indexOf(start);
    int to = source.indexOf(next, from + start.length());
    return source.substring(from, to);
  }
}
