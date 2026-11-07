-- 月度统计只读取执行事实的标识、人员、时间和结果，避免扫描含答案/标准快照的整张执行表。
-- 逐条执行，不放入事务；仅作用于巡更业务库，不修改共享数据库全局参数。
CREATE INDEX CONCURRENTLY IF NOT EXISTS pt_exec_statistics_task
    ON pt_execution (task_id)
    INCLUDE (id, project_id, point_id, user_id, user_name, receive_time, result);

ANALYZE pt_execution;

-- 回退（索引不会改变业务数据）：
-- DROP INDEX CONCURRENTLY IF EXISTS pt_exec_statistics_task;
