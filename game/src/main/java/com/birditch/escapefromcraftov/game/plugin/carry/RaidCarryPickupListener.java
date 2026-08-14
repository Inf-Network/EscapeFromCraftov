package com.birditch.escapefromcraftov.game.plugin.carry;

import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.RaidInventoryMutationGate;
import com.birditch.escapefromcraftov.game.plugin.ownership.LootOwnershipController;
import java.util.Map;
import java.util.WeakHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;

/** Takes over real ground loot only after the ownership guard has allowed the event. */
public final class RaidCarryPickupListener implements Listener {

  private final RaidCarryController carry;
  private final LootOwnershipController ownership;
  private final CraftovItemStacks itemStacks;
  private final RaidInventoryMutationGate mutationGate;
  private final Map<EntityPickupItemEvent, LootOwnershipController.ManualPickupPermit> frozen =
      new WeakHashMap<>();

  public RaidCarryPickupListener(RaidCarryController carry,
      LootOwnershipController ownership, CraftovItemStacks itemStacks) {
    this(carry, ownership, itemStacks, RaidInventoryMutationGate.legacyAllowAll());
  }

  public RaidCarryPickupListener(RaidCarryController carry,
      LootOwnershipController ownership, CraftovItemStacks itemStacks,
      RaidInventoryMutationGate mutationGate) {
    this.carry = Preconditions.checkNotNull(carry, "carry");
    this.ownership = Preconditions.checkNotNull(ownership, "ownership");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    this.mutationGate = Preconditions.checkNotNull(mutationGate, "mutationGate");
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPickupGuard(EntityPickupItemEvent event) {
    if (!(event.getEntity() instanceof Player player)
        || !carry.active(player.getUniqueId())) return;
    if (!mutationGate.allows(player.getUniqueId())
        || !itemStacks.isCraftovItem(event.getItem().getItemStack())) {
      event.setCancelled(true);
      return;
    }
    var permit = ownership.prepareManualPickup(event.getItem(), player).orElse(null);
    if (permit == null) {
      event.setCancelled(true);
      return;
    }
    // Do not mutate or cancel a permitted event yet: later HIGHEST listeners retain veto power.
    frozen.put(event, permit);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPickupCommit(EntityPickupItemEvent event) {
    LootOwnershipController.ManualPickupPermit permit = frozen.remove(event);
    if (!(event.getEntity() instanceof Player player) || permit == null) return;
    // Replace vanilla insertion only after the event survived every guard.
    event.setCancelled(true);
    if (!mutationGate.allows(player.getUniqueId())
        || !carry.transferIntoCargo(player, permit.admitted(),
            () -> mutationGate.allows(player.getUniqueId())
                && ownership.commitManualPickup(event.getItem(), player, permit))) {
      player.sendActionBar(Component.text("携行空间或负重已达上限", NamedTextColor.RED));
    }
  }
}
