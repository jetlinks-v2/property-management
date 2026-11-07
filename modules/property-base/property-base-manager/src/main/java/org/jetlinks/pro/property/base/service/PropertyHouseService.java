package org.jetlinks.pro.property.base.service;

import static org.jetlinks.pro.property.base.service.ArchiveScope.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.entity.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.*;

/** 维护真实末端的房屋扩展；项目事务锁保护保存、导入及客户存在时的删除约束。 */
@Service
@RequiredArgsConstructor
public class PropertyHouseService {
    private static final Set<String> FIELDS =
            Set.of(
                    "id",
                    "spaceId",
                    "name",
                    "fullName",
                    "projectId",
                    "completed",
                    "houseType",
                    "houseState",
                    "billingArea",
                    "buildingArea",
                    "insideArea",
                    "deliveryDate",
                    "modifyTime");
    private final ArchiveScope scope;
    private final PropertyDictionaryService dictionaries;
    private final ReactiveRepository<PropertyHouseEntity, String> houses;
    private final ReactiveRepository<PropertyCustomerRelationshipEntity, String> relationships;

    /** 跨项目统计沿用与房屋列表相同的空间权限；不分页，不把非末级节点计为房屋。 */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Mono<org.jetlinks.pro.property.base.archive.PropertyHouseSummary> summary(
            List<String> ids) {
        if (ids == null
                || ids.isEmpty()
                || ids.size() > 100
                || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 64))
            return Mono.error(error("invalid_query", 400));
        return reactor.core.publisher.Flux.fromIterable(new LinkedHashSet<>(ids))
                .concatMap(id -> all(id, null))
                .flatMapIterable(rows -> rows)
                .collectList()
                .flatMap(
                        rows ->
                                dictionaries
                                        .items("house-state")
                                        .map(items -> HouseSummaryRules.summarize(rows, items)));
    }

    public Mono<PagerResult<HouseView>> query(
            String projectId, String spaceId, QueryParamEntity query) {
        return all(projectId, spaceId).map(rows -> ArchiveQuery.page(rows, query, FIELDS));
    }

    public Mono<List<HouseView>> export(String projectId, String spaceId, QueryParamEntity query) {
        return all(projectId, spaceId).map(rows -> ArchiveQuery.filter(rows, query, FIELDS));
    }

    public Mono<HouseView> detail(String projectId, String spaceId) {
        return scope.leaf(projectId, spaceId, CrudAssetPermission.read)
                .flatMap(
                        node ->
                                houses.findById(spaceId)
                                        .map(Optional::of)
                                        .defaultIfEmpty(Optional.empty())
                                        .map(row -> view(projectId, node, row.orElse(null))));
    }

    public Mono<PropertyHouseEntity> requireCompleted(
            String projectId, String spaceId, CrudAssetPermission permission) {
        return scope.leaf(projectId, spaceId, permission)
                .then(houses.findById(spaceId))
                .filter(
                        e ->
                                projectId.equals(e.getProjectId())
                                        && !Boolean.TRUE.equals(e.getDeleted()))
                .switchIfEmpty(Mono.error(error("house_incomplete", 409)));
    }

    private Mono<List<HouseView>> all(String projectId, String spaceId) {
        return scope.spaces(projectId, spaceId, CrudAssetPermission.read)
                .flatMap(
                        nodes -> {
                            var leaves =
                                    nodes.stream()
                                            .filter(e -> Boolean.TRUE.equals(e.getLeaf()))
                                            .toList();
                            if (leaves.isEmpty()) return Mono.just(List.of());
                            return houses.createQuery()
                                    .in(
                                            "id",
                                            leaves.stream()
                                                    .map(PropertySpaceEntity::getId)
                                                    .toList())
                                    .fetch()
                                    .collectMap(PropertyHouseEntity::getId)
                                    .map(
                                            byId ->
                                                    leaves.stream()
                                                            .map(
                                                                    node ->
                                                                            view(
                                                                                    projectId,
                                                                                    node,
                                                                                    byId.get(
                                                                                            node
                                                                                                    .getId())))
                                                            .toList());
                        });
    }

    @Transactional
    public Mono<HouseView> save(String projectId, String spaceId, HouseWrite input) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.lock(projectId))
                .then(scope.leaf(projectId, spaceId, CrudAssetPermission.save))
                .flatMap(
                        node ->
                                houses.findById(spaceId)
                                        .map(Optional::of)
                                        .defaultIfEmpty(Optional.empty())
                                        .flatMap(
                                                old ->
                                                        prepareHouse(
                                                                projectId,
                                                                node,
                                                                input,
                                                                old.orElse(null))));
    }

    private Mono<HouseView> prepareHouse(
            String projectId, PropertySpaceEntity node, HouseWrite input, PropertyHouseEntity old) {
        PropertyDictionaryService.version(input.version());
        validate(input);
        if (old != null && !projectId.equals(old.getProjectId()))
            return Mono.error(error("house_project_conflict", 409));
        if (input.version() != (old == null ? 0 : old.getVersion()))
            return Mono.error(error("version_conflict", 409));
        boolean existing = old != null && !Boolean.TRUE.equals(old.getDeleted());
        return dictionaries
                .validateSelection(
                        "house-type", input.houseType(), existing ? old.getHouseType() : null)
                .then(
                        dictionaries.validateSelection(
                                "house-state",
                                input.houseState(),
                                existing ? old.getHouseState() : null))
                .then(scope.authenticated())
                .flatMap(auth -> persistHouse(auth, projectId, node, input, old));
    }

    private Mono<HouseView> persistHouse(
            org.hswebframework.web.authorization.Authentication auth,
            String projectId,
            PropertySpaceEntity node,
            HouseWrite input,
            PropertyHouseEntity old) {
        var entity = new PropertyHouseEntity();
        entity.setId(node.getId());
        entity.setProjectId(projectId);
        entity.setRootId(node.getRootId());
        entity.setHouseType(input.houseType());
        entity.setHouseState(input.houseState());
        entity.setBillingArea(input.billingArea());
        entity.setBuildingArea(input.buildingArea());
        entity.setInsideArea(input.insideArea());
        entity.setDeliveryDate(input.deliveryDate());
        entity.setRemark(input.remark());
        PropertyDictionaryService.init(entity, auth);
        entity.setVersion(input.version() + 1);
        if (old != null) entity.setCreateTime(old.getCreateTime());
        Mono<Void> write =
                old == null
                        ? houses.insert(entity).then()
                        : updateHouse(auth, entity, input.version());
        return write.thenReturn(view(projectId, node, entity));
    }

    private Mono<Void> updateHouse(
            org.hswebframework.web.authorization.Authentication auth,
            PropertyHouseEntity entity,
            long version) {
        var update =
                PropertyDictionaryService.audit(houses.createUpdate(), auth, version)
                        .set("houseType", entity.getHouseType())
                        .set("houseState", entity.getHouseState())
                        .set("billingArea", entity.getBillingArea())
                        .set("deleted", false)
                        .setNull("deletedTime");
        setNullable(update, "buildingArea", entity.getBuildingArea());
        setNullable(update, "insideArea", entity.getInsideArea());
        setNullable(update, "deliveryDate", entity.getDeliveryDate());
        setNullable(update, "remark", entity.getRemark());
        return update.where("id", entity.getId())
                .and("projectId", entity.getProjectId())
                .and("version", version)
                .execute()
                .flatMap(n -> n == 1 ? Mono.empty() : Mono.error(error("version_conflict", 409)));
    }

    @Transactional
    public Mono<Void> delete(String projectId, String spaceId, long version) {
        return scope.project(projectId, CrudAssetPermission.delete)
                .then(scope.lock(projectId))
                .then(requireCompleted(projectId, spaceId, CrudAssetPermission.delete))
                .then(
                        relationships
                                .createQuery()
                                .where("projectId", projectId)
                                .and("spaceId", spaceId)
                                .and("deleted", false)
                                .isNull("endTime")
                                .count())
                .flatMap(
                        count ->
                                count > 0
                                        ? Mono.error(error("house_has_customers", 409))
                                        : scope.authenticated())
                .flatMap(
                        auth ->
                                PropertyDictionaryService.audit(
                                                houses.createUpdate()
                                                        .set("deleted", true)
                                                        .set(
                                                                "deletedTime",
                                                                System.currentTimeMillis()),
                                                auth,
                                                version)
                                        .where("id", spaceId)
                                        .and("projectId", projectId)
                                        .and("deleted", false)
                                        .and("version", version)
                                        .execute())
                .flatMap(n -> n == 1 ? Mono.empty() : Mono.error(error("version_conflict", 409)));
    }

    @Transactional
    public Mono<ImportResult> importRows(String projectId, List<HouseWrite> rows) {
        if (rows == null || rows.isEmpty()) return Mono.error(error("import_limit", 400));
        return Mono.defer(
                () -> {
                    var ids = new HashSet<String>();
                    return scope.project(projectId, CrudAssetPermission.save)
                            .then(scope.lock(projectId))
                            // 预检阶段只有读取和加锁；整份文件通过后才订阅任何保存操作。
                            .thenMany(
                                    Flux.range(0, rows.size())
                                            .concatMap(
                                                    index ->
                                                            ImportValidation.at(
                                                                    index + 2,
                                                                    () -> {
                                                                        var row = rows.get(index);
                                                                        if (row == null
                                                                                || row.spaceId()
                                                                                        == null
                                                                                || !ids.add(
                                                                                        row
                                                                                                .spaceId()))
                                                                            return Mono.error(
                                                                                    error(
                                                                                            "import_duplicate",
                                                                                            400));
                                                                        validate(row);
                                                                        return scope.leaf(
                                                                                        projectId,
                                                                                        row
                                                                                                .spaceId(),
                                                                                        CrudAssetPermission
                                                                                                .save)
                                                                                .then(
                                                                                        houses.findById(
                                                                                                        row
                                                                                                                .spaceId())
                                                                                                .filter(
                                                                                                        old ->
                                                                                                                !projectId
                                                                                                                        .equals(
                                                                                                                                old
                                                                                                                                        .getProjectId()))
                                                                                                .hasElement())
                                                                                .flatMap(
                                                                                        conflict ->
                                                                                                conflict
                                                                                                        ? Mono
                                                                                                                .error(
                                                                                                                        error(
                                                                                                                                "house_project_conflict",
                                                                                                                                409))
                                                                                                        : detail(
                                                                                                                projectId,
                                                                                                                row
                                                                                                                        .spaceId()))
                                                                                .flatMap(
                                                                                        current -> {
                                                                                            if (current
                                                                                                    .getCompleted())
                                                                                                return Mono
                                                                                                        .error(
                                                                                                                error(
                                                                                                                        "house_already_completed",
                                                                                                                        409));
                                                                                            if (!projectId
                                                                                                    .equals(
                                                                                                            current
                                                                                                                    .getProjectId()))
                                                                                                return Mono
                                                                                                        .error(
                                                                                                                error(
                                                                                                                        "house_project_conflict",
                                                                                                                        409));
                                                                                            var
                                                                                                    input =
                                                                                                            new HouseWrite(
                                                                                                                    current
                                                                                                                            .getVersion(),
                                                                                                                    row
                                                                                                                            .houseType(),
                                                                                                                    row
                                                                                                                            .houseState(),
                                                                                                                    row
                                                                                                                            .billingArea(),
                                                                                                                    row
                                                                                                                            .buildingArea(),
                                                                                                                    row
                                                                                                                            .insideArea(),
                                                                                                                    row
                                                                                                                            .deliveryDate(),
                                                                                                                    row
                                                                                                                            .remark(),
                                                                                                                    row
                                                                                                                            .spaceId());
                                                                                            return dictionaries
                                                                                                    .validateSelection(
                                                                                                            "house-type",
                                                                                                            input
                                                                                                                    .houseType(),
                                                                                                            null)
                                                                                                    .then(
                                                                                                            dictionaries
                                                                                                                    .validateSelection(
                                                                                                                            "house-state",
                                                                                                                            input
                                                                                                                                    .houseState(),
                                                                                                                            null))
                                                                                                    .thenReturn(
                                                                                                            input);
                                                                                        });
                                                                    })))
                            .collectList()
                            .flatMapMany(
                                    prepared ->
                                            Flux.range(0, prepared.size())
                                                    .concatMap(
                                                            index ->
                                                                    ImportValidation.at(
                                                                            index + 2,
                                                                            () ->
                                                                                    save(
                                                                                            projectId,
                                                                                            prepared.get(
                                                                                                            index)
                                                                                                    .spaceId(),
                                                                                            prepared
                                                                                                    .get(
                                                                                                            index)))))
                            .then(Mono.just(new ImportResult(rows.size())));
                });
    }

    private static void setNullable(
            org.hswebframework.ezorm.rdb.mapping.ReactiveUpdate<PropertyHouseEntity> update,
            String field,
            Object value) {
        if (value == null) update.setNull(field);
        else update.set(field, value);
    }

    private static void validate(HouseWrite input) {
        area(input.billingArea(), true);
        area(input.buildingArea(), false);
        area(input.insideArea(), false);
        if (input.remark() != null && input.remark().length() > 2000)
            throw error("invalid_field", 400);
        if (input.deliveryDate() != null && input.deliveryDate() < 0)
            throw error("invalid_field", 400);
    }

    private static void area(BigDecimal value, boolean required) {
        if ((required && value == null)
                || (value != null
                        && (value.signum() < (required ? 1 : 0)
                                || value.stripTrailingZeros().scale() > 2
                                || value.precision() - value.scale() > 16)))
            throw error("invalid_area", 400);
    }

    private static HouseView view(
            String projectId, PropertySpaceEntity node, PropertyHouseEntity entity) {
        var view = new HouseView();
        boolean sameProject = entity != null && projectId.equals(entity.getProjectId());
        boolean completed = sameProject && !Boolean.TRUE.equals(entity.getDeleted());
        // 已清除的资料仅保留数据库历史，当前视图不回显；版本仍用于重新完善时的并发校验。
        if (completed) BeanUtils.copyProperties(entity, view);
        view.setId(node.getId());
        view.setSpaceId(node.getId());
        view.setProjectId(projectId);
        view.setName(node.getName());
        view.setFullName(node.getFullName());
        view.setSpaceTypeId(node.getSpaceTypeId());
        view.setSpaceTypeName(node.getSpaceTypeName());
        view.setSpaceLocation(node.getSpaceLocation());
        view.setSpaceSegments(node.getSpaceSegments());
        view.setVersion(entity == null ? 0L : entity.getVersion());
        view.setCompleted(completed);
        return view;
    }

    @Getter
    @Setter
    public static class HouseView extends PropertyHouseEntity {
        private String spaceId, name, fullName;
        private String spaceTypeId, spaceTypeName, spaceLocation;
        private java.util.List<PropertySpaceEntity.SpaceSegment> spaceSegments =
                java.util.List.of();
        private Boolean completed;
    }

    public record HouseWrite(
            Long version,
            String houseType,
            String houseState,
            BigDecimal billingArea,
            BigDecimal buildingArea,
            BigDecimal insideArea,
            Long deliveryDate,
            String remark,
            String spaceId) {}

    public record ImportResult(int count) {}
}
