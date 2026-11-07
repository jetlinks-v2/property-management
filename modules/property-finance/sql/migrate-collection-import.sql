-- PostgreSQL；新部署由EasyORM创建；既有部署备份并停收费服务后执行，可重复执行。
-- 只增加导入暂存表，不修改已有收款、余额或应收数据；非public schema请替换。
BEGIN;
CREATE TABLE IF NOT EXISTS public.pm_fin_collect_import (
 id varchar(64) PRIMARY KEY, project_id varchar(64) NOT NULL, kind varchar(16) NOT NULL,
 file_name varchar(255) NOT NULL, status varchar(20) NOT NULL, total_count integer,
 success_count integer, rejected_count integer, error_count integer, error_message varchar(8000),
 operator_id varchar(64), operator_name varchar(128), finish_time bigint,
 version bigint NOT NULL, deleted boolean NOT NULL, deleted_time bigint, create_time bigint,
 modify_time bigint, modifier_id varchar(64), modifier_name varchar(128)
);
CREATE INDEX IF NOT EXISTS idx_pm_fin_collect_imp_project ON public.pm_fin_collect_import(project_id,kind,create_time);
CREATE TABLE IF NOT EXISTS public.pm_fin_collect_import_line (
 id varchar(64) PRIMARY KEY, project_id varchar(64) NOT NULL, space_id varchar(64) NOT NULL,
 import_id varchar(64) NOT NULL, source_key varchar(64) NOT NULL, full_name varchar(2000) NOT NULL,
 customer_name varchar(255) NOT NULL, line_number integer NOT NULL, status varchar(16) NOT NULL,
 payload_json varchar(20000) NOT NULL, review_reason varchar(1000), reviewed_by varchar(64),
 reviewed_name varchar(128), reviewed_at bigint, result_id varchar(64),
 version bigint NOT NULL, deleted boolean NOT NULL, deleted_time bigint, create_time bigint,
 modify_time bigint, modifier_id varchar(64), modifier_name varchar(128)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_fin_collect_imp_source ON public.pm_fin_collect_import_line(source_key);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_fin_collect_imp_row ON public.pm_fin_collect_import_line(import_id,line_number);
CREATE INDEX IF NOT EXISTS idx_pm_fin_collect_imp_scope ON public.pm_fin_collect_import_line(project_id,space_id,status);
COMMIT;
