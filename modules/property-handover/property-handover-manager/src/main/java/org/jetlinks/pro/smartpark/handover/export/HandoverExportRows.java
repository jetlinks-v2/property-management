package org.jetlinks.pro.smartpark.handover.export;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.smartpark.handover.api.ExportRequest;
import org.jetlinks.pro.smartpark.handover.entity.*;
import org.jetlinks.pro.smartpark.handover.excel.HandoverExcelSchema;
import org.jetlinks.pro.smartpark.handover.service.*;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * The exact problem selector is reused for page, count and export, including time and contractor
 * scope.
 */
@Component
@RequiredArgsConstructor
public class HandoverExportRows {
    static final int PAGE_SIZE = 100;
    private static final Set<String> FILTERS =
            Set.of(
                    "keyword",
                    "location",
                    "modifyTimeFrom",
                    "modifyTimeTo",
                    "status",
                    "auditStage",
                    "organizationId",
                    "categoryId",
                    "itemId",
                    "view",
                    "inspectionTimeFrom",
                    "inspectionTimeTo");
    private final ObjectMapper mapper;
    private final HandoverScope scope;
    private final HandoverConfigurationService configuration;
    private final HandoverDirectory directory;
    private final HandoverProblemService problems;
    private final HandoverProblemRepository problemRepository;
    private final HandoverCategoryRepository categories;
    private final HandoverMaterialCategoryRepository materialCategories;
    private final HandoverLedgerRepository ledgers;
    private final HandoverEquipmentRepository equipment;
    private final HandoverRecordRepository records;
    private final HandoverItemRepository items;
    private final HandoverPhotoRepository photos;
    private final HandoverNodeRepository nodes;

    public void validate(ExportRequest input) {
        HandoverExcelSchema.columns(input.getKind());
        HandoverRules.require("ledger".equals(input.getKind()) || HandoverRules.hasText(input.getProjectId()), "project_required");
        HandoverRules.require(
                input.getFilters() == null || FILTERS.containsAll(input.getFilters().keySet()),
                "export_filter_invalid");
        HandoverRules.require(
                input.getIds() == null || input.getIds().size() <= 10000, "export_selection_limit");
        if (input.getSource() != null)
            HandoverRules.require(
                    Set.of(
                                    "SITE_INSPECTION",
                                    "PHYSICAL_HANDOVER",
                                    "QUALITY_ACCEPTANCE",
                                    "MATERIAL_TRANSFER")
                            .contains(input.getSource()),
                    "source_invalid");
        if (input.getStage() != null)
            HandoverRules.require(HandoverRules.STAGES.contains(input.getStage()), "stage_invalid");
    }

    HandoverQuery query(ExportRequest input, int page, List<String> ids) {
        HandoverQuery q =
                mapper.convertValue(
                        input.getFilters() == null ? Map.of() : input.getFilters(),
                        HandoverQuery.class);
        q.setProjectId(input.getProjectId());
        q.setSource(input.getSource());
        q.setStage(input.getStage());
        q.setIds(ids == null ? input.getIds() : ids);
        q.setPageIndex(page);
        q.setPageSize(PAGE_SIZE);
        return q;
    }

