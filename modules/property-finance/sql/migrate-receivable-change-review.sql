-- PostgreSQL；停服、备份后执行。非 public schema 请统一替换。可重复执行。
-- 本脚本只增加变更申请和历史账任务身份，不修改存量审核状态或金额。
BEGIN;
CREATE TABLE IF NOT EXISTS public.pm_fin_recv_change (
    id varchar(64) PRIMARY KEY,
    project_id varchar(64) NOT NULL,
    space_id varchar(64) NOT NULL,
    receivable_id varchar(64) NOT NULL,
    source_version bigint NOT NULL,
    full_name varchar(2000) NOT NULL,
    customer_name varchar(255) NOT NULL,
    subject_name varchar(255) NOT NULL,
    operation varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    reason varchar(1000) NOT NULL,
    applicant_id varchar(64) NOT NULL,
    applicant_name varchar(128) NOT NULL,
    input_json varchar(100000) NOT NULL,
    preview_json varchar(100000) NOT NULL,
    before_json varchar(100000) NOT NULL,
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
CREATE INDEX IF NOT EXISTS idx_pm_fin_recv_change_scope ON public.pm_fin_recv_change (project_id, space_id, status);
CREATE INDEX IF NOT EXISTS idx_pm_fin_recv_change_bill ON public.pm_fin_recv_change (receivable_id, status);
ALTER TABLE public.pm_fin_receivable ADD COLUMN IF NOT EXISTS tenant_id varchar(64);
ALTER TABLE public.pm_fin_billing_task ADD COLUMN IF NOT EXISTS receivable_id varchar(64);
ALTER TABLE public.pm_fin_billing_task ALTER COLUMN setting_id DROP NOT NULL;

-- 只从数据库中已经冻结的业务归属回填租户，不能把未知租户直接当作默认租户。
UPDATE public.pm_fin_receivable b SET tenant_id = s.tenant_id
FROM public.pm_fin_house_setting s
WHERE b.setting_id = s.id AND b.project_id = s.project_id AND b.tenant_id IS NULL AND s.tenant_id IS NOT NULL;

WITH project_tenant AS (
    SELECT project_id, min(tenant_id) AS tenant_id
    FROM public.pm_fin_period_control GROUP BY project_id HAVING count(*) = 1
)
UPDATE public.pm_fin_receivable b SET tenant_id = p.tenant_id
FROM project_tenant p WHERE b.project_id = p.project_id AND b.tenant_id IS NULL;

-- 同项目存在多租户或缺少期间归属时必须人工核对；拒绝猜测和继续启动。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.pm_fin_receivable WHERE deleted = false AND tenant_id IS NULL) THEN
        RAISE EXCEPTION 'Receivable tenant ownership is unresolved. Verify project/setting/period ownership before rerunning this migration.';
    END IF;
END $$;
COMMIT;
