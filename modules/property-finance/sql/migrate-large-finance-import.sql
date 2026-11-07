-- PostgreSQL；升级前停止收费服务并备份。可重复执行，不改变已有财务金额。
BEGIN;
ALTER TABLE public.pm_fin_collect_import ADD COLUMN IF NOT EXISTS phase varchar(24);
ALTER TABLE public.pm_fin_collect_import ADD COLUMN IF NOT EXISTS processed_count integer;
ALTER TABLE public.pm_fin_recv_import ADD COLUMN IF NOT EXISTS phase varchar(24);
ALTER TABLE public.pm_fin_recv_import ADD COLUMN IF NOT EXISTS processed_count integer;
CREATE TABLE IF NOT EXISTS public.pm_fin_import_task (
 id varchar(64) PRIMARY KEY, project_id varchar(64) NOT NULL, kind varchar(16) NOT NULL,
 operation varchar(16) NOT NULL, state varchar(24) NOT NULL, source_key varchar(64) NOT NULL,
 record_id varchar(64), tenant_id varchar(64), executor_snapshot varchar(60000), file_name varchar(255),
 lease_owner varchar(64), lease_until bigint, reason varchar(1000), error_message varchar(8000),
 version bigint NOT NULL, deleted boolean NOT NULL, deleted_time bigint, create_time bigint,
 modify_time bigint, modifier_id varchar(64), modifier_name varchar(128)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_fin_import_task_source ON public.pm_fin_import_task(source_key);
CREATE INDEX IF NOT EXISTS idx_pm_fin_import_task_scan ON public.pm_fin_import_task(state,lease_until);
CREATE INDEX IF NOT EXISTS idx_pm_fin_collect_imp_pending ON public.pm_fin_collect_import_line(import_id,status,line_number);
CREATE INDEX IF NOT EXISTS idx_pm_fin_recv_imp_pending ON public.pm_fin_recv_import_line(import_id,status,line_number);
COMMIT;
