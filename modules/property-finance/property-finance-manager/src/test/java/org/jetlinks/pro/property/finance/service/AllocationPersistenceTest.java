package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.*;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.hswebframework.web.crud.configuration.*;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.organization.PropertyProjectInfo;
import org.jetlinks.pro.property.finance.document.AllocationInput;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.*;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;

import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.*;
import java.util.*;

/** 真PG验证号段唯一性、版本与整批事务；外部字典/用户命令单独隔离。 */
@SpringBootTest(
        classes = AllocationPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AllocationPersistenceTest {
    static final Duration WAIT = Duration.ofSeconds(60);
    static final GenericContainer<?> DB =
            new GenericContainer<>("postgres:16-alpine")
                    .withEnv("POSTGRES_PASSWORD", "test-password")
                    .withEnv("POSTGRES_DB", "allocation_test")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        DB.start();
        r.add(
                "spring.r2dbc.url",
                () ->
                        "r2dbc:postgresql://127.0.0.1:"
                                + DB.getMappedPort(5432)
                                + "/allocation_test");
        r.add("spring.r2dbc.username", () -> "postgres");
        r.add("spring.r2dbc.password", () -> "test-password");
        r.add("easyorm.dialect", () -> "postgres");
        r.add("easyorm.default-schema", () -> "public");
    }

    @Autowired AllocationService service;
    @Autowired ReactiveRepository<AllocationBookEntity, String> books;
    @Autowired ReactiveRepository<AllocationNumberEntity, String> numbers;
    @Autowired ReactiveRepository<AllocationLogEntity, String> logs;
    @Autowired io.r2dbc.spi.ConnectionFactory connectionFactory;

    @BeforeEach
    void clean() {
        logs.deleteById(logs.createQuery().fetch().map(AllocationLogEntity::getId)).block(WAIT);
        numbers.deleteById(numbers.createQuery().fetch().map(AllocationNumberEntity::getId))
                .block(WAIT);
        books.deleteById(books.createQuery().fetch().map(AllocationBookEntity::getId)).block(WAIT);
    }

    static Context auth() {
        var u = new SimpleUser();
        u.setId("operator");
        u.setUsername("操作员");
        var a = new SimpleAuthentication();
        a.setUser(u);
        return Context.of(Authentication.class, a);
    }

    AllocationInput input(long start, int count, int per) {
        return new AllocationInput(
                null,
                LocalDate.of(2024, 2, 1),
                "recipient",
                "1",
                "CODE",
                "1",
                "100.01",
                count,
                per,
                start,
                true,
                false,
                "购票");
    }

    List<AllocationBookEntity> create(String project, AllocationInput... input) {
        return service.create(project, List.of(input)).contextWrite(auth()).block(WAIT);
    }

    AllocationInput edit(AllocationBookEntity row, long start, int per) {
        var i = input(start, 1, per);
        return new AllocationInput(
                row.getVersion(),
                i.buyDate(),
                i.recipientId(),
                i.billType(),
                i.billCode(),
                i.billSource(),
                i.quota(),
                1,
                per,
                start,
                i.enabled(),
                i.recovered(),
                i.remark());
    }

    void rejects(Mono<?> operation, int status) {
        StepVerifier.create(operation.contextWrite(auth()))
                .expectErrorMatches(
                        e -> e instanceof BusinessException b && b.getStatus() == status)
                .verify(WAIT);
    }

    @Test
    void multipleBooksContinuousNumbersAndRealBuyDate() {
        var rows = create("a", input(100, 2, 3), input(200, 1, 2));
        assertEquals(3, rows.size());
        assertEquals(100L, rows.get(0).getStartNumber());
        assertEquals(102L, rows.get(0).getEndNumber());
        assertEquals(103L, rows.get(1).getStartNumber());
        assertEquals(105L, rows.get(1).getEndNumber());
        assertEquals(LocalDate.of(2024, 2, 1), rows.get(0).getBuyDate());
        assertEquals("领用人", rows.get(0).getRecipientName());
        assertEquals(8, numbers.createQuery().count().block(WAIT));
        assertEquals(3, logs.createQuery().count().block(WAIT));
    }

    @Test
    void overlappingBatchRollsBackEverything() {
        rejects(service.create("a", List.of(input(1, 1, 4), input(4, 1, 3))), 409);
        assertEquals(0, books.createQuery().count().block(WAIT));
        assertEquals(0, numbers.createQuery().count().block(WAIT));
        assertEquals(0, logs.createQuery().count().block(WAIT));
    }

    @Test
    void existingRangeConflictAndOtherProjectIndependent() {
        create("a", input(10, 1, 3));
        rejects(service.create("a", List.of(input(12, 1, 3))), 409);
        create("b", input(10, 1, 3));
        assertEquals(2, books.createQuery().count().block(WAIT));
        assertEquals(6, numbers.createQuery().count().block(WAIT));
    }

    @Test
    void concurrentOverlapOnlyOneSuccess() {
        var result =
                Flux.merge(
                                service.create("a", List.of(input(10, 1, 4)))
                                        .map(x -> true)
                                        .onErrorResume(
                                                BusinessException.class,
                                                e -> {
                                                    assertEquals(409, e.getStatus());
                                                    return Mono.just(false);
                                                }),
                                service.create("a", List.of(input(12, 1, 4)))
                                        .map(x -> true)
                                        .onErrorResume(
                                                BusinessException.class,
                                                e -> {
                                                    assertEquals(409, e.getStatus());
                                                    return Mono.just(false);
                                                }))
                        .contextWrite(auth())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, result.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, books.createQuery().count().block(WAIT));
        assertEquals(4, numbers.createQuery().count().block(WAIT));
    }

    @Test
    void numberDiscardRetainedThroughMetadataEditAndVersionConflict() {
        var book = create("a", input(1, 1, 3)).get(0);
        var number =
                service.pool("a", book.getId(), new QueryParamEntity())
                        .contextWrite(auth())
                        .block(WAIT)
                        .getData()
                        .get(0);
        service.numberState(
                        "a",
                        book.getId(),
                        number.getId(),
                        new AllocationService.NumberState(0L, false, "破损"))
                .contextWrite(auth())
                .block(WAIT);
        var current = service.detail("a", book.getId()).contextWrite(auth()).block(WAIT);
        assertEquals(1, current.getDiscardedCount());
        var changed =
                service.update("a", book.getId(), edit(current, 1, 3))
                        .contextWrite(auth())
                        .block(WAIT);
        assertFalse(numbers.findById(number.getId()).block(WAIT).getEnabled());
        assertEquals(1, changed.getDiscardedCount());
        rejects(service.update("a", book.getId(), edit(current, 1, 3)), 409);
        rejects(
                service.numberState(
                        "a",
                        book.getId(),
                        number.getId(),
                        new AllocationService.NumberState(0L, true, "恢复")),
                409);
    }

    @Test
    void rangeEditAndLogicalDeleteRetainHistoryAndReleaseNumbers() {
        var row = create("a", input(1, 1, 3)).get(0);
        var edited =
                service.update("a", row.getId(), edit(row, 20, 2)).contextWrite(auth()).block(WAIT);
        assertEquals(5, numbers.createQuery().count().block(WAIT));
        assertEquals(2, numbers.createQuery().where("deleted", false).count().block(WAIT));
        create("a", input(1, 1, 3));
        service.delete("a", row.getId(), edited.getVersion()).contextWrite(auth()).block(WAIT);
        assertTrue(books.findById(row.getId()).block(WAIT).getDeleted());
        assertEquals(
                0,
                numbers.createQuery()
                        .where("bookId", row.getId())
                        .and("deleted", false)
                        .count()
                        .block(WAIT));
        rejects(service.detail("a", row.getId()), 404);
        create("a", input(20, 1, 2));
    }

    @Test
    void usedNumberBlocksEditDeleteAndDiscard() {
        var row = create("a", input(1, 1, 2)).get(0);
        var number =
                service.pool("a", row.getId(), new QueryParamEntity())
                        .contextWrite(auth())
                        .block(WAIT)
                        .getData()
                        .get(0);
        numbers.createUpdate().set("used", true).where("id", number.getId()).execute().block(WAIT);
        rejects(service.update("a", row.getId(), edit(row, 10, 2)), 409);
        rejects(service.delete("a", row.getId(), 0), 409);
        rejects(
                service.numberState(
                        "a",
                        row.getId(),
                        number.getId(),
                        new AllocationService.NumberState(0L, false, "废弃")),
                409);
        assertFalse(books.findById(row.getId()).block(WAIT).getDeleted());
    }

    @Test
    void projectTenantAndForcedPagingCannotLeak() {
        var row = create("a", input(1, 2, 3)).get(0);
        create("b", input(50, 1, 3));
        var q = new QueryParamEntity();
        q.setPaging(false);
        q.setPageSize(1);
        var page = service.query("a", q).contextWrite(auth()).block(WAIT);
        assertEquals(2, page.getTotal());
        assertEquals(1, page.getData().size());
        assertTrue(page.getData().stream().allMatch(x -> x.getProjectId().equals("a")));
        q.or("projectId", "eq", "b");
        assertEquals(0, service.query("a", q).contextWrite(auth()).block(WAIT).getTotal());
        rejects(service.detail("b", row.getId()), 404);
        rejects(service.detail("forbidden", row.getId()), 403);
        StepVerifier.create(service.query("a", q)).expectError().verify(WAIT);
        assertEquals(
                0,
                service.query("a", new QueryParamEntity())
                        .contextWrite(auth())
                        .contextWrite(TenantHolder.makeCurrent("other"))
                        .block(WAIT)
                        .getTotal());
    }

    @Test
    void allReferencesValidatedBeforeWriting() {
        rejects(
                service.create(
                        "a",
                        List.of(
                                input(1, 1, 2),
                                new AllocationInput(
                                        null,
                                        LocalDate.now(),
                                        "missing",
                                        "1",
                                        "CODE",
                                        "1",
                                        null,
                                        1,
                                        2,
                                        4L,
                                        true,
                                        false,
                                        null))),
                400);
        assertEquals(0, books.createQuery().count().block(WAIT));
        var i = input(1, 1, 1);
        rejects(
                service.create(
                        "a",
                        List.of(
                                new AllocationInput(
                                        null,
                                        i.buyDate(),
                                        i.recipientId(),
                                        "missing",
                                        i.billCode(),
                                        i.billSource(),
                                        null,
                                        1,
                                        1,
                                        1L,
                                        true,
                                        false,
                                        null))),
                400);
    }

    @Test
    void malformedRangeQuotaAndBatchLimitsRejected() {
        rejects(service.create("a", List.of(input(999999999999L, 1, 2))), 400);
        rejects(service.create("a", List.of(input(0, 1, 2))), 400);
        rejects(service.create("a", List.of(input(1, 100, 2))), 400);
        rejects(service.create("a", List.of(input(1, 1, 10000))), 400);
        var i = input(1, 1, 1);
        rejects(
                service.create(
                        "a",
                        List.of(
                                new AllocationInput(
                                        null,
                                        i.buyDate(),
                                        i.recipientId(),
                                        "1",
                                        "CODE",
                                        "1",
                                        "1.001",
                                        1,
                                        1,
                                        1L,
                                        true,
                                        false,
                                        null))),
                400);
    }

    @Test
    void crossChunkNumbersAreNotTruncated() {
        var rows = create("a", input(1, 3, 3334));
        assertEquals(10002, numbers.createQuery().where("deleted", false).count().block(WAIT));
        assertEquals(10002L, rows.get(2).getEndNumber());
        assertEquals(
                3334,
                service.pool("a", rows.get(2).getId(), new QueryParamEntity())
                        .contextWrite(auth())
                        .block(WAIT)
                        .getTotal());
    }

    @Test
    void numberStateAuditFailureRollsBackNumberBookAndLog() throws Exception {
        var book = create("a", input(1, 1, 2)).get(0);
        var number =
                service.pool("a", book.getId(), new QueryParamEntity())
                        .contextWrite(auth())
                        .block(WAIT)
                        .getData()
                        .get(0);
        var constraint = "alloc_log_" + UUID.randomUUID().toString().replace("-", "");
        assertEquals(
                0,
                DB.execInContainer(
                                "psql",
                                "-U",
                                "postgres",
                                "-d",
                                "allocation_test",
                                "-c",
                                "ALTER TABLE pm_fin_allocation_log ADD CONSTRAINT "
                                        + constraint
                                        + " CHECK (action <> 'NUMBER_DISCARD')")
                        .getExitCode());
        try {
            StepVerifier.create(
                            service.numberState(
                                            "a",
                                            book.getId(),
                                            number.getId(),
                                            new AllocationService.NumberState(0L, false, "破损"))
                                    .contextWrite(auth()))
                    .expectError()
                    .verify(WAIT);
        } finally {
            DB.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "allocation_test",
                    "-c",
                    "ALTER TABLE pm_fin_allocation_log DROP CONSTRAINT " + constraint);
        }
        assertTrue(numbers.findById(number.getId()).block(WAIT).getEnabled());
        assertEquals(0L, numbers.findById(number.getId()).block(WAIT).getVersion());
        var after = books.findById(book.getId()).block(WAIT);
        assertEquals(0L, after.getVersion());
        assertEquals(0, after.getDiscardedCount());
        assertEquals(1, logs.createQuery().count().block(WAIT));
    }

    @Test
    void finalCreateAuditFailureRollsBackEarlierBooks() throws Exception {
        var constraint = "alloc_log_" + UUID.randomUUID().toString().replace("-", "");
        assertEquals(
                0,
                DB.execInContainer(
                                "psql",
                                "-U",
                                "postgres",
                                "-d",
                                "allocation_test",
                                "-c",
                                "ALTER TABLE pm_fin_allocation_log ADD CONSTRAINT "
                                        + constraint
                                        + " CHECK (after_snapshot NOT LIKE '%FAIL%')")
                        .getExitCode());
        var i = input(100, 1, 2);
        var fail =
                new AllocationInput(
                        null,
                        i.buyDate(),
                        i.recipientId(),
                        i.billType(),
                        "FAIL",
                        i.billSource(),
                        null,
                        1,
                        2,
                        100L,
                        true,
                        false,
                        null);
        try {
            StepVerifier.create(
                            service.create("a", List.of(input(1, 1, 2), fail)).contextWrite(auth()))
                    .expectError()
                    .verify(WAIT);
        } finally {
            DB.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "allocation_test",
                    "-c",
                    "ALTER TABLE pm_fin_allocation_log DROP CONSTRAINT " + constraint);
        }
        assertEquals(0, books.createQuery().count().block(WAIT));
        assertEquals(0, numbers.createQuery().count().block(WAIT));
        assertEquals(0, logs.createQuery().count().block(WAIT));
    }

    @Test
    void disabledAndRecoveredBooksDoNotReviveDiscardedNumbers() {
        var row = create("a", input(1, 1, 2)).get(0);
        var number =
                service.pool("a", row.getId(), new QueryParamEntity())
                        .contextWrite(auth())
                        .block(WAIT)
                        .getData()
                        .get(0);
        service.numberState(
                        "a",
                        row.getId(),
                        number.getId(),
                        new AllocationService.NumberState(0L, false, "废弃"))
                .contextWrite(auth())
                .block(WAIT);
        var current = service.detail("a", row.getId()).contextWrite(auth()).block(WAIT);
        var i = edit(current, 1, 2);
        var stopped =
                service.update(
                                "a",
                                row.getId(),
                                new AllocationInput(
                                        i.version(),
                                        i.buyDate(),
                                        i.recipientId(),
                                        i.billType(),
                                        i.billCode(),
                                        i.billSource(),
                                        i.quota(),
                                        1,
                                        2,
                                        1L,
                                        false,
                                        true,
                                        null))
                        .contextWrite(auth())
                        .block(WAIT);
        assertFalse(stopped.getEnabled());
        assertTrue(stopped.getRecovered());
        var restored =
                service.update("a", row.getId(), edit(stopped, 1, 2))
                        .contextWrite(auth())
                        .block(WAIT);
        assertTrue(restored.getEnabled());
        assertEquals(1, restored.getDiscardedCount());
        assertFalse(numbers.findById(number.getId()).block(WAIT).getEnabled());
    }

    @Test
    void concurrentEditAndStateUpdatesRejectStaleVersionAndCrossBookNumber() {
        var book = create("a", input(1, 1, 3)).get(0);
        var other = create("a", input(20, 1, 3)).get(0);
        var number =
                service.pool("a", book.getId(), new QueryParamEntity())
                        .contextWrite(auth())
                        .block(WAIT)
                        .getData()
                        .get(0);
        rejects(
                service.numberState(
                        "a",
                        other.getId(),
                        number.getId(),
                        new AllocationService.NumberState(0L, false, "废弃")),
                404);
        var result =
                Flux.merge(
                                service.update("a", book.getId(), edit(book, 1, 3))
                                        .map(x -> 200)
                                        .onErrorResume(
                                                BusinessException.class,
                                                e -> Mono.just(e.getStatus())),
                                service.update("a", book.getId(), edit(book, 1, 3))
                                        .map(x -> 200)
                                        .onErrorResume(
                                                BusinessException.class,
                                                e -> Mono.just(e.getStatus())))
                        .contextWrite(auth())
                        .collectList()
                        .block(WAIT);
        assertEquals(List.of(200, 409), result.stream().sorted().toList());
    }

    @Test
    void namespaceTypeAndCodeAreIndependentButRangeChangeConflictPreservesOld() {
        var first = create("a", input(1, 1, 2)).get(0);
        var i = input(1, 1, 2);
        create(
                "a",
                new AllocationInput(
                        null,
                        i.buyDate(),
                        i.recipientId(),
                        "2",
                        i.billCode(),
                        i.billSource(),
                        null,
                        1,
                        2,
                        1L,
                        true,
                        false,
                        null));
        create(
                "a",
                new AllocationInput(
                        null,
                        i.buyDate(),
                        i.recipientId(),
                        "1",
                        "OTHER",
                        i.billSource(),
                        null,
                        1,
                        2,
                        1L,
                        true,
                        false,
                        null));
        create("a", input(20, 1, 2));
        rejects(service.update("a", first.getId(), edit(first, 20, 2)), 409);
        assertEquals(1L, books.findById(first.getId()).block(WAIT).getStartNumber());
        assertEquals(
                2,
                numbers.createQuery()
                        .where("bookId", first.getId())
                        .and("deleted", false)
                        .count()
                        .block(WAIT));
    }

    @Test
    void httpUsesProjectRoutesStringMoneyAndIndependentPermissionActions() throws Exception {
        var controller = new org.jetlinks.pro.property.finance.web.AllocationController(service);
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                controller)
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter((e, c) -> c.filter(e).contextWrite(auth()))
                        .build();
        var row = create("a", input(1, 1, 3)).get(0);
        var url = "/property/finance/projects/a/allocations/" + row.getId();
        client.get()
                .uri(url)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.quota")
                .isEqualTo("100.01")
                .jsonPath("$.recipientName")
                .isEqualTo("领用人")
                .jsonPath("$.tenantId")
                .doesNotExist();
        client.get()
                .uri("/property/finance/projects/b/allocations/" + row.getId())
                .exchange()
                .expectStatus()
                .isNotFound();
        client.put()
                .uri(url + "/numbers/missing/_state")
                .bodyValue(new AllocationService.NumberState(0L, false, "废弃"))
                .exchange()
                .expectStatus()
                .isNotFound();
        client.put()
                .uri("/property/finance/projects/a/allocations")
                .bodyValue(Map.of("deleted", true))
                .exchange()
                .expectStatus()
                .isEqualTo(405);
        client.delete().uri(url + "?version=0").exchange().expectStatus().isOk();
        assertTrue(books.findById(row.getId()).block(WAIT).getDeleted());
        var annotations = org.jetlinks.pro.property.finance.web.AllocationController.class;
        assertEquals(
                "property-finance-allocation",
                annotations
                        .getAnnotation(
                                org.hswebframework.web.authorization.annotation.Resource.class)
                        .id());
        var method =
                annotations.getMethod(
                        "state",
                        String.class,
                        String.class,
                        String.class,
                        AllocationService.NumberState.class);
        assertEquals(
                "pool",
                method.getAnnotation(
                                org.hswebframework.web.authorization.annotation.ResourceAction
                                        .class)
                        .id());
    }

    @SpringBootConfiguration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableEasyormRepository("org.jetlinks.pro.property.finance.entity")
    @Import(AllocationService.class)
    @ImportAutoConfiguration({
        EasyormConfiguration.class,
        R2dbcSqlExecutorConfiguration.class,
        R2dbcAutoConfiguration.class,
        R2dbcTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        EntityFactoryConfiguration.class
    })
    static class Config {
        @Bean
        FinanceScope scope() {
            return new FinanceScope() {
                @Override
                public Mono<PropertyProjectInfo> project(String id, CrudAssetPermission p) {
                    return authenticated()
                            .flatMap(
                                    a ->
                                            "forbidden".equals(id)
                                                    ? Mono.error(error("project_forbidden", 403))
                                                    : Mono.just(new PropertyProjectInfo(id, id)));
                }
            };
        }

        @Bean
        AllocationRecipientSource recipients() {
            return new AllocationRecipientSource() {
                @Override
                public Mono<Recipient> require(String id) {
                    return "missing".equals(id)
                            ? Mono.error(error("allocation_recipient_invalid", 400))
                            : Mono.just(new Recipient(id, "recipient", "领用人"));
                }
            };
        }

        @Bean
        RecordedDictionarySource dictionaries() {
            return new RecordedDictionarySource() {
                @Override
                public Mono<Map<String, String>> options(String code) {
                    return Mono.just(
                            Map.of("1", "bill_type".equals(code) ? "电子收据" : "自购", "2", "第二项"));
                }
            };
        }
    }
}
