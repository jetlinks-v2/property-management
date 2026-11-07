-- PostgreSQL；已有库停收费服务、备份后执行。非 public schema 请统一替换。
-- 只收敛税目主档单价，保留13位整数容量；历史账单与审计快照不改写。
-- 有效小数超过两位时整笔回滚，禁止缩列时由数据库静默舍入。
BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE public.pm_fin_tax_item IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.pm_fin_tax_item WHERE price <> round(price, 2)) THEN
        RAISE EXCEPTION 'Tax item prices exceed 2 decimal places. Resolve these prices before migration.';
    END IF;
END $$;
ALTER TABLE public.pm_fin_tax_item ALTER COLUMN price TYPE numeric(15, 2);
COMMIT;
