-- 只读盘点；在配置的业务 schema 下执行，不修改任何数据。
SELECT c.tenant_id,c.application_id,c.project_id,c.id,c.parent_id,c.name,c.deleted,
 (SELECT count(*) FROM sp_handover_record r WHERE r.category_id=c.id) AS material_refs,
 (SELECT count(*) FROM sp_handover_problem p WHERE p.category_id=c.id) AS problem_refs,
 (SELECT count(*) FROM sp_handover_ledger l WHERE l.category_id=c.id) AS ledger_refs,
 (SELECT count(*) FROM sp_handover_equipment e WHERE e.category_id=c.id) AS equipment_refs
FROM sp_handover_category c ORDER BY c.tenant_id,c.application_id,c.project_id,c.parent_id,c.name;
-- 孤立引用必须先处理，迁移脚本不会猜测分类。
SELECT r.id,r.category_id FROM sp_handover_record r LEFT JOIN sp_handover_category c ON c.id=r.category_id
WHERE r.category_id IS NOT NULL AND r.category_id<>'' AND c.id IS NULL;
