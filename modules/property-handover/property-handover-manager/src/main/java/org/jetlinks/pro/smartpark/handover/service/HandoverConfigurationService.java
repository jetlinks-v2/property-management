package org.jetlinks.pro.smartpark.handover.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.hswebframework.web.exception.NotFoundException;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.smartpark.handover.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

/** Project-owned configuration and registers; a single path enforces CAS and logical deletion. */
@Service
@RequiredArgsConstructor
public class HandoverConfigurationService {
    private final HandoverScope scope;
    private final HandoverDirectory directory;
    private final HandoverFiles files;
    private final HandoverProcessService processes;
    private final HandoverCategoryRepository categories;
    private final HandoverResponsibilityRepository responsibilities;
    private final HandoverItemRepository items;
    private final HandoverProblemRepository problems;
    private final HandoverLedgerRepository ledgers;
    private final HandoverEquipmentRepository equipment;
    private final HandoverRecordRepository records;

    public <E extends HandoverEntity> Mono<PagerResult<E>> query(
            GenericReactiveCrudService<E, String> repository,
            HandoverQuery input,
            String keywordField) {
        return scope.current()
                .flatMap(c -> scope.query(input.parameter(keywordField), c, input.getProjectId()))
                .flatMap(repository::queryPager);
    }

    public <E extends HandoverEntity> Mono<E> read(
            GenericReactiveCrudService<E, String> repository, String id) {
        return scope.current()
                .flatMap(
                        c ->
                                repository
                                        .findById(id)
                                        .switchIfEmpty(Mono.error(new NotFoundException()))
                                        .doOnNext(row -> scope.owned(row, c))
                                        .flatMap(
                                                row ->
                                                        scope.project(
                                                                        row.getProjectId(),
                                                                        CrudAssetPermission.read)
                                                                .thenReturn(row)));
    }

    /** Validation is exposed for Excel's complete preflight, without business writes. */
    public <E extends HandoverEntity> Mono<E> validate(E row) {
        HandoverRules.require(HandoverRules.hasText(row.getProjectId()), "project_required");
        Mono<Void> check =
                directory
                        .employee()
                        .then(scope.project(row.getProjectId(), CrudAssetPermission.save))
                        .then(directory.project(row.getProjectId()))
                        .then();
        if (row instanceof HandoverCategoryEntity category) {
            HandoverRules.require(
                    HandoverRules.hasText(category.getName()) && category.getName().length() <= 128,
                    "name_required");
            if (category.getEnabled() == null) category.setEnabled(true);
            if (HandoverRules.hasText(category.getParentId()))
                check =
                        check.then(
                                read(categories, category.getParentId())
                                        .doOnNext(
                                                parent -> {
                                                    HandoverRules.require(
                                                            Objects.equals(
                                                                            parent.getProjectId(),
                                                                            row.getProjectId())
                                                                    && !Objects.equals(
                                                                            parent.getId(),
                                                                            row.getId())
                                                                    && !HandoverRules.hasText(
                                                                            parent.getParentId()),
                                                            "category_depth");
                                                })
                                        .then());
            check = check.then(categoryHierarchy(category));
        } else if (row instanceof HandoverResponsibilityEntity responsibility) {
            if (responsibility.getEnabled() == null) responsibility.setEnabled(true);
            check =
                    check.then(
                                    directory
                                            .external(
                                                    row.getProjectId(),
                                                    responsibility.getOrganizationId())
                                            .doOnNext(org -> responsibility.setName(org.getName()))
                                            .then())
                            .then(
                                    scope.current()
                                            .flatMap(
                                                    context ->
                                                            responsibilities
                                                                    .createQuery()
                                                                    .setParam(
                                                                            scope.mandatory(
                                                                                    new org
                                                                                            .hswebframework
                                                                                            .web.api
                                                                                            .crud
                                                                                            .entity
                                                                                            .QueryParamEntity(),
                                                                                    context,
                                                                                    row
                                                                                            .getProjectId()))
                                                                    .and(
                                                                            "organizationId",
                                                                            responsibility
                                                                                    .getOrganizationId())
                                                                    .and("deleted", false)
                                                                    .and(
                                                                            "id",
                                                                            "not",
                                                                            Objects.toString(
                                                                                    row.getId(),
                                                                                    "_new_"))
                                                                    .count())
                                            .doOnNext(
                                                    n ->
                                                            HandoverRules.require(
                                                                    n == 0,
                                                                    "organization_duplicate"))
                                            .then());
        } else if (row instanceof HandoverLedgerEntity ledger) {
            HandoverRules.require(HandoverRules.hasText(ledger.getTitle()), "title_required");
            check =
                    check.then(category(row.getProjectId(), ledger.getCategoryId()))
                            .then(files.validate(ledger.getFiles()));
        } else if (row instanceof HandoverEquipmentEntity entry) {
            HandoverRules.require(HandoverRules.hasText(entry.getName()), "name_required");
            check = check.then(category(row.getProjectId(), entry.getCategoryId()));
        } else if (row instanceof HandoverRecordEntity record) {
            HandoverRules.require(
                    HandoverRules.STAGES.contains(record.getStage()), "stage_invalid");
            HandoverRules.require(HandoverRules.hasText(record.getTitle()), "title_required");
            check = check.then(processes.assertAvailable(row.getProjectId(), record.getStage()));
            check =
                    check.then(category(row.getProjectId(), record.getCategoryId()))
                            .then(files.validate(record.getFiles()));
            List<String> persons =
                    record.getPersonIds() == null ? List.of() : record.getPersonIds();
            HandoverRules.require(persons.size() <= 200, "user_limit");
            check = check.thenMany(Flux.fromIterable(persons).concatMap(directory::user)).then();
        } else if (row instanceof HandoverItemEntity item) {
            HandoverRules.require(
                    java.util.Set.of("PHYSICAL_HANDOVER", "QUALITY_ACCEPTANCE", "MATERIAL_TRANSFER")
                            .contains(Objects.toString(item.getSource(), "")),
                    "source_invalid");
            HandoverRules.require(HandoverRules.hasText(item.getName()), "name_required");
            check =
                    check.then(
                            processes.assertAvailable(row.getProjectId(), stage(item.getSource())));
        }
        return check.thenReturn(row);
    }

