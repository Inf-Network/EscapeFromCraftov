package com.birditch.escapefromcraftov.game.plugin.start;

import com.birditch.escapefromcraftov.api.game.CraftovGame;

/// Player-facing presentation boundary for the automatic room countdown.
public interface CraftovStartNotifier {

  void countdown(CraftovGame game, int remainingSeconds, int minimumPlayers);

  void cancelled(CraftovGame game, int minimumPlayers);

  void started(CraftovGame game);
}
