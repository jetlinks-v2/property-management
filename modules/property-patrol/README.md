# 智能巡更后端

本仓库是巡更后端的唯一开发入口。巡更是独立的 JetLinks SaaS 业务服务，装配自己的 manager/core，并通过 property-base-core 公开只读命令读取组织和有效员工，不装配 base-manager、不调用财务业务，不在门禁服务内实现巡更，不复制 SaaS 的登录、用户、组织或授权角色。面向 PC 管理端和后续执行端，业务时间统一为 `Asia/Shanghai`。

本人巡更资格校验 SaaS 已认证身份中的当前业务应用成员维度，入口 Scope 不是成员凭据；配置其他人员使用有效员工、SaaS当前应用成员及 user 资产范围的交集。普通巡更人员无需因此取得系统用户查询权限。任务执行仍受计划资产、业务岗位和排班约束。无真实时段的生成封印保留防重，但不进入 PC 任务组列表、计数、详情或导出。

## 模块与能力入口

| 模块 | 职责 |
| --- | --- |
| `property-patrol-core` | 无 I/O 的规则展开、跨天冲突检查、执行资格策略 |
| `property-patrol-manager` | 配置资产、计划、岗位、排班、任务与执行事务、检查判定、受控附件、人员 SDK 适配、查询与统计 |
| `services/property-patrol-service` | SaaS 微服务启动、认证与发现、PostgreSQL/R2DBC、Redis |

PC 模块位于 SaaS 的 `runtime-ui/modules/cloud-patrol-ui`，前端仍独立维护。配置包括分类、点位、检查标准、业务岗位、人员排班和巡更计划；PC 沿原菜单组织，岗位成员维护、单点/批量岗位及规则指派、月模板下载与导入统一从点位管理进入；内部计划和时段任务不设独立菜单。记录按任务组分页，详情查看真实时段，统计保留原有三类。在线执行接口保留给后续执行端，PC 不冒充移动打卡终端。

**这不是旧系统全量迁移版本。** 点位批量导入导出、PC二维码图片和打印已有；不包含小程序页面、扫码真机与APP硬件识别验收、集团模板、离线包/补传、外部流程审批、预警通知、人员评价、集团综合报表、旧API兼容及历史数据迁移。PC/H5 NFC与定位暂缓。数据库测试与独立打包也不代表已经通过真实SaaS联调或生产验收。

## 数据与业务契约

