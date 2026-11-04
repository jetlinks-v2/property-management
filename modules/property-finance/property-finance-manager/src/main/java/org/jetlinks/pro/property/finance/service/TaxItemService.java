package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.audit;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.init;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.tax.TaxItemInput;
import org.jetlinks.pro.property.finance.tax.TaxItemRules;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/** 项目只能访问有效关联公司的共享税目；任何写入先锁公司，与公司删除使用同一锁协议。 */
@Service
@RequiredArgsConstructor
public class TaxItemService {
    private final FinanceScope scope;
    private final InvoiceCompanyService companies;
    private final ReactiveRepository<FinanceProjectCompanyEntity, String> relations;
    private final ReactiveRepository<TaxItemEntity, String> items;
    private final ReactiveRepository<TaxOperationEntity, String> operations;
    private final ReactiveRepository<StandardEntity, String> standards;

    public Mono<PagerResult<TaxItemEntity>> query(String projectId, QueryParamEntity input) {
        return scope.project(projectId, CrudAssetPermission.read)
                .thenMany(
                        relations
                                .createQuery()
                                .where("projectId", projectId)
                                .and("deleted", false)
                                .fetch())
                .map(FinanceProjectCompanyEntity::getCompanyId)
                .collectList()
                .flatMap(
                        ids -> {
                            var query = bounded(input);
                            // 外部OR条件嵌套后再附加项目公司集合，不能由调用方放宽归属或删除过滤。
                            query.toNestQuery(
                                    nested ->
                                            nested.and("deleted", false)
                                                    .in(
                                                            "companyId",
                                                            ids.isEmpty() ? List.of("") : ids));
                            return QueryHelper.queryPager(query, items::createQuery);
                        });
    }

    public Mono<TaxItemEntity> detail(String projectId, String id) {
        return scope.project(projectId, CrudAssetPermission.read)
                .then(item(id, false))
                .flatMap(item -> related(projectId, item.getCompanyId(), false).thenReturn(item));
    }

    /** 后续收费标准/票据选择应在所属事务内锁定并快照此结果，不得直接读取表绕过校验。 */
    @Transactional
    public Mono<TaxItemEntity> requireUsable(String projectId, String companyId, String id) {
        return scope.project(projectId, CrudAssetPermission.read)
                .then(related(projectId, companyId, true))
                .then(item(id, true))
                .flatMap(
                        item ->
                                item.getCompanyId().equals(companyId)
                                                && Boolean.TRUE.equals(item.getEnabled())
                                        ? Mono.just(item)
                                        : Mono.error(error("tax_unavailable", 409)));
    }

