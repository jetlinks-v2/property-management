-- PostgreSQL；已有库停收费服务、备份后执行，非 public schema 请统一替换。
-- 旧活动承接为已通过，保留 enabled 和资金/参与记录；重复执行不覆盖新审批状态。
BEGIN;
LOCK TABLE public.pm_fin_activity IN ACCESS EXCLUSIVE MODE;
ALTER TABLE public.pm_fin_activity ADD COLUMN IF NOT EXISTS review_state varchar(16);
ALTER TABLE public.pm_fin_activity ADD COLUMN IF NOT EXISTS reviewed_by varchar(64);
ALTER TABLE public.pm_fin_activity ADD COLUMN IF NOT EXISTS reviewed_name varchar(128);
ALTER TABLE public.pm_fin_activity ADD COLUMN IF NOT EXISTS reviewed_at bigint;
ALTER TABLE public.pm_fin_activity ADD COLUMN IF NOT EXISTS review_reason varchar(1000);
UPDATE public.pm_fin_activity SET review_state = 'APPROVED' WHERE review_state IS NULL;
ALTER TABLE public.pm_fin_activity ALTER COLUMN review_state SET NOT NULL;
COMMIT;
