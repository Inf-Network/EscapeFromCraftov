package com.birditch.escapefromcraftov.item;

import com.birditch.escapefromcraftov.api.CraftovException;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/// In-memory [ItemRegistry] preserving registration order.
///
/// Not thread-safe: registration happens at bootstrap and all access is
/// main-thread confined.
public final class SimpleItemRegistry implements ItemRegistry {

  private final Map<ItemId, ItemDefinition> definitions = new LinkedHashMap<>();

  @Override
  public void register(ItemDefinition definition) {
    Preconditions.checkNotNull(definition, "definition");
    if (definitions.containsKey(definition.id())) {
      throw new CraftovException("Item already registered: " + definition.id().value());
    }
    definitions.put(definition.id(), definition);
  }

  @Override
  public Optional<ItemDefinition> find(ItemId id) {
    Preconditions.checkNotNull(id, "id");
    return Optional.ofNullable(definitions.get(id));
  }

  @Override
  public ItemDefinition require(ItemId id) {
    return find(id).orElseThrow(() -> new CraftovException("Item not found: " + id.value()));
  }

  @Override
  public Collection<ItemDefinition> all() {
    return Collections.unmodifiableCollection(definitions.values());
  }

  @Override
  public int size() {
    return definitions.size();
  }
}
