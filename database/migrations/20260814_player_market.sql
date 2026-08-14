-- EscapeFromCraftov durable player market / auction house.
-- Run once with psql before switching database.orm.schema-action to validate.
-- If database.table-prefix is configured, apply it to every table/index/constraint name.

BEGIN;

-- Hibernate generated this enum CHECK before MARKET existed. PostgreSQL keeps
-- the old allowed-value set until the constraint is explicitly replaced.
ALTER TABLE craftov_player_items
  DROP CONSTRAINT IF EXISTS craftov_player_items_location_check;
ALTER TABLE craftov_player_items
  ADD CONSTRAINT craftov_player_items_location_check
  CHECK (location IN ('WAREHOUSE', 'LOADOUT', 'CRAFTOV', 'MARKET'));

CREATE TABLE IF NOT EXISTS craftov_market_listings (
  listing_id uuid PRIMARY KEY,
  create_operation_id uuid NOT NULL,
  seller_id uuid NOT NULL,
  storage_id uuid NOT NULL,
  instance_id uuid NOT NULL,
  item_id varchar(255) NOT NULL,
  quantity integer NOT NULL CHECK (quantity > 0),
  item_data text,
  price bigint NOT NULL CHECK (price > 0 AND price <= 1000000000),
  status varchar(255) NOT NULL,
  buyer_id uuid,
  close_operation_id uuid,
  created_at timestamp(6) with time zone NOT NULL,
  expires_at timestamp(6) with time zone NOT NULL,
  closed_at timestamp(6) with time zone,
  version bigint
);

CREATE INDEX IF NOT EXISTS idx_craftov_market_active_expiry
  ON craftov_market_listings (status, expires_at);
CREATE INDEX IF NOT EXISTS idx_craftov_market_seller_status
  ON craftov_market_listings (seller_id, status, created_at);

CREATE TABLE IF NOT EXISTS craftov_market_operations (
  operation_id uuid PRIMARY KEY,
  operation_type varchar(255) NOT NULL,
  player_id uuid NOT NULL,
  listing_id uuid NOT NULL,
  created_at timestamp(6) with time zone NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_craftov_market_operation_listing
  ON craftov_market_operations (listing_id);

COMMIT;
