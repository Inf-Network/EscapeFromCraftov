/// Shared Paper glue used by both plugin jars: scheduler adapter, MiniMessage
/// catalog, YAML resource loading, config mappers, and proxy connector.
///
/// This is the ONLY core package allowed to import org.bukkit / io.papermc /
/// net.kyori; domain, redis, and runtime packages stay Bukkit-free.
@NullMarked
package com.birditch.raidcore.bukkit;

import org.jspecify.annotations.NullMarked;
