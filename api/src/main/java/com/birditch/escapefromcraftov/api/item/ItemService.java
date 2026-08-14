package com.birditch.escapefromcraftov.api.item;

/// Creates item instances from registered definitions.
public interface ItemService {

    /// @throws com.birditch.escapefromcraftov.api.CraftovException if the item is unknown or
    ///         `quantity` is outside `1..stackSize`
    ItemInstance createInstance(ItemId id, int quantity);

    ItemRegistry registry();
}
