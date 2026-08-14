package com.birditch.escapefromcraftov.api.game;

import java.util.UUID;

/// Pure team relationship rules shared by Paper listeners and tests.
public final class CraftovTeamRules {
  private CraftovTeamRules() {}

  public static boolean sameTeam(CraftovGame game, UUID first, UUID second) {
    CraftovPlayer left = game.players().stream()
        .filter(player -> player.playerId().equals(first)).findFirst().orElse(null);
    CraftovPlayer right = game.players().stream()
        .filter(player -> player.playerId().equals(second)).findFirst().orElse(null);
    return left != null && right != null && left.teamId().isPresent() && right.teamId().isPresent()
        && left.teamId().getAsInt() == right.teamId().getAsInt();
  }
}
