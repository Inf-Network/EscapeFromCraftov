package com.birditch.raidcore.profile;

import com.birditch.raidcore.api.economy.Money;
import com.birditch.raidcore.api.profile.PlayerProfile;
import com.birditch.raidcore.api.profile.ProfileException;
import com.birditch.raidcore.api.profile.ProfileRepository;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.database.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/// PostgreSQL [ProfileRepository] over plain JDBC.
///
/// All statements run on the injected IO executor (virtual threads). Profile
/// fields are snapshotted on the calling thread before going async, so IO never
/// reads the mutable [PlayerProfile] off-thread. SQL failures surface as
/// [ProfileException].
public final class PostgresProfileRepository implements ProfileRepository {

  private static final String CREATE_TABLE_SQL =
      """
      CREATE TABLE IF NOT EXISTS raid_profiles (
        player_id UUID PRIMARY KEY,
        name TEXT NOT NULL,
        balance BIGINT NOT NULL,
        level INT NOT NULL,
        created_at TIMESTAMPTZ NOT NULL
      )
      """;
  private static final String SELECT_SQL =
      "SELECT name, balance, level, created_at FROM raid_profiles WHERE player_id = ?";
  private static final String UPSERT_SQL =
      """
      INSERT INTO raid_profiles (player_id, name, balance, level, created_at)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (player_id)
      DO UPDATE SET name = EXCLUDED.name, balance = EXCLUDED.balance, level = EXCLUDED.level
      """;
  private static final String DELETE_SQL = "DELETE FROM raid_profiles WHERE player_id = ?";

  private final Database database;
  private final ExecutorService ioExecutor;

  public PostgresProfileRepository(Database database, ExecutorService ioExecutor) {
    this.database = Preconditions.checkNotNull(database, "database");
    this.ioExecutor = Preconditions.checkNotNull(ioExecutor, "ioExecutor");
  }

  /// Creates the `raid_profiles` table when missing. Blocking; call once at boot
  /// before serving any requests.
  public void initSchema() {
    try (Connection connection = database.connection();
        Statement statement = connection.createStatement()) {
      statement.execute(CREATE_TABLE_SQL);
    } catch (SQLException e) {
      throw new ProfileException("Failed to initialize profile schema", e);
    }
  }

  @Override
  public CompletableFuture<Optional<PlayerProfile>> load(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return CompletableFuture.supplyAsync(() -> selectProfile(playerId), ioExecutor);
  }

  @Override
  public CompletableFuture<Void> save(PlayerProfile profile) {
    Preconditions.checkNotNull(profile, "profile");
    ProfileRow row =
        new ProfileRow(
            profile.playerId(),
            profile.name(),
            profile.balance().units(),
            profile.level(),
            profile.createdAt());
    return CompletableFuture.runAsync(() -> upsert(row), ioExecutor);
  }

  @Override
  public CompletableFuture<Void> delete(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return CompletableFuture.runAsync(() -> deleteRow(playerId), ioExecutor);
  }

  private Optional<PlayerProfile> selectProfile(UUID playerId) {
    try (Connection connection = database.connection();
        PreparedStatement statement = connection.prepareStatement(SELECT_SQL)) {
      statement.setObject(1, playerId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return Optional.empty();
        }
        return Optional.of(readProfile(playerId, resultSet));
      }
    } catch (SQLException e) {
      throw new ProfileException("Failed to load profile " + playerId, e);
    }
  }

  private static PlayerProfile readProfile(UUID playerId, ResultSet resultSet) throws SQLException {
    return new PlayerProfile(
        playerId,
        resultSet.getString("name"),
        Money.of(resultSet.getLong("balance")),
        resultSet.getInt("level"),
        resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private void upsert(ProfileRow row) {
    try (Connection connection = database.connection();
        PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
      statement.setObject(1, row.playerId());
      statement.setString(2, row.name());
      statement.setLong(3, row.balance());
      statement.setInt(4, row.level());
      statement.setObject(5, OffsetDateTime.ofInstant(row.createdAt(), ZoneOffset.UTC));
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new ProfileException("Failed to save profile " + row.playerId(), e);
    }
  }

  private void deleteRow(UUID playerId) {
    try (Connection connection = database.connection();
        PreparedStatement statement = connection.prepareStatement(DELETE_SQL)) {
      statement.setObject(1, playerId);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new ProfileException("Failed to delete profile " + playerId, e);
    }
  }

  /// Immutable field snapshot captured on the caller thread before async IO.
  private record ProfileRow(UUID playerId, String name, long balance, int level, Instant createdAt) {}
}
