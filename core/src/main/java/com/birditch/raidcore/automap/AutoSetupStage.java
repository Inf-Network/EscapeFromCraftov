package com.birditch.raidcore.automap;

public enum AutoSetupStage {
  VALIDATE_WORLD("检查世界"),
  ANALYZE_TERRAIN("分析地形与建筑"),
  GENERATE_POI("生成 POI"),
  GENERATE_SPAWNS("生成出生区域"),
  GENERATE_LOOT_AI("生成 Loot 与 AI 区域"),
  GENERATE_EXTRACTIONS("生成撤离点"),
  VALIDATE_BALANCE("验证地图并评分");

  private final String displayName;

  AutoSetupStage(String displayName) { this.displayName = displayName; }
  public String displayName() { return displayName; }
}
