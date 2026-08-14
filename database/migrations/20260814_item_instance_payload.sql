-- EscapeFromCraftov durable physical-item identity and Paper item payload.
-- Run with psql autocommit (do not wrap CREATE INDEX CONCURRENTLY in a transaction).
-- If database.table-prefix is configured, apply the same prefix to the table/index below.

ALTER TABLE craftov_player_items
  ADD COLUMN IF NOT EXISTS instance_id uuid;

ALTER TABLE craftov_player_items
  ADD COLUMN IF NOT EXISTS item_data text;

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_craftov_items_instance
  ON craftov_player_items (instance_id);
