-- 计费管理默认字典（PostgreSQL，UTF-8）。
-- 先启动物业服务完成 EasyORM 建表，再执行基础模块 init-property-dictionaries.sql 和本文件。
-- 来源为旧系统 SourceChargeEnum、PatternPaymentEnum、tcs_sys.sql 中真实费用类型。
-- 欠费等级旧282无可核对选项，本文件只建分类，请按业务实际维护等级后配置欠费通知。
-- 仅补缺、不覆盖已有编辑/启停/逻辑删除；ID遵循基础字典UUID v3规则；可重复执行。
-- 默认public schema，其他schema请统一替换；本脚本不创建公司、科目、用户或账务数据。
BEGIN;

WITH defaults(id,code,name,sort_index) AS (
    VALUES
    ('246d9d53-f060-3456-b301-ceac4ce42556', 'source_charge', '收费来源', 100),
    ('339c91f8-4473-340f-bc7c-d200228ab307', 'pattern_payment', '支付方式', 110),
    ('5e737be2-450f-3c7f-a9d2-a0d783291598', 'charge-item-in-type', '费用类型', 120),
    ('9087aad1-12e1-3006-a83d-bc2ceab2c917', 'arrears-level', '欠费等级', 130),
    ('02bc3063-1897-36cc-bf28-1d4315816b1d', 'promotion-type', '活动类型', 140)
)
INSERT INTO public.pm_dictionary_type(id,code,name,enabled,sort_index,version,deleted,create_time,modify_time,modifier_id,modifier_name)
SELECT id,code,name,true,sort_index,0,false,
       (extract(epoch FROM transaction_timestamp())*1000)::bigint,
       (extract(epoch FROM transaction_timestamp())*1000)::bigint,'finance-init','计费字典初始化'
FROM defaults
WHERE NOT EXISTS (SELECT 1 FROM public.pm_dictionary_type e WHERE e.code=defaults.code)
ON CONFLICT DO NOTHING;

