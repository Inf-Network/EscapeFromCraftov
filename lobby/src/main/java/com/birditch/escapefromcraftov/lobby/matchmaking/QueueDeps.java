package com.birditch.escapefromcraftov.lobby.matchmaking;

import com.birditch.escapefromcraftov.api.network.AssignmentStore;
import com.birditch.escapefromcraftov.api.network.InstanceDirectory;
import com.birditch.escapefromcraftov.api.network.MessageBroker;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.settings.PlayerSettingsService;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.time.TimeSource;
import com.birditch.escapefromcraftov.lobby.LobbyConfig;
import com.birditch.escapefromcraftov.lobby.LobbyDataGate;
import com.birditch.escapefromcraftov.redis.JsonCodec;
import java.util.logging.Logger;

/// Collaborators of [QueueService].
public record QueueDeps(
    InstanceDirectory directory,
    AssignmentStore assignments,
    MessageBroker broker,
    PlayerGateway players,
    CraftovScheduler scheduler,
    TimeSource timeSource,
    PlayerSettingsService settings,
    LobbyDataGate dataGate,
    LobbyConfig config,
    JsonCodec codec,
    Logger logger) {

  public QueueDeps {
    Preconditions.checkNotNull(directory, "directory");
    Preconditions.checkNotNull(assignments, "assignments");
    Preconditions.checkNotNull(broker, "broker");
    Preconditions.checkNotNull(players, "players");
    Preconditions.checkNotNull(scheduler, "scheduler");
    Preconditions.checkNotNull(timeSource, "timeSource");
    Preconditions.checkNotNull(settings, "settings");
    Preconditions.checkNotNull(dataGate, "dataGate");
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(codec, "codec");
    Preconditions.checkNotNull(logger, "logger");
  }
}
