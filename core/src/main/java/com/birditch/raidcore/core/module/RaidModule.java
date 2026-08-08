package com.birditch.raidcore.core.module;

import com.birditch.raidcore.core.RaidContext;

/// A self-contained runtime unit wired by [ModuleManager].
///
/// Lifecycle: `initialize` (resolve dependencies, register services), `start`
/// (activate behavior), `stop` (release resources). All lifecycle calls happen on the
/// main thread in the order managed by [ModuleManager].
public interface RaidModule {

  /// Stable unique identifier used for registration and diagnostics.
  String id();

  void initialize(RaidContext context);

  void start();

  void stop();
}
