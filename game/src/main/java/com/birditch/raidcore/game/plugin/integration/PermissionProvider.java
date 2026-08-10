package com.birditch.raidcore.game.plugin.integration;

import org.bukkit.command.CommandSender;

public interface PermissionProvider {

  boolean has(CommandSender sender, String permission);
}
