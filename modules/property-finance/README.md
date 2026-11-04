# 财务收费系统

`property-finance-manager` 承载本系统 Controller、Service、Entity、Repository、权限及国际化，由 `services/property-finance-service` 独立装配、启动和发布。

当前仅提供模块及独立启动骨架，收费业务尚待实现。

业务 CRUD 和权限留在 manager。系统内部确有领域类型或公共契约需求时再增加 core；core 不反向依赖 manager。

禁止依赖、调用其他物业业务系统或读写其数据表。认证、租户、资产授权等直接复用 SaaS 公共组件。

从仓库根目录只构建本系统：

```bash
mvn -pl :property-finance-service -am verify
```
