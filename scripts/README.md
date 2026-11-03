# 本地初始化与空间类型同步

以下 SQL 针对 PostgreSQL `jetlinks` 数据库的 `public` schema，可重复执行：

- `sql/init-property-dictionaries.sql`：补充房屋类型、房态、客户性质、证件类型，共 4 类 16 项；已有编码及其启停、删除状态不会被覆盖。
- `sql/upgrade-space-type.sql`：为已有空间镜像补充类型 ID、类型名称及带类型的位置字段，不修改房屋扩展资料。

空间及空间类型分别通过 SaaS 的 `spaceService:space` 和 `spaceService:space-type` 命令读取。同步保留 SaaS 的真实层级，类型只用于展示，不用于判断能否完善房屋资料；只有来源真实末端可以完善。

首次启用空间类型同步，需要更新 SaaS 运行包。先在 `cloud.jetlinks/modules/jetlinks-space` 构建安装 `space-core`，再在 `cloud.jetlinks/runtime/all-in-one` 打包运行服务。若本机未安装 AI 模块依赖，可使用以下打包命令：

```powershell
mvn -o '-P!ai-agent' '-DskipTests' package
```

随后在 `D:\Work\saas\cloud.jetlinks\runtime\all-in-one` 更新 Docker 应用容器：

```powershell
docker build -t jetlinks-all-in-one:2.20.0-SNAPSHOT .
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --force-recreate --no-deps all-in-one-service
```

最后重启 IDEA 中的物业后端，并在页面点击同步空间。已有镜像会补上类型及带类型的位置；若 SaaS 尚未提供空间类型命令，本轮同步明确报错并保留旧镜像，不会清空已有数据。

Excel 导入模板按页面当前项目生成，只接受 Excel 文件，最大 10MB。房屋模板预填项目中待完善的末端空间；客户模板按个人、企业分别提供，包含完整空间路径、与新增一致的资料字段及下拉选项，附真实空间、字典及客户账号参考。导入不采用文件中的项目列切换项目，先完成全批校验再提交。