    @Transactional
    public Mono<TaxItemEntity> create(String projectId, TaxItemInput input) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            var item = TaxItemRules.validate(input);
                                            return related(projectId, item.getCompanyId(), true)
                                                    .flatMap(
                                                            company -> {
                                                                item.setId(
                                                                        UUID.randomUUID()
                                                                                .toString());
                                                                init(item, auth);
                                                                item.setEnabled(true);
                                                                item.setSellerTaxNum(
                                                                        company.getTaxNumber());
                                                                item.setCompanyName(
                                                                        company.getCompanyName());
                                                                item.setSyncStatus("LOCAL");
                                                                return items.insert(item)
                                                                        .then(
                                                                                log(
                                                                                        null, item,
                                                                                        "CREATE",
                                                                                        auth))
                                                                        .thenReturn(item);
                                                            });
                                        }))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/tax/create"));
    }

    @Transactional
    public Mono<TaxItemEntity> update(String projectId, String id, TaxItemInput input) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            var next = TaxItemRules.validate(input);
                                            return related(projectId, next.getCompanyId(), true)
                                                    .then(item(id, true))
                                                    .flatMap(
                                                            old -> {
                                                                if (!old.getCompanyId()
                                                                        .equals(
                                                                                next
                                                                                        .getCompanyId()))
                                                                    return Mono.error(
                                                                            error(
                                                                                    "tax_company_immutable",
                                                                                    409));
                                                                if (input.getVersion() == null
                                                                        || !input.getVersion()
                                                                                .equals(
                                                                                        old
                                                                                                .getVersion()))
                                                                    return Mono.error(
                                                                            error(
                                                                                    "version_conflict",
                                                                                    409));
                                                                var update = items.createUpdate();
                                                                var wrapper =
                                                                        new BeanWrapperImpl(next);
                                                                var fields =
                                                                        new ArrayList<>(
                                                                                TaxItemRules.FIELDS
                                                                                        .keySet());
                                                                fields.addAll(
                                                                        List.of(
                                                                                "taxRate",
                                                                                "price",
                                                                                "includingTaxFlag",
                                                                                "favouredPolicyFlag",
                                                                                "matchKey"));
                                                                for (var field : fields) {
                                                                    var value =
                                                                            wrapper
                                                                                    .getPropertyValue(
                                                                                            field);
                                                                    if (value == null)
                                                                        update.setNull(field);
                                                                    else update.set(field, value);
                                                                }
                                                                return audit(
                                                                                update,
                                                                                auth,
                                                                                old.getVersion())
                                                                        .set("syncStatus", "LOCAL")
                                                                        .where("id", id)
                                                                        .and(
                                                                                "version",
                                                                                old.getVersion())
                                                                        .and("deleted", false)
                                                                        .execute()
                                                                        .flatMap(
                                                                                count ->
                                                                                        count == 1
                                                                                                ? item(
                                                                                                        id,
                                                                                                        false)
                                                                                                : Mono
                                                                                                        .<TaxItemEntity>
                                                                                                                error(
                                                                                                                        error(
                                                                                                                                "version_conflict",
                                                                                                                                409)))
                                                                        .flatMap(
                                                                                saved ->
                                                                                        log(
                                                                                                        old,
                                                                                                        saved,
                                                                                                        "UPDATE",
                                                                                                        auth)
                                                                                                .thenReturn(
                                                                                                        saved));
                                                            });
                                        }))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/tax/update"));
    }

    @Transactional
    public Mono<TaxItemEntity> state(String projectId, String id, Long version, boolean enabled) {
        if (version == null) return Mono.error(error("tax_invalid_state", 400));
        return change(projectId, id, version, enabled, false)
                .as(MonoTracer.create("/property/finance/tax/state"));
    }

    @Transactional
    public Mono<Void> delete(String projectId, String id, Long version) {
        if (version == null) return Mono.error(error("tax_invalid_state", 400));
        return change(projectId, id, version, false, true)
                .then()
                .as(MonoTracer.create("/property/finance/tax/delete"));
    }

    /** 批量删除按公司和ID固定顺序获取锁，任一版本失败整批回滚。 */
    @Transactional
    public Mono<Void> deleteBatch(String projectId, List<VersionedId> requested) {
        if (requested == null
                || requested.isEmpty()
                || requested.size() > 200
                || requested.stream()
                        .anyMatch(row -> row == null || row.id() == null || row.version() == null)
                || requested.stream().map(VersionedId::id).distinct().count() != requested.size())
            return Mono.error(error("tax_invalid_batch", 400));
        return scope.project(projectId, CrudAssetPermission.save)
                .thenMany(
                        Flux.fromIterable(requested)
                                .concatMap(
                                        row ->
                                                item(row.id(), false)
                                                        .map(item -> Map.entry(item, row))))
                .sort(
                        Comparator.comparing(
                                        (Map.Entry<TaxItemEntity, VersionedId> row) ->
                                                row.getKey().getCompanyId())
                                .thenComparing(row -> row.getKey().getId()))
                .concatMap(
                        row ->
                                change(
                                        projectId,
                                        row.getValue().id(),
                                        row.getValue().version(),
                                        false,
                                        true))
                .then();
    }

    private Mono<TaxItemEntity> change(
            String projectId, String id, long version, boolean enabled, boolean deleted) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                item(id, false)
                                        .flatMap(
                                                existing ->
                                                        related(
                                                                projectId,
                                                                existing.getCompanyId(),
                                                                true))
                                        .then(item(id, true))
                                        .flatMap(
                                                old -> {
                                                    if (old.getVersion() != version)
                                                        return Mono.error(
                                                                error("version_conflict", 409));
                                                    var update =
                                                            audit(
                                                                            items.createUpdate(),
                                                                            auth,
                                                                            version)
                                                                    .set("enabled", enabled);
                                                    if (deleted)
                                                        update.set("deleted", true)
                                                                .set(
                                                                        "deletedTime",
                                                                        System.currentTimeMillis());
                                                    // 新标准同样先持有公司和税目锁，检查后不能再穿入有效引用。
                                                    Mono<Void> guard =
                                                            enabled && !deleted
                                                                    ? Mono.empty()
                                                                    : standards
                                                                            .createQuery()
                                                                            .where("taxItemId", id)
                                                                            .and("deleted", false)
                                                                            .count()
                                                                            .flatMap(
                                                                                    count ->
                                                                                            count
                                                                                                            > 0
                                                                                                    ? Mono
                                                                                                            .error(
                                                                                                                    error(
                                                                                                                            "tax_referenced",
                                                                                                                            409))
                                                                                                    : Mono
                                                                                                            .empty());
                                                    return guard.then(
                                                            update.where("id", id)
                                                                    .and("version", version)
                                                                    .and("deleted", false)
                                                                    .execute()
                                                                    .flatMap(
                                                                            count ->
                                                                                    count == 1
                                                                                            ? items
                                                                                                    .findById(
                                                                                                            id)
                                                                                            : Mono
                                                                                                    .<TaxItemEntity>
                                                                                                            error(
                                                                                                                    error(
                                                                                                                            "version_conflict",
                                                                                                                            409)))
                                                                    .flatMap(
                                                                            saved ->
                                                                                    log(
                                                                                                    old,
                                                                                                    saved,
                                                                                                    deleted
                                                                                                            ? "DELETE"
                                                                                                            : enabled
                                                                                                                    ? "ENABLE"
                                                                                                                    : "DISABLE",
                                                                                                    auth)
                                                                                            .thenReturn(
                                                                                                    saved)));
                                                }));
    }

    public Mono<PagerResult<TaxOperationEntity>> logs(
            String projectId, String id, QueryParamEntity input) {
        return scope.project(projectId, CrudAssetPermission.read)
                .then(items.findById(id))
                .switchIfEmpty(Mono.error(error("tax_not_found", 404)))
                .flatMap(item -> related(projectId, item.getCompanyId(), false))
                .flatMap(
                        company -> {
                            var query = bounded(input);
                            query.toNestQuery(nested -> nested.and("taxItemId", id));
                            query.toQuery().orderByDesc("createTime");
                            return QueryHelper.queryPager(query, operations::createQuery);
                        });
    }

    Mono<FinanceCompanyEntity> related(String projectId, String companyId, boolean lock) {
        return companies
                .company(companyId, lock)
                .flatMap(
                        company -> {
                            var query = new QueryParamEntity();
                            query.setForUpdate(lock);
                            return relations
                                    .createQuery()
                                    .setParam(query)
                                    .where("projectId", projectId)
                                    .and("companyId", companyId)
                                    .and("deleted", false)
                                    .fetchOne()
                                    .switchIfEmpty(Mono.error(error("tax_company_unrelated", 403)))
                                    .thenReturn(company);
                        });
    }

    Mono<TaxItemEntity> item(String id, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return items.createQuery()
                .setParam(query)
                .where("id", id)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("tax_not_found", 404)));
    }

    Mono<Void> log(
            TaxItemEntity before, TaxItemEntity after, String operation, Authentication auth) {
        var log = new TaxOperationEntity();
        log.setId(UUID.randomUUID().toString());
        init(log, auth);
        log.setTaxItemId(after.getId());
        log.setCompanyId(after.getCompanyId());
        log.setOperation(operation);
        log.setBeforeSnapshot(before == null ? null : snapshot(before));
        log.setAfterSnapshot(snapshot(after));
        return operations.insert(log).then();
    }

    private String snapshot(TaxItemEntity item) {
        var fields = new LinkedHashMap<String, Object>();
        var wrapper = new BeanWrapperImpl(item);
        for (var field : TaxItemRules.FIELDS.keySet())
            fields.put(field, wrapper.getPropertyValue(field));
        fields.put("price", item.getPrice() == null ? null : item.getPrice().toPlainString());
        fields.put("taxRate", item.getTaxRate());
        fields.put("includingTaxFlag", item.getIncludingTaxFlag());
        fields.put("favouredPolicyFlag", item.getFavouredPolicyFlag());
        fields.put("enabled", item.getEnabled());
        return JSON.toJSONString(fields);
    }

    static QueryParamEntity bounded(QueryParamEntity input) {
        var query = input == null ? new QueryParamEntity() : input.clone();
        query.setTotal(null);
        query.setPaging(true);
        query.setPageIndex(Math.max(0, query.getPageIndex()));
        query.setPageSize(Math.max(1, Math.min(200, query.getPageSize())));
        return query;
    }

    Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("tax_duplicate", 409);
        }
        return failure;
    }

    public record VersionedId(String id, Long version) {}
}
