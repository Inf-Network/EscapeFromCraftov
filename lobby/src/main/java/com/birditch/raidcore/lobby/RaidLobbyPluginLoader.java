package com.birditch.raidcore.lobby;

import com.birditch.raidcore.bukkit.PaperLibraries;
import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;

/// Resolves lobby runtime libraries before Paper creates the plugin.
public final class RaidLobbyPluginLoader implements PluginLoader {

  @Override
  public void classloader(PluginClasspathBuilder classpathBuilder) {
    PaperLibraries.addCentral(
        classpathBuilder,
        "com.zaxxer:HikariCP:7.1.0",
        "org.postgresql:postgresql:42.7.13",
        "org.hibernate.orm:hibernate-core:7.4.5.Final",
        "redis.clients:jedis:7.5.3",
        "com.google.code.gson:gson:2.14.0");
  }
}
