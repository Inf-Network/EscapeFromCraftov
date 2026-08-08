package com.birditch.raidcore.game.plugin;

import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.item.ItemRegistry;
import com.birditch.raidcore.api.loot.LootTableRegistry;
import com.birditch.raidcore.api.profile.ProfileService;
import com.birditch.raidcore.api.session.SessionService;
import com.birditch.raidcore.combat.CombatModule;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.RaidContext;
import com.birditch.raidcore.core.config.RaidCoreConfig;
import com.birditch.raidcore.core.database.Database;
import com.birditch.raidcore.core.database.DatabaseConfig;
import com.birditch.raidcore.core.database.DatabaseType;
import com.birditch.raidcore.core.database.HikariDatabase;
import com.birditch.raidcore.core.event.SimpleEventBus;
import com.birditch.raidcore.core.module.ModuleManager;
import com.birditch.raidcore.core.module.RaidModule;
import com.birditch.raidcore.core.service.ServiceRegistry;
import com.birditch.raidcore.economy.EconomyModule;
import com.birditch.raidcore.extraction.ExtractionModule;
import com.birditch.raidcore.game.GameModule;
import com.birditch.raidcore.item.ItemModule;
import com.birditch.raidcore.loot.LootModule;
import com.birditch.raidcore.game.plugin.command.CommandContext;
import com.birditch.raidcore.game.plugin.command.RaidCommand;
import com.birditch.raidcore.profile.ProfileModule;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/// Boots and tears down the RaidCore runtime inside the Paper server.
///
/// [#start] wires infrastructure, registers the domain modules in contract order
/// (item, loot, profile, economy, combat, game, extraction) and exposes commands.
/// [#stop] releases everything with each step guarded so later steps still run;
/// both are main-thread only.
public final class RaidApplication {

  private final RaidPlugin plugin;
  private final Logger logger;

  private @Nullable ServiceRegistry services;
  private @Nullable ModuleManager moduleManager;
  private @Nullable ExecutorService ioExecutor;
  private @Nullable Database database;

  public RaidApplication(RaidPlugin plugin) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.logger = plugin.getLogger();
  }

  /// Called once from [RaidPlugin#onEnable]; any thrown exception aborts startup
  /// and the plugin disables itself.
  public void start() {
    banner();
    ConfigLoader loader = new ConfigLoader(plugin);
    RaidCoreConfig config = loader.loadConfig();
    DatabaseConfig databaseConfig = loader.loadDatabaseConfig();
    config.validate();
    databaseConfig.validate();
    Messages messages = new Messages(loader.loadMessages());
    RaidContext context = createContext(config, databaseConfig);
    ModuleManager modules = createModules();
    modules.initializeAll(context);
    modules.startAll();
    registerPermissions();
    registerConnectionListener(context, messages);
    registerCommands(new CommandContext(plugin, context.services(), modules, messages, config));
    logSummary(context, modules);
  }

  /// Guarded teardown; each step failure is logged and the remaining steps run.
  public void stop() {
    runGuarded("destroy games", () -> withServices(
        registry -> registry.find(RaidGameService.class).ifPresent(RaidGameService::destroyAll)));
    runGuarded("save profiles", this::saveAllProfiles);
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
    logger.info("RaidCore disabled.");
  }

  private RaidContext createContext(RaidCoreConfig config, DatabaseConfig databaseConfig) {
    PaperRaidScheduler scheduler = new PaperRaidScheduler(plugin);
    SimpleEventBus eventBus = new SimpleEventBus(logger);
    ServiceRegistry registry = new ServiceRegistry();
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    Database db =
        databaseConfig.type() == DatabaseType.POSTGRESQL ? new HikariDatabase(databaseConfig) : null;
    services = registry;
    ioExecutor = executor;
    database = db;
    return new RaidContext(
        config, databaseConfig, registry, eventBus, scheduler, executor,
        Optional.ofNullable(db), logger);
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

  private void registerConnectionListener(RaidContext context, Messages messages) {
    ConnectionListener listener =
        new ConnectionListener(context.services(), context.scheduler(), messages, logger);
    plugin.getServer().getPluginManager().registerEvents(listener, plugin);
  }

  private void registerCommands(CommandContext commandContext) {
    plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
        event.registrar().register(
            RaidCommand.build(commandContext), "RaidCore root command", List.of("raidcore")));
  }

  private void banner() {
    logger.info("========================================");
    logger.info("  RaidCore - extraction raid framework");
    logger.info("========================================");
  }

  private void logSummary(RaidContext context, ModuleManager modules) {
    ServiceRegistry registry = context.services();
    String moduleIds =
        modules.modules().stream().map(RaidModule::id).collect(Collectors.joining(", "));
    logger.info(() -> "RaidCore v" + plugin.getPluginMeta().getVersion()
        + " on Minecraft " + Bukkit.getMinecraftVersion()
        + " (Java " + Runtime.version().feature() + ")");
    logger.info(() -> "Modules: " + moduleIds);
    logger.info(() -> "Items: " + registry.require(ItemRegistry.class).size()
        + ", loot tables: " + registry.require(LootTableRegistry.class).size()
        + ", games: " + registry.require(RaidGameService.class).activeCount()
        + ", sessions: " + registry.require(SessionService.class).count());
    logger.info("RaidCore READY.");
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
