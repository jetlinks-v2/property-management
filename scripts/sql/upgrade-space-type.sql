-- 同步 SaaS 空间类型与带类型的位置，不触碰已有空间、项目绑定或房屋资料。
BEGIN;
ALTER TABLE public.pm_space ADD COLUMN IF NOT EXISTS space_type_id varchar(64);
ALTER TABLE public.pm_space ADD COLUMN IF NOT EXISTS space_type_name varchar(128);
ALTER TABLE public.pm_space ADD COLUMN IF NOT EXISTS space_location varchar(32768);
COMMIT;
