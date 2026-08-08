/// Redis-backed network fabric shared by the game and lobby plugins: instance
/// directory snapshots, pending match assignments, pub/sub messaging, and player
/// settings/cosmetics persistence.
///
/// Jedis types never escape this package ([RedisConnector#resource] is
/// package-private); every Redis call blocks and must run on the io executor or a
/// virtual thread, never on the server main thread.
@NullMarked
package com.birditch.raidcore.redis;

import org.jspecify.annotations.NullMarked;
