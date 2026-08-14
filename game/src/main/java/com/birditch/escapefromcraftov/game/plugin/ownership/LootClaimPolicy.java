package com.birditch.escapefromcraftov.game.plugin.ownership;

import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovTeamRules;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Pure claim construction/access policy kept free of Bukkit runtime state. */
final class LootClaimPolicy {

  private LootClaimPolicy() {}

  static LootClaim forPlayerDeath(CraftovGame game, UUID victimId, UUID attributedKiller,
      long nowMillis, long protectionMillis) {
    return attributedKiller == null || attributedKiller.equals(victimId)
        || CraftovTeamRules.sameTeam(game, attributedKiller, victimId)
        ? publicClaim(game.id(), nowMillis)
        : forKiller(game, attributedKiller, nowMillis, protectionMillis);
  }

  static LootClaim forKiller(CraftovGame game, UUID killerId, long nowMillis,
      long protectionMillis) {
    CraftovPlayer killer = game.players().stream()
        .filter(player -> player.playerId().equals(killerId) && !player.extracted())
        .findFirst().orElse(null);
    if (killer == null) return publicClaim(game.id(), nowMillis);
    Set<UUID> priority = new LinkedHashSet<>();
    if (killer.teamId().isPresent()) {
      int teamId = killer.teamId().getAsInt();
      game.players().stream()
          .filter(player -> player.teamId().isPresent()
              && player.teamId().getAsInt() == teamId)
          .map(CraftovPlayer::playerId)
          .forEach(priority::add);
    }
    priority.add(killerId);
    return new LootClaim(game.id(), priority, nowMillis + protectionMillis);
  }

  static LootClaim publicClaim(CraftovGameId gameId, long nowMillis) {
    return new LootClaim(gameId, Set.of(), nowMillis);
  }

  static LootAccess access(CraftovGame game, UUID playerId, LootClaim claim, long nowMillis) {
    boolean member = game.state() == CraftovGameState.RUNNING
        && game.id().equals(claim.gameId())
        && game.players().stream().anyMatch(player -> player.playerId().equals(playerId)
            && !player.extracted());
    if (!member) return new LootAccess(LootAccess.Kind.NOT_ACCESSIBLE, 0);
    if (!claim.protectedAt(nowMillis)) return new LootAccess(LootAccess.Kind.PUBLIC, 0);
    int seconds = (int) Math.max(1,
        Math.ceil((claim.unlockAtMillis() - nowMillis) / 1000.0));
    return new LootAccess(claim.priorityPlayers().contains(playerId)
        ? LootAccess.Kind.PRIORITY : LootAccess.Kind.PROTECTED, seconds);
  }
}
