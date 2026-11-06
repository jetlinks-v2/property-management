-- 巡更业务范围从“租户|应用”统一为“应用”，不修改平台 tenant_id、资产绑定或任何权限。
-- 与新版巡更后端同时发布；先停止巡更写入并备份受影响行的 id/scope_key。
-- 应用 ID 来自原范围键，仅剥离一个旧前缀；无分隔符的新键保持不变，可重复执行。
BEGIN;

-- 同一应用不同旧前缀下若存在重复点位编码，整笔迁移拒绝，不能合并或删除业务点位。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pt_point
        GROUP BY CASE WHEN scope_key ~ '^[^|]*[|][^|]+$'
                      THEN split_part(scope_key, '|', 2) ELSE scope_key END, code
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION '巡更应用范围迁移失败：同一应用存在重复点位编码，请先核对';
    END IF;
END $$;

DO $$
DECLARE
    target_table text;
BEGIN
    FOREACH target_table IN ARRAY ARRAY[
        'pt_level', 'pt_standard', 'pt_category', 'pt_tag', 'pt_warning',
        'pt_point', 'pt_plan', 'pt_roster', 'pt_attachment',
        'pt_point_category', 'pt_config_history', 'pt_post'
    ] LOOP
        -- pt_post 仅兼容旧库遗留表，新服务不再装配此表。
        IF to_regclass(target_table) IS NOT NULL THEN
            EXECUTE format('UPDATE %I SET scope_key = split_part(scope_key, ''|'', 2) '
                           'WHERE scope_key ~ ''^[^|]*[|][^|]+$''', target_table);
        END IF;
    END LOOP;
END $$;

COMMIT;
