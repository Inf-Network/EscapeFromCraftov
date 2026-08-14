package com.birditch.escapefromcraftov.lobby;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/// Main-thread gate for Lobby actions that require all PostgreSQL-backed
/// player data to be loaded successfully.
public final class LobbyDataGate {

  private final Set<UUID> readyPlayers = new HashSet<>();
  private final Set<UUID> raidPreparationPlayers = new HashSet<>();
  private final Map<UUID, Long> matchTransferGenerations = new HashMap<>();
  private long nextMatchTransferGeneration;

  public void markReady(UUID playerId) {
    readyPlayers.add(Preconditions.checkNotNull(playerId, "playerId"));
  }

  public boolean isReady(UUID playerId) {
    return readyPlayers.contains(Preconditions.checkNotNull(playerId, "playerId"));
  }

  public void clear(UUID playerId) {
    UUID checked = Preconditions.checkNotNull(playerId, "playerId");
    readyPlayers.remove(checked);
    raidPreparationPlayers.remove(checked);
    matchTransferGenerations.remove(checked);
  }

  /** Atomically freezes a complete party while its authoritative inputs are loaded. */
  public boolean beginRaidPreparation(Collection<UUID> playerIds) {
    Collection<UUID> checked = Preconditions.checkNotNull(playerIds, "playerIds");
    Preconditions.checkArgument(!checked.isEmpty(), "playerIds must not be empty");
    for (UUID playerId : checked) {
      if (isRaidMutationLocked(Preconditions.checkNotNull(playerId, "playerId"))) return false;
    }
    raidPreparationPlayers.addAll(checked);
    return true;
  }

  public void finishRaidPreparation(Collection<UUID> playerIds) {
    Preconditions.checkNotNull(playerIds, "playerIds").forEach(raidPreparationPlayers::remove);
  }

  public boolean isRaidMutationLocked(UUID playerId) {
    UUID checked = Preconditions.checkNotNull(playerId, "playerId");
    return raidPreparationPlayers.contains(checked)
        || matchTransferGenerations.containsKey(checked);
  }

  public boolean isMatchTransferLocked(UUID playerId) {
    return matchTransferGenerations.containsKey(
        Preconditions.checkNotNull(playerId, "playerId"));
  }

  /** Bridges the gap between ticket removal and the player's Proxy quit event. */
  public long beginMatchTransfer(Collection<UUID> playerIds) {
    Collection<UUID> checked = Preconditions.checkNotNull(playerIds, "playerIds");
    Preconditions.checkArgument(!checked.isEmpty(), "playerIds must not be empty");
    long generation = ++nextMatchTransferGeneration;
    checked.forEach(playerId -> matchTransferGenerations.put(
        Preconditions.checkNotNull(playerId, "playerId"), generation));
    return generation;
  }

  public void finishMatchTransfer(Collection<UUID> playerIds, long generation) {
    Preconditions.checkNotNull(playerIds, "playerIds").forEach(playerId ->
        matchTransferGenerations.remove(
            Preconditions.checkNotNull(playerId, "playerId"), generation));
  }
}
