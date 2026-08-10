package com.birditch.raidcore.game.plugin.start;

import com.birditch.raidcore.api.game.RaidGame;

/// Player-facing presentation boundary for the automatic room countdown.
public interface RaidStartNotifier {

  void countdown(RaidGame game, int remainingSeconds, int minimumPlayers);

  void cancelled(RaidGame game, int minimumPlayers);

  void started(RaidGame game);
}
