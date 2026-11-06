-- 更新巡更服务前执行：仅调整初版字段名，不映射旧自建角色为应用角色，不删除业务行。
-- 若上次自动建表已创建空的目标列，先去掉空列再重命名原列；目标列有数据时拒绝覆盖。
-- 旧测试指派仍需重新选择当前应用角色；不会据此取得应用角色成员资格。
DO $$
DECLARE
    item record;
    populated boolean;
BEGIN
    FOR item IN SELECT * FROM (VALUES
        ('pt_plan', 'post_id', 'role_id'),
        ('pt_roster', 'post_id', 'role_id'),
        ('pt_task', 'post_id', 'role_id'),
        ('pt_task', 'post_name', 'role_name'),
        ('pt_generation', 'post_id', 'role_id'),
        ('pt_generation', 'post_name', 'role_name')
    ) AS changes(table_name, old_column, new_column)
    LOOP
        IF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = item.table_name
                     AND column_name = item.old_column) THEN
            EXECUTE format('LOCK TABLE public.%I IN ACCESS EXCLUSIVE MODE', item.table_name);
            IF EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = 'public' AND table_name = item.table_name
                         AND column_name = item.new_column) THEN
                EXECUTE format('SELECT EXISTS (SELECT 1 FROM public.%I WHERE %I IS NOT NULL)',
                               item.table_name, item.new_column) INTO populated;
                IF populated THEN
                    RAISE EXCEPTION 'Both role columns contain data: %.%', item.table_name, item.new_column;
                END IF;
                EXECUTE format('ALTER TABLE public.%I DROP COLUMN %I', item.table_name, item.new_column);
            END IF;
            EXECUTE format('ALTER TABLE public.%I RENAME COLUMN %I TO %I',
                           item.table_name, item.old_column, item.new_column);
        END IF;
    END LOOP;
END $$;
