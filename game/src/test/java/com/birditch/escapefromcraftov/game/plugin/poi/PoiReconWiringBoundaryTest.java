package com.birditch.escapefromcraftov.game.plugin.poi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class PoiReconWiringBoundaryTest {

  @Test
  void applicationOwnsRegistrationStartupHudProjectionAndShutdown() throws IOException {
    String application = read("src/main/java/com/birditch/escapefromcraftov/game/plugin/"
        + "GameApplication.java");

    assertTrue(application.contains("new PoiReconRuntime(games, index, scheduler"));
    assertTrue(application.contains("context.eventBus().register(reconRuntime)"));
    assertTrue(application.contains("reconRuntime.start()"));
    assertTrue(application.contains("extractionRoutes,\n        reconRuntime, configs.messages()"));
    String stop = between(application, "private void stopPoiReconRuntime()", "private void saveAllProfiles");
    assertTrue(stop.contains("poiReconSubscription.unsubscribe()"));
    assertTrue(stop.contains("poiReconRuntime.close()"));
  }

  @Test
  void oneBoundedTimerSamplesLocationsWithoutMovementEvents() throws IOException {
    String runtime = read("src/main/java/com/birditch/escapefromcraftov/game/plugin/poi/"
        + "PoiReconRuntime.java");
    assertTrue(runtime.contains("MAX_PLAYER_CHECKS_PER_TICK = 128"));
    assertEquals(1, occurrences(runtime, "runTimer("));
    assertTrue(runtime.contains("player.getLocation()"));
    assertTrue(runtime.contains("player.isOnGround()"));

    Path poiPackage = source("src/main/java/com/birditch/escapefromcraftov/game/plugin/poi");
    try (var files = Files.list(poiPackage)) {
      assertFalse(files.filter(path -> path.toString().endsWith(".java")).map(path -> {
        try {
          return Files.readString(path);
        } catch (IOException failure) {
          throw new IllegalStateException(failure);
        }
      }).anyMatch(source -> source.contains("PlayerMoveEvent")));
    }
  }

  private static int occurrences(String text, String fragment) {
    int count = 0;
    for (int offset = 0; (offset = text.indexOf(fragment, offset)) >= 0;
         offset += fragment.length()) count++;
    return count;
  }

  private static String between(String source, String start, String end) {
    int first = source.indexOf(start);
    int last = source.indexOf(end, first + start.length());
    assertTrue(first >= 0 && last > first);
    return source.substring(first, last);
  }

  private static String read(String relative) throws IOException {
    return Files.readString(source(relative));
  }

  private static Path source(String relative) {
    Path current = Path.of("").toAbsolutePath();
    Path local = current.resolve(relative);
    return Files.exists(local) ? local : current.resolve("game").resolve(relative);
  }
}
