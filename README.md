# 物业管理后端

基于 JetLinks SaaS 2.20 的物业组后端单仓库。基础档案、财务收费、智能巡更各自拥有业务模块、启动服务和发布制品，可以只构建、启动或更新其中一个系统。各系统直接复用 SaaS 网关、认证、租户、资产权限和公共中间件，系统之间互不依赖、互不调用。

## 工程结构

```text
property-management/
├── modules/
│   ├── property-base/
│   │   └── property-base-manager/
│   ├── property-finance/
│   │   └── property-finance-manager/
│   └── property-patrol/
│       ├── property-patrol-core/
│       └── property-patrol-manager/
├── services/
│   ├── property-base-service/
│   ├── property-finance-service/
│   └── property-patrol-service/
├── deploy/
│   ├── patrol/                 巡更连接示例及认证服务应用模板
│   ├── start-service.sh        单系统 JAR 启动脚本
│   └── enable-host-dev.ps1     Windows Docker SaaS / IDEA 联调
└── .run/                       三个独立 IDEA 启动配置
```

Java 17、Spring Boot 3.4.13、JetLinks 2.20.0-SNAPSHOT，版本和插件由根 POM 管理。`modules/` 中的 POM 只负责构建聚合，不启动进程；每个 `service` 只依赖本系统 manager。单个系统可以包含多个菜单及对应 Controller、Service、Entity、权限和国际化。系统内部有需要时拆分 core，core 只承载领域类型、DTO、规则或契约，不承载 CRUD，也不反向依赖 manager。

禁止业务系统之间的 Maven 依赖、内部服务注入、跨库读写，以及通过 HTTP、命令、代理或事件互相调用；SaaS 公共能力由各系统直接接入。基础档案不是其他系统的必启前置服务。各前端仍分别放在 SaaS 的 `runtime-ui/modules/`，不在本仓库合并前端。

| 系统 | 启动类 | 默认 HTTP / 集群 / RPC | 服务发现名 | 默认业务库 |
| --- | --- | --- | --- | --- |
| 基础档案 | `org.jetlinks.pro.property.base.PropertyBaseApplication` | 8503 / 18503 / 28503 | `property-base-service` | `property_base` |
| 财务收费 | `org.jetlinks.pro.property.finance.PropertyFinanceApplication` | 8504 / 18504 / 28504 | `property-finance-service` | `property_finance` |
| 智能巡更 | `org.jetlinks.pro.patrol.PatrolApplication` | 8502 / 18502 / 28502 | `cloud-patrol-service` | `cloud_patrol` |

各系统有独立的事件和配置命名空间。巡更保留已部署的 `cloud-patrol-service` 服务名、`cloud-patrol` 命名空间、`/park/patrol/**` 接口、资产/权限 ID 和 `CloudPatrol` 应用模板；目录与 Maven 制品名改为 `property-patrol-*` 不改变这些外部契约。

## 当前业务范围

- [基础档案](modules/property-base/README.md)：已有 `GET /property/context` 当前用户/项目上下文接口，字典、空间、客户等业务尚待开发。
- [财务收费](modules/property-finance/README.md)：已有模块和独立启动骨架，收费业务尚待开发。
- [智能巡更](modules/property-patrol/README.md)：包含现有分类、点位、标准、岗位、排班、计划、任务、执行及统计实现与测试；后续巡更后端统一在本仓库开发。

## 独立构建与启动

需要 JDK17、Maven 及目标 SaaS 对应 JetLinks 制品的仓库权限。根目录执行以下命令只构建所选系统和它自己的依赖，不会构建或启动兄弟系统：

```bash
mvn -pl :property-patrol-service -am verify
mvn -pl :property-base-service -am verify
mvn -pl :property-finance-service -am verify
```

也可执行 `mvn verify` 构建全仓库；这仍只生成三个独立 JAR，不产生统一业务进程。产物分别位于 `services/property-<system>-service/target/application.jar`，发布时须保留系统目录或镜像名区别，不能把三个同名 JAR 混放。

