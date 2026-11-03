-- 本地物业默认字典。只插入缺项；保留已有、停用及逻辑删除记录。
-- ID 与 PropertyDictionaryService.identity 的 UUID v3 规则一致。
BEGIN;
INSERT INTO public.pm_dictionary_type (id,code,name,enabled,sort_index,version,deleted,create_time,modify_time,modifier_id,modifier_name) VALUES
('e02856a9-6db6-3058-bba9-cc8e90e68493','house-type','房屋类型',true,10,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('db9468c7-d869-3087-8cb1-fb3499576daf','house-state','房态',true,20,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('5963416e-0db1-3062-aebf-44caba16e0ce','customer-nature','客户性质',true,30,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('8816f303-8194-33c1-96cd-9aee47e1ad0d','certificate-type','证件类型',true,40,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化')
ON CONFLICT DO NOTHING;
INSERT INTO public.pm_dictionary_item (id,type_code,value,text,enabled,sort_index,version,deleted,create_time,modify_time,modifier_id,modifier_name) VALUES
('f2b2334a-db45-3217-8049-e15482638e8a','house-type','residential','住宅',true,10,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('08bdc2f0-f87c-30d1-b76e-ea0e595ef855','house-type','shop','商铺',true,20,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('39ad149d-f8f1-31b6-a7e3-0c8e24a31bff','house-type','office','办公',true,30,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('93cb2d65-259f-3fb6-a5ca-dc48fff78ac4','house-type','parking','车位/车库',true,40,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('6c3a67fc-2c3c-3591-8184-c63f9044d941','house-state','unoccupied','未入住',true,10,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('5c409b4e-d00f-3fe7-93a6-1174fdc3d081','house-state','occupied','自住',true,20,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('c0c9c806-864f-3229-82e4-dbc4ad365d8e','house-state','rented','出租',true,30,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('6b87789e-7a49-35a1-8c72-feb61702fcf7','house-state','vacant','空置',true,40,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('4d71785b-ad0a-3413-84d4-db6227b311b4','customer-nature','owner','业主',true,10,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('d11080b4-7d44-39dd-ab66-896cff8164f0','customer-nature','tenant','租户',true,20,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('931f84e1-dd5b-3d78-9120-e7b2b8499cf6','customer-nature','family','家人',true,30,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('de95d569-4c7f-36eb-972f-332a0ed3b828','certificate-type','id-card','居民身份证',true,10,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('db20dbde-e2d7-3f81-bddc-9e0bc9677205','certificate-type','passport','护照',true,20,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('f6bf79ee-4b06-39d8-a6c0-99356d16cebf','certificate-type','hk-macao-permit','港澳居民来往内地通行证',true,30,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('afaa693a-0053-31cd-b899-e5daf1eda45a','certificate-type','taiwan-permit','台湾居民来往大陆通行证',true,40,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化'),
('60bfccf6-0011-3eb6-8f6d-47a536393cbe','certificate-type','other','其他',true,50,0,false,(extract(epoch from transaction_timestamp()) * 1000)::bigint,(extract(epoch from transaction_timestamp()) * 1000)::bigint,'dictionary-init','默认字典初始化')
ON CONFLICT DO NOTHING;
COMMIT;
