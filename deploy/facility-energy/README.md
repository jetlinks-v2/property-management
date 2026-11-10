# 设施设备部署

本应用包含独立后端服务、SaaS PC 模块以及共用同一业务后端的员工／业主 H5。设备仅从 Cloud 同步，物业共享组织、人员、空间、房屋和客户档案由基础服务维护。本版没有支付、计费、充值、远程断供、仓库耗材或旧系统历史导入。

## 已有数据库升级

已有设施业务库部署本版前，备份并依次执行 `migrations/20261105-decimal-precision.sql` 与 `migrations/20261105-collection-status.sql`。前者将设备、读数、预警规则及能耗目标的小数列改为 `numeric(44,12)`，保留原整数容量并避免小数取整；后者补齐采集时间、最近成功时间和错误状态列。新库由实体元数据初始化。迁移仅针对设施业务库，不在 Cloud 或基础档案库执行；数值类型调整会取得表锁，应安排维护窗口。

## 制品与运行前置

- 后端：`services/property-facility-energy-service/target/application.jar`，Java 17，服务身份 `property-facility-energy-service`。
- HTTP 8508、集群 18508、RPC 28508；数据库默认 `property_facility_energy`。集群地址需能被 Cloud 和基础服务双向访问。
- PC：相邻 `saas-runtime-ui/modules/facility-energy-ui/dist`，模块名 `facility-energy-ui`。
- H5：该模块的 `mobile-h5/dist`，容器示例绑定本机 9502，由正式 HTTPS 入口代理访问。摄像头、离线资源缓存需安全上下文。
- 必须先具备 Cloud SaaS 网关／运行端、设备公开 SDK、通知服务、共享物业基础服务、PostgreSQL、Redis，以及可信的 RPC token、JWT 公钥和集群发现连接。
- Cloud 设备所属进程须装配 `property-facility-energy-cloud-support` 及 `property-facility-energy-core`，扫描 `org.jetlinks.pro.property.facility.cloud`。它公开 `propertyFacilityCloudService:history/QueryFacilityPropertyPage`，在原认证、租户与资产上下文中调用本地 Cloud 公开设备命令，不读取设备表。当前 Cloud 2.20 的历史查询命令工厂生成的子类 ID 与处理器 ID 不一致，不能直接经原 RPC 工厂查询；此适配器显式保留公开命令 ID、分页条件与响应类型。适配器不得打入设施业务服务。
- Cloud 通知所有者须包含 `notify-manager` 的应用范围上下文修复：`NotificationCommandSupport.assertTargetAssetPermission` 使用当前 `AssetsHolder.currentReactive()` 校验收件人，保留已激活应用范围，不从单独的认证对象重建。修复不绕过收件人资产校验，也不增加发送人权限；部署到通知命令实际所在进程。未包含此修复的旧包可能返回 `asset_scope_context_missing`，仅更新设施服务无法解决。
- 共享基础服务须发布本版 `property-base-core` 客户只读契约与 `PropertyCustomerCommandSupport`；业务服务不装配 `property-base-manager`，不跨库读取共享基础表。
- 设施附件由 `property-facility-energy-file-support` 提供，须与同版本设施 core 装配在实际持有 `FileManager` 的 Cloud 文件进程，扫描 `org.jetlinks.pro.property.facility.file` 并启用 Spring AOP。该适配器提供 `propertyFacilityCloudService:files/AssertFacilityFiles` 公开命令与 `/file/facility/upload?projectId=...` 上传入口；不装进设施或基础档案服务。设备与文件进程分开部署时，分别安装历史适配器和文件适配器，避免把命令注册到错误进程。

先构建独立服务，再准备配置；不会自动创建应用、导入菜单或修改已有环境。

```sh
mvn -pl :property-facility-energy-service,:property-facility-energy-cloud-support,:property-facility-energy-file-support -am verify
mvn -pl :property-base-service,:property-file-support -am verify
cp deploy/facility-energy/.env.example deploy/facility-energy/.env
cp deploy/facility-energy/h5-config.example.json deploy/facility-energy/h5-config.json
```

填写 `.env` 的数据库、Redis、RPC、JWT、集群外部地址、Cloud Docker 网络和网关地址；启用短信时再配置短信回执密钥。示例文件没有可用凭据；`.env`、实际 H5 配置和 `application-local.yml` 已忽略。不要将本机密钥放进共享 YAML、制品或日志。

`DB_POOL_MAX_SIZE` 默认 16，必须纳入共享 PostgreSQL 的连接预算：所有服务连接池上限总和及运维、恢复预留不能超过数据库 `max_connections`。容量验收同时观察连接数和获取连接超时，不能只增加单服务连接池上限。

```sh
docker compose -f deploy/facility-energy/compose.yml --profile web config --quiet
docker compose -f deploy/facility-energy/compose.yml --profile web up -d --build
```

