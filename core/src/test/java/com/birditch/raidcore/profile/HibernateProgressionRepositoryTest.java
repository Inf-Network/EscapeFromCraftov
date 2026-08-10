package com.birditch.raidcore.profile;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.birditch.raidcore.core.database.Database;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

class HibernateProgressionRepositoryTest {

  @Test
  void rejectsInvalidAwardsBeforeSchedulingDatabaseWork() {
    CountingDatabase database = new CountingDatabase();
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      HibernateProgressionRepository repository =
          new HibernateProgressionRepository(database, executor);
      UUID transactionId = UUID.randomUUID();
      UUID playerId = UUID.randomUUID();
      Instant timestamp = Instant.now();

      assertThrows(NullPointerException.class,
          () -> repository.award(null, playerId, 10, "行动奖励", timestamp));
      assertThrows(NullPointerException.class,
          () -> repository.award(transactionId, null, 10, "行动奖励", timestamp));
      assertThrows(IllegalArgumentException.class,
          () -> repository.award(transactionId, playerId, 0, "行动奖励", timestamp));
      assertThrows(IllegalArgumentException.class,
          () -> repository.award(transactionId, playerId, -1, "行动奖励", timestamp));
      assertThrows(NullPointerException.class,
          () -> repository.award(transactionId, playerId, 10, null, timestamp));
      assertThrows(IllegalArgumentException.class,
          () -> repository.award(transactionId, playerId, 10, "  ", timestamp));
      assertThrows(NullPointerException.class,
          () -> repository.award(transactionId, playerId, 10, "行动奖励", null));

      org.junit.jupiter.api.Assertions.assertEquals(0, database.calls);
    }
  }

  private static final class CountingDatabase implements Database {
    private int calls;

    @Override
    public <T> T transaction(java.util.function.Function<Session, T> work) {
      calls++;
      throw new AssertionError("invalid award reached the database");
    }

    @Override
    public void close() {}
  }
}
