-- 旧版本空间已关联项目、但未登记组织资产归属时执行。PostgreSQL；先连接基础模块业务库并选择相应 schema。
-- ID/target_key 与 JetLinks DimensionAssetBindingEntity 的生成规则一致。
-- 仅补齐有效项目中有效空间的缺失归属，不覆盖已有授权、不改角色权限、不删除资产或历史数据。
INSERT INTO s_dimension_assets_bind
    (id, target_type, target_id, target_key, asset_type, asset_id, relation, permission, update_time)
SELECT md5('org|' || binding.organization_id || '|space|' || space.id),
       'org', binding.organization_id, md5('org|' || binding.organization_id),
       'space', space.id, 'owner', 15, floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint
FROM pm_space space
JOIN pm_space_binding binding ON binding.id = space.root_id AND binding.deleted = false
JOIN pm_organization organization ON organization.id = binding.organization_id
    AND organization.deleted = false AND organization.organization_type = 'project'
WHERE space.deleted = false
ON CONFLICT (id) DO NOTHING;