不使用 Docker 时，给独立进程注入对应环境变量，或使用本机配置：

```sh
./deploy/start-service.sh facility-energy /absolute/path/application-local.yml
```

生产网关应将 `/property/facility-energy/**` 转发至本服务，共享 `/property/**` 其余路径转发至基础服务，并保留认证、租户和应用资产范围。服务内置 `route/property-facility-energy.json`，以 `order: -20` 优先于基础服务 `order: -10` 的兜底路由，同时发布受认证保护的接口文档路由。文件上传和下载复用 Cloud `/file/**`。H5 的 `/api` 代理须经过同一网关，不能直接绕过身份链路。

### Cloud 内嵌生产包

设施前端源码对应 `git@github.com:jetlinks-v2/device-energy.git`，在运行端工作区挂载为 `modules/facility-energy-ui`，与 `modules/cloud-patrol-ui` 一起编入 Cloud 宿主。右上角语言选择复用 Cloud；页面在宿主路由中原生渲染。开发服务器验证不能替代以下生产包部署。

在相邻 `saas-runtime-ui` 构建宿主与 H5：

```sh
pnpm -F jetlinks-web-core build -- --module-name cloud-patrol-ui,facility-energy-ui,property-base-ui
cd modules/facility-energy-ui/mobile-h5
npm ci
npm run build
```

宿主输出为运行端根目录 `dist`（多个模块共同构建）；单模块构建的输出为模块自己的 `dist`。正式宿主还需要其他业务模块时，将它们加入同一构建白名单。不要替换正在使用的其他应用宿主或以空白模块覆盖已有菜单。

可以用 `compose.web-artifacts.yml` 部署已构建的只读制品：设置 `CLOUD_WEB_DIST` 为宿主 `dist` 的绝对路径，`H5_WEB_DIST` 为 `mobile-h5/dist` 的绝对路径，填写 `h5-config.json`，并指定 `API_UPSTREAM` 和 `CLOUD_NETWORK`。可用 `WEB_IMAGE` 指定已核验的 Nginx 镜像，`PC_PORT`、`H5_ARTIFACT_PORT` 分别配置监听端口，默认 9508／9509。

```sh
docker compose --env-file deploy/facility-energy/.env \
  -f deploy/facility-energy/compose.web-artifacts.yml config --quiet
docker compose --env-file deploy/facility-energy/.env \
  -f deploy/facility-energy/compose.web-artifacts.yml up -d
```

宿主以 `/<应用 ID>/#/` 进入，通过真实 SaaS 账号和应用授权加载菜单。PC 与 H5 的 `/api` 均由 Nginx 转发到 Cloud 网关；验收需要检查实际数据、全部静态资源、浏览器异常和语言切换后的重新加载。

## 应用、套餐与角色

`menus.json` 是菜单定义；`template.json` 是模板基本信息（code `FacilityEnergy`）；`template-grant.json` 是菜单授权树与可选资产策略，均为待导入定义，不表示已部署。

“配置规则”和“重试失败通知”按钮包含公开通知命令所需的 `notifier:send` 依赖权限；仅配置设施规则权限不足以调用 Cloud 站内通知。现有部署更新菜单、服务订阅和应用模板后，还需重新保存相应角色的按钮授权。收件人的用户资产权限仍由 Cloud 通知命令校验，不需要授予通知器管理权限。

在目标环境备份相关菜单，通过运行端菜单管理导入定义，创建应用模板并保存授权树；再将菜单加入服务套餐，刷新目标项目订阅快照。最后在 Cloud 项目创建应用，应用实例的 `configuration.customDomain` 留空，从 Cloud 项目应用卡片进入，保存角色菜单、按钮及资产策略。PC 导航与当前报事报修保持一致，沿用宿主默认的一级侧栏和内容区下级导航，不要求配置 `navigationMode: tree`。平台接口可参考相邻[报修部署文档](../repair/README.md)；必须合并现有授权，不用空集合覆盖其他菜单。

PC 可见菜单均有真实组件。公共五类基础页面由 `property-base-ui` 提供；设备同步状态和应用操作日志保留原 ID、URL 与权限，提升为一级业务入口，不依赖旧系统管理目录。隐藏的 `facility/Employee`、`facility/Owner` 是 H5 授权节点，不注册 PC 页面，也不作为 PC 导航入口。

