# 品质核查

品质核查作为独立 Cloud 应用运行，业务由 property-quality-manager 承载，property-quality-service 独立启动，默认 HTTP/Cluster/RPC 为 8507/18507/28507，业务库 property_quality。基础目录只通过 property-base-core 的公开命令读取；报修通过 property-repair-core 公开契约调用。

PC 与员工 H5 位于独立 quality-inspection-ui 仓库，共用同一应用、角色及业务数据。功能覆盖公司/项目配置、模板评分、临时/周期任务、条线核查、草稿、整改复验、统计导出及转报修。维保抽查延期；不迁旧业务历史。

接口以 /quality 开头，使用真实 SaaS 认证、租户、应用和 AssetsHolder 数据权限。公司级配置属于当前租户的当前品质应用；项目级配置和工单使用基础档案的项目组织。

独立验证：`mvn -pl :property-quality-service -am verify`。部署接线与模板位于 deploy/quality，运行态验收证据位于被 Git 忽略的 .agent-runtime/quality-migration。
