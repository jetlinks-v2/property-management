package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.mapping.ReactiveUpdate;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.tax.InvoiceCompanyInput;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/** 共享税号主档使用全局菜单动作权限；项目关联另行校验组织资产，不能以项目参数缩窄维护权限。 */
@Service
@RequiredArgsConstructor
public class InvoiceCompanyService {
    private final FinanceScope scope;
    private final ReactiveRepository<FinanceCompanyEntity, String> companies;
    private final ReactiveRepository<FinanceBankEntity, String> banks;
    private final ReactiveRepository<FinanceProjectCompanyEntity, String> relations;
    private final ReactiveRepository<RecordedInfoEntity, String> recorded;
    private final ReactiveRepository<TaxItemEntity, String> taxItems;
    private final ReactiveRepository<StandardEntity, String> standards;
    private static final Map<String, Integer> FIELDS =
            Map.ofEntries(
                    Map.entry("companyName", 100),
                    Map.entry("taxNumber", 50),
                    Map.entry("address", 255),
                    Map.entry("phone", 15),
                    Map.entry("depositBank", 100),
                    Map.entry("bankAccount", 100),
                    Map.entry("drawer", 60),
                    Map.entry("reviewer", 60),
                    Map.entry("estateNo", 255),
                    Map.entry("shop", 255),
                    Map.entry("legalPersonId", 90),
                    Map.entry("ratepayingNature", 100),
                    Map.entry("invoiceManufacturerCode", 60),
                    Map.entry("invoiceManufacturerName", 100),
                    Map.entry("invoiceExtensionNumber", 32),
                    Map.entry("departmentId", 64),
                    Map.entry("thirdpartyVendorId", 64));
    private static final Set<String> REQUIRED =
            Set.of(
                    "companyName",
                    "taxNumber",
                    "address",
                    "phone",
                    "depositBank",
                    "bankAccount",
                    "drawer",
                    "reviewer",
                    "estateNo");

    public Mono<PagerResult<FinanceCompanyEntity>> query(QueryParamEntity input) {
        return scope.authenticated()
                .flatMap(
                        auth -> {
                            var query = input.clone();
                            query.setTotal(null);
                            query.setPaging(true);
                            query.setPageIndex(Math.max(0, query.getPageIndex()));
                            query.setPageSize(Math.max(1, Math.min(200, query.getPageSize())));
                            query.toNestQuery(nested -> nested.and("deleted", false));
                            return QueryHelper.queryPager(query, companies::createQuery);
                        });
    }

    public Mono<Detail> detail(String id) {
        return scope.authenticated().then(company(id, false)).flatMap(this::withBanks);
    }

