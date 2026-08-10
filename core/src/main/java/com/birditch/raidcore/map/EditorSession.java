package com.birditch.raidcore.map;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/// One administrator's isolated in-memory draft and editing history.
public final class EditorSession {

  private static final int MAX_HISTORY = 100;

  private final UUID playerId;
  private final Deque<ManualRaidMap> undo = new ArrayDeque<>();
  private final Deque<ManualRaidMap> redo = new ArrayDeque<>();
  private final EnumSet<MapObjectType> visible = EnumSet.allOf(MapObjectType.class);
  private ManualRaidMap saved;
  private ManualRaidMap draft;
  private EditorTool tool = EditorTool.MAP;
  private @Nullable String selectedObjectId;
  private @Nullable MapObject clipboard;
  private @Nullable ManualMapValidationResult validationResult;
  private int visibilityRadius = 96;
  private boolean boundsVisible;

  public EditorSession(UUID playerId, ManualRaidMap map) {
    this.playerId = Preconditions.checkNotNull(playerId, "playerId");
    this.saved = Preconditions.checkNotNull(map, "map");
    this.draft = map;
    this.boundsVisible = map.bounds() != null;
  }

  public UUID playerId() { return playerId; }
  public String mapId() { return draft.mapId(); }
  public ManualRaidMap draft() { return draft; }
  public EditorTool tool() { return tool; }
  public Optional<String> selectedObjectId() { return Optional.ofNullable(selectedObjectId); }
  public Optional<MapObject> clipboard() { return Optional.ofNullable(clipboard); }
  public Optional<ManualMapValidationResult> validationResult() {
    return Optional.ofNullable(validationResult);
  }
  public int visibilityRadius() { return visibilityRadius; }
  public Set<MapObjectType> visibleTypes() { return Set.copyOf(visible); }
  public boolean boundsVisible() { return boundsVisible; }
  public boolean dirty() { return !draft.equals(saved); }
  public boolean canUndo() { return !undo.isEmpty(); }
  public boolean canRedo() { return !redo.isEmpty(); }

  public void setTool(EditorTool newTool) {
    tool = Preconditions.checkNotNull(newTool, "newTool");
  }

  public void select(@Nullable String objectId) {
    if (objectId != null) {
      Preconditions.checkArgument(draft.findObject(objectId).isPresent(),
          "Unknown map object id: " + objectId);
    }
    selectedObjectId = objectId;
  }

  public void copySelected() {
    clipboard = selectedObjectId == null ? null : draft.findObject(selectedObjectId).orElse(null);
  }

  public void setValidationResult(ManualMapValidationResult result) {
    validationResult = Preconditions.checkNotNull(result, "result");
  }

  public void setVisible(MapObjectType type, boolean show) {
    Preconditions.checkNotNull(type, "type");
    if (show) visible.add(type); else visible.remove(type);
  }

  public void showAll() {
    visible.addAll(EnumSet.allOf(MapObjectType.class));
    boundsVisible = true;
  }
  public void hideAll() {
    visible.clear();
    boundsVisible = false;
  }

  public void setBoundsVisible(boolean show) { boundsVisible = show; }

  public void setVisibilityRadius(int blocks) {
    Preconditions.checkArgument(blocks >= 16 && blocks <= 512,
        "visibility radius must be 16..512");
    visibilityRadius = blocks;
  }

  public boolean apply(UnaryOperator<ManualRaidMap> operation) {
    Preconditions.checkNotNull(operation, "operation");
    ManualRaidMap updated = Preconditions.checkNotNull(operation.apply(draft), "updated map");
    Preconditions.checkArgument(updated.mapId().equals(draft.mapId()),
        "operation cannot change map id");
    if (updated.equals(draft)) return false;
    push(undo, draft);
    draft = updated;
    redo.clear();
    validationResult = null;
    if (selectedObjectId != null && draft.findObject(selectedObjectId).isEmpty()) {
      selectedObjectId = null;
    }
    return true;
  }

  public boolean undo() {
    if (undo.isEmpty()) return false;
    push(redo, draft);
    draft = undo.removeLast();
    validationResult = null;
    clearMissingSelection();
    return true;
  }

  public boolean redo() {
    if (redo.isEmpty()) return false;
    push(undo, draft);
    draft = redo.removeLast();
    validationResult = null;
    clearMissingSelection();
    return true;
  }

  public void markSaved(ManualRaidMap persisted) {
    Preconditions.checkArgument(persisted.mapId().equals(mapId()), "saved map id mismatch");
    saved = persisted;
    draft = persisted;
    undo.clear();
    redo.clear();
    validationResult = null;
    clearMissingSelection();
  }

  private void clearMissingSelection() {
    if (selectedObjectId != null && draft.findObject(selectedObjectId).isEmpty()) {
      selectedObjectId = null;
    }
  }

  private static void push(Deque<ManualRaidMap> stack, ManualRaidMap map) {
    if (stack.size() >= MAX_HISTORY) stack.removeFirst();
    stack.addLast(map);
  }
}