WITH defaults(id,type_code,value,text,sort_index) AS (
    VALUES
    ('907d7d24-fb96-3f26-8a3a-350e8d299f8a', 'promotion-type', 'prepay', '充值赠送', 10),
    ('54c150ad-827a-3c06-8d29-157cb0fbf666', 'source_charge', '1', 'PC', 10),
    ('dd608d84-3a6c-3718-9453-f1df835311f6', 'source_charge', '2', '微信公众号', 20),
    ('3c419dac-7442-3f41-9fac-04af6a875dde', 'source_charge', '3', 'POS机', 30),
    ('1cfe9719-f381-33bf-9f1c-742860c31576', 'source_charge', '4', '微信', 40),
    ('94b5bc56-40b7-3116-b99c-41c84c9456e2', 'source_charge', '5', '支付宝', 50),
    ('f5a664ff-48f0-33f7-a9fc-858797148a87', 'source_charge', '6', '银联云闪付', 60),
    ('2e06937a-d942-3d0e-8828-607ecf2e15c6', 'source_charge', '7', '数字人民币', 70),
    ('acb8df69-8492-3b20-b5b6-e21feb27232b', 'source_charge', '9', '积分', 80),
    ('4504f072-3338-3645-ad38-306b8ce653cf', 'source_charge', '10', '临停车', 90),
    ('1bb374cb-7718-355f-a649-1a9714e92cb1', 'source_charge', '12', '美好家app支付', 100),
    ('41fa6464-ce2d-3e59-bf67-6d5763807210', 'source_charge', '13', '历史导入', 110),
    ('6458c6cc-50e6-37dd-8a29-303e4c3bf0e8', 'pattern_payment', '1', '现金', 10),
    ('00c73c28-5ce5-314f-a184-a7dcc0388920', 'pattern_payment', '2', '客户汇款', 20),
    ('4169e018-5c36-3b9a-9718-b3edc53b73c2', 'pattern_payment', '3', '转账', 30),
    ('f5aedc3f-2fd9-38da-ade1-9be0243041a0', 'pattern_payment', '4', 'pos', 40),
    ('d28deafc-b25b-3916-948c-4cb3073fd511', 'pattern_payment', '9', '退款转预收', 50),
    ('01172c05-3161-3a21-8814-b8f613f0e6e2', 'pattern_payment', '15', '预收款划入', 60),
    ('8f3abc91-3f6b-3a38-b268-ceb4107505be', 'pattern_payment', '16', '预收款划出', 70),
    ('30e447bd-9d2f-3fc8-8af5-45321fc739a2', 'pattern_payment', '17', '预收款结转', 80),
    ('90d2cad1-8b0b-321c-af1e-8e4ac17777b2', 'pattern_payment', '18', '历史数据导入', 90),
    ('bb251b9e-6f18-3207-afbb-16dc43e8bb15', 'pattern_payment', '19', '应收抵扣', 100),
    ('71948519-b2a3-3218-ae5e-65b0234fb82c', 'pattern_payment', '8', '押金类转', 110),
    ('8fd07aa7-9965-3326-8d91-43dd9668c2b2', 'pattern_payment', '20', '用户扫码', 120),
    ('2e516df5-e890-3f05-8fd4-ccbd2f860741', 'pattern_payment', '21', '小程序支付', 130),
    ('6a60bef3-98da-37a4-bba9-0ce10833f0cb', 'pattern_payment', '22', '微信公众号', 140),
    ('b7d4259e-0de4-33b3-afed-4b680a7b659f', 'pattern_payment', '23', '支付宝生活号', 150),
    ('9121898c-e079-319f-a438-70242c72dbe7', 'pattern_payment', '24', 'JS支付', 160),
    ('bf0b80c8-1c59-391b-8af5-3c4389deadc9', 'pattern_payment', '25', 'SDK支付', 170),
    ('ee4ff095-f20c-3c2b-9530-d90c4e0d8aa5', 'pattern_payment', '26', 'H5支付', 180),
    ('2ea4c1b2-6f04-3430-add8-e180cee4201a', 'pattern_payment', '5', '已缴款', 190),
    ('1fa59bd4-cfbf-3b34-9aab-3434db6aeb6c', 'pattern_payment', '27', '扫码盒子-微信', 200),
    ('3ac865e4-7de2-33d4-87dc-69f1ad10d3e9', 'pattern_payment', '28', '扫码盒子-支付宝', 210),
    ('b4271544-9cd0-335b-9692-22d7b83da2b5', 'pattern_payment', '29', '扫码盒子-银联云闪付', 220),
    ('f4976cc8-efb6-358f-a72a-5b653946c4e8', 'pattern_payment', '30', '每日停车收费', 230),
    ('2f4320de-01f3-3a96-83db-7eae1d03a815', 'charge-item-in-type', '1', '常规收费项目', 10),
    ('78d414a6-2edc-3873-adcd-b553f583e4de', 'charge-item-in-type', '2', '抄表收费项目', 20),
    ('e9415a5e-c0be-39f5-b709-551d6b084587', 'charge-item-in-type', '3', '临时收费项目', 30),
    ('2a837803-00b5-3477-b5af-554d7653ad64', 'charge-item-in-type', '4', '租赁收费项目', 40),
    ('36109c6b-7db8-3041-b6be-0dac167bd5cc', 'charge-item-in-type', '5', '分摊收费项目', 50),
    ('b6126781-217b-310f-96f9-9419448c9dcf', 'charge-item-in-type', '6', '押金收费项目', 60),
    ('593d4598-7172-3bf4-8326-67679377147a', 'charge-item-in-type', '7', '出入证收费项目', 70)
)
INSERT INTO public.pm_dictionary_item(id,type_code,value,text,enabled,sort_index,version,deleted,create_time,modify_time,modifier_id,modifier_name)
SELECT id,type_code,value,text,true,sort_index,0,false,
       (extract(epoch FROM transaction_timestamp())*1000)::bigint,
       (extract(epoch FROM transaction_timestamp())*1000)::bigint,'finance-init','计费字典初始化'
FROM defaults
WHERE NOT EXISTS (SELECT 1 FROM public.pm_dictionary_item e WHERE e.type_code=defaults.type_code AND e.value=defaults.value)
ON CONFLICT DO NOTHING;
COMMIT;
