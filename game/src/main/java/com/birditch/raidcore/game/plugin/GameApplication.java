package com.birditch.raidcore.game.plugin;

import com.birditch.raidcore.api.event.EventSubscription;
import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.item.ItemRegistry;
import com.birditch.raidcore.api.loot.LootTableRegistry;
import com.birditch.raidcore.api.network.InstanceDirectory;
import com.birditch.raidcore.api.network.NetworkKeys;
import com.birditch.raidcore.api.profile.ProfileService;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.api.session.SessionService;
import com.birditch.raidcore.bukkit.BungeeConnector;
import com.birditch.raidcore.bukkit.CoreConfigMapper;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.bukkit.PaperRaidScheduler;
import com.birditch.raidcore.bukkit.RedisConfigMapper;
import com.birditch.raidcore.bukkit.YamlResources;
import com.birditch.raidcore.combat.CombatModule;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.time.SystemTimeSource;
import com.birditch.raidcore.core.RaidContext;
import com.birditch.raidcore.core.config.RaidCoreConfig;
import com.birditch.raidcore.core.database.Database;
import com.birditch.raidcore.core.database.DatabaseConfig;
import com.birditch.raidcore.core.database.HikariDatabase;
import com.birditch.raidcore.core.event.SimpleEventBus;
import com.birditch.raidcore.core.module.ModuleManager;
import com.birditch.raidcore.core.module.RaidModule;
import com.birditch.raidcore.core.service.ServiceRegistry;
import com.birditch.raidcore.economy.EconomyModule;
import com.birditch.raidcore.extraction.ExtractionModule;
import com.birditch.raidcore.game.GameModule;
import com.birditch.raidcore.game.plugin.command.CommandContext;
import com.birditch.raidcore.game.plugin.command.RaidCommand;
import com.birditch.raidcore.game.plugin.instance.BridgeDeps;
import com.birditch.raidcore.game.plugin.instance.InstanceProvisioner;
import com.birditch.raidcore.game.plugin.instance.InstanceWorldBridge;
import com.birditch.raidcore.game.plugin.instance.InstanceWorldIndex;
import com.birditch.raidcore.game.plugin.instance.ProvisionerDeps;
import com.birditch.raidcore.game.plugin.isolation.ChatIsolationListener;
import com.birditch.raidcore.game.plugin.isolation.VisibilityService;
import com.birditch.raidcore.game.plugin.listener.GameConnectionListener;
import com.birditch.raidcore.game.plugin.network.PendingDeps;
import com.birditch.raidcore.game.plugin.network.PendingJoinHandler;
import com.birditch.raidcore.game.plugin.network.PublisherDeps;
import com.birditch.raidcore.game.plugin.network.SnapshotPublisher;
import com.birditch.raidcore.game.plugin.world.WorldService;
import com.birditch.raidcore.game.plugin.world.WorldServiceDeps;
import com.birditch.raidcore.item.ItemModule;
import com.birditch.raidcore.loot.LootModule;
import com.birditch.raidcore.profile.ProfileModule;
import com.birditch.raidcore.redis.JedisMessageBroker;
import com.birditch.raidcore.redis.JsonCodec;
import com.birditch.raidcore.redis.RedisAssignmentStore;
import com.birditch.raidcore.redis.RedisConfig;
import com.birditch.raidcore.redis.RedisConnector;
import com.birditch.raidcore.redis.RedisInstanceDirectory;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.jspecify.annotations.Nullable;

/// Boots and tears down the Escape from Craftov game-server runtime.
///
/// [#start] wires infrastructure, registers the domain modules in contract order
/// (item, loot, profile, economy, combat, game, extraction), attaches the Redis
/// network fabric (instance directory, pending assignments, provision channel)
/// and the per-instance world machinery, then exposes commands. [#stop] releases
/// everything with each step guarded so later steps still run; both are
/// main-thread only. Redis being unreachable at boot never aborts startup —
/// publishes and reads fail gracefully and recover when Redis returns.
public final class GameApplication {

  private final RaidGamePlugin plugin;
  private final Logger logger;

