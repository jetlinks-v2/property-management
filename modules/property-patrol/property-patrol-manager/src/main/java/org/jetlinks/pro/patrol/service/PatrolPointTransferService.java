package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.*;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.stream.Collectors;

/** 点位文件交换边界：引用资产读取授权、当前应用归属、批量插入和新资产绑定。 */
@Service
@AllArgsConstructor
public class PatrolPointTransferService {
    public static final int EXPORT_LIMIT = 10000;
    private final PatrolPointService points;
    private final PatrolCategoryService categories;
    private final PatrolTagService tags;
    private final PatrolPostService posts;
    private final PatrolPlanService plans;
    private final PatrolScope scope;
    private final TransactionalOperator transaction;

    public Mono<byte[]> template() {
        return scope.current().flatMap(current -> {
            var query = QueryParamEntity.newQuery().is("enabled", true).is("scopeKey", current.key()).getParam();
            query.setPaging(false);
            return AssetsHolder.injectQueryParam(query, PatrolAssetType.patrolCategory, "id")
                    .flatMapMany(categories::query).take(PatrolPointWorkbook.MAX_ROWS + 1L).collectList()
                    .flatMap(rows -> rows.size() > PatrolPointWorkbook.MAX_ROWS
                            ? Mono.error(PatrolPointWorkbook.invalid())
                            : Mono.fromCallable(() -> PatrolPointWorkbook.template(rows.stream()
                                    .collect(Collectors.toMap(PatrolConfigEntity::getId, PatrolConfigEntity::getName, (a,b) -> a, TreeMap::new))))
                                    .subscribeOn(Schedulers.boundedElastic()));
        });
    }

    public Mono<PatrolPointWorkbook.Preview> preview(byte[] bytes) {
        return Mono.fromCallable(() -> PatrolPointWorkbook.read(bytes)).subscribeOn(Schedulers.boundedElastic())
                .flatMap(rows -> scope.current().flatMap(current -> names(categories, PatrolAssetType.patrolCategory,
                        rows.stream().map(PatrolPointWorkbook.ImportRow::categoryId).filter(id -> !id.isBlank()).toList(), current.key())
                        .map(names -> {
                            for (var row : rows) {
                                // 无权读取的分类与不存在的分类使用相同提示；不返回其名称。
                                if (!names.containsKey(row.categoryId())) row.errors().add("categoryUnavailable");
                                else if (!Objects.equals(names.get(row.categoryId()), row.categoryName())) row.errors().add("categoryMismatch");
                            }
                            return new PatrolPointWorkbook.Preview(rows);
                        })));
    }

    /** 提交时重新验证原文件；整批插入和绑定原子完成，失败不会留下半份数据。 */
    public Mono<Integer> importPoints(byte[] bytes) {
        return preview(bytes).flatMap(preview -> {
            if (!preview.valid()) return Mono.error(PatrolPointWorkbook.invalid());
            return scope.current().zipWith(ReactiveAuthenticationHolder.get()).flatMap(pair -> {
                long now = System.currentTimeMillis(); String user = pair.getT2().getUser().getId();
                List<PatrolPointEntity> rows = preview.rows().stream().map(source -> {
                    var row = new PatrolPointEntity(); row.setId(UUID.randomUUID().toString());
                    row.setCode("PT" + row.getId().replace("-", "").toUpperCase(Locale.ROOT));
                    row.setName(source.name()); row.setCategoryId(source.categoryId()); row.setLocation(source.location());
                    row.setScopeKey(pair.getT1().key()); row.setTagIds(List.of()); row.setNfcCode(""); row.setDescription("");
                    row.setCreatorId(user); row.setModifierId(user);
                    row.setCreatorName(Objects.requireNonNullElse(pair.getT2().getUser().getName(), "")); row.setModifierName(row.getCreatorName()); row.setCreateTime(now); row.setModifyTime(now);
                    row.setExtensions(new HashMap<>()); return row;
                }).toList();
                return Flux.fromIterable(rows).concatMap(PatrolOwnership::assignSelected).then()
                        .then(points.insertBatch(Flux.fromIterable(rows).buffer(200)))
                        .thenMany(AssetsHolder.bindAssets(Flux.fromIterable(rows), PatrolAssetType.patrolPoint, PatrolConfigEntity::getId))
                        .then(Mono.just(rows.size()));
            });
        }).as(transaction::transactional).as(MonoTracer.create("/patrol/points/import"));
    }

