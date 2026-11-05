package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.hswebframework.web.crud.configuration.*;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.tax.InvoiceCompanyInput;
import org.jetlinks.pro.property.finance.tax.RecordedInfoInput;
import org.jetlinks.pro.property.finance.web.RecordedInfoController;
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
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 独立数据库验证组合、事务、并发与项目隔离；字典源在此只隔离公开命令边界，不替代基础命令测试。 */
@SpringBootTest(
        classes = RecordedInfoPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RecordedInfoPersistenceTest {
    static final Duration WAIT = Duration.ofSeconds(30);
    static final GenericContainer<?> DB =
            new GenericContainer<>("postgres:16-alpine")
                    .withEnv("POSTGRES_PASSWORD", "test-password")
                    .withEnv("POSTGRES_DB", "recorded_test")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.start();
        registry.add(
                "spring.r2dbc.url",
                () -> "r2dbc:postgresql://127.0.0.1:" + DB.getMappedPort(5432) + "/recorded_test");
        registry.add("spring.r2dbc.username", () -> "postgres");
        registry.add("spring.r2dbc.password", () -> "test-password");
        registry.add("easyorm.dialect", () -> "postgres");
        registry.add("easyorm.default-schema", () -> "public");
    }

    @Autowired RecordedInfoService service;
    @Autowired InvoiceCompanyService companies;
    @Autowired ProjectCompanyService projects;
    @Autowired ReactiveRepository<RecordedInfoEntity, String> records;
    @Autowired ReactiveRepository<SettlementRuleEntity, String> rules;
    @Autowired ReactiveRepository<FinanceProjectCompanyEntity, String> links;
    @Autowired ReactiveRepository<FinanceBankEntity, String> banks;
    @Autowired ReactiveRepository<FinanceCompanyEntity, String> companyRows;

    @BeforeEach
    void clean() {
        Config.dictionaryUnavailable = false;
        Config.dictionaryReads.set(0);
        rules.deleteById(rules.createQuery().fetch().map(SettlementRuleEntity::getId)).block(WAIT);
        records.deleteById(records.createQuery().fetch().map(RecordedInfoEntity::getId))
                .block(WAIT);
        links.deleteById(links.createQuery().fetch().map(FinanceProjectCompanyEntity::getId))
                .block(WAIT);
        banks.deleteById(banks.createQuery().fetch().map(FinanceBankEntity::getId)).block(WAIT);
        companyRows
                .deleteById(companyRows.createQuery().fetch().map(FinanceCompanyEntity::getId))
                .block(WAIT);
    }

    @Test
    void cartesianCreationPersistsAllTiersAndReportsSkippedCombinations() {
        var input = bound("A");
        input.setSourceCharges(List.of("1", "2"));
        input.setPatternPayments(List.of("1", "2"));
        var result = service.create("project-a", input).contextWrite(auth()).block(WAIT);
        assertEquals(4, result.created().size());
        assertEquals(0, result.skipped().size());
        assertEquals(8, rules.createQuery().where("deleted", false).count().block(WAIT));
        var second = service.create("project-a", input).contextWrite(auth()).block(WAIT);
        assertEquals(0, second.created().size());
        assertEquals(4, second.skipped().size());
        var detail =
                service.detail("project-a", result.created().get(0).getId())
                        .contextWrite(auth())
                        .block(WAIT);
        assertEquals(2, detail.tiers().size());
        assertEquals(new BigDecimal("2.000000"), detail.tiers().get(1).getProcedureRate());
        assertEquals(
                new BigDecimal("2.00"),
                service.preview("project-a", detail.record().getId(), new BigDecimal("100.00"))
                        .contextWrite(auth())
                        .block(WAIT));
    }

    @Test
    void concurrentDuplicateAndConcurrentUpdatesHaveOneWinnerWithStableVersion() {
        var input = bound("B");
        var created =
                Flux.merge(
                                service.create("project-a", input).materialize(),
                                service.create("project-a", input).materialize())
                        .contextWrite(auth())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, created.stream().filter(signal -> signal.isOnNext()).count());
        assertEquals(1, created.stream().filter(signal -> conflict(signal.getThrowable())).count());
        var row = records.createQuery().fetchOne().block(WAIT);
        input.setVersion(0L);
        var updated =
                Flux.merge(
                                service.update("project-a", row.getId(), input).materialize(),
                                service.update("project-a", row.getId(), input).materialize())
                        .contextWrite(auth())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, updated.stream().filter(signal -> signal.isOnNext()).count());
        assertEquals(1, updated.stream().filter(signal -> conflict(signal.getThrowable())).count());
        assertEquals(1L, records.findById(row.getId()).block(WAIT).getVersion());
        assertEquals(2, rules.createQuery().where("deleted", false).count().block(WAIT));
        assertEquals(2, rules.createQuery().where("deleted", true).count().block(WAIT));
    }

    @Test
    void foreignProjectAndForeignBankAreRejectedAndDeletedIdentityCanBeRecreated() {
        var input = bound("C");
        var other = bound("D");
        var original =
                service.create("project-a", input)
                        .contextWrite(auth())
                        .block(WAIT)
                        .created()
                        .get(0);
        StepVerifier.create(service.detail("project-b", original.getId()).contextWrite(auth()))
                .expectErrorMatches(RecordedInfoPersistenceTest::notFound)
                .verify(WAIT);
        StepVerifier.create(service.delete("project-b", original.getId(), 0).contextWrite(auth()))
                .expectErrorMatches(RecordedInfoPersistenceTest::notFound)
                .verify(WAIT);
        input.setBankId(other.getBankId());
        StepVerifier.create(service.create("project-a", input).contextWrite(auth()))
                .expectErrorMatches(RecordedInfoPersistenceTest::conflict)
                .verify(WAIT);
        assertEquals(1, records.createQuery().count().block(WAIT));
        service.delete("project-a", original.getId(), 0).contextWrite(auth()).block(WAIT);
        assertTrue(records.findById(original.getId()).block(WAIT).getDeleted());
        assertEquals(0, rules.createQuery().where("deleted", false).count().block(WAIT));
        input.setBankId(original.getBankId());
        var restored =
                service.create("project-a", input)
                        .contextWrite(auth())
                        .block(WAIT)
                        .created()
                        .get(0);
        assertNotEquals(original.getId(), restored.getId());
        var query = new QueryParamEntity();
        query.or("projectId", "eq", "project-b").or("deleted", "eq", true);
        assertEquals(
                1,
                service.query("project-a", new QueryParamEntity())
                        .contextWrite(auth())
                        .block(WAIT)
                        .getTotal());
        assertEquals(
                0, service.query("project-a", query).contextWrite(auth()).block(WAIT).getTotal());
        assertEquals(
                0,
                service.query("project-b", new QueryParamEntity())
                        .contextWrite(auth())
                        .block(WAIT)
                        .getTotal());
    }

    @Test
    void databaseFailureOnLaterCombinationRollsBackEveryCreatedParentAndTier() {
        var input = bound("E");
        input.setSourceCharges(List.of("1", "2"));
        // The second source has a deliberately overlong label from the source fixture; the database
        // rejects its parent insert.
        Config.longDictionaryText = true;
        try {
            StepVerifier.create(service.create("project-a", input).contextWrite(auth()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(0, records.createQuery().count().block(WAIT));
            assertEquals(0, rules.createQuery().count().block(WAIT));
        } finally {
            Config.longDictionaryText = false;
        }
    }

    @Test
    void unavailableSourcesAndInvalidTiersFailBeforeAnyWriteAndStaleDeleteKeepsRules() {
        var input = bound("F");
        input.setSourceCharges(List.of("disabled"));
        StepVerifier.create(service.create("project-a", input).contextWrite(auth()))
                .expectError(BusinessException.class)
                .verify(WAIT);
        input.setSourceCharges(List.of("1"));
        input.setFacilitatorId("provider-1");
        StepVerifier.create(service.create("project-a", input).contextWrite(auth()))
                .expectErrorMatches(
                        failure -> failure instanceof BusinessException e && e.getStatus() == 503)
                .verify(WAIT);
        input.setFacilitatorId(null);
        input.setTiers(
                List.of(
                        SettlementRuleServiceTest.tier("0", "100", "1"),
                        SettlementRuleServiceTest.tier("99", null, "2")));
        StepVerifier.create(service.create("project-a", input).contextWrite(auth()))
                .expectError(BusinessException.class)
                .verify(WAIT);
        assertEquals(0, records.createQuery().count().block(WAIT));
        input.setTiers(SettlementRuleServiceTest.percentage().getTiers());
        var row =
                service.create("project-a", input)
                        .contextWrite(auth())
                        .block(WAIT)
                        .created()
                        .get(0);
        StepVerifier.create(service.delete("project-a", row.getId(), 9).contextWrite(auth()))
                .expectErrorMatches(RecordedInfoPersistenceTest::conflict)
                .verify(WAIT);
        assertEquals(2, rules.createQuery().where("deleted", false).count().block(WAIT));
        StepVerifier.create(service.detail("project-a", row.getId()))
                .expectError(UnAuthorizedException.class)
                .verify(WAIT);
        StepVerifier.create(service.create("forbidden", input).contextWrite(auth()))
                .expectError(BusinessException.class)
                .verify(WAIT);
    }

    @Test
    void bankRemovalAndProjectUnbindCannotBreakActiveConfigurationReferences() {
        var input = bound("G");
        var spare = bound("H");
        service.create("project-a", input).contextWrite(auth()).block(WAIT);
        var company = companyInput("G");
        company.setVersion(0L);
        company.setBanks(List.of());
        StepVerifier.create(companies.update(input.getCompanyId(), company).contextWrite(auth()))
                .expectErrorMatches(RecordedInfoPersistenceTest::conflict)
                .verify(WAIT);
        var second =
                links.createQuery().where("companyId", spare.getCompanyId()).fetchOne().block(WAIT);
        projects.setDefault("project-a", second.getId(), second.getVersion())
                .contextWrite(auth())
                .block(WAIT);
        var relation =
                links.createQuery().where("companyId", input.getCompanyId()).fetchOne().block(WAIT);
        StepVerifier.create(
                        projects.unbind("project-a", relation.getId(), relation.getVersion())
                                .contextWrite(auth()))
                .expectErrorMatches(RecordedInfoPersistenceTest::conflict)
                .verify(WAIT);
        assertFalse(banks.findById(input.getBankId()).block(WAIT).getDeleted());
        assertEquals(0L, companyRows.findById(input.getCompanyId()).block(WAIT).getVersion());
    }

    @Test
    void
            optionsContainOnlyCurrentProjectCompaniesAndActiveBanksWithoutOtherMaintenancePermissions() {
        var current = bound("OPTIONS_A");
        var other = companies.create(companyInput("OPTIONS_B")).contextWrite(auth()).block(WAIT);
        projects.bind("project-b", other.company().getId()).contextWrite(auth()).block(WAIT);
        var removed = new FinanceBankEntity();
        removed.setId(UUID.randomUUID().toString());
        removed.setCompanyId(current.getCompanyId());
        removed.setBankName("已删除银行");
        removed.setBankAccount("999");
        removed.setDefaultBank(false);
        removed.setVersion(0L);
        removed.setDeleted(true);
        banks.insert(removed).block(WAIT);
        var options = service.options("project-a").contextWrite(auth()).block(WAIT);
        assertEquals(2, options.sources().size());
        assertEquals(2, options.payments().size());
        assertEquals(1, options.companies().size());
        assertEquals(current.getCompanyId(), options.companies().get(0).id());
        assertEquals(
                List.of(current.getBankId()),
                options.companies().get(0).banks().stream()
                        .map(RecordedInfoService.BankOption::id)
                        .toList());
        assertEquals("123456", options.companies().get(0).banks().get(0).bankAccount());
        assertEquals(
                List.of(other.company().getId()),
                service.options("project-b").contextWrite(auth()).block(WAIT).companies().stream()
                        .map(RecordedInfoService.CompanyOption::id)
                        .toList());
        assertEquals(
                List.of(),
                service.options("project-empty").contextWrite(auth()).block(WAIT).companies());
    }

    @Test
    void optionsRequireProjectReadAndDictionaryFailureIsNotAnEmptySuccess() {
        StepVerifier.create(service.options("forbidden").contextWrite(auth()))
                .expectErrorMatches(
                        failure -> failure instanceof BusinessException e && e.getStatus() == 403)
                .verify(WAIT);
        StepVerifier.create(service.options("project-a"))
                .expectError(UnAuthorizedException.class)
                .verify(WAIT);
        assertEquals(0, Config.dictionaryReads.get());
        Config.dictionaryUnavailable = true;
        StepVerifier.create(service.options("project-a").contextWrite(auth()))
                .expectErrorMatches(
                        failure -> failure instanceof BusinessException e && e.getStatus() == 503)
                .verify(WAIT);
    }

    @Test
    void optionsHttpRouteReadsRealProjectDataAndPreservesForbiddenAndSourceFailureStatuses() {
        var current = bound("OPTIONS_HTTP");
        var controller = new RecordedInfoController(service);
        var client =
                WebTestClient.bindToController(controller)
                        .controllerAdvice(new OptionErrors())
                        .webFilter((exchange, chain) -> chain.filter(exchange).contextWrite(auth()))
                        .build();
        client.get()
                .uri("/property/finance/projects/project-a/recorded/_options")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.companies.length()")
                .isEqualTo(1)
                .jsonPath("$.companies[0].id")
                .isEqualTo(current.getCompanyId())
                .jsonPath("$.companies[0].banks[0].id")
                .isEqualTo(current.getBankId())
                .jsonPath("$.companies[0].taxNumber")
                .doesNotExist()
                .jsonPath("$.sources.length()")
                .isEqualTo(2)
                .jsonPath("$.payments.length()")
                .isEqualTo(2);
        client.get()
                .uri("/property/finance/projects/project-empty/recorded/_options")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.companies.length()")
                .isEqualTo(0);
        client.get()
                .uri("/property/finance/projects/forbidden/recorded/_options")
                .exchange()
                .expectStatus()
                .isForbidden();
        Config.dictionaryUnavailable = true;
        client.get()
                .uri("/property/finance/projects/project-a/recorded/_options")
                .exchange()
                .expectStatus()
                .isEqualTo(503);
        WebTestClient.bindToController(controller)
                .controllerAdvice(new OptionErrors())
                .build()
                .get()
                .uri("/property/finance/projects/project-a/recorded/_options")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    RecordedInfoInput bound(String tax) {
        var company = companies.create(companyInput(tax)).contextWrite(auth()).block(WAIT);
        projects.bind("project-a", company.company().getId()).contextWrite(auth()).block(WAIT);
        var input = SettlementRuleServiceTest.percentage();
        input.setCompanyId(company.company().getId());
        input.setBankId(company.banks().get(0).getId());
        input.setSourceCharges(List.of("1"));
        input.setPatternPayments(List.of("1"));
        return input;
    }

    static InvoiceCompanyInput companyInput(String tax) {
        var input = new InvoiceCompanyInput();
        input.setCompanyName("入账公司" + tax);
        input.setTaxNumber(tax);
        input.setAddress("测试地址");
        input.setPhone("02312345678");
        input.setDepositBank("开户银行");
        input.setBankAccount("123456");
        input.setDrawer("开票员");
        input.setReviewer("复核员");
        input.setEstateNo("权证");
        input.setBanks(
                List.of(
                        new InvoiceCompanyInput.BankInput(
                                null, "入账银行", "123456", true, null, null, null, null, null)));
        return input;
    }

    static boolean conflict(Throwable failure) {
        return failure instanceof BusinessException e && e.getStatus() == 409;
    }

    static boolean notFound(Throwable failure) {
        return failure instanceof BusinessException e && e.getStatus() == 404;
    }

    static Context auth() {
        var user = new SimpleUser();
        user.setId("recorded-tester");
        user.setUsername("入账测试");
        var authentication = new SimpleAuthentication();
        authentication.setUser(user);
        return Context.of(Authentication.class, authentication);
    }

    /** HTTP 测试使用真实服务及项目边界；平台统一响应包装与菜单拦截由宿主装配。 */
    @org.springframework.web.bind.annotation.RestControllerAdvice
    static class OptionErrors {
        @org.springframework.web.bind.annotation.ExceptionHandler(BusinessException.class)
        org.springframework.http.ResponseEntity<Map<String, String>> business(
                BusinessException failure) {
            return org.springframework.http.ResponseEntity.status(failure.getStatus())
                    .body(Map.of("code", failure.getMessage()));
        }

        @org.springframework.web.bind.annotation.ExceptionHandler(UnAuthorizedException.class)
        org.springframework.http.ResponseEntity<Void> anonymous() {
            return org.springframework.http.ResponseEntity.status(401).build();
        }
    }

    @SpringBootConfiguration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableEasyormRepository("org.jetlinks.pro.property.finance.entity")
    @Import({
        RecordedInfoService.class,
        SettlementRuleService.class,
        InvoiceCompanyService.class,
        ProjectCompanyService.class
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
        static volatile boolean longDictionaryText;
        static volatile boolean dictionaryUnavailable;
        static final AtomicInteger dictionaryReads = new AtomicInteger();

        @org.springframework.context.annotation.Bean
        FinanceScope scope() {
            return new FinanceScope() {
                @Override
                public Mono<org.jetlinks.pro.property.base.organization.PropertyProjectInfo>
                        project(
                                String id,
                                org.jetlinks.pro.assets.crud.CrudAssetPermission permission) {
                    return authenticated()
                            .flatMap(
                                    auth ->
                                            "forbidden".equals(id)
                                                    ? Mono.error(error("project_forbidden", 403))
                                                    : Mono.just(
                                                            new org.jetlinks.pro.property.base
                                                                    .organization
                                                                    .PropertyProjectInfo(id, id)));
                }
            };
        }

        @org.springframework.context.annotation.Bean
        RecordedDictionarySource dictionaries() {
            return new RecordedDictionarySource() {
                @Override
                public Mono<Map<String, String>> options(String code) {
                    return Mono.defer(
                            () -> {
                                dictionaryReads.incrementAndGet();
                                if (dictionaryUnavailable)
                                    return Mono.error(error("dictionary_source_unavailable", 503));
                                return Mono.just(
                                        Map.of(
                                                "1",
                                                "source_charge".equals(code) ? "PC" : "现金",
                                                "2",
                                                longDictionaryText && "source_charge".equals(code)
                                                        ? "超".repeat(101)
                                                        : "第二项"));
                            });
                }
            };
        }
    }
}
