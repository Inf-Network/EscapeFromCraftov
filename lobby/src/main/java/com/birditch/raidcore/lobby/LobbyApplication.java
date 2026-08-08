package com.birditch.raidcore.lobby;

import com.birditch.raidcore.api.network.MessageBroker;
import com.birditch.raidcore.bukkit.BungeeConnector;
import com.birditch.raidcore.bukkit.CoreConfigMapper;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.bukkit.PaperRaidScheduler;
import com.birditch.raidcore.bukkit.RedisConfigMapper;
import com.birditch.raidcore.bukkit.YamlResources;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.time.SystemTimeSource;
import com.birditch.raidcore.lobby.command.LobbyCommand;
import com.birditch.raidcore.lobby.command.LobbyCommandContext;
import com.birditch.raidcore.lobby.listener.ConnectionDeps;
import com.birditch.raidcore.lobby.listener.LobbyConnectionListener;
import com.birditch.raidcore.lobby.listener.LobbyProtectionListener;
import com.birditch.raidcore.lobby.matchmaking.QueueDeps;
import com.birditch.raidcore.lobby.matchmaking.QueueService;
import com.birditch.raidcore.lobby.menu.LobbyMenus;
import com.birditch.raidcore.lobby.menu.MenuDeps;
import com.birditch.raidcore.lobby.menu.MenuListener;
import com.birditch.raidcore.redis.JedisMessageBroker;
import com.birditch.raidcore.redis.JsonCodec;
import com.birditch.raidcore.redis.RedisAssignmentStore;
import com.birditch.raidcore.redis.RedisConfig;
import com.birditch.raidcore.redis.RedisConnector;
import com.birditch.raidcore.redis.RedisCosmeticsService;
import com.birditch.raidcore.redis.RedisInstanceDirectory;
import com.birditch.raidcore.redis.RedisPlayerSettingsService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.jspecify.annotations.Nullable;

/// Boots and tears down the lobby runtime: configs, Redis infrastructure,
/// proxy connector, matchmaking queue, listeners and commands. No domain
/// modules and no SQL — the lobby is a thin shell over the Redis fabric.
///
/// A dead Redis at boot never aborts startup: the probe logs SEVERE, the
/// plugin stays enabled and every Redis-backed service recovers on its own
/// once the connection returns. Both [#start] and [#stop] are main-thread only.
public final class LobbyApplication {

  private static final String PLAY_PERMISSION = "raid.lobby.play";
  private static final Duration REDIS_PROBE_TIMEOUT = Duration.ofSeconds(3);

  private final RaidLobbyPlugin plugin;
  private final Logger logger;

  private @Nullable ExecutorService ioExecutor;
  private @Nullable RedisConnector redisConnector;
  private @Nullable MessageBroker broker;
  private @Nullable QueueService queueService;
  private @Nullable BungeeConnector bungeeConnector;