    public Mono<byte[]> export(QueryParamEntity query) {
        return export(query, null);
    }

    /** 分类侧栏导出仅包含该分类及其岗位；概览导出全部当前分类，取消后的旧字段不参与展示。 */
    public Mono<byte[]> export(QueryParamEntity source, String categoryId) {
        var query = source.clone();
        query.setPageIndex(0); query.setPageSize(EXPORT_LIMIT + 1); query.setPaging(true);
        return scope.current().flatMap(current -> {
            query.toNestQuery().and("scopeKey", current.key());
            if (categoryId != null) query.toNestQuery().and("categoryId", categoryId);
            // 全部当前筛选结果，权限在此入口注入，不能以页面勾选或前端分页代替。
            return AssetsHolder.injectQueryParam(query, PatrolAssetType.patrolPoint, "id").flatMap(points::queryPager)
                    .flatMap(page -> {
                        if (page.getTotal() > EXPORT_LIMIT) return Mono.error(new BusinessException("error.patrol.point.export_limit"));
                        var rows = page.getData();
                        var categoryIds = rows.stream().flatMap(row -> row.getCategoryIds().stream()).toList();
                        var tagIds = rows.stream().flatMap(row -> (row.getTagIds() == null ? List.<String>of() : row.getTagIds()).stream()).toList();
                        return Mono.zip(names(categories, PatrolAssetType.patrolCategory, categoryIds, current.key()),
                                        names(tags, PatrolAssetType.patrolTag, tagIds, current.key()),
                                        pointPosts(rows, categoryId, current.key()))
                                .flatMap(refs -> Mono.fromCallable(() -> {
                                            if (categoryId != null) rows.forEach(row -> row.setCategoryIds(List.of(categoryId)));
                                            return PatrolPointWorkbook.export(rows, refs.getT1(), refs.getT2(), refs.getT3());
                                        })
                                        .subscribeOn(Schedulers.boundedElastic()));
                    });
        });
    }

    private Mono<Map<String, String>> pointPosts(List<PatrolPointEntity> points, String categoryId, String key) {
        var pointIds = points.stream().map(PatrolPointEntity::getId).toList();
        if (pointIds.isEmpty()) return Mono.just(Map.of());
        var byId = points.stream().collect(Collectors.toMap(PatrolPointEntity::getId, row -> row));
        var query = QueryParamEntity.newQuery().in("pointId", pointIds).is("enabled", true).is("scopeKey", key).getParam();
        query.setPaging(false);
        return AssetsHolder.injectQueryParam(query, PatrolAssetType.patrolPlan, "id").flatMapMany(plans::query)
                .take(EXPORT_LIMIT * 20L + 1).collectList().flatMap(rows -> {
                    if (rows.size() > EXPORT_LIMIT * 20L) return Mono.error(new BusinessException("error.patrol.point.export_limit"));
                    var active = rows.stream().filter(row -> {
                        var point = byId.get(row.getPointId());
                        String category = row.getCategoryId() == null ? point.getCategoryId() : row.getCategoryId();
                        return point.getCategoryIds().contains(category) && (categoryId == null || categoryId.equals(category));
                    }).toList();
                    return names(posts, PatrolAssetType.patrolPost, active.stream().map(PatrolPlanEntity::getPostId).toList(), key)
                            .map(names -> active.stream().filter(row -> names.containsKey(row.getPostId()))
                                    .collect(Collectors.groupingBy(PatrolPlanEntity::getPointId, Collectors.mapping(row -> names.get(row.getPostId()),
                                            Collectors.collectingAndThen(Collectors.toCollection(TreeSet::new), set -> String.join("、", set))))));
                });
    }

    private <E extends PatrolConfigEntity> Mono<Map<String, String>> names(GenericReactiveCrudService<E, String> service,
            PatrolAssetType type, List<String> ids, String key) {
        // 引用名称也使用各自资产的读取权限；分批避免大 IN 列表，不读取其他应用。
        return Flux.fromIterable(ids).distinct().buffer(200).concatMap(batch -> {
            var query = QueryParamEntity.newQuery().in("id", batch).is("scopeKey", key).getParam(); query.setPaging(false);
            return AssetsHolder.injectQueryParam(query, type, "id").flatMapMany(service::query);
        }).collectMap(PatrolConfigEntity::getId, PatrolConfigEntity::getName);
    }
}
