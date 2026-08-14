package com.birditch.escapefromcraftov.game.plugin;

import com.birditch.escapefromcraftov.bukkit.ServerRoleGuard;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/// Paper entry point; all real work lives in [GameApplication]. The field is
/// assigned before [GameApplication#start] so a partial startup still gets its
/// guarded teardown in [#onDisable].
public final class CraftovGamePlugin extends JavaPlugin {

  private @Nullable GameApplication application;

  @Override
  public void onEnable() {
    try {
      ServerRoleGuard.requireAbsent(this, "EscapeFromCraftovLobby");
    } catch (IllegalStateException e) {
      getLogger().log(Level.SEVERE,
          "检测到 EscapeFromCraftovLobby：Game 与 Lobby 必须部署在不同的 Paper 服务端，本插件拒绝启动。", e);
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    GameApplication app = new GameApplication(this);
    application = app;
    try {
      app.start();
    } catch (Exception e) {
      getLogger().log(Level.SEVERE, "EscapeFromCraftovGame failed to start; disabling plugin.", e);
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
