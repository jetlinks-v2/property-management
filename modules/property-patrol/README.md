# 智能巡更后端

本仓库是巡更后端的唯一开发入口。巡更是独立的 JetLinks SaaS 业务服务，只依赖自己的 manager/core，不调用基础档案或财务系统，不在门禁服务内实现巡更，不复制 SaaS 的登录、用户、组织或授权角色。面向 PC 管理端和后续执行端，业务时间统一为 `Asia/Shanghai`。

## 模块与能力入口

| 模块 | 职责 |
| --- | --- |
| `property-patrol-core` | 无 I/O 的规则展开、跨天冲突检查、执行资格策略 |
| `property-patrol-manager` | 配置资产、计划、岗位、排班、任务与执行事务、检查判定、受控附件、人员 SDK 适配、查询与统计 |
| `services/property-patrol-service` | SaaS 微服务启动、认证与发现、PostgreSQL/R2DBC、Redis |

PC 模块位于 SaaS 的 `runtime-ui/modules/cloud-patrol-ui`，前端仍独立维护。配置包括分类、点位、检查标准、业务岗位、人员排班和巡更计划；PC 沿原菜单组织，岗位成员维护、逐点位岗位/等级/标准指派、月模板下载与导入统一从点位管理进入；内部计划和时段任务不设独立菜单。记录按任务组分页，详情查看真实时段，统计保留原有三类。在线执行接口保留给后续执行端，PC 不冒充移动打卡终端。

**这不是旧系统全量迁移版本。** 不包含小程序页面、二维码图像生成/硬件识别、集团模板、点位批量导入导出、离线包/补传、漏检申报审批、预警通知、人员评价、集团综合报表、旧 API 兼容及历史数据迁移。数据库测试与独立打包也不代表已经通过真实 SaaS 联调或生产验收。

## 数据与业务契约

- 各类配置是独立资产，使用 `AssetsHolder` 维护项目与应用范围。任务、执行记录和统计关联 `patrolPlan`，不能通过自传项目 ID 扩大查询范围。
- 新增 ID、归属和审计由服务端维护。新增配置与资产绑定在同一事务；编辑不能移动归属。配置只提供新增/完整编辑，不开放物理删除；通过停用保留历史引用。
- `scopeKey` 是项目和应用组成的业务唯一性边界，不是数据权限过滤的替代物。点位编码在此范围内唯一。分类不能循环，计划的点位、岗位和标准必须同范围且可用。
- 岗位是巡更业务分组，不是应用管理员等授权角色。人员只通过认证服务公开 SDK 读取，必须同时为启用账号、当前项目成员、当前应用成员并通过 `user` 资产权限。
- 一个计划关联一个点位、一个岗位、一份标准及一套频次。**同岗位的任务完成次数共享，不按岗位内每个人各生成一份任务**；不同岗位必须建立不同计划。
- 日期覆盖优先于星期规则，空覆盖表示当日休巡。时段左闭右开，结束分钟小于开始分钟表示跨天，结束 `1440` 表示当日24点；不足整周期的尾段不生成任务。跨天任务归开始的逻辑日期。
- 排班也归班次开始日，单班不超过24小时。`optional` 表示同应用当日完全没有排班时放行排班条件；已有排班则必须命中本人、本岗位和时窗。`required` 始终要求有效排班。岗位成员资格、任务时窗和冷却校验不会因此放宽。
- 任务每天上海时间 00:01 自动生成，按持久化计划归属恢复租户；HTTP 手动生成仍要求计划资产 save。多节点使用同一数据库封印防重，失败按计划记录日志。
- 月排班使用岗位/人员 ID，前四行表头、第五行示例、第六行起填写；班次表明确起止时间，结束小于开始表示跨天，休息不计在班。最多1000行、5MB、100个班次，不直接接受旧手机号模板。整月替换先验证岗位成员和既有排班资产 save，再在事务内停用旧排班、插入和绑定新排班；失败整批回滚，并发版本防止交错覆盖。
- 日历按原系统的任务结束日展示，任务组使用规则归属日，二者口径独立。
- 首次生成某计划某日的封印及全部任务在同一事务内写入；即使该日无任务，也保留封印。并发依靠数据库唯一键防重，规则/标准编辑不会覆盖该日任务快照，也不会因为规则版本变化重复应巡。
- 任务保存点位/岗位名称、时窗、次数、冷却、排班策略、检查项等快照。执行资格复核当前计划/点位/岗位启用状态、实时岗位成员、当前账号/应用成员和排班。历史记录按快照解释。
- 提交使用服务端当前用户；`requestId` 在同一执行用户下必须唯一。同请求重放不重复计数，不同内容复用同编号拒绝。任务计数 CAS 和执行记录原子提交，失败整体回滚。
- 检查项支持 `number/text/choice/image`，缺少类型的已部署标准按 `choice` 读取。数值上下限包含边界，最多18位整数和6位小数，通过 JSON 字符串传输防止浏览器丢失精度；超限结果保存为不合格，不阻止有效执行计数。选项只有显式配置 `qualifiedOptions` 才判定合格与否，文本参考、图像及无判定规则的项目为 `not_evaluated`，不把上传成功当成检查合格。
- 整体结果：任一检查项不合格即 `fail`；全部合格才为 `pass`；其余（包括可选未填）为 `not_evaluated`。旧记录没有判定时显示未判定，不按当前标准回算。执行记录保存题目、规则、答案及逐题判定快照。
- 图片每题最多一张，例图与执行图片用途隔离。上传只接受 JPEG/PNG/WebP 文件头，最大5MB；拒绝 SVG 和任意外部 URL。复用平台 `fileService` 命令，巡更只登记受控附件 ID。引用须通过资产 read、同项目/应用和用途校验；执行图片另校验上传者。预览在认证请求内返回限长 data URL，不向前端泄露平台内部地址。
- 文件写入平台后，本地附件登记和资产绑定在同一事务；绑定失败则登记回滚。远端文件无法随业务事务回滚，可能留下未引用文件；平台可能复用文件 ID，因此不在失败补偿中盲目删除。生产上线前需明确平台保留与清理策略。当前文件头检查不等于完整图片解码验证，普通图片上传也不能证明现场拍摄；原生相机及采集证据待 APP 对接。
- JSON 列通过 EasyORM 公开元数据扩展复用 Spring 的标准 `ObjectMapper`，与 HTTP 的日期键和 record 契约一致。

