# 报修菜单与模板交接

这些 JSON 来自本地已部署配置，供另一套 JetLinks 2.20 环境复现。没有测试账号、令牌、密码或连接信息。导入前需同步 `runtime-ui/modules/report-repair-ui`，并启动报修后端。

- `menus.json`：报修相关的 33 个菜单定义，包括共享基础资料。保留原始节点 ID、路由、按钮、资产类型和组件注册码；查验等延期迁移节点不代表本模板已授权。
- `template.json`：官方 provider 的报事报修模板基础定义，不包含本地模板 ID。
- `template-grant.json`：模板完整菜单树、勾选状态及真实资产策略。菜单定义存在与菜单获授权是两回事。

使用运行端平台管理员在菜单管理导入定义；也可由已登录管理会话调用现有 `PATCH /menu` 保存菜单数组。先备份同编码菜单，避免覆盖另一套环境已定制的定义。新建模板使用 `POST /business-application-template`，取得该环境 ID 后以 `PUT /business-application-template/{id}/menus` 保存 template-grant.json。

随后通过服务管理将相关菜单加入套餐，并同步项目订阅快照；在目标 Cloud 项目创建应用、角色和人员，保存角色菜单及资产策略。不要复制本地测试用户或租户 ID 到另一环境。

模板为工单提供 org-include-children 和 tenant 两种可授权范围，具体角色仍须单独选择；列表、详情、统计和动作受后端真实权限限制。修理人员需要 query、accept、follow、finish，调度人员需要 query、create、dispatch。配置人员需要配置 query/save；共享资料同步需要 property-data 的 query/save/sync。

源组织、人员和空间先在 Cloud 维护，再同步、标记业务项目/员工、关联空间；A/B 等业务项目的配置分别创建。应用侧栏树需在实例设置 navigationMode: tree，该配置当前不由模板自动继承。

本次导出已包含统计、评价、导出页面及有偿费用、签字、代登记评价、审核等按钮；导入模板后仍须给实际应用角色授权相应按钮和资产范围。新增页面目录后重启运行端 Vite，避免旧模块路由表导致空白页面。

后端独立启动、环境配置与切换要求见 `../../modules/property-repair/README.md`。

设备关联已随菜单与模板导出：发起按钮增加 `device-instance/query`，设备资产支持所在组织及下级或租户策略。应用角色需要保存对应设备读取范围；不需要设备编辑权限。Cloud 中还须将设备分配给业务项目组织或其下级组织，并在末级分类开启“关联物联网设备”。服务端始终追加当前业务项目组织范围，角色拥有全部设备读取权限也不能跨业务项目选择设备。


二维码入口还需将 `repair-qr-configuration` 加入服务套餐，再刷新目标项目订阅快照，最后检查应用模板与配置角色的菜单/维护按钮及原有资产策略。否则角色授权详情虽已勾选，`/menu/user-own/tree` 仍可能缺项并显示404。可使用运营端 `PUT /saas/service/{serviceId}/runtime/{runtimeId}/menus/_grant` 保存完整原授权并追加菜单，再 `POST /saas/runtime/{runtimeId}/service/snapshots/_reload` 传入目标 `projectIds` 和 `serviceIds`；保留其他菜单与 `assetAccesses`，不要用空集合覆盖。二维码空间先在共享资料关联到业务项目，再在配置中选择允许节点及下级。

操作日志沿用 `repair-0593f6e90e2c4b30a6b2e9f2c73d4106` 菜单，组件为 `repair/OperationLogs`，专用权限为 `repair-operation-log/query`。定义、模板、服务套餐和目标项目订阅快照均需包含该节点；仅给应用管理员授予此权限，普通配置、调度、维修角色不授予。日志覆盖本应用全部业务项目，独立按可信租户和应用隔离，不随工单组织范围缩小。

个人工单提示音随 `report-repair-ui` 代码与 `assets/dingdingding.wav` 一同构建；通过布局扩展注册，无需新增菜单或角色授权。只有当前应用具备报事记录菜单时才显示，默认关闭。个人设置使用 Cloud 的 `GET/PATCH /user/settings/report-repair/new-order-sound`，不需要修改人员表或重启报修后端。开发端首次更新模块入口后完整刷新页面。

独立员工 H5 位于 `jetlinks-v2/report-saas-ui` 仓库 `2.20` 分支的 `employee-h5/`，在该目录执行 `npm ci`、`npm run build` 和 `docker compose -f compose.yml up -d --build`，入口为 9300。它复用上述报修应用及原有角色、菜单权限和数据范围，无需复制菜单或业务数据；部署前配置 `public/config.json` 的 Cloud 项目编码与应用 ID。
