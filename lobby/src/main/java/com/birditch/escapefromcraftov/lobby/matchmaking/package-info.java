/// Lobby matchmaking: a FIFO queue ticking against the shared instance
/// directory, short-lived slot reservations and the pure instance-selection
/// logic. All queue state is main-thread confined.
@NullMarked
package com.birditch.escapefromcraftov.lobby.matchmaking;

import org.jspecify.annotations.NullMarked;
