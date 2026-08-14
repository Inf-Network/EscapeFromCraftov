package com.birditch.escapefromcraftov.api.game;

import java.util.Locale;
import java.util.OptionalInt;

/// Stable mapping between authored spawn labels and numeric craftov teams.
public final class CraftovSpawnTeamRules {

  private CraftovSpawnTeamRules() {}

  public static boolean isGeneric(String label) {
    String normalized = normalize(label);
    return normalized.equals("players") || normalized.equals("any") || normalized.equals("*");
  }

  public static boolean isValid(String label) {
    return isGeneric(label) || assignedTeam(label).isPresent();
  }

  public static boolean accepts(String label, int teamId) {
    if (teamId < 1) return isGeneric(label);
    return isGeneric(label) || assignedTeam(label).orElse(-1) == teamId;
  }

  public static OptionalInt assignedTeam(String label) {
    String normalized = normalize(label);
    if (normalized.equals("red")) return OptionalInt.of(1);
    if (normalized.equals("blue")) return OptionalInt.of(2);
    String number = normalized;
    if (number.startsWith("team_")) number = number.substring(5);
    else if (number.startsWith("team-")) number = number.substring(5);
    else if (number.startsWith("team")) number = number.substring(4);
    try {
      int parsed = Integer.parseInt(number);
      return parsed >= 1 ? OptionalInt.of(parsed) : OptionalInt.empty();
    } catch (NumberFormatException ignored) {
      return OptionalInt.empty();
    }
  }

  private static String normalize(String label) {
    return label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
  }
}
