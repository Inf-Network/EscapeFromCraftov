package com.birditch.raidcore.game.plugin;

import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/// Paper entry point; all real work lives in [RaidApplication]. The field is
/// assigned before [RaidApplication#start] so a partial startup still gets its
/// guarded teardown in [#onDisable].
public final class RaidPlugin extends JavaPlugin {

  private @Nullable RaidApplication application;

  @Override
  public void onEnable() {
    RaidApplication app = new RaidApplication(this);
    application = app;
    try {
      app.start();
    } catch (Exception e) {
      getLogger().log(Level.SEVERE, "RaidCore failed to start; disabling plugin.", e);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public void onDisable() {
    RaidApplication app = application;
    if (app != null) {
      app.stop();
      application = null;
    }
  }
}
