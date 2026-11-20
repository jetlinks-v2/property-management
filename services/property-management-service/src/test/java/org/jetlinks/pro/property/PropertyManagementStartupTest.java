package org.jetlinks.pro.property;

import io.r2dbc.spi.ConnectionFactory;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeChildrenAssetsAccessSupport;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeParentAssetsAccessSupport;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.QueryPropertyUsersCommand;
import org.jetlinks.pro.property.base.command.QueryPropertyDictionaryOptionsCommand;
import org.jetlinks.pro.property.finance.command.QueryMeterStandardsCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;

import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 真实统一上下文及隔离 PostgreSQL/Redis；不访问本机业务库或 SaaS，也不替换业务 Bean。 */
@SpringBootTest(classes = PropertyManagementApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class PropertyManagementStartupTest {
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:16-alpine")
            .withEnv("POSTGRES_PASSWORD", UUID.randomUUID().toString())
            .withEnv("POSTGRES_DB", "property_unified").withExposedPorts(5432);
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry registry) throws Exception {
        POSTGRES.start();
        REDIS.start();
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/property_unified");
        registry.add("spring.r2dbc.username", () -> "postgres");
        registry.add("spring.r2dbc.password", () -> POSTGRES.getEnvMap().get("POSTGRES_PASSWORD"));
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.data.redis.database", () -> 0);
        registry.add("easyorm.default-schema", () -> "public");
        registry.add("jetlinks.cluster.port", () -> 0);
        registry.add("jetlinks.cluster.rpc-port", () -> 0);
        registry.add("jetlinks.cluster.seeds", () -> "127.0.0.1:9");
        var token = "isolated-" + UUID.randomUUID();
        registry.add("jetlinks.cluster.rpc-token", () -> token);
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var key = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        registry.add("jetlinks.microservice.token.jwt.decode-key", () -> key);
    }

    @Autowired ApplicationContext context;
    @Autowired DatabaseClient database;
    @Autowired WebTestClient web;

    @Test
    void eightModulesUseOneDatabaseAndPublishTheirOriginalProtectedApis() {
        assertEquals(1, context.getBeansOfType(ConnectionFactory.class).size());
        assertEquals(1, context.getBeansOfType(OrgIncludeParentAssetsAccessSupport.class).size());
        assertEquals(1, context.getBeansOfType(OrgIncludeChildrenAssetsAccessSupport.class).size());
        var tables = database.sql("select table_name from information_schema.tables where table_schema='public'")
                .map((row, metadata) -> row.get("table_name", String.class)).all().collectList()
                .block(Duration.ofSeconds(20));
        assertNotNull(tables);
        for (var table : List.of("pm_organization", "pm_fin_house_setting", "pm_fin_standard", "fe_standard",
                "pd_application", "pt_task", "sp_repair_order", "pq_task", "fe_device", "sp_handover_problem")) {
            assertTrue(tables.contains(table), table);
        }
        web.get().uri("/property/organization/_tree").exchange().expectStatus().isUnauthorized();
        web.get().uri("/property/handover/problem/_query").exchange().expectStatus().isUnauthorized();
        for (var path : List.of("/property/finance/subjects/_query", "/property/facility-energy/tasks/_query",
                "/decoration/applications/_query", "/park/patrol/points/_query",
                "/repair/orders/_query", "/quality/tasks/_query")) {
            web.post().uri(path).bodyValue(java.util.Map.of()).exchange().expectStatus().isUnauthorized();
        }
    }

    @Test
    void baseAndFinanceCommandsRemainDiscoverableInsideTheUnifiedProcess() {
        for (var service : List.of(QueryPropertyUsersCommand.SERVICE_ID,
                QueryPropertyDictionaryOptionsCommand.SERVICE_ID, QueryMeterStandardsCommand.SERVICE_ID)) {
            assertNotNull(CommandSupportManagerProviders.getCommandSupport(service)
                    .block(Duration.ofSeconds(10)), service);
        }
    }
}
