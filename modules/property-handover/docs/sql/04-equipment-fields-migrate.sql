-- 仅迁移旧设备模板实际使用的 supplier/productionDate 扩展字段。
-- 部署新字段后备份 schema 再执行；非法日期会使整个事务回滚，先修复源值。
BEGIN;
LOCK TABLE sp_handover_equipment IN SHARE ROW EXCLUSIVE MODE;
CREATE TABLE sp_handover_equipment_fields_backup AS
 SELECT id,supplier,production_date,version FROM sp_handover_equipment;
UPDATE sp_handover_equipment SET
 supplier=coalesce(nullif(supplier,''),nullif(properties::jsonb->>'supplier','')),
 production_date=coalesce(production_date,
  CASE WHEN nullif(properties::jsonb->>'productionDate','') IS NULL THEN NULL
   ELSE (extract(epoch FROM ((properties::jsonb->>'productionDate')::date::timestamp AT TIME ZONE 'Asia/Shanghai'))*1000)::bigint END),
 version=version+1
WHERE nullif(properties,'') IS NOT NULL AND
 ((nullif(supplier,'') IS NULL AND nullif(properties::jsonb->>'supplier','') IS NOT NULL)
 OR (production_date IS NULL AND nullif(properties::jsonb->>'productionDate','') IS NOT NULL));
-- properties 不删除，原始扩展参数完整保留。
COMMIT;
