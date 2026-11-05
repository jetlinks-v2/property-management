# 承接查验独立服务

本服务提供 `/property/handover/**`，使用独立 PostgreSQL 数据库 `property_handover`。基础组织、人员、空间通过 `property-base-core` 公共命令查询；认证、租户、应用、文件继续使用 SaaS 平台。运行类路径不引入基础、报修或巡检 manager。

报事转查验通过 `CreateHandoverProblemCommand` 调用 `handoverService:problem`。查验使用独立命令 provider，不能与基础服务的 `propertyService` 共用前缀，否则本机命令注册会遮蔽远程项目、员工和组织目录。

## 本地启动

使用 JDK 17 和 Maven 3.9。IDEA 启动 `SmartParkHandoverApplication`，工作目录设为本服务目录，profiles 与相邻服务一致使用 `default,dev,local`；只启用 `default,local` 也可。连接信息放在未跟踪的 `src/main/resources/application-local.yml` 或 IDE 环境变量中。`application-default.yml` 可作为本机示例保存，按根 `.gitignore` 约定不提交；`application-local.yml/.yaml` 同时排除在发布 JAR 之外。不要把实际密码、公钥或 RPC 密钥写进共享文件。

| 变量 | 说明 |
| --- | --- |
| `DB_HOST` / `DB_PORT` | PostgreSQL 地址；默认 `127.0.0.1:5432` |
| `DB_DATABASE` / `DB_SCHEMA` | 默认 `property_handover` / `public`，使用本服务独立库 |
| `DB_USERNAME` / `DB_PASSWORD` | 数据库账号、密码，必须提供 |
| `REDIS_HOST` / `REDIS_PORT` | 平台 Redis，默认 `127.0.0.1:6379` |
| `REDIS_DATABASE` / `REDIS_PASSWORD` | 默认数据库 `0`；密码按目标 Redis 提供 |
| `SERVER_PORT` / `SERVER_ID` | 默认 `8507` / `smart-park-handover-service-1`，集群每个实例 ID 唯一 |
| `CLUSTER_PORT` / `RPC_PORT` | 默认 `18507` / `28507` |
| `CLUSTER_EXTERNAL_HOST` / `CLUSTER_EXTERNAL_PORT` | 其他节点可回连的地址和端口；默认回环地址仅适用于同机环境 |
| `RPC_EXTERNAL_HOST` / `RPC_EXTERNAL_PORT` | RPC 回连地址和端口，默认继承集群地址与 RPC 监听端口 |
| `CLUSTER_SEEDS` | 已运行的 SaaS 集群种子，默认 `127.0.0.1:18800` |
| `RPC_TOKEN` | 与目标 SaaS 集群一致的通信密钥，必须提供 |
| `JWT_PUBLIC_KEY` | 认证服务签发 JWT 对应的公钥，必须提供 |

本机 YAML 可以使用顶层键 `DB_HOST`、`DB_USERNAME` 等变量名覆盖占位符。生产建议通过环境或外部配置提供。先创建数据库和 schema，账号需具备查验表的建表/升级权限；表由实体映射管理，禁止通过基础库授权绕过公共命令。

从仓库根目录构建：

```powershell
$env:JAVA_HOME='C:/path/to/jdk-17'
mvn -pl services/property-handover-service -am package '-DskipTests'
./scripts/handover/Start-Handover.ps1
```

服务注册的两条路由 `/property/handover/**` 与 `/v3/api-docs/smart-park-handover-service` 均要求平台认证。网关需要发现此服务，基础服务和认证命令提供方需可用；服务不维护独立账号或登录入口。管理员还需在平台为使用者配置对应查验动作权限和项目组织资产权限。

IDEA 可直接使用仓库的“承接查验 SmartParkHandoverApplication”运行配置。命令行脚本默认使用 PATH 中的 Java，可通过 `-Java` 指定 JDK 17。两种入口均提供平台 FST 序列化所需的 `--add-opens`；自行使用 `java -jar` 时须保留相同参数，否则 Redis/集群序列化会报 JDK 模块访问错误。

验收脚本 `scripts/handover/Invoke-HandoverAcceptance.ps1` 默认直连网关 `8800`，接口不带 `/api`。通过前端代理访问时显式传入 `-ApiPrefix /api`。令牌通过当前进程的 `HANDOVER_TEST_TOKEN` 读取。

## 导出 worker 运维契约

应用启用 `@EnableScheduling`。worker 每 5 秒从数据库选择 `pending` 和租约过期的 `running`，通过版本、状态和 leaseOwner 的 CAS 领取；同一任务由一个节点处理。租约 2 分钟，每 20 秒续租，续租超时 10 秒，单任务最长 24 小时。重启恢复待处理和过期任务，`failed` 必须由有权限的用户调用 `POST /property/handover/export/{id}/retry` 显式重试。

`sp_handover_export` 需要与实体映射同步，包括 `manifestFileId`；租户、应用、项目和创建人由服务填写。执行和下载都重新检查创建人的当前认证、租户、应用、`handover-export/export` 动作和项目权限。权威认证命令 `GetAuthenticationCommand` 不可用时任务失败，不回退到过期授权缓存。

