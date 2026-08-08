/// Raid-instance lifecycle glue for the game server: the game↔world index
/// (including the async-readable isolation-group map), the domain-event bridge
/// that creates/destroys worlds and moves players, and the self-provisioner
/// that keeps ready instances available.
@NullMarked
package com.birditch.raidcore.game.plugin.instance;

import org.jspecify.annotations.NullMarked;
