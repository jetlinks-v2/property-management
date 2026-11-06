-- 仅供当前低资源测试环境（PostgreSQL 0.5 CPU）使用，不作为生产环境通用配置。
-- 只调整巡更事实表的扫描计划，避免多个并行 worker 争抢半核 CPU、重复扫描索引。
-- 配合 20261106-query-performance.sql 的覆盖索引；不改变全局数据库参数或业务数据。
ALTER TABLE pt_generation SET (parallel_workers = 0);
ALTER TABLE pt_task SET (parallel_workers = 0);

-- 扩容后重新比较执行计划；撤销本配置：
-- ALTER TABLE pt_generation RESET (parallel_workers);
-- ALTER TABLE pt_task RESET (parallel_workers);
