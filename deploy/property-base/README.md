# 公共物业基础管理接入

公共前端由 `jetlinks-v2/property-base-ui` 的 `2.20` 分支维护，宿主路径 `modules/property-base-ui`。组织、用户、空间、客户和字典使用 `charging-basic`、`charging-*` 菜单 ID 及 `charging/*` 组件 code；这些兼容标识不表示依赖收费业务。基础后端由独立 `property-base-service` 持有。

品质 PC 构建同时加载公共模块和实际 SaaS 依赖，员工 H5 独立构建。先增量初始化公共菜单，再在品质应用模板、套餐和角色中按职责引用所需公共页面和按钮，刷新项目订阅；不将公共定义复制进品质菜单或覆盖其他系统。

已有品质 `quality-system-*` 基础菜单按 `menu-migration.json` 映射到公共菜单，保留原动作与资产范围，使用品质目录中的离线转换脚本生成待审核授权。验证新入口及管理员、只读、普通员工权限后停用旧引用。转换前备份模板、套餐、订阅和角色，不全量初始化菜单。业务项目和核查候选仍使用 `/quality/context/**` 的资格校验。

文件校验由 Cloud 文件所有者装配 `property-file-support`；人员目录读取由认证服务提供 `authService:user-directory`。品质与基础服务不直接查询 Cloud 数据库。发布时必须配套提供这两个公开能力。