## 人员与业务边界

巡更人员沿用 SaaS 当前应用的人员管理，使用公开用户 SDK、应用成员关系和 `AssetsHolder` 权限；不依赖园区员工、业主、组织标签或空间项目模块。巡更业务岗位不等于平台授权角色。SaaS 技术租户不等于园区业务项目：平台创建的应用允许没有租户，内部范围键保留可空租户部分与应用 ID；应用 Scope 始终必需。有租户的定时计划恢复原上下文，无租户应用不伪造租户。

## API

统一使用宿主认证、响应包装及 QueryParam/PagerResult，不另建旧式返回包装。下表是服务内部路径；经过网关时的外部前缀由平台部署决定。

| 操作 | 路径 | 功能权限 |
| --- | --- | --- |
| 配置分页/详情 | `POST /park/patrol/{resource}/_query`、`GET /park/patrol/{resource}/{id}` | 对应 `patrol-category/point/standard/post/roster/plan:query` |
| 新增/完整编辑配置 | `POST /park/patrol/{resource}`、`PUT /park/patrol/{resource}/{id}` | 对应 `:save`，编辑另校验资产 save |
| 可用人员分页 | `POST /park/patrol/persons/_query` | `patrol-post:query` |
| 任务分页/详情 | `/park/patrol/tasks/**` 只读查询 | `patrol-task:query` + 计划资产 read |
| 任务预览 | `POST /park/patrol/tasks/_preview` | `patrol-task:query` + 计划资产 read |
| 月排班模板/导入 | `GET /park/patrol/rosters/month/template?month=YYYY-MM`、`POST /park/patrol/rosters/month/import?month=YYYY-MM`，multipart 字段 `file` | `patrol-roster:query/save`；导入另校验既有排班资产 save |
| 手动生成 | `POST /park/patrol/tasks/_generate` | `patrol-task:generate` + 计划资产 save |
| 图片上传 | `POST /park/patrol/attachments/{example\|evidence}`，multipart 字段 `file` | `patrol-attachment:save` + 可信项目/应用上下文，成功后绑定附件资产 |
| 图片预览 | `GET /park/patrol/attachments/{id}/preview` | `patrol-attachment:query` + 附件资产 read |
| 执行结果提交 | `POST /park/patrol/executions` | `patrol-execution:submit` + 计划资产 read + 业务资格 |
| 执行记录分页/详情 | `/park/patrol/executions/**` 只读查询 | `patrol-execution:query` + 计划资产 read |
| 完成统计 | `POST /park/patrol/reports/summary` | `patrol-report:query` + 计划资产范围 |

`resource` 为 `categories`、`points`、`standards`、`posts`、`rosters`、`plans`。预览/生成请求为 `{ "planId": "…", "date": "YYYY-MM-DD" }`。提交请求为 `{ "taskId": "…", "requestId": "…", "answers": [{"itemId":"…","value":"…","attachmentId":"…"}], "remark": "…" }`，图像题不填 `value`，通过 `attachmentId` 引用本人上传的 `evidence` 图片；例图通过检查项 `exampleFileId` 引用 `example` 图片。不可用例图替代执行证据。不接受客户端执行用户或采集时间来绕过资格。

统计接收 `{ "from": "YYYY-MM-DD", "to": "YYYY-MM-DD" }`，含首尾最多31天。`expected` 为应巡次数之和，`completed` 为已接受执行次数之和，`completeWindows` 为达标时段数，`overdueMissing` 为已结束时段未完成次数。完成率不等于检查合格率，申请/审批豁免不混入正常执行事实。尚未生成的任务不在统计分母中。

## 构建与测试

以下命令从仓库根目录执行。需要 JDK17、Maven 及目标 SaaS 对应的 JetLinks `2.20.0-SNAPSHOT` 制品和仓库访问配置。不要仅凭版本字符串判断 SNAPSHOT 内容完全一致。

```bash
mvn -B -ntp -pl :property-patrol-service -am test
mvn -B -ntp -pl :property-patrol-service -am verify
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

本批新增 `patrolAttachment` 资产和 `patrol-attachment:query/save` 权限。已有环境需要增量更新标准/任务/记录菜单、标准保存按钮和 CloudPatrol 模板资产范围，并刷新订阅及应用授权；只重启巡更不会自动更新持久化菜单/模板。前后端制品需要一起更新，附件还要求平台公共文件命令服务可达，不能以模块构建通过替代实际上传/预览联调。

PC 模板不默认授予执行提交权限；未来执行端需要独立装配该权限。没有权限的用户不能通过直接调用提交接口替代授权。后台自动任务生成也需要明确服务身份、授权与运维策略，不能靠绕过 AssetsHolder 的定时扫描代替。
