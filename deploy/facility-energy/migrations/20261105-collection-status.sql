BEGIN;
ALTER TABLE fe_mapping ADD COLUMN IF NOT EXISTS last_collection_time bigint;
ALTER TABLE fe_mapping ADD COLUMN IF NOT EXISTS last_collection_success_time bigint;
ALTER TABLE fe_mapping ADD COLUMN IF NOT EXISTS collection_error varchar(256);
COMMIT;
