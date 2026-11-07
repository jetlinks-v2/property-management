-- PostgreSQL：回退时独立执行，不放入事务。
DROP INDEX CONCURRENTLY IF EXISTS idx_repair_order_accept_timing;
