# 基础模块

前后端开发、复制接入及公共能力复用见[系统管理模块前后端开发与复用规范](系统管理模块前后端开发与复用规范.md)。

首次部署需在后端建表后执行[默认字典 SQL](sql/init-property-dictionaries.sql)，包含当前 4 类、17 个选项。具体执行方式见[字典初始化章节](系统管理模块前后端开发与复用规范.md#14-部署时初始化默认字典)。

基础服务与报修服务的 JVM 时区应与业务时区一致；中国本地部署可设置容器 `TZ=Asia/Shanghai` 或 `JAVA_OPTS=-Duser.timezone=Asia/Shanghai`。Excel 日期按 JVM 时区解析，仅设置 Jackson 的 `time-zone` 不会改变 Excel 日期含义。

`property-base-manager` 承载本领域 Controller、Service、Entity、Repository、权限及国际化，由独立的 `property-base-service` 装配。从仓库根目录执行 `mvn -pl :property-base-service -am verify`，只构建基础系统及其依赖。

`property-base-core` 提供公共 DTO、组织类型枚举、组织树及基础目录查询命令，不依赖 manager，不包含数据库实体或 CRUD。基础服务必须独立运行；报修、巡更等业务服务按需运行，读取基础数据时只依赖 core 并执行公开命令，不装配本 manager。各应用的系统管理页面经网关调用同一套 `/property/**`，共享同一份基础数据。

## 组织树公开命令

| 项目 | 契约 |
| --- | --- |
| 服务 ID | `propertyService:organization`，由 `@CommandService` 注册 |
| 命令类 | `QueryPropertyOrganizationTreeCommand`，位于 core 的 `command` 包 |
| 命令 ID | `QueryPropertyOrganizationTree`，按 JetLinks 命令类名规则生成 |
| 输入 | 无参数；不接收调用者指定的用户、项目、租户或查询条件 |
| 返回 | `Flux<PropertyOrganizationInfo>`，每个元素为一个根节点，`children` 为下级节点 |
| 节点字段 | `id`、`name`、`code`、`parentId`、`sortIndex`、`organizationType`、`children` |
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


## 用户镜像与物业身份

用户管理实现位于 manager 的 `service/*User*`、`entity/PropertyUser*`、`entity/UserSyncStateEntity`、`web/PropertyUserController`，设计以相邻 `../../../设计文档/用户管理设计.md` 为准。SaaS 账号资料只读，物业身份可以同时为客户、员工，也可清空；身份不等于 SaaS 角色或房屋关系。客户身份保留原 `owner` 编码及数据库列，房屋关系中的“业主”性质含义不变。

`/property/user` 提供 `_query`、`_counts`、`{id}`、`_identities`、`{id}/identities`、`_sync`、`_sync/status`，资源 `property-user`，动作 `query/save/sync`。分页及计数先通过原 `user` 的 AssetsHolder 解析授权 ID，再与 `pm_user` 筛选取交集；不在镜像库执行依赖 Cloud `s_user` 的权限 SQL；详情与标注分别校验 read/save。标注提交完整集合和 version，冲突拒绝覆盖；没有通用账号保存或删除接口。

全量同步通过 `authService:user` 显式 SDK 命令执行，分页暂存、校验和缺失 ID 复核后统一事务落库，保留本地身份、版本和标注审计；异常空来源、分页变化、重复 ID 或复核失败均不提交。SaaS 删除仅逻辑删除，同 ID 恢复保留身份。

用户及关系表 `pm_user`、`pm_user_org` 和租约表 `pm_user_sync_state` 由 EasyORM 管理。配置统一使用 `application-local.yml` 中的 `property.user-sync`：`enabled` 默认 true、`interval-seconds` 默认 3600（1 小时）、`max-users` 默认 100000、`timeout-seconds` 默认 120。关闭 enabled 同时关闭 worker 及手动任务入口。首次在应用就绪 5 秒后执行；手动同步返回 taskId，客户端轮询状态，不能把受理当完成。

运行观测 MBean 为 `org.jetlinks:type=PropertyManagement,name=UserSync`；最近任务及失败原因通过 `_sync/status` 查询，失败保留上次成功时间。基础管理前端通过公开 HTTP 接口访问；其他物业后端不依赖本系统的内部服务和数据库，通过 core 中公开命令读取所需基础能力。

## 空间与房屋档案

空间结构通过固定命令服务 `spaceService:space` 显式不分页读取完整快照，最多 20000 个节点；收到第 20001 条即拒绝本轮，返回顺序不影响完整性判断；不依赖 SaaS manager，不读取空间类型决定业务层级。`pm_space` 保存来源镜像，`pm_space_binding` 保存根空间与组织的关联，`pm_house` 保存物业房屋扩展。源节点名称和层级变化不覆盖本地面积、字典值或客户资料。

空间类型名称通过共享运行服务的只读命令 `spaceService:space-type` 解析；部署时必须包含查询类及其自动配置注册。空间页或类型页任意失败均拒绝整轮快照。

报修通过 `propertyService:directory` 消费本模块公开的 `QueryPropertyProjectsCommand`、`QueryPropertyUsersCommand` 和 `QueryPropertySpacesCommand`，只返回当前租户及资产权限交集内的项目、用户身份和绑定空间。消费方仅依赖 `property-base-core`；参数通过命令容器传递，项目范围和员工筛选跨 RPC 保持一致。空目录正常返回，缺少身份、权限或服务异常明确拒绝，不触发同步或查询旧报修目录。

项目目录在基础服务查询 `pm_organization`，使用 AssetsHolder 批量资产过滤并与可信租户取交集；不能把依赖 Cloud 的 `s_organization` 的资产条件拼进基础镜像库或报修库。消费方将命令返回的授权项目 ID 与业务查询在分页前取交集；空目录对应空业务结果，命令失败向上传播。

真实末端由完整来源树判断：只有一个“1栋”时也可完善为房屋，权限过滤后隐藏子节点不会把父节点变成末端。顶级空间可关联项目或外协单位，一个组织最多关联一个根空间，根空间与组织一对一绑定；项目选择返回已标注为项目且有权访问的组织。已有房屋资料的根不能直接改归属，避免客户历史随绑定静默迁移。

| 接口前缀 | 主要接口 | 菜单资源 / 动作 |
| --- | --- | --- |
| `/property/space` | GET `_projects`、`_roots`、`_tree?projectId=`；PUT `{rootId}/organization`；POST `_sync`；GET `_sync/status` | `property-space` / `query,save,sync` |
| `/property/house` | POST `_query`、`_import`、`_export`；GET `template.xlsx`；GET/PUT/DELETE `{spaceId}` | `property-house` / `query,save,delete,import,export` |
| `/property/customer` | POST `_query`、`_history`、`_import`、`_export`；POST 新建；GET/PUT/DELETE `{id}`；POST `{id}/relationships`；PUT `relationships/{id}/end`；DELETE `relationships/{id}`；GET `_families`、`template.xlsx` | `property-customer` / `query,save,delete,import,export` |
| `/property/dictionary` | GET/POST `_types`；PUT/DELETE `_types/{id}`；GET/POST `{code}/_items`；PUT/DELETE `items/{id}` | `property-dictionary` / `query,save,delete` |

房屋和客户接口必须显式传 `projectId`，查询可传 `spaceId` 限定子树。项目 `organization` 与节点 `space` 资产均使用 `AssetsHolder`；客户先按有权空间关系缩小范围，再查询、统计和分页。选择客户账号还需该账号的 `user` 资产读取权。

空间同步状态和租约存储在 `pm_space_sync_state`，首次就绪后 5 秒执行，此后默认每 1 小时执行。间隔配置为 `property.space.sync-interval`（默认 `1h`），来源上限为 20000；手动 `_sync` 等待本轮完成。异常、超限、缺失引用类型、重复 ID、环或意外空来源均保留原镜像。同步链路提供 MonoTracer 和 `org.jetlinks:type=PropertyManagement,name=SpaceSync` MBean。

## 客户、家庭与历史

个人及企业均使用已同步、启用且标注客户身份的 `userId` 建档；每项目同账号一份主档，通过关系表关联多套房屋。名称、电话和邮箱在前端选账号时回显，之后保存到本地主档，不回写 SaaS，也不被用户同步覆盖。个人必须选择客户性质和新建/加入家庭，企业不接收个人性质、家庭或个人证件字段。

每次房屋关系独立保存开始、结束时间和原因。家庭只要仍有一个有效成员就可加入；最后有效成员结束或误建关联被删除后不再作为当前家庭选项。历史接口按每次已结束关系返回完整客户资料；逻辑删除主档保留历史，误建关联的删除不虚构结束时间。结束最后关系后主档仍能按有权的历史关联管理和复用。

已逻辑删除的客户再次单条建档时，先校验来源用户和原客户的空间资产保存权限，再按本次资料恢复同一主档并新增房屋关联；稳定主档标识、原创建时间和全部旧关系保留，旧关系不恢复。账号和个人/企业类型仍不可更改；整笔操作遵循项目锁、版本 CAS 和事务，新关联无效时恢复也回滚。未删除的同账号主档仍拒绝重复新建，应关联已有客户；批量导入仍遵循既有复用规则。

所有删除均为逻辑删除，不继承或开放通用物理删除接口。写入使用 `version` CAS 并记录修改人、时间；项目数据库行锁保护同项目的房屋、客户、关系写入。仍有有效客户的房屋，以及仍有有效房屋关系的客户，均拒绝直接删除。

## 字典与批量操作

字典全系统统一，分类编码和同分类选项值保存后不可改，删除后的编码仍占用。分类/选项均支持排序、说明和独立启停；恢复分类不会启用此前单独停用的项。业务分类为 `house-type`、`house-state`、`customer-nature`、`certificate-type`，选项统一维护；初始选项可按需执行 `modules/property-base/sql/init-property-dictionaries.sql` 补齐，不覆盖既有状态。已有值允许保留停用编码，新选值必须同时满足分类和项启用。绑定业务字段的分类、有未删除选项的分类、被当前或历史资料引用的选项不能删除，应使用停用。

列表使用标准 `QueryParamEntity`，支持白名单字段、嵌套条件、排序和分页；未知字段或操作明确拒绝，不静默忽略。导出使用同一权限和筛选，返回全部匹配记录，上限 10000；不能把当前页当完整导出。模板、导入、导出均由后端提供。模板为 `GET /property/{house|customer}/template.xlsx?projectId=`，客户另必传 `customerType=person/company`，复用 import 权限，按项目返回中文填写表、真实空间和字典/用户参考；房屋全部已完善仍可下载空填写表。`POST _import` 为 multipart，字段 `file`，仅 xls/xlsx、10MB、1–500 行；`POST _export` 接收同列表 QueryParam，返回 XLSX 文件流。前端不解析 Excel 或提交 JSON 行数组。

输出复用 JetLinks `ExcelUtils` 及其 `WorkbookOption`；读取使用配套 POI 保留真实物理行号，只处理第一张数据表，拒绝公式、重复表头、空行和非文本标识。不使用默认导入器的分批写入或失败后逐行重试。文件全部解析、字典转换、字段/权限/现有数据/批内重复及家庭一致性预检通过后，才订阅一个响应式事务中的全部写入；预检失败零写入，并发持久化失败整批回滚。错误响应含真实 Excel 行号和业务原因；前端仅消息提示，文件接口的 JSON 错误不能保存为 Excel。房屋导入只补尚未完善的末端；客户按姓名和联系电话唯一匹配来源账号，再按稳定 `userId` 复用可管理的主档，不覆盖既有资料；个人填写家庭方式和家庭名称，同文件同房屋同名新家庭归组。
