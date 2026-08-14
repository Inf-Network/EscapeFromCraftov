-- EscapeFromCraftov named loadout positions (phase 1).
-- Run with psql autocommit (do not wrap CREATE INDEX CONCURRENTLY in a transaction).
-- If database.table-prefix is configured, apply the same prefix to the table/index below.

ALTER TABLE craftov_player_items
  ADD COLUMN IF NOT EXISTS loadout_slot varchar(32);

ALTER TABLE craftov_player_items
  DROP CONSTRAINT IF EXISTS ck_craftov_items_loadout_slot;

ALTER TABLE craftov_player_items
  ADD CONSTRAINT ck_craftov_items_loadout_slot CHECK (
    loadout_slot IS NULL OR (
      location IN ('LOADOUT', 'CRAFTOV') AND
      loadout_slot IN (
        'MAIN_HAND', 'OFF_HAND', 'HEAD', 'BODY_ARMOR', 'CHEST_RIG',
        'BACKPACK', 'QUICK_USE_1', 'QUICK_USE_2', 'SECURE_CONTAINER'
      )
    )
  ) NOT VALID;

ALTER TABLE craftov_player_items
  VALIDATE CONSTRAINT ck_craftov_items_loadout_slot;

-- NULL legacy LOADOUT rows deliberately do not participate. Only one named
-- item may occupy a position while the player is preparing in the Lobby.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_craftov_items_named_loadout
  ON craftov_player_items (player_id, loadout_slot)
  WHERE location = 'LOADOUT' AND loadout_slot IS NOT NULL;
