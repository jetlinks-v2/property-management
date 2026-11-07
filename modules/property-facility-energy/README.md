# 设施设备能源管理

独立 Cloud 应用，业务由 property-facility-energy-manager 承载，property-facility-energy-service 装配。默认端口8508/18508/28508，库property_facility_energy，接口/property/facility-energy。共享基础数据仅通过property-base-core公开命令访问。

业务设计：[设施设备能源管理系统设计](../../../设计文档/设施设备能源管理系统设计.md)。PC与移动端位于独立facility-energy-ui；部署菜单、应用模板及环境示例位于deploy/facility-energy。

构建：`mvn -pl :property-facility-energy-service -am verify`。不要将base-manager、repair-manager、quality-manager或patrol-manager加入本服务。上线须独立验收SaaS应用、角色资产范围、Cloud设备、短信通知器及回执，不以打包成功代替联调。

### 组织权限查询

设施资产沿用 `AssetsHolder` 的 `org-include-children` 策略。认证服务已经展开有权访问的下级组织；设施服务通过 `FacilityOrganizationAccessSupport` 将这些维度交给标准维度条件构建器，不使用本地 `s_dimension_closure` 优化。组织层级由共享档案和 Cloud 持有，设施库不复制该表数据。这个扩展只改变设施资产的查询生成方式，保留既有组织、创建人、租户及应用权限。其他资产继续使用框架原策略。
