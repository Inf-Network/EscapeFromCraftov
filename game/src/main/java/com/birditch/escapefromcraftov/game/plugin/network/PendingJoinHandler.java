package com.birditch.escapefromcraftov.game.plugin.network;

import com.birditch.escapefromcraftov.api.CraftovPermissions;
import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.game.CraftovStartedEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.GameFailures;
import com.birditch.escapefromcraftov.api.inventory.CraftovInventoryClosedException;
import com.birditch.escapefromcraftov.api.inventory.CraftovInventoryOutcome;
import com.birditch.escapefromcraftov.api.inventory.LoadoutSlot;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.item.ItemCategory;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.network.MatchAssignment;
import com.birditch.escapefromcraftov.bukkit.MapNames;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import com.birditch.escapefromcraftov.game.plugin.GameServerConfig;
import com.birditch.escapefromcraftov.game.plugin.LobbyReturn;
import com.birditch.escapefromcraftov.map.EditorSession;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/// Consumes the lobby-issued pending match assignment when a player joins this
/// server and routes them into the assigned craftov instance.
///
/// The atomic consume (GETDEL) runs async on the store's IO executor; every
/// outcome hops back to the main thread. This is a dedicated game server, so a
/// player is returned to Lobby unless one valid, ready assignment is consumed.
public final class PendingJoinHandler {

  private final PendingDeps deps;
  private final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();
  private final Map<UUID, PendingEntry> retainedAssignments = new ConcurrentHashMap<>();

  public enum ClaimOutcome {
    CLAIMED,
    UNCLAIMED
  }

