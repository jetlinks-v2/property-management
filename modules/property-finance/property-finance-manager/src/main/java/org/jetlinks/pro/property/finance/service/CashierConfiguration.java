package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.CashierInput;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.springframework.stereotype.Component;

import reactor.core.publisher.*;

import java.util.*;

/** 复用项目公司、启用字典及PC入账配置。人工确认与在线通道分开，不能凭前端支付方式记账。 */
@Component
@RequiredArgsConstructor
public class CashierConfiguration {
    private final InvoiceCompanyService companies;
    private final RecordedDictionarySource dictionaries;
    private final ReactiveRepository<FinanceProjectCompanyEntity, String> relations;
    private final ReactiveRepository<RecordedInfoEntity, String> records;
    private final ReactiveRepository<FinanceBankEntity, String> banks;

    Mono<String> defaultCompanyId(String project, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return relations
                .createQuery()
                .setParam(query)
                .where("projectId", project)
                .and("defaultKey", project)
                .and("deleted", false)
                .fetchOne()
                .map(FinanceProjectCompanyEntity::getCompanyId);
    }

    /** 新收款统一使用项目默认公司；历史退款仍沿用原款公司的配置。 */
    public Mono<Resolved> resolveCollection(String project, CashierInput input, boolean lock) {
        return resolve(project, input, lock)
                .flatMap(
                        config ->
                                defaultCompanyId(project, lock)
                                        .filter(config.company().getId()::equals)
                                        .switchIfEmpty(
                                                Mono.error(
                                                        error(
                                                                "cashier_default_company_required",
                                                                409)))
                                        .thenReturn(config));
    }

    Mono<List<CompanyOption>> collectionOptions(String project) {
        return defaultCompanyId(project, false)
                .switchIfEmpty(Mono.error(error("cashier_default_company_required", 409)))
                .flatMap(
                        id ->
                                options(project)
                                        .map(
                                                rows -> {
                                                    var selected =
                                                            rows.stream()
                                                                    .filter(
                                                                            row ->
                                                                                    id.equals(
                                                                                            row
                                                                                                    .id()))
                                                                    .toList();
                                                    if (selected.size() != 1)
                                                        throw error(
                                                                "cashier_default_company_required",
                                                                409);
                                                    return selected;
                                                }));
    }

    Mono<FinanceCompanyEntity> balanceCompany(String project, String companyId, boolean lock) {
        return companies
                .company(companyId, lock)
                .flatMap(
                        company ->
                                relations
                                        .createQuery()
                                        .where("projectId", project)
                                        .and("companyId", companyId)
                                        .and("deleted", false)
                                        .fetchOne()
                                        .switchIfEmpty(
                                                Mono.error(
                                                        error(
                                                                "cashier_configuration_invalid",
                                                                409)))
                                        .thenReturn(company));
    }

    public Mono<Resolved> resolve(String project, CashierInput input, boolean lock) {
        return companies
                .company(input.getCompanyId(), lock)
                .flatMap(
                        company ->
                                relations
                                        .createQuery()
                                        .where("projectId", project)
                                        .and("companyId", company.getId())
                                        .and("deleted", false)
                                        .fetchOne()
                                        .switchIfEmpty(
                                                Mono.error(
                                                        error(
                                                                "cashier_configuration_invalid",
                                                                409)))
                                        .then(
                                                Mono.zip(
                                                        dictionaries.options("pattern_payment"),
                                                        dictionaries.options("source_charge")))
                                        .flatMap(
                                                dict -> {
                                                    if (!dict.getT2().containsKey("1"))
                                                        return Mono.error(
                                                                error(
                                                                        "cashier_configuration_invalid",
                                                                        409));
                                                    return Flux.fromIterable(input.getTenders())
                                                            .concatMap(
                                                                    tender -> {
                                                                        var name =
                                                                                dict.getT1()
                                                                                        .get(
                                                                                                tender
                                                                                                        .method());
                                                                        if (name == null)
                                                                            return Mono.error(
                                                                                    error(
                                                                                            "cashier_configuration_invalid",
                                                                                            409));
                                                                        if ("1"
                                                                                .equals(
                                                                                        tender
                                                                                                .method()))
                                                                            return Mono.just(
                                                                                    new Funding(
                                                                                            "1",
                                                                                            name,
                                                                                            null,
                                                                                            null,
                                                                                            null,
                                                                                            null,
                                                                                            company
                                                                                                    .getVersion()));
                                                                        var query =
                                                                                new QueryParamEntity();
                                                                        query.setForUpdate(lock);
                                                                        return records.createQuery()
                                                                                .setParam(query)
                                                                                .where(
                                                                                        "id",
                                                                                        tender
                                                                                                .recordedId())
                                                                                .and(
                                                                                        "projectId",
                                                                                        project)
                                                                                .and(
                                                                                        "companyId",
                                                                                        company
                                                                                                .getId())
                                                                                .and(
                                                                                        "sourceCharge",
                                                                                        "1")
                                                                                .and(
                                                                                        "patternPayment",
                                                                                        tender
                                                                                                .method())
                                                                                .and(
                                                                                        "deleted",
                                                                                        false)
                                                                                .fetchOne()
                                                                                .switchIfEmpty(
                                                                                        Mono.error(
                                                                                                error(
                                                                                                        "cashier_configuration_invalid",
                                                                                                        409)))
                                                                                .flatMap(
                                                                                        record ->
                                                                                                banks.createQuery()
                                                                                                        .where(
                                                                                                                "id",
                                                                                                                record
                                                                                                                        .getBankId())
                                                                                                        .and(
                                                                                                                "companyId",
                                                                                                                company
                                                                                                                        .getId())
                                                                                                        .and(
                                                                                                                "deleted",
                                                                                                                false)
                                                                                                        .fetchOne()
                                                                                                        .switchIfEmpty(
                                                                                                                Mono
                                                                                                                        .error(
                                                                                                                                error(
                                                                                                                                        "cashier_configuration_invalid",
                                                                                                                                        409)))
                                                                                                        .map(
                                                                                                                bank ->
                                                                                                                        new Funding(
                                                                                                                                tender
                                                                                                                                        .method(),
                                                                                                                                name,
                                                                                                                                record
                                                                                                                                        .getId(),
                                                                                                                                bank
                                                                                                                                        .getId(),
                                                                                                                                bank
                                                                                                                                        .getBankName(),
                                                                                                                                bank
                                                                                                                                        .getBankAccount(),
                                                                                                                                record
                                                                                                                                        .getVersion())));
                                                                    })
                                                            .collectList()
                                                            .map(
                                                                    funding ->
                                                                            new Resolved(
                                                                                    company,
                                                                                    funding));
                                                }));
    }

