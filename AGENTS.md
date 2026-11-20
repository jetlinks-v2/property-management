# 物业后端开发约定

- 使用已安装的共享 `jetlinks-*` skills；结构不明确时先用 `jetlinks-router`，按任务选择 CRUD、响应式、资产权限、跨边界及规范技能。
- JetLinks 保持 `2.20.0-SNAPSHOT`，Java 17、Spring Boot 3.4.13 与当前平台 BOM 一致，版本及插件由根 POM 管理。
- 业务保持在 `modules/property-*/property-*-manager`。仅保留 `services/property-management-service` 一个启动工程、`PropertyManagementApplication` 一个启动类，统一装配八个业务模块；不再新增各模块独立启动类。
- manager 承载接口、应用服务、持久化、权限和国际化；有跨模块共享的 DTO、命令、事件、SPI 契约时再增加 core。core 不依赖 manager。
- 所有业务模块共用统一 R2DBC 数据源和一个 PostgreSQL 数据库/schema，不自动创建模块独立数据库。基础模块持有基础表；业务模块继续通过 core 公开命令访问基础能力，保留认证和资产权限，不直接注入其他模块内部 Service/Repository。
- 先读相邻代码和对应业务设计，最小修改；manager 可以依赖公共 core，不互相依赖。统一启动工程集中依赖八个 manager，文件及 Cloud 专用适配器仍由 SaaS 装配。
- 使用框架现有响应式 CRUD、查询与事务能力，运行时链路不调用 `block()` 或阻塞 I/O。
- 登录复用 SaaS 网关。读取 `TenantHolder` 不代表取得业务授权，项目数据通过统一 `AssetsHolder` 体系校验；详情、批量、导入导出不能绕过权限。
- 删除遵循业务设计，统一逻辑删除；不开放框架通用物理删除入口。
- 用户可见异常、权限动作等遵循平台国际化；公共契约、权限和生命周期边界添加简洁有用的注释。
- 配置集中在统一启动工程的 `src/main/resources/application*.yml`，按 default/dev/local 区分环境。全进程使用一组端口、服务身份、数据库与事件/配置命名空间；Redis、RPC、认证等公共连接与 SaaS 对齐。实际本机密码、token、密钥仅放在被 Git 忽略且不打入 JAR 的 `application-local.yml`，不写入共享配置或日志。
- 各模块 Java 包、接口路径、命令 ID、应用模板及既有资产和权限 ID 保持不变。统一服务身份为 `property-management-service`、事件/配置命名空间为 `property-management`；部署时同步调整旧服务路由、订阅和配置，不把集群 namespace 成员列表视为旧服务身份兼容。
- 新增业务行为补充相应行为测试；修改后运行相关验证。结构变更须验证 `mvn -pl :property-management-service -am verify`、最终 JAR 包含八个 manager，以及单进程共库启动、权限提供者和同名实体装配；不把打包成功等同于网关、登录或业务权限联调成功。
- 权威业务设计位于相邻 `../设计文档`；临时计划和验证证据放在被 Git 忽略的 `.agent-runtime`，不写入 README 执行流水。
- 未明确要求时不推送远程，不修改其他仓库的无关工作区内容。