- 各类配置是独立资产，使用 `AssetsHolder` 维护项目与应用范围。任务、执行记录和统计关联 `patrolPlan`，不能通过自传项目 ID 扩大查询范围。
- 新增 ID、归属和审计由服务端维护；创建/修改姓名快照取自可信SaaS身份，覆盖配置入口、导入和岗位规则操作，编辑保留原创建人。历史缺失不按当前人员姓名回填，客户端不能改写姓名快照。新增配置与资产绑定在同一事务；编辑不能移动归属。配置不开放物理删除；等级、标准、预警另有逻辑删除，停用不是删除。删除项从列表/分页/详情/候选/导出排除，底层及历史快照保留；引用中的等级/标准（含停用岗位关系）不可删除，引用和删除共用行锁。
- `scopeKey` 是项目和应用组成的业务唯一性边界，不是数据权限过滤的替代物。点位编码在此范围内唯一。分类不能循环，计划的点位、岗位和标准必须同范围且可用。
- 岗位是巡更业务分组，不是应用管理员等授权角色。人员只通过认证服务公开 SDK 读取，必须同时为启用账号、当前应用成员并通过 `user` 资产权限；园区业务项目成员关系尚未接入，不以技术租户代替。
- `pt_plan` 先承载点位和岗位的指派，可暂不配置标准与规则；未配置不生成任务。规则配置只选择等级与标准，不要求人工名称、生效期限或排班策略；新配置按星期和高级日期决定巡更日期，历史期限在仅调整岗位时保留，重新配置规则时取消人工期限。**同岗位的任务完成次数共享，不按岗位内每个人各生成一份任务**；不同岗位使用独立计划。
- 指定岗位保留仍选岗位的规则，移除只停用关系，重新选择复用计划ID及全部历史。批量规则要求所有点位岗位相同，按第一点位配置覆盖。每次最多100点位、20岗位；点位与已有计划分别校验资产read/save，引用read由统一校验器负责；事务内CAS点位版本、批量保存和绑定，失败整体回滚。
- 日期覆盖优先于星期规则，空覆盖表示当日休巡。时段左闭右开，结束分钟小于开始分钟表示跨天，开始等于结束表示从开始起24小时，结束 `1440` 表示当日24点。每个配置时段保留一条任务和完整起止，应巡次数为 `floor(时段分钟数/频率分钟数) × times`，不足一周期仍保留应巡0次的时段；每时段独立配置冷却，录入单位支持分钟/小时/天。跨天任务归开始的逻辑日期。
- 排班也归班次开始日，单班不超过24小时。`optional` 表示同应用当日完全没有排班时放行排班条件；已有排班则必须命中本人、本岗位和时窗。`required` 始终要求有效排班。岗位成员资格、任务时窗和冷却校验不会因此放宽。
- 任务每天上海时间 00:01 自动生成，按持久化计划归属恢复租户；HTTP 手动生成仍要求计划资产 save。多节点使用同一数据库封印防重，失败按计划记录日志。
- 月排班使用岗位/人员 ID，前四行表头、第五行示例、第六行起填写；班次表明确起止时间，结束小于开始表示跨天，休息不计在班。最多1000行、5MB、100个班次，不直接接受旧手机号模板。整月替换先验证岗位成员和既有排班资产 save，再在事务内停用旧排班、插入和绑定新排班；失败整批回滚，并发版本防止交错覆盖。
- 日历按原系统的任务结束日展示，任务组使用规则归属日，二者口径独立。任务分类沿生成时的名称快照展示及导出，不回查当前分类名称。日详情及导出的手机号仅对具有 `user:query` 且能读取对应用户资产的操作者补充，并限定当前应用成员；无权限或未录号码时留空，不扩大巡更人员权限，不把联系方式保存到排班历史。
- 首次生成某计划某日的封印及全部任务在同一事务内写入；即使该日无任务，也保留封印。并发依靠数据库唯一键防重，规则/标准编辑不会覆盖该日任务快照，也不会因为规则版本变化重复应巡。
- 任务保存点位/岗位名称、分类ID与名称、等级ID与名称、时窗、次数、冷却、排班策略、检查项等快照。执行资格复核当前计划/点位/岗位启用状态、实时岗位成员、当前账号/应用成员和排班。历史记录按快照解释。
- `pt_execution.collectedTime`和`networkStatus`保存客户端首次提交时的采集时间（毫秒）及连接状态（`online/offline`），允许已发布客户端省略，历史缺失不按接收时间回填。这些信息只用于追溯，在线资格、冷却与计次仍由服务器时间决定；不构成离线补传接口。
- 提交使用服务端当前用户；`requestId` 在同一执行用户下必须唯一。同请求重放不重复计数，不同内容（含采集时间/网络状态）复用同编号拒绝。任务计数 CAS 和执行记录原子提交，失败整体回滚。
- 检查项内容最多200字，支持 `number/text/choice/image`，缺少类型的已部署标准按 `choice` 读取。数值上下限包含边界，最多18位整数和6位小数，通过 JSON 字符串传输防止浏览器丢失精度；超限结果保存为不合格，不阻止有效执行计数。新建选项检查项在PC编辑器默认将首个“正常”选项显式保存为合格值；已持久化标准不补默认。选项只有显式配置 `qualifiedOptions` 才判定合格与否，文本参考、图像及无判定规则的项目为 `not_evaluated`，不把上传成功当成检查合格。
- 整体结果：任一检查项不合格即 `fail`；全部合格才为 `pass`；其余（包括可选未填）为 `not_evaluated`。旧记录没有判定时显示未判定，不按当前标准回算。执行记录保存题目、规则、答案及逐题判定快照。
- 图片每题最多一张，例图与执行图片用途隔离。上传只接受 JPEG/PNG/WebP 文件头，最大5MB；拒绝 SVG 和任意外部 URL。复用平台 `fileService` 命令，巡更只登记受控附件 ID。引用须通过资产 read、同项目/应用和用途校验；执行图片另校验上传者。预览在认证请求内返回限长 data URL，不向前端泄露平台内部地址。
- 文件写入平台后，本地附件登记和资产绑定在同一事务；绑定失败则登记回滚。远端文件无法随业务事务回滚，可能留下未引用文件；平台可能复用文件 ID，因此不在失败补偿中盲目删除。生产上线前需明确平台保留与清理策略。当前文件头检查不等于完整图片解码验证，普通图片上传也不能证明现场拍摄；原生相机及采集证据待 APP 对接。
- JSON 列通过 EasyORM 公开元数据扩展复用 Spring 的标准 `ObjectMapper`，与 HTTP 的日期键和 record 契约一致。