先准备业务库、环境变量及可达的 SaaS 公共服务，再选择其中一个启动：

```bash
# 连接变量见各 service 的 application-default.yml；巡更另有 deploy/patrol/patrol.env.example。
# 服务器通过环境变量提供真实连接值，脚本不自行加载凭据文件。
bash deploy/start-service.sh patrol
# 本机可显式加载被 Git 忽略的 application-local.yml：
bash deploy/start-service.sh base services/property-base-service/src/main/resources/application-local.yml
```

脚本提供 Java17/21 下 Redis/FST 集群序列化需要的 `--add-opens` 参数。PowerShell/IDEA 运行也需要这些参数，自带 `.run/` 配置已提供。启动一个系统不要求启动另外两个，但数据库、Redis、网关等公共依赖仍需可用。

## IDEA 与本地连接配置

1. 打开根 `pom.xml`，IDEA SDK 和 Maven Runner JRE 使用 JDK17，配置团队 JetLinks Maven settings。
2. 在目标 `services/property-<system>-service/src/main/resources/` 中，复制 `application-default.yml` 为 `application-local.yml`，去掉示例键前的注释并填写实际连接值。不要复制本机凭据到另一个系统后仍使用同一业务库。
3. 选择对应的“基础档案”“财务收费”或“智能巡更”运行配置，激活 `default,dev,local`。只有目标 service 的类路径会参与运行；不要人为把兄弟 manager 加入运行依赖。
4. 对齐 SaaS 的 Redis、RPC token、JWT 公钥和集群种子。各系统的 HTTP、集群和 RPC 广播地址必须能被平台节点回连；多个本机服务同时启动时不能覆盖成同一个端口或 `SERVER_ID`。

`application.yml` 默认仅加载 `default`，生产连接值由环境变量或外部配置提供；`application-dev.yml` 只控制开发日志。`application-local.yml` 在开发编译时进入类路径供 IDEA 使用，但被 Git 忽略，且由 JAR 插件排除，不进入发布制品。本仓库不维护 SaaS 管理员初始密码或 JWT 私钥。

Windows 连接本机 Docker SaaS 时可使用：

```powershell
./deploy/enable-host-dev.ps1 -System patrol -SaasComposeDirectory '实际 cloud.jetlinks/runtime/all-in-one 路径'
```

脚本只更新所选服务本地配置中的两个注册地址，并为本机 Docker 网关/运行端叠加 `deploy/docker-compose.host-dev.yml`；不会启动其他物业系统。多网卡时用 `-AdvertisedHost` 指定地址。它面向本机 Docker 联调，不用于修改已部署的测试环境；连接远程测试公共服务时自行配置可达的地址和隧道。

## SaaS 接入与部署边界

各系统通过网关发现和公开路由接入，登录、用户、角色、组织和项目管理沿用 SaaS。`TenantHolder` 只代表项目上下文，业务数据权限仍由 `AssetsHolder` 控制。巡更业务与应用初始化步骤见 [巡更说明](modules/property-patrol/README.md)。基础系统的 `/property/context` 沿用响应式认证，不接收客户端传入的用户或项目 ID，也不向其他物业系统提供上下文中转服务。

仅调整源码仓库和构建结构，不需要重新部署整个 SaaS。切换新产物时只更新对应系统后端，巡更继续使用原部署目录、业务库、服务名和应用模板；同一环境替换时先停止旧实例，避免保留相同节点 ID 的重复实例。运行端前端文件未变化时无需重发；只有页面变动才重建运行端宿主。基础、财务服务以后按需分别部署、初始化各自菜单和模板。

SaaS 聚合工作区通过 `modules/property-management` 引用本仓库，前端仍各自维护。源码聚合不等于部署或应用开通；单系统打包成功也不等于网关、项目应用、资产权限及 PostgreSQL 业务验收通过。
