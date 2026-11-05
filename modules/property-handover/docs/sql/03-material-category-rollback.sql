-- 停止查验服务写入并回退应用版本后执行。迁移后有编辑时拒绝覆盖，需人工审查。
BEGIN;
LOCK TABLE sp_handover_record,sp_handover_material_category IN SHARE ROW EXCLUSIVE MODE;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM sp_handover_material_category c JOIN sp_handover_material_category_map m ON c.id=m.new_id WHERE c.version<>0) THEN
  RAISE EXCEPTION '迁移后资料分类已编辑，需人工处理';
 END IF;
 IF EXISTS(SELECT 1 FROM sp_handover_material_record_backup b
 JOIN sp_handover_material_category_map m ON m.old_id=b.category_id
 LEFT JOIN sp_handover_record r ON r.id=b.id
 WHERE r.version IS DISTINCT FROM b.version+1 OR r.category_id IS DISTINCT FROM m.new_id) THEN
  RAISE EXCEPTION '迁移后资料已变化，禁止自动回滚';
 END IF;
 IF EXISTS(SELECT 1 FROM sp_handover_record r JOIN sp_handover_material_category_map m ON r.category_id=m.new_id
 LEFT JOIN sp_handover_material_record_backup b ON b.id=r.id WHERE b.id IS NULL) THEN
  RAISE EXCEPTION '迁移后新增了资料记录，需人工处理';
 END IF;
END $$;
UPDATE sp_handover_record r SET category_id=b.category_id,version=b.version
FROM sp_handover_material_record_backup b WHERE b.id=r.id;
DELETE FROM sp_handover_material_category c USING sp_handover_material_category_map m WHERE c.id=m.new_id;
-- 映射和备份不删除，留作核对；重复运行迁移将明确失败。
COMMIT;
