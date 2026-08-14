package com.birditch.escapefromcraftov.api.network;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.HashSet;
import java.util.List;

/** Action-tier descriptors advertised for one enabled map. */
public record MapActionTierCatalog(CraftovMapId mapId, List<RaidActionProfile> tiers) {

  public MapActionTierCatalog {
    Preconditions.checkNotNull(mapId, "mapId");
    tiers = List.copyOf(Preconditions.checkNotNull(tiers, "tiers"));
    Preconditions.checkArgument(!tiers.isEmpty(), "tiers must not be empty");
    HashSet<RaidTierId> ids = new HashSet<>();
    for (RaidActionProfile tier : tiers) {
      Preconditions.checkArgument(ids.add(tier.id()),
          "tiers must not contain duplicate id: " + tier.id().value());
    }
  }
}
