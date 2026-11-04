-- PostgreSQL：已有应收表升级时停服务并备份后执行；全新库由 EasyORM 建表。
-- 如不是 public schema，执行前统一替换。可重复执行；禁止用伪造的收费设置ID填充历史应收。
BEGIN;
LOCK TABLE public.pm_fin_receivable IN ACCESS EXCLUSIVE MODE;
ALTER TABLE public.pm_fin_receivable ALTER COLUMN setting_id DROP NOT NULL;
ALTER TABLE public.pm_fin_receivable ADD COLUMN IF NOT EXISTS source_type varchar(32);
ALTER TABLE public.pm_fin_receivable ADD COLUMN IF NOT EXISTS import_id varchar(64);
ALTER TABLE public.pm_fin_receivable ADD COLUMN IF NOT EXISTS account_period varchar(7);
ALTER TABLE public.pm_fin_receivable ADD COLUMN IF NOT EXISTS contract_code varchar(100);
ALTER TABLE public.pm_fin_receivable ADD COLUMN IF NOT EXISTS remark varchar(1000);
COMMIT;
