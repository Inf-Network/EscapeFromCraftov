package com.birditch.raidcore.lobby.matchmaking;

import com.birditch.raidcore.api.network.AssignmentStore;
import com.birditch.raidcore.api.network.InstanceDirectory;
import com.birditch.raidcore.api.network.MessageBroker;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.api.settings.PlayerSettingsService;
import com.birditch.raidcore.bukkit.BungeeConnector;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.time.TimeSource;
import com.birditch.raidcore.lobby.LobbyConfig;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/// Collaborators of [QueueService].
public record QueueDeps(
    InstanceDirectory directory,
    AssignmentStore assignments,
    MessageBroker broker,
    BungeeConnector bungee,
    RaidScheduler scheduler,
    TimeSource timeSource,
    PlayerSettingsService settings,
    LobbyConfig config,
    Messages messages,
    Plugin plugin,
    Logger logger) {

  public QueueDeps {
    Preconditions.checkNotNull(directory, "directory");
    Preconditions.checkNotNull(assignments, "assignments");
    Preconditions.checkNotNull(broker, "broker");
    Preconditions.checkNotNull(bungee, "bungee");
    Preconditions.checkNotNull(scheduler, "scheduler");
    Preconditions.checkNotNull(timeSource, "timeSource");
    Preconditions.checkNotNull(settings, "settings");
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(plugin, "plugin");
    Preconditions.checkNotNull(logger, "logger");
  }
}
