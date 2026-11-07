-- PostgreSQL；停止收费服务并备份 pm_fin_carry_subject、pm_fin_carry_plan 后执行。
-- 非 public schema 请统一替换；不修改计划配置、资金和执行历史。
BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE public.pm_fin_carry_plan, public.pm_fin_carry_subject IN ACCESS EXCLUSIVE MODE;
ALTER TABLE public.pm_fin_carry_subject ADD COLUMN IF NOT EXISTS standard_id varchar(64);

-- 仅按明确的单科目、单标准配置回填；旧多选或缺失配置必须先人工修正。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.pm_fin_carry_subject c
        LEFT JOIN public.pm_fin_carry_plan p ON p.id = c.plan_id
        WHERE NOT c.deleted AND c.active_key IS NOT NULL AND (
            p.id IS NULL OR p.deleted OR p.project_id <> c.project_id
            OR CASE
                WHEN jsonb_typeof(p.config_json::jsonb -> 'subjectIds') = 'array'
                 AND jsonb_typeof(p.config_json::jsonb -> 'standardIds') = 'array'
                THEN jsonb_array_length(p.config_json::jsonb -> 'subjectIds') <> 1
                  OR jsonb_array_length(p.config_json::jsonb -> 'standardIds') <> 1
                  OR (p.config_json::jsonb -> 'subjectIds' ->> 0) IS DISTINCT FROM c.subject_id
                  OR COALESCE(length(trim(p.config_json::jsonb -> 'standardIds' ->> 0)), 0) NOT BETWEEN 1 AND 64
                  OR (c.standard_id IS NOT NULL AND c.standard_id IS DISTINCT FROM
                        (p.config_json::jsonb -> 'standardIds' ->> 0))
                ELSE true
            END
        )
    ) THEN
        RAISE EXCEPTION 'Invalid carry-forward scope. Edit or delete legacy plans before migration.';
    END IF;
END $$;

UPDATE public.pm_fin_carry_subject c
SET standard_id = p.config_json::jsonb -> 'standardIds' ->> 0
FROM public.pm_fin_carry_plan p
WHERE p.id = c.plan_id AND NOT c.deleted AND c.active_key IS NOT NULL
  AND c.standard_id IS NULL;

-- 防止旧版本服务继续写入无标准的有效占用，绕过组合唯一键。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.pm_fin_carry_subject'::regclass
          AND conname = 'ck_pm_fin_carry_scope_standard'
    ) THEN
        ALTER TABLE public.pm_fin_carry_subject
            ADD CONSTRAINT ck_pm_fin_carry_scope_standard
            CHECK (deleted OR active_key IS NULL OR standard_id IS NOT NULL);
    END IF;
END $$;

-- 先建立细粒度约束再移除旧约束；重复占用导致整笔回滚。
CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_fin_carry_scope
    ON public.pm_fin_carry_subject (project_id, subject_id, standard_id, active_key);
ALTER TABLE public.pm_fin_carry_subject DROP CONSTRAINT IF EXISTS uk_pm_fin_carry_subject;
DROP INDEX IF EXISTS public.uk_pm_fin_carry_subject;
COMMIT;
