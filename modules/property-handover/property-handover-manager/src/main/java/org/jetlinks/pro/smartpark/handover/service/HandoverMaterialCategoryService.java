package org.jetlinks.pro.smartpark.handover.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.NotFoundException;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.pro.smartpark.handover.entity.HandoverMaterialCategoryEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 资料分类按当前应用共享；读写均取服务端上下文，不接受客户端租户或应用范围。 */
@Service
@RequiredArgsConstructor
public class HandoverMaterialCategoryService {
    private final HandoverScope scope;
    private final HandoverDirectory directory;
    private final HandoverMaterialCategoryRepository repository;
    private final HandoverRecordRepository records;

    private QueryParamEntity selector(HandoverContext context) {
        return scope.mandatory(new QueryParamEntity(), context, null);
    }

    public Mono<PagerResult<HandoverMaterialCategoryEntity>> query(HandoverQuery input) {
        return scope.current().flatMap(context -> {
            var query = selector(context);
            query.setPageIndex(Math.max(0, input.getPageIndex()));
            query.setPageSize(Math.max(1, Math.min(200, input.getPageSize())));
            query.toQuery().orderByAsc("sortIndex").orderByDesc("modifyTime").orderByAsc("id");
            if (HandoverRules.hasText(input.getKeyword()))
                query.toQuery().like("name", "%" + input.getKeyword() + "%");
            if (HandoverRules.hasText(input.getCategoryId()))
                query.toQuery().nest().and("id", input.getCategoryId())
                    .or("parentId", input.getCategoryId()).end();
            if (input.getEnabled() != null) query.toQuery().and("enabled", input.getEnabled());
            if (HandoverRules.hasText(input.getOperator()))
                query.toQuery().like("modifierName", "%" + input.getOperator() + "%");
            if (input.getModifyTimeFrom() != null)
                query.toQuery().and("modifyTime", "gte", input.getModifyTimeFrom());
            if (input.getModifyTimeTo() != null)
                query.toQuery().and("modifyTime", "lte", input.getModifyTimeTo());
            return repository.queryPager(query).flatMap(page -> names(context, page.getData()).thenReturn(page));
        });
    }

    private Mono<Void> names(HandoverContext context, List<HandoverMaterialCategoryEntity> rows) {
        var ids = rows.stream().map(HandoverMaterialCategoryEntity::getParentId)
                .filter(HandoverRules::hasText).distinct().toList();
        if (ids.isEmpty()) return Mono.empty();
        return repository.createQuery().setParam(selector(context)).in("id", ids).fetch()
                .collectMap(HandoverMaterialCategoryEntity::getId, HandoverMaterialCategoryEntity::getName)
                .doOnNext(names -> rows.forEach(row -> row.setParentName(names.get(row.getParentId())))).then();
    }

    public Flux<HandoverMaterialCategoryEntity> tree() {
        return scope.current().flatMapMany(context -> {
                    var query = selector(context);
                    query.toQuery().orderByAsc("sortIndex").orderByAsc("id");
                    return repository.createQuery().setParam(query).fetch();
                })
                .take(20001).collectList().flatMapMany(rows -> {
                    HandoverRules.require(rows.size() <= 20000, "excel_reference_limit");
                    var indexed = rows.stream().collect(Collectors.toMap(
                            HandoverMaterialCategoryEntity::getId, Function.identity()));
                    rows.forEach(row -> {
                        if (HandoverRules.hasText(row.getParentId())) {
                            var parent = indexed.get(row.getParentId());
                            HandoverRules.require(parent != null, "category_invalid");
                            parent.getChildren().add(row);
                            row.setParentName(parent.getName());
                        }
                    });
                    return Flux.fromIterable(rows).filter(row -> !HandoverRules.hasText(row.getParentId()));
                });
    }

    public Mono<HandoverMaterialCategoryEntity> read(String id) {
        return scope.current().flatMap(context -> repository.findById(id)
                .switchIfEmpty(Mono.error(new NotFoundException()))
                .doOnNext(row -> scope.owned(row, context))
                .flatMap(row -> names(context, List.of(row)).thenReturn(row)));
    }

    /** 停用目录仍可解析历史名称，但不能作为新增/修改记录的可选资料分类。 */
    public Mono<Void> selectable(String id) {
        if (!HandoverRules.hasText(id)) return Mono.empty();
        return read(id).flatMap(row -> {
            HandoverRules.require(Boolean.TRUE.equals(row.getEnabled()), "category_invalid");
            return HandoverRules.hasText(row.getParentId())
                    ? read(row.getParentId()).doOnNext(parent -> HandoverRules.require(
                            Boolean.TRUE.equals(parent.getEnabled()), "category_invalid")).then()
                    : Mono.empty();
        });
    }

