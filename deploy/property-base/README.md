# 公共物业基础管理接入与菜单迁移

报事报修、品质核查、设施设备能源、巡更和收费统一使用 `jetlinks-v2/property-base-ui` 的 `2.20` 分支。公共模块独立维护组织、用户、空间、客户和字典五类页面、菜单、按钮与中英文资源；基础后端仍是独立的 `property-base-service`，业务服务通过原命令接口读取同一套基础数据。

本目录只记录部署关系和旧菜单对应表，不复制公共 `baseMenu.json`，不在业务模板中再嵌入一套公共定义。公共模块提供定义，Cloud 宿主收集并执行菜单初始化；初始化不等于应用订阅或角色授权。

## 版本与构建

- 基础前端：`property-base-ui/2.20`，宿主路径 `modules/property-base-ui`。
- 应用后端：`property-management/2.20`，需包含客户空间记录模型调整、组织项目章接口，已核对的配套版本为 `bd2db1c` 及其后兼容提交。旧基础服务与新客户页面不能混用；升级前备份数据库，旧 Excel 模板重新下载。此次前端抽取不搬迁基础数据库。
- 宿主：`saas-runtime-ui/property/2.20-dev`。维护各子模块已验证的提交，不使用 `--remote` 批量更新不配套版本。
- 基础后端必须运行，报修、品质、设施等业务后端按启用功能分别启动。加载前端模块不替代后端启动。

在宿主根目录检出公共模块，并执行原依赖安装和集成构建：

```sh
git submodule update --init modules/property-base-ui
pnpm install --frozen-lockfile
pnpm build:property-apps
```

`build:property-apps` 包含公共、报修、品质、设施、运行端和认证等模块，输出宿主根目录 `dist/`。实际环境使用巡更、收费等其他模块时，将其追加到同一白名单或使用宿主完整构建；不要用缺少现有业务的构建覆盖在用站点。单模块 `build` 输出业务远程包，`build:with-base` 输出含公共模块的宿主产物，二者部署目录不同。

继续采用 Cloud PC 集成部署，不需要再启一个公共基础 PC 站点；应用实例使用现有 Cloud 入口、登录、网关、租户和应用上下文。独立 H5 继续单独构建与发布，不将 PC 公共模块打入 H5。

若以后采用独立动态应用分发，再按公共仓库的引用文档发布公共前端应用（应用 ID `property-base`、模块 ID `property-base-ui`）。本次集成方案不需要配置此独立发布记录。

## 新环境初始化

1. 发布包含公共模块的 Cloud 运行端和配套基础后端。
2. 在宿主原菜单初始化界面仅增量初始化公共模块，得到一份“物业基础管理”。其历史菜单 ID 为 `charging-basic` 和 `charging-*`，组件 code 为 `charging/*`，URL 为 `/charging/basic/*`。这些是兼容标识，不能重命名成报修、品质或设施前缀，也不表示依赖收费业务。
3. 从各业务 `deploy/*/menus.json` 导入业务定义；这些文件不包含公共基础副本。业务 `template-grant.json` 也只提供业务菜单授权，不默认授予公共维护能力。
4. 在需要使用公共页面的应用模板、服务套餐中选择已初始化的公共菜单引用，刷新目标项目订阅快照；各应用不重复创建公共菜单定义。模板和套餐需按实际平台配置合并保存，不能用仅含业务的新模板文件覆盖已有完整授权。
5. 为各应用角色按职责配置公共菜单查询、按钮和资产范围。普通业务人员无需维护组织、用户或客户；只要能执行自身业务操作即可。人员、派单、核查分配和设施业主资格继续由对应业务后端校验，不能以通用基础列表替代。

公共菜单有一份定义，但不同应用、模板和角色可以选择不同权限。不能因为某个角色原来能查看工单，就给它全部基础管理权限。

## 已有环境增量迁移

先备份受影响菜单、应用模板、服务套餐、订阅快照及各角色的菜单／按钮和资产授权，再发布配套前后端并初始化公共菜单。对应关系见 [menu-migration.json](menu-migration.json)：

