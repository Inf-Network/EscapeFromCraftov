package com.birditch.escapefromcraftov.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.profile.ProgressionCurve;
import com.birditch.escapefromcraftov.api.profile.ProgressionUpdate;
import com.birditch.escapefromcraftov.api.testing.ManualScheduler;
import com.birditch.escapefromcraftov.api.testing.MutableTimeSource;
import com.birditch.escapefromcraftov.common.result.Result;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class DefaultProgressionServiceTest {

  private final MutableTimeSource time = new MutableTimeSource(1_000L);
  private final ManualScheduler scheduler = new ManualScheduler();
  private final DefaultProfileService profiles = new DefaultProfileService(
      new InMemoryProfileRepository(), time, Logger.getAnonymousLogger());
  private final InMemoryProgressionRepository repository = new InMemoryProgressionRepository();
  private final DefaultProgressionService progression = new DefaultProgressionService(
      profiles, repository, scheduler, time);

  @Test
  void commitsExperienceAndSynchronizesTheOnlineProfileCache() {
    UUID playerId = loadedPlayer();

    ProgressionUpdate update = progression.award(UUID.randomUUID(), playerId, 100,
        "成功撤离").join().value().orElseThrow();

    assertEquals(1, update.levelBefore());
    assertEquals(2, update.levelAfter());
    assertTrue(update.levelledUp());
    assertEquals(100, profiles.require(playerId).experience());
    assertEquals(2, profiles.require(playerId).level());
  }

  @Test
  void repeatedTransactionIdDoesNotAwardTwice() {
    UUID playerId = loadedPlayer();
    UUID transactionId = UUID.randomUUID();

    ProgressionUpdate first = progression.award(transactionId, playerId, 250, "撤离")
        .join().value().orElseThrow();
    ProgressionUpdate repeated = progression.award(transactionId, playerId, 250, "撤离")
        .join().value().orElseThrow();

    assertEquals(first, repeated);
    assertEquals(250, profiles.require(playerId).experience());
    assertEquals(1, repository.transactions.size());
  }

  @Test
  void rejectsOffMainThreadInitiation() {
    UUID playerId = loadedPlayer();
    scheduler.setMainThread(false);

    assertThrows(IllegalStateException.class, () -> progression.award(
        UUID.randomUUID(), playerId, 25, "参与行动"));
  }

  private UUID loadedPlayer() {
    UUID playerId = UUID.randomUUID();
    profiles.load(playerId, "Steve").join();
    repository.experience.put(playerId, 0L);
    return playerId;
  }

  private static final class InMemoryProgressionRepository implements ProgressionRepository {
    private final Map<UUID, Long> experience = new LinkedHashMap<>();
    private final Map<UUID, ProgressionUpdate> transactions = new LinkedHashMap<>();

    @Override
    public CompletableFuture<Result<ProgressionUpdate>> award(UUID transactionId, UUID playerId,
        long awarded, String reason, Instant timestamp) {
      ProgressionUpdate existing = transactions.get(transactionId);
      if (existing != null) return CompletableFuture.completedFuture(Result.ok(existing));
      long beforeExperience = experience.getOrDefault(playerId, 0L);
      int beforeLevel = ProgressionCurve.levelForExperience(beforeExperience);
      long afterExperience = beforeExperience + awarded;
      int afterLevel = ProgressionCurve.levelForExperience(afterExperience);
      ProgressionUpdate update = new ProgressionUpdate(transactionId, playerId, awarded,
          afterExperience, beforeLevel, afterLevel, reason, timestamp);
      experience.put(playerId, afterExperience);
      transactions.put(transactionId, update);
      return CompletableFuture.completedFuture(Result.ok(update));
    }
  }
}