    @Transactional
    public Mono<HandoverMaterialCategoryEntity> save(HandoverMaterialCategoryEntity input) {
        HandoverRules.require(HandoverRules.hasText(input.getName()) && input.getName().length() <= 128,
                "name_required");
        input.setName(input.getName().trim());
        if (input.getEnabled() == null) input.setEnabled(true);
        if (!HandoverRules.hasText(input.getParentId())) input.setParentId(null);
        return directory.employee().then(scope.current()).flatMap(context -> {
            Mono<Void> parent = input.getParentId() == null ? Mono.empty()
                    : read(input.getParentId()).doOnNext(row -> HandoverRules.require(
                            !HandoverRules.hasText(row.getParentId())
                                    && !Objects.equals(row.getId(), input.getId()), "category_depth"))
                            .flatMap(this::reserve).then();
            if (input.getParentId() != null && HandoverRules.hasText(input.getId()))
                parent = parent.then(repository.createQuery().setParam(selector(context))
                        .where("parentId", input.getId()).count()
                        .doOnNext(count -> HandoverRules.require(count == 0, "category_depth")).then());
            String key = context.tenantId() + "|" + context.applicationId() + "|"
                    + Objects.toString(input.getParentId(), "") + "|" + input.getName();
            input.setActiveKey(DigestUtils.md5DigestAsHex(key.getBytes(StandardCharsets.UTF_8)));
            var duplicate = repository.createQuery().setParam(selector(context))
                    .where("activeKey", input.getActiveKey())
                    .and("id", "not", Objects.toString(input.getId(), "_new_")).count()
                    .doOnNext(count -> HandoverRules.require(count == 0, "category_duplicate"));
            return parent.then(duplicate).then(Mono.defer(() -> {
                input.setModifierName(context.userName());
                if (!HandoverRules.hasText(input.getId())) {
                    input.setId(IDGenerator.MD5.generate());
                    scope.initialize(input, context);
                    return repository.insert(input).thenReturn(input);
                }
                return read(input.getId()).flatMap(old -> {
                    HandoverRules.conflict(Objects.equals(input.getVersion(), old.getVersion()));
                    input.setTenantId(old.getTenantId());
                    input.setApplicationId(old.getApplicationId());
                    input.setCreatorId(old.getCreatorId());
                    input.setCreatorName(old.getCreatorName());
                    input.setCreateTime(old.getCreateTime());
                    input.setDeleted(false);
                    input.setModifyTime(System.currentTimeMillis());
                    input.setVersion(old.getVersion() + 1);
                    var update = repository.createUpdate().set(input);
                    if (input.getParentId() == null) update.setNull("parentId");
                    return update.where("id", old.getId()).and("version", old.getVersion())
                            .and("deleted", false).execute().doOnNext(n -> HandoverRules.conflict(n == 1))
                            .thenReturn(input);
                });
            }));
        }).onErrorMap(HandoverMaterialCategoryService::nameConflict);
    }

    private static Throwable nameConflict(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return new org.hswebframework.web.exception.BusinessException("error.handover.category_duplicate", 409);
        }
        return error;
    }

    private Mono<Void> reserve(HandoverMaterialCategoryEntity row) {
        return repository.createUpdate().set("version", row.getVersion() + 1)
                .where("id", row.getId()).and("version", row.getVersion()).and("deleted", false)
                .execute().doOnNext(n -> HandoverRules.conflict(n == 1)).then();
    }

    public Mono<Void> reserveForRecord(String id) {
        if (!HandoverRules.hasText(id)) return Mono.empty();
        return selectable(id).then(read(id)).flatMap(this::reserve);
    }

    @Transactional
    public Mono<Void> delete(String id, Integer version) {
        return directory.employee().then(read(id)).flatMap(row -> {
            HandoverRules.conflict(Objects.equals(version, row.getVersion()));
            return reserve(row).then(Mono.zip(repository.createQuery().where("parentId", id).and("deleted", false).count(),
                            records.createQuery().where("categoryId", id).and("deleted", false).count()))
                    .doOnNext(counts -> HandoverRules.require(counts.getT1() + counts.getT2() == 0,
                            "record_referenced"))
                    .then(repository.createUpdate().set("deleted", true).setNull("activeKey")
                            .set("version", version + 1).set("modifyTime", System.currentTimeMillis())
                            .where("id", id).and("version", version + 1).and("deleted", false).execute())
                    .doOnNext(n -> HandoverRules.conflict(n == 1)).then();
        });
    }
}
