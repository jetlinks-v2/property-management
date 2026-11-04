package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.patrol.entity.PatrolGenerationEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 任务组先按快照筛选分页，再批量汇总本页真实时段，避免 N+1 与前端聚合分页失真。 */
@Service
public class PatrolTaskGroupService extends GenericReactiveCrudService<PatrolGenerationEntity, String> {
    private final QueryHelper helper;
    private final PatrolTaskService tasks;

    public PatrolTaskGroupService(ReactiveRepository<PatrolGenerationEntity, String> repository,
                                  QueryHelper helper, PatrolTaskService tasks) {
        super(repository);
        this.helper = helper;
        this.tasks = tasks;
    }

    private QueryHelper.SortSpec<PatrolGenerationEntity> snapshotQuery(QueryParamEntity query) {
        if (query.getSorts().isEmpty()) query.toQuery().orderByDesc("belongDate").orderByAsc("id");
        // 首时段内连接排除休息日的空生成封印；保留封印防重，列表、计数和导出只查询真实任务组。
        // 唯一索引保证每组只有一个 periodNo=1；类别/等级来自真实时段快照，筛选在分页前完成。
        return helper.select(PatrolGenerationEntity.class)
                .all(PatrolGenerationEntity.class)
                .as(PatrolTaskEntity::getCategoryId, PatrolGenerationEntity::setCategoryId)
                .as(PatrolTaskEntity::getCategoryName, PatrolGenerationEntity::setCategoryName)
                .as(PatrolTaskEntity::getLevelId, PatrolGenerationEntity::setLevelId)
                .as(PatrolTaskEntity::getLevelName, PatrolGenerationEntity::setLevelName)
                .from(PatrolGenerationEntity.class)
                .innerJoin(PatrolTaskEntity.class, join -> join
                        .is(PatrolTaskEntity::getTaskGroupId, PatrolGenerationEntity::getId)
                        .and(PatrolTaskEntity::getPeriodNo, 1))
                .where(query);
    }

    @Override
    public Mono<PagerResult<PatrolGenerationEntity>> queryPager(QueryParamEntity query) {
        return QueryHelper.transformPageResult(snapshotQuery(query).fetchPaged(), this::fillProgress);
    }

    @Override
    public Mono<Integer> count(QueryParamEntity query) {
        return snapshotQuery(query).count();
    }

    @Override
    public Flux<PatrolGenerationEntity> query(QueryParamEntity query) {
        return snapshotQuery(query).fetch().buffer(200).concatMap(this::fillProgress).flatMapIterable(Function.identity());
    }

    @Override
    public Mono<PatrolGenerationEntity> findById(String id) {
        return query(QueryParamEntity.newQuery().is("id", id).getParam()).next();
    }

    private Mono<List<PatrolGenerationEntity>> fillProgress(List<PatrolGenerationEntity> groups) {
        if (groups.isEmpty()) return Mono.just(groups);
        var byId = groups.stream().collect(Collectors.toMap(PatrolGenerationEntity::getId, Function.identity()));
        // 主组已由控制器按 planId 注入资产权限；子记录仅查询这些组，不扩展授权范围。
        return tasks.createQuery().select("taskGroupId", "completedCount", "lastExecutionTime")
                .in(PatrolTaskEntity::getTaskGroupId, byId.keySet()).fetch()
                .doOnNext(task -> {
                    var group = byId.get(task.getTaskGroupId());
                    group.setCompletedCount(group.getCompletedCount() + task.getCompletedCount());
                    if (task.getLastExecutionTime() != null && (group.getLastExecutionTime() == null
                            || task.getLastExecutionTime() > group.getLastExecutionTime())) {
                        group.setLastExecutionTime(task.getLastExecutionTime());
                    }
                }).then(Mono.just(groups));
    }
}
