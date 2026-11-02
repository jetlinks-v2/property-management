# 物业管理后端

基于 JetLinks SaaS 2.20 的独立物业后端仓库。按业务划分 Maven 模块，通过一个 Spring Boot 服务统一启动，复用现有 SaaS 网关、登录、租户、资产权限和 Docker 中间件。

## 工程结构

```text
property-management/
├── modules/
│   ├── property-base/
│   │   └── property-base-manager/       字典、空间、客户等基础档案
│   ├── property-finance/
│   │   └── property-finance-manager/    财务收费
│   └── property-patrol/
│       └── property-patrol-manager/     巡更
├── services/
│   └── property-management-service/     统一启动、配置与装配
├── config/                             外部本地配置及样例
├── deploy/                             Docker SaaS 与 IDEA 联调配置
└── .run/                               IDEA 启动和调试配置
```

Java 17、Spring Boot 3.4.13、JetLinks 2.20.0-SNAPSHOT。Boot 版本与当前 JetLinks BOM 一致，根 POM 统一版本和依赖；业务 manager 由启动模块显式依赖，只有一个应用进程和服务发现身份。参考门禁的多模块与 SaaS 接入结构，不依赖门禁厂商插件。

各 manager 承载领域接口、应用服务、持久化、权限及国际化。需要跨模块复用 DTO、命令、事件或 SPI 时，再在对应领域增加 core。core 不承载 CRUD，不反向依赖 manager；跨领域不直接读写其他模块的表或注入其内部服务。当前财务和巡更仅有模块边界，具体业务按后续设计实施。

## IDEA 启动与 Docker SaaS 接入

1. 用 IDEA 打开本目录 `pom.xml`，SDK 和 Maven Runner JRE 选择 JDK 17，使用现有 JetLinks Maven settings 与仓库权限。
2. 启动现有 Docker PostgreSQL、Redis、SaaS 网关和运行端；运营控制端仍按 SaaS 原有方式运行。
3. 首次配置时，将 `config/application-local.example.yml` 复制为 `config/application-local.yml`，填写现有 PostgreSQL、Redis、RPC token 和 JWT 公钥。已配置的本机文件无需覆盖；不需要 JWT 私钥。
4. 在 PowerShell 执行 `./deploy/enable-host-dev.ps1`，为 Docker 网关及运行端叠加本机联调端口和注册地址。脚本读取带默认网关的唯一活动网卡地址；有多个候选时要求用 `-AdvertisedHost` 指定。脚本同时生成被 Git 忽略的 `config/application-host-dev.yml`，让物业服务与容器使用相同注册地址，不修改包含密码的本地配置。脚本只更新这两个服务，数据卷和数据库、Redis 等依赖容器保留。默认 SaaS 位于相邻 `cloud.jetlinks/runtime/all-in-one`，Compose 项目名为 `jetlinks-local`，均可通过参数调整。
5. 等待网关与运行端健康，运行自带配置 **物业后端 PropertyManagementApplication**，或调试 `org.jetlinks.pro.property.PropertyManagementApplication`。工作目录必须为本仓库根目录。

`config/application-local.yml` 由 `spring.config.import` 加载，已忽略且不会打入 JAR。共享默认配置和 IDEA 配置不得填写实际密钥。`application-local.example.yml` 是外部配置示例，实际本地配置放在 `config`，不用在每个业务模块重复创建。

| 用途 | 默认端口或地址 |
| --- | --- |
| 物业 HTTP | `8503` |
| 物业集群 / RPC | `18503` / `28503` |
| SaaS 网关 HTTP | `http://127.0.0.1:8800` |
| SaaS 网关集群 / RPC | `18800` / `28800` |
| SaaS 运行端 HTTP（网关回连） | `8200` |
| SaaS 运行端集群 / RPC | `18200` / `28200` |
| PostgreSQL | `127.0.0.1:5432/jetlinks`，schema `public` |
| Redis | `127.0.0.1:6379`，与运行端同一逻辑库 |

容器与物业服务向集群发布同一个实际宿主机地址，种子为 `127.0.0.1:18800`，上述集群和 RPC 端口需双向可达。使用实际地址可以避免 Windows hosts 中过期的 `host.docker.internal` 记录。网卡地址改变后重新运行脚本并重启物业服务。服务器部署时替换为实际双向可达地址，并移除本机生成的 `config/application-host-dev.yml`。

联调覆盖文件位于本仓库，后续更新 Docker 网关或运行端时仍需带上它；再次运行脚本即可。恢复纯 Docker 模式时，用原来的两个 Compose 文件执行 `up -d --no-deps api-gateway-service all-in-one-service`；不要执行 `down -v`。

## 接口与权限边界

`GET /property/context` 返回平台标准响应包装，`result` 包含 `userId`、`username`、`projectId`（无项目时显式为 `null`）。前端通过 SaaS 网关调用 `/property/context`，沿用运行端的登录 token 和项目上下文。网关通过 JetLinks 集群发现服务和接口路由，业务模块不分别注册。

接口只读取当前响应式认证与项目上下文，不接收用户或项目切换参数；未登录直接访问服务也会拒绝。`TenantHolder` 的项目上下文不能代替 `AssetsHolder` 数据权限。后续业务接口声明资源动作、资产类型并统一校验查询、详情、修改、逻辑删除、批量及导入导出权限。

SaaS 登录 token 应通过网关使用；网关处理登录身份并向后端传递平台内部认证上下文。不要把直接向 `8503` 发送原始登录 token 当成网关登录接入的验收路径，也不要为此在物业服务新增第二套 token 校验或登录接口。

OpenAPI 分组为 `property-management-service`，路由 `/v3/api-docs/property-management-service`。扫描覆盖 `org.jetlinks.pro.property` 下所有已装配模块的接口。

不新增独立登录、用户、角色或项目管理。项目列表由其他开发人员提供；收费前端已有统一项目列表接入入口。字典、空间、客户等业务设计位于相邻 `../设计文档`，所有业务删除遵循已确认的逻辑删除规则。

## 构建与命令行运行

```powershell
mvn clean verify
$propertyJvmOptions = @(
  '--add-opens=java.base/java.lang=ALL-UNNAMED',
  '--add-opens=java.base/java.util=ALL-UNNAMED',
  '--add-opens=java.base/java.util.concurrent=ALL-UNNAMED',
  '--add-opens=java.base/java.io=ALL-UNNAMED',
  '--add-opens=java.base/java.net=ALL-UNNAMED',
  '--add-opens=java.base/java.text=ALL-UNNAMED',
  '--add-opens=java.base/java.math=ALL-UNNAMED',
  '--add-opens=java.base/java.time=ALL-UNNAMED',
  '--add-opens=java.scripting/javax.script=ALL-UNNAMED',
  '-Dspring.reactor.debug-agent.enabled=false'
)
java @propertyJvmOptions -jar services/property-management-service/target/application.jar
```

Java 17 的集群序列化需要这些 VM 参数，IDEA 配置已提供。仅启动物业后端即可调试三个业务模块，SaaS 及中间件继续在 Docker 运行。日志位于 `data/logs/`。原 `charging-management` 保留供对照，物业业务后续统一在本仓库开发。
