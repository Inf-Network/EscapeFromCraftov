package com.birditch.escapefromcraftov.game.plugin.poi;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Optional;
import java.util.UUID;

/** Read-only, fail-closed view of one player's frozen raid reconnaissance target. */
@FunctionalInterface
public interface PoiReconLookup {

  PoiReconLookup NONE = (_, _) -> Optional.empty();

  Optional<View> view(CraftovGameId gameId, UUID playerId);

  static PoiReconLookup none() {
    return NONE;
  }

  /** Immutable Sidebar projection; runtime-owned mutable state never escapes. */
  record View(
      String poiId,
      String displayName,
      int tier,
      boolean completed,
      String direction,
      long distanceMeters,
      boolean distanceCapped) {

    public static final int MAXIMUM_DISPLAY_NAME_CODE_POINTS = 24;
    public static final long MAXIMUM_DISPLAY_DISTANCE_METERS = 9_999L;

    public View {
      Preconditions.checkNotNull(poiId, "poiId");
      Preconditions.checkArgument(!poiId.isBlank(), "poiId must not be blank");
      displayName = normalizedDisplayName(displayName);
      Preconditions.checkArgument(tier >= 1 && tier <= 4, "tier must be 1..4");
      Preconditions.checkNotNull(direction, "direction");
      Preconditions.checkArgument(!direction.isBlank(), "direction must not be blank");
      Preconditions.checkArgument(direction.codePointCount(0, direction.length()) <= 3,
          "direction is too long");
      Preconditions.checkArgument(direction.codePoints().noneMatch(codePoint ->
              Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                  || Character.isISOControl(codePoint)),
          "direction contains whitespace or control characters");
      Preconditions.checkArgument(distanceMeters >= 0, "distanceMeters must not be negative");
      Preconditions.checkArgument(distanceMeters <= MAXIMUM_DISPLAY_DISTANCE_METERS,
          "distanceMeters exceeds display cap");
      Preconditions.checkArgument(!distanceCapped
              || distanceMeters == MAXIMUM_DISPLAY_DISTANCE_METERS,
          "capped distance must use the display ceiling");
    }

    public String distanceLabel() {
      return distanceCapped ? distanceMeters + "m+" : distanceMeters + "m";
    }

    static String normalizedDisplayName(String value) {
      Preconditions.checkNotNull(value, "displayName");
      StringBuilder normalized = new StringBuilder(value.length());
      boolean pendingSpace = false;
      for (int offset = 0; offset < value.length();) {
        int codePoint = value.codePointAt(offset);
        offset += Character.charCount(codePoint);
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
            || Character.isISOControl(codePoint)) {
          pendingSpace = normalized.length() > 0;
        } else {
          if (pendingSpace) normalized.append(' ');
          normalized.appendCodePoint(codePoint);
          pendingSpace = false;
        }
      }
      String cleaned = normalized.toString();
      Preconditions.checkArgument(!cleaned.isBlank(), "displayName must not be blank");
      return truncate(cleaned, MAXIMUM_DISPLAY_NAME_CODE_POINTS);
    }

    private static String truncate(String value, int maximumCodePoints) {
      int codePoints = value.codePointCount(0, value.length());
      if (codePoints <= maximumCodePoints) return value;
      int end = value.offsetByCodePoints(0, maximumCodePoints - 1);
      return value.substring(0, end) + '…';
    }
  }
}
