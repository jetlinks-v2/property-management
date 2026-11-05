-- PostgreSQL；现有部署停服务并备份后执行。非 public schema 请替换 schema；可重复执行。
-- 新部署由 EasyORM 建表；既有 SUCCESS 批次及正式应收保持原样，不改成待审核。
BEGIN;
ALTER TABLE public.pm_fin_recv_import ADD COLUMN IF NOT EXISTS rejected_count integer;
CREATE TABLE IF NOT EXISTS public.pm_fin_recv_import_line (
    id varchar(64) PRIMARY KEY,
    project_id varchar(64) NOT NULL,
    space_id varchar(64) NOT NULL,
    import_id varchar(64) NOT NULL,
    full_name varchar(2000) NOT NULL,
    customer_name varchar(255) NOT NULL,
    line_number integer NOT NULL,
    status varchar(16) NOT NULL,
    payload_json varchar(100000) NOT NULL,
    review_reason varchar(1000),
    reviewed_by varchar(64),
    reviewed_name varchar(128),
    reviewed_at bigint,
    version bigint NOT NULL,
    deleted boolean NOT NULL,
    deleted_time bigint,
    create_time bigint,
    modify_time bigint,
    modifier_id varchar(64),
    modifier_name varchar(128)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_fin_recv_imp_row ON public.pm_fin_recv_import_line (import_id, line_number);
CREATE INDEX IF NOT EXISTS idx_pm_fin_recv_imp_scope ON public.pm_fin_recv_import_line (project_id, space_id, status);
COMMIT;
