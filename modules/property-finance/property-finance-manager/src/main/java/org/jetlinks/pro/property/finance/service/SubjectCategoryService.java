package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.finance.entity.SubjectCategoryEntity;
import org.jetlinks.pro.property.finance.entity.SubjectEntity;
import org.jetlinks.pro.property.finance.subject.SubjectCategoryInput;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

@Service
@RequiredArgsConstructor
public class SubjectCategoryService {
    private final FinanceScope scope;
    private final ReactiveRepository<SubjectCategoryEntity, String> categories;
    private final ReactiveRepository<SubjectEntity, String> subjects;

    public Flux<SubjectCategoryEntity> list() {
        var query = new QueryParamEntity();
        query.setPaging(false);
        query.orderBy("sortIndex");
        query.orderBy("id");
        return scope.authenticated()
                .thenMany(categories.createQuery().setParam(query).where("deleted", false).fetch());
    }

    @Transactional
    public Mono<SubjectCategoryEntity> create(SubjectCategoryInput input) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            var entity = validate(input);
                                            entity.setId(UUID.randomUUID().toString());
                                            init(entity, auth);
                                            Mono<Void> parent =
                                                    entity.getParentId().isEmpty()
                                                            ? Mono.empty()
                                                            : category(entity.getParentId(), true)
                                                                    .then();
                                            // 子目录与科目创建持有父目录锁，删除检查和新增引用不能穿插。
                                            return parent.then(categories.insert(entity))
                                                    .thenReturn(entity);
                                        }))
                .onErrorMap(SubjectService::duplicate);
    }

    @Transactional
    public Mono<SubjectCategoryEntity> update(String id, SubjectCategoryInput input) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                category(id, true)
                                        .flatMap(
                                                old ->
                                                        Mono.defer(
                                                                () -> {
                                                                    var next = validate(input);
                                                                    if (input.version() == null
                                                                            || !input.version()
                                                                                    .equals(
                                                                                            old
                                                                                                    .getVersion()))
                                                                        return Mono.error(
                                                                                error(
                                                                                        "version_conflict",
                                                                                        409));
                                                                    if (!old.getParentId()
                                                                            .equals(
                                                                                    next
                                                                                            .getParentId()))
                                                                        return Mono.error(
                                                                                error(
                                                                                        "category_parent_immutable",
                                                                                        400));
                                                                    return audit(
                                                                                    categories
                                                                                            .createUpdate(),
                                                                                    auth,
                                                                                    old
                                                                                            .getVersion())
                                                                            .set(
                                                                                    "name",
                                                                                    next.getName())
                                                                            .set(
                                                                                    "nameKey",
                                                                                    next
                                                                                            .getNameKey())
                                                                            .set(
                                                                                    "sortIndex",
                                                                                    next
                                                                                            .getSortIndex())
                                                                            .where("id", id)
                                                                            .and(
                                                                                    "version",
                                                                                    old
                                                                                            .getVersion())
                                                                            .and("deleted", false)
                                                                            .execute()
                                                                            .flatMap(
                                                                                    SubjectService
                                                                                            ::changed)
                                                                            .then(
                                                                                    category(
                                                                                            id,
                                                                                            false));
                                                                })))
                .onErrorMap(SubjectService::duplicate);
    }

    @Transactional
    public Mono<Void> delete(String id, long version) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                category(id, true)
                                        .flatMap(
                                                old -> {
                                                    if (old.getVersion() != version)
                                                        return Mono.error(
                                                                error("version_conflict", 409));
                                                    return categories
                                                            .createQuery()
                                                            .where("parentId", id)
                                                            .and("deleted", false)
                                                            .count()
                                                            .flatMap(SubjectService::unreferenced)
                                                            .then(
                                                                    subjects.createQuery()
                                                                            .where("categoryId", id)
                                                                            .and("deleted", false)
                                                                            .count())
                                                            .flatMap(SubjectService::unreferenced)
                                                            .then(
                                                                    audit(
                                                                                    categories
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
                                                }));
    }

    Mono<SubjectCategoryEntity> category(String id, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return categories
                .createQuery()
                .setParam(query)
                .where("id", id)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("category_not_found", 404)));
    }

    public Mono<List<String>> descendants(String id) {
        if (id == null || id.isBlank()) return Mono.just(List.of());
        return category(id, false)
                .thenMany(list())
                .collectList()
                .map(
                        rows -> {
                            var children = new HashMap<String, List<String>>();
                            for (var row : rows)
                                children.computeIfAbsent(
                                                row.getParentId(), key -> new ArrayList<>())
                                        .add(row.getId());
                            var result = new LinkedHashSet<String>();
                            var pending = new ArrayDeque<String>();
                            pending.add(id);
                            while (!pending.isEmpty()) {
                                var next = pending.removeFirst();
                                if (result.add(next))
                                    pending.addAll(children.getOrDefault(next, List.of()));
                            }
                            return List.copyOf(result);
                        });
    }

    private SubjectCategoryEntity validate(SubjectCategoryInput input) {
        if (input == null
                || input.name() == null
                || input.name().isBlank()
                || input.name().trim().length() > 100
                || input.name().contains("/")
                || (input.parentId() != null && input.parentId().length() > 64)
                || (input.sortIndex() != null && input.sortIndex() < 0))
            throw error("category_invalid", 400);
        var entity = new SubjectCategoryEntity();
        entity.setParentId(input.parentId() == null ? "" : input.parentId());
        entity.setName(input.name().trim());
        entity.setNameKey(entity.getName());
        entity.setSortIndex(input.sortIndex() == null ? 0 : input.sortIndex());
        return entity;
    }
}
