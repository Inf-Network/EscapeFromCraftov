package com.birditch.escapefromcraftov.game.plugin.ai;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.time.Duration;

/** Pure state transitions for the bounded Minecraft-native AI tactics loop. */
final class AiTacticsPolicy {

  /**
   * Server tuning values, not parameters copied from Delta Force or another game.
   * They deliberately favor bounded Paper work over frame-perfect simulation.
   */
  static final Tuning DEFAULT = new Tuning(
      96, 40, 48, 24, 12,
      Duration.ofSeconds(6), Duration.ofSeconds(8), Duration.ofSeconds(2));

  private AiTacticsPolicy() {}

  static State next(Input input, Tuning tuning) {
    Preconditions.checkNotNull(input, "input");
    Preconditions.checkNotNull(tuning, "tuning");
    if (!input.actorInsideLeash()) return State.RETURN;
    return switch (input.state()) {
      case DORMANT -> input.acquiredTarget() ? State.ALERT
          : input.participantNearby() ? State.PATROL : State.DORMANT;
      case PATROL -> input.acquiredTarget() ? State.ALERT
          : input.participantNearby() ? State.PATROL : State.DORMANT;
      case ALERT -> input.targetValid() && input.targetVisible() && input.targetInsideLeash()
          ? State.CHASE : State.SEARCH;
      case CHASE -> {
        if (input.acquiredTarget() && !input.targetValid()) yield State.ALERT;
        if (!input.targetValid() || !input.targetInsideLeash()) yield State.SEARCH;
        if (!input.targetVisible()
            && input.sinceLastSeen().compareTo(tuning.lostSightDelay()) >= 0) {
          yield State.SEARCH;
        }
        yield State.CHASE;
      }
      case SEARCH -> input.acquiredTarget() ? State.ALERT
          : input.stateAge().compareTo(tuning.searchDuration()) >= 0
              ? State.RETURN : State.SEARCH;
      case RETURN -> input.acquiredTarget() ? State.ALERT
          : input.homeReached()
              ? input.participantNearby() ? State.PATROL : State.DORMANT
              : State.RETURN;
    };
  }

  static boolean insideLeash(double x, double z, int minX, int maxX,
      int minZ, int maxZ, double margin) {
    double xDistance = outsideDistance(x, minX, maxX + 1.0);
    double zDistance = outsideDistance(z, minZ, maxZ + 1.0);
    return xDistance * xDistance + zDistance * zDistance <= margin * margin;
  }

  /** Wake is deliberately broader than target acquisition and ignores the zone leash. */
  static boolean canWake(double distanceSquared, Tuning tuning) {
    Preconditions.checkNotNull(tuning, "tuning");
    return Double.isFinite(distanceSquared) && distanceSquared >= 0
        && distanceSquared <= tuning.wakeDistance() * tuning.wakeDistance();
  }

  /** Detection remains fail-closed to the authored leash and line of sight. */
  static boolean canDetect(boolean targetInsideLeash, boolean lineOfSight,
      double distanceSquared, Tuning tuning) {
    Preconditions.checkNotNull(tuning, "tuning");
    return targetInsideLeash && lineOfSight
        && Double.isFinite(distanceSquared) && distanceSquared >= 0
        && distanceSquared <= tuning.detectionDistance() * tuning.detectionDistance();
  }

  private static double outsideDistance(double value, double minimum, double maximum) {
    if (value < minimum) return minimum - value;
    if (value > maximum) return value - maximum;
    return 0;
  }

  enum State {
    DORMANT,
    PATROL,
    ALERT,
    CHASE,
    SEARCH,
    RETURN
  }

  record Input(
      State state,
      boolean participantNearby,
      boolean acquiredTarget,
      boolean targetValid,
      boolean targetVisible,
      boolean targetInsideLeash,
      boolean actorInsideLeash,
      boolean homeReached,
      Duration stateAge,
      Duration sinceLastSeen) {

    Input {
      Preconditions.checkNotNull(state, "state");
      Preconditions.checkNotNull(stateAge, "stateAge");
      Preconditions.checkNotNull(sinceLastSeen, "sinceLastSeen");
      Preconditions.checkArgument(!stateAge.isNegative(), "stateAge must not be negative");
      Preconditions.checkArgument(!sinceLastSeen.isNegative(),
          "sinceLastSeen must not be negative");
    }
  }

  record Tuning(
      double wakeDistance,
      double detectionDistance,
      double alertRadius,
      double leashMargin,
      int maximumAlertPeers,
      Duration lostSightDelay,
      Duration searchDuration,
      Duration pathFailureBackoff) {

    Tuning {
      Preconditions.checkArgument(Double.isFinite(wakeDistance) && wakeDistance > 0,
          "wakeDistance must be finite and positive");
      Preconditions.checkArgument(Double.isFinite(detectionDistance) && detectionDistance > 0,
          "detectionDistance must be finite and positive");
      Preconditions.checkArgument(Double.isFinite(alertRadius) && alertRadius > 0,
          "alertRadius must be finite and positive");
      Preconditions.checkArgument(Double.isFinite(leashMargin) && leashMargin >= 0,
          "leashMargin must be finite and non-negative");
      Preconditions.checkArgument(maximumAlertPeers >= 0,
          "maximumAlertPeers must not be negative");
      Preconditions.checkNotNull(lostSightDelay, "lostSightDelay");
      Preconditions.checkNotNull(searchDuration, "searchDuration");
      Preconditions.checkNotNull(pathFailureBackoff, "pathFailureBackoff");
      Preconditions.checkArgument(!lostSightDelay.isNegative(),
          "lostSightDelay must not be negative");
      Preconditions.checkArgument(!searchDuration.isNegative(),
          "searchDuration must not be negative");
      Preconditions.checkArgument(!pathFailureBackoff.isNegative()
              && !pathFailureBackoff.isZero(),
          "pathFailureBackoff must be positive");
    }
  }
}
