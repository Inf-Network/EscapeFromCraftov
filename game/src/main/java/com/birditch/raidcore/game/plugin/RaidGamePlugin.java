package com.birditch.raidcore.game.plugin;

import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/// Paper entry point; all real work lives in [GameApplication]. The field is
/// assigned before [GameApplication#start] so a partial startup still gets its
/// guarded teardown in [#onDisable].
public final class RaidGamePlugin extends JavaPlugin {

  private @Nullable GameApplication application;

  @Override
  public void onEnable() {
    GameApplication app = new GameApplication(this);
    application = app;
    try {
      app.start();
    } catch (Exception e) {
      getLogger().log(Level.SEVERE, "RaidCoreGame failed to start; disabling plugin.", e);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public void onDisable() {
    GameApplication app = application;
    if (app != null) {
      app.stop();
      application = null;
    }
  }
}
