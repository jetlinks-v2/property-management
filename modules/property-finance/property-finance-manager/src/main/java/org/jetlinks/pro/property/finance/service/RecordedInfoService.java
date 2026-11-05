package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.audit;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.init;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryOption;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.tax.RecordedInfoInput;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 入账配置同时校验项目资产、公司项目关联、稳定银行 ID；组合写入和阶梯替换在一个事务中。 */
@Service
@RequiredArgsConstructor
public class RecordedInfoService {
    private final FinanceScope scope;
    private final InvoiceCompanyService companies;
    private final RecordedDictionarySource dictionaries;
    private final SettlementRuleService fees;
    private final ReactiveRepository<RecordedInfoEntity, String> records;
    private final ReactiveRepository<SettlementRuleEntity, String> rules;
    private final ReactiveRepository<FinanceBankEntity, String> banks;
    private final ReactiveRepository<FinanceProjectCompanyEntity, String> relations;

    public Mono<PagerResult<RecordedInfoEntity>> query(String projectId, QueryParamEntity input) {
        return scope.project(projectId, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    var query = input.clone();
                                    query.setTotal(null);
                                    query.setPaging(true);
                                    query.setPageIndex(Math.max(0, query.getPageIndex()));
                                    query.setPageSize(
                                            Math.max(1, Math.min(200, query.getPageSize())));
                                    query.toNestQuery(
                                            nested ->
                                                    nested.and("projectId", projectId)
                                                            .and("deleted", false));
                                    return QueryHelper.queryPager(query, records::createQuery);
                                }));
    }

    public Mono<Detail> detail(String projectId, String id) {
        return scope.project(projectId, CrudAssetPermission.read)
                .then(record(projectId, id, false))
                .flatMap(this::withRules);
    }

    /** 入账查询权限只读取当前项目的选择数据，不要求调用者取得基础字典或共享公司维护权限。 */
    public Mono<OptionsDetail> options(String projectId) {
        return scope.project(projectId, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    var companyOptions =
                                            relations
                                                    .createQuery()
                                                    .where("projectId", projectId)
                                                    .and("deleted", false)
                                                    .fetch()
                                                    .concatMap(
                                                            relation ->
                                                                    companies
                                                                            .company(
                                                                                    relation
                                                                                            .getCompanyId(),
                                                                                    false)
                                                                            .flatMap(
                                                                                    company ->
                                                                                            banks.createQuery()
                                                                                                    .where(
                                                                                                            "companyId",
                                                                                                            company
                                                                                                                    .getId())
                                                                                                    .and(
                                                                                                            "deleted",
                                                                                                            false)
                                                                                                    .fetch()
                                                                                                    .sort(
                                                                                                            Comparator
                                                                                                                    .comparing(
                                                                                                                            FinanceBankEntity
                                                                                                                                    ::getBankName)
                                                                                                                    .thenComparing(
                                                                                                                            FinanceBankEntity
                                                                                                                                    ::getId))
                                                                                                    .map(
                                                                                                            bank ->
                                                                                                                    new BankOption(
                                                                                                                            bank
                                                                                                                                    .getId(),
                                                                                                                            bank
                                                                                                                                    .getBankName(),
                                                                                                                            bank
                                                                                                                                    .getBankAccount()))
                                                                                                    .collectList()
                                                                                                    .map(
                                                                                                            rows ->
                                                                                                                    new CompanyOption(
                                                                                                                            company
                                                                                                                                    .getId(),
                                                                                                                            company
                                                                                                                                    .getCompanyName(),
                                                                                                                            rows))))
                                                    .sort(
                                                            Comparator.comparing(
                                                                            CompanyOption
                                                                                    ::companyName)
                                                                    .thenComparing(
                                                                            CompanyOption::id))
                                                    .collectList();
                                    return Mono.zip(
                                                    dictionaries.options("source_charge"),
                                                    dictionaries.options("pattern_payment"),
                                                    companyOptions)
                                            .map(
                                                    values ->
                                                            new OptionsDetail(
                                                                    dictionaryOptions(
                                                                            values.getT1()),
                                                                    dictionaryOptions(
                                                                            values.getT2()),
                                                                    values.getT3()));
                                }));
    }

    private List<PropertyDictionaryOption> dictionaryOptions(Map<String, String> values) {
        return values.entrySet().stream()
                .map(value -> new PropertyDictionaryOption(value.getKey(), value.getValue()))
                .toList();
    }

    @Transactional
    public Mono<BatchResult> create(String projectId, RecordedInfoInput input) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                validated(input, false)
                                        .flatMap(
                                                options ->
                                                        companies
                                                                .company(input.getCompanyId(), true)
                                                                .flatMap(
                                                                        company ->
                                                                                linkedBank(
                                                                                                projectId,
                                                                                                input)
                                                                                        .flatMap(
                                                                                                bank -> {
                                                                                                    var
                                                                                                            created =
                                                                                                                    new ArrayList<
                                                                                                                            RecordedInfoEntity>();
                                                                                                    var
                                                                                                            skipped =
                                                                                                                    new ArrayList<
                                                                                                                            Combination>();
                                                                                                    var
                                                                                                            combinations =
                                                                                                                    new ArrayList<
                                                                                                                            Combination>();
                                                                                                    for (var
                                                                                                            source :
                                                                                                                    input
                                                                                                                            .getSourceCharges())
                                                                                                        for (var
                                                                                                                payment :
                                                                                                                        input
                                                                                                                                .getPatternPayments())
                                                                                                            combinations
                                                                                                                    .add(
                                                                                                                            new Combination(
                                                                                                                                    source,
                                                                                                                                    payment));
                                                                                                    return Flux
                                                                                                            .fromIterable(
                                                                                                                    combinations)
                                                                                                            .concatMap(
                                                                                                                    pair -> {
                                                                                                                        var
                                                                                                                                key =
                                                                                                                                        key(
                                                                                                                                                projectId,
                                                                                                                                                input
                                                                                                                                                        .getCompanyId(),
                                                                                                                                                input
                                                                                                                                                        .getBankId(),
                                                                                                                                                pair);
                                                                                                                        return records.createQuery()
                                                                                                                                .where(
                                                                                                                                        "activeKey",
                                                                                                                                        key)
                                                                                                                                .and(
                                                                                                                                        "deleted",
                                                                                                                                        false)
                                                                                                                                .fetchOne()
                                                                                                                                .flatMap(
                                                                                                                                        existing -> {
                                                                                                                                            if (combinations
                                                                                                                                                            .size()
                                                                                                                                                    == 1)
                                                                                                                                                return Mono
                                                                                                                                                        .<RecordedInfoEntity>
                                                                                                                                                                error(
                                                                                                                                                                        error(
                                                                                                                                                                                "recorded_duplicate",
                                                                                                                                                                                409));
                                                                                                                                            skipped
                                                                                                                                                    .add(
                                                                                                                                                            pair);
                                                                                                                                            return Mono
                                                                                                                                                    .<RecordedInfoEntity>
                                                                                                                                                            empty();
                                                                                                                                        })
                                                                                                                                .switchIfEmpty(
                                                                                                                                        Mono
                                                                                                                                                .defer(
                                                                                                                                                        () -> {
                                                                                                                                                            if (skipped
                                                                                                                                                                    .contains(
                                                                                                                                                                            pair))
                                                                                                                                                                return Mono
                                                                                                                                                                        .empty();
                                                                                                                                                            var
                                                                                                                                                                    entity =
                                                                                                                                                                            entity(
                                                                                                                                                                                    projectId,
                                                                                                                                                                                    input,
                                                                                                                                                                                    company,
                                                                                                                                                                                    bank,
                                                                                                                                                                                    pair,
                                                                                                                                                                                    options);
                                                                                                                                                            entity
                                                                                                                                                                    .setId(
                                                                                                                                                                            UUID.randomUUID()
                                                                                                                                                                                    .toString());
                                                                                                                                                            init(
                                                                                                                                                                    entity,
                                                                                                                                                                    auth);
                                                                                                                                                            return records.insert(
                                                                                                                                                                            entity)
                                                                                                                                                                    .then(
                                                                                                                                                                            replaceRules(
                                                                                                                                                                                    entity
                                                                                                                                                                                            .getId(),
                                                                                                                                                                                    input,
                                                                                                                                                                                    auth))
                                                                                                                                                                    .thenReturn(
                                                                                                                                                                            entity)
                                                                                                                                                                    .doOnNext(
                                                                                                                                                                            created
                                                                                                                                                                                    ::add);
                                                                                                                                                        }));
                                                                                                                    })
                                                                                                            .then(
                                                                                                                    Mono
                                                                                                                            .fromSupplier(
                                                                                                                                    () ->
                                                                                                                                            new BatchResult(
                                                                                                                                                    List
                                                                                                                                                            .copyOf(
                                                                                                                                                                    created),
                                                                                                                                                    List
                                                                                                                                                            .copyOf(
                                                                                                                                                                    skipped))));
                                                                                                }))))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/recorded/create"));
    }

    @Transactional
    public Mono<Detail> update(String projectId, String id, RecordedInfoInput input) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                validated(input, true)
                                        .flatMap(
                                                options ->
                                                        record(projectId, id, false)
                                                                .flatMap(
                                                                        initial -> {
                                                                            // 公司始终先于配置行加锁；公司变更时按 ID
                                                                            // 顺序锁两家公司，避免交叉迁移死锁。
                                                                            return Flux
                                                                                    .fromIterable(
                                                                                            new TreeSet<>(
                                                                                                    List
                                                                                                            .of(
                                                                                                                    initial
                                                                                                                            .getCompanyId(),
                                                                                                                    input
                                                                                                                            .getCompanyId())))
                                                                                    .concatMap(
                                                                                            companyId ->
                                                                                                    companies
                                                                                                            .company(
                                                                                                                    companyId,
                                                                                                                    true))
                                                                                    .then(
                                                                                            record(
                                                                                                    projectId,
                                                                                                    id,
                                                                                                    true))
                                                                                    .flatMap(
                                                                                            old -> {
                                                                                                if (!Objects
                                                                                                                .equals(
                                                                                                                        input
                                                                                                                                .getVersion(),
                                                                                                                        old
                                                                                                                                .getVersion())
                                                                                                        || !Objects
                                                                                                                .equals(
                                                                                                                        old
                                                                                                                                .getCompanyId(),
                                                                                                                        initial
                                                                                                                                .getCompanyId()))
                                                                                                    return Mono
                                                                                                            .error(
                                                                                                                    error(
                                                                                                                            "version_conflict",
                                                                                                                            409));
                                                                                                return companies
                                                                                                        .company(
                                                                                                                input
                                                                                                                        .getCompanyId(),
                                                                                                                false)
                                                                                                        .flatMap(
                                                                                                                company ->
                                                                                                                        linkedBank(
                                                                                                                                        projectId,
                                                                                                                                        input)
                                                                                                                                .flatMap(
                                                                                                                                        bank -> {
                                                                                                                                            var
                                                                                                                                                    next =
                                                                                                                                                            entity(
                                                                                                                                                                    projectId,
                                                                                                                                                                    input,
                                                                                                                                                                    company,
                                                                                                                                                                    bank,
                                                                                                                                                                    new Combination(
                                                                                                                                                                            input.getSourceCharges()
                                                                                                                                                                                    .get(
                                                                                                                                                                                            0),
                                                                                                                                                                            input.getPatternPayments()
                                                                                                                                                                                    .get(
                                                                                                                                                                                            0)),
                                                                                                                                                                    options);
                                                                                                                                            return records.createQuery()
                                                                                                                                                    .where(
                                                                                                                                                            "activeKey",
                                                                                                                                                            next
                                                                                                                                                                    .getActiveKey())
                                                                                                                                                    .and(
                                                                                                                                                            "deleted",
                                                                                                                                                            false)
                                                                                                                                                    .and(
                                                                                                                                                            "id",
                                                                                                                                                            "not",
                                                                                                                                                            id)
                                                                                                                                                    .count()
                                                                                                                                                    .flatMap(
                                                                                                                                                            count ->
                                                                                                                                                                    count
                                                                                                                                                                                    > 0
                                                                                                                                                                            ? Mono
                                                                                                                                                                                    .<Void>
                                                                                                                                                                                            error(
                                                                                                                                                                                                    error(
                                                                                                                                                                                                            "recorded_duplicate",
                                                                                                                                                                                                            409))
                                                                                                                                                                            : Mono
                                                                                                                                                                                    .empty())
                                                                                                                                                    .then(
                                                                                                                                                            writeUpdate(
                                                                                                                                                                    old,
                                                                                                                                                                    next,
                                                                                                                                                                    auth))
                                                                                                                                                    .then(
                                                                                                                                                            replaceRules(
                                                                                                                                                                    id,
                                                                                                                                                                    input,
                                                                                                                                                                    auth))
                                                                                                                                                    .then(
                                                                                                                                                            record(
                                                                                                                                                                    projectId,
                                                                                                                                                                    id,
                                                                                                                                                                    false))
                                                                                                                                                    .flatMap(
                                                                                                                                                            this
                                                                                                                                                                    ::withRules);
                                                                                                                                        }));
                                                                                            });
                                                                        })))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/recorded/update"));
    }

    @Transactional
    public Mono<Void> delete(String projectId, String id, long version) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                record(projectId, id, false)
                                        .flatMap(
                                                initial ->
                                                        companies
                                                                .company(
                                                                        initial.getCompanyId(),
                                                                        true)
                                                                .then(record(projectId, id, true))
                                                                .flatMap(
                                                                        old -> {
                                                                            if (old.getVersion()
                                                                                            != version
                                                                                    || !Objects
                                                                                            .equals(
                                                                                                    old
                                                                                                            .getCompanyId(),
                                                                                                    initial
                                                                                                            .getCompanyId()))
                                                                                return Mono.error(
                                                                                        error(
                                                                                                "version_conflict",
                                                                                                409));
                                                                            return audit(
                                                                                            records
                                                                                                    .createUpdate(),
                                                                                            auth,
                                                                                            version)
                                                                                    .set(
                                                                                            "deleted",
                                                                                            true)
                                                                                    .setNull(
                                                                                            "activeKey")
                                                                                    .set(
                                                                                            "deletedTime",
                                                                                            System
                                                                                                    .currentTimeMillis())
                                                                                    .where("id", id)
                                                                                    .and(
                                                                                            "version",
                                                                                            version)
                                                                                    .execute()
                                                                                    .flatMap(
                                                                                            count ->
                                                                                                    count
                                                                                                                    == 1
                                                                                                            ? retireRules(
                                                                                                                    id,
                                                                                                                    auth)
                                                                                                            : Mono
                                                                                                                    .error(
                                                                                                                            error(
                                                                                                                                    "version_conflict",
                                                                                                                                    409)));
                                                                        })))
                .as(MonoTracer.create("/property/finance/recorded/delete"));
    }

    public Mono<java.math.BigDecimal> preview(
            String projectId, String id, java.math.BigDecimal amount) {
        return detail(projectId, id)
                .map(
                        detail -> {
                            var input = new RecordedInfoInput();
                            BeanUtils.copyProperties(detail.record(), input);
                            input.setTiers(
                                    detail.tiers().stream()
                                            .map(
                                                    row ->
                                                            new RecordedInfoInput.Tier(
                                                                    row.getMinimumAmount(),
                                                                    row.getMaximumAmount(),
                                                                    row.getProcedureRate()))
                                            .toList());
                            return fees.preview(input, amount);
                        });
    }

    /** 调用方必须已持有对应公司行锁；这些检查与引用创建使用同一锁协议。 */
    public Mono<Void> assertCompanyUnused(String companyId) {
        return unused(
                records.createQuery().where("companyId", companyId).and("deleted", false).count());
    }

    public Mono<Void> assertBankUnused(String bankId) {
        return unused(records.createQuery().where("bankId", bankId).and("deleted", false).count());
    }

    public Mono<Void> assertProjectCompanyUnused(String projectId, String companyId) {
        return unused(
                records.createQuery()
                        .where("projectId", projectId)
                        .and("companyId", companyId)
                        .and("deleted", false)
                        .count());
    }

    private Mono<Void> unused(Mono<Integer> count) {
        return count.flatMap(
                value -> value > 0 ? Mono.error(error("recorded_referenced", 409)) : Mono.empty());
    }

    private Mono<Options> validated(RecordedInfoInput input, boolean editing) {
        return Mono.defer(
                () -> {
                    if (input == null
                            || !validId(input.getCompanyId())
                            || !validId(input.getBankId()))
                        return Mono.error(error("recorded_invalid", 400));
                    validateValues(input.getSourceCharges(), editing);
                    validateValues(input.getPatternPayments(), editing);
                    if (editing && (input.getVersion() == null || input.getVersion() < 0))
                        return Mono.error(error("version_conflict", 409));
                    fees.validate(input);
                    // 旧服务商尚无迁移后的真实公共来源。非空不得静默保存未经校验的 ID 或模拟成功。
                    if (input.getFacilitatorId() != null && !input.getFacilitatorId().isBlank())
                        return Mono.error(error("facilitator_source_unavailable", 503));
                    return Mono.zip(
                                    dictionaries.options("source_charge"),
                                    dictionaries.options("pattern_payment"))
                            .map(
                                    values -> {
                                        if (!values.getT1()
                                                        .keySet()
                                                        .containsAll(input.getSourceCharges())
                                                || !values.getT2()
                                                        .keySet()
                                                        .containsAll(input.getPatternPayments()))
                                            throw error("recorded_dictionary_invalid", 400);
                                        return new Options(values.getT1(), values.getT2());
                                    });
                });
    }

    private void validateValues(List<String> values, boolean editing) {
        if (values == null
                || values.isEmpty()
                || values.size() > 50
                || (editing && values.size() != 1)
                || values.stream()
                        .anyMatch(
                                value ->
                                        value == null
                                                || value.isBlank()
                                                || value.length() > 100
                                                || !value.equals(value.trim()))
                || new HashSet<>(values).size() != values.size())
            throw error("recorded_invalid", 400);
    }

    private boolean validId(String value) {
        return value != null && !value.isBlank() && value.length() <= 64;
    }

    private Mono<FinanceBankEntity> linkedBank(String projectId, RecordedInfoInput input) {
        return relations
                .createQuery()
                .where("projectId", projectId)
                .and("companyId", input.getCompanyId())
                .and("deleted", false)
                .count()
                .flatMap(
                        count ->
                                count == 0
                                        ? Mono.error(error("recorded_company_unbound", 409))
                                        : banks.createQuery()
                                                .where("id", input.getBankId())
                                                .and("companyId", input.getCompanyId())
                                                .and("deleted", false)
                                                .fetchOne()
                                                .switchIfEmpty(
                                                        Mono.error(error("bank_invalid", 409))));
    }

    private Mono<RecordedInfoEntity> record(String projectId, String id, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return records.createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", projectId)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("recorded_not_found", 404)));
    }

    private Mono<Detail> withRules(RecordedInfoEntity record) {
        return rules.createQuery()
                .where("recordedId", record.getId())
                .and("deleted", false)
                .fetch()
                .sort(Comparator.comparing(SettlementRuleEntity::getSortIndex))
                .collectList()
                .map(rows -> new Detail(record, rows));
    }

    private RecordedInfoEntity entity(
            String projectId,
            RecordedInfoInput input,
            FinanceCompanyEntity company,
            FinanceBankEntity bank,
            Combination pair,
            Options options) {
        var entity = new RecordedInfoEntity();
        BeanUtils.copyProperties(input, entity, "version", "facilitatorId");
        entity.setProjectId(projectId);
        entity.setSourceCharge(pair.sourceCharge());
        entity.setPatternPayment(pair.patternPayment());
        entity.setCompanyName(company.getCompanyName());
        entity.setBankName(bank.getBankName());
        entity.setSourceChargeText(options.sources().get(pair.sourceCharge()));
        entity.setPatternPaymentText(options.payments().get(pair.patternPayment()));
        entity.setActiveKey(key(projectId, input.getCompanyId(), input.getBankId(), pair));
        return entity;
    }

    private Mono<Void> writeUpdate(
            RecordedInfoEntity old, RecordedInfoEntity next, Authentication auth) {
        var update = records.createUpdate();
        var wrapper = new org.springframework.beans.BeanWrapperImpl(next);
        for (var field :
                List.of(
                        "companyId",
                        "bankId",
                        "sourceCharge",
                        "patternPayment",
                        "companyName",
                        "bankName",
                        "sourceChargeText",
                        "patternPaymentText",
                        "facilitatorId",
                        "serviceChargeType",
                        "minimumFee",
                        "maximumFee",
                        "fixedAmount",
                        "activeKey")) {
            var value = wrapper.getPropertyValue(field);
            if (value == null) update.setNull(field);
            else update.set(field, value);
        }
        return audit(update, auth, old.getVersion())
                .where("id", old.getId())
                .and("version", old.getVersion())
                .and("deleted", false)
                .execute()
                .flatMap(
                        count ->
                                count == 1
                                        ? Mono.empty()
                                        : Mono.error(error("version_conflict", 409)));
    }

    private Mono<Void> replaceRules(String id, RecordedInfoInput input, Authentication auth) {
        var tiers = input.getTiers() == null ? List.<RecordedInfoInput.Tier>of() : input.getTiers();
        return retireRules(id, auth)
                .thenMany(Flux.range(0, tiers.size()))
                .concatMap(
                        index -> {
                            var source = tiers.get(index);
                            var row = new SettlementRuleEntity();
                            row.setId(UUID.randomUUID().toString());
                            row.setRecordedId(id);
                            row.setSortIndex(index);
                            row.setMinimumAmount(source.minimumAmount());
                            row.setMaximumAmount(source.maximumAmount());
                            row.setProcedureRate(source.procedureRate());
                            init(row, auth);
                            return rules.insert(row);
                        })
                .then();
    }

    private Mono<Void> retireRules(String id, Authentication auth) {
        return rules.createQuery()
                .where("recordedId", id)
                .and("deleted", false)
                .fetch()
                .concatMap(
                        row ->
                                audit(rules.createUpdate(), auth, row.getVersion())
                                        .set("deleted", true)
                                        .set("deletedTime", System.currentTimeMillis())
                                        .where("id", row.getId())
                                        .execute())
                .then();
    }

    private String key(String projectId, String companyId, String bankId, Combination pair) {
        // UUID 的稳定摘要不暴露外部输入，长度前缀避免分隔符碰撞；数据库唯一键防跨进程并发重复。
        var parts =
                List.of(projectId, companyId, bankId, pair.sourceCharge(), pair.patternPayment());
        var canonical = new StringBuilder();
        parts.forEach(value -> canonical.append(value.length()).append(':').append(value));
        return UUID.nameUUIDFromBytes(canonical.toString().getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("recorded_duplicate", 409);
        }
        return failure;
    }

    private record Options(Map<String, String> sources, Map<String, String> payments) {}

    public record Combination(String sourceCharge, String patternPayment) {}

    public record BatchResult(List<RecordedInfoEntity> created, List<Combination> skipped) {}

    public record Detail(RecordedInfoEntity record, List<SettlementRuleEntity> tiers) {}

    public record OptionsDetail(
            List<PropertyDictionaryOption> sources,
            List<PropertyDictionaryOption> payments,
            List<CompanyOption> companies) {}

    public record CompanyOption(String id, String companyName, List<BankOption> banks) {}

    public record BankOption(String id, String bankName, String bankAccount) {}
}
