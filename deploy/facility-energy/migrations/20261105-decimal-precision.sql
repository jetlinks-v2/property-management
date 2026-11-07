-- Default numeric(32,0) rounds configuration and readings. Preserve its integer
-- capacity while supporting all 12 fractional digits allowed by metric mapping.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
ALTER TABLE fe_device
  ALTER COLUMN power TYPE numeric(44,12),
  ALTER COLUMN purchase_price TYPE numeric(44,12);
ALTER TABLE fe_rule
  ALTER COLUMN threshold TYPE numeric(44,12),
  ALTER COLUMN critical_threshold TYPE numeric(44,12),
  ALTER COLUMN near_ratio TYPE numeric(44,12);
ALTER TABLE fe_reading
  ALTER COLUMN raw_value TYPE numeric(44,12),
  ALTER COLUMN value TYPE numeric(44,12);
ALTER TABLE fe_alert
  ALTER COLUMN value TYPE numeric(44,12),
  ALTER COLUMN threshold TYPE numeric(44,12);
ALTER TABLE fe_replacement
  ALTER COLUMN final_reading TYPE numeric(44,12),
  ALTER COLUMN initial_reading TYPE numeric(44,12);
ALTER TABLE fe_energy_target
  ALTER COLUMN target_usage TYPE numeric(44,12),
  ALTER COLUMN management_area TYPE numeric(44,12);
COMMIT;
