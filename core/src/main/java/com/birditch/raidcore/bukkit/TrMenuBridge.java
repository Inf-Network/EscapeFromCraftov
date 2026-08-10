package com.birditch.raidcore.bukkit;

import com.birditch.raidcore.common.check.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jspecify.annotations.Nullable;

/**
 * Paper-only adapter for the required TrMenu plugin.
 *
 * RaidCore deliberately does not compile against TrMenu's internal API. Its
 * supported community build is TrMenu 3.12.5 (see the public v3 documentation
 * and the CoderKuo/TrMenu repository). The bridge first uses the public API when
 * that build is present, then falls back to the stable `/trmenu open` command.
 * TrMenu remains an independently installed server plugin and is never bundled
 * into a RaidCore jar. TrMenu owns every inventory UI; RaidCore supplies only
 * validated actions and dynamic ItemStacks through TrMenu's public event.
 */
public final class TrMenuBridge {

  public static final String SUPPORTED_VERSION = "3.12.5";
  /**
   * TrMenu changed its Java package while keeping the public API contract. A
   * server may therefore expose either namespace depending on the community
   * build that was installed. Keep this compatibility list at the reflection
   * boundary instead of leaking TrMenu classes into RaidCore modules.
   */
  private static final String[] API_TYPES = {
      "trplugins.menu.api.TrMenuAPI",
      "me.arasple.mc.trmenu.api.TrMenuAPI"
  };
  private static final String[] MENU_OPEN_REASON_TYPES = {
      "trplugins.menu.api.event.MenuOpenEvent$Reason",
      "me.arasple.mc.trmenu.api.event.MenuOpenEvent$Reason"
  };
  private static final String[] CUSTOM_ITEM_SOURCE_EVENT_TYPES = {
      "trplugins.menu.api.event.CustomItemSourceEvent",
      "me.arasple.mc.trmenu.api.event.CustomItemSourceEvent"
  };
  private static final String[] MENU_SESSION_TYPES = {
      "trplugins.menu.module.display.MenuSession",
      "me.arasple.mc.trmenu.module.display.MenuSession"
  };
  private static final Pattern SAFE_TOKEN = Pattern.compile("[a-zA-Z0-9_-]+");
  private final Plugin owner;
  private final Logger logger;

  public TrMenuBridge(Plugin owner) {
    this.owner = Preconditions.checkNotNull(owner, "owner");
    this.logger = owner.getLogger();
  }

  public boolean available() {
    return owner.getServer().getPluginManager().isPluginEnabled("TrMenu");
  }

  /** Fails fast with an actionable message when the hard dependency is absent. */
  public void requireAvailable() {
    if (!available()) {
      throw new IllegalStateException(
          "RaidCore requires TrMenu " + SUPPORTED_VERSION + " in the server plugins directory");
    }
    Plugin trMenu = owner.getServer().getPluginManager().getPlugin("TrMenu");
    String version = trMenu == null ? "" : trMenu.getPluginMeta().getVersion();
    if (!SUPPORTED_VERSION.equals(version)) {
      throw new IllegalStateException("RaidCore supports TrMenu " + SUPPORTED_VERSION
          + " exactly, but found " + (version.isBlank() ? "an unknown version" : version));
    }
  }