    private Mono<Void> stageWrite(HandoverEntity row) {
        if (row instanceof HandoverRecordEntity record)
            return processes.writeStage(row.getProjectId(), record.getStage());
        if (row instanceof HandoverItemEntity item)
            return processes.writeStage(row.getProjectId(), stage(item.getSource()));
        return Mono.empty();
    }

    static String stage(String source) {
        return "PHYSICAL_HANDOVER".equals(source)
                ? "physical"
                : "QUALITY_ACCEPTANCE".equals(source) ? "quality" : "material";
    }

    private Mono<Void> categoryHierarchy(HandoverCategoryEntity category) {
        Mono<Void> hierarchy = Mono.empty();
        // 将带子分类的根节点挂到其他根下会造出三级，父节点本身为根并不足以保证两级。
        if (HandoverRules.hasText(category.getId())
                && HandoverRules.hasText(category.getParentId())) {
            hierarchy =
                    categories
                            .createQuery()
                            .where("parentId", category.getId())
                            .and("deleted", false)
                            .count()
                            .doOnNext(count -> HandoverRules.require(count == 0, "category_depth"))
                            .then();
        }
        return hierarchy
                .then(scope.current())
                .flatMap(
                        context -> {
                            var query =
                                    scope.mandatory(
                                            new org.hswebframework.web.api.crud.entity
                                                    .QueryParamEntity(),
                                            context,
                                            category.getProjectId());
                            query.toQuery()
                                    .and("name", category.getName())
                                    .and("id", "not", Objects.toString(category.getId(), "_new_"));
                            if (HandoverRules.hasText(category.getParentId()))
                                query.toQuery().and("parentId", category.getParentId());
                            else query.toQuery().nest().isNull("parentId").or("parentId", "").end();
                            return categories
                                    .createQuery()
                                    .setParam(query)
                                    .count()
                                    .doOnNext(
                                            count ->
                                                    HandoverRules.require(
                                                            count == 0, "category_duplicate"))
                                    .then();
                        });
    }