  public LobbyApplication(RaidLobbyPlugin plugin) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.logger = plugin.getLogger();
  }

  /// Called once from [RaidLobbyPlugin#onEnable]; any thrown exception aborts
  /// startup and the plugin disables itself.
  public void start() {
    banner();
    BootConfig configs = loadConfigs();
    LobbyConfig config = configs.lobby();
    Messages messages = configs.messages();
    PaperRaidScheduler scheduler = new PaperRaidScheduler(plugin);
    SystemTimeSource timeSource = new SystemTimeSource();
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    ioExecutor = executor;
    RedisConnector redis = new RedisConnector(configs.redis());
    redisConnector = redis;
    JsonCodec codec = new JsonCodec();
    RedisInstanceDirectory directory = new RedisInstanceDirectory(redis, codec, executor, logger);
    RedisAssignmentStore assignments = new RedisAssignmentStore(redis, codec, executor);
    JedisMessageBroker messageBroker = new JedisMessageBroker(redis, logger);
    broker = messageBroker;
    RedisPlayerSettingsService settings = new RedisPlayerSettingsService(redis, executor);
    RedisCosmeticsService cosmetics =
        new RedisCosmeticsService(redis, executor, LobbyCosmetics.catalog());
    BungeeConnector bungee = new BungeeConnector(plugin);
    bungeeConnector = bungee;
    QueueService queue = new QueueService(new QueueDeps(directory, assignments, messageBroker,
        bungee, scheduler, timeSource, settings, config, messages, plugin, logger));
    queueService = queue;
    queue.start();
    ConnectionDeps connectionDeps = new ConnectionDeps(plugin, scheduler, messages, settings,
        cosmetics, queue, mainMenuOpener(messages), logger);
    MenuDeps menuDeps =
        new MenuDeps(settings, cosmetics, queue, scheduler, config, messages, plugin, logger);
    registerListeners(connectionDeps, menuDeps, config);
    registerPlayPermission();
    registerCommands(new LobbyCommandContext(plugin, queue, messages, config));
    logSummary(config, configs.redis(), directory);
  }

  /// Guarded teardown; each step failure is logged and the remaining steps run.
  public void stop() {
    runGuarded("stop queue", () -> {
      if (queueService != null) {
        queueService.stop();
      }
    });
    runGuarded("unregister proxy channel", () -> {
      if (bungeeConnector != null) {
        bungeeConnector.unregister();
      }
    });
    runGuarded("close message broker", () -> {
      if (broker != null) {
        broker.close();
      }
    });
    runGuarded("close redis", () -> {
      if (redisConnector != null) {
        redisConnector.close();
      }
    });
    runGuarded("shut down IO executor", this::shutdownIoExecutor);
    runGuarded("unregister listeners", () -> HandlerList.unregisterAll(plugin));
    runGuarded("cancel scheduled tasks", () -> Bukkit.getScheduler().cancelTasks(plugin));
    logger.info("RaidCoreLobby 已卸载。");
  }

  private BootConfig loadConfigs() {
    LobbyConfig config = LobbyConfigMapper.read(YamlResources.loadOrSaveDefault(plugin, "config.yml"));
    config.validate();
    RedisConfig redisConfig = RedisConfigMapper.read(YamlResources.loadOrSaveDefault(plugin, "redis.yml"));
    redisConfig.validate();
    Messages messages =
        new Messages(CoreConfigMapper.readMessages(YamlResources.loadOrSaveDefault(plugin, "messages.yml")));
    return new BootConfig(config, redisConfig, messages);
  }

  private Consumer<Player> mainMenuOpener(Messages messages) {
    return player -> player.openInventory(LobbyMenus.MainMenu.build(messages));
  }

  private void registerListeners(ConnectionDeps connectionDeps, MenuDeps menuDeps,
      LobbyConfig config) {
    PluginManager manager = plugin.getServer().getPluginManager();
    manager.registerEvents(new LobbyConnectionListener(connectionDeps), plugin);
    manager.registerEvents(new LobbyProtectionListener(config), plugin);
    manager.registerEvents(new MenuListener(menuDeps), plugin);
  }

  private void registerPlayPermission() {
    PluginManager manager = plugin.getServer().getPluginManager();
    if (manager.getPermission(PLAY_PERMISSION) == null) {
      manager.addPermission(new Permission(
          PLAY_PERMISSION, "Allows queueing for raids from the lobby.", PermissionDefault.TRUE));
    }
  }

  private void registerCommands(LobbyCommandContext context) {
    plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
      for (LiteralCommandNode<CommandSourceStack> root : LobbyCommand.build(context)) {
        event.registrar().register(root);
      }
    });
  }

  private void banner() {
    logger.info("========================================");
    logger.info("  Escape from Craftov · 逃离块科夫 — 大厅");
    logger.info("========================================");
  }

  private void logSummary(LobbyConfig config, RedisConfig redisConfig,
      RedisInstanceDirectory directory) {
    boolean redisUp = probeRedis(directory, redisConfig);
    logger.info(() -> "RaidCoreLobby v" + plugin.getPluginMeta().getVersion()
        + ",Minecraft " + Bukkit.getMinecraftVersion()
        + "(Java " + Runtime.version().feature() + ")");
    logger.info(() -> "Redis:" + redisConfig.host() + ":" + redisConfig.port()
        + (redisUp ? " 连接正常" : " 连接失败"));
    logger.info(() -> "匹配轮询:" + config.queueTickSeconds()
        + " 秒 / 装扮:" + LobbyCosmetics.catalog().size() + " 款");
    logger.info("RaidCoreLobby 启动完成。");
  }

  /// One-time blocking probe at boot (acceptable, mirrors the game server's
  /// schema init); a failure only downgrades the summary line to FAILED.
  private boolean probeRedis(RedisInstanceDirectory directory, RedisConfig redisConfig) {
    try {
      directory.activeServers().get(REDIS_PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logRedisDown(redisConfig, e);
      return false;
    } catch (ExecutionException | TimeoutException e) {
      logRedisDown(redisConfig, e);
      return false;
    }
  }

  private void logRedisDown(RedisConfig redisConfig, Exception cause) {
    logger.log(Level.SEVERE, "无法连接 Redis(" + redisConfig.host() + ":"
        + redisConfig.port() + ")。大厅保持运行,但匹配已暂停;Redis 恢复后自动继续。", cause);
  }

  private void shutdownIoExecutor() throws InterruptedException {
    ExecutorService executor = ioExecutor;
    if (executor == null) {
      return;
    }
    executor.shutdown();
    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
      executor.shutdownNow();
    }
  }

  private void runGuarded(String step, ShutdownStep action) {
    try {
      action.run();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.log(Level.SEVERE, "Shutdown step interrupted: " + step, e);
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Shutdown step failed: " + step, e);
    }
  }

  private record BootConfig(LobbyConfig lobby, RedisConfig redis, Messages messages) {}

  @FunctionalInterface
  private interface ShutdownStep {
    void run() throws Exception;
  }
}