- PC 日任务组按首时段的分类/等级生成快照筛选，先分页再批量读取本页时段汇总进度；历史没有分类名称快照时留空，不以当前配置冒充历史。执行记录通过真实 taskId 展示原时段起止、分类和等级，不改变打卡事实。
- 三类统计的周期、分组行、全范围总计和XLSX导出共用资产授权查询；总计点位去重，覆盖率按总有效次数/总应巡次数计算，不平均分组百分比。未完成次数包含未截止任务，不一律标成漏检。人员页保留岗位口径，不将整岗次数复制给各成员。
- PC/H5 的 NFC、定位、点位坐标与打卡范围整链暂缓；不因此暂缓图片、二维码、记录或本系统漏检业务。

等级修改记录只从启用此能力后的真实操作追加，保存可信操作人姓名和名称/状态/说明/规则前后快照，写入与配置同一事务；不回填虚构历史。标准与标签不额外开放该历史入口。配置导出规则/检查项使用独立明细表及流式写入，不扩展未经授权的图片、人员或集团信息。删除按钮在模板中默认不授予，既有环境只增量同步菜单并按职责授权。

## 点位分类关系

一个物理点位可关联多个分类，岗位和规则按“点位＋分类＋岗位”独立配置。`pt_point_category` 保存当前关系及取消后的停用行；`pt_point.categoryId` 保留初始分类，仅在尚无关系行时兼容历史数据。首次调整分类时在点位锁内迁移旧关系和计划分类，保留原计划ID；`categoryIds` 为服务端只读投影，不接受客户端覆盖。

`POST /park/patrol/point-categories/link`、`/cancel` 接收 `{pointIds:[...],categoryId}`，最多100点位。操作使用点位save、引用分类read及受影响计划save权限，当前应用校验和整批事务同时生效。取消仅停用所选分类下的计划，保留物理点位、任务封印、执行及漏检历史；重新关联不自动启用旧计划。岗位编辑与分类取消通过点位锁及版本比较防止交错覆盖。

分类侧栏、岗位规则编辑、任务生成和点位导出使用当前关系。多分类点位配置岗位时必须选择分类；全部分类概览汇总可读的有效计划。按分类导出只显示该分类及其岗位，概览导出全部当前分类；全部取消后保留物理点位供重新关联，不以初始分类回填。

## 人员与业务边界

巡更人员取物业基础数据中的有效员工，并与 SaaS 当前应用成员和 user 资产范围相交。应用成员关系在认证服务解析，员工身份及直接组织在基础服务查询；不复制主数据。纯客户/业主不作为执行人员，双身份按 employee 状态判断。巡更业务岗位不等于平台授权角色。SaaS 技术租户不等于园区业务项目：平台创建的应用允许没有租户，内部范围键保留可空租户部分与应用 ID；应用 Scope 始终必需。有租户的定时计划恢复原上下文，无租户应用不伪造租户。

## 业务项目与执行边界

- `GET /park/patrol/projects/_tree` 读取基础服务已过滤的组织树，不重算组织及下级权限、不补查不可见祖先。非 project 节点保留层级，置灰禁选；无项目不是全库权限。
- 可选 `X-Patrol-Project` 只能选择树内 project，收窄当前请求；省略表示全部有权项目。它不替代应用 Scope、功能或资产权限，应用ID、宿主项目编码、租户ID都不等于业务项目ID。
- 点位/岗位/计划/排班归项目；计划从点位、排班从岗位继承，跨项目引用拒绝。共享配置可显式指定 ownerOrganizationId，仅可被该组织可见路径下的项目使用；编辑不改变原归属。
- 现有资产授权与项目范围相交，在分页、汇总前过滤；新配置沿用平台自动应用资产绑定，组织范围直接由已授权基础树与业务归属相交，不新增组织资产授权。管理范围不授予执行资格：必须同时满足有效员工、当前应用成员、岗位、排班、时窗、次数和间隔。普通执行人员无需员工目录维护权限。
- 任务组、时段和执行记录保存项目快照，不改变任务组ID、日期、时段或计次。已有无归属数据不随机分配；在项目工具条“历史数据归属”中审查后处理，通常先分类/标准/等级，再点位/岗位，最后计划/排班。自动生成跳过无项目旧计划。
- `POST /park/patrol/project-migrations/_query` 输入 `{type,query}`，只查当前应用、原资产授权下未归属配置；`POST .../assign` 输入 `{type,ids,projectId}`，每次1–100条，要求对应 query/save 功能权限、原资产save和目标项目可见。只允许空归属变为确定项目，CAS更新与事务保护；计划历史只补缺失项目字段，不改计数与打卡事实。
- 标准共享例图上传可带 `X-Patrol-Owner`，必须为已授权树内组织；普通执行证据必须归项目。

项目名称保留生成时快照。项目标注撤销、组织停用或权限撤回后，下次请求不再访问其数据；恢复授权后可重新访问。基础组织生命周期目前不反查巡更引用，后续如需删除阻断应通过公开契约协调，不能跨表耦合。

