package com.birditch.escapefromcraftov.game.plugin;

import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.loot.LootTableRegistry;
import com.birditch.escapefromcraftov.api.network.InstanceDirectory;
import com.birditch.escapefromcraftov.api.session.SessionService;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.core.config.EscapeFromCraftovConfig;
import com.birditch.escapefromcraftov.core.module.ModuleManager;
import com.birditch.escapefromcraftov.core.module.CraftovModule;
import com.birditch.escapefromcraftov.core.service.ServiceRegistry;
import com.birditch.escapefromcraftov.redis.RedisConfig;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;

/// Console banner and startup summary of the game server (operator-facing,
/// Simplified Chinese per v4).
final class StartupSummary {

  private final CraftovGamePlugin plugin;
  private final Logger logger;

  StartupSummary(CraftovGamePlugin plugin, Logger logger) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.logger = Preconditions.checkNotNull(logger, "logger");
  }

  void banner() {
    logger.info("========================================");
    logger.info("  Escape from Craftov · 逃离块科夫 — 游戏服务端");
    logger.info("========================================");
  }

  /// One-time blocking probe (2s cap) so the summary can report Redis health.
  /// A failure is logged SEVERE once and never aborts startup.
  boolean probeRedis(InstanceDirectory directory) {
    try {
      directory.activeServers().get(2, TimeUnit.SECONDS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.log(Level.SEVERE, "启动时的 Redis 探测被中断。", e);
      return false;
    } catch (ExecutionException | TimeoutException e) {
      logger.log(Level.SEVERE,
          "启动时无法连接 Redis;实例目录的读写会限流重试,Redis 恢复后自动继续。", e);
      return false;
    }
  }

  void log(ServiceRegistry registry, ModuleManager modules, GameServerConfig server,
      SummaryConfigs configs) {
    String moduleIds =
        modules.modules().stream().map(CraftovModule::id).collect(Collectors.joining(", "));
    logger.info(() -> "EscapeFromCraftovGame v" + plugin.getPluginMeta().getVersion()
        + ",Minecraft " + Bukkit.getMinecraftVersion()
        + "(Java " + Runtime.version().feature() + ")");
    logger.info(() -> "模块:" + moduleIds);
    logger.info(() -> "物品 " + registry.require(ItemRegistry.class).size()
        + " 个,掉落表 " + registry.require(LootTableRegistry.class).size()
        + " 张,对局 " + registry.require(CraftovGameService.class).activeCount()
        + " 场,会话 " + registry.require(SessionService.class).count() + " 个");
    logger.info(() -> "服务器:" + server.serverId().value()
        + "(代理名 " + server.proxyName() + ")");
    logger.info(() -> "实例:" + registry.require(CraftovGameService.class).all().size()
        + "/" + configs.core().game().maxInstances()
        + "(常备 " + server.minReadyInstances() + " 个)");
    logger.info(() -> "Redis:" + configs.redis().host() + ":" + configs.redis().port()
        + (configs.redisOk() ? " 连接正常" : " 连接失败"));
    logger.info("EscapeFromCraftovGame 启动完成。");
  }

  record SummaryConfigs(EscapeFromCraftovConfig core, RedisConfig redis, boolean redisOk) {}
}
