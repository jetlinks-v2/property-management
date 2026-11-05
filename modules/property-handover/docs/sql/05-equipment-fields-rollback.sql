BEGIN;
LOCK TABLE sp_handover_equipment IN SHARE ROW EXCLUSIVE MODE;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM sp_handover_equipment_fields_backup b
 LEFT JOIN sp_handover_equipment e USING(id)
 WHERE e.id IS NULL OR e.version IS DISTINCT FROM b.migrated_version
 OR e.supplier IS DISTINCT FROM b.migrated_supplier
 OR e.production_date IS DISTINCT FROM b.migrated_production_date)
 THEN RAISE EXCEPTION '迁移行已编辑或删除，请人工核对回滚'; END IF;
END $$;
UPDATE sp_handover_equipment e SET supplier=b.supplier,production_date=b.production_date,version=b.version
FROM sp_handover_equipment_fields_backup b WHERE e.id=b.id;
COMMIT;
