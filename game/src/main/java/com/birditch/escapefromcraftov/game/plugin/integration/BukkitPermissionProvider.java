package com.birditch.escapefromcraftov.game.plugin.integration;

import org.bukkit.command.CommandSender;

public final class BukkitPermissionProvider implements PermissionProvider {

  @Override
  public boolean has(CommandSender sender, String permission) {
    return sender.hasPermission(permission);
  }
}
