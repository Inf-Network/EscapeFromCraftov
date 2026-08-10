package com.birditch.raidcore.bukkit;

import com.birditch.raidcore.common.check.Preconditions;
import org.bukkit.plugin.Plugin;

/// Rejects an invalid topology where both RaidCore server roles are installed
/// in one Paper process. Presence is checked instead of enabled state so load
/// order cannot allow one role to start after the other role was disabled.
public final class ServerRoleGuard {

  private ServerRoleGuard() {}

  public static void requireAbsent(Plugin plugin, String incompatiblePluginName) {
    Preconditions.checkNotNull(plugin, "plugin");
    Preconditions.checkNotNull(incompatiblePluginName, "incompatiblePluginName");
    Plugin incompatible = plugin.getServer().getPluginManager().getPlugin(incompatiblePluginName);
    if (incompatible != null) {
      throw new IllegalStateException(
          plugin.getName() + " and " + incompatiblePluginName
              + " are exclusive server roles and cannot be installed together");
    }
  }
}
