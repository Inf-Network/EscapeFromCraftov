package com.birditch.escapefromcraftov.game.plugin.poi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovPoi;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class PoiReconPolicyTest {

  private static final CraftovGameId GAME_ID = CraftovGameId.of("poi-policy");

  @Test
  void selectsOnlyTheHighestTierAndBreaksTiesStably() {
    CraftovPoi low = poi("low", "低价值区", 1, 0, 0, 0, 1, 1, 1);
    CraftovPoi alpha = poi("alpha", "甲区", 4, 10, 0, 10, 11, 1, 11);
    CraftovPoi beta = poi("beta", "乙区", 4, 20, 0, 20, 21, 1, 21);

    String first = PoiReconPolicy.select(GAME_ID, 18, List.of(low, alpha, beta))
        .orElseThrow().id();
    String reordered = PoiReconPolicy.select(GAME_ID, 18, List.of(beta, low, alpha))
        .orElseThrow().id();

    assertEquals(first, reordered);
    assertTrue(Set.of("alpha", "beta").contains(first));
    assertTrue(PoiReconPolicy.select(GAME_ID, 18, List.of()).isEmpty());
  }

  @Test
  void supportingBlockFallbackRequiresGroundAndChecksExactlyOneNegativeYBlock() {
    CraftovPoi groundOnly = poi("negative-ground", "负高度地面", 2,
        0, -61, 0, 0, -61, 0);

    assertTrue(PoiReconPolicy.reached(groundOnly, 0.5, -60.0, 0.5, true),
        "grounded feet may match exactly the supporting block");
    assertFalse(PoiReconPolicy.reached(groundOnly, 0.5, -60.0, 0.5, false),
        "airborne feet must not use the supporting block fallback");
    assertFalse(PoiReconPolicy.reached(groundOnly, 0.5, -59.0, 0.5, true),
        "the fallback must not scan through a stacked upper floor");
    assertTrue(PoiReconPolicy.reached(groundOnly, 0.5, -60.5, 0.5, false),
        "direct 3D feet containment remains valid even while airborne");
    assertFalse(PoiReconPolicy.reached(groundOnly, 0.5, Double.NaN, 0.5, true));
  }

  @Test
  void measurementUsesEightDirectionsArrivedAndABoundedDistanceLabel() {
    CraftovPoi target = poi("center", "中心", 3, 0, 0, 0, 0, 0, 0);

    assertEquals(PoiReconPolicy.Direction.ARRIVED,
        PoiReconPolicy.measure(target, 0.5, 0.5).orElseThrow().direction());
    assertEquals(PoiReconPolicy.Direction.EAST,
        PoiReconPolicy.measure(target, -9.5, 0.5).orElseThrow().direction());
    assertEquals(PoiReconPolicy.Direction.SOUTH_EAST,
        PoiReconPolicy.measure(target, -9.5, -9.5).orElseThrow().direction());
    assertEquals(PoiReconPolicy.Direction.SOUTH,
        PoiReconPolicy.measure(target, 0.5, -9.5).orElseThrow().direction());
    assertEquals(PoiReconPolicy.Direction.SOUTH_WEST,
        PoiReconPolicy.measure(target, 10.5, -9.5).orElseThrow().direction());
    assertEquals(PoiReconPolicy.Direction.WEST,
        PoiReconPolicy.measure(target, 10.5, 0.5).orElseThrow().direction());
    assertEquals(PoiReconPolicy.Direction.NORTH_WEST,
        PoiReconPolicy.measure(target, 10.5, 10.5).orElseThrow().direction());
    assertEquals(PoiReconPolicy.Direction.NORTH,
        PoiReconPolicy.measure(target, 0.5, 10.5).orElseThrow().direction());
    assertEquals(PoiReconPolicy.Direction.NORTH_EAST,
        PoiReconPolicy.measure(target, -9.5, 10.5).orElseThrow().direction());

    CraftovPoi extreme = poi("extreme", "极远", 4,
        Integer.MAX_VALUE, 0, Integer.MAX_VALUE,
        Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
    PoiReconPolicy.Measurement bounded = PoiReconPolicy.measure(
        extreme, Integer.MIN_VALUE, Integer.MIN_VALUE).orElseThrow();
    assertEquals(PoiReconPolicy.MAXIMUM_DISPLAY_DISTANCE_METERS, bounded.distanceMeters());
    assertTrue(bounded.distanceCapped());
    assertTrue(PoiReconPolicy.measure(target, Double.POSITIVE_INFINITY, 0).isEmpty());
  }

  private static CraftovPoi poi(String id, String name, int tier,
      int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    return new CraftovPoi(id, name, tier, minX, minY, minZ, maxX, maxY, maxZ);
  }
}
