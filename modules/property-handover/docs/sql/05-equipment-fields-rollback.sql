BEGIN;
LOCK TABLE sp_handover_equipment IN SHARE ROW EXCLUSIVE MODE;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM sp_handover_equipment e JOIN sp_handover_equipment_fields_backup b USING(id)
 WHERE e.version NOT IN (b.version,b.version+1)) THEN RAISE EXCEPTION '迁移后存在编辑，请人工核对回滚'; END IF;
END $$;
UPDATE sp_handover_equipment e SET supplier=b.supplier,production_date=b.production_date,version=b.version
FROM sp_handover_equipment_fields_backup b WHERE e.id=b.id;
COMMIT;
