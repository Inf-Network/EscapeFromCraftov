package com.birditch.escapefromcraftov.profile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.profile.PlayerProfile;
import com.birditch.escapefromcraftov.api.profile.ProfileException;
import com.birditch.escapefromcraftov.api.profile.ProfileRepository;
import com.birditch.escapefromcraftov.api.testing.MutableTimeSource;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultProfileService")
class DefaultProfileServiceTest {

  private static final long START_MILLIS = 1_000_000L;

  private final InMemoryProfileRepository repository = new InMemoryProfileRepository();
  private final MutableTimeSource timeSource = new MutableTimeSource(START_MILLIS);
  private final DefaultProfileService service =
      new DefaultProfileService(repository, timeSource, quietLogger());
  private final UUID playerId = UUID.randomUUID();

  private static Logger quietLogger() {
    Logger logger = Logger.getLogger(DefaultProfileServiceTest.class.getName());
    logger.setUseParentHandlers(false);
    return logger;
  }

  @Nested
  @DisplayName("load")
  class Load {

    @Test
    @DisplayName("creates a default profile when the repository has none")
    void createsDefaultWhenAbsent() {
      PlayerProfile profile = service.load(playerId, "Steve").join();
      assertEquals("Steve", profile.name());
      assertEquals(Money.ZERO, profile.balance());
      assertEquals(1, profile.level());
      assertEquals(Instant.ofEpochMilli(START_MILLIS), profile.createdAt());
      assertEquals(1, service.cachedCount());
    }

    @Test
    @DisplayName("returns the stored profile and caches it")
    void returnsStoredProfile() {
      repository
          .save(new PlayerProfile(playerId, "Steve", Money.of(500), 3, Instant.ofEpochMilli(42)))
          .join();
      PlayerProfile profile = service.load(playerId, "Steve").join();
      assertEquals(Money.of(500), profile.balance());
      assertEquals(3, profile.level());
      assertEquals(Instant.ofEpochMilli(42), profile.createdAt());
      assertSame(profile, service.require(playerId));
    }

    @Test
    @DisplayName("renames the profile when the player name changed")
    void renamesOnNameChange() {
      repository
          .save(new PlayerProfile(playerId, "OldName", Money.of(500), 3, Instant.ofEpochMilli(42)))
          .join();
      PlayerProfile profile = service.load(playerId, "NewName").join();
      assertEquals("NewName", profile.name());
    }
  }

  @Nested
  @DisplayName("cache access")
  class CacheAccess {

    @Test
    @DisplayName("find is empty and require throws before load")
    void missBeforeLoad() {
      assertTrue(service.find(playerId).isEmpty());
      ProfileException exception =
          assertThrows(ProfileException.class, () -> service.require(playerId));
      assertEquals("Profile not loaded: " + playerId, exception.getMessage());
    }

    @Test
    @DisplayName("find and require return the cached instance after load")
    void hitAfterLoad() {
      PlayerProfile profile = service.load(playerId, "Steve").join();
      assertSame(profile, service.find(playerId).orElseThrow());
      assertSame(profile, service.require(playerId));
    }
  }

  @Nested
  @DisplayName("persistence")
  class Persistence {

    @Test
    @DisplayName("save persists the cached profile")
    void savePersistsCachedProfile() {
      PlayerProfile profile = service.load(playerId, "Steve").join();
      profile.setBalance(Money.of(750));
      service.save(playerId).join();
      assertEquals(Money.of(750), repository.load(playerId).join().orElseThrow().balance());
    }

    @Test
    @DisplayName("save of an unloaded player completes without touching the repository")
    void saveAbsentIsNoOp() {
      assertDoesNotThrow(() -> service.save(playerId).join());
      assertTrue(repository.load(playerId).join().isEmpty());
    }

    @Test
    @DisplayName("unload saves then evicts")
    void unloadSavesThenEvicts() {
      PlayerProfile profile = service.load(playerId, "Steve").join();
      profile.setBalance(Money.of(321));
      service.unload(playerId).join();
      assertTrue(service.find(playerId).isEmpty());
      assertEquals(0, service.cachedCount());
      assertEquals(Money.of(321), repository.load(playerId).join().orElseThrow().balance());
    }

    @Test
    @DisplayName("unload evicts even when the save fails")
    void unloadEvictsOnSaveFailure() {
      DefaultProfileService failing =
          new DefaultProfileService(new FailingSaveRepository(), timeSource, quietLogger());
      failing.load(playerId, "Steve").join();
      CompletableFuture<Void> unload = failing.unload(playerId);
      assertThrows(CompletionException.class, unload::join);
      assertEquals(0, failing.cachedCount());
    }

    @Test
    @DisplayName("stale unload callback does not evict a freshly re-loaded profile")
    void staleUnloadKeepsFreshlyReloadedProfile() {
      ControllableProfileRepository controllable = new ControllableProfileRepository();
      DefaultProfileService racing =
          new DefaultProfileService(controllable, timeSource, quietLogger());
      PlayerProfile first = racing.load(playerId, "Steve").join();
      CompletableFuture<Void> unload = racing.unload(playerId);
      PlayerProfile second = racing.load(playerId, "Steve").join();
      assertNotSame(first, second);

      controllable.completeSave();
      unload.join();

      assertSame(second, racing.find(playerId).orElseThrow());
      assertEquals(1, racing.cachedCount());
    }

    @Test
    @DisplayName("saveAll persists every cached profile and keeps the cache")
    void saveAllPersistsEverything() {
      UUID second = UUID.randomUUID();
      service.load(playerId, "Steve").join().setBalance(Money.of(10));
      service.load(second, "Alex").join().setBalance(Money.of(20));
      service.saveAll().join();
      assertEquals(Money.of(10), repository.load(playerId).join().orElseThrow().balance());
      assertEquals(Money.of(20), repository.load(second).join().orElseThrow().balance());
      assertEquals(2, service.cachedCount());
    }
  }

  /// Repository double whose save future is completed manually, letting tests
  /// interleave cache operations with an in-flight save. Loads always miss, so
  /// every [DefaultProfileService#load] caches a fresh instance.
  private static final class ControllableProfileRepository implements ProfileRepository {

    private final CompletableFuture<Void> saveFuture = new CompletableFuture<>();

    @Override
    public CompletableFuture<Optional<PlayerProfile>> load(UUID playerId) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<Void> save(PlayerProfile profile) {
      return saveFuture;
    }

    @Override
    public CompletableFuture<Void> delete(UUID playerId) {
      return CompletableFuture.completedFuture(null);
    }

    void completeSave() {
      saveFuture.complete(null);
    }
  }

  private static final class FailingSaveRepository implements ProfileRepository {

    @Override
    public CompletableFuture<Optional<PlayerProfile>> load(UUID playerId) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<Void> save(PlayerProfile profile) {
      return CompletableFuture.failedFuture(new ProfileException("save rejected"));
    }

    @Override
    public CompletableFuture<Void> delete(UUID playerId) {
      return CompletableFuture.completedFuture(null);
    }
  }
}
