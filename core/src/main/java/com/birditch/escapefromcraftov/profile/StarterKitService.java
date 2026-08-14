package com.birditch.escapefromcraftov.profile;

import com.birditch.escapefromcraftov.api.profile.PlayerProfile;
import java.util.concurrent.CompletableFuture;

/// Atomically creates a missing profile with its one-time starter assets.
public interface StarterKitService {

  CompletableFuture<StarterKitResult> initialize(PlayerProfile profile);
}
