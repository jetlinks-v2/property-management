-- PostgreSQL 16：先执行本脚本再启动收费服务。只补空业务单号，不更改任何内部ID和金额。

BEGIN;

-- 历史补号独立命名空间；序列跨重复执行持续递增，新增旧数据也不会撞号。
CREATE SEQUENCE IF NOT EXISTS public.pm_fin_legacy_document_no;

ALTER TABLE public.pm_fin_receivable ADD COLUMN IF NOT EXISTS bill_no varchar(40);

ALTER TABLE public.pm_fin_payment ADD COLUMN IF NOT EXISTS bill_no varchar(40);

ALTER TABLE public.pm_fin_fund_entry ADD COLUMN IF NOT EXISTS bill_no varchar(40);

ALTER TABLE public.pm_fin_refund ADD COLUMN IF NOT EXISTS bill_no varchar(40);

WITH pending AS (
    SELECT b.id, 'YS' AS prefix,
           to_char(to_timestamp(coalesce(b.create_time, 0) / 1000.0) AT TIME ZONE 'Asia/Shanghai', 'YYYYMMDD') AS day,
           nextval('public.pm_fin_legacy_document_no') AS serial
    FROM public.pm_fin_receivable b
    WHERE b.bill_no IS NULL OR btrim(b.bill_no) = ''
)
UPDATE public.pm_fin_receivable b
SET bill_no = p.prefix || p.day || 'H' || lpad(p.serial::text, 12, '0')
FROM pending p WHERE b.id = p.id;

DO $$ BEGIN IF EXISTS (SELECT 1 FROM public.pm_fin_receivable WHERE bill_no IS NULL OR btrim(bill_no) = '') THEN RAISE EXCEPTION 'Unnumbered receivable rows remain; check related account data'; END IF; END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_fin_receivable_bill_no ON public.pm_fin_receivable(bill_no);

WITH pending AS (
    SELECT b.id, CASE WHEN source_type = 'TEMPORARY' THEN 'LS' ELSE 'SS' END AS prefix,
           to_char(to_timestamp(coalesce(b.create_time, 0) / 1000.0) AT TIME ZONE 'Asia/Shanghai', 'YYYYMMDD') AS day,
           nextval('public.pm_fin_legacy_document_no') AS serial
    FROM public.pm_fin_payment b
    WHERE b.bill_no IS NULL OR btrim(b.bill_no) = ''
)
UPDATE public.pm_fin_payment b
SET bill_no = p.prefix || p.day || 'H' || lpad(p.serial::text, 12, '0')
FROM pending p WHERE b.id = p.id;

DO $$ BEGIN IF EXISTS (SELECT 1 FROM public.pm_fin_payment WHERE bill_no IS NULL OR btrim(bill_no) = '') THEN RAISE EXCEPTION 'Unnumbered payment rows remain; check related account data'; END IF; END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_fin_payment_bill_no ON public.pm_fin_payment(bill_no);

WITH pending AS (
    SELECT e.id, CASE WHEN a.type = 'DEPOSIT' THEN 'YJ' ELSE 'YC' END AS prefix,
           to_char(to_timestamp(coalesce(e.create_time, 0) / 1000.0) AT TIME ZONE 'Asia/Shanghai', 'YYYYMMDD') AS day,
           nextval('public.pm_fin_legacy_document_no') AS serial
    FROM public.pm_fin_fund_entry e JOIN public.pm_fin_fund_account a ON a.id = e.account_id AND a.project_id = e.project_id
    WHERE e.bill_no IS NULL OR btrim(e.bill_no) = ''
)
UPDATE public.pm_fin_fund_entry b
SET bill_no = p.prefix || p.day || 'H' || lpad(p.serial::text, 12, '0')
FROM pending p WHERE b.id = p.id;

DO $$ BEGIN IF EXISTS (SELECT 1 FROM public.pm_fin_fund_entry WHERE bill_no IS NULL OR btrim(bill_no) = '') THEN RAISE EXCEPTION 'Unnumbered fund_entry rows remain; check related account data'; END IF; END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_fin_fund_entry_bill_no ON public.pm_fin_fund_entry(bill_no);

WITH pending AS (
    SELECT b.id, 'TK' AS prefix,
           to_char(to_timestamp(coalesce(b.create_time, 0) / 1000.0) AT TIME ZONE 'Asia/Shanghai', 'YYYYMMDD') AS day,
           nextval('public.pm_fin_legacy_document_no') AS serial
    FROM public.pm_fin_refund b
    WHERE b.bill_no IS NULL OR btrim(b.bill_no) = ''
)
UPDATE public.pm_fin_refund b
SET bill_no = p.prefix || p.day || 'H' || lpad(p.serial::text, 12, '0')
FROM pending p WHERE b.id = p.id;

DO $$ BEGIN IF EXISTS (SELECT 1 FROM public.pm_fin_refund WHERE bill_no IS NULL OR btrim(bill_no) = '') THEN RAISE EXCEPTION 'Unnumbered refund rows remain; check related account data'; END IF; END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_fin_refund_bill_no ON public.pm_fin_refund(bill_no);

COMMIT;
