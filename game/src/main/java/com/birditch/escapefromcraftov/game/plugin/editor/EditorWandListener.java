package com.birditch.escapefromcraftov.game.plugin.editor;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.map.BlockPosition;
import com.birditch.escapefromcraftov.map.EditorSessionService;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/// PDC-marked two-point wand; it does not depend on a display name or material check.
public final class EditorWandListener implements Listener {

  private final EditorSelectionService selections;
  private final EditorSessionService sessions;
  private final NamespacedKey wandKey;

  public EditorWandListener(Plugin plugin, EditorSelectionService selections,
      EditorSessionService sessions) {
    Preconditions.checkNotNull(plugin, "plugin");
    this.selections = Preconditions.checkNotNull(selections, "selections");
    this.sessions = Preconditions.checkNotNull(sessions, "sessions");
    this.wandKey = new NamespacedKey(plugin, "map_editor_wand");
  }

  public ItemStack createWand() {
    ItemStack item = new ItemStack(Material.BLAZE_ROD);
    ItemMeta meta = item.getItemMeta();
    meta.displayName(Component.text("Craftov 地图选区工具", NamedTextColor.GOLD));
    meta.lore(List.of(Component.text("左键方块: 位置 1", NamedTextColor.GRAY),
        Component.text("右键方块: 位置 2", NamedTextColor.GRAY)));
    meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
    item.setItemMeta(meta);
    return item;
  }

  @EventHandler(ignoreCancelled = false)
  public void onInteract(PlayerInteractEvent event) {
    if (!isWand(event.getItem())) return;
    Action action = event.getAction();
    if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;
    // The wand is a capability of an active editor session, not a global
    // server tool. A copied wand in a normal craftov must behave like an ordinary
    // item and must never mutate an administrator's draft.
    if (sessions.find(event.getPlayer().getUniqueId()).isEmpty()) return;
    Block block = event.getClickedBlock();
    if (block == null) return;
    event.setCancelled(true);
    Player player = event.getPlayer();
    BlockPosition position = new BlockPosition(block.getX(), block.getY(), block.getZ());
    if (action == Action.LEFT_CLICK_BLOCK) {
      selections.setFirst(player.getUniqueId(), block.getWorld().getName(), position);
      player.sendMessage(Component.text("已设置位置 1: " + coordinate(position),
          NamedTextColor.GREEN));
    } else {
      selections.setSecond(player.getUniqueId(), block.getWorld().getName(), position);
      player.sendMessage(Component.text("已设置位置 2: " + coordinate(position),
          NamedTextColor.AQUA));
    }
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    selections.clear(event.getPlayer().getUniqueId());
  }

  private boolean isWand(ItemStack item) {
    return item != null && item.hasItemMeta()
        && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
  }

  private static String coordinate(BlockPosition position) {
    return position.x() + ", " + position.y() + ", " + position.z();
  }
}
