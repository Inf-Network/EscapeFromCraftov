package com.birditch.escapefromcraftov.core.module;

import com.birditch.escapefromcraftov.core.CraftovContext;

/// A self-contained runtime unit wired by [ModuleManager].
///
/// Lifecycle: `initialize` (resolve dependencies, register services), `start`
/// (activate behavior), `stop` (release resources). All lifecycle calls happen on the
/// main thread in the order managed by [ModuleManager].
public interface CraftovModule {

  /// Stable unique identifier used for registration and diagnostics.
  String id();

  void initialize(CraftovContext context);

  void start();

  void stop();
}
