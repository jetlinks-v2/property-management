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
    private final HandoverMaterialCategoryService materialCategories;
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

    /** 信息维护跨项目分页，先从公共目录取得调用者可读项目，再在查验库内分页。 */
    public <E extends HandoverEntity> Mono<PagerResult<E>> queryInformation(
            GenericReactiveCrudService<E, String> repository, HandoverQuery input) {
        return authorizedQuery(input, "name").flatMap(repository::queryPager);
    }

    /** 列表与导出共用项目授权条件，必须在数据库分页前应用；空授权范围匹配零行。 */
    public Mono<org.hswebframework.web.api.crud.entity.QueryParamEntity> authorizedQuery(
            HandoverQuery input, String keywordField) {
        if (HandoverRules.hasText(input.getProjectId()))
            return scope.current().flatMap(c -> scope.query(input.parameter(keywordField), c, input.getProjectId()));
        return scope.current().flatMap(context -> directory.projects()
                .map(org -> org.getId()).distinct().collectList().flatMap(projectIds -> {
                    var query = scope.mandatory(input.parameter(keywordField), context, null);
                    // 空授权范围必须匹配零行，不能省略项目条件而退化为应用内全量查询。
                    if (projectIds.isEmpty()) query.toQuery().isNull("projectId").notNull("projectId");
                    else query.toQuery().in("projectId", projectIds);
                    return Mono.just(query);
                }));
    }

    public Mono<PagerResult<HandoverLedgerEntity>> queryLedger(HandoverQuery input) {
        return authorizedQuery(input, "description").flatMap(ledgers::queryPager)
                .flatMap(page -> Flux.fromIterable(page.getData()).concatMap(row -> {
                    if (!HandoverRules.hasText(row.getCategoryId())) return Mono.just(row);
                    return categories.findById(row.getCategoryId())
                        .filter(c -> Objects.equals(c.getTenantId(), row.getTenantId())
                                && Objects.equals(c.getApplicationId(), row.getApplicationId())
                                && Objects.equals(c.getProjectId(), row.getProjectId()) && !Boolean.TRUE.equals(c.getDeleted()))
                        .doOnNext(c -> row.setCategoryName(c.getName())).thenReturn(row);
                }).then(Mono.just(page)));
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
            HandoverRules.require(HandoverRules.hasText(ledger.getDescription()) && ledger.getDescription().length() <= 500, "ledger_description_invalid");
            HandoverRules.require(HandoverRules.hasText(ledger.getLocation()) && ledger.getLocation().length() <= 200, "ledger_location_invalid");
            HandoverRules.require(HandoverRules.hasText(ledger.getCategoryId()), "category_required");
            HandoverRules.require(HandoverRules.hasText(ledger.getOrganizationId()), "organization_required");
            HandoverRules.require(ledger.getInspectionTime() == null || ledger.getInspectionTime() >= 0, "date_invalid");
            // 旧台账没有独立标题；兼容既有非空列，界面和导入均以问题描述为主体。
            if (!HandoverRules.hasText(ledger.getTitle()))
                ledger.setTitle(ledger.getDescription().substring(0, Math.min(256, ledger.getDescription().length())));
            if (HandoverRules.hasText(ledger.getOrganizationId()))
                check = check.then(responsibility(row.getProjectId(), ledger.getOrganizationId())
                        .doOnNext(unit -> ledger.setOrganizationName(unit.getName())).then());
            check =
                    check.then(category(row.getProjectId(), ledger.getCategoryId()))
                            .then(files.validateLedger(ledger.getFiles()));
        } else if (row instanceof HandoverEquipmentEntity entry) {
            HandoverRules.require(HandoverRules.hasText(entry.getName()), "name_required");
            HandoverRules.require(entry.getQuantity() == null || entry.getQuantity().signum() >= 0, "quantity_invalid");
            if (entry.getProductionDate() != null) HandoverRules.require(entry.getProductionDate() >= 0, "date_invalid");
            if (entry.getInstallationDate() != null) HandoverRules.require(entry.getInstallationDate() >= 0, "date_invalid");
            // categoryId 仅保留迁移前的数据，不再把设备文本分类校验为问题分类。
        } else if (row instanceof HandoverRecordEntity record) {
            HandoverRules.require(
                    HandoverRules.STAGES.contains(record.getStage()), "stage_invalid");
            HandoverRules.require(HandoverRules.hasText(record.getTitle()), "title_required");
            validateRecord(record);
            check = check.then(processes.assertAvailable(row.getProjectId(), record.getStage()));
            check =
                    check.then("disclosure".equals(record.getStage()) ? Mono.empty() : materialCategories.selectable(record.getCategoryId()))
                            .then(files.validate(record.getFiles()));
            List<String> persons =
                    record.getPersonIds() == null ? List.of() : record.getPersonIds();
            HandoverRules.require(persons.size() <= 200, "user_limit");
            check = check.thenMany(Flux.fromIterable(persons).concatMap(directory::user)).then();
            if ("disclosure".equals(record.getStage())) check = check.then(directory.user(record.getProperties().get("receiverId"))
                .doOnNext(user -> record.getProperties().put("receiverName", user.getName())).then());
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
            return processes.writeStage(row.getProjectId(), record.getStage())
                    .then("disclosure".equals(record.getStage()) ? Mono.empty() : materialCategories.reserveForRecord(record.getCategoryId()));
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
                                if (input instanceof HandoverLedgerEntity ledger) {
                                    ledger.setEntryTime(input.getCreateTime());
                                    // 上传身份只信任认证上下文，客户端及导入均不能冒用其他人。
                                    ledger.setEntryUserId(c.userId()); ledger.setEntryUserName(c.userName());
                                    if (ledger.getInspectionTime() == null) ledger.setInspectionTime(input.getCreateTime());
                                    ledger.setSupplementUserId(null); ledger.setSupplementUserName(null); ledger.setSupplementTime(null);
                                }
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
                                                if (old instanceof HandoverLedgerEntity before && input instanceof HandoverLedgerEntity after) {
                                                    after.setEntryTime(before.getEntryTime());
                                                    after.setEntryUserId(before.getEntryUserId());
                                                    after.setEntryUserName(before.getEntryUserName());
                                                    after.setInspectionTime(before.getInspectionTime());
                                                    after.setSupplementUserId(before.getSupplementUserId());
                                                    after.setSupplementUserName(before.getSupplementUserName());
                                                    after.setSupplementTime(before.getSupplementTime());
                                                    after.setFiles(before.getFiles()); // 补图必须经过专用动作记录真实操作者。
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
                        }).flatMap(saved -> saved instanceof HandoverRecordEntity record
                            ? processes.recordOperation(saved.getProjectId(), record.getStage(), "save", record.getTitle()).thenReturn(saved)
                            : Mono.just(saved));
    }

    /** 专用补图动作：版本、文件权限、项目权限与补录审计在一次事务中提交。 */
    @Transactional
    public Mono<HandoverLedgerEntity> supplementLedger(String id, org.jetlinks.pro.smartpark.handover.api.ActionRequest input) {
        HandoverRules.require(input.getPhotos() != null && !input.getPhotos().isEmpty(), "photos_required");
        return directory.employee().then(read(ledgers, id)).flatMap(row -> {
            HandoverRules.conflict(Objects.equals(row.getVersion(), input.getVersion()));
            HandoverRules.require(row.getFiles() == null || row.getFiles().isEmpty(), "ledger_photos_present");
            return scope.project(row.getProjectId(), CrudAssetPermission.save).then(files.validateLedger(input.getPhotos()))
                    .then(scope.current()).flatMap(context -> {
                        long now = System.currentTimeMillis();
                        return ledgers.createUpdate().set("files", input.getPhotos())
                                .set("supplementUserId", context.userId()).set("supplementUserName", context.userName())
                                .set("supplementTime", now).set("modifyTime", now).set("version", row.getVersion() + 1)
                                .where("id", id).and("version", row.getVersion()).and("deleted", false).execute()
                                .doOnNext(n -> HandoverRules.conflict(n == 1)).then(read(ledgers, id));
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
                                    .then(processes.writeStage(item.getProjectId(), stage(item.getSource())))
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
                                    .then(processes.recordOperation(item.getProjectId(), stage(item.getSource()), "confirm", item.getName()))
                                    .then(
                                            Mono.fromSupplier(
                                                    () -> {
                                                        item.setConfirmed(true);
                                                        item.setVersion(version + 1);
                                                        return item;
                                                    }));
                        });
    }
    private static void validateRecord(HandoverRecordEntity record) {
        var values = record.getProperties() == null ? java.util.Map.<String,String>of() : record.getProperties();
        List<String> required = switch (record.getStage()) {
            case "kickoff" -> List.of("meetingLocation", "meetingStartTime", "meetingEndTime");
            case "material" -> List.of("submitLocation", "submitUserName", "submitUserPhone", "receiveUserName", "receiveUserPhone", "materialComplete");
            case "disclosure" -> List.of("disclosureCategory", "disclosureUserName", "receiverId");
            default -> List.of();
        };
        for (String key : required) HandoverRules.require(HandoverRules.hasText(values.get(key)), "record_fields_required");
        if ("kickoff".equals(record.getStage())) {
            try {
                long start = Long.parseLong(values.get("meetingStartTime"));
                long end = Long.parseLong(values.get("meetingEndTime"));
                HandoverRules.require(start >= 0 && end >= start, "meeting_time_invalid");
            } catch (NumberFormatException error) {
                throw new org.hswebframework.web.exception.BusinessException("error.handover.meeting_time_invalid", 400);
            }
        }
        if (java.util.Set.of("material", "disclosure").contains(record.getStage()))
            HandoverRules.require(record.getTime() != null && record.getTime() >= 0, "date_invalid");
        if ("material".equals(record.getStage())) HandoverRules.require(java.util.Set.of("0", "1").contains(values.get("materialComplete")), "request_invalid");
        if ("disclosure".equals(record.getStage())) record.setCategoryId(null);
    }

    /** 完整预检后写入；最终 CAS 冲突也回滚整批确认和节点日志。 */
    @Transactional
    public Mono<List<HandoverItemEntity>> confirmBatch(org.jetlinks.pro.smartpark.handover.api.BatchConfirmRequest input) {
        HandoverRules.require("MATERIAL_TRANSFER".equals(input.getSource()), "source_invalid");
        var selected = input.getItems();
        HandoverRules.require(selected != null && !selected.isEmpty() && selected.size() <= 200, "request_invalid");
        HandoverRules.require(selected.stream().map(org.jetlinks.pro.smartpark.handover.api.BatchConfirmRequest.Item::getId).distinct().count() == selected.size(), "request_invalid");
        return directory.employee().then(scope.project(input.getProjectId(), CrudAssetPermission.save))
            .thenMany(Flux.fromIterable(selected).concatMap(entry -> read(items, entry.getId()).doOnNext(row -> {
                HandoverRules.require(Objects.equals(input.getProjectId(), row.getProjectId()) && input.getSource().equals(row.getSource()), "request_invalid");
                HandoverRules.conflict(Objects.equals(entry.getVersion(), row.getVersion()));
                HandoverRules.require(!Boolean.TRUE.equals(row.getConfirmed()), "item_confirmed");
            }).flatMap(row -> problems.createQuery().where("itemId",row.getId()).and("deleted",false).and("status","not",3).count()
               .doOnNext(n -> HandoverRules.require(n == 0,"item_open_problems")).thenReturn(row))))
            .collectList().flatMapMany(rows -> Flux.fromIterable(rows).concatMap(row -> confirm(row.getId(), row.getVersion())))
            .collectList();
    }

}
