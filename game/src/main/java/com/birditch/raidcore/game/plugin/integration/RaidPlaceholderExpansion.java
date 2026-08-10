package com.birditch.raidcore.game.plugin.integration;

import com.birditch.raidcore.api.economy.EconomyService;
import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.profile.PlayerProfile;
import com.birditch.raidcore.api.profile.ProfileService;
import com.birditch.raidcore.api.profile.ProgressionCurve;
import com.birditch.raidcore.api.session.SessionService;
import com.birditch.raidcore.bukkit.RaidItemStacks;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.service.ServiceRegistry;
import java.time.Duration;
import java.time.Instant;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// Exposes only values backed by current runtime or PostgreSQL-cached profile data.
public final class RaidPlaceholderExpansion extends PlaceholderExpansion {

  private final Plugin plugin;
  private final ServiceRegistry services;
  private final RaidItemStacks itemStacks;
  private final int raidDurationSeconds;

  public RaidPlaceholderExpansion(Plugin plugin, ServiceRegistry services) {
    this(plugin, services, new RaidItemStacks(services.require(
        com.birditch.raidcore.api.item.ItemRegistry.class)), 1800);
  }

  public RaidPlaceholderExpansion(Plugin plugin, ServiceRegistry services,
      RaidItemStacks itemStacks, int raidDurationSeconds) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.services = Preconditions.checkNotNull(services, "services");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    Preconditions.checkArgument(raidDurationSeconds > 0, "raidDurationSeconds must be positive");
    this.raidDurationSeconds = raidDurationSeconds;
  }

  @Override public @NotNull String getIdentifier() { return "raidcore"; }
  @Override public @NotNull String getAuthor() { return "birditch"; }
  @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
  @Override public boolean persist() { return true; }

  @Override
  public @Nullable String onRequest(OfflinePlayer player, @NotNull String parameters) {
    if (player.getUniqueId() == null) return null;
    RaidGame game = services.require(SessionService.class).find(player.getUniqueId())
        .flatMap(session -> session.currentGame())
        .flatMap(id -> services.require(RaidGameService.class).find(id)).orElse(null);
    return switch (parameters.toLowerCase(java.util.Locale.ROOT)) {
      case "map" -> game == null ? "" : game.mapId().value();
      case "game_id" -> game == null ? "" : game.id().value();
      case "game_state" -> game == null ? "LOBBY" : game.state().name();
      case "player_state" -> game == null ? "LOBBY" : "RAID";
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

  private @Nullable String remainingTime(RaidGame game) {
    if (game == null || game.startedAt().isEmpty()) return null;
    long elapsed = Duration.between(game.startedAt().orElseThrow(), Instant.now()).toSeconds();
    return Long.toString(Math.max(0L, raidDurationSeconds - elapsed));
  }

  private @Nullable String extracted(RaidGame game, OfflinePlayer player) {
    if (game == null) return null;
    return game.players().stream()
        .filter(value -> value.playerId().equals(player.getUniqueId()))
        .findFirst()
        .map(value -> Boolean.toString(value.extracted()))
        .orElse(null);
  }
}