    @Transactional
    public Mono<Detail> create(InvoiceCompanyInput input) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            var entity = validated(input);
                                            entity.setId(UUID.randomUUID().toString());
                                            init(entity, auth);
                                            return companies
                                                    .insert(entity)
                                                    .then(
                                                            replaceBanks(
                                                                    entity.getId(),
                                                                    input.getBanks(),
                                                                    auth))
                                                    .then(withBanks(entity));
                                        }))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/company/create"));
    }

    @Transactional
    public Mono<Detail> update(String id, InvoiceCompanyInput input) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                company(id, true)
                                        .flatMap(
                                                old ->
                                                        Mono.defer(
                                                                () -> {
                                                                    if (input == null
                                                                            || input.getVersion()
                                                                                    == null
                                                                            || !input.getVersion()
                                                                                    .equals(
                                                                                            old
                                                                                                    .getVersion()))
                                                                        return Mono.error(
                                                                                error(
                                                                                        "version_conflict",
                                                                                        409));
                                                                    var next = validated(input);
                                                                    var update =
                                                                            companies
                                                                                    .createUpdate();
                                                                    var wrapper =
                                                                            new org.springframework
                                                                                    .beans
                                                                                    .BeanWrapperImpl(
                                                                                    next);
                                                                    for (var field :
                                                                            FIELDS.keySet()) {
                                                                        var value =
                                                                                wrapper
                                                                                        .getPropertyValue(
                                                                                                field);
                                                                        if (value == null)
                                                                            update.setNull(field);
                                                                        else
                                                                            update.set(
                                                                                    field, value);
                                                                    }
                                                                    if (next.getQuota() == null)
                                                                        update.setNull("quota");
                                                                    else
                                                                        update.set(
                                                                                "quota",
                                                                                next.getQuota());
                                                                    return audit(
                                                                                    update,
                                                                                    auth,
                                                                                    old
                                                                                            .getVersion())
                                                                            .where("id", id)
                                                                            .and(
                                                                                    "version",
                                                                                    old
                                                                                            .getVersion())
                                                                            .and("deleted", false)
                                                                            .execute()
                                                                            .flatMap(
                                                                                    changed ->
                                                                                            changed
                                                                                                            == 1
                                                                                                    ? Mono
                                                                                                            .empty()
                                                                                                    : Mono
                                                                                                            .error(
                                                                                                                    error(
                                                                                                                            "version_conflict",
                                                                                                                            409)))
                                                                            // 主档 CAS
                                                                            // 与银行替换属于同一事务；任何子项失败整笔回滚。
                                                                            .then(
                                                                                    replaceBanks(
                                                                                            id,
                                                                                            input
                                                                                                    .getBanks(),
                                                                                            auth))
                                                                            .then(
                                                                                    company(
                                                                                            id,
                                                                                            false))
                                                                            .flatMap(
                                                                                    this
                                                                                            ::withBanks);
                                                                })))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/company/update"));
    }

    @Transactional
    public Mono<Void> delete(String id, long version) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                company(id, true)
                                        .flatMap(
                                                old -> {
                                                    if (old.getVersion() != version)
                                                        return Mono.error(
                                                                error("version_conflict", 409));
                                                    // 关联写入方同样必须锁住公司；有项目关联时不能级联删除共享公司。
                                                    return standards
                                                            .createQuery()
                                                            .where("companyId", id)
                                                            .and("deleted", false)
                                                            .count()
                                                            .flatMap(
                                                                    count ->
                                                                            count > 0
                                                                                    ? Mono.error(
                                                                                            error(
                                                                                                    "company_referenced",
                                                                                                    409))
                                                                                    : Mono.empty())
                                                            .then(
                                                                    relations
                                                                            .createQuery()
                                                                            .where("companyId", id)
                                                                            .and("deleted", false)
                                                                            .count())
                                                            .flatMap(
                                                                    count ->
                                                                            count > 0
                                                                                    ? Mono.error(
                                                                                            error(
                                                                                                    "company_referenced",
                                                                                                    409))
                                                                                    : Mono.empty())
                                                            .then(
                                                                    recorded.createQuery()
                                                                            .where("companyId", id)
                                                                            .and("deleted", false)
                                                                            .count())
                                                            .flatMap(
                                                                    count ->
                                                                            count > 0
                                                                                    ? Mono.error(
                                                                                            error(
                                                                                                    "company_referenced",
                                                                                                    409))
                                                                                    : Mono.empty())
                                                            .then(
                                                                    taxItems.createQuery()
                                                                            .where("companyId", id)
                                                                            .and("deleted", false)
                                                                            .count())
                                                            .flatMap(
                                                                    count ->
                                                                            count > 0
                                                                                    ? Mono.error(
                                                                                            error(
                                                                                                    "company_referenced",
                                                                                                    409))
                                                                                    : Mono.empty())
                                                            .then(
                                                                    audit(
                                                                                    companies
                                                                                            .createUpdate(),
                                                                                    auth,
                                                                                    version)
                                                                            .set("deleted", true)
                                                                            .set(
                                                                                    "deletedTime",
                                                                                    System
                                                                                            .currentTimeMillis())
                                                                            .where("id", id)
                                                                            .and("version", version)
                                                                            .execute())
                                                            .thenMany(
                                                                    banks.createQuery()
                                                                            .where("companyId", id)
                                                                            .and("deleted", false)
                                                                            .fetch())
                                                            .concatMap(
                                                                    bank ->
                                                                            markBankDeleted(
                                                                                    bank, auth))
                                                            .then();
                                                }))
                .as(MonoTracer.create("/property/finance/company/delete"));
    }

    /** 所有银行变更、项目绑定及后续财务配置引用均共用公司行锁，避免检查后产生新引用。 */
    Mono<FinanceCompanyEntity> company(String id, boolean lock) {
        if (id == null || id.isBlank()) return Mono.error(error("company_not_found", 404));
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return companies
                .createQuery()
                .setParam(query)
                .where("id", id)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("company_not_found", 404)));
    }

    private Mono<Detail> withBanks(FinanceCompanyEntity entity) {
        return banks.createQuery()
                .where("companyId", entity.getId())
                .and("deleted", false)
                .fetch()
                .sort(Comparator.comparing(FinanceBankEntity::getId))
                .collectList()
                .map(list -> new Detail(entity, list));
    }

    private Mono<Void> replaceBanks(
            String companyId, List<InvoiceCompanyInput.BankInput> input, Authentication auth) {
        var requested = input == null ? List.<InvoiceCompanyInput.BankInput>of() : input;
        return banks.createQuery()
                .where("companyId", companyId)
                .and("deleted", false)
                .fetch()
                .collectMap(FinanceBankEntity::getId)
                .flatMap(
                        old -> {
                            var retained = new HashSet<String>();
                            for (var bank : requested) {
                                if (bank.id() != null
                                        && (!old.containsKey(bank.id())
                                                || !retained.add(bank.id())))
                                    return Mono.error(error("bank_invalid", 409));
                            }
                            return Flux.fromIterable(requested)
                                    .concatMap(
                                            bank -> {
                                                if (bank.id() == null) {
                                                    var entity = new FinanceBankEntity();
                                                    entity.setId(UUID.randomUUID().toString());
                                                    entity.setCompanyId(companyId);
                                                    entity.setBankName(bank.bankName().trim());
                                                    entity.setBankAccount(
                                                            bank.bankAccount().trim());
                                                    entity.setDefaultBank(
                                                            Boolean.TRUE.equals(
                                                                    bank.defaultBank()));
                                                    BeanUtils.copyProperties(
                                                            bank,
                                                            entity,
                                                            "id",
                                                            "companyId",
                                                            "bankName",
                                                            "bankAccount",
                                                            "version",
                                                            "deleted",
                                                            "createTime",
                                                            "modifyTime",
                                                            "modifierId",
                                                            "modifierName");
                                                    init(entity, auth);
                                                    return banks.insert(entity).then();
                                                }
                                                var bankUpdate = banks.createUpdate();
                                                var bankWrapper =
                                                        new org.springframework.beans
                                                                .BeanWrapperImpl(bank);
                                                for (var field :
                                                        List.of(
                                                                "financingInstitution",
                                                                "jointLineNumber",
                                                                "accountName",
                                                                "accountNumber",
                                                                "accountBookId")) {
                                                    var value = bankWrapper.getPropertyValue(field);
                                                    if (value == null) bankUpdate.setNull(field);
                                                    else bankUpdate.set(field, value);
                                                }
                                                return audit(
                                                                bankUpdate,
                                                                auth,
                                                                old.get(bank.id()).getVersion())
                                                        .set("bankName", bank.bankName().trim())
                                                        .set(
                                                                "bankAccount",
                                                                bank.bankAccount().trim())
                                                        .set(
                                                                "defaultBank",
                                                                Boolean.TRUE.equals(
                                                                        bank.defaultBank()))
                                                        .where("id", bank.id())
                                                        .execute()
                                                        .then();
                                            })
                                    .thenMany(
                                            Flux.fromIterable(old.values())
                                                    .filter(
                                                            bank ->
                                                                    !retained.contains(
                                                                            bank.getId())))
                                    .concatMap(bank -> markBankDeleted(bank, auth))
                                    .then();
                        });
    }

    private Mono<Void> markBankDeleted(FinanceBankEntity bank, Authentication auth) {
        // 与入账规则维护方共用公司行锁；先检查后更新必须在同一事务内，查询失败原样抛出。
        return recorded.createQuery()
                .where("bankId", bank.getId())
                .and("deleted", false)
                .count()
                .flatMap(
                        count ->
                                count > 0
                                        ? Mono.error(error("bank_referenced", 409))
                                        : Mono.empty())
                .then(
                        audit(banks.createUpdate(), auth, bank.getVersion())
                                .set("deleted", true)
                                .set("deletedTime", System.currentTimeMillis())
                                .where("id", bank.getId())
                                .execute())
                .then();
    }

    private FinanceCompanyEntity validated(InvoiceCompanyInput input) {
        if (input == null) throw error("invalid_company", 400);
        var entity = new FinanceCompanyEntity();
        BeanUtils.copyProperties(input, entity);
        var wrapper = new org.springframework.beans.BeanWrapperImpl(entity);
        for (var field : FIELDS.entrySet()) {
            var value = (String) wrapper.getPropertyValue(field.getKey());
            value = value == null ? null : value.trim();
            if ((REQUIRED.contains(field.getKey()) && (value == null || value.isBlank()))
                    || (value != null && value.length() > field.getValue()))
                throw error("invalid_company", 400);
            wrapper.setPropertyValue(field.getKey(), value);
        }
        entity.setTaxNumber(entity.getTaxNumber().toUpperCase(Locale.ROOT));
        if (!entity.getTaxNumber().matches("[A-Z0-9_]+")
                || !entity.getBankAccount().matches("[0-9]+")
                || (entity.getInvoiceExtensionNumber() != null
                        && !entity.getInvoiceExtensionNumber().matches("[0-9]*"))
                || (entity.getQuota() != null && entity.getQuota().signum() < 0))
            throw error("invalid_company", 400);
        var inputBanks =
                input.getBanks() == null
                        ? List.<InvoiceCompanyInput.BankInput>of()
                        : input.getBanks();
        if (inputBanks.stream().anyMatch(Objects::isNull)) throw error("bank_invalid", 400);
        if (inputBanks.stream().filter(bank -> Boolean.TRUE.equals(bank.defaultBank())).count() > 1)
            throw error("bank_default", 400);
        for (var bank : inputBanks) {
            var optional =
                    new String[] {
                        bank.financingInstitution(),
                        bank.jointLineNumber(),
                        bank.accountName(),
                        bank.accountNumber()
                    };
            if (Arrays.stream(optional).anyMatch(value -> value != null && value.length() > 100)
                    || (bank.accountBookId() != null && bank.accountBookId().length() > 64))
                throw error("bank_invalid", 400);
            if (bank.bankName() == null
                    || bank.bankName().isBlank()
                    || bank.bankName().length() > 100
                    || bank.bankAccount() == null
                    || !bank.bankAccount().trim().matches("[0-9]{1,100}"))
                throw error("bank_invalid", 400);
        }
        return entity;
    }

    static void init(FinanceEntity entity, Authentication auth) {
        entity.setVersion(0L);
        entity.setDeleted(false);
        entity.setCreateTime(System.currentTimeMillis());
        entity.setModifyTime(entity.getCreateTime());
        entity.setModifierId(auth.getUser().getId());
        entity.setModifierName(auth.getUser().getUsername());
    }

    static <T> ReactiveUpdate<T> audit(
            ReactiveUpdate<T> update, Authentication auth, long version) {
        return update.set("version", version + 1)
                .set("modifyTime", System.currentTimeMillis())
                .set("modifierId", auth.getUser().getId())
                .set("modifierName", auth.getUser().getUsername());
    }

    private Throwable duplicate(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("company_duplicate", 409);
        }
        return error;
    }

    public record Detail(FinanceCompanyEntity company, List<FinanceBankEntity> banks) {}
}
