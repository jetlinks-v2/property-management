# 承接查验独立服务

本服务提供 `/property/handover/**`，使用独立 PostgreSQL 数据库 `property_handover`。基础组织、人员、空间通过 `property-base-core` 公共命令查询；认证、租户、应用、文件继续使用 SaaS 平台。运行类路径不引入基础、报修或巡检 manager。

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
