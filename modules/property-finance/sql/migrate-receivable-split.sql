-- PostgreSQL，升级前停物业服务并备份；全新库由 EasyORM 建表，无需执行。
-- 使用其他 schema 时统一替换 public.。重复执行不会覆盖已有账单身份。
BEGIN;
LOCK TABLE public.pm_fin_receivable IN ACCESS EXCLUSIVE MODE;
ALTER TABLE public.pm_fin_receivable ADD COLUMN IF NOT EXISTS bill_key varchar(160);
ALTER TABLE public.pm_fin_receivable ADD COLUMN IF NOT EXISTS source_receivable_id varchar(64);
UPDATE public.pm_fin_receivable
SET bill_key = setting_id || ':' || period_start || ':' || period_end
WHERE bill_key IS NULL AND source_receivable_id IS NULL;
-- 历史无完整来源时必须人工核对，不能用随机键掩盖重复账期。
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM public.pm_fin_receivable WHERE bill_key IS NULL) THEN
        RAISE EXCEPTION '存在无法回填的账单身份，请核对来源后再升级';
    END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_fin_recv_bill_key ON public.pm_fin_receivable (bill_key);
ALTER TABLE public.pm_fin_receivable ALTER COLUMN bill_key SET NOT NULL;
ALTER TABLE public.pm_fin_receivable DROP CONSTRAINT IF EXISTS uk_pm_fin_recv_period;
DROP INDEX IF EXISTS public.uk_pm_fin_recv_period;
CREATE INDEX IF NOT EXISTS idx_pm_fin_recv_source ON public.pm_fin_receivable (source_receivable_id, deleted);
COMMIT;
