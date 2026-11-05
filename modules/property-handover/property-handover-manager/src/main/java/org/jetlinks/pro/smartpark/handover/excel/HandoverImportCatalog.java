package org.jetlinks.pro.smartpark.handover.excel;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.jetlinks.pro.smartpark.handover.entity.*;
import org.jetlinks.pro.smartpark.handover.service.*;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 每次导入只读取当前授权项目的可选项；有界参考目录超过容量时明确失败，不截取前页。 */
@Component
@RequiredArgsConstructor
public class HandoverImportCatalog {
    private static final int REFERENCE_LIMIT = 20000;
    private final HandoverScope scope;
    private final HandoverDirectory directory;
    private final HandoverCategoryRepository categories;
    private final HandoverMaterialCategoryRepository materialCategories;
    private final HandoverResponsibilityRepository organizations;
    private final HandoverItemRepository items;

    public record Catalog(
            List<HandoverCategoryEntity> categories,
            List<HandoverResponsibilityEntity> organizations,
            List<HandoverItemEntity> items,
            List<PropertyEmployeeInfo> users) {
        public String categoryLabel(HandoverCategoryEntity category) {
            if (category.getParentId() == null) return category.getName();
            return categories.stream()
                    .filter(c -> c.getId().equals(category.getParentId()))
                    .findFirst()
                    .map(parent -> parent.getName() + "/" + category.getName())
                    .orElse(category.getName());
        }

        public Map<String, List<String>> options(String kind) {
            var options = options();
            if ("equipment".equals(kind)) options.remove("categoryName");
            return options;
        }

        public Map<String, List<String>> options() {
            Map<String, List<String>> result = new LinkedHashMap<>();
            result.put(
                    "categoryName",
                    categories.stream()
                            .filter(c -> !Boolean.FALSE.equals(c.getEnabled()))
                            .map(this::categoryLabel)
                            .distinct()
                            .toList());
            result.put(
                    "parentName",
                    categories.stream()
                            .filter(c -> c.getParentId() == null)
                            .map(HandoverCategoryEntity::getName)
                            .distinct()
                            .toList());
            result.put(
                    "organizationName",
                    organizations.stream()
                            .filter(c -> Boolean.TRUE.equals(c.getEnabled()))
                            .map(HandoverResponsibilityEntity::getName)
                            .distinct()
                            .toList());
            result.put(
                    "itemName",
                    items.stream()
                            .filter(c -> !Boolean.TRUE.equals(c.getConfirmed()))
                            .map(HandoverItemEntity::getName)
                            .distinct()
                            .toList());
            result.put(
                    "initiatorName",
                    users.stream().map(PropertyEmployeeInfo::name).distinct().toList());
            result.put(
                    "entryUserName",
                    users.stream().map(PropertyEmployeeInfo::name).distinct().toList());
            result.put(
                    "receiverName",
                    users.stream().map(PropertyEmployeeInfo::name).distinct().toList());
            result.put("properties.materialComplete", List.of("齐全", "不齐全"));
            result.put("inspectionType", List.of("承接查验", "管理权移交"));
            return result;
        }
    }

    public Mono<Catalog> load(String projectId, String source) {
        return load(projectId, source, null);
    }

    public Mono<Catalog> load(String projectId, String source, String kind) {
        return directory
                .employee()
                .then(scope.project(projectId, CrudAssetPermission.save))
                .then(scope.current())
                .flatMap(
                        context ->
                                Mono.zip(
                                                "record".equals(kind)
                                                        ? materialOptions(context)
                                                        : read(categories, context, projectId),
                                                read(organizations, context, projectId),
                                                read(items, context, projectId)
                                                        .map(
                                                                list ->
                                                                        list.stream()
                                                                                .filter(
                                                                                        i ->
                                                                                                source
                                                                                                                == null
                                                                                                        || source
                                                                                                                .equals(
                                                                                                                        i
                                                                                                                                .getSource()))
                                                                                .toList()),
                                                users(projectId))
                                        .map(
                                                t ->
                                                        new Catalog(
                                                                t.getT1(), t.getT2(), t.getT3(),
                                                                t.getT4())));
    }

    // 仅将共享资料目录投影为模板选项，绝不写回项目问题分类表。
    private Mono<List<HandoverCategoryEntity>> materialOptions(HandoverContext context) {
        return materialCategories
                .createQuery()
                .setParam(scope.mandatory(new QueryParamEntity(), context, null))
                .fetch()
                .take(REFERENCE_LIMIT + 1L)
                .collectList()
                .map(
                        rows -> {
                            HandoverRules.require(
                                    rows.size() <= REFERENCE_LIMIT, "excel_reference_limit");
                            var enabled =
                                    rows.stream()
                                            .filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                                            .map(HandoverMaterialCategoryEntity::getId)
                                            .collect(java.util.stream.Collectors.toSet());
                            return rows.stream()
                                    .filter(
                                            row ->
                                                    enabled.contains(row.getId())
                                                            && (row.getParentId() == null
                                                                    || enabled.contains(
                                                                            row.getParentId())))
                                    .map(
                                            row -> {
                                                var option = new HandoverCategoryEntity();
                                                option.setId(row.getId());
                                                option.setName(row.getName());
                                                option.setParentId(row.getParentId());
                                                option.setEnabled(row.getEnabled());
                                                return option;
                                            })
                                    .toList();
                        });
    }

    private <E extends HandoverEntity> Mono<List<E>> read(
            GenericReactiveCrudService<E, String> repository,
            HandoverContext context,
            String projectId) {
        var query = scope.mandatory(new QueryParamEntity(), context, projectId);
        query.setPaging(false);
        return repository
                .createQuery()
                .setParam(query)
                .fetch()
                .take(REFERENCE_LIMIT + 1L)
                .collectList()
                .doOnNext(
                        list ->
                                HandoverRules.require(
                                        list.size() <= REFERENCE_LIMIT, "excel_reference_limit"));
    }

    private Mono<List<PropertyEmployeeInfo>> users(String projectId) {
        return directory
                .users(projectId, null, 0, 200)
                .flatMap(
                        first -> {
                            HandoverRules.require(
                                    first.getTotal() <= REFERENCE_LIMIT, "excel_reference_limit");
                            int pages = (int) ((first.getTotal() + 199) / 200);
                            var collected = new ArrayList<>(first.getData());
                            return Flux.range(1, Math.max(0, pages - 1))
                                    .concatMap(page -> directory.users(projectId, null, page, 200))
                                    .doOnNext(
                                            page -> {
                                                HandoverRules.require(
                                                        page.getTotal() == first.getTotal(),
                                                        "excel_directory_changed");
                                                collected.addAll(page.getData());
                                            })
                                    .then(
                                            Mono.fromSupplier(
                                                    () -> {
                                                        HandoverRules.require(
                                                                collected.size() == first.getTotal()
                                                                        && collected.stream()
                                                                                        .map(
                                                                                                PropertyEmployeeInfo
                                                                                                        ::id)
                                                                                        .distinct()
                                                                                        .count()
                                                                                == first.getTotal(),
                                                                "excel_directory_changed");
                                                        return List.copyOf(collected);
                                                    }));
                        });
    }
}
