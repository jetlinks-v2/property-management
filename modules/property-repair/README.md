# 报事报修

本模块将原独立报修服务的领域代码迁入物业后端，由独立的 `property-repair-service` 装配，沿用仓库每个业务系统独立启动、发布的边界。Java 包名、`sp_repair_*` / `pp_*` 表名、资源权限 ID 和 `/repair/**`、`/property-data/**` 接口保持兼容。

`property-repair-core` 包含报修请求 DTO、状态与 Cloud 目录读取契约。`property-repair-manager` 包含工单、流程配置、审核、通知、导出、二维码、操作日志及原报修目录投影和 Cloud 命令适配。目录投影实现与报修同属一个装配边界，不依赖其他业务 manager。

原报修的 `pp_organization`、`pp_person`、`pp_space` 保存业务项目标签、员工/业主标记和空间关联。物业基础模块的 `pm_*` 档案具有不同字段和业务语义，本次不做表合并或历史数据转换。原有报修目录入口继续可用，Cloud 组织、人员、空间仍通过平台命令读取。报修投影的同名 Spring 组件使用 `repair` 前缀，避免覆盖基础模块。

## 运行与切换

1. 按仓库根目录说明配置数据库、Redis、RPC token 和 JWT 公钥，连接原报修使用的数据库及 schema 可读取既有报修数据。
2. 默认业务库为 `property_repair`；接管原报修数据时显式指定原数据库及 schema。其他物业系统无需启动。服务发现名及事件/配置命名空间保留 `smart-park-repair-service` / `smart-park-repair`。
3. 执行 `mvn -pl :property-repair-service -am verify`，运行 `services/property-repair-service/target/application.jar`，默认 HTTP / 集群 / RPC 端口为 8506 / 18506 / 28506，避免与既有巡更 8502 冲突。
4. 切换环境时停止旧 `smart-park-repair-service`，再启用本仓库报修服务，避免重复注册 `/repair/**`、`/property-data/**` 路由和重复运行报修调度。等待服务发现完成后通过 SaaS 网关验证。
5. 原应用模板与权限配置位于 `deploy/repair`。已有环境保留现有模板、订阅快照和角色授权；新环境替换模板中的环境标识后导入并授权。前端通过网关访问，不直连业务服务。

报修服务开启报修所需的访问日志、工单直接资产映射和空间资产表映射；匿名入口仅为 `/repair/public/qr/**`，其余报修和目录接口继续认证。匿名二维码还会在业务层校验配置、范围及上传授权。调度的只读观测保留 `org.jetlinks.pro.smartpark.repair:name=ScheduledJob` MBean；没有另外增加调度实例。

## 前端

业务前端仓库为 [report-saas-ui](https://github.com/jetlinks-v2/report-saas-ui)，分支 `2.20`，PC 模块由 `saas-runtime-ui` 的 `property/2.20-dev` 分支以 `modules/report-repair-ui` 子模块接入。模块仍通过应用模板的 `componentCode` 注册页面；`baseMenu.json` 保持空数组，避免重复创建应用菜单。

员工 H5 在业务前端仓库的 `employee-h5/` 独立构建，复用同一网关、应用、工单及权限；部署配置与构建命令见其 README。后端仓库只保留后端模块和应用模板。

## 验证边界

迁入的领域测试覆盖流程、配置、动作表单、权限、二维码和导出等规则；报修服务测试覆盖组件扫描、路由与资产配置及独立装配。构建与测试不能替代目标环境的网关服务发现、登录、模板订阅授权和真实业务联调。

保留原迁移边界：业主独立入口、外部系统联动、完整旧接口分支及数据库 20 万条导出负载仍需后续验收。