  /** Opens a named TrMenu menu for the player, returning false only on runtime failure. */
  public boolean open(Player player, String menu, String... arguments) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkArgument(validToken(menu), "invalid TrMenu menu name");
    Preconditions.checkNotNull(arguments, "arguments");
    for (String argument : arguments) {
      Preconditions.checkArgument(validToken(argument), "invalid TrMenu argument");
    }
    if (!available()) return false;
    Boolean apiResult = openViaPublicApi(player, menu, arguments);
    if (apiResult != null) return apiResult;
    // The command syntax treats the token after the menu as the target player.
    // TrMenu intentionally restricts /trmenu open to operators; use the
    // console for this compatibility path so a normal player never needs an
    // elevated permission just to open a RaidCore menu.
    StringBuilder command = new StringBuilder("trmenu open ").append(menu)
        .append(' ').append(player.getName());
    for (String argument : arguments) {
      command.append(' ').append(argument);
    }
    try {
      return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.toString());
    } catch (RuntimeException | LinkageError error) {
      logger.log(Level.WARNING, "TrMenu 打开菜单失败: " + menu, error);
      return false;
    }
  }

  /**
   * Calls TrMenu 3.12.x's public API without putting its classes on RaidCore's
   * compile or runtime class path. A null result means the API is unavailable
   * or changed, so the caller should use the command compatibility path.
   */
  private @Nullable Boolean openViaPublicApi(Player player, String menu, String[] arguments) {
    Plugin trMenu = owner.getServer().getPluginManager().getPlugin("TrMenu");
    if (trMenu == null) return null;
    try {
      ClassLoader loader = trMenu.getClass().getClassLoader();
      Class<?> apiType = loadFirst(loader, API_TYPES);
      Object api = apiType.getField("INSTANCE").get(null);
      Method getMenuById = apiType.getMethod("getMenuById", String.class);
      Object menuObject = getMenuById.invoke(api, menu);
      if (menuObject == null) return false;

      // TrMenu skips the open callback when the same menu is already open and
      // performs only a page refresh. Update that live session first so title
      // arguments and dynamic pages cannot retain stale values.
      setArgumentsIfViewing(loader, player, menuObject, arguments);

      Class<?> reasonType = loadFirst(loader, MENU_OPEN_REASON_TYPES);
      @SuppressWarnings({"unchecked", "rawtypes"})
      Object reason = Enum.valueOf((Class<? extends Enum>) reasonType, "PLAYER_COMMAND");
      Method open = null;
      for (Method candidate : menuObject.getClass().getMethods()) {
        Class<?>[] parameters = candidate.getParameterTypes();
        if (candidate.getName().equals("open")
            && parameters.length == 4
            && parameters[0] == Player.class
            && parameters[1] == Integer.class
            && parameters[2] == reasonType
            && parameters[3] == Consumer.class) {
          open = candidate;
          break;
        }
      }
      if (open == null) return null;
      Consumer<Object> configure = session -> {
        try {
          Method setter = session.getClass().getMethod("setArguments", String[].class);
          setter.invoke(session, (Object) arguments.clone());
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException error) {
          throw new IllegalStateException("TrMenu session arguments API is unavailable", error);
        }
      };
      open.invoke(menuObject, player, null, reason, configure);
      Object openedSession = session(loader, player);
      return openedSession != null
          && openedSession.getClass().getMethod("getMenu").invoke(openedSession) == menuObject;
    } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException error) {
      return null;
    } catch (InvocationTargetException | IllegalAccessException | RuntimeException | LinkageError error) {
      logger.log(Level.FINE, "TrMenu public API could not open menu: " + menu, error);
      return null;
    }
  }

  /**
   * Checks the actual TrMenu session instead of trusting a RaidCore-side flag.
   * Menu action endpoints use this to reject copied commands after the virtual
   * inventory has already been closed.
   */
  public boolean isViewing(Player player, String menu) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkArgument(validToken(menu), "invalid TrMenu menu name");
    if (!available()) return false;
    Plugin trMenu = owner.getServer().getPluginManager().getPlugin("TrMenu");
    if (trMenu == null) return false;
    try {
      ClassLoader loader = trMenu.getClass().getClassLoader();
      Class<?> apiType = loadFirst(loader, API_TYPES);
      Object api = apiType.getField("INSTANCE").get(null);
      Object menuObject = apiType.getMethod("getMenuById", String.class).invoke(api, menu);
      if (menuObject == null) return false;
      Object session = session(loader, player);
      if (session == null) return false;
      return session.getClass().getMethod("getMenu").invoke(session) == menuObject;
    } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException
        | InvocationTargetException | IllegalAccessException | RuntimeException | LinkageError error) {
      logger.log(Level.FINE, "无法读取 TrMenu 当前会话: " + menu, error);
      return false;
    }
  }

  /**
   * Closes a RaidCore menu through TrMenu's menu lifecycle. This is deliberately
   * separate from creating a Bukkit inventory: TrMenu remains the owner of the
   * virtual window and its session, including when a validation failure or a
   * server shutdown interrupts an action.
   */
  public boolean close(Player player, String menu) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkArgument(validToken(menu), "invalid TrMenu menu name");
    if (!available()) return false;
    Plugin trMenu = owner.getServer().getPluginManager().getPlugin("TrMenu");
    if (trMenu == null) return false;
    try {
      ClassLoader loader = trMenu.getClass().getClassLoader();
      Class<?> apiType = loadFirst(loader, API_TYPES);
      Object api = apiType.getField("INSTANCE").get(null);
      Object menuObject = apiType.getMethod("getMenuById", String.class)
          .invoke(api, menu);
      if (menuObject == null) return false;
      Object activeSession = session(loader, player);
      if (activeSession == null) return true;
      Method getMenu = activeSession.getClass().getMethod("getMenu");
      if (getMenu.invoke(activeSession) != menuObject) return true;
      return closeSession(activeSession);
    } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException
        | InvocationTargetException | IllegalAccessException | RuntimeException | LinkageError error) {
      // There is deliberately no Bukkit inventory fallback. A TrMenu API
      // mismatch must remain visible as an integration fault instead of
      // silently introducing a second menu lifecycle.
      logger.log(Level.SEVERE, "无法通过 TrMenu 关闭菜单: " + menu, error);
      return false;
    }
  }

  /**
   * Closes whichever TrMenu session is currently active for the player.
   *
   * Game transitions use this method because they must close an open menu
   * before clearing or replacing the player's inventory, even when the active
   * menu belongs to another plugin. There is intentionally no Bukkit
   * {@code closeInventory()} fallback: TrMenu owns the session and therefore
   * must receive its close lifecycle.
   */
  public boolean closeCurrent(Player player) {
    Preconditions.checkNotNull(player, "player");
    if (!available()) return false;
    Plugin trMenu = owner.getServer().getPluginManager().getPlugin("TrMenu");
    if (trMenu == null) return false;
    try {
      Object activeSession = session(trMenu.getClass().getClassLoader(), player);
      return activeSession == null || closeSession(activeSession);
    } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException
        | InvocationTargetException | IllegalAccessException | RuntimeException | LinkageError error) {
      logger.log(Level.SEVERE, "无法通过 TrMenu 关闭当前菜单", error);
      return false;
    }
  }

  /** Closes all RaidCore-owned menus for a player during disconnect/teardown. */
  public void close(Player player, String... menus) {
    Preconditions.checkNotNull(menus, "menus");
    for (String menu : menus) close(player, menu);
  }

  private static boolean closeSession(Object activeSession)
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    Method getMenu = activeSession.getClass().getMethod("getMenu");
    if (getMenu.invoke(activeSession) == null) return true;
    // Menu.removeViewer only changes TrMenu's viewer set. The session close
    // lifecycle also closes the packet window, fires its event and cancels
    // menu tasks.
    activeSession.getClass().getMethod("close", boolean.class, boolean.class)
        .invoke(activeSession, true, true);
    if (getMenu.invoke(activeSession) != null) {
      activeSession.getClass().getMethod("shut").invoke(activeSession);
    }
    return getMenu.invoke(activeSession) == null;
  }

  private static void setArgumentsIfViewing(ClassLoader loader, Player player,
      Object menuObject, String[] arguments)
      throws ClassNotFoundException, NoSuchFieldException, NoSuchMethodException,
      InvocationTargetException, IllegalAccessException {
    Object session = session(loader, player);
    if (session == null) return;
    Object currentMenu = session.getClass().getMethod("getMenu").invoke(session);
    if (currentMenu == menuObject) {
      session.getClass().getMethod("setArguments", String[].class)
          .invoke(session, (Object) arguments.clone());
    }
  }

  private static Object session(ClassLoader loader, Player player)
      throws ClassNotFoundException, NoSuchFieldException, NoSuchMethodException,
      InvocationTargetException, IllegalAccessException {
    Class<?> sessionType = loadFirst(loader, MENU_SESSION_TYPES);
    Field companionField = sessionType.getField("Companion");
    Object companion = companionField.get(null);
    return companion.getClass().getMethod("getSession", Player.class).invoke(companion, player);
  }

  /** Copies a default menu without overwriting administrator edits. */
  public boolean installDefault(String resourcePath, String fileName) {
    Preconditions.checkNotNull(resourcePath, "resourcePath");
    Preconditions.checkArgument(fileName.endsWith(".yml") && validToken(fileName.substring(0,
        fileName.length() - 4)), "invalid TrMenu file name");
    if (!available()) return false;
    Plugin trMenu = owner.getServer().getPluginManager().getPlugin("TrMenu");
    if (trMenu == null) return false;
    Path targetDirectory = trMenu.getDataFolder().toPath().resolve("menus").normalize();
    Path target = targetDirectory.resolve(fileName).normalize();
    if (!target.startsWith(targetDirectory)) return false;
    if (Files.exists(target)) return false;
    try (InputStream input = owner.getResource(resourcePath)) {
      if (input == null) {
        logger.warning("RaidCore 找不到内置 TrMenu 菜单资源: " + resourcePath);
        return false;
      }
      Files.createDirectories(targetDirectory);
      Files.copy(input, target);
      logger.info("已安装 TrMenu 菜单模板: " + fileName);
      return true;
    } catch (IOException error) {
      throw new IllegalStateException("无法安装 TrMenu 菜单模板: " + fileName, error);
    }
  }

  /** Reloads TrMenu after RaidCore installs one or more first-run templates. */
  public void reloadMenus() {
    requireAvailable();
    if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "trmenu reload")) {
      throw new IllegalStateException("TrMenu rejected its reload command");
    }
  }

  /** Disables the owner if TrMenu does not finish loading every required menu. */
  public void requireMenusLoaded(String... menuIds) {
    Preconditions.checkNotNull(menuIds, "menuIds");
    for (String menuId : menuIds) {
      Preconditions.checkArgument(validToken(menuId), "invalid TrMenu menu name");
    }
    new BukkitRunnable() {
      private int attempts;

      @Override
      public void run() {
        if (!owner.isEnabled()) {
          cancel();
          return;
        }
        boolean loaded = java.util.Arrays.stream(menuIds).allMatch(TrMenuBridge.this::menuLoaded);
        if (loaded) {
          logger.info("TrMenu required menus loaded: " + String.join(", ", menuIds));
          cancel();
          return;
        }
        if (++attempts < 20) return;
        logger.severe("TrMenu did not load every required RaidCore menu: "
            + String.join(", ", menuIds));
        cancel();
        owner.getServer().getPluginManager().disablePlugin(owner);
      }
    }.runTaskTimer(owner, 1L, 10L);
  }

  /**
   * Registers a dynamic ItemStack source used as {@code source:NAME:id} in a
   * TrMenu file. The reflective boundary keeps TrMenu out of RaidCore's Jar.
   */
  public AutoCloseable registerItemSource(String sourceName,
      BiFunction<Player, String, @Nullable ItemStack> resolver) {
    requireAvailable();
    Preconditions.checkArgument(validToken(sourceName), "invalid TrMenu source name");
    Preconditions.checkNotNull(resolver, "resolver");
    Plugin trMenu = Objects.requireNonNull(
        owner.getServer().getPluginManager().getPlugin("TrMenu"));
    try {
      ClassLoader loader = trMenu.getClass().getClassLoader();
      Class<?> rawEvent = loadFirst(loader, CUSTOM_ITEM_SOURCE_EVENT_TYPES);
      if (!Event.class.isAssignableFrom(rawEvent)) {
        throw new IllegalStateException("TrMenu custom item source is not a Bukkit event");
      }
      Method getName = rawEvent.getMethod("getName");
      Method getId = rawEvent.getMethod("getId");
      Method getSession = rawEvent.getMethod("getSession");
      Method setSource = rawEvent.getMethod("setSource", ItemStack.class);
      Method getViewer = getSession.getReturnType().getMethod("getViewer");
      String expected = sourceName.replace("-", "").toUpperCase(Locale.ROOT);
      Listener listener = new Listener() {};
      @SuppressWarnings("unchecked")
      Class<? extends Event> eventType = (Class<? extends Event>) rawEvent;
      owner.getServer().getPluginManager().registerEvent(eventType, listener,
          EventPriority.NORMAL, (_, event) -> {
            try {
              if (!expected.equals(String.valueOf(getName.invoke(event)))) return;
              Object session = getSession.invoke(event);
              Player viewer = (Player) getViewer.invoke(session);
              String id = String.valueOf(getId.invoke(event));
              ItemStack item = resolver.apply(viewer, id);
              setSource.invoke(event, item == null ? null : item.clone());
            } catch (InvocationTargetException | IllegalAccessException | RuntimeException error) {
              logger.log(Level.SEVERE,
                  "TrMenu dynamic item source failed: " + sourceName, error);
            }
          }, owner, true);
      return () -> HandlerList.unregisterAll(listener);
    } catch (ClassNotFoundException | NoSuchMethodException | LinkageError error) {
      throw new IllegalStateException(
          "TrMenu " + SUPPORTED_VERSION + " custom item source API is unavailable", error);
    }
  }

  public String status() {
    if (!available()) return "NOT_INSTALLED";
    @Nullable Plugin trMenu = owner.getServer().getPluginManager().getPlugin("TrMenu");
    return trMenu == null ? "NOT_INSTALLED" : "FOUND " + trMenu.getPluginMeta().getVersion();
  }

  private boolean menuLoaded(String menuId) {
    Plugin trMenu = owner.getServer().getPluginManager().getPlugin("TrMenu");
    if (trMenu == null) return false;
    try {
      Class<?> apiType = loadFirst(trMenu.getClass().getClassLoader(), API_TYPES);
      Object api = apiType.getField("INSTANCE").get(null);
      return apiType.getMethod("getMenuById", String.class).invoke(api, menuId) != null;
    } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException
        | InvocationTargetException | IllegalAccessException | LinkageError error) {
      logger.log(Level.SEVERE, "Unable to inspect TrMenu menu registry", error);
      return false;
    }
  }

  private static boolean validToken(String value) {
    return value != null && SAFE_TOKEN.matcher(value).matches();
  }

  private static Class<?> loadFirst(ClassLoader loader, String... classNames)
      throws ClassNotFoundException {
    ClassNotFoundException failure = null;
    for (String className : classNames) {
      try {
        return Class.forName(className, true, loader);
      } catch (ClassNotFoundException error) {
        failure = error;
      }
    }
    throw Objects.requireNonNull(failure, "classNames must not be empty");
  }
}
