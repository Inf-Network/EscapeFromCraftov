package com.birditch.escapefromcraftov.profile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.profile.PlayerProfile;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryProfileRepository")
class InMemoryProfileRepositoryTest {

  private final InMemoryProfileRepository repository = new InMemoryProfileRepository();
  private final UUID playerId = UUID.randomUUID();

  private PlayerProfile sampleProfile() {
    return new PlayerProfile(playerId, "Steve", Money.of(100), 2, Instant.ofEpochMilli(1_000));
  }

  @Test
  @DisplayName("load returns empty for an unknown player")
  void loadReturnsEmptyForUnknownPlayer() {
    assertTrue(repository.load(playerId).join().isEmpty());
  }

  @Test
  @DisplayName("all futures are already completed")
  void futuresAreAlreadyCompleted() {
    assertTrue(repository.save(sampleProfile()).isDone());
    assertTrue(repository.load(playerId).isDone());
    assertTrue(repository.delete(playerId).isDone());
  }

  @Test
  @DisplayName("save then load round-trips every field")
  void saveThenLoadRoundTrips() {
    repository.save(sampleProfile()).join();
    PlayerProfile loaded = repository.load(playerId).join().orElseThrow();
    assertEquals(playerId, loaded.playerId());
    assertEquals("Steve", loaded.name());
    assertEquals(Money.of(100), loaded.balance());
    assertEquals(2, loaded.level());
    assertEquals(Instant.ofEpochMilli(1_000), loaded.createdAt());
  }

  @Test
  @DisplayName("save stores a copy so later caller mutations do not leak in")
  void saveStoresCopy() {
    PlayerProfile original = sampleProfile();
    repository.save(original).join();
    original.setBalance(Money.of(999));
    original.rename("Alex");
    PlayerProfile stored = repository.load(playerId).join().orElseThrow();
    assertEquals(Money.of(100), stored.balance());
    assertEquals("Steve", stored.name());
  }

  @Test
  @DisplayName("load returns a fresh copy so mutations do not leak back")
  void loadReturnsFreshCopy() {
    repository.save(sampleProfile()).join();
    PlayerProfile first = repository.load(playerId).join().orElseThrow();
    first.setBalance(Money.of(777));
    PlayerProfile second = repository.load(playerId).join().orElseThrow();
    assertNotSame(first, second);
    assertEquals(Money.of(100), second.balance());
  }

  @Test
  @DisplayName("delete removes the stored profile and tolerates unknown players")
  void deleteRemovesProfile() {
    repository.save(sampleProfile()).join();
    repository.delete(playerId).join();
    assertTrue(repository.load(playerId).join().isEmpty());
    assertDoesNotThrow(() -> repository.delete(playerId).join());
  }
}
