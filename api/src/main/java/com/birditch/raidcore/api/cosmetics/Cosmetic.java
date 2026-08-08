package com.birditch.raidcore.api.cosmetics;

import com.birditch.raidcore.common.check.Preconditions;

/// A cosmetic item in the catalog; `materialKey` is a namespaced material name
/// such as `"minecraft:golden_helmet"`.
public record Cosmetic(CosmeticId id, String displayName, String materialKey) {

    public Cosmetic {
        Preconditions.checkNotNull(id, "id");
        Preconditions.checkNotNull(displayName, "displayName");
        Preconditions.checkNotNull(materialKey, "materialKey");
        Preconditions.checkArgument(!displayName.isBlank(), "displayName must not be blank");
        Preconditions.checkArgument(!materialKey.isBlank(), "materialKey must not be blank");
    }
}
