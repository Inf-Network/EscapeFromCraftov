-- EscapeFromCraftov explicit PvE kill count for existing raid-report tables.
-- Run once with psql after 20260814_raid_results.sql and before switching
-- database.orm.schema-action to validate.
-- If database.table-prefix is configured, apply it to the table/constraint below.

BEGIN;

ALTER TABLE craftov_raid_results
  ADD COLUMN IF NOT EXISTS pve_kills integer NOT NULL DEFAULT 0;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM pg_constraint
      WHERE conname = 'ck_craftov_raid_result_pve_kills'
        AND conrelid = 'craftov_raid_results'::regclass
  ) THEN
    ALTER TABLE craftov_raid_results
      ADD CONSTRAINT ck_craftov_raid_result_pve_kills
      CHECK (pve_kills >= 0 AND pve_kills <= kills);
  END IF;
END
$$;

COMMIT;
