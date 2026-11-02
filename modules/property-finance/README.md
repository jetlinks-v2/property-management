# 财务收费模块

`property-finance-manager` 承载本领域 Controller、Service、Entity、Repository、权限及国际化，统一由 `property-management-service` 装配。

当前仅提供模块边界。业务功能按后续确认的设计实现。

跨模块共享 DTO、命令、事件、SPI 等公共契约出现时，再增加 `property-finance-core`；core 不承载 CRUD，不反向依赖 manager。其他领域通过明确公共契约交互，不跨领域直接操作数据库表或注入内部服务。
