package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.WAIT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.CashierInput;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.*;

/** 真实项目公司关系验证默认入账、配置变更及历史退款公司隔离。 */
@SpringBootTest(
        classes = CashierPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CashierDefaultCompanyPersistenceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        HouseSettingPersistenceTest.database(registry);
    }

    @Autowired ReactiveRepository<FinanceProjectCompanyEntity, String> relations;
    @Autowired ReactiveRepository<RecordedInfoEntity, String> records;
    @Autowired ReactiveRepository<FinanceBankEntity, String> banks;
    CashierConfiguration configuration;
    String project;

    @BeforeEach
    void setup() {
        project = UUID.randomUUID().toString();
        var companies = mock(InvoiceCompanyService.class);
        when(companies.company(anyString(), anyBoolean()))
                .thenAnswer(
                        call -> {
                            var company = new FinanceCompanyEntity();
                            company.setId(call.getArgument(0));
                            company.setCompanyName(company.getId());
                            company.setVersion(1L);
                            return Mono.just(company);
                        });
        var dictionaries = mock(RecordedDictionarySource.class);
        when(dictionaries.options("pattern_payment")).thenReturn(Mono.just(Map.of("1", "现金")));
        when(dictionaries.options("source_charge")).thenReturn(Mono.just(Map.of("1", "PC")));
        configuration =
                new CashierConfiguration(companies, dictionaries, relations, records, banks);
    }

    FinanceProjectCompanyEntity bind(String company, boolean selected) {
        var row = new FinanceProjectCompanyEntity();
        row.setId(UUID.randomUUID().toString());
        row.setProjectId(project);
        row.setCompanyId(company);
        row.setDefaultKey(selected ? project : null);
        StandardPersistenceTest.initialize(row);
        relations.insert(row).block(WAIT);
        return row;
    }

    CashierInput input(String company) {
        var input = new CashierInput();
        input.setCompanyId(company);
        input.setTenders(List.of(new CashierInput.Tender("1", null, new BigDecimal("10.00"))));
        return input;
    }

    void rejects(Mono<?> operation) {
        StepVerifier.create(operation)
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 409))
                .verify(WAIT);
    }

    @Test
    void onlyProjectDefaultIsAvailableForCollectionAndForgedCompanyIsRejected() {
        bind("other", false);
        bind("default", true);
        var options = configuration.collectionOptions(project).block(WAIT);
        assertEquals(
                List.of("default"),
                options.stream().map(CashierConfiguration.CompanyOption::id).toList());
        assertEquals(
                "default",
                configuration
                        .resolveCollection(project, input("default"), false)
                        .block(WAIT)
                        .company()
                        .getId());
        rejects(configuration.resolveCollection(project, input("other"), false));
    }

    @Test
    void changingDefaultInvalidatesOldCollectionButHistoricalRefundKeepsItsCompany() {
        var old = bind("old", true);
        var next = bind("next", false);
        var original = input("old");
        assertEquals(
                "old",
                configuration
                        .resolveCollection(project, original, false)
                        .block(WAIT)
                        .company()
                        .getId());
        relations
                .createUpdate()
                .setNull("defaultKey")
                .where("id", old.getId())
                .execute()
                .block(WAIT);
        relations
                .createUpdate()
                .set("defaultKey", project)
                .where("id", next.getId())
                .execute()
                .block(WAIT);
        rejects(configuration.resolveCollection(project, original, false));
        assertEquals(
                "old",
                configuration.resolve(project, original, false).block(WAIT).company().getId());
        assertEquals(
                "next",
                configuration
                        .resolveCollection(project, input("next"), false)
                        .block(WAIT)
                        .company()
                        .getId());
    }

    @Test
    void missingDeletedOrForeignProjectDefaultCannotBeUsedForCollection() {
        bind("ordinary", false);
        rejects(configuration.collectionOptions(project));
        rejects(configuration.resolveCollection(project, input("ordinary"), false));
        var selected = bind("selected", true);
        relations
                .createUpdate()
                .set("deleted", true)
                .where("id", selected.getId())
                .execute()
                .block(WAIT);
        rejects(configuration.collectionOptions(project));
        rejects(configuration.resolveCollection(project, input("selected"), false));
        project = UUID.randomUUID().toString();
        rejects(configuration.collectionOptions(project));
    }
}
