-- PostgreSQL；升级前停止收费服务并备份。全新库由 EasyORM 建字段，无需执行 DDL。
-- 本脚本仅供人工审核执行，本任务没有对正式环境执行任何 DDL / 业务写入。
-- 其它 schema 请统一替换 public。回填依据是已确认的原历史导入模板第九列“应收日期”，
-- 旧代码将其存入 due_date；仅复制此来源，不把普通账单到期日或迁移当天当成应收日期。
BEGIN;
LOCK TABLE public.pm_fin_receivable IN ACCESS EXCLUSIVE MODE;
ALTER TABLE public.pm_fin_receivable ADD COLUMN IF NOT EXISTS receivable_date varchar(10);
UPDATE public.pm_fin_receivable SET receivable_date = due_date, version = version + 1
 WHERE receivable_date IS NULL AND source_type = 'HISTORICAL_IMPORT' AND import_id IS NOT NULL;
COMMIT;
-- 必须核对未确定历史来源；日期筛选不包含空日期，界面/导出保留空白，不静默猜测。
SELECT source_type, COUNT(*) AS missing_business_dates FROM public.pm_fin_receivable
 WHERE receivable_date IS NULL GROUP BY source_type;
