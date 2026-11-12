-- 巡更查询专用索引；每条语句单独执行，不放入事务块。
-- 执行记录按计划资产计数时只读取窄索引，避免扫描答案、图片及标准快照。
CREATE INDEX CONCURRENTLY IF NOT EXISTS pt_exec_plan_query ON pt_execution (plan_id);

-- 首时段用于确认任务组真实存在；计数只需组 ID，不读取分类/等级等快照。
CREATE INDEX CONCURRENTLY IF NOT EXISTS pt_task_first_group
    ON pt_task (task_group_id)
    WHERE period_no = 1;

-- 月历只需日期、项目、点位与进度，历史 business_date 回退仍在同一索引内校验。
CREATE INDEX CONCURRENTLY IF NOT EXISTS pt_task_calendar_facts
    ON pt_task (belong_date, business_date, plan_id)
    INCLUDE (project_id, point_id, start_time, end_time, required_count, completed_count);

ANALYZE pt_execution;
ANALYZE pt_task;

-- 验证索引 indisvalid/indisready；IF NOT EXISTS 不会修复同名的无效索引。
-- 回退（不修改业务数据或权限）：
-- DROP INDEX CONCURRENTLY IF EXISTS pt_exec_plan_query;
-- DROP INDEX CONCURRENTLY IF EXISTS pt_task_first_group;
-- DROP INDEX CONCURRENTLY IF EXISTS pt_task_calendar_facts;
