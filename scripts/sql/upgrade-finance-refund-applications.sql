-- PostgreSQL；在收费业务库执行，申请只占用额度，审核成功才生成资金事实。
-- 发布前备份。既有已成功退款不迁入申请表，也不改变原收款和余额。
BEGIN;
ALTER TABLE pm_fin_payment_line ADD COLUMN IF NOT EXISTS refund_reserved_amount numeric(20,2) NOT NULL DEFAULT 0;
ALTER TABLE pm_fin_fund_account ADD COLUMN IF NOT EXISTS refund_reserved_amount numeric(20,2) NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS pm_fin_refund_application (
    id varchar(64) PRIMARY KEY, version bigint NOT NULL, deleted boolean NOT NULL,
    deleted_time bigint, create_time bigint, modify_time bigint, modifier_id varchar(64), modifier_name varchar(128),
    project_id varchar(64) NOT NULL, number varchar(64) NOT NULL, source_type varchar(20) NOT NULL,
    status varchar(20) NOT NULL, company_id varchar(64) NOT NULL, company_name varchar(100) NOT NULL,
    receiver_name varchar(128) NOT NULL, method_text varchar(100) NOT NULL, refunded_at varchar(30) NOT NULL,
    reason varchar(1000) NOT NULL, amount numeric(20,2) NOT NULL, result_id varchar(64),
    reviewed_by varchar(64), reviewer_name varchar(128), reviewed_at bigint, review_reason varchar(1000),
    action_request_id varchar(100), fingerprint varchar(64) NOT NULL, input_json varchar(65535) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pm_fin_refund_app ON pm_fin_refund_application(project_id,status);

CREATE TABLE IF NOT EXISTS pm_fin_refund_app_line (
    id varchar(64) PRIMARY KEY, version bigint NOT NULL, deleted boolean NOT NULL,
    deleted_time bigint, create_time bigint, modify_time bigint, modifier_id varchar(64), modifier_name varchar(128),
    project_id varchar(64) NOT NULL, status varchar(20) NOT NULL, application_id varchar(64) NOT NULL,
    source_id varchar(64) NOT NULL, space_id varchar(64) NOT NULL, full_name varchar(2000) NOT NULL,
    customer_name varchar(128) NOT NULL, subject_name varchar(100) NOT NULL,
    calc_start_date varchar(10), calc_end_date varchar(10), amount numeric(20,2) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pm_fin_refund_app_line ON pm_fin_refund_app_line(project_id,application_id);

CREATE TABLE IF NOT EXISTS pm_fin_refund_app_log (
    id varchar(64) PRIMARY KEY, version bigint NOT NULL, deleted boolean NOT NULL,
    deleted_time bigint, create_time bigint, modify_time bigint, modifier_id varchar(64), modifier_name varchar(128),
    project_id varchar(64) NOT NULL, application_id varchar(64) NOT NULL, action varchar(20) NOT NULL,
    reason varchar(1000), snapshot_json varchar(65535) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pm_fin_refund_log ON pm_fin_refund_app_log(project_id,application_id,create_time);
COMMIT;
