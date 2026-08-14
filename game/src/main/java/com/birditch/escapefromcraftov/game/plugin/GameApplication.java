package com.birditch.escapefromcraftov.game.plugin;

import com.birditch.escapefromcraftov.api.event.EventSubscription;
import com.birditch.escapefromcraftov.api.CraftovException;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.combat.CombatService;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventoryService;
import com.birditch.escapefromcraftov.api.network.InstanceDirectory;
import com.birditch.escapefromcraftov.api.network.NetworkKeys;
import com.birditch.escapefromcraftov.api.network.ProvisionRequest;
import com.birditch.escapefromcraftov.api.profile.ProfileService;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.bukkit.BungeeConnector;
import com.birditch.escapefromcraftov.bukkit.CoreConfigMapper;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.bukkit.PaperCraftovScheduler;
import com.birditch.escapefromcraftov.bukkit.RedisConfigMapper;
import com.birditch.escapefromcraftov.bukkit.ShutdownSteps;
import com.birditch.escapefromcraftov.bukkit.YamlResources;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.bukkit.TrMenuBridge;
import com.birditch.escapefromcraftov.combat.CombatModule;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.time.SystemTimeSource;
import com.birditch.escapefromcraftov.core.CraftovContext;
import com.birditch.escapefromcraftov.core.config.EscapeFromCraftovConfig;
import com.birditch.escapefromcraftov.core.database.Database;
import com.birditch.escapefromcraftov.core.database.DatabaseConfig;
import com.birditch.escapefromcraftov.core.database.HibernateDatabase;
import com.birditch.escapefromcraftov.core.event.SimpleEventBus;
import com.birditch.escapefromcraftov.core.module.ModuleManager;
import com.birditch.escapefromcraftov.core.module.CraftovModule;
import com.birditch.escapefromcraftov.core.service.ServiceRegistry;
import com.birditch.escapefromcraftov.economy.EconomyModule;
import com.birditch.escapefromcraftov.extraction.ExtractionModule;
import com.birditch.escapefromcraftov.game.GameModule;
import com.birditch.escapefromcraftov.game.plugin.command.CommandContext;
import com.birditch.escapefromcraftov.game.plugin.command.CraftovCommand;
import com.birditch.escapefromcraftov.game.plugin.instance.BridgeDeps;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceProvisioner;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldBridge;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import com.birditch.escapefromcraftov.game.plugin.instance.ProvisionerDeps;
import com.birditch.escapefromcraftov.game.plugin.isolation.ChatIsolationListener;
import com.birditch.escapefromcraftov.game.plugin.isolation.VisibilityService;
import com.birditch.escapefromcraftov.game.plugin.listener.GameConnectionListener;
import com.birditch.escapefromcraftov.game.plugin.listener.GameCombatListener;
import com.birditch.escapefromcraftov.game.plugin.hud.CraftovSidebarService;
import com.birditch.escapefromcraftov.game.plugin.listener.ExtractionRegionListener;
import com.birditch.escapefromcraftov.game.plugin.editor.ManualMapRegistry;
import com.birditch.escapefromcraftov.game.plugin.editor.ManualMapService;
import com.birditch.escapefromcraftov.game.plugin.editor.ManualMapStore;
import com.birditch.escapefromcraftov.game.plugin.editor.ManualCraftovMapProvider;
import com.birditch.escapefromcraftov.game.plugin.editor.EditorConfirmationService;
import com.birditch.escapefromcraftov.game.plugin.editor.EditorSelectionService;
import com.birditch.escapefromcraftov.game.plugin.editor.EditorVisualizationService;
import com.birditch.escapefromcraftov.game.plugin.editor.EditorInteractionService;
import com.birditch.escapefromcraftov.game.plugin.editor.EditorObjectMenuController;
import com.birditch.escapefromcraftov.game.plugin.editor.EditorWandListener;
import com.birditch.escapefromcraftov.game.plugin.editor.MapEditorConfigMapper;
import com.birditch.escapefromcraftov.game.plugin.editor.MapEditorSettings;
import com.birditch.escapefromcraftov.game.plugin.integration.IntegrationManager;
import com.birditch.escapefromcraftov.game.plugin.network.PendingDeps;
import com.birditch.escapefromcraftov.game.plugin.network.PendingJoinHandler;
import com.birditch.escapefromcraftov.game.plugin.network.PendingPlayerGuard;
import com.birditch.escapefromcraftov.game.plugin.loot.CraftovLootListener;
import com.birditch.escapefromcraftov.game.plugin.network.PublisherDeps;
import com.birditch.escapefromcraftov.game.plugin.network.SnapshotPublisher;
import com.birditch.escapefromcraftov.game.plugin.start.BukkitCraftovStartNotifier;
import com.birditch.escapefromcraftov.game.plugin.start.CraftovStartCoordinator;
import com.birditch.escapefromcraftov.game.plugin.world.WorldService;
import com.birditch.escapefromcraftov.game.plugin.world.WorldServiceDeps;
import com.birditch.escapefromcraftov.item.ItemModule;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.loot.LootTableRegistry;
import com.birditch.escapefromcraftov.api.loot.LootService;
import com.birditch.escapefromcraftov.loot.LootModule;
import com.birditch.escapefromcraftov.map.EditorSessionService;
import com.birditch.escapefromcraftov.map.EditorSession;
import com.birditch.escapefromcraftov.map.ManualMapReferences;
import com.birditch.escapefromcraftov.map.ManualMapValidationSettings;
import com.birditch.escapefromcraftov.profile.ProfileModule;
import com.birditch.escapefromcraftov.redis.JedisMessageBroker;
import com.birditch.escapefromcraftov.redis.JsonCodec;
import com.birditch.escapefromcraftov.redis.RedisAssignmentStore;
import com.birditch.escapefromcraftov.redis.RedisConfig;
import com.birditch.escapefromcraftov.redis.RedisConnector;
import com.birditch.escapefromcraftov.redis.RedisInstanceDirectory;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
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

  private final CraftovGamePlugin plugin;
  private final Logger logger;

  private @Nullable ServiceRegistry services;
  private @Nullable ModuleManager moduleManager;
  private @Nullable ExecutorService ioExecutor;
  private @Nullable Database database;
  private @Nullable RedisConnector redisConnector;
  private @Nullable WorldService instanceWorlds;
  private @Nullable InstanceWorldIndex instanceWorldIndex;
  private @Nullable JedisMessageBroker messageBroker;
  private @Nullable BungeeConnector bungeeConnector;
  private @Nullable EventSubscription bridgeSubscription;
  private @Nullable EventSubscription lootSubscription;
  private @Nullable CraftovLootListener lootMenus;
  private @Nullable InstanceProvisioner provisioner;
  private @Nullable SnapshotPublisher publisher;
  private @Nullable CraftovStartCoordinator startCoordinator;
  private @Nullable EditorVisualizationService editorVisualization;
  private @Nullable EditorInteractionService editorInteraction;
  private @Nullable EditorObjectMenuController editorObjectMenus;
  private @Nullable EditorSessionService editorSessions;
  private @Nullable IntegrationManager integrationManager;
  private @Nullable CraftovSidebarService sidebar;
  private @Nullable VisibilityService visibility;

  /// Everything loaded from the plugin's YAML resources, validated.
  private record Configs(
      EscapeFromCraftovConfig core,
      DatabaseConfig database,
      GameServerConfig server,
      GameplaySettings gameplay,
      RedisConfig redis,
      MapEditorSettings editor,
      Messages messages) {}

  private record MapRuntime(
      ManualMapRegistry registry,
      ManualMapService service,
      EditorSessionService sessions,
      EditorSelectionService selections,
      EditorWandListener wand,
      EditorConfirmationService confirmations,
      EditorObjectMenuController objectMenus,
      EditorVisualizationService visualization,
      EditorInteractionService interaction,
      IntegrationManager integrations,
      ManualCraftovMapProvider provider) {}

  private record MapInfrastructure(
      ManualMapRegistry registry,
      ManualMapService service,
      EditorSessionService sessions,
      EditorSelectionService selections,
      EditorWandListener wand,
      EditorConfirmationService confirmations,
      ManualCraftovMapProvider provider) {}

  /// Wiring produced by [#wireNetwork] that later startup steps still need.
  private record Wiring(
      InstanceDirectory directory,
      InstanceWorldIndex index,
      VisibilityService visibility,
      PendingJoinHandler pendingJoins,
      CraftovItemStacks itemStacks,
      GameCombatListener combat,
      CraftovSidebarService sidebar,
      CraftovLootListener lootMenus,
      InstanceWorldBridge bridge) {}

  public GameApplication(CraftovGamePlugin plugin) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.logger = plugin.getLogger();
  }

  /// Called once from [CraftovGamePlugin#onEnable]; any thrown exception aborts
  /// startup and the plugin disables itself.
  public void start() {
    StartupSummary summary = new StartupSummary(plugin, logger);
    summary.banner();
    Configs configs = loadConfigs();
    TrMenuBridge trMenu = new TrMenuBridge(plugin);
    trMenu.requireAvailable();
    boolean installed = trMenu.installDefault(
        "trmenu/escapefromcraftov_editor_toolbar.yml", "escapefromcraftov_editor_toolbar.yml");
    installed |= trMenu.installDefault(
        "trmenu/escapefromcraftov_editor_object.yml", "escapefromcraftov_editor_object.yml");
    installed |= trMenu.installDefault("trmenu/escapefromcraftov_loot.yml", "escapefromcraftov_loot.yml");
    if (installed) trMenu.reloadMenus();
    trMenu.requireMenusLoaded("escapefromcraftov_editor_toolbar", "escapefromcraftov_editor_object",
        "escapefromcraftov_loot");
    logger.info("Menu provider: " + trMenu.status()
        + " (all EscapeFromCraftov inventory menus are rendered by TrMenu)");
    CraftovContext context = createContext(configs.core(), configs.database());
    MapInfrastructure mapInfrastructure = createMapInfrastructure(context, configs);
    ModuleManager modules = createModules(mapInfrastructure.provider());
    modules.initializeAll(context);
    modules.startAll();
    MapRuntime maps = createMapRuntime(context, configs, trMenu, mapInfrastructure);
    maps.integrations().start();
    auditEnabledMaps(maps);
    Wiring wiring = wireNetwork(context, configs, maps, trMenu);
    registerPermissions();
    registerListeners(context, configs, wiring, maps);
    registerCommands(
        new CommandContext(plugin, context.services(), modules, configs.messages(), configs.core(),
            configs.server(), context.scheduler(), maps.service(), maps.registry(), maps.sessions(),
            maps.selections(), maps.wand(), maps.confirmations(), maps.objectMenus(),
            maps.visualization(), maps.interaction(),
            wiring.lootMenus(), trMenu, configs.editor(),
            maps.integrations().selections(), maps.integrations().permissions()));
    summary.log(context.services(), modules, configs.server(), new StartupSummary.SummaryConfigs(
        configs.core(), configs.redis(), summary.probeRedis(wiring.directory())));
  }

  /// Guarded teardown; each step failure is logged and the remaining steps run.
  public void stop() {
    ShutdownSteps steps = new ShutdownSteps(logger);
    steps.run("settle active craftov losses", this::settleActiveCraftovLosses);
    steps.run("destroy games", () -> withServices(
        registry -> registry.find(CraftovGameService.class).ifPresent(CraftovGameService::destroyAll)));
    steps.run("destroy instance worlds", this::destroyInstanceWorlds);
    steps.run("save profiles", this::saveAllProfiles);
    stopNetwork(steps);
    steps.run("stop modules", () -> {
      if (moduleManager != null) {
        moduleManager.stopAll();
      }
    });
    steps.run("unregister listeners", () -> HandlerList.unregisterAll(plugin));
    steps.run("stop dynamic Tab isolation", () -> {
      if (visibility != null) {
        visibility.close();
        visibility = null;
      }
    });
    steps.run("cancel scheduled tasks", () -> Bukkit.getScheduler().cancelTasks(plugin));
    steps.run("stop map editor visualization", () -> {
      if (editorInteraction != null) editorInteraction.close();
      if (editorObjectMenus != null) editorObjectMenus.close();
      if (editorVisualization != null) editorVisualization.close();
      if (editorSessions != null) {
        long dirty = editorSessions.all().stream().filter(EditorSession::dirty).count();
        if (dirty > 0) logger.warning("服务端关闭时仍有 " + dirty + " 个未保存地图编辑会话");
      }
    });
    steps.run("stop dynamic Sidebar", () -> {
      if (sidebar != null) {
        sidebar.close();
        sidebar = null;
      }
    });
    steps.run("stop optional integrations", () -> {
      if (integrationManager != null) integrationManager.close();
    });
    ExecutorService executor = ioExecutor;
    if (executor != null) {
      steps.shutdownExecutor("shut down IO executor", executor);
    }
    steps.run("close database", () -> {
      if (database != null) {
        database.close();
      }
    });
    steps.run("clear services", () -> withServices(ServiceRegistry::clear));
    logger.info("EscapeFromCraftovGame 已卸载。");
  }

  /// Stop is the same as an unexpected craftov loss for anyone still inside a
  /// room. Wait for the durable settlement futures before tearing down the
  /// world and database layers; otherwise a restart could leave CRAFTOV rows
  /// visible until a later loadout claim happens to purge them.
  private void settleActiveCraftovLosses() throws Exception {
    ServiceRegistry registry = services;
    if (registry == null) return;
    Optional<CraftovGameService> games = registry.find(CraftovGameService.class);
    Optional<PlayerInventoryService> inventories = registry.find(PlayerInventoryService.class);
    if (games.isEmpty() || inventories.isEmpty()) return;
    List<CompletableFuture<com.birditch.escapefromcraftov.api.inventory.CraftovSettlementResult>> settlements =
        games.get().all().stream()
        .flatMap(game -> game.players().stream()
            .map(player -> inventories.get().settleDeath(player.playerId(), game.id())))
        .toList();
    if (settlements.isEmpty()) return;
    CompletableFuture.allOf(settlements.toArray(CompletableFuture[]::new))
        .get(15, TimeUnit.SECONDS);
  }

  /// Domain game destruction and Bukkit world destruction are separate
  /// responsibilities. Keep them ordered so a room never leaves a loaded
  /// `craftov_<id>` world behind during a clean plugin stop.
  private void destroyInstanceWorlds() throws Exception {
    WorldService worlds = instanceWorlds;
    InstanceWorldIndex index = instanceWorldIndex;
    if (worlds == null || index == null) return;
    List<com.birditch.escapefromcraftov.api.game.CraftovGameId> gameIds = index.gameIds();
    if (gameIds.isEmpty()) return;
    CompletableFuture<?>[] destructions = gameIds.stream()
        .map(worlds::destroyInstanceWorld)
        .toArray(CompletableFuture[]::new);
    CompletableFuture.allOf(destructions).get(15, TimeUnit.SECONDS);
    gameIds.forEach(index::remove);
  }

  private Configs loadConfigs() {
    var configYaml = YamlResources.loadMainConfig(
        plugin, Map.of("database", "database.yml", "redis", "redis.yml"));
    EscapeFromCraftovConfig config = CoreConfigMapper.readCoreConfig(configYaml);
    GameServerConfig serverConfig = GameConfigMapper.read(configYaml);
    GameplaySettings gameplay = GameConfigMapper.readGameplay(configYaml);
    DatabaseConfig databaseConfig = CoreConfigMapper.readDatabaseConfig(configYaml);
    RedisConfig redisConfig = RedisConfigMapper.read(configYaml);
    config.validate();
    databaseConfig.validate();
    serverConfig.validate(config.game().maxInstances(), config.game().maxPlayers());
    gameplay.validate();
    redisConfig.validate();
    Messages messages = new Messages(
        CoreConfigMapper.readMessages(YamlResources.loadOrSaveDefault(plugin, "messages.yml")));
    MapEditorSettings editor = MapEditorConfigMapper.read(configYaml);
    return new Configs(config, databaseConfig, serverConfig, gameplay, redisConfig, editor,
        messages);
  }

  private CraftovContext createContext(EscapeFromCraftovConfig config, DatabaseConfig databaseConfig) {
    PaperCraftovScheduler scheduler = new PaperCraftovScheduler(plugin);
    SimpleEventBus eventBus = new SimpleEventBus(logger);
    ServiceRegistry registry = new ServiceRegistry();
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    Database db = createDatabase(databaseConfig);
    services = registry;
    ioExecutor = executor;
    database = db;
    return new CraftovContext(
        config, databaseConfig, registry, eventBus, scheduler, executor, db, logger);
  }

  /// The game server hard-requires PostgreSQL: a pool that cannot be built
  /// aborts startup with an operator-facing explanation instead of limping on.
  private Database createDatabase(DatabaseConfig databaseConfig) {
    try {
      return new HibernateDatabase(databaseConfig);
    } catch (RuntimeException e) {
      logger.severe(() -> "无法连接 PostgreSQL 数据库("
          + databaseConfig.host() + ":" + databaseConfig.port()
          + "/" + databaseConfig.database() + ")。"
          + "请检查 config.yml 的 database 节、PostgreSQL 服务是否可达、账号密码与库权限是否正确。"
          + "游戏服务端强依赖数据库,本次启动已中止。");
      throw e;
    }
  }

  private ModuleManager createModules(com.birditch.escapefromcraftov.game.CraftovMapProvider maps) {
    ModuleManager manager = new ModuleManager(logger);
    moduleManager = manager;
    manager.register(new ItemModule());
    manager.register(new LootModule());
    manager.register(new ProfileModule());
    manager.register(new EconomyModule());
    manager.register(new CombatModule());
    manager.register(new GameModule(maps));
    manager.register(new ExtractionModule());
    return manager;
  }

  private MapInfrastructure createMapInfrastructure(CraftovContext context, Configs configs) {
    try {
      Files.createDirectories(plugin.getDataFolder().toPath().resolve("imports"));
    } catch (IOException e) {
      throw new CraftovException("无法创建地图导入目录", e);
    }
    ManualMapStore manualStore = new ManualMapStore(
        plugin.getDataFolder().toPath().resolve("maps"), configs.editor().retainedRevisions());
    ManualMapRegistry manualRegistry = new ManualMapRegistry(manualStore);
    ManualMapService manualService = new ManualMapService(manualStore, manualRegistry,
        configs.editor().validation(), world -> references(context.services(), world),
        plugin.getDataFolder().toPath().resolve(configs.server().mapTemplateDir()),
        plugin.getServer().getWorldContainer().toPath());
    EditorSessionService sessions = new EditorSessionService();
    editorSessions = sessions;
    EditorSelectionService selections = new EditorSelectionService();
    EditorWandListener wand = new EditorWandListener(plugin, selections, sessions);
    EditorConfirmationService confirmations = new EditorConfirmationService(
        java.time.Duration.ofSeconds(configs.editor().confirmationSeconds()));
    ManualCraftovMapProvider provider = new ManualCraftovMapProvider(manualRegistry, context.services());
    logger.info(() -> "已加载地图配置:" + manualRegistry.all().size() + " 张");
    return new MapInfrastructure(manualRegistry, manualService, sessions, selections, wand,
        confirmations, provider);
  }

  private MapRuntime createMapRuntime(CraftovContext context, Configs configs,
      TrMenuBridge trMenu, MapInfrastructure infrastructure) {
    EditorSessionService sessions = infrastructure.sessions();
    IntegrationManager integrations = new IntegrationManager(plugin, context.services(),
        infrastructure.selections(),
        new CraftovItemStacks(context.services().require(ItemRegistry.class)),
        configs.core().game().durationSeconds());
    integrationManager = integrations;
    EditorVisualizationService visualization = new EditorVisualizationService(plugin, sessions,
        configs.editor().visualizationRefreshTicks(), configs.editor().maximumObjectsPerFrame(),
        configs.editor().maximumHologramsPerPlayer(),
        integrations.holograms(), integrations.selections());
    editorVisualization = visualization;
    EditorObjectMenuController objectMenus = new EditorObjectMenuController(plugin, sessions,
        infrastructure.confirmations(), context.services(), integrations.permissions(), logger,
        trMenu);
    editorObjectMenus = objectMenus;
    EditorInteractionService interaction = new EditorInteractionService(plugin, sessions,
        objectMenus, configs.editor().visualizationRefreshTicks());
    editorInteraction = interaction;
    return new MapRuntime(infrastructure.registry(), infrastructure.service(), sessions,
        infrastructure.selections(), infrastructure.wand(), infrastructure.confirmations(),
        objectMenus, visualization, interaction, integrations, infrastructure.provider());
  }

  private ManualMapReferences references(ServiceRegistry services, String worldName) {
    Set<String> lootTables = services.find(LootTableRegistry.class).stream()
        .flatMap(registry -> registry.all().stream())
        .map(table -> table.id().value()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    Set<String> itemIds = services.find(ItemRegistry.class).stream()
        .flatMap(registry -> registry.all().stream())
        .map(item -> item.id().value()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    boolean worldExists = Bukkit.getWorld(worldName) != null || Files.isRegularFile(
        plugin.getServer().getWorldContainer().toPath().resolve(worldName).resolve("level.dat"));
    return new ManualMapReferences(worldExists, lootTables, itemIds);
  }

  private void auditEnabledMaps(MapRuntime maps) {
    for (var map : List.copyOf(maps.registry().all())) {
      if (!map.enabled()) continue;
      var validation = maps.service().validate(map);
      if (validation.hasErrors()) {
        maps.service().setEnabled(map.mapId(), false);
        logger.severe("地图 " + map.mapId() + " 启动验证失败，已自动禁用；ERROR="
            + validation.errorCount());
      }
    }
  }

  /// Builds the Redis fabric and the per-instance world machinery, starts the
  /// self-provisioner and the snapshot heartbeat, and routes provision hints
  /// back to the main thread.
  private Wiring wireNetwork(
      CraftovContext context, Configs configs, MapRuntime mapRuntime, TrMenuBridge trMenu) {
    ManualMapRegistry maps = mapRuntime.registry();
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
    CraftovGameService games = context.services().require(CraftovGameService.class);
    CraftovScheduler scheduler = context.scheduler();
    GameServerConfig server = configs.server();
    WorldService worlds = new WorldService(new WorldServiceDeps(
        plugin, scheduler, executor,
        plugin.getDataFolder().toPath().resolve(server.mapTemplateDir()),
        server.allowFlatFallback(), maps, logger));
    InstanceWorldIndex index = new InstanceWorldIndex();
    instanceWorldIndex = index;
    instanceWorlds = worlds;
    VisibilityService visibility = new VisibilityService(plugin, index, games, configs.messages());
    visibility.start();
    this.visibility = visibility;
    CraftovItemStacks itemStacks = new CraftovItemStacks(context.services().require(ItemRegistry.class));
    PlayerInventoryService inventories = context.services().require(PlayerInventoryService.class);
    GameCombatListener combatListener = new GameCombatListener(plugin, games,
        context.services().require(CombatService.class), inventories,
        context.services().require(com.birditch.escapefromcraftov.api.profile.ProgressionService.class),
        index, scheduler, configs.messages(), configs.gameplay());
    CraftovStartCoordinator starts = new CraftovStartCoordinator(games, index, scheduler,
        new BukkitCraftovStartNotifier(configs.messages()), logger,
        server.minimumPlayers(), server.startCountdownSeconds());
    startCoordinator = starts;
    CraftovSidebarService sidebarService = new CraftovSidebarService(plugin, games,
        mapRuntime.sessions(), mapRuntime.service(),
        context.services().require(com.birditch.escapefromcraftov.api.extraction.ExtractionService.class),
        context.services().require(CombatService.class), itemStacks, configs.messages(),
        configs.gameplay(),
        combatListener, starts,
        configs.core().game().durationSeconds(), configs.core().game().maxPlayers(),
        server.minimumPlayers());
    sidebarService.start();
    sidebar = sidebarService;
    starts.start();
    CraftovLootListener lootMenuController = new CraftovLootListener(games,
        context.services().require(LootService.class), itemStacks, index, configs.messages(),
        configs.gameplay().lootContainerSize(), trMenu, scheduler);
    lootMenus = lootMenuController;
    lootSubscription = context.eventBus().register(lootMenuController);
    InstanceWorldBridge instanceBridge = new InstanceWorldBridge(
        new BridgeDeps(games, context.services().require(ProfileService.class),
            context.services().require(com.birditch.escapefromcraftov.api.profile.ProgressionService.class),
            inventories,
            context.services().require(
                com.birditch.escapefromcraftov.api.extraction.ExtractionService.class), itemStacks,
            context.eventBus(), worlds, index, visibility, bungee, server, configs.messages(), trMenu,
            scheduler, configs.gameplay().extractionExperience(), logger));
    bridgeSubscription = context.eventBus().register(instanceBridge);
    InstanceProvisioner instanceProvisioner = new InstanceProvisioner(
        new ProvisionerDeps(games, configs.core().game(), server, maps::isEnabled,
            scheduler, logger));
    provisioner = instanceProvisioner;
    SnapshotPublisher snapshotPublisher = new SnapshotPublisher(new PublisherDeps(
        games, index, directory, server, configs.core(),
        new SystemTimeSource(), scheduler, logger));
    publisher = snapshotPublisher;
    // A hard stop can leave disposable craftov_<id> folders behind. Finish the
    // namespaced cleanup before provisioning new rooms so an old directory is
    // never overwritten by a freshly generated game id.
    worlds.cleanupOrphanInstanceWorlds().whenComplete((_, cleanupError) -> scheduler.runSync(() -> {
      if (!plugin.isEnabled()) return;
      if (cleanupError != null) {
        logger.log(Level.WARNING, "孤立实例世界清理未完成，实例补备仍会继续", cleanupError);
      }
      instanceProvisioner.start();
      snapshotPublisher.start();
    }));
    broker.subscribe(NetworkKeys.PROVISION_CHANNEL,
        payload -> handleProvisionRequest(codec, payload, scheduler, instanceProvisioner));
    PendingJoinHandler pendingJoins = new PendingJoinHandler(new PendingDeps(
        assignments, games, server, index, bungee, inventories, itemStacks, trMenu, visibility,
        mapRuntime.sessions(), mapRuntime.selections(),
        scheduler, configs.messages(), logger));
    return new Wiring(directory, index, visibility, pendingJoins, itemStacks, combatListener,
        sidebarService, lootMenuController, instanceBridge);
  }

  private void handleProvisionRequest(JsonCodec codec, String payload, CraftovScheduler scheduler,
      InstanceProvisioner instanceProvisioner) {
    try {
      ProvisionRequest request = codec.fromJson(payload, ProvisionRequest.class);
      scheduler.runSync(() -> instanceProvisioner.onProvisionRequest(request));
    } catch (RuntimeException e) {
      logger.log(Level.WARNING, "忽略无法解析的实例扩容消息。", e);
    }
  }

  private void registerPermissions() {
    PluginManager pluginManager = plugin.getServer().getPluginManager();
    addPermission(pluginManager, new Permission(
        CraftovCommand.PERMISSION_ADMIN, "Access to EscapeFromCraftov admin commands", PermissionDefault.OP));
    addPermission(pluginManager, new Permission(
        CraftovCommand.PERMISSION_INFO, "Access to EscapeFromCraftov info commands", PermissionDefault.TRUE));
    for (String node : List.of(
        com.birditch.escapefromcraftov.api.CraftovPermissions.EDITOR,
        com.birditch.escapefromcraftov.api.CraftovPermissions.EDITOR_MAP,
        com.birditch.escapefromcraftov.api.CraftovPermissions.EDITOR_SPAWN,
        com.birditch.escapefromcraftov.api.CraftovPermissions.EDITOR_LOOT,
        com.birditch.escapefromcraftov.api.CraftovPermissions.EDITOR_AI,
        com.birditch.escapefromcraftov.api.CraftovPermissions.EDITOR_EXTRACTION,
        com.birditch.escapefromcraftov.api.CraftovPermissions.EDITOR_POI,
        com.birditch.escapefromcraftov.api.CraftovPermissions.EDITOR_DELETE,
        com.birditch.escapefromcraftov.api.CraftovPermissions.EDITOR_SAVE,
        com.birditch.escapefromcraftov.api.CraftovPermissions.EDITOR_ROLLBACK)) {
      addPermission(pluginManager, new Permission(node,
          "Access to EscapeFromCraftov map editor feature " + node, PermissionDefault.OP));
    }
  }

  private static void addPermission(PluginManager pluginManager, Permission permission) {
    if (pluginManager.getPermission(permission.getName()) == null) {
      pluginManager.addPermission(permission);
    }
  }

  private void registerListeners(CraftovContext context, Configs configs, Wiring wiring,
      MapRuntime maps) {
    PluginManager pluginManager = plugin.getServer().getPluginManager();
    pluginManager.registerEvents(new ChatIsolationListener(wiring.index(), configs.messages()),
        plugin);
    pluginManager.registerEvents(new GameConnectionListener(new GameConnectionListener.Deps(
        context.services(), context.scheduler(), configs.messages(), wiring.pendingJoins(),
        wiring.visibility(), wiring.index(), logger)), plugin);
    pluginManager.registerEvents(new PendingPlayerGuard(wiring.pendingJoins()), plugin);
    pluginManager.registerEvents(new ExtractionRegionListener(
        context.services().require(CraftovGameService.class),
        context.services().require(com.birditch.escapefromcraftov.api.extraction.ExtractionService.class),
        wiring.index(), configs.messages()), plugin);
    pluginManager.registerEvents(wiring.combat(), plugin);
    pluginManager.registerEvents(wiring.bridge(), plugin);
    pluginManager.registerEvents(wiring.lootMenus(), plugin);
    pluginManager.registerEvents(maps.visualization(), plugin);
    pluginManager.registerEvents(maps.interaction(), plugin);
    pluginManager.registerEvents(maps.objectMenus(), plugin);
    pluginManager.registerEvents(maps.wand(), plugin);
  }

  private void registerCommands(CommandContext commandContext) {
    plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
        event.registrar().register(
            CraftovCommand.build(commandContext), "EscapeFromCraftov root command", List.of("escapefromcraftov")));
  }

  /// Network teardown runs before module stop so the directory heartbeat
  /// fast-expires and no provision hint arrives mid-shutdown.
  private void stopNetwork(ShutdownSteps steps) {
    steps.run("unsubscribe instance bridge", () -> {
      if (bridgeSubscription != null) {
        bridgeSubscription.unsubscribe();
      }
    });
    steps.run("unsubscribe loot listener", () -> {
      if (lootSubscription != null) {
        lootSubscription.unsubscribe();
        lootSubscription = null;
      }
    });
    steps.run("close TrMenu Loot controller", () -> {
      if (lootMenus != null) {
        lootMenus.close();
        lootMenus = null;
      }
    });
    steps.run("stop instance provisioner", () -> {
      if (provisioner != null) {
        provisioner.stop();
      }
    });
    steps.run("stop craftov start coordinator", () -> {
      if (startCoordinator != null) {
        startCoordinator.close();
        startCoordinator = null;
      }
    });
    steps.run("stop snapshot publisher", () -> {
      if (publisher != null) {
        publisher.stop();
      }
    });
    steps.run("close message broker", () -> {
      if (messageBroker != null) {
        messageBroker.close();
      }
    });
    steps.run("close redis", () -> {
      if (redisConnector != null) {
        redisConnector.close();
      }
    });
    steps.run("unregister proxy channel", () -> {
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

  private void withServices(Consumer<ServiceRegistry> action) {
    ServiceRegistry registry = services;
    if (registry != null) {
      action.accept(registry);
    }
  }
}