| 角色 | 必需权限及边界 |
| --- | --- |
| 应用配置员 | 相应 `facility-*` 配置 query/save/delete；设备同步、来源设备只读和有效项目组织范围；按职责授予基础档案维护权限 |
| 调度员 | `facility-task/query,dispatch,close`；计划、标准、设备／组查询；组织、应用和任务资产范围 |
| 员工 | 隐藏员工节点；`facility-task/query,save`、设施设备／组只读与本人执行所需的资产范围；文件权限。接单与执行实时校验项目所属组织成员关系 |
| 审核员 | `facility-task/query,review`，任务资产范围；任务审核人须与计划指定身份一致 |
| 能源管理员 | 映射、规则、预警、报表权限；来源 `device` 只读资产及 Cloud `device-instance/query`；月度计划 `facilityTarget` 资产 |
| 业主 | 仅隐藏业主节点和应用访问权；不授予通用设施设备、任务或 Cloud 设备管理权限。本人设备访问由有效客户绑定区间决定 |

权限动作控制接口访问，资产策略控制记录范围，不能相互替代。后台作业恢复配置人的当前身份、应用及资产范围；配置人撤权后不能继续越权执行。 设施入口和后台身份恢复均校验当前认证中的真实目标应用成员维度，通过框架 `AssetScopeActivation.isScopeOnly` 排除仅表示请求范围的合成维度；租户拥有应用或旧请求携带应用范围不能替代成员关系。请使用稳定、明确授权的运维账号配置后台规则，并验证其有效期与资产范围。

设施档案的权限由本服务校验；Cloud 设备物模型、历史属性及实时订阅的设备资产权限由 Cloud 公开命令提供方校验，保留原认证、租户和应用上下文。调用方不在设施数据库重复计算 Cloud 设备的资产绑定，也不将提供方的拒绝转换为空数据或成功。

平台网关的令牌转换缓存影响已登录请求的权限生效时间。当前依赖版本在连续访问时最长缓存 60 秒，空闲 10 秒后过期。修改角色或组织后，应核对 Cloud 当前授权与业务接口分别何时生效；后台作业另行验证当前配置人授权，不能用前台缓存窗口代替后台撤权验收。

## 初始业务配置

1. 在公共物业基础管理同步组织、用户和空间，将业务项目组织标注为有效项目，维护员工／客户身份及有效房屋关系。
2. 在 Cloud 将设备分配给业务项目组织或其下级，应用内首次手动同步。之后每小时对账；多项目归属需管理员确认。同步失败或源分页不完整不会据此批量删除档案。
3. 补充设施资料、负责人、空间与附件。CSV 导入通过 Cloud 设备 ID＋档案版本进行稀疏更新，不创建新设备或覆盖来源字段。按需启用巡检、维保和能源功能。
4. 配置设备组和二维码、作业标准、周期计划和任务配置负责人。所有工单由项目所属组织的有效员工接单或被派单，提交后由负责人审核；不再区分单、双工单或配置外协跟进。
5. 为水、电、气仪表从实际物模型选属性，核对单位、倍率、精度和样例。多个累计／增量指标中明确选择一个汇总用量指标；总分表按选定层级报表，避免重复合计。可以复用已有仪表配置，保存时仍按目标设备物模型校验。
6. 选择责任客户及同项目房屋，维护联系人。仅短信联系人不获得 H5 权限；绑定结束或换业主后历史查询与新提醒分别按有效区间控制。
7. 明确设置并启用预警规则，配置 H5 消息及员工站内通知；新规则默认停用。本版保留短信配置和发送记录页面，暂不启用短信。后续启用时再配置通知器及已审核模板。

| 规则 | 配置说明 |
| --- | --- |
| 日／月用量 | 选择累计或增量属性；阈值使用标准化后单位；可配置接近比例和严重阈值 |
| 真实余额／剩余量 | 选择余额属性并设置有效期；过期读数不能触发确定的缴费提醒，延迟发送前再次核验 |
| 状态异常 | 选择状态指标，配置触发状态文本；计数阈值通常设为 1 |
| 读数异常 | 无效读数和累计倒退的数量；计数阈值通常设为 1 |
| 离线／逾期／即将逾期 | 离线为 0／1，任务按数量；即将逾期还需提前提醒秒数；阈值由管理员明确配置 |
| 月度能耗计划 | 先在统计报表维护项目、能源类型及月份目标；规则阈值按执行百分比（例如 100）配置。指定接收员工，仅向该员工发送站内信及可选短信，不向任意业主发送项目汇总。目标为 0、无有效用量或单位不兼容时不生成虚构执行率 |

累计量缺少周期边界读数时标记不完整，不插值生成收费依据。已观测到的有效用量足以超过月目标时仍可发计划预警。数据过多时返回明确上限错误，不能把截断数据当完整统计；报表默认按上海时区。

## H5 与离线

填写 `h5-config.json` 的 `applicationId`、Cloud 项目编码 `tenantDomain`、物业联系电话，`apiBase` 保持同源 `/api`。同一 H5 根据实际权限展示员工或业主入口。可以独立登录，也可由配置在 `allowedHostOrigins` 的受信宿主通过 nonce 握手交接会话；不要把 token 放在 URL。