  private @Nullable ServiceRegistry services;
  private @Nullable ModuleManager moduleManager;
  private @Nullable ExecutorService ioExecutor;
  private @Nullable Database database;
  private @Nullable RedisConnector redisConnector;
  private @Nullable JedisMessageBroker messageBroker;
  private @Nullable BungeeConnector bungeeConnector;
  private @Nullable EventSubscription bridgeSubscription;
  private @Nullable InstanceProvisioner provisioner;
  private @Nullable SnapshotPublisher publisher;

  /// Everything loaded from the plugin's YAML resources, validated.
  private record Configs(
      RaidCoreConfig core,
      DatabaseConfig database,
      GameServerConfig server,
      RedisConfig redis,
      Messages messages) {}

  /// Wiring produced by [#wireNetwork] that later startup steps still need.
  private record Wiring(
      InstanceDirectory directory,
      InstanceWorldIndex index,
      VisibilityService visibility,
      PendingJoinHandler pendingJoins) {}

  public GameApplication(RaidGamePlugin plugin) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.logger = plugin.getLogger();
  }

  /// Called once from [RaidGamePlugin#onEnable]; any thrown exception aborts
  /// startup and the plugin disables itself.
  public void start() {
    banner();
    Configs configs = loadConfigs();
    RaidContext context = createContext(configs.core(), configs.database());
    ModuleManager modules = createModules();
    modules.initializeAll(context);
    modules.startAll();
    Wiring wiring = wireNetwork(context, configs);
    registerPermissions();
    registerListeners(context, configs, wiring);
    registerCommands(
        new CommandContext(plugin, context.services(), modules, configs.messages(), configs.core()));
    logSummary(context, modules, configs, probeRedis(wiring.directory()));
  }

  /// Guarded teardown; each step failure is logged and the remaining steps run.
  public void stop() {
    runGuarded("destroy games", () -> withServices(
        registry -> registry.find(RaidGameService.class).ifPresent(RaidGameService::destroyAll)));
    runGuarded("save profiles", this::saveAllProfiles);
    stopNetwork();
    runGuarded("stop modules", () -> {
      if (moduleManager != null) {
        moduleManager.stopAll();
      }
    });
    runGuarded("unregister listeners", () -> HandlerList.unregisterAll(plugin));
    runGuarded("cancel scheduled tasks", () -> Bukkit.getScheduler().cancelTasks(plugin));
    runGuarded("shut down IO executor", this::shutdownIoExecutor);
    runGuarded("close database", () -> {
      if (database != null) {
        database.close();
      }
    });
    runGuarded("clear services", () -> withServices(ServiceRegistry::clear));
    logger.info("RaidCoreGame 已卸载。");
  }

  private Configs loadConfigs() {
    var configYaml = YamlResources.loadOrSaveDefault(plugin, "config.yml");
    RaidCoreConfig config = CoreConfigMapper.readCoreConfig(configYaml);
    GameServerConfig serverConfig = GameConfigMapper.read(configYaml);
    DatabaseConfig databaseConfig =
        CoreConfigMapper.readDatabaseConfig(YamlResources.loadOrSaveDefault(plugin, "database.yml"));
    RedisConfig redisConfig =
        RedisConfigMapper.read(YamlResources.loadOrSaveDefault(plugin, "redis.yml"));
    config.validate();
    databaseConfig.validate();
    serverConfig.validate();
    redisConfig.validate();
    Messages messages = new Messages(
        CoreConfigMapper.readMessages(YamlResources.loadOrSaveDefault(plugin, "messages.yml")));
    return new Configs(config, databaseConfig, serverConfig, redisConfig, messages);
  }

  private RaidContext createContext(RaidCoreConfig config, DatabaseConfig databaseConfig) {
    PaperRaidScheduler scheduler = new PaperRaidScheduler(plugin);
    SimpleEventBus eventBus = new SimpleEventBus(logger);
    ServiceRegistry registry = new ServiceRegistry();
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    Database db = createDatabase(databaseConfig);
    services = registry;
    ioExecutor = executor;
    database = db;
    return new RaidContext(
        config, databaseConfig, registry, eventBus, scheduler, executor, db, logger);
  }

  /// The game server hard-requires PostgreSQL: a pool that cannot be built
  /// aborts startup with an operator-facing explanation instead of limping on.
  private Database createDatabase(DatabaseConfig databaseConfig) {
    try {
      return new HikariDatabase(databaseConfig);
    } catch (RuntimeException e) {
      logger.severe(() -> "无法连接 PostgreSQL 数据库("
          + databaseConfig.host() + ":" + databaseConfig.port()
          + "/" + databaseConfig.database() + ")。"
          + "请检查 database.yml 的连接配置、PostgreSQL 服务是否可达、账号密码与库权限是否正确。"
          + "游戏服务端强依赖数据库,本次启动已中止。");
      throw e;
    }
  }

  private ModuleManager createModules() {
    ModuleManager manager = new ModuleManager(logger);
    moduleManager = manager;
    manager.register(new ItemModule());
    manager.register(new LootModule());
    manager.register(new ProfileModule());
    manager.register(new EconomyModule());
    manager.register(new CombatModule());
    manager.register(new GameModule());
    manager.register(new ExtractionModule());
    return manager;
  }

  /// Builds the Redis fabric and the per-instance world machinery, starts the
  /// self-provisioner and the snapshot heartbeat, and routes provision hints
  /// back to the main thread.
  private Wiring wireNetwork(RaidContext context, Configs configs) {
    ExecutorService executor = context.ioExecutor();
    RedisConnector redis = new RedisConnector(configs.redis());
    redisConnector = redis;
    JsonCodec codec = new JsonCodec();
    RedisInstanceDirectory directory = new RedisInstanceDirectory(redis, codec, executor, logger);
    RedisAssignmentStore assignments = new RedisAssignmentStore(redis, codec, executor);
    JedisMessageBroker broker = new JedisMessageBroker(redis, logger);
    messageBroker = broker;
    BungeeConnector bungee = new BungeeConnector(plugin);
    bungeeConnector = bungee;
    RaidGameService games = context.services().require(RaidGameService.class);
    RaidScheduler scheduler = context.scheduler();
    GameServerConfig server = configs.server();
    WorldService worlds = new WorldService(new WorldServiceDeps(
        plugin, scheduler, executor,
        plugin.getDataFolder().toPath().resolve(server.mapTemplateDir()), logger));
    InstanceWorldIndex index = new InstanceWorldIndex();
    VisibilityService visibility = new VisibilityService(plugin, index);
    bridgeSubscription = context.eventBus().register(new InstanceWorldBridge(
        new BridgeDeps(games, worlds, index, visibility, bungee, server, scheduler, logger)));
    InstanceProvisioner instanceProvisioner = new InstanceProvisioner(
        new ProvisionerDeps(games, configs.core().game(), server, scheduler, logger));
    provisioner = instanceProvisioner;
    instanceProvisioner.start();
    SnapshotPublisher snapshotPublisher = new SnapshotPublisher(new PublisherDeps(
        games, index, directory, server, configs.core(),
        new SystemTimeSource(), scheduler, logger));
    publisher = snapshotPublisher;
    snapshotPublisher.start();
    broker.subscribe(NetworkKeys.PROVISION_CHANNEL,
        payload -> scheduler.runSync(() -> instanceProvisioner.onProvisionHint(payload)));
    PendingJoinHandler pendingJoins = new PendingJoinHandler(new PendingDeps(
        assignments, games, server, bungee, scheduler, configs.messages(), logger));
    return new Wiring(directory, index, visibility, pendingJoins);
  }

  private void registerPermissions() {
    PluginManager pluginManager = plugin.getServer().getPluginManager();
    addPermission(pluginManager, new Permission(
        RaidCommand.PERMISSION_ADMIN, "Access to RaidCore admin commands", PermissionDefault.OP));
    addPermission(pluginManager, new Permission(
        RaidCommand.PERMISSION_INFO, "Access to RaidCore info commands", PermissionDefault.TRUE));
  }

  private static void addPermission(PluginManager pluginManager, Permission permission) {
    if (pluginManager.getPermission(permission.getName()) == null) {
      pluginManager.addPermission(permission);
    }
  }

  private void registerListeners(RaidContext context, Configs configs, Wiring wiring) {
    PluginManager pluginManager = plugin.getServer().getPluginManager();
    pluginManager.registerEvents(new ChatIsolationListener(wiring.index()), plugin);
    pluginManager.registerEvents(new GameConnectionListener(new GameConnectionListener.Deps(
        context.services(), context.scheduler(), configs.messages(), wiring.pendingJoins(),
        wiring.visibility(), wiring.index(), logger)), plugin);
  }

  private void registerCommands(CommandContext commandContext) {
    plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
        event.registrar().register(
            RaidCommand.build(commandContext), "RaidCore root command", List.of("raidcore")));
  }

  /// One-time blocking probe (2s cap) so the startup summary can report Redis
  /// health. A failure is logged SEVERE once and never aborts startup.
  private boolean probeRedis(InstanceDirectory directory) {
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

  private void banner() {
    logger.info("========================================");
    logger.info("  Escape from Craftov · 逃离块科夫 — 游戏服务端");
    logger.info("========================================");
  }

  private void logSummary(
      RaidContext context, ModuleManager modules, Configs configs, boolean redisOk) {
    ServiceRegistry registry = context.services();
    String moduleIds =
        modules.modules().stream().map(RaidModule::id).collect(Collectors.joining(", "));
    logger.info(() -> "RaidCoreGame v" + plugin.getPluginMeta().getVersion()
        + ",Minecraft " + Bukkit.getMinecraftVersion()
        + "(Java " + Runtime.version().feature() + ")");
    logger.info(() -> "模块:" + moduleIds);
    logger.info(() -> "物品 " + registry.require(ItemRegistry.class).size()
        + " 个,掉落表 " + registry.require(LootTableRegistry.class).size()
        + " 张,对局 " + registry.require(RaidGameService.class).activeCount()
        + " 场,会话 " + registry.require(SessionService.class).count() + " 个");
    GameServerConfig server = configs.server();
    logger.info(() -> "服务器:" + server.serverId().value()
        + "(代理名 " + server.proxyName() + ")");
    logger.info(() -> "实例:" + registry.require(RaidGameService.class).all().size()
        + "/" + configs.core().game().maxInstances()
        + "(常备 " + server.minReadyInstances() + " 个)");
    logger.info(() -> "Redis:" + configs.redis().host() + ":" + configs.redis().port()
        + (redisOk ? " 连接正常" : " 连接失败"));
    logger.info("RaidCoreGame 启动完成。");
  }

  /// Network teardown runs before module stop so the directory heartbeat
  /// fast-expires and no provision hint arrives mid-shutdown.
  private void stopNetwork() {
    runGuarded("unsubscribe instance bridge", () -> {
      if (bridgeSubscription != null) {
        bridgeSubscription.unsubscribe();
      }
    });
    runGuarded("stop instance provisioner", () -> {
      if (provisioner != null) {
        provisioner.stop();
      }
    });
    runGuarded("stop snapshot publisher", () -> {
      if (publisher != null) {
        publisher.stop();
      }
    });
    runGuarded("close message broker", () -> {
      if (messageBroker != null) {
        messageBroker.close();
      }
    });
    runGuarded("close redis", () -> {
      if (redisConnector != null) {
        redisConnector.close();
      }
    });
    runGuarded("unregister proxy channel", () -> {
      if (bungeeConnector != null) {
        bungeeConnector.unregister();
      }
    });
  }

  private void saveAllProfiles() throws Exception {
    ServiceRegistry registry = services;
    if (registry == null) {
      return;
    }
    Optional<ProfileService> profiles = registry.find(ProfileService.class);
    if (profiles.isPresent()) {
      profiles.get().saveAll().get(10, TimeUnit.SECONDS);
    }
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

  private void withServices(Consumer<ServiceRegistry> action) {
    ServiceRegistry registry = services;
    if (registry != null) {
      action.accept(registry);
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

  @FunctionalInterface
  private interface ShutdownStep {
    void run() throws Exception;
  }
}
