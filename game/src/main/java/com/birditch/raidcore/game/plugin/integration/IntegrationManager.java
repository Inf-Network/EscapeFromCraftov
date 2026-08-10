package com.birditch.raidcore.game.plugin.integration;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.bukkit.RaidItemStacks;
import com.birditch.raidcore.core.service.ServiceRegistry;
import com.birditch.raidcore.game.plugin.editor.EditorSelectionService;
import java.util.List;
import java.util.logging.Logger;
import net.luckperms.api.LuckPermsProvider;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/// Validates required plugin integrations once and exposes isolated adapters.
///
/// The corresponding Paper metadata marks these plugins as hard dependencies.
/// A missing or unusable integration is a startup error rather than a silent
/// downgrade that could change permission, selection, menu, or display rules.
public final class IntegrationManager implements AutoCloseable {

  private final Plugin plugin;
  private final ServiceRegistry services;
  private final Logger logger;
  private final SelectionProvider selections;
  private final PermissionProvider permissions;
  private final HologramProvider holograms;
  private final RaidItemStacks itemStacks;
  private final int raidDurationSeconds;
  private @Nullable RaidPlaceholderExpansion placeholders;
  private boolean closed;

  public IntegrationManager(Plugin plugin, ServiceRegistry services,
      EditorSelectionService fallbackSelections) {
    this(plugin, services, fallbackSelections,
        new RaidItemStacks(services.require(com.birditch.raidcore.api.item.ItemRegistry.class)),
        1800);
  }

  public IntegrationManager(Plugin plugin, ServiceRegistry services,
      EditorSelectionService fallbackSelections, RaidItemStacks itemStacks,
      int raidDurationSeconds) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.services = Preconditions.checkNotNull(services, "services");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    Preconditions.checkArgument(raidDurationSeconds > 0, "raidDurationSeconds must be positive");
    this.raidDurationSeconds = raidDurationSeconds;
    this.logger = plugin.getLogger();
    requireEnabled("WorldEdit");
    requireEnabled("LuckPerms");
    requireEnabled("DecentHolograms");
    requireEnabled("PlaceholderAPI");
    logOptionalIntegrations();
    SelectionProvider nativeSelection = new RaidSelectionProvider(fallbackSelections);
    this.selections = worldEdit(nativeSelection);
    this.permissions = luckPerms();
    this.holograms = decentHolograms();
  }

  public SelectionProvider selections() { return selections; }
  public PermissionProvider permissions() { return permissions; }
  public HologramProvider holograms() { return holograms; }

  public void start() {
    try {
      RaidPlaceholderExpansion expansion = new RaidPlaceholderExpansion(plugin, services,
          itemStacks, raidDurationSeconds);
      if (!expansion.register()) {
        throw new IllegalStateException("PlaceholderAPI 拒绝注册 RaidCore 占位符扩展");
      }
      placeholders = expansion;
    } catch (RuntimeException | LinkageError e) {
      throw new IllegalStateException("PlaceholderAPI 适配器初始化失败，RaidCore 无法继续启动", e);
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    if (placeholders != null) {
      placeholders.unregister();
      placeholders = null;
    }
    holograms.close();
  }

  private SelectionProvider worldEdit(SelectionProvider fallback) {
    try {
      logFound("WorldEdit");
      return new CompositeSelectionProvider(List.of(new WorldEditSelectionProvider(), fallback));
    } catch (RuntimeException | LinkageError e) {
      throw new IllegalStateException("WorldEdit 适配器初始化失败，RaidCore 无法继续启动", e);
    }
  }

  private PermissionProvider luckPerms() {
    PermissionProvider fallback = new BukkitPermissionProvider();
    try {
      logFound("LuckPerms");
      return new LuckPermsPermissionProvider(LuckPermsProvider.get(), fallback);
    } catch (RuntimeException | LinkageError e) {
      throw new IllegalStateException("LuckPerms 适配器初始化失败，RaidCore 无法继续启动", e);
    }
  }

  private HologramProvider decentHolograms() {
    try {
      logFound("DecentHolograms");
      return new DecentHologramsProvider();
    } catch (RuntimeException | LinkageError e) {
      throw new IllegalStateException("DecentHolograms 适配器初始化失败，RaidCore 无法继续启动", e);
    }
  }

  private void requireEnabled(String name) {
    if (!enabled(name)) {
      throw new IllegalStateException("RaidCore 缺少硬依赖插件: " + name);
    }
    logFound(name);
  }

  private void logFound(String name) {
    Plugin found = plugin.getServer().getPluginManager().getPlugin(name);
    String version = found == null ? "unknown" : found.getPluginMeta().getVersion();
    logger.info("Integration " + name + ": FOUND " + version);
  }

  /**
   * Records the wider Paper ecosystem without making unused integrations a
   * deployment requirement. A detected plugin is deliberately reported as
   * "available for a future adapter" rather than being silently treated as a
   * source of gameplay truth.
   */
  private void logOptionalIntegrations() {
    for (String name : List.of("ProtocolLib", "Citizens", "MythicMobs", "ItemsAdder",
        "Oraxen", "FastAsyncWorldEdit", "Vault")) {
      Plugin found = plugin.getServer().getPluginManager().getPlugin(name);
      if (found == null || !plugin.getServer().getPluginManager().isPluginEnabled(name)) {
        logger.info("Integration " + name + ": NOT INSTALLED (optional)");
      } else {
        logger.info("Integration " + name + ": FOUND " + found.getPluginMeta().getVersion()
            + " (optional adapter not selected)");
      }
    }
  }

  private boolean enabled(String name) {
    return plugin.getServer().getPluginManager().isPluginEnabled(name);
  }
}