| 原页面 | 报修原 ID | 品质原 ID | 设施原 ID | 公共 ID / code |
| --- | --- | --- | --- | --- |
| 父目录 | repair-system-basic | quality-system-basic | facility-system-basic | charging-basic / charging/basic |
| 组织 | repair-system-OrganizationType | quality-system-OrganizationType | facility-system-OrganizationType | charging-OrganizationType / charging/OrganizationType |
| 用户 | repair-system-User | quality-system-User | facility-system-User | charging-User / charging/User |
| 空间 | repair-system-Space | quality-system-Space | facility-system-Space | charging-Space / charging/Space |
| 客户 | repair-system-Customer | quality-system-Customer | facility-system-Customer | charging-Customer / charging/Customer |
| 字典 | repair-system-Dictionary | quality-system-Dictionary | facility-system-Dictionary | charging-Dictionary / charging/Dictionary |

迁移顺序：

1. 保留旧节点，先将所需公共菜单引用增量加入目标应用模板及服务套餐。公共菜单在本地集成模式下不绑定报修、品质或收费的 `options.appName`；避免从旧节点复制业务 appName 导致页面载入错误。
2. 按角色逐项映射旧查询与按钮授权。相同按钮 ID 仍须核对实际 `permissions`；只授予原有且当前职责需要的动作。客户模型已调整，旧家庭、结束关系等动作没有直接等价项，不能自动转成客户删除权限。新项目章 `seal` 权限独立审批分配，不能由旧组织 `save` 自动推导。
3. 保留原应用、组织、空间、人员及业务资产范围，合并公共菜单所需策略，不将原授权重置为“全部数据”。角色只读时不额外勾选保存、同步、删除或导入按钮。
4. 同步服务菜单授权并刷新目标项目订阅快照，重新保存实际应用角色授权。可沿用原平台 API：`PUT /business-application-template/{id}/menus`、`PUT /saas/service/{serviceId}/runtime/{runtimeId}/menus/_grant` 和 `POST /saas/runtime/{runtimeId}/service/snapshots/_reload`。保存完整合并结果，保留原有其他菜单、按钮及 `assetAccesses`；环境 ID 从目标环境读取。
5. 设施的 `facility-system-Sync`、`facility-system-Operations` 保留原 ID、URL、code、按钮和资产权限，将 `parentId` 改为 `-1`。先提升这两个业务入口，再处理旧父目录，防止递归删除同步和日志菜单。
6. 验收公共菜单、各角色和业务功能后，取消旧基础菜单在目标模板、套餐、订阅和角色中的引用，并隐藏旧节点。确认无其他应用引用后再按平台菜单管理流程清理；不直接全量初始化或批量删除全局菜单。
7. 刷新菜单缓存或重新登录，从项目应用卡片重新进入，检查 `/menu/user-own/tree` 中的实际可见菜单、组件 code、appName 和翻译。

代码删除不会自动删除数据库中的旧菜单，也不会为角色自动授权。可以多次核对、补齐缺项；公共菜单定义仍只初始化一份。

## 验收与回退

- 页面只有一套公共基础管理，五个页面可打开，中英文随 Cloud 语言切换；旧应用基础副本不再出现。
- 管理员可执行已授权的维护操作；只读角色不能保存、同步、删除或导入，未获公共页面权限的业务人员仍只能访问授权业务。
- 项目、空间和客户范围正确，跨项目及越权读取仍由服务端拒绝；公共模块缺失或接口失败明确报错，不伪装为空数据。
- 报修工单、二维码项目与空间选择、派单和提示音；品质任务、人员候选与核查整改；设施同步、运维日志及原业务页面保持可用。H5 使用原业务契约。

构建与本地测试只能证明源码和制品，不代表测试服的角色、订阅和网关已经验收。环境变更时保留上一版前端制品与授权备份；回退先恢复与旧菜单匹配的制品，再恢复对应授权，不能用空菜单集合覆盖其他系统。数据库模型升级的回退按该版本数据库变更另行评估，不能通过换回前端解决。
