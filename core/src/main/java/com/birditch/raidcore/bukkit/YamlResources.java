package com.birditch.raidcore.bukkit;

import com.birditch.raidcore.common.check.Preconditions;
import java.io.File;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/// Loads plugin YAML resources, copying the bundled default into the data
/// folder on first run.
public final class YamlResources {

  private YamlResources() {}

  public static FileConfiguration loadOrSaveDefault(Plugin plugin, String fileName) {
    Preconditions.checkNotNull(plugin, "plugin");
    Preconditions.checkNotNull(fileName, "fileName");
    File file = new File(plugin.getDataFolder(), fileName);
    if (!file.exists()) {
      plugin.saveResource(fileName, false);
    }
    return YamlConfiguration.loadConfiguration(file);
  }
}
