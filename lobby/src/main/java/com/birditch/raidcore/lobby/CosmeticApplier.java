package com.birditch.raidcore.lobby;

import com.birditch.raidcore.api.cosmetics.Cosmetic;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/// Renders a cosmetic on a player's helmet slot. Lobby-only convention: the
/// lobby owns the whole inventory, so the helmet slot is free to overwrite.
public final class CosmeticApplier {

  private static final Logger LOGGER = Logger.getLogger(CosmeticApplier.class.getName());

  private CosmeticApplier() {}

  /// Sets the helmet for `cosmetic`, or clears it when `cosmetic` is `null` or
  /// its material key no longer resolves (logged, never thrown — a stale
  /// catalog entry must not break the join flow).
  public static void apply(Player player, @Nullable Cosmetic cosmetic) {
    Preconditions.checkNotNull(player, "player");
    if (cosmetic == null) {
      player.getInventory().setHelmet(null);
      return;
    }
    Material material = Material.matchMaterial(cosmetic.materialKey());
    if (material == null) {
      LOGGER.warning(() -> "Unknown material '" + cosmetic.materialKey()
          + "' for cosmetic " + cosmetic.id().value() + "; clearing helmet instead.");
      player.getInventory().setHelmet(null);
      return;
    }
    player.getInventory().setHelmet(new ItemStack(material));
  }
}
