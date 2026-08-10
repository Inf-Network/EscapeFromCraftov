package com.birditch.raidcore.bukkit;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.config.ConfigurationException;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
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

  /// Loads the main config and copies sections from legacy split files when an
  /// upgrading installation does not yet have those sections. Legacy files are
  /// intentionally retained as rollback references, but are no longer read on
  /// later boots once migration has succeeded.
  public static FileConfiguration loadMainConfig(
      Plugin plugin, Map<String, String> legacySectionFiles) {
    Preconditions.checkNotNull(plugin, "plugin");
    Preconditions.checkNotNull(legacySectionFiles, "legacySectionFiles");
    FileConfiguration config = loadOrSaveDefault(plugin, "config.yml");
    boolean changed = false;
    for (Map.Entry<String, String> entry : legacySectionFiles.entrySet()) {
      String sectionName = entry.getKey();
      if (config.isConfigurationSection(sectionName)) {
        continue;
      }
      File legacyFile = new File(plugin.getDataFolder(), entry.getValue());
      if (!legacyFile.isFile()) {
        continue;
      }
      ConfigurationSection legacy =
          YamlConfiguration.loadConfiguration(legacyFile).getConfigurationSection(sectionName);
      if (legacy == null) {
        continue;
      }
      ConfigurationSection target = config.createSection(sectionName);
      for (String key : legacy.getKeys(true)) {
        if (!legacy.isConfigurationSection(key)) {
          target.set(key, legacy.get(key));
        }
      }
      plugin.getLogger().warning(
          "已将旧配置 " + entry.getValue() + " 的 " + sectionName
              + " 节迁移到 config.yml；旧文件仅保留作回滚参考。");
      changed = true;
    }
    if (changed) {
      save(plugin, config, "config.yml");
    }
    return config;
  }

  private static void save(Plugin plugin, FileConfiguration config, String fileName) {
    try {
      config.save(new File(plugin.getDataFolder(), fileName));
    } catch (IOException e) {
      throw new ConfigurationException("Failed to save migrated " + fileName, e);
    }
  }
}
