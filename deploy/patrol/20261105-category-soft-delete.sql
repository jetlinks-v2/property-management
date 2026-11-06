-- 已部署巡更库升级：删除时间为空的历史分类仍然有效，不删除或停用既有数据。
ALTER TABLE pt_category ADD COLUMN IF NOT EXISTS deleted_time BIGINT;
