# 物业管理后端

基于 JetLinks SaaS 2.20，基础档案、财务收费、装修管理、智能巡更、报事报修、品质核查、设施能源和承接查验由一个启动工程统一运行，共用一个 PostgreSQL 数据库及 schema。业务模块、接口路径、命令契约、应用模板和资产权限边界保持独立。

## 工程结构

```text
property-management/
├── modules/
│   ├── property-base/
│   ├── property-finance/
│   ├── property-decoration/
│   ├── property-patrol/
│   ├── property-repair/
│   ├── property-quality/
│   ├── property-facility-energy/
│   ├── property-handover/
│   └── property-runtime-support/           跨模块权限装配，无启动类
├── services/property-management-service/   本仓本地开发启动工程
├── deploy/                                启动脚本、部署配置及应用模板
└── .run/PropertyManagementApplication.run.xml
```

Java 17、Spring Boot 3.4.13、JetLinks 2.20.0-SNAPSHOT，版本与插件由根 POM 管理。manager 承载本模块业务，core 承载公开命令、DTO、事件及 SPI，不反向依赖 manager。跨模块读取仍通过公开命令保留调用者认证、租户与 AssetsHolder 权限，不因共进程共库直接操作其他模块的内部服务或表。

| 项目 | 配置 |
| --- | --- |
| 启动类 | `org.jetlinks.pro.property.PropertyManagementApplication` |
| Maven 制品 | `property-management-service` |
| JAR | `services/property-management-service/target/application.jar` |
| 默认 HTTP / 集群 / RPC | 8500 / 18500 / 28500 |
| 服务发现名 | `property-management-service` |
| 事件及配置命名空间 | `property-management` |
| 数据库 / schema | 统一的 `DB_DATABASE` / `DB_SCHEMA`，默认 `jetlinks` / `public` |

文件和 Cloud 专用适配器继续由 SaaS 公共服务装配，不进入物业业务 JAR。统一服务不提供 SaaS 登录、设备接入、平台文件存储或管理员初始化。

## 构建与启动

需要 JDK17+、Maven 及团队 JetLinks Maven 仓库权限：

```bash
mvn -pl :property-management-service -am verify
bash deploy/start-service.sh
# 可显式加载本机配置：
bash deploy/start-service.sh /absolute/path/to/application-local.yml
```

脚本提供 Redis/FST 所需的 Java 模块开放参数。IDEA 导入根 POM，选择“物业统一后端 PropertyManagementApplication”运行配置。业务模块测试也可单独执行；运行后端只启动这一个 JAR。

连接配置集中在 `services/property-management-service/src/main/resources/`：

- `application.yml`：统一装配配置，默认加载 `default`。
- `application-default.yml`：非敏感连接默认值。
- `application-dev.yml`：开发日志。
- `application-local.yml`：自行创建并填写本机连接值，被 Git 忽略且不进入发布 JAR。IDEA 激活 `default,dev,local` 使用。

至少提供 `DB_USERNAME`、`DB_PASSWORD`、`RPC_TOKEN`、`JWT_PUBLIC_KEY`，并按环境设置数据库、Redis、集群种子及可回连的集群/RPC 广播地址。JWT 公钥、Redis 与 RPC 配置须与 SaaS 对齐；不要提交实际密码、token 或密钥。数据库应由部署方预先准备，EasyORM 在配置的 schema 中初始化业务表，不创建八个数据库。

Windows 与本机 Docker SaaS 联调可使用：

```powershell
./deploy/enable-host-dev.ps1 -SaasComposeDirectory '实际 cloud.jetlinks/runtime/all-in-one 路径'
```

该脚本更新统一服务本地配置的广播地址，并叠加本机 Docker 网关/运行端开发配置。多网卡时显式传入 `-AdvertisedHost`；不用于修改远程正式环境。

## Docker

构建上述 JAR 后，在仓库根目录：

```bash
docker build -f deploy/Dockerfile -t property-management:local .
docker compose --env-file /absolute/path/to/property.env -f deploy/compose.yml config --quiet
docker compose --env-file /absolute/path/to/property.env -f deploy/compose.yml up -d
```

`deploy/compose.yml` 只运行一个物业服务，复用外部 PostgreSQL、Redis 和 SaaS 网络，不创建独立数据库容器。连接变量及 `CLOUD_NETWORK` 必填，广播地址须能被 SaaS 回连。`deploy/start-property-suite.sh` 只管理此统一服务，不再按业务名启动八个后端，也不替代 SaaS 公共服务的部署脚本。历史各模块部署文档中的独立进程步骤已停用，应用菜单、权限与业务迁移 SQL 仍按对应模块使用。

## 从独立服务切换

改连接地址不会把旧库的数据自动合并。已有环境切换前应停写并备份，单独制定迁移方案，把各模块业务表导入目标库，检查 ID、序列、字典、资产关系及约束冲突；共享框架表不能直接覆盖合并。核对记录数、关联完整性和业务金额后再切流。本次源码调整不搬迁、删除或清空现有数据库。

统一进程只有一组服务身份、事件/配置命名空间和定时任务实例。部署时停止旧八个服务，调整 SaaS 中指向旧服务的路由、订阅和配置，以及反向代理地址，再刷新服务发现。集群 namespace 保留旧业务成员范围，并不代表旧 server-id、事件主题或配置空间会自动兼容。尤其需要验证设备采集、SaaS 告警、空间同步、任务调度、附件与跨模块命令。

业务 Java 包、HTTP 路径、应用模板（如 `CloudPatrol`）、资源权限及命令 ID 保持不变；PC/H5 仍从原前端仓库发布，经 SaaS 网关进入。共库不扩大项目、应用和租户权限，也不自动开通应用或菜单。匿名报修二维码仅保留 `/repair/public/qr/**`，其他业务入口继续鉴权。

## 模块与交付

业务说明见各 `modules/property-*/README.md`，应用模板及迁移脚本见 `deploy/`。共享分支为 `2.20`，使用整合分支及 PR 交付；聚合仓库通过一个 `property-management` 子模块引用本仓库。后端合入不会自动更新宿主子模块指针或发布运行端。

结构变更需验证八个 manager 同时打包、同进程共库启动、Repository 注册、组织权限提供者及受保护路由。构建和启动检查不替代真实 SaaS 的登录、应用授权与业务流程验收。临时验证日志与本地备份放在被 Git 忽略的 `.agent-runtime/`。

聚合 UAT 环境通过其已有 `applications-service` 装配物业 manager 与 `property-runtime-support`，不复制本仓启动工程。跨模块权限装配代码集中在 `modules/property-runtime-support`，不放入宿主启动工程或基础 manager。

设施告警使用平台装配的 EventBus，平台总线与 SaaS 告警源处于同一事件命名空间时启用实时订阅，否则按周期同步。聚合运行时，`property-runtime-support` 继续提供 `namespacedEventBusFactory`，供已接入该工厂的园区版本使用。
