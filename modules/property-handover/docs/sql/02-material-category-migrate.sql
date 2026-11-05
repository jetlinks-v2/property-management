-- 先部署新实体以建表，再备份业务 schema，执行01盘点。仅一次迁移，遇到歧义整批回滚。
-- 旧问题分类 ID 不动；复制被资料记录引用的分类及其父级，永久保存映射和旧版本。
BEGIN;
LOCK TABLE sp_handover_category,sp_handover_material_category,sp_handover_record IN SHARE ROW EXCLUSIVE MODE;
CREATE TABLE sp_handover_material_category_map AS
WITH RECURSIVE used AS (
 SELECT c.* FROM sp_handover_category c WHERE EXISTS(SELECT 1 FROM sp_handover_record r WHERE r.category_id=c.id)
 UNION
 SELECT p.* FROM sp_handover_category p JOIN used c ON p.id=c.parent_id
)
SELECT id AS old_id,md5('handover-material|'||id) AS new_id FROM used;
ALTER TABLE sp_handover_material_category_map ADD PRIMARY KEY(old_id);
CREATE TABLE sp_handover_material_record_backup AS
 SELECT id,category_id,version FROM sp_handover_record WHERE category_id IS NOT NULL AND category_id<>'';
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM sp_handover_material_record_backup b LEFT JOIN sp_handover_material_category_map m ON m.old_id=b.category_id WHERE m.new_id IS NULL) THEN
  RAISE EXCEPTION '资料存在未知分类，先修复孤立引用';
 END IF;
 IF EXISTS(SELECT 1 FROM sp_handover_material_category_map m JOIN sp_handover_category c ON c.id=m.old_id
  LEFT JOIN sp_handover_material_category_map p ON p.old_id=c.parent_id
  WHERE c.deleted=false GROUP BY c.tenant_id,c.application_id,coalesce(p.new_id,''),c.name HAVING count(*)>1) THEN
  RAISE EXCEPTION '跨项目同级资料分类重名：先由业务确认重命名，禁止自动合并';
 END IF;
END $$;
INSERT INTO sp_handover_material_category
(id,tenant_id,application_id,version,deleted,creator_id,creator_name,create_time,modify_time,active_key,name,parent_id,sort_index,description,enabled,modifier_name)
SELECT m.new_id,c.tenant_id,c.application_id,0,c.deleted,c.creator_id,c.creator_name,c.create_time,c.modify_time,
 CASE WHEN c.deleted THEN NULL ELSE md5(c.tenant_id||'|'||c.application_id||'|'||coalesce(p.new_id,'')||'|'||c.name) END,
 c.name,p.new_id,c.sort_index,c.description,c.enabled,c.creator_name
FROM sp_handover_category c JOIN sp_handover_material_category_map m ON m.old_id=c.id
LEFT JOIN sp_handover_material_category_map p ON p.old_id=c.parent_id;
UPDATE sp_handover_record r SET category_id=m.new_id,version=r.version+1
FROM sp_handover_material_category_map m WHERE r.category_id=m.old_id;
-- 对账：应为0；数量与01盘点/备份一致。保留映射和备份表直到验收完成。
SELECT count(*) AS unmatched FROM sp_handover_material_record_backup b
LEFT JOIN sp_handover_record r ON r.id=b.id
LEFT JOIN sp_handover_material_category_map m ON m.old_id=b.category_id
WHERE r.category_id IS DISTINCT FROM m.new_id;
COMMIT;
