package com.birditch.escapefromcraftov.game.plugin.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.game.plugin.poi.PoiReconLookup;
import org.junit.jupiter.api.Test;

final class CraftovSidebarReconTest {

  @Test
  void runningSidebarRetainsMinecraftsFifteenLineHardBudget() {
    assertEquals(15, CraftovSidebarService.maximumLines());
  }

  @Test
  void rendersIncompleteAndCompletedReconWithoutUnboundedDistance() {
    PoiReconLookup.View incomplete = new PoiReconLookup.View(
        "warehouse", "旧仓库", 4, false, "东北", 9_999, true);
    PoiReconLookup.View completed = new PoiReconLookup.View(
        "warehouse", "旧仓库", 4, true, "已到达", 0, false);

    assertEquals("侦察 旧仓库 · 东北 9999m+",
        CraftovSidebarService.reconnaissanceText(incomplete));
    assertEquals("侦察完成 · 旧仓库",
        CraftovSidebarService.reconnaissanceText(completed));
    assertThrows(IllegalArgumentException.class, () -> new PoiReconLookup.View(
        "overflow", "超距离", 4, false, "北", Long.MAX_VALUE, false));
  }

  @Test
  void immutableViewTruncatesAnUntrustedLongDisplayNameBeforeHudRendering() {
    PoiReconLookup.View view = new PoiReconLookup.View(
        "long", "这是一段来自外部\n查询实现而且长到足以撑爆侧边栏的侦察目标名称",
        4, false, "北", 12, false);

    assertEquals(PoiReconLookup.View.MAXIMUM_DISPLAY_NAME_CODE_POINTS,
        view.displayName().codePointCount(0, view.displayName().length()));
    assertTrue(view.displayName().endsWith("…"));
    assertFalse(view.displayName().contains("\n"));
    assertTrue(CraftovSidebarService.reconnaissanceText(view)
        .codePointCount(0, CraftovSidebarService.reconnaissanceText(view).length()) <= 41);
  }
}
