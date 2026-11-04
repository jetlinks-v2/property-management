# 品质核查应用部署

品质核查由 `property-quality-service`、`quality-inspection-ui` PC 站点和员工 H5 分别发布。默认服务 HTTP/Cluster/RPC 为 8507/18507/28507，业务库为 `property_quality`。Java 字节码目标为 17，与 Cloud 使用相同 JetLinks 2.20 依赖。

```sh
mvn -pl :property-quality-service -am verify
mvn -pl :property-repair-service,:property-file-support -am verify
```

品质服务只能装配品质 manager。基础档案、报修通过 core 命令交互；基础档案为公共前置，转报修时才需要目标报修应用。公司配置也限定于当前租户、当前应用。

## 公共文件服务

文件上传与预览由 Cloud 公共文件服务持有。将 `property-base-core` 和 `property-file-support` 部署到该文件服务进程，由其执行 `AssertPropertyFilesCommand`，品质和报修进程不直接查询本地文件目录或 Cloud 数据库。

使用 Spring Boot PropertiesLauncher 的现有文件服务可通过 `LOADER_PATH=/application/property-extensions/` 加载这两个独立 JAR；也可将 `property-file-support` 作为其启动模块依赖。不得同时放入两份不同版本的 base-core，或将 base-manager 放入公共文件服务。更换镜像须保留原运行参数和文件卷。

## Cloud 配置顺序

1. 增量保存 `menus.json`，创建 `template.json`，将 `template-grant.json` 授予品质模板。
2. 在目标区域服务套餐中追加品质菜单及资产策略，保留原应用授权；刷新目标项目订阅快照。
3. 在该项目开通品质应用，配置 PC 独立站点域名，绑定真实应用成员及角色。核查、整改、复验和只读角色分别授予所需动作，资产范围限定在业务项目组织。
4. H5 `/config.json` 填写该应用的 `applicationId`、项目域 `tenantDomain`、`apiBase` 和允许的宿主来源。PC、H5 的同源 `/api` 均代理 Cloud 网关。
5. 在品质应用设置中填写同租户报修 `repairApplicationId`。实际发起人还须为目标报修应用成员，并具备创建工单及相应项目数据权限。

转报修命令采用租户、来源应用和检查记录的唯一键实现幂等；品质与报修不共享数据库事务。请求失败可重试，原核查结果保留。

联动员工的报修角色沿用目标应用当前模板和订阅允许的策略，只配置实际需要的报修工单数据范围。不要将来源应用或管理员角色的基础档案、文件资产策略整份复制给联动角色；基础目录读取继续遵循目标应用现有授权。报修服务离线时，品质接口返回 503；目标成员、动作或数据权限拒绝继续保留原错误。

配置必须通过环境变量或被 Git 忽略的本地文件注入。新服务使用独立数据库、服务身份及命名空间。发布时固定源码提交、依赖 JAR 校验和及镜像 digest，避免本机 SNAPSHOT 缓存漂移。

当前集成基线固定 `hsweb-commons-crud` 为 `5.0.3-20261103.081301-3`，SHA-256 为 `9f2452b38651bcbe39e5c7bc0aff94ee7a360918c968783eaa2dc069a8bf8c8d`。升级此依赖须与 Cloud 运行环境一起验证，不替换本机公共 Maven 缓存来固定发布版本。

## 初次指派权限回归

在专用测试应用中准备项目 A、B，以及仅拥有 A 的品质工单范围和核查动作的员工。设置 `QUALITY_API_URL`、`QUALITY_ACCESS_TOKEN`、`QUALITY_TENANT_DOMAIN`、`QUALITY_APPLICATION_ID`，并将 `QUALITY_TEST_DATA_FILE` 指向数据文件：包含 `projectA`、`projectB`、`inspectorId` 和可创建公司任务的 `task` 表单（模板及条线核查人使用真实测试数据）。令牌由进程环境提供，禁止提交。

```sh
node deploy/quality/tests/assignment-scope.mjs
```

脚本验证 A 的候选人可选、B 的候选人不可选、伪造 B 指派被拒绝，以及失败请求未落库。它依赖真实 Cloud、基础档案、角色授权和品质服务；不能以单纯的动作权限替代品质工单的数据范围。

## 升级及回滚

发布前备份 Cloud、基础档案和报修业务数据库，保留镜像、Compose、环境文件和公共文件卷，检查 dump 可由 `pg_restore -l` 读取。仅增量更新品质接入所需配置和服务。

失败时停止品质容器，恢复原报修和公共文件服务镜像及参数，再恢复本次新增授权差异和入口。镜像回滚不撤销业务写入；数据库恢复必须单独处理新增数据，不覆盖共享库。不要删除既有数据卷。

设备维保抽查延期。旧配置与历史数据不随本应用导入；测试数据通过真实业务接口新建。
