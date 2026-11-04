-- 物业基础模块默认字典（PostgreSQL，UTF-8）。
-- 按 2026-11-04 当前字典整理：4 类、17 项；不包含账号或业务档案。
-- 先启动物业后端完成 EasyORM 建表，再连接物业后端使用的数据库执行。
-- 默认 schema 为 public；使用其他 schema 时统一替换本文件中的 public.。
-- 仅补缺项，不覆盖已有名称、排序、说明、启停或逻辑删除状态。
-- ID 遵循 PropertyDictionaryService.identity 的 UUID v3 规则；新记录重新生成审计时间。
BEGIN;

-- 字典分类：4 条。
WITH defaults (id, code, name, enabled, sort_index, description) AS (
    VALUES
    ('e02856a9-6db6-3058-bba9-cc8e90e68493', 'house-type', '房屋类型', true, 10, NULL),
    ('db9468c7-d869-3087-8cb1-fb3499576daf', 'house-state', '房态', true, 20, NULL),
    ('5963416e-0db1-3062-aebf-44caba16e0ce', 'customer-nature', '客户性质', true, 30, NULL),
    ('8816f303-8194-33c1-96cd-9aee47e1ad0d', 'certificate-type', '证件类型', true, 40, NULL)
)
INSERT INTO public.pm_dictionary_type (
    id, code, name, enabled, sort_index, description,
    version, deleted, create_time, modify_time, modifier_id, modifier_name
)
SELECT defaults.id, defaults.code, defaults.name, defaults.enabled, defaults.sort_index, defaults.description,
       0, false,
       (extract(epoch FROM transaction_timestamp()) * 1000)::bigint,
       (extract(epoch FROM transaction_timestamp()) * 1000)::bigint,
       'dictionary-init', '默认字典初始化'
FROM defaults
WHERE NOT EXISTS (
    SELECT 1 FROM public.pm_dictionary_type existing WHERE existing.code = defaults.code
)
ON CONFLICT DO NOTHING;

-- 字典选项：17 条。
WITH defaults (id, type_code, value, text, enabled, sort_index, description) AS (
    VALUES
    ('de95d569-4c7f-36eb-972f-332a0ed3b828', 'certificate-type', 'id-card', '居民身份证', true, 10, NULL),
    ('db20dbde-e2d7-3f81-bddc-9e0bc9677205', 'certificate-type', 'passport', '护照', true, 20, NULL),
    ('f6bf79ee-4b06-39d8-a6c0-99356d16cebf', 'certificate-type', 'hk-macao-permit', '港澳居民来往内地通行证', true, 30, NULL),
    ('afaa693a-0053-31cd-b899-e5daf1eda45a', 'certificate-type', 'taiwan-permit', '台湾居民来往大陆通行证', true, 40, NULL),
    ('60bfccf6-0011-3eb6-8f6d-47a536393cbe', 'certificate-type', 'other', '其他', true, 50, NULL),
    ('4d71785b-ad0a-3413-84d4-db6227b311b4', 'customer-nature', 'owner', '业主', true, 10, NULL),
    ('d11080b4-7d44-39dd-ab66-896cff8164f0', 'customer-nature', 'tenant', '租户', true, 20, NULL),
    ('931f84e1-dd5b-3d78-9120-e7b2b8499cf6', 'customer-nature', 'family', '家人', true, 30, NULL),
    ('6c3a67fc-2c3c-3591-8184-c63f9044d941', 'house-state', 'unoccupied', '未入住', true, 10, NULL),
    ('5c409b4e-d00f-3fe7-93a6-1174fdc3d081', 'house-state', 'occupied', '自住', true, 20, NULL),
    ('c0c9c806-864f-3229-82e4-dbc4ad365d8e', 'house-state', 'rented', '出租', true, 30, NULL),
    ('6b87789e-7a49-35a1-8c72-feb61702fcf7', 'house-state', 'vacant', '空置', true, 40, NULL),
    ('f2b2334a-db45-3217-8049-e15482638e8a', 'house-type', 'residential', '住宅', true, 10, NULL),
    ('08bdc2f0-f87c-30d1-b76e-ea0e595ef855', 'house-type', 'shop', '商铺', true, 20, NULL),
    ('39ad149d-f8f1-31b6-a7e3-0c8e24a31bff', 'house-type', 'office', '办公', true, 30, NULL),
    ('93cb2d65-259f-3fb6-a5ca-dc48fff78ac4', 'house-type', 'parking', '车位', true, 40, ''),
    ('5b8ead7e-3c60-347f-9295-ca1734637fcf', 'house-type', 'hotel', '酒店', true, 50, '')
)
INSERT INTO public.pm_dictionary_item (
    id, type_code, value, text, enabled, sort_index, description,
    version, deleted, create_time, modify_time, modifier_id, modifier_name
)
SELECT defaults.id, defaults.type_code, defaults.value, defaults.text, defaults.enabled, defaults.sort_index, defaults.description,
       0, false,
       (extract(epoch FROM transaction_timestamp()) * 1000)::bigint,
       (extract(epoch FROM transaction_timestamp()) * 1000)::bigint,
       'dictionary-init', '默认字典初始化'
FROM defaults
WHERE NOT EXISTS (
    SELECT 1 FROM public.pm_dictionary_item existing WHERE existing.type_code = defaults.type_code AND existing.value = defaults.value
)
ON CONFLICT DO NOTHING;

COMMIT;
