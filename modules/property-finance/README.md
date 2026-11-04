# 物业计费模块

业务由统一 property-management-service 装配，使用 JetLinks 2.20 响应式服务、SaaS登录和菜单动作权限。项目业务同时检查 organization 资产；基础数据只通过 property-base-core 的公开命令读取，不依赖基础 manager 或跨表访问。

## 本地与部署初始化

1. 按物业仓库说明配置被忽略的 application-local.yml，启动后由 EasyORM 创建业务表。
2. 在物业服务使用的 PostgreSQL 执行 `../property-base/sql/init-property-dictionaries.sql`，再执行本模块 `sql/init-finance-dictionaries.sql`（UTF-8）。如使用非 public schema，统一替换脚本中的 public.。
3. SQL 只补缺项，可重复执行，不覆盖已有名称、启停、排序和逻辑删除状态。收费来源、支付方式、费用类型来自旧代码真实枚举；欠费等级分类初始为空，应先在字典管理维护实际等级，再维护欠费通知等级配置。
4. 前端初始化 charging-management-ui 菜单和按钮权限，按职责分配计费管理权限及项目组织资产。税号为共享主档，项目通过独立关系使用公司；项目关联不会授予全局公司维护权。

命令行示例（连接参数由部署者提供，密码使用 PG 托管凭据或交互输入）：

```powershell
psql -h <数据库地址> -p <端口> -U <用户名> -d <数据库> -W -v ON_ERROR_STOP=1 -f modules/property-base/sql/init-property-dictionaries.sql
psql -h <数据库地址> -p <端口> -U <用户名> -d <数据库> -W -v ON_ERROR_STOP=1 -f modules/property-finance/sql/init-finance-dictionaries.sql
```

## 接口与引用约定

接口前缀 `/property/finance`。税号公司在 `/companies`；项目范围接口在 `/projects/{projectId}` 下，参数必须显式传递。新增、更新、逻辑删除使用业务入口，更新和删除必须携带当前版本，冲突返回409，不自动覆盖。公司与银行保持稳定ID，财务引用不能通过删除重建绕过。

新增公司并关联通过 `/companies/_create-and-bind/{projectId}` 在一笔事务完成；项目公司详情和候选查询通过项目权限入口，避免要求用户获得共享主档维护权限。每项目最多一个默认公司，先切换默认才允许解除原默认关系。银行和公司被入账规则或税目实际引用时拒绝删除；相关维护持公司锁后再锁从表，查询失败直接抛出，不按“无引用”处理。

税目和收费科目的Excel模板、解析及校验由后端提供，只接受Excel；先全量校验，再事务写入，失败包含行号、字段和原因。关键写入与算费使用MonoTracer。

## 算费任务配置

房屋收费设置保留规则、房屋和客户快照，保存后在同一事务立即出账。自动任务独立于基础数据同步：生成应收00:00、每日计提00:10、账期关闭00:20、逾期计算00:30。关期默认关闭，不影响正常计提。部署到多节点时各节点保持相同业务时区。

可在被忽略的 `application-local.yml` 或部署环境配置中覆盖：

```yaml
property:
  finance:
    billing-job:
      enabled: true
      zone: Asia/Shanghai
      generate-at: "00:00"
      accrue-at: "00:10"
      close-enabled: false
      close-at: "00:20"
      late-fee-at: "00:30"
```

任务记录由EasyORM建表，每设置、任务类型和业务日只有一个持久身份。数据库租约协调多实例；单任务失败回滚其账单，其他任务继续处理。服务重启补跑当天已到点计划，并恢复等待任务及过期执行租约。失败任务在收费设置的“算费任务”中处理后重试，保留原业务日期；接口 `/projects/{projectId}/billing-tasks/_query` 与 `/{id}/_retry` 按项目和空间资产权限校验，分别复用收费设置的查询和生成应收按钮权限。

`org.jetlinks:type=PropertyManagement,name=BillingJob` MBean暴露运行、成功、失败、跳过、最近执行时间和稳定错误键，不暴露客户隐私或绕过权限的业务重试。后台以系统操作者和设置保存的租户执行冻结规则，不使用最近登录用户的上下文。轮询30秒只处理恢复和触发计划，单项超时5分钟、租约10分钟；账期唯一约束和设置行锁防止故障恢复重复出账。

手续费阶梯旧系统存在字段但未持久化、未找到计算消费者；当前配置预览按整笔金额匹配连续区间、百分比费率、保底封顶后四舍五入到分，作为明确补齐的规则，不表示已与支付入账联调。外部服务商目录和开票通道尚未接入时不生成假选项或假成功。
