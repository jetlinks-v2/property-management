# 智能巡更后端

本仓库是巡更后端的唯一开发入口。巡更是独立的 JetLinks SaaS 业务服务，只依赖自己的 manager/core，不调用基础档案或财务系统，不在门禁服务内实现巡更，不复制 SaaS 的登录、用户、组织或授权角色。面向 PC 管理端和后续执行端，业务时间统一为 `Asia/Shanghai`。

## 模块与能力入口

| 模块 | 职责 |
| --- | --- |
| `property-patrol-core` | 无 I/O 的规则展开、跨天冲突检查、执行资格策略 |
| `property-patrol-manager` | 配置资产、计划、岗位、排班、任务与执行事务、人员 SDK 适配、查询与统计 |
| `services/property-patrol-service` | SaaS 微服务启动、认证与发现、PostgreSQL/R2DBC、Redis |

PC 模块位于 SaaS 的 `runtime-ui/modules/cloud-patrol-ui`，前端仍独立维护。配置包括分类、点位、检查标准、业务岗位、人员排班和巡更计划；管理端可以预览/手动生成某计划某日的任务，查询任务、执行结果和完成统计。在线执行接口保留给后续执行端，PC 不冒充移动打卡终端。

**这不是旧系统全量迁移版本。** 不包含小程序页面、后台自动生成调度、二维码图像生成/硬件识别、附件、标签、集团模板、排班日历和导入导出、离线包/补传、漏检申报审批、预警通知、人员评价、集团综合报表、旧 API 兼容及历史数据迁移。数据库测试与独立打包也不代表已经通过真实 SaaS 联调或生产验收。

## 数据与业务契约

- 六类配置是独立资产，使用 `AssetsHolder` 维护项目与应用范围。任务、执行记录和统计关联 `patrolPlan`，不能通过自传项目 ID 扩大查询范围。
- 新增 ID、归属和审计由服务端维护。新增配置与资产绑定在同一事务；编辑不能移动归属。配置只提供新增/完整编辑，不开放物理删除；通过停用保留历史引用。
- `scopeKey` 是项目和应用组成的业务唯一性边界，不是数据权限过滤的替代物。点位编码在此范围内唯一。分类不能循环，计划的点位、岗位和标准必须同范围且可用。
- 岗位是巡更业务分组，不是应用管理员等授权角色。人员只通过认证服务公开 SDK 读取，必须同时为启用账号、当前项目成员、当前应用成员并通过 `user` 资产权限。
- 一个计划关联一个点位、一个岗位、一份标准及一套频次。**同岗位的任务完成次数共享，不按岗位内每个人各生成一份任务**；不同岗位必须建立不同计划。
- 日期覆盖优先于星期规则，空覆盖表示当日休巡。时段左闭右开，结束分钟小于开始分钟表示跨天，结束 `1440` 表示当日24点；不足整周期的尾段不生成任务。跨天任务归开始的逻辑日期。
- 排班也归班次开始日，单班不超过24小时。`optional` 表示同应用当日完全没有排班时放行排班条件；已有排班则必须命中本人、本岗位和时窗。`required` 始终要求有效排班。岗位成员资格、任务时窗和冷却校验不会因此放宽。
- 首次生成某计划某日的封印及全部任务在同一事务内写入；即使该日无任务，也保留封印。并发依靠数据库唯一键防重，规则/标准编辑不会覆盖该日任务快照，也不会因为规则版本变化重复应巡。
- 任务保存点位/岗位名称、时窗、次数、冷却、排班策略、检查项等快照。执行资格复核当前计划/点位/岗位启用状态、实时岗位成员、当前账号/应用成员和排班。历史记录按快照解释。
- 提交使用服务端当前用户；`requestId` 在同一执行用户下必须唯一。同请求重放不重复计数，不同内容复用同编号拒绝。任务计数 CAS 和执行记录原子提交，失败整体回滚。
- JSON 列通过 EasyORM 公开元数据扩展复用 Spring 的标准 `ObjectMapper`，与 HTTP 的日期键和 record 契约一致。

## API

统一使用宿主认证、响应包装及 QueryParam/PagerResult，不另建旧式返回包装。下表是服务内部路径；经过网关时的外部前缀由平台部署决定。

| 操作 | 路径 | 功能权限 |
| --- | --- | --- |
| 配置分页/详情 | `POST /park/patrol/{resource}/_query`、`GET /park/patrol/{resource}/{id}` | 对应 `patrol-category/point/standard/post/roster/plan:query` |
| 新增/完整编辑配置 | `POST /park/patrol/{resource}`、`PUT /park/patrol/{resource}/{id}` | 对应 `:save`，编辑另校验资产 save |
| 可用人员分页 | `POST /park/patrol/persons/_query` | `patrol-post:query` |
| 任务分页/详情 | `/park/patrol/tasks/**` 只读查询 | `patrol-task:query` + 计划资产 read |
| 任务预览 | `POST /park/patrol/tasks/_preview` | `patrol-task:query` + 计划资产 read |
| 手动生成 | `POST /park/patrol/tasks/_generate` | `patrol-task:generate` + 计划资产 save |
| 执行结果提交 | `POST /park/patrol/executions` | `patrol-execution:submit` + 计划资产 read + 业务资格 |
| 执行记录分页/详情 | `/park/patrol/executions/**` 只读查询 | `patrol-execution:query` + 计划资产 read |
| 完成统计 | `POST /park/patrol/reports/summary` | `patrol-report:query` + 计划资产范围 |

