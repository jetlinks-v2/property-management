-- PostgreSQL；仅扩展交账冻结快照，不回写历史资金事实。发布前由运维在目标收费库执行。
-- 先只读确认列/缺失量；历史编号由 HIS-ID 展示，不伪造新交账或新收款。
SELECT table_name, column_name FROM information_schema.columns
WHERE table_name IN ('pm_fin_handover', 'pm_fin_handover_line')
AND column_name IN ('handover_no', 'attachments_json', 'calc_start_date', 'calc_end_date');
BEGIN;
ALTER TABLE pm_fin_handover ADD COLUMN IF NOT EXISTS handover_no varchar(40);
ALTER TABLE pm_fin_handover ADD COLUMN IF NOT EXISTS attachments_json varchar(8000);
ALTER TABLE pm_fin_handover_line ADD COLUMN IF NOT EXISTS calc_start_date varchar(10);
ALTER TABLE pm_fin_handover_line ADD COLUMN IF NOT EXISTS calc_end_date varchar(10);
ALTER TABLE pm_fin_handover_line ADD COLUMN IF NOT EXISTS business_type varchar(30);
COMMIT;
-- 历史附件继续读取旧单文件列，不强制补齐文件大小；历史缺失费用期间保留NULL。
-- 不可用交账日、到期日或当天来伪造费用期间；新PAYMENT交账直接保存分配行期间快照。