## API

统一使用宿主认证、响应包装及 QueryParam/PagerResult，不另建旧式返回包装。下表是服务内部路径；经过网关时的外部前缀由平台部署决定。

| 操作 | 路径 | 功能权限 |
| --- | --- | --- |
| 配置分页/详情 | `POST /park/patrol/{resource}/_query`、`GET /park/patrol/{resource}/{id}` | 对应 `patrol-category/point/standard/post/roster/plan:query` |
| 新增/完整编辑配置 | `POST /park/patrol/{resource}`、`PUT /park/patrol/{resource}/{id}` | 对应 `:save`，编辑另校验资产 save |
| 等级修改记录 | `POST /park/patrol/levels/{id}/history/_query` | `patrol-level:query` + 等级资产read + 当前应用，最大50条/页 |
| 配置XLSX导出 | `POST /park/patrol/{levels\|standards\|tags}/_export` | 对应query + 当前应用及资产read；10000配置/100000明细上限 |
| 配置逻辑删除 | `DELETE /park/patrol/{levels\|standards\|warnings}/{id}` | 对应delete + 配置资产delete + 当前应用，不级联历史 |
| 点位岗位回填 | `POST /park/patrol/point-assignments/_detail`，`{pointIds:[...],categoryId}`；概览使用 `/_summary` | `patrol-plan:query` + 点位及有效计划资产read |
| 点位导入模板 | `GET /park/patrol/points/workbook/template` | `patrol-point:query`，仅含有读取权限的当前应用分类参考 |
| 点位导入预检/提交 | `POST /park/patrol/points/workbook/preview`、`/import`，multipart字段`file` | `patrol-point:save`；重新检查分类read/当前应用；新增资产绑定与插入同一事务 |
| 点位导出 | `POST /park/patrol/points/workbook/export?categoryId=...`，QueryParam；分类参数可省略 | `patrol-point:query` + 点位资产read；名称引用独立read；全筛选最多10000点位 |
| 批量指定岗位 | `POST /park/patrol/point-assignments/posts`，`{pointIds:[...],categoryId,postIds:[...]}` | `patrol-plan:save` + 点位及已有计划资产save |
| 批量配置规则 | `POST /park/patrol/point-assignments/rules`，`{pointIds:[...],categoryId,rules:[{postId,levelId,standardId}]}` | `patrol-plan:save` + 点位及已有计划资产save |
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

`resource` 为 `categories`、`points`、`standards`、`posts`、`rosters`、`plans`。预览/生成请求为 `{ "planId": "…", "date": "YYYY-MM-DD" }`。提交请求为 `{ "taskId": "…", "requestId": "…", "answers": [{"itemId":"…","value":"…","attachmentId":"…"}], "remark": "…", "collectedTime": 1793751000000, "networkStatus": "online" }`，采集字段可省略，图像题不填 `value`，通过 `attachmentId` 引用本人上传的 `evidence` 图片；例图通过检查项 `exampleFileId` 引用 `example` 图片。不可用例图替代执行证据。不接受客户端执行用户或采集时间来绕过资格。

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

三类统计补充接口：`POST /park/patrol/statistics/{points|people}/report` 返回周期、分组与总计；`POST /park/patrol/statistics/{points|people}/export?qualification=false` 导出XLSX，`qualification=true`导出时段完成合格率。均沿用 `patrol-report:query` 与计划资产 read，原列表接口保留给已部署调用。

项目与员工接入本地验证：已覆盖真实应用 Scope 新增、项目内查询、兄弟项目列表/详情拒绝、普通编辑禁止改归属，以及共享配置编辑保留组织；有效员工执行资格的正向完整联调仍需已标注员工的测试账号。本地接入使用独立基础服务的公开只读命令；远端上线需同步发布基础服务的新员工契约，不能仅更新巡更 JAR。历史无归属数据仍须显式审查分配，不自动迁移。

## 组织资产权限兼容

巡更独立服务的 `PatrolAssetsConfiguration` 通过原 Bean 名替换组织及上下级权限提供者。
当前公共组件在账号具有多个应用维度但没有有效组织维度时，会生成目标为空的资产查询条件。
`EmptySafeOrgIncludeParentAssetsAccessSupport` 只对空组织的查询分支使用恒假条件，
保留其他租户、应用和创建人授权分支；有组织及写入操作继续使用公共组件行为。
该配置属于巡更启动服务，不要求将基础 manager 打入巡更，也不修改公共组件仓库。
公共组件修复后可以移除此兼容实现；回归须覆盖零个及多个非组织维度、有效组织和同名 Bean 替换。
