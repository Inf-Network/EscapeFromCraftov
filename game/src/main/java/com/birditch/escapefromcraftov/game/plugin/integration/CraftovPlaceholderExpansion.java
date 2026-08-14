package com.birditch.escapefromcraftov.game.plugin.integration;

import com.birditch.escapefromcraftov.api.economy.EconomyService;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.profile.PlayerProfile;
import com.birditch.escapefromcraftov.api.profile.ProfileService;
import com.birditch.escapefromcraftov.api.profile.ProgressionCurve;
import com.birditch.escapefromcraftov.api.session.SessionService;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.core.service.ServiceRegistry;
import java.time.Duration;
import java.time.Instant;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// Exposes only values backed by current runtime or PostgreSQL-cached profile data.
public final class CraftovPlaceholderExpansion extends PlaceholderExpansion {

  private final Plugin plugin;
  private final ServiceRegistry services;
  private final CraftovItemStacks itemStacks;
  private final int craftovDurationSeconds;

  public CraftovPlaceholderExpansion(Plugin plugin, ServiceRegistry services) {
    this(plugin, services, new CraftovItemStacks(services.require(
        com.birditch.escapefromcraftov.api.item.ItemRegistry.class)), 1800);
  }

  public CraftovPlaceholderExpansion(Plugin plugin, ServiceRegistry services,
      CraftovItemStacks itemStacks, int craftovDurationSeconds) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.services = Preconditions.checkNotNull(services, "services");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    Preconditions.checkArgument(craftovDurationSeconds > 0, "craftovDurationSeconds must be positive");
    this.craftovDurationSeconds = craftovDurationSeconds;
  }

  @Override public @NotNull String getIdentifier() { return "escapefromcraftov"; }
  @Override public @NotNull String getAuthor() { return "birditch"; }
  @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
  @Override public boolean persist() { return true; }

  @Override
  public @Nullable String onRequest(OfflinePlayer player, @NotNull String parameters) {
    if (player.getUniqueId() == null) return null;
    CraftovGame game = services.require(SessionService.class).find(player.getUniqueId())
        .flatMap(session -> session.currentGame())
        .flatMap(id -> services.require(CraftovGameService.class).find(id)).orElse(null);
    return switch (parameters.toLowerCase(java.util.Locale.ROOT)) {
      case "map" -> game == null ? "" : game.mapId().value();
      case "game_id" -> game == null ? "" : game.id().value();
      case "game_state" -> game == null ? "LOBBY" : game.state().name();
      case "player_state" -> game == null ? "LOBBY" : "CRAFTOV";
      case "team" -> game == null ? "" : game.players().stream()
          .filter(value -> value.playerId().equals(player.getUniqueId())).findFirst()
          .flatMap(value -> value.teamId().stream().boxed().findFirst())
          .map(String::valueOf).orElse("");
      case "money" -> services.require(EconomyService.class).balance(player.getUniqueId())
          .map(value -> Long.toString(value.units())).orElse("");
      case "level" -> profile(player).map(value -> Integer.toString(value.level())).orElse("");
      case "experience" -> profile(player)
          .map(value -> Long.toString(value.experience())).orElse("");
      case "experience_to_next_level" -> profile(player)
          .map(value -> Long.toString(
              ProgressionCurve.experienceToNextLevel(value.experience()))).orElse("");
      case "alive_players" -> game == null ? "0" : Integer.toString(game.playerCount());
      case "loot_value" -> lootValue(player);
      case "remaining_time" -> remainingTime(game);
      case "extracted" -> extracted(game, player);
      default -> null;
    };
  }

  private java.util.Optional<PlayerProfile> profile(OfflinePlayer player) {
    return services.require(ProfileService.class).find(player.getUniqueId());
  }

  private @Nullable String lootValue(OfflinePlayer player) {
    if (!player.isOnline()) return null;
    org.bukkit.entity.Player online = Bukkit.getPlayer(player.getUniqueId());
    if (online == null) return null;
    return Long.toString(itemStacks.totalValue(online.getInventory().getContents()));
  }

  private @Nullable String remainingTime(CraftovGame game) {
    if (game == null || game.startedAt().isEmpty()) return null;
    long elapsed = Duration.between(game.startedAt().orElseThrow(), Instant.now()).toSeconds();
    return Long.toString(Math.max(0L, craftovDurationSeconds - elapsed));
  }

  private @Nullable String extracted(CraftovGame game, OfflinePlayer player) {
    if (game == null) return null;
    return game.players().stream()
        .filter(value -> value.playerId().equals(player.getUniqueId()))
        .findFirst()
        .map(value -> Boolean.toString(value.extracted()))
        .orElse(null);
  }
}
