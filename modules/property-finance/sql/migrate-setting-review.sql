-- PostgreSQL；已有库停收费服务、备份后执行。非 public schema 请统一替换。
-- 存量设置保留生效状态；普通待审核应收承接原生效设置结果，不改金额。可重复执行。
BEGIN;
LOCK TABLE public.pm_fin_house_setting IN ACCESS EXCLUSIVE MODE;
ALTER TABLE public.pm_fin_house_setting ADD COLUMN IF NOT EXISTS review_state varchar(16);
ALTER TABLE public.pm_fin_house_setting ADD COLUMN IF NOT EXISTS reviewed_by varchar(64);
ALTER TABLE public.pm_fin_house_setting ADD COLUMN IF NOT EXISTS reviewed_name varchar(128);
ALTER TABLE public.pm_fin_house_setting ADD COLUMN IF NOT EXISTS reviewed_at bigint;
ALTER TABLE public.pm_fin_house_setting ADD COLUMN IF NOT EXISTS review_reason varchar(1000);
UPDATE public.pm_fin_house_setting SET review_state = 'APPROVED' WHERE review_state IS NULL;
ALTER TABLE public.pm_fin_house_setting ALTER COLUMN review_state SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_pm_fin_set_review ON public.pm_fin_house_setting (project_id, review_state, deleted);
-- 旧版本保存设置已直接出账；补齐来源审批结果，避免移除二次审核入口后旧账不能收款。
UPDATE public.pm_fin_receivable b SET review_state = 'APPROVED', version = b.version + 1
FROM public.pm_fin_house_setting s
WHERE b.setting_id = s.id AND b.project_id = s.project_id
  AND s.review_state = 'APPROVED' AND b.review_state = 'PENDING'
  AND b.deleted = false AND (b.source_type IS NULL OR b.source_type = 'CHARGING');
COMMIT;