    public Mono<List<CompanyOption>> options(String project) {
        return dictionaries
                .options("pattern_payment")
                .flatMap(
                        methods ->
                                relations
                                        .createQuery()
                                        .where("projectId", project)
                                        .and("deleted", false)
                                        .fetch()
                                        .take(201)
                                        .collectList()
                                        .flatMapMany(
                                                rows ->
                                                        rows.size() > 200
                                                                ? Flux.error(
                                                                        error(
                                                                                "cashier_configuration_limit",
                                                                                409))
                                                                : Flux.fromIterable(rows))
                                        .concatMap(
                                                relation ->
                                                        companies
                                                                .company(
                                                                        relation.getCompanyId(),
                                                                        false)
                                                                .flatMap(
                                                                        company ->
                                                                                records.createQuery()
                                                                                        .where(
                                                                                                "projectId",
                                                                                                project)
                                                                                        .and(
                                                                                                "companyId",
                                                                                                company
                                                                                                        .getId())
                                                                                        .and(
                                                                                                "sourceCharge",
                                                                                                "1")
                                                                                        .and(
                                                                                                "deleted",
                                                                                                false)
                                                                                        .fetch()
                                                                                        .filter(
                                                                                                row ->
                                                                                                        PaymentRules
                                                                                                                        .MANUAL_METHODS
                                                                                                                        .contains(
                                                                                                                                row
                                                                                                                                        .getPatternPayment())
                                                                                                                && !"1"
                                                                                                                        .equals(
                                                                                                                                row
                                                                                                                                        .getPatternPayment())
                                                                                                                && methods
                                                                                                                        .containsKey(
                                                                                                                                row
                                                                                                                                        .getPatternPayment()))
                                                                                        .take(1001)
                                                                                        .collectList()
                                                                                        .flatMap(
                                                                                                rows -> {
                                                                                                    if (rows
                                                                                                                    .size()
                                                                                                            > 1000)
                                                                                                        return Mono
                                                                                                                .error(
                                                                                                                        error(
                                                                                                                                "cashier_configuration_limit",
                                                                                                                                409));
                                                                                                    var
                                                                                                            funding =
                                                                                                                    new ArrayList<
                                                                                                                            FundingOption>();
                                                                                                    if (methods
                                                                                                            .containsKey(
                                                                                                                    "1"))
                                                                                                        funding
                                                                                                                .add(
                                                                                                                        new FundingOption(
                                                                                                                                "1",
                                                                                                                                "1",
                                                                                                                                methods
                                                                                                                                        .get(
                                                                                                                                                "1"),
                                                                                                                                null,
                                                                                                                                null));
                                                                                                    rows
                                                                                                            .stream()
                                                                                                            .sorted(
                                                                                                                    Comparator
                                                                                                                            .comparing(
                                                                                                                                    RecordedInfoEntity
                                                                                                                                            ::getId))
                                                                                                            .forEach(
                                                                                                                    row ->
                                                                                                                            funding
                                                                                                                                    .add(
                                                                                                                                            new FundingOption(
                                                                                                                                                    row
                                                                                                                                                            .getId(),
                                                                                                                                                    row
                                                                                                                                                            .getPatternPayment(),
                                                                                                                                                    methods
                                                                                                                                                            .get(
                                                                                                                                                                    row
                                                                                                                                                                            .getPatternPayment()),
                                                                                                                                                    row
                                                                                                                                                            .getId(),
                                                                                                                                                    row
                                                                                                                                                            .getBankName())));
                                                                                                    return Mono
                                                                                                            .just(
                                                                                                                    new CompanyOption(
                                                                                                                            company
                                                                                                                                    .getId(),
                                                                                                                            company
                                                                                                                                    .getCompanyName(),
                                                                                                                            funding));
                                                                                                })))
                                        .collectList());
    }

    public record Funding(
            String method,
            String text,
            String recordedId,
            String bankId,
            String bankName,
            String bankAccount,
            Long version) {}

    public record Resolved(FinanceCompanyEntity company, List<Funding> funding) {
        public String identity() {
            return company.getId() + ":" + company.getVersion() + ":" + funding.toString();
        }
    }

    public record FundingOption(
            String id, String method, String text, String recordedId, String bankName) {}

    public record CompanyOption(String id, String name, List<FundingOption> funding) {}
}
