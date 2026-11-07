-- PostgreSQL：在报修业务库独立执行，不放入事务。
-- 支持接单及时率关联派单时间的统计查询。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_repair_order_accept_timing ON sp_repair_order(id) INCLUDE(accept_time) WHERE accept_time IS NOT NULL;
ANALYZE sp_repair_order;