员工必须在线接单、下载任务和指导书附件后离线执行。缓存按后端确认的租户、应用与用户隔离，照片先保存在本地，联网补传后用稳定提交键提交。任务已撤回、转派或权限失效时保留本地内容并显示冲突，不能覆盖服务器状态。离线身份有有效期，退出后不能复用旧登录权限。

PC 与 H5 的附件必须通过设施专用文件入口上传，项目来自当前档案、作业标准或下载的任务。文件所有者在持久化前写入租户、应用和项目范围，保留平台上传类型权限；响应只返回文件 ID、名称和大小。通用文件地址、缩略图及压缩下载均在文件 SPI 校验当前应用、租户与 AssetsHolder 组织读权限，知道文件 ID 或访问密钥也不能跨范围读取。业务关联附件时另校验文件属于该业务项目；未受保护的通用上传文件不能直接关联设施记录。其他应用没有设施标记的文件沿用平台行为。首次上线应使用专用入口生成验收附件，不能沿用调试时公开上传的文件。

业主只查询本人有效绑定期间的仪表和消息。历史绑定可查询本人的历史区间；当前业主不能查看前业主记录。H5 提供物业联系方式和缴费提醒，不提供支付。

## 短信与回执

本版按用户确认保留短信配置和发送记录页面，暂不接入短信服务、不发送验收短信，真实短信受理与送达不作为本轮验收条件。H5 消息和员工站内信仍需实际验证。以下契约用于后续启用短信。

启用时配置实际可用的 Cloud 短信通知器、服务商签名和已审核模板。发送变量包含 `telephone`、`phone`、`message`；通知任务 ID 作为 `bizId`，`bizType` 为 `facility-energy`。应由通知服务适配提供商字段，不能假定所有服务商的原始回调格式相同。

短信状态严格区分 `pending`、`sending`、`submitted`（受理）、`delivered`（送达）、`failed` 和 `uncertain`（结果待核对）。明确失败可重试；网络超时导致结果未知时停止自动重发，先核对服务商记录，避免重复短信。

回执适配器调用 `POST /property/facility-energy/callbacks/sms`，传入：

```json
{"notificationId":"OUTBOX_ID","notifierId":"NOTIFIER_ID","sendId":"PROVIDER_CALL_ID","receiver":"RECIPIENT","delivered":true,"reason":""}
```

`X-Receipt-Timestamp` 为毫秒时间戳，允许时差 5 分钟；`X-Receipt-Signature` 为 `HMAC-SHA256(secret, timestamp + "\n" + 原始正文)` 的十六进制值。密钥通过 `FACILITY_SMS_RECEIPT_SECRET` 配置，至少 32 字符。回执同时匹配通知 ID、通知器、提供商消息标识及收件人，网关不可改写签名正文。该路由由签名认证，其余业务路由仍须 SaaS 认证。

通知发送前的设备、规则或收件人资格失效会记录 `eligibility_permission_denied` 并取消；Cloud 提供方拒绝发送会记录 `delivery_permission_denied` 并标记失败，修正配置后可人工重试，不自动反复调用拒绝的发送命令。成功重试会清除上一条失败原因。

后续启用短信时，页面显示受理不能代替真实送达，须使用批准的测试联系人完成实际发送、失败回执、重试及送达验收。未配置或未发送的短信不能记为成功。

## 运行观测与验收

后台每 30 秒轮询，设备同步按每小时到期执行。同步状态页（`POST /property/facility-energy/devices/sync/_query`）、通知任务页和操作日志用于定位积压、失败和最近成功；JMX `org.jetlinks:type=PropertyFacilityEnergy,name=Jobs` 提供轮询状态、最近完成时间、累计失败／采集／同步及活动订阅数。`/property/facility-energy/jobs` 是链路追踪名称，不是 HTTP 查询接口。单次轮询有处理上限，部署前以目标设备规模验证积压、查询上限与恢复耗时。

同步状态与操作日志按公共基础目录返回的授权业务项目查询。组织资产的读权限和租户归属由公共目录的 AssetsHolder 校验，本服务在分页前将授权项目与当前租户、应用及未删除记录取交集，不在独立业务库展开 Cloud 组织资产 SQL。授权目录为空时返回空记录，目录调用失败时返回错误，不退回应用全量查询。

上线场景及本地验证覆盖见 [验收清单](ACCEPTANCE.md)。本地测试、构建和模拟浏览器检查不构成真实 Cloud 设备、生产账号、网关、短信或部署验收。

公共基础前端接入和存量菜单迁移见 [公共基础管理部署](../property-base/README.md)。设施模板只保存业务菜单，公共基础定义由宿主统一初始化，各应用按职责引用并授权。不因移除旧目录递归删除设施同步和日志入口。
