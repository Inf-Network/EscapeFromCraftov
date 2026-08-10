package com.birditch.raidcore.api.game;

import java.util.UUID;

/// Pure team relationship rules shared by Paper listeners and tests.
public final class RaidTeamRules {
  private RaidTeamRules() {}

  public static boolean sameTeam(RaidGame game, UUID first, UUID second) {
    RaidPlayer left = game.players().stream()
        .filter(player -> player.playerId().equals(first)).findFirst().orElse(null);
    RaidPlayer right = game.players().stream()
        .filter(player -> player.playerId().equals(second)).findFirst().orElse(null);
    return left != null && right != null && left.teamId().isPresent() && right.teamId().isPresent()
        && left.teamId().getAsInt() == right.teamId().getAsInt();
  }
}
