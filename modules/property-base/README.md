# 基础档案模块

`property-base-manager` 承载本领域 Controller、Service、Entity、Repository、权限及国际化，统一由 `property-management-service` 装配。

当前仅提供模块边界。字典、空间、客户档案按设计文档逐项实现；项目列表接口由项目模块提供。

跨模块共享 DTO、命令、事件、SPI 等公共契约出现时，再增加 `property-base-core`；core 不承载 CRUD，不反向依赖 manager。其他领域通过明确公共契约交互，不跨领域直接操作数据库表或注入内部服务。