`resource` 为 `categories`、`points`、`standards`、`posts`、`rosters`、`plans`。预览/生成请求为 `{ "planId": "…", "date": "YYYY-MM-DD" }`。提交请求为 `{ "taskId": "…", "requestId": "…", "answers": [{"itemId":"…","value":"…"}], "remark": "…" }`，不接受客户端执行用户或采集时间来绕过资格。

统计接收 `{ "from": "YYYY-MM-DD", "to": "YYYY-MM-DD" }`，含首尾最多31天。`expected` 为应巡次数之和，`completed` 为已接受执行次数之和，`completeWindows` 为达标时段数，`overdueMissing` 为已结束时段未完成次数。完成率不等于检查合格率，申请/审批豁免不混入正常执行事实。尚未生成的任务不在统计分母中。

## 构建与测试

以下命令从仓库根目录执行。需要 JDK17、Maven 及目标 SaaS 对应的 JetLinks `2.20.0-SNAPSHOT` 制品和仓库访问配置。不要仅凭版本字符串判断 SNAPSHOT 内容完全一致。

```bash
mvn -B -ntp -pl :property-patrol-service -am test
mvn -B -ntp -pl :property-patrol-service -am package
```

可执行产物：`services/property-patrol-service/target/application.jar`。macOS 中文路径环境若系统 locale 不可用，可在 Maven 命令前设置 `LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8`，并显式选择 JDK17。

测试使用隔离的 R2DBC H2 与生产相同的 EasyORM 注解、SQL 执行器、事务管理器和租户资产实现，覆盖规则、生成封印、快照、并发、防重、回滚、排班、权限和统计；认证 SDK 的网络边界采用测试替身。H2 的 CLOB 编码在并发测试中隔离到 `boundedElastic`，生产链路不因此切换线程。上线前仍需真实 PostgreSQL、网关及 SaaS 项目/应用权限验收。

## SaaS 接入与运行配置

使用 `deploy/patrol/patrol.env.example` 准备环境变量，不包含真实密码。数据库默认名为 `cloud_patrol`，建议分配独立业务库和账号，避免误用平台测试库；由部署方先创建数据库。持久化表由平台 EasyORM 初始化机制管理。HTTP 默认8502、集群18502、RPC28502，端口均可覆盖；多实例必须配置不同 `SERVER_ID`。

```bash
# 仅在接入环境准备完毕后执行，不属于平台部署脚本。
set -a
source /absolute/path/to/patrol.env
set +a
java \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.time=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  --add-opens=java.base/java.text=ALL-UNNAMED \
  --add-opens=java.base/java.math=ALL-UNNAMED \
  --add-opens=java.scripting/javax.script=ALL-UNNAMED \
  -jar services/property-patrol-service/target/application.jar
```

JDK17/21 下沿用平台启动脚本的模块开放参数，供现有 Redis/FST 序列化组件使用。缺少这些参数可能在集群节点写入 Redis 时触发 `InaccessibleObjectException`；不要通过关闭集群或替换公共序列化格式绕过。

必须和平台对齐集群种子、RPC token、JWT 公钥、网络可达的广播地址及 Redis 配置。业务库保持独立；不要复制参考项目里的默认密码，也不要为巡更另起一个认证系统。

应用装配顺序：

1. 启动巡更服务并确认发现与网关路由能够到达 `/park/patrol/**`。服务名为 `cloud-patrol-service`；反向代理/外部前缀需由部署方与 PC 的宿主请求路径对齐。
2. 将 PC 模块接入 runtime-ui，**增量**导入巡更菜单，不全量覆盖其他应用菜单。
3. 将 `deploy/patrol/authentication-patrol-application.yml` 作为认证服务的外部配置加载，不能加载到巡更服务代替认证装配。模板初始化关联已有菜单；已存在同 code 的模板不会靠重启自动覆写。
4. 在项目中创建启用 `CloudPatrol` 应用，分配成员、菜单/按钮权限并关联获准使用的用户资产。新建巡更配置资产为 `SELECT_AND_BIND`，平台用户为 `SELECT`。既有资产需要显式关联应用。
5. 从项目的**应用入口**进入。项目上下文、`X-Asset-Scope` 与认证由宿主携带，不手工加旁路 token。用两个项目、同项目不同应用、只读与维护角色分别验收。

PC 模板不默认授予执行提交权限；未来执行端需要独立装配该权限。没有权限的用户不能通过直接调用提交接口替代授权。后台自动任务生成也需要明确服务身份、授权与运维策略，不能靠绕过 AssetsHolder 的定时扫描代替。
