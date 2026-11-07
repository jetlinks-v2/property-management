package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.hswebframework.web.crud.configuration.*;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.tax.TaxItemInput;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.*;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/** 在真实PostgreSQL验证共享税目、项目范围、CAS、唯一性和导入事务，隔离开发业务库。 */
@SpringBootTest(
        classes = TaxItemPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TaxItemPersistenceTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final GenericContainer<?> DB =
            new GenericContainer<>("postgres:16-alpine")
                    .withEnv("POSTGRES_PASSWORD", "test-password")
                    .withEnv("POSTGRES_DB", "tax_test")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.start();
        registry.add(
                "spring.r2dbc.url",
                () -> "r2dbc:postgresql://127.0.0.1:" + DB.getMappedPort(5432) + "/tax_test");
        registry.add("spring.r2dbc.username", () -> "postgres");
        registry.add("spring.r2dbc.password", () -> "test-password");
        registry.add("easyorm.dialect", () -> "postgres");
        registry.add("easyorm.default-schema", () -> "public");
    }

    @Autowired TaxItemService service;
    @Autowired TaxImportService imports;
    @Autowired InvoiceCompanyService companies;
    @Autowired ProjectCompanyService projects;
    @Autowired ReactiveRepository<TaxItemEntity, String> items;
    @Autowired ReactiveRepository<TaxOperationEntity, String> operations;
    @Autowired ReactiveRepository<TaxImportEntity, String> batches;
    @Autowired ReactiveRepository<FinanceProjectCompanyEntity, String> links;
    String companyId;

    @BeforeEach
    void setup() {
        companyId =
                companies
                        .create(
                                InvoiceCompanyPersistenceTest.input(
                                        "TAX"
                                                + java.util
                                                        .UUID
                                                        .randomUUID()
                                                        .toString()
                                                        .replace("-", "")))
                        .contextWrite(InvoiceCompanyPersistenceTest.auth())
                        .block(WAIT)
                        .company()
                        .getId();
        projects.bind("project-a", companyId)
                .contextWrite(InvoiceCompanyPersistenceTest.auth())
                .block(WAIT);
    }

    @Test
    void companySharingAndNestedQueryCannotLeakToUnrelatedProject() {
        var saved = create("物业服务", "301");
        projects.bind("project-b", companyId)
                .contextWrite(InvoiceCompanyPersistenceTest.auth())
                .block(WAIT);
        assertEquals(
                saved.getId(),
                service.detail("project-b", saved.getId())
                        .contextWrite(InvoiceCompanyPersistenceTest.auth())
                        .block(WAIT)
                        .getId());
        var query = new QueryParamEntity();
        query.or("companyId", "eq", companyId);
        assertEquals(
                0,
                service.query("project-other", query)
                        .contextWrite(InvoiceCompanyPersistenceTest.auth())
                        .block(WAIT)
                        .getTotal());
        StepVerifier.create(
                        service.detail("project-other", saved.getId())
                                .contextWrite(InvoiceCompanyPersistenceTest.auth()))
                .expectErrorMatches(failure -> status(failure, 403))
                .verify(WAIT);
        StepVerifier.create(service.query("project-a", query))
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(WAIT);
    }

    @Test
    void concurrentDuplicateCreateKeepsOneTaxAndOneAuditRecord() {
        var count = items.createQuery().count().block(WAIT);
        var results =
                Flux.merge(
                                service.create("project-a", input(" Shared ", "ab1")).materialize(),
                                service.create("project-a", input("shared", "AB1")).materialize())
                        .contextWrite(InvoiceCompanyPersistenceTest.auth())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, results.stream().filter(signal -> signal.isOnNext()).count());
        assertEquals(
                1, results.stream().filter(signal -> status(signal.getThrowable(), 409)).count());
        assertEquals(count + 1, items.createQuery().count().block(WAIT));
        var tax = items.createQuery().where("companyId", companyId).fetchOne().block(WAIT);
        assertEquals("AB1", tax.getGoodsCode());
        assertEquals(
                1, operations.createQuery().where("taxItemId", tax.getId()).count().block(WAIT));
    }

    @Test
    void concurrentVersionedUpdatesAllowOnlyOneAndCannotSilentlyOverwrite() {
        var tax = create("初始名称", "301");
        var first = input("修改甲", "301");
        var second = input("修改乙", "301");
        first.setVersion(0L);
        second.setVersion(0L);
        var results =
                Flux.merge(
                                service.update("project-a", tax.getId(), first).materialize(),
                                service.update("project-a", tax.getId(), second).materialize())
                        .contextWrite(InvoiceCompanyPersistenceTest.auth())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, results.stream().filter(signal -> signal.isOnNext()).count());
        assertEquals(
                1, results.stream().filter(signal -> status(signal.getThrowable(), 409)).count());
        assertEquals(1L, items.findById(tax.getId()).block(WAIT).getVersion());
        assertEquals(
                2, operations.createQuery().where("taxItemId", tax.getId()).count().block(WAIT));
    }

    @Test
    void batchDeleteRollsBackAllRowsOnStaleVersionAndLogicalDeleteKeepsHistory() {
        var a = create("甲", "301");
        var b = create("乙", "302");
        StepVerifier.create(
                        service.deleteBatch(
                                        "project-a",
                                        List.of(
                                                new TaxItemService.VersionedId(a.getId(), 0L),
                                                new TaxItemService.VersionedId(b.getId(), 9L)))
                                .contextWrite(InvoiceCompanyPersistenceTest.auth()))
                .expectErrorMatches(failure -> status(failure, 409))
                .verify(WAIT);
        assertFalse(items.findById(a.getId()).block(WAIT).getDeleted());
        service.delete("project-a", a.getId(), 0L)
                .contextWrite(InvoiceCompanyPersistenceTest.auth())
                .block(WAIT);
        assertTrue(items.findById(a.getId()).block(WAIT).getDeleted());
        var query = new QueryParamEntity();
        query.or("deleted", "eq", true);
        assertTrue(
                service
                        .query("project-a", query)
                        .contextWrite(InvoiceCompanyPersistenceTest.auth())
                        .block(WAIT)
                        .getData()
                        .stream()
                        .noneMatch(row -> row.getId().equals(a.getId())));
        assertEquals(
                2,
                service.logs("project-a", a.getId(), new QueryParamEntity())
                        .contextWrite(InvoiceCompanyPersistenceTest.auth())
                        .block(WAIT)
                        .getTotal());
        StepVerifier.create(
                        service.create("project-a", input("甲", "301"))
                                .contextWrite(InvoiceCompanyPersistenceTest.auth()))
                .expectErrorMatches(failure -> status(failure, 409))
                .verify(WAIT);
    }

    @Test
    void priceUsesTwoDecimalsForCreateUpdateAndRejectsExcessPrecisionWithoutWrites() {
        var input = input("两位单价", "PRICE2");
        input.setPrice(new BigDecimal("80.0000000000"));
        var saved = service.create("project-a", input)
                .contextWrite(InvoiceCompanyPersistenceTest.auth()).block(WAIT);
        assertEquals(new BigDecimal("80.00"), saved.getPrice());
        assertEquals(new BigDecimal("80.00"), items.findById(saved.getId()).block(WAIT).getPrice());
        input.setVersion(saved.getVersion());
        input.setPrice(new BigDecimal("100.05"));
        var updated = service.update("project-a", saved.getId(), input)
                .contextWrite(InvoiceCompanyPersistenceTest.auth()).block(WAIT);
        assertEquals(new BigDecimal("100.05"), updated.getPrice());
        input.setVersion(updated.getVersion());
        for (String invalid : List.of("0.001", "80.999", "-0.01", "10000000000000")) {
            input.setPrice(new BigDecimal(invalid));
            StepVerifier.create(service.update("project-a", saved.getId(), input)
                            .contextWrite(InvoiceCompanyPersistenceTest.auth()))
                    .expectErrorMatches(failure -> status(failure, 400)).verify(WAIT);
            StepVerifier.create(service.create("project-a", input)
                            .contextWrite(InvoiceCompanyPersistenceTest.auth()))
                    .expectErrorMatches(failure -> status(failure, 400)).verify(WAIT);
        }
        assertEquals(new BigDecimal("100.05"), items.findById(saved.getId()).block(WAIT).getPrice());
        assertEquals(1, items.createQuery().where("companyId", companyId).count().block(WAIT));
        assertEquals(2, operations.createQuery().where("taxItemId", saved.getId()).count().block(WAIT));
        input.setPrice(null);
        assertNull(service.update("project-a", saved.getId(), input)
                .contextWrite(InvoiceCompanyPersistenceTest.auth()).block(WAIT).getPrice());
    }

    @Test
    void priceMigrationRefusesRoundingAndCanBeRepeatedAfterCorrection() throws Exception {
        var saved = create("迁移单价", "MIGRATE2");
        String script = java.nio.file.Files.readString(
                java.nio.file.Path.of("../sql/migrate-tax-item-price-scale.sql"));
        assertEquals(0, DB.execInContainer("psql", "-U", "postgres", "-d", "tax_test",
                "-v", "ON_ERROR_STOP=1", "-c",
                "ALTER TABLE pm_fin_tax_item ALTER COLUMN price TYPE numeric(23,10);"
                        + "UPDATE pm_fin_tax_item SET price=0.005 WHERE id='" + saved.getId() + "';")
                .getExitCode());
        var rejected = DB.execInContainer("psql", "-U", "postgres", "-d", "tax_test",
                "-v", "ON_ERROR_STOP=1", "-c", script);
        assertNotEquals(0, rejected.getExitCode());
        assertTrue(rejected.getStderr().contains("exceed 2 decimal places"));
        var unchanged = DB.execInContainer("psql", "-U", "postgres", "-d", "tax_test",
                "-Atc", "SELECT price FROM pm_fin_tax_item WHERE id='" + saved.getId() + "';");
        assertEquals(0, unchanged.getExitCode());
        assertEquals(0, new BigDecimal("0.005").compareTo(
                new BigDecimal(unchanged.getStdout().trim())));
        assertEquals(0, DB.execInContainer("psql", "-U", "postgres", "-d", "tax_test",
                "-v", "ON_ERROR_STOP=1", "-c",
                "UPDATE pm_fin_tax_item SET price=0.01 WHERE id='" + saved.getId() + "';").getExitCode());
        for (int i = 0; i < 2; i++) {
            var migrated = DB.execInContainer("psql", "-U", "postgres", "-d", "tax_test",
                    "-v", "ON_ERROR_STOP=1", "-c", script);
            assertEquals(0, migrated.getExitCode(), migrated.getStderr());
        }
        var converted = DB.execInContainer("psql", "-U", "postgres", "-d", "tax_test",
                "-Atc", "SELECT price FROM pm_fin_tax_item WHERE id='" + saved.getId() + "';");
        assertEquals(0, converted.getExitCode());
        assertEquals(new BigDecimal("0.01"), new BigDecimal(converted.getStdout().trim()));
    }

    @Test
    void invalidRatePrecisionPriceAndPolicyNeverPersist() {
        for (var rate : List.of("-0.01", "1.01", "0.0000001")) {
            var input = input("错误税率", "301");
            input.setTaxRate(new BigDecimal(rate));
            StepVerifier.create(
                            service.create("project-a", input)
                                    .contextWrite(InvoiceCompanyPersistenceTest.auth()))
                    .expectErrorMatches(failure -> status(failure, 400))
                    .verify(WAIT);
        }
        var input = input("优惠", "301");
        input.setFavouredPolicyFlag(1);
        StepVerifier.create(
                        service.create("project-a", input)
                                .contextWrite(InvoiceCompanyPersistenceTest.auth()))
                .expectErrorMatches(failure -> status(failure, 400))
                .verify(WAIT);
        assertEquals(0, items.createQuery().where("companyId", companyId).count().block(WAIT));
    }

    @Test
    void httpRequiresVersionAndKeepsFullPricePrecision() {
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web.TaxItemController(
                                        service))
                        .controllerAdvice(new HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange)
                                                .contextWrite(InvoiceCompanyPersistenceTest.auth()))
                        .build();
        var input = input("精确单价", "301");
        input.setPrice(new BigDecimal("1234567890123.12"));
        var tax =
                service.create("project-a", input)
                        .contextWrite(InvoiceCompanyPersistenceTest.auth())
                        .block(WAIT);
        var uri = "/property/finance/projects/project-a/tax-items/" + tax.getId();
        client.get()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.price")
                .isEqualTo("1234567890123.12");
        client.delete().uri(uri).exchange().expectStatus().isBadRequest();
        client.put()
                .uri(uri + "/state")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"enabled\":false}")
                .exchange()
                .expectStatus()
                .isBadRequest();
        var snapshot =
                operations
                        .createQuery()
                        .where("taxItemId", tax.getId())
                        .fetchOne()
                        .block(WAIT)
                        .getAfterSnapshot();
        assertTrue(snapshot.contains("1234567890123.12"));
        assertFalse(snapshot.contains("matchKey"));
        assertFalse(items.findById(tax.getId()).block(WAIT).getDeleted());
        assertTrue(items.findById(tax.getId()).block(WAIT).getEnabled());
    }

    @Test
    void uploadFailurePersistsFailedBatchAndNoPartialTaxRows() throws Exception {
        var bytes = new TaxWorkbookTest().workbook(true, false);
        var file =
                new org.springframework.http.codec.multipart.FilePart() {
                    public String filename() {
                        return "tax.xlsx";
                    }

                    public String name() {
                        return "file";
                    }

                    public org.springframework.http.HttpHeaders headers() {
                        return org.springframework.http.HttpHeaders.EMPTY;
                    }

                    public Flux<org.springframework.core.io.buffer.DataBuffer> content() {
                        return Flux.just(
                                org.springframework.core.io.buffer.DefaultDataBufferFactory
                                        .sharedInstance
                                        .wrap(bytes));
                    }

                    public reactor.core.publisher.Mono<Void> transferTo(java.nio.file.Path target) {
                        return reactor.core.publisher.Mono.error(
                                new UnsupportedOperationException());
                    }
                };
        StepVerifier.create(
                        imports.upload("project-a", companyId, file)
                                .contextWrite(InvoiceCompanyPersistenceTest.auth()))
                .expectErrorMatches(failure -> status(failure, 400))
                .verify(WAIT);
        assertEquals(0, items.createQuery().where("companyId", companyId).count().block(WAIT));
        var record = batches.createQuery().where("companyId", companyId).fetchOne().block(WAIT);
        assertEquals("FAILED", record.getStatus());
        assertEquals(0, record.getSuccessCount());
        assertEquals(2, record.getTotalCount());
        assertEquals(1, record.getErrorCount());
        assertTrue(record.getErrorMessage().contains("3"));
    }

    @Test
    void disabledTaxCannotBeSelectedAndCompanyCannotDeleteReferencedTax() {
        var tax = create("服务费", "301");
        service.state("project-a", tax.getId(), 0L, false)
                .contextWrite(InvoiceCompanyPersistenceTest.auth())
                .block(WAIT);
        StepVerifier.create(
                        service.requireUsable("project-a", companyId, tax.getId())
                                .contextWrite(InvoiceCompanyPersistenceTest.auth()))
                .expectErrorMatches(failure -> status(failure, 409))
                .verify(WAIT);
        // 隔离掉项目关联这条独立阻止条件，证明单独存在共享税目也真实阻止公司删除。
        links.createUpdate()
                .set("deleted", true)
                .setNull("defaultKey")
                .where("companyId", companyId)
                .execute()
                .block(WAIT);
        assertEquals(
                0,
                links.createQuery()
                        .where("companyId", companyId)
                        .and("deleted", false)
                        .count()
                        .block(WAIT));
        StepVerifier.create(
                        companies
                                .delete(companyId, 0)
                                .contextWrite(InvoiceCompanyPersistenceTest.auth()))
                .expectErrorMatches(failure -> status(failure, 409))
                .verify(WAIT);
    }

    @Test
    void importUpdateReEnablesAndFailureRollsBackEarlierRowsAndAudit() {
        var tax = create("导入服务", "301");
        service.state("project-a", tax.getId(), 0L, false)
                .contextWrite(InvoiceCompanyPersistenceTest.auth())
                .block(WAIT);
        var batch = batch();
        assertEquals(
                1,
                imports.apply("project-a", companyId, batch, List.of(input("导入服务", "301")))
                        .contextWrite(InvoiceCompanyPersistenceTest.auth())
                        .block(WAIT)
                        .count());
        assertTrue(items.findById(tax.getId()).block(WAIT).getEnabled());
        var deleted = create("已删除", "303");
        service.delete("project-a", deleted.getId(), 0L)
                .contextWrite(InvoiceCompanyPersistenceTest.auth())
                .block(WAIT);
        var failBatch = batch();
        StepVerifier.create(
                        imports.apply(
                                        "project-a",
                                        companyId,
                                        failBatch,
                                        List.of(input("应回滚", "302"), input("已删除", "303")))
                                .contextWrite(InvoiceCompanyPersistenceTest.auth()))
                .expectErrorMatches(failure -> status(failure, 409))
                .verify(WAIT);
        assertEquals(
                0,
                items.createQuery()
                        .where("companyId", companyId)
                        .and("goodsName", "应回滚")
                        .count()
                        .block(WAIT));
        assertEquals("PROCESSING", batches.findById(failBatch.getId()).block(WAIT).getStatus());
    }

    TaxImportEntity batch() {
        var batch = new TaxImportEntity();
        batch.setId(java.util.UUID.randomUUID().toString());
        batch.setProjectId("project-a");
        batch.setCompanyId(companyId);
        batch.setFileName("tax.xlsx");
        batch.setStatus("PROCESSING");
        batch.setDeleted(false);
        batch.setVersion(0L);
        batches.insert(batch).block(WAIT);
        return batch;
    }

    TaxItemEntity create(String name, String code) {
        return service.create("project-a", input(name, code))
                .contextWrite(InvoiceCompanyPersistenceTest.auth())
                .block(WAIT);
    }

    TaxItemInput input(String name, String code) {
        var input = new TaxItemInput();
        input.setCompanyId(companyId);
        input.setGoodsName(name);
        input.setGoodsCode(code);
        input.setClassificationName("服务");
        input.setTaxRate(new BigDecimal("0.06"));
        return input;
    }

    static boolean status(Throwable failure, int status) {
        return failure instanceof BusinessException ex && ex.getStatus() == status;
    }

    /** 只适配框架业务异常HTTP状态；实际网关包装与授权由宿主完成。 */
    @org.springframework.web.bind.annotation.RestControllerAdvice
    static class HttpErrors {
        @org.springframework.web.bind.annotation.ExceptionHandler(BusinessException.class)
        org.springframework.http.ResponseEntity<String> business(BusinessException failure) {
            return org.springframework.http.ResponseEntity.status(failure.getStatus())
                    .body(failure.getMessage());
        }
    }

    @SpringBootConfiguration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableEasyormRepository("org.jetlinks.pro.property.finance.entity")
    @Import({
        InvoiceCompanyService.class,
        ProjectCompanyService.class,
        TaxItemService.class,
        TaxImportService.class
    })
    @ImportAutoConfiguration({
        EasyormConfiguration.class,
        R2dbcSqlExecutorConfiguration.class,
        R2dbcAutoConfiguration.class,
        R2dbcTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        EntityFactoryConfiguration.class
    })
    static class Config {
        @org.springframework.context.annotation.Bean
        FinanceScope scope() {
            return new FinanceScope() {
                @Override
                public reactor.core.publisher.Mono<
                                org.jetlinks.pro.property.base.organization.PropertyProjectInfo>
                        project(
                                String id,
                                org.jetlinks.pro.assets.crud.CrudAssetPermission permission) {
                    return authenticated()
                            .flatMap(
                                    auth ->
                                            "forbidden".equals(id)
                                                    ? reactor.core.publisher.Mono.error(
                                                            error("project_forbidden", 403))
                                                    : reactor.core.publisher.Mono.just(
                                                            new org.jetlinks.pro.property.base
                                                                    .organization
                                                                    .PropertyProjectInfo(id, id)));
                }
            };
        }
    }
}
