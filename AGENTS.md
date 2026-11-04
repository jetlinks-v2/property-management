# 物业后端开发约定

- 使用已安装的共享 `jetlinks-*` skills；结构不明确时先用 `jetlinks-router`，按任务选择 CRUD、响应式、资产权限、跨边界及规范技能。
- JetLinks 保持 `2.20.0-SNAPSHOT`，Java 17、Spring Boot 3.4.13 与当前平台 BOM 一致，版本及插件由根 POM 管理。
- 一个仓库管理多个独立业务系统。业务放入对应 `modules/property-*/property-*-manager`，每个系统由自己的 `services/property-*-service` 装配、启动和发布。一个系统可以承载多个菜单，不按菜单拆服务；不增加聚合所有业务的统一启动入口。
- manager 承载接口、应用服务、持久化、权限和国际化；有跨模块共享的 DTO、命令、事件、SPI 契约时再增加 core。core 不依赖 manager。
- 物业业务系统之间互不依赖、互不调用，包括 core 契约依赖、命令、代理、HTTP 和事件调用；禁止注入对方 Service、Repository 或直接操作对方数据表。每个 service 只依赖本系统 manager，各系统直接接入 SaaS 公共组件和服务。
- 先读相邻代码和对应业务设计，最小修改；系统内部 manager 可以依赖本系统 core，不把基础档案系统变成其他物业系统的启动依赖。
- 使用框架现有响应式 CRUD、查询与事务能力，运行时链路不调用 `block()` 或阻塞 I/O。
- 登录复用 SaaS 网关。读取 `TenantHolder` 不代表取得业务授权，项目数据通过统一 `AssetsHolder` 体系校验；详情、批量、导入导出不能绕过权限。
- 删除遵循业务设计，统一逻辑删除；不开放框架通用物理删除入口。
- 用户可见异常、权限动作等遵循平台国际化；公共契约、权限和生命周期边界添加简洁有用的注释。
- 配置分别放在各启动服务的 `src/main/resources/application*.yml`，按 default/dev/local 区分环境。各系统的端口、服务身份、业务库、事件和配置命名空间独立；Redis、RPC、认证等公共连接与 SaaS 对齐。实际本机密码、token、密钥仅放在被 Git 忽略且不打入 JAR 的 `application-local.yml`，不写入共享配置或日志。
- 巡更已部署的外部契约保持不变：Java 包 `org.jetlinks.pro.patrol`、接口 `/park/patrol/**`、服务名 `cloud-patrol-service`、命名空间 `cloud-patrol`、应用模板 `CloudPatrol` 及既有资产和权限 ID。Maven/目录使用 `property-patrol-*`，不因此重命名外部标识。
- 新增业务行为补充相应行为测试；修改后运行相关验证。结构变更须验证单系统 `mvn -pl :property-*-service -am verify` 及最终 JAR 不含兄弟系统业务模块；不把打包成功等同于网关、登录或业务权限联调成功。
- 权威业务设计位于相邻 `../设计文档`；临时计划和验证证据放在被 Git 忽略的 `.agent-runtime`，不写入 README 执行流水。
- 未明确要求时不推送远程，不修改其他仓库的无关工作区内容。
