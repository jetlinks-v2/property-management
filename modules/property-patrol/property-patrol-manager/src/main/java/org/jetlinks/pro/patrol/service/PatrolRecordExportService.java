package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.jetlinks.pro.patrol.entity.PatrolGenerationEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * 为记录导出分批读取真实时段和执行事实；调用方先完成任务组筛选与授权。
 * 子表沿用各自的计划资产读权限，仅查询原始事实，不关联漏检申报或审核历史。
 */
@Service
@AllArgsConstructor
public class PatrolRecordExportService {
    public static final int DETAIL_LIMIT = 100000;
    static final int GROUP_BATCH_SIZE = 64;
    private final ReactiveRepository<PatrolTaskEntity, String> tasks;
    private final ReactiveRepository<PatrolExecutionEntity, String> executions;

    /** 一批关联数据写完即可释放；不跨批次保留答案和检查标准快照。 */
    public record Batch(List<PatrolGenerationEntity> groups, List<PatrolTaskEntity> periods,
                        List<PatrolExecutionEntity> executions) {}

    public Flux<Batch> batches(List<PatrolGenerationEntity> authorizedGroups) {
        return Flux.defer(() -> {
            var budget = new DetailBudget();
            return Flux.fromIterable(authorizedGroups).buffer(GROUP_BATCH_SIZE)
                    .concatMap(groups -> load(groups, budget), 1);
        });
    }

    private Mono<Batch> load(List<PatrolGenerationEntity> groups, DetailBudget budget) {
        var ids = groups.stream().map(PatrolGenerationEntity::getId).toList();
        var periodQuery = groupQuery(ids, "periodNo");
        // 时段表不重复读取整套检查标准；执行详情使用执行时已保存的标准快照。
        periodQuery.setExcludes(Set.of("standardSnapshot"));
        return read(tasks, periodQuery, budget, row -> 1)
                .flatMap(periods -> read(executions, groupQuery(ids, "receiveTime"), budget,
                        row -> 1 + checkItemIds(row).size())
                    .map(facts -> new Batch(groups, periods, facts)));
    }

    private static QueryParamEntity groupQuery(List<String> ids, String order) {
        var query = QueryParamEntity.newQuery().in("taskGroupId", ids)
                .orderByAsc("taskGroupId").orderByAsc(order).orderByAsc("id").getParam();
        // 数据库也限制最大扫描结果；展开后的检查项等行数由同一请求预算进一步约束。
        query.setPageSize(DETAIL_LIMIT + 1);
        query.setPaging(true);
        return query;
    }

    private <T> Mono<List<T>> read(ReactiveRepository<T, String> repository, QueryParamEntity query,
                                   DetailBudget budget, ToIntFunction<T> rows) {
        return AssetsHolder.injectQueryParam(query, PatrolAssetType.patrolPlan, "planId")
                .flatMapMany(authorized -> repository.createQuery().setParam(authorized).fetch())
                .doOnNext(row -> budget.add(rows.applyAsInt(row))).collectList();
    }

    /** 同时保留快照中的未填写项及历史答案中的额外项，不能用当前标准覆盖历史。 */
    public static Set<String> checkItemIds(PatrolExecutionEntity execution) {
        var ids = new LinkedHashSet<String>();
        if (execution.getStandardSnapshot() != null) execution.getStandardSnapshot().forEach(item -> ids.add(item.id()));
        if (execution.getAnswers() != null) execution.getAnswers().forEach(answer -> ids.add(answer.itemId()));
        if (execution.getItemResults() != null) execution.getItemResults().forEach(result -> ids.add(result.itemId()));
        return ids;
    }

    /** 上限按 Excel 实际展开行计数，不能只按任务组数限制而放大到无界执行/检查项。 */
    static final class DetailBudget {
        private int used;
        void add(int rows) {
            used += rows;
            if (used > DETAIL_LIMIT) throw new BusinessException("error.patrol_record_export_detail_limit");
        }
    }
}
