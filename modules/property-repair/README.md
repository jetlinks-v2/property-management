# 报事报修

本模块将原独立报修服务的领域代码迁入物业后端，由独立的 `property-repair-service` 装配，沿用仓库每个业务系统独立启动、发布的边界。Java 包名、`sp_repair_*` 表名、资源权限 ID 和 `/repair/**` 接口保持兼容。

`property-repair-core` 包含报修请求 DTO 与状态。`property-repair-manager` 包含工单、流程配置、审核、通知、导出、二维码和操作日志。目录通过 `RepairDirectory` 消费 `property-base-core` 的公开只读命令，不依赖基础模块 manager，也不直接操作其数据表。

组织类型、用户身份和空间根绑定统一由基础模块维护。报修不再读取或写入 `pp_organization`、`pp_person`、`pp_space`，也不再注册 `/property-data/**`；旧表保留供核对。切换前备份数据库，将既有业务项目标注和员工身份通过基础接口迁入，并按原归属关联根空间，保留工单及配置中的稳定项目、用户、空间 ID 和历史名称。多身份用户仍可同时为客户和员工，去掉员工身份后立即失去报修员工资格。

这是报修读取基础公共目录的明确依赖：必须同时部署 `property-base-service`。工单发起、人员候选、二维码及调度按当前租户、应用和资产范围查询目录；目录服务不可用时明确报错，不回退旧镜像。

## 运行与切换

1. 按仓库根目录说明配置数据库、Redis、RPC token 和 JWT 公钥，连接原报修使用的数据库及 schema 可读取既有报修数据。
2. 默认业务库为 `property_repair`；接管原报修数据时显式指定原数据库及 schema。基础服务独立配置自己的业务库并接入同一 SaaS 集群；财务与巡更无需启动。报修服务发现名及事件/配置命名空间保留 `smart-park-repair-service` / `smart-park-repair`。
3. 执行 `mvn -pl :property-repair-service -am verify`，运行 `services/property-repair-service/target/application.jar`，默认 HTTP / 集群 / RPC 端口为 8506 / 18506 / 28506，避免与既有巡更 8502 冲突。
4. 切换环境时停止旧 `smart-park-repair-service`，再启用基础服务与本仓库报修服务，避免重复注册 `/repair/**` 路由和重复运行报修调度。等待服务发现完成后通过 SaaS 网关验证。空间同步需要共享运行服务提供 `spaceService:space` 和 `spaceService:space-type` 两个只读命令。
5. 原应用模板与权限配置位于 `deploy/repair`。已有环境保留现有模板、订阅快照和角色授权；新环境替换模板中的环境标识后导入并授权。前端通过网关访问，不直连业务服务。

报修服务开启报修所需的访问日志、工单直接资产映射和空间资产表映射；匿名入口仅为 `/repair/public/qr/**`，其余报修和目录接口继续认证。匿名二维码还会在业务层校验配置、范围及上传授权。调度的只读观测保留 `org.jetlinks.pro.smartpark.repair:name=ScheduledJob` MBean；没有另外增加调度实例。

## 前端

业务前端仓库为 [report-saas-ui](https://github.com/jetlinks-v2/report-saas-ui)，PC 模块由 `saas-runtime-ui` 以 `modules/report-repair-ui` 子模块接入，与巡更一起构建并部署到 Cloud 运行端。应用实例 `configuration.customDomain` 留空，无需独立 PC 站点；员工 H5 仍单独发布。模块通过应用模板的 `componentCode` 注册报修业务页面；组织、用户、空间、客户和字典统一由 `property-base-ui` 提供，不维护基础管理页面或菜单副本。公共基础管理授权迁移见 `deploy/property-base/README.md`，迁移时保留既有角色与资产范围。

员工 H5 在业务前端仓库的 `employee-h5/` 独立构建，复用同一网关、应用、工单及权限；部署配置与构建命令见其 README。后端仓库只保留后端模块和应用模板。

## 验证边界

迁入的领域测试覆盖流程、配置、动作表单、权限、二维码和导出等规则；报修服务测试覆盖组件扫描、路由与资产配置及独立装配。构建与测试不能替代目标环境的网关服务发现、登录、模板订阅授权和真实业务联调。

保留原迁移边界：业主独立入口、外部系统联动、完整旧接口分支及数据库 20 万条导出负载仍需后续验收。

报修服务的界面异常与通知资源位于 `property-repair-manager/src/main/resources/i18n/smart-park-repair/`，中文与英文分别维护在 `messages_zh.properties`、`messages_en.properties`，沿用平台请求语言解析。公开二维码表单也发送 `Accept-Language`，不发送登录令牌、租户或应用认证头。菜单与按钮的中英文元数据位于 `deploy/repair`。


## 统计、候选人与导出查询

列表页签使用 `/repair/orders/_status-counts` 轻量计数接口，查询范围仍由工单资产权限注入。汇总统计将派单过程按工单预聚合，分类分布通过层级关联读取首级分类；分类已删除时保留工单分类名称快照。接单及时率查询的 PostgreSQL 索引及回退脚本位于 `deploy/repair/migrations/20261106-accept-timing.*.sql`，须在报修业务库独立执行，不能放入事务。

分派和转派候选接口支持 `search` 与 `limit`，先按姓名或ID筛选，再以受控并发逐人检查目标工单的处理授权；有界请求最多返回50人，未指定限制的既有调用保留全量语义。处置提交仍重新检查目标人员权限。

导出记录按当前租户和应用读取创建人，并通过认证服务的 `authService:user-directory` 命令分批核验组织及用户资产范围，不在报修数据库查询认证表。认证服务不可用时明确失败，空授权生成空查询范围；下载时继续逐批复核文件内工单权限。组织及上下级资产提供者在没有有效组织维度时仅关闭组织分支，避免构造无目标条件而使导出页面报错。