  public PendingJoinHandler(PendingDeps deps) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
  }

  /** Applies the fail-closed state before any profile or assignment IO starts. */
  public void prepare(Player player) {
    Preconditions.checkNotNull(player, "player");
    pendingPlayers.add(player.getUniqueId());
    if (!deps.menus().closeCurrent(player)) {
      deps.logger().warning("无法关闭 " + player.getName() + " 的 TrMenu 会话");
    }
    clearInventory(player);
    player.setInvulnerable(true);
  }

  public boolean isPending(UUID playerId) {
    return pendingPlayers.contains(Preconditions.checkNotNull(playerId, "playerId"));
  }

  /** Clears a connection that never entered a running craftov. */
  public void release(UUID playerId) {
    UUID checked = Preconditions.checkNotNull(playerId, "playerId");
    pendingPlayers.remove(checked);
    PendingEntry entry = retainedAssignments.get(checked);
    if (entry != null && entry.cancelBeforeClaim()) {
      retainedAssignments.remove(checked, entry);
    }
  }

  /**
   * Removes a WAITING membership without ever moving its LOADOUT rows. The game
   * listener calls this before leaving the room on quit and shutdown.
   */
  public void cancelBeforeStart(UUID playerId, CraftovGameId gameId) {
    Preconditions.checkNotNull(gameId, "gameId");
    UUID checked = Preconditions.checkNotNull(playerId, "playerId");
    pendingPlayers.remove(checked);
    PendingEntry entry = retainedAssignments.get(checked);
    if (entry != null && entry.assignment.gameId().equals(gameId)
        && entry.cancelBeforeClaim()) {
      retainedAssignments.remove(checked, entry);
    }
  }

  /**
   * Serializes a RUNNING quit behind the start-time loadout claim. Empty means
   * the player had already finished preparation and can use the normal loss
   * path immediately.
   */
  public Optional<CompletableFuture<ClaimOutcome>> deferStartedQuit(
      UUID playerId, CraftovGameId gameId) {
    Preconditions.checkNotNull(gameId, "gameId");
    UUID checked = Preconditions.checkNotNull(playerId, "playerId");
    pendingPlayers.remove(checked);
    PendingEntry entry = retainedAssignments.get(checked);
    if (entry == null || !entry.assignment.gameId().equals(gameId)) {
      return Optional.empty();
    }
    entry.requestQuit();
    beginClaim(entry);
    return Optional.of(entry.completion());
  }

  /** Starts every retained assignment for the room, exactly once. */
  @Subscribe
  public void onCraftovStarted(CraftovStartedEvent event) {
    for (PendingEntry entry : List.copyOf(retainedAssignments.values())) {
      if (!entry.assignment.gameId().equals(event.gameId())) continue;
      UUID playerId = entry.assignment.playerId();
      Player player = Bukkit.getPlayer(playerId);
      boolean stillMember = deps.games().find(event.gameId()).stream()
          .flatMap(game -> game.players().stream())
          .anyMatch(member -> member.playerId().equals(playerId));
      if (player == null || !player.isOnline() || !stillMember) {
        cancelBeforeStart(playerId, event.gameId());
        if (stillMember) deps.games().leave(playerId);
        continue;
      }
      beginClaim(entry);
    }
  }

  /** Releases retained references after the event subscription is removed. */
  public void close() {
    pendingPlayers.clear();
    for (PendingEntry entry : retainedAssignments.values()) {
      entry.complete(ClaimOutcome.UNCLAIMED);
    }
    retainedAssignments.clear();
  }

  /// Called only after the player's PostgreSQL profile is ready.
  public void handle(Player player) {
    Preconditions.checkNotNull(player, "player");
    // Always consume a real lobby assignment first. Administrators also play
    // test matches, so bypassing Redis solely because of their permissions
    // would strand them in the editor source world while ordinary players join
    // the copied instance. Editor access is only the no-assignment fallback.
    deps.store().consumePending(player.getUniqueId()).whenComplete((assignment, error) -> {
      MatchAssignment match = assignment == null ? null : assignment.orElse(null);
      deps.scheduler().runSync(() -> completeConsume(player, match, error));
    });
  }

  public void profileFailed(Player player) {
    Preconditions.checkNotNull(player, "player");
    if (player.isOnline()) {
      player.sendMessage(deps.messages().raw("join.profile-failed"));
      LobbyReturn.send(deps.bungee(), deps.config(), deps.messages(), player);
    }
  }

  private void completeConsume(Player player, MatchAssignment assignment, Throwable error) {
    if (!player.isOnline()) {
      return;
    }
    if (error != null) {
      logConsumeFailure(player, error);
      if (canEdit(player)) {
        authorizeEditor(player);
        return;
      }
      rejectAssignment(player, "game.assignment-unavailable", "Redis unavailable");
      return;
    }
    if (assignment == null) {
      if (canEdit(player)) {
        authorizeEditor(player);
        return;
      }
      rejectAssignment(player, "game.assignment-missing", "missing assignment");
      return;
    }
    apply(player, assignment);
  }

  private void apply(Player player, MatchAssignment assignment) {
    if (!player.isOnline()) {
      return;
    }
    TargetValidation target = validateTarget(player.getUniqueId(), assignment, deps.config());
    if (target != TargetValidation.VALID) {
      deps.logger().warning("忽略发给 " + player.getName() + " 的匹配分配:" + switch (target) {
        case PLAYER_MISMATCH -> "分配中的玩家是 " + assignment.playerId();
        case SERVER_ID_MISMATCH -> "目标服务器 ID 是 " + assignment.serverId().value()
            + ",本服是 " + deps.config().serverId().value();
        case PROXY_NAME_MISMATCH -> "目标代理后端是 " + assignment.proxyName()
            + ",本服是 " + deps.config().proxyName();
        case VALID -> throw new IllegalStateException("VALID assignment reached rejection branch");
      });
      rejectAssignment(player, "game.assignment-invalid", target.playerMessage());
      return;
    }
    CraftovGame game = deps.games().find(assignment.gameId()).orElse(null);
    if (game == null) {
      rejectAssignment(player, "game.assignment-invalid", GameFailures.GAME_NOT_FOUND);
      return;
    }
    if (!game.mapId().equals(assignment.mapId())) {
      rejectAssignment(player, "game.assignment-invalid", "地图不匹配");
      return;
    }
    if (game.mode() != assignment.mode() || game.difficulty() != assignment.difficulty()) {
      rejectAssignment(player, "game.assignment-invalid", "模式或难度不匹配");
      return;
    }
    if (game.state() != CraftovGameState.WAITING || !deps.index().isReady(game.id())) {
      rejectAssignment(player, "game.assignment-invalid", "对局实例尚未就绪");
      return;
    }
    EditorSession editor = deps.editorSessions().find(player.getUniqueId()).orElse(null);
    if (editor != null && editor.dirty()) {
      rejectAssignment(player, "game.assignment-invalid",
          "地图编辑器存在未保存修改，请先保存或放弃修改后再匹配");
      return;
    }
    deps.editorSessions().exit(player.getUniqueId(), false);
    deps.editorSelections().clear(player.getUniqueId());
    Result<CraftovGame> joined = deps.games().join(
        assignment.gameId(), player.getUniqueId(), assignment.teamKey());
    if (joined instanceof Result.Failure<CraftovGame>(String message)) {
      rejectAssignment(player,
          GameFailures.GAME_FULL.equals(message) ? "game.instance-full" : "game.assignment-invalid",
          message);
      return;
    }
    if (!deps.menus().closeCurrent(player)) {
      player.sendMessage(deps.messages().raw("game.assignment-unavailable",
          Placeholder.unparsed("reason", "菜单会话无法安全关闭")));
      leaveAndReturn(player);
      return;
    }
    // This point is intentionally after a valid assignment and successful
    // domain join. Editor fallback never receives raid state normalization.
    RaidEntryState.reset(player);
    PendingEntry entry = new PendingEntry(assignment);
    PendingEntry previous = retainedAssignments.putIfAbsent(player.getUniqueId(), entry);
    if (previous != null) {
      deps.logger().severe("玩家存在重复的待领取匹配分配:" + player.getUniqueId());
      leaveAndReturn(player);
      return;
    }
    // start() publishes synchronously, but checking the durable domain view
    // also protects against a future subscriber/order change.
    if (game.startedAt().isPresent()) beginClaim(entry);
  }

  private void beginClaim(PendingEntry entry) {
    if (!entry.beginClaim()) return;
    Player player = Bukkit.getPlayer(entry.assignment.playerId());
    if (player != null && player.isOnline() && !entry.quitRequested()) {
      player.sendMessage(deps.messages().raw("inventory.loadout-loading"));
    }
    claimLoadout(entry, 0);
  }

  private void claimLoadout(PendingEntry entry, int attempt) {
    MatchAssignment assignment = entry.assignment;
    if (retainedAssignments.get(assignment.playerId()) != entry) return;
    deps.inventories().claimLoadout(assignment.playerId(), assignment.gameId())
        .whenComplete((items, error) -> deps.scheduler().runSync(() ->
            finishLoadout(entry, items, error, attempt)));
  }

  private void finishLoadout(PendingEntry entry, List<StoredItem> items,
      Throwable error, int attempt) {
    MatchAssignment assignment = entry.assignment;
    UUID playerId = assignment.playerId();
    Player player = Bukkit.getPlayer(playerId);
    if (retainedAssignments.get(playerId) != entry) return;
    if (error != null || items == null) {
      Throwable cause = unwrap(error);
      if (cause instanceof CraftovInventoryClosedException closed) {
        deps.logger().warning("出战装备领取已被既有结算关闭:" + playerId
            + "/" + closed.outcome());
        completeEntry(entry, ClaimOutcome.UNCLAIMED);
        if (player != null && player.isOnline() && !entry.quitRequested()) {
          player.sendMessage(deps.messages().raw("inventory.loadout-closed"));
          leaveAndReturn(player);
        }
        return;
      }
      deps.logger().log(Level.WARNING,
          "领取出战栏失败，将在 5 秒后自动重试 (第 " + (attempt + 1) + " 次):"
              + playerId, cause);
      if (player != null && player.isOnline() && !entry.quitRequested() && attempt == 0) {
        player.sendMessage(deps.messages().raw("inventory.loadout-retrying"));
      }
      deps.scheduler().runLater(Duration.ofSeconds(5),
          () -> claimLoadout(entry, attempt + 1));
      return;
    }
    if (entry.quitRequested() || player == null || !player.isOnline()) {
      // The quit listener is waiting on this exact future. Completing only
      // after claimLoadout commits prevents settleLoss from closing the raid
      // before LOADOUT has moved to CRAFTOV.
      completeEntry(entry, ClaimOutcome.CLAIMED);
      return;
    }
    try {
      List<StoredItem> ordered = items.stream()
          .sorted(java.util.Comparator.comparingInt(item -> item.loadoutSlot() == null ? 1 : 0))
          .toList();
      for (StoredItem item : ordered) {
        giveClaimedItem(player, item);
      }
    } catch (RuntimeException mappingFailure) {
      clearInventory(player);
      List<ItemInstance> restore = items.stream().map(item ->
          new ItemInstance(item.instanceId(), item.itemId(), item.quantity(),
              item.opaquePayload())).toList();
      entry.markRestoring();
      restoreClaimedLoadout(entry, restore, mappingFailure, 0);
      return;
    }
    completeEntry(entry, ClaimOutcome.CLAIMED);
    player.setInvulnerable(false);
    player.sendMessage(deps.messages().raw("game.sent-to-instance",
        Placeholder.unparsed("id", assignment.gameId().value()),
        Placeholder.component("map", MapNames.display(deps.messages(), assignment.mapId()))));
  }

  private void giveClaimedItem(Player player, StoredItem item) {
    ItemCategory category = deps.items().require(item.itemId()).category();
    if (item.loadoutSlot() != null) {
      giveNamedItem(player.getInventory(), item, category);
      return;
    }
    List<ItemStack> backpack = new ArrayList<>();
    for (ItemStack stack : deps.itemStacks().fromStored(item)) {
      if (!tryEquip(player.getInventory(), category, stack)) backpack.add(stack);
    }
    if (!backpack.isEmpty()
        && !player.getInventory().addItem(backpack.toArray(ItemStack[]::new)).isEmpty()) {
      throw new IllegalStateException("出战物品超过玩家背包容量");
    }
  }

  private void giveNamedItem(PlayerInventory inventory, StoredItem item, ItemCategory category) {
    LoadoutSlot slot = item.loadoutSlot();
    if (slot == null || !slot.accepts(category)) {
      throw new IllegalStateException("持久化配装位与物品类别不匹配: " + item.itemId());
    }
    NamedStackPlan<ItemStack> plan = planNamedStacks(slot, deps.itemStacks().fromStored(item));
    ItemStack stack = plan.equipped();
    switch (slot) {
      case MAIN_HAND -> putHotbar(inventory, 0, stack);
      case OFF_HAND -> {
        if (!empty(inventory.getItemInOffHand())) {
          throw new IllegalStateException("副手配装位发生冲突");
        }
        inventory.setItemInOffHand(stack);
      }
      case HEAD -> putArmor(inventory, category, stack, EquipmentSlot.HEAD);
      case BODY_ARMOR -> putArmor(inventory, category, stack, EquipmentSlot.CHEST);
      case QUICK_USE_1 -> putHotbar(inventory, 1, stack);
      case QUICK_USE_2 -> putHotbar(inventory, 2, stack);
      case CHEST_RIG, BACKPACK, SECURE_CONTAINER ->
          throw new IllegalStateException("保留配装位不能包含物品: " + slot);
    }
    for (ItemStack overflow : plan.backpack()) putBackpack(inventory, overflow);
  }

  /**
   * A quick-use durable stack may expand into several unstackable Bukkit items
   * (for example two healing potions). The first stays in the named hotbar cell;
   * every derived physical stack is carried in the backpack. Equipment positions
   * remain strictly one physical stack.
   */
  static <T> NamedStackPlan<T> planNamedStacks(LoadoutSlot slot, List<T> stacks) {
    Preconditions.checkNotNull(slot, "slot");
    Preconditions.checkNotNull(stacks, "stacks");
    if (stacks.isEmpty()) throw new IllegalStateException("具名配装位没有可发放的物品");
    boolean quickUse = slot == LoadoutSlot.QUICK_USE_1 || slot == LoadoutSlot.QUICK_USE_2;
    if (!quickUse && stacks.size() != 1) {
      throw new IllegalStateException("具名装备位包含多个物品堆栈: " + slot);
    }
    return new NamedStackPlan<>(stacks.getFirst(),
        quickUse ? List.copyOf(stacks.subList(1, stacks.size())) : List.of());
  }

  record NamedStackPlan<T>(T equipped, List<T> backpack) {
    NamedStackPlan {
      Preconditions.checkNotNull(equipped, "equipped");
      backpack = List.copyOf(Preconditions.checkNotNull(backpack, "backpack"));
    }
  }

  private static void putBackpack(PlayerInventory inventory, ItemStack stack) {
    // Vanilla main-inventory cells 9..35 are the carried backpack area. Do not
    // let a derived potion occupy another named hotbar position.
    for (int index = 9; index < 36; index++) {
      if (!empty(inventory.getItem(index))) continue;
      inventory.setItem(index, stack);
      return;
    }
    throw new IllegalStateException("出战物品超过玩家背包容量");
  }

  private static void putHotbar(PlayerInventory inventory, int index, ItemStack stack) {
    if (!empty(inventory.getItem(index))) {
      throw new IllegalStateException("快捷栏配装位发生冲突: " + index);
    }
    inventory.setItem(index, stack);
    if (index == 0) inventory.setHeldItemSlot(0);
  }

  private static void putArmor(PlayerInventory inventory, ItemCategory category,
      ItemStack stack, EquipmentSlot expected) {
    if (stack.getAmount() != 1 || armorSlot(category, stack.getType()).orElse(null) != expected) {
      throw new IllegalStateException("护甲配装位与实际装备类型不匹配: " + expected);
    }
    if (!empty(inventory.getItem(expected))) {
      throw new IllegalStateException("护甲配装位发生冲突: " + expected);
    }
    inventory.setItem(expected, stack);
  }

  static boolean tryEquip(PlayerInventory inventory, ItemCategory category, ItemStack stack) {
    if (stack.getAmount() != 1) return false;
    if (category == ItemCategory.SHIELD) {
      if (!empty(inventory.getItemInOffHand())) return false;
      inventory.setItemInOffHand(stack);
      return true;
    }
    if (category != ItemCategory.ARMOR && category != ItemCategory.HELMET) return false;
    EquipmentSlot slot = armorSlot(category, stack.getType()).orElse(null);
    if (slot == null) return false;
    if (!empty(inventory.getItem(slot))) return false;
    inventory.setItem(slot, stack);
    return true;
  }

  static Optional<EquipmentSlot> armorSlot(ItemCategory category, Material material) {
    if (category != ItemCategory.ARMOR && category != ItemCategory.HELMET) {
      return Optional.empty();
    }
    EquipmentSlot slot = material.getEquipmentSlot();
    if (category == ItemCategory.HELMET && slot != EquipmentSlot.HEAD) {
      return Optional.empty();
    }
    return switch (slot) {
      case HEAD, CHEST, LEGS, FEET -> Optional.of(slot);
      default -> Optional.empty();
    };
  }

  private static boolean empty(ItemStack stack) {
    return stack == null || stack.isEmpty();
  }

  private void restoreClaimedLoadout(PendingEntry entry,
      List<ItemInstance> restore, RuntimeException mappingFailure, int attempt) {
    MatchAssignment assignment = entry.assignment;
    UUID playerId = assignment.playerId();
    deps.inventories().settleExtraction(playerId, assignment.gameId(), restore)
        .whenComplete((result, error) -> deps.scheduler().runSync(() -> {
          if (retainedAssignments.get(playerId) != entry) return;
          Player player = Bukkit.getPlayer(playerId);
          if (error != null || result == null) {
            deps.logger().log(Level.WARNING,
                "恢复未发放的出战装备失败，将在 5 秒后自动重试 (第 " + (attempt + 1)
                    + " 次):" + playerId, error == null ? mappingFailure : error);
            if (player != null && player.isOnline() && !entry.quitRequested() && attempt == 0) {
              player.sendMessage(deps.messages().raw("inventory.loadout-restore-retrying"));
            }
            deps.scheduler().runLater(Duration.ofSeconds(5), () ->
                restoreClaimedLoadout(entry, restore, mappingFailure, attempt + 1));
            return;
          }
          deps.logger().log(Level.SEVERE, "无法把出战装备转换成 Minecraft 物品:"
              + playerId, mappingFailure);
          boolean quitRequested = entry.quitRequested();
          completeEntry(entry, ClaimOutcome.UNCLAIMED);
          if (!quitRequested && player != null && player.isOnline()) {
            player.sendMessage(deps.messages().raw(result.outcome()
                == CraftovInventoryOutcome.EXTRACTED
                ? "inventory.loadout-restored" : "inventory.loadout-closed"));
            leaveAndReturn(player);
          } else if (!quitRequested) {
            deps.games().leave(playerId);
          }
        }));
  }

  private void completeEntry(PendingEntry entry, ClaimOutcome outcome) {
    UUID playerId = entry.assignment.playerId();
    retainedAssignments.remove(playerId, entry);
    pendingPlayers.remove(playerId);
    entry.complete(outcome);
  }

  private void leaveAndReturn(Player player) {
    if (!deps.games().leave(player.getUniqueId()).isSuccess()) {
      LobbyReturn.send(deps.bungee(), deps.config(), deps.messages(), player);
    }
  }

  private void authorizeEditor(Player player) {
    UUID playerId = player.getUniqueId();
    deps.index().setGroup(playerId, "editor:" + playerId);
    release(playerId);
    player.setInvulnerable(false);
    deps.visibility().refresh(player);
    player.sendMessage(deps.messages().raw("join.editor-ready"));
  }

  private static boolean canEdit(Player player) {
    return player.hasPermission(CraftovPermissions.ADMIN)
        || player.hasPermission(CraftovPermissions.EDITOR);
  }

  private static void clearInventory(Player player) {
    player.getInventory().clear();
    player.getInventory().setArmorContents(null);
    player.getInventory().setItemInOffHand(null);
  }

  private void rejectAssignment(Player player, String key, String reason) {
    player.sendMessage(deps.messages().raw(key, Placeholder.unparsed("reason", reason)));
    LobbyReturn.send(deps.bungee(), deps.config(), deps.messages(), player);
  }

  /// May run on the store's IO thread; `java.util.logging` is thread-safe.
  private void logConsumeFailure(Player player, Throwable error) {
    Throwable cause = unwrap(error);
    deps.logger().warning("消费 " + player.getName() + " 的匹配分配失败:" + cause);
  }

  static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  static TargetValidation validateTarget(UUID playerId, MatchAssignment assignment,
      GameServerConfig config) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(assignment, "assignment");
    Preconditions.checkNotNull(config, "config");
    if (!assignment.playerId().equals(playerId)) return TargetValidation.PLAYER_MISMATCH;
    if (!assignment.serverId().equals(config.serverId())) {
      return TargetValidation.SERVER_ID_MISMATCH;
    }
    if (!assignment.proxyName().equals(config.proxyName())) {
      return TargetValidation.PROXY_NAME_MISMATCH;
    }
    return TargetValidation.VALID;
  }

  static final class PendingEntry {

    private final MatchAssignment assignment;
    private final CompletableFuture<ClaimOutcome> completion = new CompletableFuture<>();
    private ClaimPhase phase = ClaimPhase.WAITING;
    private boolean quitRequested;

    PendingEntry(MatchAssignment assignment) {
      this.assignment = Preconditions.checkNotNull(assignment, "assignment");
    }

    boolean beginClaim() {
      if (phase != ClaimPhase.WAITING) return false;
      phase = ClaimPhase.CLAIMING;
      return true;
    }

    boolean cancelBeforeClaim() {
      if (phase != ClaimPhase.WAITING) return false;
      complete(ClaimOutcome.UNCLAIMED);
      return true;
    }

    void markRestoring() {
      if (phase == ClaimPhase.CLAIMING) phase = ClaimPhase.RESTORING;
    }

    void requestQuit() {
      quitRequested = true;
    }

    boolean quitRequested() {
      return quitRequested;
    }

    CompletableFuture<ClaimOutcome> completion() {
      return completion;
    }

    void complete(ClaimOutcome outcome) {
      phase = ClaimPhase.COMPLETE;
      completion.complete(outcome);
    }
  }

  private enum ClaimPhase {
    WAITING,
    CLAIMING,
    RESTORING,
    COMPLETE
  }

  enum TargetValidation {
    VALID(""),
    PLAYER_MISMATCH("玩家身份不匹配"),
    SERVER_ID_MISMATCH("目标服务器不匹配"),
    PROXY_NAME_MISMATCH("代理后端不匹配");

    private final String playerMessage;

    TargetValidation(String playerMessage) {
      this.playerMessage = playerMessage;
    }

    String playerMessage() {
      return playerMessage;
    }
  }
}
