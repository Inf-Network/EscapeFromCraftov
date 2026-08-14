package com.birditch.escapefromcraftov.game.plugin.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InstanceWorldIndexTest {

  @Test
  void playerGroupsAreIndependentAndUnknownPlayersDefaultToLobby() {
    InstanceWorldIndex index = new InstanceWorldIndex();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();

    assertEquals("lobby", index.groupOf(first));
    index.setGroup(first, "game:a1b2c3");
    index.setGroup(second, "game:d4e5f6");

    assertEquals("game:a1b2c3", index.groupOf(first));
    assertEquals("game:d4e5f6", index.groupOf(second));
    index.clearGroup(first);
    assertEquals("lobby", index.groupOf(first));
  }

  @Test
  void everyRoomKeepsItsOwnWorldAndReadyState() {
    InstanceWorldIndex index = new InstanceWorldIndex();
    CraftovGameId first = CraftovGameId.of("a1b2c3");
    CraftovGameId second = CraftovGameId.of("d4e5f6");

    index.register(first, "craftov_a1b2c3");
    index.register(second, "craftov_d4e5f6");
    index.markReady(first);

    assertEquals("craftov_a1b2c3", index.worldOf(first).orElseThrow());
    assertEquals(first, index.gameByWorld("craftov_a1b2c3").orElseThrow());
    assertEquals(second, index.gameByWorld("craftov_d4e5f6").orElseThrow());
    assertTrue(index.isReady(first));
    assertFalse(index.isReady(second));
    assertEquals(2, index.gameIds().size());
  }

  @Test
  void removingOneRoomCannotDamageAnotherRoom() {
    InstanceWorldIndex index = new InstanceWorldIndex();
    CraftovGameId first = CraftovGameId.of("a1b2c3");
    CraftovGameId second = CraftovGameId.of("d4e5f6");
    index.register(first, "craftov_a1b2c3");
    index.register(second, "craftov_d4e5f6");
    index.markReady(first);
    index.markReady(second);

    index.remove(first);

    assertTrue(index.worldOf(first).isEmpty());
    assertTrue(index.gameByWorld("craftov_a1b2c3").isEmpty());
    assertFalse(index.isReady(first));
    assertEquals("craftov_d4e5f6", index.worldOf(second).orElseThrow());
    assertTrue(index.isReady(second));
  }
}
