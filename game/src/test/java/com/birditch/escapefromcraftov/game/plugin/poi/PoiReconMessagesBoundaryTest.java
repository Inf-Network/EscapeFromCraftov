package com.birditch.escapefromcraftov.game.plugin.poi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class PoiReconMessagesBoundaryTest {

  private static final Pattern TOP_LEVEL = Pattern.compile("^([a-z0-9-]+):(?:\\s.*)?$");
  private static final Pattern DIRECT_CHILD = Pattern.compile("^  ([a-z0-9-]+):(?:\\s.*)?$");

  @Test
  void poiCatalogIsTopLevelWithoutStealingWaitingOrStartMessages() throws IOException {
    Set<String> keys = directKeys(Files.readAllLines(resource("messages.yml")));

    assertTrue(keys.contains("poi.recon-completed"));
    assertTrue(keys.contains("game.waiting-arrival-timeout"));
    assertTrue(keys.contains("game.start-countdown-title"));
    assertTrue(keys.contains("game.start-countdown-subtitle"));
    assertTrue(keys.contains("game.start-countdown-actionbar"));
    assertTrue(keys.contains("game.start-cancelled-title"));
    assertTrue(keys.contains("game.start-cancelled-subtitle"));
    assertTrue(keys.contains("game.start-cancelled-actionbar"));
    assertTrue(keys.contains("game.start-title"));
    assertTrue(keys.contains("game.start-subtitle"));
    assertTrue(keys.contains("game.start-actionbar"));
    assertFalse(keys.contains("poi.waiting-arrival-timeout"));
    assertFalse(keys.contains("poi.start-title"));
  }

  private static Set<String> directKeys(Iterable<String> lines) {
    Set<String> result = new HashSet<>();
    String section = "";
    for (String line : lines) {
      Matcher top = TOP_LEVEL.matcher(line);
      if (top.matches()) {
        section = top.group(1);
        continue;
      }
      Matcher child = DIRECT_CHILD.matcher(line);
      if (!section.isEmpty() && child.matches()) {
        result.add(section + '.' + child.group(1));
      }
    }
    return result;
  }

  private static Path resource(String name) {
    Path module = Path.of("").toAbsolutePath();
    Path local = module.resolve("src/main/resources").resolve(name);
    return Files.exists(local) ? local
        : module.resolve("game/src/main/resources").resolve(name);
  }
}
