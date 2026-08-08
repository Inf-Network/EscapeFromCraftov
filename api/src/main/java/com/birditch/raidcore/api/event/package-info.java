/// In-process domain event bus. Publishing is main-thread only and dispatch is
/// exact-class based (no polymorphic dispatch to supertype listeners).
@NullMarked
package com.birditch.raidcore.api.event;

import org.jspecify.annotations.NullMarked;
