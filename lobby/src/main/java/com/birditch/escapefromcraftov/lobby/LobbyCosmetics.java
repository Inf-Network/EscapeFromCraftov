package com.birditch.escapefromcraftov.lobby;

import com.birditch.escapefromcraftov.api.cosmetics.Cosmetic;
import com.birditch.escapefromcraftov.api.cosmetics.CosmeticId;
import java.util.List;

/// Built-in cosmetic catalog offered by the lobby.
public final class LobbyCosmetics {

  private LobbyCosmetics() {}

  public static List<Cosmetic> catalog() {
    return List.of(
        new Cosmetic(CosmeticId.of("golden_crown"), "黄金王冠", "minecraft:golden_helmet"),
        new Cosmetic(CosmeticId.of("iron_helm"), "铁盔", "minecraft:iron_helmet"),
        new Cosmetic(CosmeticId.of("diamond_visor"), "钻石面甲", "minecraft:diamond_helmet"),
        new Cosmetic(CosmeticId.of("emerald_circlet"), "翡翠头环", "minecraft:turtle_helmet"));
  }
}