原照片、XLSX 和 `.sources` manifest 都通过平台 FileManager 操作。生成文件不开放 publicAccess，文件 ID 不作为公共返回字段；下载通过 `/property/handover/export/{id}/download` 逐批校验源文件权限，禁止改成静态文件 URL。文件存储成功而数据库失败时执行删除补偿；补偿失败写出可读错误及日志，需要运维核对存储。

节点 `java.io.tmpdir` 必须可写，并预留最终 XLSX、工作 XML 和单张原图/缩略图所需空间；结束和异常均清理临时文件。原图最多 32 MiB，缩略图最长边 512，单 sheet 含照片/节点最多 1,048,576 行，单格最多 32,767 字符；超限或图片读取失败使整个任务明确失败，不静默跳图。图片按 OOXML ZIP 部件逐张流式写入，不能改成收集全量图片字节。

JMX `org.jetlinks:type=HandoverExport,name=worker` 提供本节点的 `Active`、`Claimed`、`Completed`、`Failed`、`LastError`、`LeaseMillis`、`HeartbeatMillis`。这些是运行监控，业务重试必须经过授权 API。

## 验证边界

模块装配测试检查独立类路径、认证路由、调度启用和 YAML 绑定路径；业务测试在临时 PostgreSQL 验证事务、CAS 和权限。完整上线验收仍需在目标 SaaS 环境执行真实登录、跨服务目录、文件、PC/H5 以及报事提供方的联调。

## 分类接口与既有数据升级

`/property/handover/category` 是当前租户、应用共享的两级资料目录，不要求项目参数；`/category/tree` 返回完整树，`/_query` 分页查询，`/{id}` 详情，POST 保存，DELETE 带 version 逻辑删除。`/property/handover/problem-category` 保留项目问题分类，项目、资产范围和版本校验不变。资料记录、模板与导入使用共享资料分类；问题和前介台账使用项目问题分类。启停不改变历史分类名称，停用项不可用于新记录。分类维护继续使用 `handover-config` 资源的保存/删除权限，菜单动作 ID 不变。

既有环境先备份业务 schema，再执行 [只读分类盘点](../../modules/property-handover/docs/sql/01-material-category-inventory.sql)。部署新实体完成建表后，暂停查验写入，在业务 schema 下执行 [资料分类迁移](../../modules/property-handover/docs/sql/02-material-category-migrate.sql)。脚本复制资料使用的分类及父级，保存独立 ID 映射和原记录版本，保留原问题引用。跨项目同级重名、未知分类应先人工确认，脚本不会自动合并。对账为零且引用数量一致后恢复服务。需要回退时先停写并回退应用，再执行 [保护性回滚](../../modules/property-handover/docs/sql/03-material-category-rollback.sql)；迁移后已编辑或新增资料则明确拒绝自动回滚，保留备份人工处理。全新环境不需要执行迁移。

设备升级：旧模板的供应商、生产日期曾保存在 properties。备份业务 schema 后执行 modules/property-handover/docs/sql/04-equipment-fields-migrate.sql，仅补空字段，保留原扩展值；日期须为 PostgreSQL 可解析日期，非法值整批回滚。备份表只记录实际迁移行及迁移后的值和版本。需要撤回时执行 05-equipment-fields-rollback.sql：迁移行已编辑、删除或值不符则整批拒绝；未迁移行和迁移后新增的行不参与回滚。保留备份表用于核对，脚本不支持重复执行。


## 阶段记录、清单确认和首页统计

PC 展示七个主阶段；交接总结为次级入口，后端八阶段契约保留。启动会议保存地点及起止毫秒时间，结束不得早于开始；资料移交保存地点、双方人名电话、资料完整性及业务时间；技术交底使用文本分类、交底人、目录接收人和业务时间。服务端校验与表单、阶段 Excel 模板一致。

`POST /property/handover/item/_confirm` 接收 `{projectId,source:"MATERIAL_TRANSFER",items:[{id,version}]}`。完整校验当前项目、来源、版本及未完成问题后，事务确认整批；一条冲突全部回滚。单条确认沿用 `/{id}/confirm`。确认与阶段推进竞争同一流程版本，完成阶段不可继续确认。`GET /property/handover/process/history?projectId=...&stage=...&pageIndex=0&pageSize=12` 返回真实操作者、动作、内容及时间，遵守项目范围。项目进度查询支持 keyword、stage 和 stageStatus，先筛选再分页。

`GET /property/handover/statistics?projectId=...&year=2026` 的 projectId 可省略，省略时只汇总当前有权项目。四指标为有业务记录的项目数、累计问题数、已完成问题数、资料移交清单数；前介台账不计为施工问题。年份仅作用于 inspectionTime 的上海时区月度趋势，不影响累计数和分类/状态分布。`years` 给出历史查验年份；没有数据的月份返回零。问题列表与 H5 继续使用 `/problem/_counts`，不依赖首页权限或资料目录。H5 问题分类请求 `/problem-category/_query`。

前端沿用既有菜单与按钮动作 ID，无须仅为本轮字段、页面布局重新初始化菜单；已有账号须拥有对应流程、配置、问题动作及项目资产权限。新增批量确认使用已有流程保存按钮权限，HTTP 接口仍由配置保存资源授权。
