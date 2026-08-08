package com.birditch.raidcore.lobby;

import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/// Paper entry point; all real work lives in [LobbyApplication]. The field is
/// assigned before [LobbyApplication#start] so a partial startup still gets its
/// guarded teardown in [#onDisable].
public final class RaidLobbyPlugin extends JavaPlugin {

  private @Nullable LobbyApplication application;

  @Override
  public void onEnable() {
    LobbyApplication app = new LobbyApplication(this);
    application = app;
    try {
      app.start();
    } catch (Exception e) {
      getLogger().log(Level.SEVERE, "RaidCoreLobby failed to start; disabling plugin.", e);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public void onDisable() {
    LobbyApplication app = application;
    if (app != null) {
      app.stop();
      application = null;
    }
  }
}
