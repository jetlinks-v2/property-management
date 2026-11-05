package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.finance.entity.ProjectSubjectEntity;
import org.jetlinks.pro.property.finance.entity.SubjectEntity;
import org.jetlinks.pro.property.finance.subject.SubjectInput;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

@Service
@RequiredArgsConstructor
public class SubjectService {
    private final FinanceScope scope;
    private final SubjectCategoryService categories;
    private final ArrearsService arrears;
    private final SubjectDictionaryService dictionaries;
    private final ReactiveRepository<SubjectEntity, String> subjects;
    private final ReactiveRepository<ProjectSubjectEntity, String> projects;

    public Mono<PagerResult<SubjectEntity>> query(QueryParamEntity input, String categoryId) {
        return scope.authenticated()
                .then(categories.descendants(categoryId))
                .flatMap(
                        ids -> {
                            var query = page(input);
                            query.toNestQuery(
                                    nested -> {
                                        nested.and("deleted", false);
                                        if (!ids.isEmpty()) nested.and("categoryId", "in", ids);
                                    });
                            return QueryHelper.queryPager(query, subjects::createQuery);
                        });
    }

    public Mono<SubjectEntity> detail(String id) {
        return scope.authenticated().then(subject(id, false));
    }

    @Transactional
    public Mono<SubjectEntity> create(SubjectInput input) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            var entity = validate(input);
                                            entity.setId(UUID.randomUUID().toString());
                                            // 18 位字母数字编码满足旧表单 20 位约束，唯一性由数据库兜底，无用户输入码。
                                            entity.setCode(
                                                    "KM"
                                                            + UUID.randomUUID()
                                                                    .toString()
                                                                    .replace("-", "")
                                                                    .substring(0, 16)
                                                                    .toUpperCase(Locale.ROOT));
                                            init(entity, auth);
                                            return validateReferences(entity)
                                                    .then(subjects.insert(entity))
                                                    .thenReturn(entity);
                                        }))
                .onErrorMap(SubjectService::duplicate)
                .as(MonoTracer.create("/property/finance/subject/create"));
    }

    @Transactional
    public Mono<SubjectEntity> update(String id, SubjectInput input) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            var next = validate(input);
                                            // 引用锁先于科目锁，遵循目录/欠费配置 -> 科目 -> 项目关系的统一顺序。
                                            return validateReferences(next)
                                                    .then(subject(id, true))
                                                    .flatMap(
                                                            old -> {
                                                                if (input.getVersion() == null
                                                                        || !input.getVersion()
                                                                                .equals(
                                                                                        old
                                                                                                .getVersion()))
                                                                    return Mono.error(
                                                                            error(
                                                                                    "version_conflict",
                                                                                    409));
                                                                var update =
                                                                        audit(
                                                                                        subjects
                                                                                                .createUpdate(),
                                                                                        auth,
                                                                                        old
                                                                                                .getVersion())
                                                                                .set(
                                                                                        "categoryId",
                                                                                        next
                                                                                                .getCategoryId())
                                                                                .set(
                                                                                        "name",
                                                                                        next
                                                                                                .getName())
                                                                                .set(
                                                                                        "nameKey",
                                                                                        next
                                                                                                .getNameKey())
                                                                                .set(
                                                                                        "feeType",
                                                                                        next
                                                                                                .getFeeType())
                                                                                .set(
                                                                                        "enabled",
                                                                                        next
                                                                                                .getEnabled())
                                                                                .set(
                                                                                        "autoInvoice",
                                                                                        next
                                                                                                .getAutoInvoice())
                                                                                .set(
                                                                                        "arrearsConfigId",
                                                                                        next
                                                                                                .getArrearsConfigId());
                                                                if (next.getRemark() == null)
                                                                    update.setNull("remark");
                                                                else
                                                                    update.set(
                                                                            "remark",
                                                                            next.getRemark());
                                                                return update.where("id", id)
                                                                        .and(
                                                                                "version",
                                                                                old.getVersion())
                                                                        .and("deleted", false)
                                                                        .execute()
                                                                        .flatMap(
                                                                                SubjectService
                                                                                        ::changed)
                                                                        .then(subject(id, false));
                                                            });
                                        }))
                .onErrorMap(SubjectService::duplicate)
                .as(MonoTracer.create("/property/finance/subject/update"));
    }

    @Transactional
    public Mono<Void> delete(String id, long version) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                subject(id, true)
                                        .flatMap(
                                                old -> {
                                                    if (old.getVersion() != version)
                                                        return Mono.error(
                                                                error("version_conflict", 409));
                                                    return projects.createQuery()
                                                            .where("subjectId", id)
                                                            .and("deleted", false)
                                                            .count()
                                                            .flatMap(SubjectService::unreferenced)
                                                            .then(
                                                                    audit(
                                                                                    subjects
                                                                                            .createUpdate(),
                                                                                    auth,
                                                                                    version)
                                                                            .set("deleted", true)
                                                                            .setNull("nameKey")
                                                                            .set(
                                                                                    "deletedTime",
                                                                                    System
                                                                                            .currentTimeMillis())
                                                                            .where("id", id)
                                                                            .and("version", version)
                                                                            .execute())
                                                            .flatMap(SubjectService::changed);
                                                }))
                .as(MonoTracer.create("/property/finance/subject/delete"));
    }

    /** 固定科目锁序，末项版本或引用校验失败回滚本批全部逻辑删除。 */
    @Transactional
    public Mono<Void> deleteBatch(List<VersionedId> input) {
        if (input == null
                || input.isEmpty()
                || input.size() > 200
                || input.stream()
                        .anyMatch(
                                row ->
                                        row == null
                                                || row.id() == null
                                                || row.id().isBlank()
                                                || row.id().length() > 64
                                                || row.version() == null
                                                || row.version() < 0)
                || input.stream().map(VersionedId::id).distinct().count() != input.size())
            return Mono.error(error("subject_batch_invalid", 400));
        return scope.authenticated()
                .thenMany(
                        Flux.fromIterable(input)
                                .sort(Comparator.comparing(VersionedId::id))
                                .concatMap(row -> delete(row.id(), row.version())))
                .then()
                .as(MonoTracer.create("/property/finance/subject/delete-batch"));
    }

    public record VersionedId(String id, Long version) {}

    /** 创建标准等后续真实引用时必须先锁定科目，沿用此删除/引用互斥协议。 */
    Mono<SubjectEntity> subject(String id, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return subjects.createQuery()
                .setParam(query)
                .where("id", id)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("subject_not_found", 404)));
    }

    private Mono<Void> validateReferences(SubjectEntity entity) {
        return categories
                .category(entity.getCategoryId(), true)
                .then(arrears.config(entity.getArrearsConfigId(), true))
                .then(dictionaries.assertValue("charge-item-in-type", entity.getFeeType()));
    }

    private SubjectEntity validate(SubjectInput input) {
        if (input == null
                || input.getName() == null
                || input.getName().isBlank()
                || input.getName().trim().length() > 100
                || input.getCategoryId() == null
                || input.getCategoryId().isBlank()
                || input.getCategoryId().length() > 64
                || input.getFeeType() == null
                || input.getFeeType().isBlank()
                || input.getFeeType().length() > 64
                || input.getArrearsConfigId() == null
                || input.getArrearsConfigId().isBlank()
                || input.getArrearsConfigId().length() > 64
                || input.getEnabled() == null
                || input.getAutoInvoice() == null
                || (input.getRemark() != null && input.getRemark().length() > 250))
            throw error("subject_invalid", 400);
        var entity = new SubjectEntity();
        entity.setName(input.getName().trim());
        entity.setNameKey(entity.getName());
        entity.setCategoryId(input.getCategoryId());
        entity.setFeeType(input.getFeeType());
        entity.setEnabled(input.getEnabled());
        entity.setAutoInvoice(input.getAutoInvoice());
        entity.setArrearsConfigId(input.getArrearsConfigId());
        entity.setRemark(
                input.getRemark() == null || input.getRemark().isBlank()
                        ? null
                        : input.getRemark().trim());
        return entity;
    }

    static QueryParamEntity page(QueryParamEntity input) {
        var query = input.clone();
        query.setTotal(null);
        query.setPaging(true);
        query.setPageIndex(Math.max(0, query.getPageIndex()));
        query.setPageSize(Math.min(200, Math.max(1, query.getPageSize())));
        return query;
    }

    static Mono<Void> changed(int count) {
        return count == 1 ? Mono.empty() : Mono.error(error("version_conflict", 409));
    }

    static Mono<Void> unreferenced(int count) {
        return count == 0 ? Mono.empty() : Mono.error(error("subject_referenced", 409));
    }

    static Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("subject_duplicate", 409);
        }
        return failure;
    }
}
