package org.jetlinks.pro.patrol.service;

import io.r2dbc.h2.CloseableConnectionFactory;
import io.r2dbc.h2.H2ConnectionFactory;
import io.r2dbc.spi.Connection;

import org.hswebframework.ezorm.rdb.mapping.EntityColumnMapping;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.mapping.defaults.DefaultReactiveRepository;
import org.hswebframework.ezorm.rdb.mapping.jpa.JpaEntityTableMetadataParser;
import org.hswebframework.ezorm.rdb.mapping.wrapper.EntityResultWrapper;
import org.hswebframework.ezorm.rdb.metadata.RDBDatabaseMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBSchemaMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.ezorm.rdb.operator.DefaultDatabaseOperator;
import org.hswebframework.ezorm.rdb.supports.h2.H2Dialect;
import org.hswebframework.web.crud.generator.CurrentTimeGenerator;
import org.hswebframework.web.crud.generator.DefaultIdGenerator;
import org.jetlinks.pro.tenant.configuration.TenantAssetsProperties;
import org.jetlinks.pro.tenant.crud.TenantColumnTableCustomizer;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/** 使用真实 ezorm DSL、实体注解和 R2DBC H2，验证游标 CAS 和幂等持久化。 */
final class PatrolDatabase implements AutoCloseable {
    final CloseableConnectionFactory connections =
            H2ConnectionFactory.inMemory("patrol-test-" + UUID.randomUUID());
    private final RDBDatabaseMetadata metadata = new RDBDatabaseMetadata(new H2Dialect());
    private final DefaultDatabaseOperator operator;
    private final Connection keepAlive;
    private final TenantColumnTableCustomizer tenants =
            new TenantColumnTableCustomizer(new TenantAssetsProperties(), java.util.List.of());
    private org.springframework.context.support.GenericApplicationContext context;
    private reactor.core.Disposable tenantProvider;

    PatrolDatabase() {
        keepAlive = Mono.from(connections.create()).block(Duration.ofSeconds(10));
        RDBSchemaMetadata schema = new RDBSchemaMetadata("PUBLIC");
        metadata.addSchema(schema);
        metadata.setCurrentSchema(schema);
        metadata.addFeature(new CurrentTimeGenerator());
        metadata.addFeature(new DefaultIdGenerator());
        metadata.addFeature(new org.jetlinks.pro.assets.impl.terms.DimensionAssetsTermBuilder());
        // 使用生产环境相同的执行器，连接的事务生命周期不由测试重写。
        var executor = new org.hswebframework.web.crud.sql.DefaultR2dbcExecutor();
        executor.setDefaultFactory(connections);
        metadata.addFeature(executor);
        operator = DefaultDatabaseOperator.of(metadata);
    }

    <T> ReactiveRepository<T, String> repository(Class<T> type, Supplier<T> factory) {
        JpaEntityTableMetadataParser parser = new JpaEntityTableMetadataParser();
        parser.setDatabaseMetadata(metadata);
        RDBTableMetadata table = parser.parseTableMetadata(type).orElseThrow();
        // 与生产环境使用同一租户扩展，验证系统同步资产的项目归属可持久化和读取。
        tenants.customTable(type, table);
        new org.jetlinks.pro.patrol.configuration.PatrolJsonMetadataCustomizer(
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules())
                .customTable(type, table);
        // DDL 成功后由框架注册元数据；提前注册会把首次建表误判为无需变更。
        operator.ddl().createOrAlter(table).commit().reactive().block(Duration.ofSeconds(10));
        EntityColumnMapping mapping =
                table.getFeatureList().stream()
                        .filter(EntityColumnMapping.class::isInstance)
                        .map(EntityColumnMapping.class::cast)
                        .findFirst()
                        .orElseThrow();
        mapping.reload();
        return new DefaultReactiveRepository<>(
                operator, table, type, new EntityResultWrapper<>(factory, mapping));
    }

    void enableTenantQueries() {
        context = new org.springframework.context.support.GenericApplicationContext();
        context.registerBean(
                org.hswebframework.ezorm.rdb.operator.DatabaseOperator.class, () -> operator);
        context.refresh();
        tenants.setApplicationContext(context);
        tenants.afterSingletonsInstantiated();
        tenantProvider =
                org.jetlinks.pro.assets.AssetDimensionProvider.supports.register(
                        "tenant", new org.jetlinks.pro.tenant.assets.TenantAssetProvider(tenants));
    }

    DefaultDatabaseOperator operator() {
        return operator;
    }

    @Override
    public void close() {
        if (tenantProvider != null) tenantProvider.dispose();
        if (context != null) context.close();
        Mono.from(keepAlive.close()).block(Duration.ofSeconds(10));
        connections.close().block(Duration.ofSeconds(10));
    }
}
