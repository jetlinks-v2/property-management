-- PostgreSQL 巡更历史查询索引；逐条执行，不放入事务块，避免建索引阻塞业务写入。
-- 与既有测试库索引同名，重复执行不创建副本。
CREATE INDEX CONCURRENTLY IF NOT EXISTS pt_history_group_business_date
    ON pt_generation (business_date, plan_id, id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS pt_history_group_belong_date
    ON pt_generation (belong_date DESC, id) INCLUDE (plan_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS pt_history_group_plan
    ON pt_generation (plan_id, id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS pt_history_group_id_plan
    ON pt_generation (id) INCLUDE (plan_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS pt_history_task_belong_date
    ON pt_task (belong_date, plan_id);

ANALYZE pt_plan;
ANALYZE pt_generation;
ANALYZE pt_task;
ANALYZE pt_execution;
