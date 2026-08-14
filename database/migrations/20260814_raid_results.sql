-- EscapeFromCraftov durable per-player raid reports.
-- Run once with psql before switching database.orm.schema-action to validate.
-- If database.table-prefix is configured, apply it to every table/index/constraint name.

BEGIN;

CREATE TABLE IF NOT EXISTS craftov_raid_results (
  result_id uuid PRIMARY KEY,
  game_id varchar(96) NOT NULL,
  player_id uuid NOT NULL,
  map_id varchar(96) NOT NULL,
  raid_mode varchar(24) NOT NULL,
  difficulty varchar(24) NOT NULL,
  outcome varchar(24) NOT NULL,
  started_at timestamp(6) with time zone NOT NULL,
  ended_at timestamp(6) with time zone NOT NULL,
  kills integer NOT NULL CHECK (kills >= 0),
  extracted_item_count integer NOT NULL CHECK (extracted_item_count >= 0),
  extracted_value bigint NOT NULL CHECK (extracted_value >= 0),
  experience bigint NOT NULL CHECK (experience >= 0),
  earnings bigint NOT NULL CHECK (earnings >= 0),
  CONSTRAINT uk_craftov_raid_result_game_player UNIQUE (game_id, player_id)
);

CREATE INDEX IF NOT EXISTS idx_craftov_raid_result_player_time
  ON craftov_raid_results (player_id, ended_at);

COMMIT;
