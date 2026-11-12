# 基础模块

前后端开发、复制接入及公共能力复用见[系统管理模块前后端开发与复用规范](系统管理模块前后端开发与复用规范.md)。

首次部署需在后端建表后执行[默认字典 SQL](sql/init-property-dictionaries.sql)，包含当前 4 类、17 个选项。具体执行方式见[字典初始化章节](系统管理模块前后端开发与复用规范.md#14-部署时初始化默认字典)。

基础服务与报修服务的 JVM 时区应与业务时区一致；中国本地部署可设置容器 `TZ=Asia/Shanghai` 或 `JAVA_OPTS=-Duser.timezone=Asia/Shanghai`。Excel 日期按 JVM 时区解析，仅设置 Jackson 的 `time-zone` 不会改变 Excel 日期含义。

`property-base-manager` 承载本领域 Controller、Service、Entity、Repository、权限及国际化，由独立的 `property-base-service` 装配。从仓库根目录执行 `mvn -pl :property-base-service -am verify`，只构建基础系统及其依赖。

`property-base-core` 提供公共 DTO、组织类型枚举、组织树及基础目录查询命令，不依赖 manager，不包含数据库实体或 CRUD。基础服务必须独立运行；报修、巡更等业务服务按需运行，读取基础数据时只依赖 core 并执行公开命令，不装配本 manager。各应用统一加载前端公共模块 `property-base-ui`，由该模块唯一提供组织、用户、空间、客户和字典菜单及页面，经网关调用同一套 `/property/**`。收费、巡更和装修不再维护基础页面副本。已初始化的旧业务基础菜单需按应用清理，并给原角色授权公共 `charging/*` 菜单，不能全量重建其他系统菜单。

## 客户房屋关系、房屋类型与项目章

既有 `propertyService:customer` 的 `QueryPropertyCustomersCommand` 保留 `projectId`、可选 `spaceId` 及 `PropertyCustomerInfo` 契约。目录按客户合并当前有权房屋 ID，一客多房仍只返回一条客户；基础管理客户列表按空间记录分页，不改变此公开命令的返回口径。已结束、已删除和无权关系不进入目录。

客户找房屋使用 `GET /property/customer/{id}/houses?projectId=...`，房屋找客户使用 `GET /property/house/{spaceId}/customers?projectId=...`。两者与 `propertyService:household` 的 `QueryPropertyHouseholdsCommand` 共用查询，保留认证、项目和空间资产权限，仅返回有效客户、完善的房屋及未结束、未删除的关系。`mine=true` 仅匹配认证本人绑定的客户，不接受客户端声明用户身份。

`propertyService:dictionary` 的 `QueryPropertyDictionaryItemsCommand` 按 `typeCode` 返回启用分类下的启用选项。房屋类型使用已有 `house-type` 字典，不另建或重复初始化。

组织标注为 `project` 后，可以通过 `PUT /property/organization/{id}/project-seal?version=...` 上传 multipart `file`；同路径 GET 私有读取、DELETE 带版本移除。上传和移除需要 `property-organization:seal` 动作及组织资产 save 权限，读取需要 query 动作及组织资产 read 权限；仅接受真实 PNG/JPEG，最大 2MB、4096×4096 像素。改为其他组织类型前必须移除章，版本冲突拒绝覆盖。图片存于公共文件服务，组织仅保存文件引用。

基础服务须配置 `property.files.server-id`，或环境变量 `PROPERTY_FILES_SERVER_ID`，指向现有公共文件节点；未配置时明确拒绝上传，不回退本机文件。装修通过组织命令服务的 `GetPropertyProjectSealCommand` 按项目读取，保留当前认证和资产上下文，不跨库、不接受客户端章 URL。

GET `/property/space/_tree?projectId=...&customerBound=true` 仅返回当前有效客户所在空间及授权祖先，`customerBound` 标明节点本身是否有有效客户。已结束/删除关系及已删除客户不作为候选；上级仅用于展示和范围查询，不能因裁剪后无下级而推断为可收费空间。普通空间树默认返回全部授权空间。公开 `QueryPropertySpaceNodesCommand` 同样支持 `customerBound`，保留原始 `leaf`；`QueryPropertyHousesCommand` 设 `customerBound=true` 时，在分页前筛选有客户且资料已完善的空间。两个命令仍执行调用者的项目和空间资产权限。

## 房屋汇总公开命令

房屋汇总公开命令 `QueryPropertyHouseSummaryCommand` 使用 `propertyService:house`，输入 `projectIds`（1～100个），返回 `Mono<PropertyHouseSummary>`。按调用者项目及空间 read 权限统计未删除的真末级空间；未完善的末级空间仍计入总数和待完善数，面积仅取已完善资料。房态按 `house-state` 字典顺序展示，启用项含零值，有引用的停用项保留，未标注房态单独计数。多项目去重，任一项目非法或无权则整请求失败；不触发同步，不允许覆盖调用者身份。

## 员工公开命令

服务标识 `propertyService:employee`，仅只读，不触发同步或改变员工身份。

| 命令 | 输入 / 输出 | 权限与空结果 |
| --- | --- | --- |
| `QueryPropertyEmployeePageCommand` | `query: QueryParamEntity` → `PagerResult<PropertyEmployeeInfo>`；每页1–200 | 认证及 user 资产范围（基础服务通过 AssetsHolder 生成原 user 资产条件，交给 SaaS 用户命令执行，再与基础有效员工相交）；先过滤未删除、启用、employee=true，再分页。应用成员关系由消费方在认证服务中解析并以 ID 相交，不能把认证库的 in-dimension 条件发送到基础库 |
| `GetCurrentPropertyEmployeeCommand` | 无输入 → 本人 `PropertyEmployeeInfo` | 从可信认证身份读取，不要求用户维护菜单或目录授权；非员工、停用或已删除返回空；匿名和服务错误传播 |

DTO 仅含 id、name、telephone、直接 organizationIds；员工 ID 与 SaaS user ID 一致，不包含密码、客户资料或内部审计字段。query 通过 AbstractCommand 属性序列化；调用方不能把空结果当有效执行身份，也不能降级为任意 SaaS 用户。员工与客户双身份按 employee 状态判断，纯客户不进入巡更岗位候选。

## 组织树公开命令

| 项目 | 契约 |
| --- | --- |
| 服务 ID | `propertyService:organization`，由 `@CommandService` 注册 |
| 命令类 | `QueryPropertyOrganizationTreeCommand`，位于 core 的 `command` 包 |
| 命令 ID | `QueryPropertyOrganizationTree`，按 JetLinks 命令类名规则生成 |
| 输入 | 无参数；不接收调用者指定的用户、项目、租户或查询条件 |
| 返回 | `Flux<PropertyOrganizationInfo>`，每个元素为一个根节点，`children` 为下级节点 |
| 节点字段 | `id`、`name`、`code`、`parentId`、`sortIndex`、`organizationType`、`projectSealFileId`、`children` |
| 组织类型 | `OrganizationType.project` 为项目，`external` 为外协单位，`null` 为未标注 |
| 范围 | 当前认证用户有权读取、未逻辑删除的完整组织树，保留未标注的公司等上级层级 |

完整树不按类型裁剪，也不继承父节点标注。不可见的父级不会被补查，其可见子级作为根节点返回；`parentId` 仍保留 SaaS 原关联。叶节点 `children` 为空列表。

命令只读物业本地镜像及当前标注，不触发同步，不暴露实体的版本、审计或删除字段。同步删除、恢复和类型标注后，下次查询直接读取最新数据。真实无数据返回空流，匿名及查询失败按原异常传播，不用空列表掩盖失败。

消费模块添加依赖：

```xml
<dependency>
    <groupId>org.jetlinks.pro</groupId>
    <artifactId>property-base-core</artifactId>
    <version>${project.version}</version>
</dependency>
```

在自己的业务接口完成菜单权限校验后，通过框架执行显式命令：

```java
return CommandSupportManagerProviders
    .getCommandSupport(QueryPropertyOrganizationTreeCommand.SERVICE_ID)
    .switchIfEmpty(Mono.error(new IllegalStateException("物业组织命令服务未注册")))
    .flatMapMany(support -> support.execute(new QueryPropertyOrganizationTreeCommand()));
```

上述类分别来自 `org.jetlinks.pro.command.CommandSupportManagerProviders`、`org.jetlinks.pro.property.base.command.QueryPropertyOrganizationTreeCommand` 和 `reactor.core.publisher.Mono`。服务未注册属于部署错误，调用方应明确反馈，不能当成无项目。需要列表时在末尾调用 `collectList()`，保持响应式链路，不调用 `block()`。

调用必须保留当前 `Authentication` / `AssetsHolder` Reactor 上下文；提供方复用 HTTP 组织树查询的资产过滤。消费方使用自己的业务菜单权限，无需授予组织类型维护菜单。内部任务若需要其他授权范围，须单独明确契约，不得通过清空上下文扩大用户查询范围。


## 项目章

项目章只在基础数据管理的组织架构中维护：`property-base-ui/views/charging/OrganizationType/ProjectSeal.vue` 位于关联空间之后，仅项目类型显示，支持预览、替换、确认移除，不要求先关联空间。装修仅通过公开 `GetPropertyProjectSealCommand` 读取当前有权项目的真实章内容，不另外提供维护入口、不跨库、不接受客户端章 URL。

前端由 `api/organization.ts` 和 `hooks/useProjectSeal.ts` 调用 `PUT /{id}/project-seal?version=...`，一次请求完成基础服务的图片校验、公共文件上传和组织引用保存；移除调用同路径 DELETE。上传和移除统一使用已有 `seal` 动作，与类型标注 `save` 独立；同时保留 organization 资产写权限。限制统一为 PNG/JPEG、2MB、4096×4096 像素，版本冲突或结果不确定时刷新，不自动重试。

`PropertyProjectSealStorage` 复用现有公共文件命令，并通过 `property.files.server-id` 显式选定节点；不需要向 SaaS 平台装配物业适配器，不修改平台代码、菜单、订阅或授权数据。未配置或节点不支持时明确失败，不回退本机。前后端更新后仍须具备既有项目章按钮权限才能实际维护，不能把构建通过当作授权或文件节点联调通过。

兼容保留 `PUT /{id}/seal` 引用接口（显式 `fileId`、`version`；`fileId:null` 清除）。它仍要求文件资产 read 权限并依赖基础服务本地 FileManager 的可信元数据，原 5MB 元数据限制不变；不能用它绑定仅存在于其他服务数据库中的文件。维护页面不再走这个分步链路，也不以放松校验或跨库读取来兼容外部文件 ID。业务读取的真实图片仍受 2MB 限制。

项目章保存在物业组织镜像自有字段，SaaS 同步不覆盖。组织树 HTTP 与公开 `QueryPropertyOrganizationTreeCommand` 返回文件 ID，命令仅对项目输出该字段；其他业务通过 core 命令读取，不直接依赖 manager 或查表。更换和移除仅改引用，不删除历史章文件；上传成功但组织保存失败时仅尝试回收本次新上传文件。出具单据的业务模块负责保存当时的章内容或引用快照。

验证：前端 `tests/project-seal.test.mjs`；后端 `PropertyOrganizationControllerTest` 检查专用动作，`ProjectSealPersistenceTest` 验证真实组织持久化、图片校验、资产权限、版本冲突及替换/移除生命周期。

## 用户镜像与物业身份

用户管理实现位于 manager 的 `service/*User*`、`entity/PropertyUser*`、`entity/UserSyncStateEntity`、`web/PropertyUserController`，设计以相邻 `../../../设计文档/用户管理设计.md` 为准。SaaS 账号资料只读，物业身份可以同时为客户、员工，也可清空；身份不等于 SaaS 角色或房屋关系。客户身份保留原 `owner` 编码及数据库列，房屋关系中的“业主”性质含义不变。

`/property/user` 提供 `_query`、`_counts`、`{id}`、`_identities`、`{id}/identities`、`_sync`、`_sync/status`，资源 `property-user`，动作 `query/save/sync`。分页及计数先通过平台 `AssetsHolder.injectQueryParam` 生成原 `user/read` 资产条件，按200个候选 ID/批调用既有 `authService:user` 标准分页命令，在 SaaS 原用户及资产绑定库求值后与 `pm_user` 筛选取交集；调用角色需具备 `user:query` 只读动作，缺失按403传播，不能当空目录。不在镜像库执行或查询 Cloud 用户的权限表；保留调用者认证、租户和应用 Scope，权限故障明确传播。详情与标注分别校验 read/save。标注提交完整集合和 version，冲突拒绝覆盖；没有通用账号保存或删除接口。

全量同步通过 `authService:user` 显式 SDK 命令执行，分页暂存、校验和缺失 ID 复核后统一事务落库，保留本地身份、版本和标注审计；异常空来源、分页变化、重复 ID 或复核失败均不提交。SaaS 删除仅逻辑删除，同 ID 恢复保留身份。

部署多个用户命令提供方时，可配置 `property.user-sync.server-id`（环境变量 `PROPERTY_USER_SYNC_SERVER_ID`）固定运行端认证节点。全量快照、缺失用户复核及权限目录读取共用该节点；不配置时沿用集群默认路由。部署后核对来源，不能把另一套认证库的少量用户快照当作原来源的全量结果；指定节点不可用时应报错，不切换来源继续删除。

用户及关系表 `pm_user`、`pm_user_org` 和租约表 `pm_user_sync_state` 由 EasyORM 管理。配置统一使用 `application-local.yml` 中的 `property.user-sync`：`enabled` 默认 true、`interval-seconds` 默认 3600（1 小时）、`max-users` 默认 100000、`timeout-seconds` 默认 120、`page-timeout-seconds` 默认 60。分页快照读取使用整轮超时预算，单页等待同时受单页和整轮预算约束；批量迁入账号时应结合实测耗时配置整轮预算。关闭 enabled 同时关闭 worker 及手动任务入口。首次在应用就绪 5 秒后执行；手动同步返回 taskId，客户端轮询状态，不能把受理当完成。

运行观测 MBean 为 `org.jetlinks:type=PropertyManagement,name=UserSync`；最近任务及失败原因通过 `_sync/status` 查询，失败保留上次成功时间。基础管理前端通过公开 HTTP 接口访问；其他物业后端不依赖本系统的内部服务和数据库，通过 core 中公开命令读取所需基础能力。

## 空间与房屋档案

空间结构通过固定命令服务 `spaceService:space` 分页全量读取，最多 20000 个节点；不依赖 SaaS manager，不读取空间类型决定业务层级。`pm_space` 保存来源镜像，`pm_space_binding` 保存根空间与组织的关联，`pm_house` 保存物业房屋扩展。源节点名称和层级变化不覆盖本地面积、字典值或客户资料。

空间类型名称通过共享运行服务的只读命令 `spaceService:space-type` 解析；部署时必须包含查询类及其自动配置注册。空间页或类型页任意失败均拒绝整轮快照。

报修通过 `propertyService:directory` 消费本模块公开的 `QueryPropertyProjectsCommand`、`QueryPropertyUsersCommand` 和 `QueryPropertySpacesCommand`，只返回当前租户及资产权限交集内的项目、用户身份和绑定空间。消费方仅依赖 `property-base-core`；参数通过命令容器传递，项目范围和员工筛选跨 RPC 保持一致。空目录正常返回，缺少身份、权限或服务异常明确拒绝，不触发同步或查询旧报修目录。

用户目录也支持仅选择 `business_application` Scope 的应用入口，不强制额外选择租户。基础服务通过平台 `AssetsHolder` 校验应用 Scope 及原用户资产权限，不将认证库的人员组织权限条件拼入本地镜像查询；同时选择租户时继续取租户交集，无租户和应用范围时拒绝。收银员姓名查询沿用该命令，不额外要求用户管理菜单权限。

项目目录在基础服务查询 `pm_organization`，使用 AssetsHolder 批量资产过滤并与可信租户取交集；不能把依赖 Cloud 的 `s_organization` 的资产条件拼进基础镜像库或报修库。消费方将命令返回的授权项目 ID 与业务查询在分页前取交集；空目录对应空业务结果，命令失败向上传播。

空间资料可在任意授权层级完善，已完善资料的空间才可关联客户；来源 leaf 只描述原始结构，不限制资料维护或客户关联。顶级空间仅可关联项目类型的组织，外协单位及未标注组织不可关联；一个组织最多关联一个根空间，根空间与组织一对一绑定。已有空间绑定时禁止修改或清除组织类型，需先解除绑定；项目选择返回已标注为项目且有权访问的组织。已有房屋资料的根不能直接改归属，避免客户历史随绑定静默迁移。

| 接口前缀 | 主要接口 | 菜单资源 / 动作 |
| --- | --- | --- |
| `/property/space` | GET `_projects`、`_roots`、`_tree?projectId=`；PUT `{rootId}/organization`；POST `_sync`；GET `_sync/status` | `property-space` / `query,save,sync` |
| `/property/house` | POST `_query`、`_import`、`_export`；GET `template.xlsx`；GET/PUT/DELETE `{spaceId}` | `property-house` / `query,save,delete,import,export` |
| `/property/customer` | POST `_query`、`_profiles`、`_history`、`_import`、`_export`；POST 新建；GET/PUT `{id}`；POST `{id}/relationships`；DELETE `relationships/{id}`；GET `template.xlsx` | `property-customer` / `query,save,delete,import,export` |
| `/property/dictionary` | GET/POST `_types`；PUT/DELETE `_types/{id}`；GET/POST `{code}/_items`；PUT/DELETE `items/{id}` | `property-dictionary` / `query,save,delete` |

房屋和客户接口必须显式传 `projectId`，查询可传 `spaceId` 限定子树。项目 `organization` 与节点 `space` 资产均使用 `AssetsHolder`；客户先按有权空间关系缩小范围，再查询、统计和分页。选择客户账号还需该账号的 `user` 资产读取权。

空间同步状态和租约存储在 `pm_space_sync_state`，首次就绪后 5 秒执行，此后默认每 1 小时执行。间隔配置为 `property.space.sync-interval`（默认 `1h`），来源上限为 20000；手动 `_sync` 等待本轮完成。异常、分页残缺、重复 ID、环或意外空来源均保留原镜像。同步链路提供 MonoTracer 和 `org.jetlinks:type=PropertyManagement,name=SpaceSync` MBean。

## 客户、空间与历史

新增保留“新建客户 / 关联已有客户”：新建从已同步、启用且标注客户身份的用户中选择；已有客户从当前项目有权的当前或历史空间记录对应资料中选择。每项目同账号一份客户资料，可以在多个空间使用。个人客户性质按每条空间记录保存，企业不填性质；不再使用家庭。

首次建档时，来源姓名、电话和邮箱有值则回显禁用，缺失允许补充；后端同样以来源非空值初始化。保存后客户资料独立维护，后续同步、再新增和导入均不覆盖。编辑可以修改已保存资料，并带客户版本及当前空间记录 ID、版本、性质一次事务保存。账号和个人/企业类型不可变。

当前列表每条空间客户记录一行，`id` 是客户资料 ID，`recordId` 是当前空间记录 ID，`relationships` 仅包含该条当前记录。`_profiles` 按客户资料去重，最后一条空间记录删除后仍可选择。删除只逻辑删除选中的空间记录，后台自动记录结束时间和删除时间；客户资料、其他空间和历史不删除。再次新增产生新记录和自动开始时间，不恢复原记录。

历史包含已结束及已逻辑删除的空间记录，返回完整客户资料、性质、空间信息和起止时间，只读。旧删除记录缺少结束时间时使用原 `deletedTime`；原时间也不存在则保持缺失，不伪造。旧家庭表和字段保留存量数据，不在运行时使用，不执行 DROP 或物理清理。旧接口 `_families`、手动结束和客户资料删除不再开放；前后端应配套升级，旧 Excel 模板需重新下载。

空间与客户详情返回 `spaceSegments: [{id,name,typeId,typeName}]`，从完整来源父链派生，裁剪子树前生成，不根据路径字符串猜测固定层级。页面用类型作上方标签、节点名称作下方值；表格及编辑顶部使用 `fullName`。所有入口仍受项目、空间资产及版本 CAS 保护，详情只读，编辑从列表进入。

## 字典与批量操作

字典全系统统一，分类编码和同分类选项值保存后不可改，删除后的编码仍占用。分类/选项均支持排序、说明和独立启停；恢复分类不会启用此前单独停用的项。业务分类为 `house-type`、`house-state`、`customer-nature`、`certificate-type`，选项统一维护；初始选项可按需执行 `modules/property-base/sql/init-property-dictionaries.sql` 补齐，不覆盖既有状态。已有值允许保留停用编码，新选值必须同时满足分类和项启用。绑定业务字段的分类、有未删除选项的分类、被当前或历史资料引用的选项不能删除，应使用停用。

列表使用标准 `QueryParamEntity`，支持白名单字段、嵌套条件、排序和分页；未知字段或操作明确拒绝，不静默忽略。导出使用同一权限和筛选，返回全部匹配记录，上限 10000；不能把当前页当完整导出。模板、导入、导出均由后端提供。模板为 `GET /property/{house|customer}/template.xlsx?projectId=`，客户另必传 `customerType=person/company`，复用 import 权限，按项目返回中文填写表、真实空间和字典/用户参考；填写页不预填空间路径，完整空间路径手动填写，空间参考保留项目各层级；客户仅可关联已完善资料的空间；单独提供一条填写示例，示例不参与导入，必填表头标红。空间资料全部已完善仍可下载空填写表。`POST _import` 为 multipart，字段 `file`，仅 xls/xlsx、10MB，建议单次不超过1000条，后端不设置导入条数上限；`POST _export` 接收同列表 QueryParam，返回 XLSX 文件流。前端不解析 Excel 或提交 JSON 行数组。

输出复用 JetLinks `ExcelUtils` 及其 `WorkbookOption`；读取使用配套 POI 保留真实物理行号，只处理第一张数据表，拒绝公式、重复表头、空行和非文本标识。不使用默认导入器的分批写入或失败后逐行重试。文件全部解析、字典转换、字段/权限/现有数据/批内重复及共用资料一致性预检通过后，才订阅一个响应式事务中的全部写入；预检失败零写入，并发持久化失败整批回滚。错误响应含真实 Excel 行号和业务原因；前端仅消息提示，文件接口的 JSON 错误不能保存为 Excel。房屋导入只补尚未完善的空间，不限制层级；客户按姓名和联系电话唯一匹配来源账号，再按稳定 `userId` 复用可管理的主档，不覆盖既有资料。个人、企业各8列，不填写家庭或开始时间；个人填写客户性质，企业无性质列。

## 客户房屋双向查询

`GET /property/customer/{id}/houses?projectId=...` 查询客户当前有效且有权读取的房屋；`GET /property/house/{spaceId}/customers?projectId=...` 查询房屋当前客户及其关系性质。响应为 `PropertyHouseholdInfo` 列表，包含客户、房屋、房屋类型、账号及关系 ID/性质，业主资格由业务按 `nature` 明确判定，不能将所有客户视为业主。排除未开始、已结束、已逻辑删除关系以及已删除客户/房屋，空查询返回空列表。分别要求 `property-customer:query` 与 `property-house:query`；项目与空间均沿用调用者 AssetsHolder 权限。

跨服务调用使用 core 的 `QueryPropertyHouseholdsCommand`，服务 ID 为 `propertyService:household`；`projectId` 必填，`spaceId`、`customerId` 可选，同时指定取交集，`mine=true` 仅匹配认证账号的 `userId`。命令参数进入正式负载，错误原样传播，不跨库或按手机号猜测关系。查询复用平台 HTTP/命令链路追踪，无新增后台任务或常驻状态。

`property-file-support` 提供 Cloud 文件所有者侧的 `AssertPropertyFilesCommand` 实现，需由持有 Cloud 文件元数据的服务装配；品质与报修只依赖 core 公开命令，不跨库查询文件。

空间关联项目时，基础模块通过 `AssetBindManager` 为整棵空间树登记 `org` 归属。解除关联、来源节点删除或跨根移动会撤销旧归属；空间同步会幂等补齐已有关系。空间与房屋接口仍执行原有 `AssetsHolder` 权限校验。

旧版本升级可以在基础模块业务库执行 `modules/property-base/sql/backfill-space-organization-assets.sql`，即时补齐缺失的组织资产关系；脚本可重复执行，不覆盖既有授权。重启基础服务后，首次空间同步也会自动补齐。独立数据库部署应在基础服务业务库执行，不在收费业务库执行。
