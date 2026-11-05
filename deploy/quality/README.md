# 品质核查菜单与模板

本目录的 `menus.json` 和 `template-grant.json` 只提供品质业务菜单及授权树；公共组织、用户、空间、客户和字典由 `property-base-ui` 唯一提供，不在品质模板中复制。后端为独立 `property-quality-service`，基础档案由 `property-base-service` 维护。

先按 [公共基础管理接入](../property-base/README.md) 初始化公共菜单一次，再导入品质业务定义、配置应用模板及服务套餐、刷新目标项目订阅快照，并为角色配置业务与所需公共菜单授权。所有操作均合并现有配置，保留原资产范围；定义存在不等于模板、订阅和角色已授权。

已有 `quality-system-*` 节点需按公共对应表迁移，验收后停用；前端源码移除旧页面不会自动修改持久化菜单。项目、员工和分配候选继续使用 `/quality/context/**` 的业务过滤，不能用公共人员列表替代执行资格。PC 构建必须同时包含 `quality-inspection-ui`、`property-base-ui` 及实际 SaaS 宿主模块，独立员工 H5 沿用自己的构建与接口。