    Mono<QueryParamEntity> selector(ExportRequest input, int page, List<String> ids) {
        HandoverQuery q = query(input, page, ids);
        if ("problem".equals(input.getKind())) return problems.selector(q);
        if ("ledger".equals(input.getKind())) return configuration.authorizedQuery(q, "description");
        return scope.current()
                .flatMap(
                        c ->
                                scope.query(
                                        q.parameter(keyword(input.getKind())),
                                        c,
                                        input.getProjectId()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    GenericReactiveCrudService<HandoverEntity, String> repository(String kind) {
        return (GenericReactiveCrudService)
                switch (kind) {
                    case "problem" -> problemRepository;
                    case "category" -> categories;
                    case "ledger" -> ledgers;
                    case "equipment" -> equipment;
                    case "record" -> records;
                    case "item" -> items;
                    default -> throw new IllegalArgumentException("Unknown export kind");
                };
    }

    static String keyword(String kind) {
        return "ledger".equals(kind) ? "description" : Set.of("category", "equipment", "item").contains(kind) ? "name" : "title";
    }

    public Mono<List<HandoverEntity>> page(ExportRequest input, int page) {
        return selector(input, page, null)
                .flatMap(
                        q ->
                                repository(input.getKind())
                                        .createQuery()
                                        .setParam(q)
                                        .fetch()
                                        .collectList())
                .flatMap(data -> {
                    if (!"ledger".equals(input.getKind())) return Mono.just(data);
                    // 每页仅请求一次公共项目目录，避免逐行发起跨服务命令。
                    return directory.projects().collectMap(p -> p.getId(), p -> p.getName()).map(projects -> {
                        data.forEach(row -> ((HandoverLedgerEntity) row).setProjectName(projects.get(row.getProjectId())));
                        return data;
                    });
                });
    }

    public Mono<Void> verify(ExportRequest input, List<String> ids) {
        // Filter changes after generation must never grant access to old file contents; verify
        // identities
        // through the live selector, retaining the original project and contractor visibility
        // conditions.
        return selector(input, 0, ids)
                .flatMap(
                        q -> {
                            q.setPaging(false);
                            return repository(input.getKind()).createQuery().setParam(q).count();
                        })
                .flatMap(
                        count ->
                                count == ids.size()
                                        ? Mono.empty()
                                        : Mono.error(new AccessDenyException()));
    }

    public Flux<HandoverPhotoEntity> photos(HandoverEntity row) {
        QueryParamEntity q =
                QueryParamEntity.newQuery()
                        .where("problemId", row.getId())
                        .and("deleted", false)
                        .and("tenantId", row.getTenantId())
                        .and("applicationId", row.getApplicationId())
                        .and("projectId", row.getProjectId())
                        .orderByAsc("photoType")
                        .orderByAsc("sortIndex")
                        .orderByAsc("createTime")
                        .orderByAsc("id")
                        .getParam();
        q.setPaging(false);
        return photos.createQuery().setParam(q).fetch();
    }

    public Flux<HandoverNodeEntity> nodes(HandoverEntity row) {
        QueryParamEntity q =
                QueryParamEntity.newQuery()
                        .where("problemId", row.getId())
                        .and("deleted", false)
                        .and("tenantId", row.getTenantId())
                        .and("applicationId", row.getApplicationId())
                        .and("projectId", row.getProjectId())
                        .orderByAsc("time")
                        .orderByAsc("id")
                        .getParam();
        q.setPaging(false);
        return nodes.createQuery().setParam(q).fetch();
    }

    public Mono<Map<String, Object>> values(HandoverEntity row) {
        Map<String, Object> value =
                mapper.convertValue(
                        row,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                LinkedHashMap<String, Object>>() {});
        Mono<?> related = Mono.empty();
        if (row instanceof HandoverLedgerEntity ledger && ledger.getProjectName() == null)
            related = directory.project(row.getProjectId()).doOnNext(p -> value.put("projectName", p.getName()));
        String category = Objects.toString(value.get("categoryId"), "");
        if (!category.isEmpty() && !(row instanceof HandoverEquipmentEntity))
            related = related.then(
                    row instanceof HandoverRecordEntity
                            ? materialCategories
                                    .findById(category)
                                    .doOnNext(c -> value.put("categoryName", c.getName())).then()
                            : categories
                                    .findById(category)
                                    .doOnNext(c -> value.put("categoryName", c.getName())).then());
        if (row instanceof HandoverRecordEntity record && record.getProperties() != null)
            value.put("receiverName", record.getProperties().getOrDefault("receiverName", ""));
        String parent = Objects.toString(value.get("parentId"), "");
        if (!parent.isEmpty())
            related =
                    related.then(
                            categories
                                    .findById(parent)
                                    .doOnNext(c -> value.put("parentName", c.getName())));
        if (row instanceof HandoverProblemEntity p && p.getItemId() != null)
            related =
                    related.then(
                            items.findById(p.getItemId())
                                    .doOnNext(
                                            i -> {
                                                value.put("itemName", i.getName());
                                                value.put("itemCode", i.getCode());
                                            }));
        return related.thenReturn(value);
    }
}
