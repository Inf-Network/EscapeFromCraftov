package net.infnetwork.snowball.craftov.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;

public final class MenuListener implements Listener {

    public MenuListener(Plugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu && menu.ticking()) {
                    menu.tick();
                }
            }
        }, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu menu) {
            event.setCancelled(true);
            try {
                menu.handleClick(event);
            } catch (RuntimeException ex) {
                Bukkit.getLogger().log(java.util.logging.Level.SEVERE, "Craftov menu click failed", ex);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu menu) {
            menu.onClose();
        }
    }
}
