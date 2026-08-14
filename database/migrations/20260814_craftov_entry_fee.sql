-- EscapeFromCraftov action-tier entry-fee economy audit type.
-- Run once before enabling action-tier admission. If database.table-prefix is
-- configured, apply it to the table and constraint names below.

BEGIN;

-- Hibernate generated this enum CHECK before CRAFTOV_ENTRY_FEE existed.
-- PostgreSQL does not expand the allowed values when the Java enum changes.
ALTER TABLE craftov_economy_transactions
  DROP CONSTRAINT IF EXISTS craftov_economy_transactions_transaction_type_check;

ALTER TABLE craftov_economy_transactions
  ADD CONSTRAINT craftov_economy_transactions_transaction_type_check
  CHECK (transaction_type IN (
    'LOOT_SELL', 'SHOP_BUY', 'SHOP_SELL', 'MARKET_BUY', 'MARKET_SELL',
    'CRAFTOV_ENTRY_FEE', 'CRAFTOV_REWARD', 'CRAFTOV_PENALTY', 'ADMIN'
  ));

COMMIT;
