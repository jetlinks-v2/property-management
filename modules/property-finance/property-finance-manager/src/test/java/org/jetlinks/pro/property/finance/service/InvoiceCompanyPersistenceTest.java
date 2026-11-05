package org.jetlinks.pro.property.finance.service;

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
import reactor.util.context.Context;

import java.time.Duration;
import java.util.List;

/** 独立 PostgreSQL 验证共享公司与银行的唯一性、CAS 和整笔事务，不连接开发业务库。 */
@SpringBootTest(
        classes = InvoiceCompanyPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class InvoiceCompanyPersistenceTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final GenericContainer<?> DB =
            new GenericContainer<>("postgres:16-alpine")
                    .withEnv("POSTGRES_PASSWORD", "test-password")
                    .withEnv("POSTGRES_DB", "finance_test")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.start();
        registry.add(
                "spring.r2dbc.url",
                () -> "r2dbc:postgresql://127.0.0.1:" + DB.getMappedPort(5432) + "/finance_test");
        registry.add("spring.r2dbc.username", () -> "postgres");
        registry.add("spring.r2dbc.password", () -> "test-password");
        registry.add("easyorm.dialect", () -> "postgres");
        registry.add("easyorm.default-schema", () -> "public");
    }

    @Autowired InvoiceCompanyService service;
    @Autowired ProjectCompanyService projectCompanies;
    @Autowired ReactiveRepository<FinanceProjectCompanyEntity, String> relations;
    @Autowired ReactiveRepository<RecordedInfoEntity, String> recorded;
    @Autowired ReactiveRepository<TaxItemEntity, String> taxItems;
    @Autowired ReactiveRepository<FinanceCompanyEntity, String> companies;
    @Autowired ReactiveRepository<FinanceBankEntity, String> banks;

    @BeforeEach
    void clean() {
        recorded.deleteById(recorded.createQuery().fetch().map(RecordedInfoEntity::getId))
                .block(WAIT);
        taxItems.deleteById(taxItems.createQuery().fetch().map(TaxItemEntity::getId)).block(WAIT);
        relations
                .deleteById(relations.createQuery().fetch().map(FinanceProjectCompanyEntity::getId))
                .block(WAIT);
        banks.deleteById(banks.createQuery().fetch().map(FinanceBankEntity::getId)).block(WAIT);
        companies
                .deleteById(companies.createQuery().fetch().map(FinanceCompanyEntity::getId))
                .block(WAIT);
    }

    @Test
    void companyQuotaKeepsAllDecimalDigitsAcrossDatabaseAndJson() throws Exception {
        var input = input("TAXEXACTQUOTA");
        var quota = new java.math.BigDecimal("9999999999999.123456");
        input.setQuota(quota);
        var saved = service.create(input).contextWrite(auth()).block(WAIT);
        var loaded = service.detail(saved.company().getId()).contextWrite(auth()).block(WAIT);
        assertEquals(0, quota.compareTo(loaded.company().getQuota()));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(loaded.company())).get("quota");
        assertTrue(
                json.isTextual(),
                "Browser must receive quota without binary floating-point conversion");
        assertEquals(quota.toPlainString(), json.textValue());
    }

    @Test
    void taxNumberIsNormalizedAndConcurrentDuplicateCannotCreateTwoCompanies() {
        var results =
                Flux.merge(
                                service.create(input(" tax01 ")).materialize(),
                                service.create(input("TAX01")).materialize())
                        .contextWrite(auth())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, results.stream().filter(signal -> signal.isOnNext()).count());
        assertEquals(1, results.stream().filter(signal -> conflict(signal.getThrowable())).count());
        assertEquals(1, companies.createQuery().count().block(WAIT));
        assertEquals("TAX01", companies.createQuery().fetchOne().block(WAIT).getTaxNumber());
    }

    @Test
    void staleUpdateDoesNotChangeCompanyOrBankAndOwnBankIdsStayStable() {
        var created = service.create(input("TAX02")).contextWrite(auth()).block(WAIT);
        var request = input("TAX02");
        request.setVersion(created.company().getVersion());
        request.setCompanyName("更新公司");
        request.setBanks(List.of(bank(created.banks().get(0).getId(), "新银行", "12345", true)));
        var updated =
                service.update(created.company().getId(), request).contextWrite(auth()).block(WAIT);
        assertEquals(created.banks().get(0).getId(), updated.banks().get(0).getId());
        assertEquals(1L, updated.company().getVersion());
        request.setCompanyName("过期修改");
        StepVerifier.create(service.update(created.company().getId(), request).contextWrite(auth()))
                .expectErrorMatches(InvoiceCompanyPersistenceTest::conflict)
                .verify(WAIT);
        assertEquals(
                "更新公司", companies.findById(created.company().getId()).block(WAIT).getCompanyName());
        assertEquals("开户名称", updated.banks().get(0).getAccountName());
        assertEquals(
                "新银行", banks.findById(created.banks().get(0).getId()).block(WAIT).getBankName());
    }

    @Test
    void multipleDefaultsAndForeignBankIdAreRejectedWithoutPartialWrites() {
        var first = service.create(input("TAX03")).contextWrite(auth()).block(WAIT);
        var second = service.create(input("TAX04")).contextWrite(auth()).block(WAIT);
        var request = input("TAX03");
        request.setVersion(0L);
        request.setBanks(List.of(bank(null, "银行甲", "1", true), bank(null, "银行乙", "2", true)));
        StepVerifier.create(service.update(first.company().getId(), request).contextWrite(auth()))
                .expectError(BusinessException.class)
                .verify(WAIT);
        request.setBanks(List.of(bank(second.banks().get(0).getId(), "其他银行", "2", true)));
        StepVerifier.create(service.update(first.company().getId(), request).contextWrite(auth()))
                .expectError(BusinessException.class)
                .verify(WAIT);
        assertEquals(0L, companies.findById(first.company().getId()).block(WAIT).getVersion());
        assertEquals(2, banks.createQuery().count().block(WAIT));
    }

    @Test
    void deletionIsLogicalAndCallerCannotQueryDeletedOrBypassAuthentication() {
        var created = service.create(input("TAX05")).contextWrite(auth()).block(WAIT);
        service.delete(created.company().getId(), 0L).contextWrite(auth()).block(WAIT);
        assertTrue(companies.findById(created.company().getId()).block(WAIT).getDeleted());
        assertTrue(banks.findById(created.banks().get(0).getId()).block(WAIT).getDeleted());
        var query = new QueryParamEntity();
        query.or("deleted", "eq", true);
        assertEquals(0, service.query(query).contextWrite(auth()).block(WAIT).getTotal());
        StepVerifier.create(service.detail(created.company().getId()).contextWrite(auth()))
                .expectErrorMatches(
                        error -> error instanceof BusinessException e && e.getStatus() == 404)
                .verify(WAIT);
        StepVerifier.create(service.create(input("TAX06")))
                .expectError(UnAuthorizedException.class)
                .verify(WAIT);
    }

    @Test
    void projectScopedDetailCannotReadOtherProjectsAndCreateBindIsAtomic() {
        var row =
                projectCompanies
                        .createAndBind("project-created", input("LINKCREATE"))
                        .contextWrite(auth())
                        .block(WAIT);
        assertEquals(
                "LINKCREATE",
                projectCompanies
                        .detail("project-created", row.relation().getId())
                        .contextWrite(auth())
                        .block(WAIT)
                        .company()
                        .getTaxNumber());
        StepVerifier.create(
                        projectCompanies
                                .detail("project-other", row.relation().getId())
                                .contextWrite(auth()))
                .expectErrorMatches(
                        failure -> failure instanceof BusinessException e && e.getStatus() == 404)
                .verify(WAIT);
        StepVerifier.create(
                        projectCompanies
                                .createAndBind("forbidden", input("NOLEAK"))
                                .contextWrite(auth()))
                .expectError(BusinessException.class)
                .verify(WAIT);
        assertEquals(1, companies.createQuery().count().block(WAIT));
    }

    static boolean conflict(Throwable error) {
        return error instanceof BusinessException e && e.getStatus() == 409;
    }

    @Test
    void realRecordedReferencesProtectBankAndProjectLinkAndRollbackCompanyChanges() {
        var first = service.create(input("REFER01")).contextWrite(auth()).block(WAIT);
        var other = service.create(input("REFER02")).contextWrite(auth()).block(WAIT);
        projectCompanies
                .bind("reference-project", other.company().getId())
                .contextWrite(auth())
                .block(WAIT);
        var link =
                projectCompanies
                        .bind("reference-project", first.company().getId())
                        .contextWrite(auth())
                        .block(WAIT);
        var rule = new RecordedInfoEntity();
        rule.setId("rule-reference");
        rule.setProjectId("reference-project");
        rule.setCompanyId(first.company().getId());
        rule.setBankId(first.banks().get(0).getId());
        rule.setSourceCharge("cashier");
        rule.setPatternPayment("cash");
        InvoiceCompanyService.init(rule, auth().get(Authentication.class));
        recorded.insert(rule).block(WAIT);
        var update = input("REFER01");
        update.setVersion(0L);
        update.setCompanyName("不应保存");
        update.setBanks(List.of());
        StepVerifier.create(service.update(first.company().getId(), update).contextWrite(auth()))
                .expectErrorMatches(InvoiceCompanyPersistenceTest::conflict)
                .verify(WAIT);
        assertEquals(0L, companies.findById(first.company().getId()).block(WAIT).getVersion());
        assertEquals(
                "测试公司", companies.findById(first.company().getId()).block(WAIT).getCompanyName());
        assertFalse(banks.findById(first.banks().get(0).getId()).block(WAIT).getDeleted());
        StepVerifier.create(
                        projectCompanies
                                .unbind("reference-project", link.relation().getId(), 0)
                                .contextWrite(auth()))
                .expectErrorMatches(InvoiceCompanyPersistenceTest::conflict)
                .verify(WAIT);
    }

    @Test
    void projectCompaniesHaveOneDefaultAndDeletedLinkCanBeRestored() {
        var first = service.create(input("LINK01")).contextWrite(auth()).block(WAIT);
        var second = service.create(input("LINK02")).contextWrite(auth()).block(WAIT);
        var a =
                projectCompanies
                        .bind("project-a", first.company().getId())
                        .contextWrite(auth())
                        .block(WAIT);
        var b =
                projectCompanies
                        .bind("project-a", second.company().getId())
                        .contextWrite(auth())
                        .block(WAIT);
        assertEquals("project-a", a.relation().getDefaultKey());
        assertNull(b.relation().getDefaultKey());
        StepVerifier.create(service.delete(first.company().getId(), 0).contextWrite(auth()))
                .expectErrorMatches(InvoiceCompanyPersistenceTest::conflict)
                .verify(WAIT);
        StepVerifier.create(
                        projectCompanies
                                .unbind("project-a", a.relation().getId(), 0)
                                .contextWrite(auth()))
                .expectErrorMatches(InvoiceCompanyPersistenceTest::conflict)
                .verify(WAIT);
        projectCompanies
                .setDefault("project-a", b.relation().getId(), 0)
                .contextWrite(auth())
                .block(WAIT);
        var list =
                projectCompanies.list("project-a").contextWrite(auth()).collectList().block(WAIT);
        assertEquals(
                1, list.stream().filter(row -> row.relation().getDefaultKey() != null).count());
        assertEquals(
                second.company().getId(),
                list.stream()
                        .filter(row -> row.relation().getDefaultKey() != null)
                        .findFirst()
                        .orElseThrow()
                        .company()
                        .getId());
        projectCompanies
                .unbind("project-a", a.relation().getId(), 1)
                .contextWrite(auth())
                .block(WAIT);
        var restored =
                projectCompanies
                        .bind("project-a", first.company().getId())
                        .contextWrite(auth())
                        .block(WAIT);
        assertEquals(a.relation().getId(), restored.relation().getId());
        assertFalse(restored.relation().getDeleted());
        StepVerifier.create(
                        projectCompanies
                                .unbind(
                                        "project-other",
                                        restored.relation().getId(),
                                        restored.relation().getVersion())
                                .contextWrite(auth()))
                .expectError(BusinessException.class)
                .verify(WAIT);
    }

    @Test
    void concurrentBindingsDoNotCreateDuplicateDefaultsAndStaleChangesAreRejected() {
        var first = service.create(input("LINK03")).contextWrite(auth()).block(WAIT);
        var result =
                Flux.merge(
                                projectCompanies
                                        .bind("project-b", first.company().getId())
                                        .materialize(),
                                projectCompanies
                                        .bind("project-b", first.company().getId())
                                        .materialize())
                        .contextWrite(auth())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, result.stream().filter(signal -> signal.isOnNext()).count());
        assertEquals(1, result.stream().filter(signal -> conflict(signal.getThrowable())).count());
        assertEquals(
                1,
                projectCompanies
                        .list("project-b")
                        .contextWrite(auth())
                        .collectList()
                        .block(WAIT)
                        .size());
        var relation = relations.createQuery().fetchOne().block(WAIT);
        StepVerifier.create(
                        projectCompanies
                                .setDefault("project-b", relation.getId(), 99)
                                .contextWrite(auth()))
                .expectErrorMatches(InvoiceCompanyPersistenceTest::conflict)
                .verify(WAIT);
        StepVerifier.create(
                        projectCompanies
                                .bind("forbidden", first.company().getId())
                                .contextWrite(auth()))
                .expectErrorMatches(
                        error -> error instanceof BusinessException e && e.getStatus() == 403)
                .verify(WAIT);
    }

    @Test
    void concurrentFirstCompaniesCannotBothBecomeProjectDefaultAndRejectedBindingRollsBack() {
        var a = service.create(input("RACE01")).contextWrite(auth()).block(WAIT);
        var b = service.create(input("RACE02")).contextWrite(auth()).block(WAIT);
        var outcomes =
                Flux.merge(
                                projectCompanies
                                        .bind("race-project", a.company().getId())
                                        .materialize(),
                                projectCompanies
                                        .bind("race-project", b.company().getId())
                                        .materialize())
                        .contextWrite(auth())
                        .collectList()
                        .block(WAIT);
        var links =
                relations
                        .createQuery()
                        .where("projectId", "race-project")
                        .and("deleted", false)
                        .fetch()
                        .collectList()
                        .block(WAIT);
        assertEquals(1, links.stream().filter(link -> link.getDefaultKey() != null).count());
        // 允许第二个事务看到先提交的默认并成功普通关联，也允许唯一键冲突明确拒绝。
        assertEquals(outcomes.stream().filter(signal -> signal.isOnNext()).count(), links.size());
        assertTrue(
                outcomes.stream()
                        .filter(signal -> signal.isOnError())
                        .allMatch(signal -> conflict(signal.getThrowable())));
        assertEquals(2, companies.createQuery().count().block(WAIT));
    }

    static Context auth() {
        var user = new SimpleUser();
        user.setId("finance-tester");
        user.setUsername("财务测试");
        var auth = new SimpleAuthentication();
        auth.setUser(user);
        return Context.of(Authentication.class, auth);
    }

    static InvoiceCompanyInput.BankInput bank(
            String id, String name, String account, boolean defaultBank) {
        return new InvoiceCompanyInput.BankInput(
                id, name, account, defaultBank, "金融机构", "联行号", "开户名称", "账户编码", null);
    }

    static InvoiceCompanyInput input(String taxNumber) {
        var input = new InvoiceCompanyInput();
        input.setCompanyName("测试公司");
        input.setTaxNumber(taxNumber);
        input.setAddress("测试地址");
        input.setPhone("023-12345678");
        input.setDepositBank("开户行");
        input.setBankAccount("123456");
        input.setDrawer("开票员");
        input.setReviewer("复核员");
        input.setEstateNo("权证1");
        input.setBanks(List.of(bank(null, "入账银行", "123456", true)));
        return input;
    }

    @SpringBootConfiguration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableEasyormRepository("org.jetlinks.pro.property.finance.entity")
    @Import({InvoiceCompanyService.class, ProjectCompanyService.class})
    @ImportAutoConfiguration({
        EasyormConfiguration.class,
        R2dbcSqlExecutorConfiguration.class,
        R2dbcAutoConfiguration.class,
        R2dbcTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        EntityFactoryConfiguration.class
    })
    static class Config {
        // 此组只隔离项目查询边界；真实命令/资产校验由基础模块项目行为测试覆盖。
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