    private static void categoryKey(HandoverEntity input, HandoverContext context) {
        if (input instanceof HandoverCategoryEntity category) {
            String key =
                    context.tenantId()
                            + "|"
                            + context.applicationId()
                            + "|"
                            + input.getProjectId()
                            + "|"
                            + Objects.toString(category.getParentId(), "")
                            + "|"
                            + category.getName();
            category.setActiveKey(
                    org.springframework.util.DigestUtils.md5DigestAsHex(
                            key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } else if (input instanceof HandoverResponsibilityEntity responsibility) {
            String key =
                    context.tenantId()
                            + "|"
                            + context.applicationId()
                            + "|"
                            + input.getProjectId()
                            + "|"
                            + responsibility.getOrganizationId();
            responsibility.setActiveKey(
                    org.springframework.util.DigestUtils.md5DigestAsHex(
                            key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    public Mono<HandoverCategoryEntity> readCategory(String id) {
        return read(categories, id);
    }

    public Mono<Void> category(String projectId, String id) {
        if (!HandoverRules.hasText(id)) return Mono.empty();
        return read(categories, id)
                .doOnNext(
                        c ->
                                HandoverRules.require(
                                        Objects.equals(c.getProjectId(), projectId)
                                                && !Boolean.FALSE.equals(c.getEnabled()),
                                        "category_invalid"))
                .then();
    }

    public Mono<HandoverResponsibilityEntity> responsibility(String projectId, String orgId) {
        HandoverRules.require(HandoverRules.hasText(orgId), "organization_required");
        return scope.current()
                .flatMap(
                        c ->
                                responsibilities
                                        .createQuery()
                                        .setParam(
                                                scope.mandatory(
                                                        new org.hswebframework.web.api.crud.entity
                                                                .QueryParamEntity(),
                                                        c,
                                                        projectId))
                                        .and("organizationId", orgId)
                                        .and("enabled", true)
                                        .fetchOne())
                .flatMap(row -> directory.external(projectId, orgId).thenReturn(row))
                .switchIfEmpty(
                        Mono.error(
                                new org.hswebframework.web.exception.BusinessException(
                                        "error.handover.organization_invalid", 400)));
    }

    @Transactional
    public <E extends HandoverEntity> Mono<E> save(
            GenericReactiveCrudService<E, String> repository, E input) {
        return validate(input)
                .then(stageWrite(input))
                .then(scope.current())
                .flatMap(
                        c -> {
                            if (!HandoverRules.hasText(input.getId())) {
                                input.setId(IDGenerator.MD5.generate());
                                scope.initialize(input, c);
                                categoryKey(input, c);
                                if (input instanceof HandoverItemEntity item)
                                    item.setConfirmed(false);
                                return repository.insert(input).thenReturn(input);
                            }
                            return read(repository, input.getId())
                                    .flatMap(
                                            old -> {
                                                HandoverRules.require(
                                                        Objects.equals(
                                                                input.getProjectId(),
                                                                old.getProjectId()),
                                                        "project_invalid");
                                                HandoverRules.conflict(
                                                        Objects.equals(
                                                                old.getVersion(),
                                                                input.getVersion()));
                                                // Confirmation is an action, never a writable
                                                // property of a normal save.
                                                if (old instanceof HandoverItemEntity previous
                                                        && input
                                                                instanceof
                                                                HandoverItemEntity next) {
                                                    HandoverRules.require(
                                                            !Boolean.TRUE.equals(
                                                                    previous.getConfirmed()),
                                                            "item_confirmed");
                                                    next.setConfirmed(previous.getConfirmed());
                                                    next.setConfirmTime(previous.getConfirmTime());
                                                    HandoverRules.require(
                                                            Objects.equals(
                                                                    previous.getSource(),
                                                                    next.getSource()),
                                                            "source_invalid");
                                                }
                                                input.setTenantId(old.getTenantId());
                                                input.setApplicationId(old.getApplicationId());
                                                input.setDeleted(false);
                                                categoryKey(input, c);
                                                input.setCreatorId(old.getCreatorId());
                                                input.setCreatorName(old.getCreatorName());
                                                input.setCreateTime(old.getCreateTime());
                                                input.setVersion(old.getVersion() + 1);
                                                input.setModifyTime(System.currentTimeMillis());
                                                return repository
                                                        .createUpdate()
                                                        .set(input)
                                                        .where("id", old.getId())
                                                        .and("version", old.getVersion())
                                                        .and("deleted", false)
                                                        .execute()
                                                        .doOnNext(
                                                                n -> HandoverRules.conflict(n == 1))
                                                        .thenReturn(input);
                                            });
                        });
    }

    @Transactional
    public <E extends HandoverEntity> Mono<Void> delete(
            GenericReactiveCrudService<E, String> repository, String id, Integer version) {
        return directory
                .employee()
                .then(read(repository, id))
                .flatMap(
                        row -> {
                            HandoverRules.conflict(Objects.equals(row.getVersion(), version));
                            return scope.project(row.getProjectId(), CrudAssetPermission.delete)
                                    .then(deletionGuard(row))
                                    .then(
                                            Mono.defer(
                                                    () -> {
                                                        var update =
                                                                repository
                                                                        .createUpdate()
                                                                        .set("deleted", true)
                                                                        .set("version", version + 1)
                                                                        .set(
                                                                                "modifyTime",
                                                                                System
                                                                                        .currentTimeMillis());
                                                        // 删除只释放活动唯一键，业务记录和历史引用保留。
                                                        if (row instanceof HandoverCategoryEntity
                                                                || row
                                                                        instanceof
                                                                        HandoverResponsibilityEntity)
                                                            update.setNull("activeKey");
                                                        return update.where("id", id)
                                                                .and("version", version)
                                                                .and("deleted", false)
                                                                .execute();
                                                    }))
                                    .doOnNext(count -> HandoverRules.conflict(count == 1))
                                    .then();
                        });
    }

    private Mono<Void> deletionGuard(HandoverEntity row) {
        if (row instanceof HandoverCategoryEntity) {
            return Mono.zip(
                            categories
                                    .createQuery()
                                    .where("parentId", row.getId())
                                    .and("deleted", false)
                                    .count(),
                            problems.createQuery()
                                    .where("categoryId", row.getId())
                                    .and("deleted", false)
                                    .count(),
                            ledgers.createQuery()
                                    .where("categoryId", row.getId())
                                    .and("deleted", false)
                                    .count(),
                            equipment
                                    .createQuery()
                                    .where("categoryId", row.getId())
                                    .and("deleted", false)
                                    .count(),
                            records.createQuery()
                                    .where("categoryId", row.getId())
                                    .and("deleted", false)
                                    .count())
                    .doOnNext(
                            t ->
                                    HandoverRules.require(
                                            t.getT1() + t.getT2() + t.getT3() + t.getT4()
                                                            + t.getT5()
                                                    == 0,
                                            "record_referenced"))
                    .then();
        }
        if (row instanceof HandoverItemEntity)
            return problems.createQuery()
                    .where("itemId", row.getId())
                    .and("deleted", false)
                    .count()
                    .doOnNext(n -> HandoverRules.require(n == 0, "record_referenced"))
                    .then();
        return Mono.empty();
    }

    /**
     * Item CAS serializes confirmation against problem creation, which also increments its version.
     */
    @Transactional
    public Mono<HandoverItemEntity> confirm(String id, Integer version) {
        return directory
                .employee()
                .then(read(items, id))
                .flatMap(
                        item -> {
                            HandoverRules.conflict(Objects.equals(item.getVersion(), version));
                            HandoverRules.require(
                                    !Boolean.TRUE.equals(item.getConfirmed()), "item_confirmed");
                            return scope.project(item.getProjectId(), CrudAssetPermission.save)
                                    .then(
                                            problems.createQuery()
                                                    .where("itemId", id)
                                                    .and("deleted", false)
                                                    .and("status", "not", 3)
                                                    .count())
                                    .doOnNext(
                                            n ->
                                                    HandoverRules.require(
                                                            n == 0, "item_open_problems"))
                                    .then(
                                            items.createUpdate()
                                                    .set("confirmed", true)
                                                    .set("confirmTime", System.currentTimeMillis())
                                                    .set("version", version + 1)
                                                    .where("id", id)
                                                    .and("version", version)
                                                    .and("confirmed", false)
                                                    .and("deleted", false)
                                                    .execute())
                                    .doOnNext(n -> HandoverRules.conflict(n == 1))
                                    .then(
                                            Mono.fromSupplier(
                                                    () -> {
                                                        item.setConfirmed(true);
                                                        item.setVersion(version + 1);
                                                        return item;
                                                    }));
                        });
    }
}
