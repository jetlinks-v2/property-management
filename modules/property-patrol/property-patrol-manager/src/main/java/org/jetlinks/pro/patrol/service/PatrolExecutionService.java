package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.hswebframework.ezorm.core.param.Term;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;

/** 执行事实只追加；PC 和 H5 查询关联原始时段，不能依照当前规则重算时间或等级。 */
@Service
public class PatrolExecutionService extends GenericReactiveCrudService<PatrolExecutionEntity, String> {
    private static final Set<String> COUNT_COLUMNS = Set.of("id", "planId", "taskId", "taskGroupId", "pointId",
            "projectId", "userId", "receiveTime", "belongDate", "result",
            "networkStatus", "requestId", "tenantId");
    private final QueryHelper helper;
    public PatrolExecutionService(ReactiveRepository<PatrolExecutionEntity, String> repository, QueryHelper helper) {
        super(repository);
        this.helper = helper;
    }
    private QueryHelper.SortSpec<PatrolExecutionEntity> snapshotQuery(QueryParamEntity query) {
        return helper.select(PatrolExecutionEntity.class).all(PatrolExecutionEntity.class)
                .as(PatrolTaskEntity::getStartTime, PatrolExecutionEntity::setPeriodStartTime)
                .as(PatrolTaskEntity::getEndTime, PatrolExecutionEntity::setPeriodEndTime)
                .as(PatrolTaskEntity::getCategoryId, PatrolExecutionEntity::setCategoryId)
                .as(PatrolTaskEntity::getCategoryName, PatrolExecutionEntity::setCategoryName)
                .as(PatrolTaskEntity::getLevelId, PatrolExecutionEntity::setLevelId)
                .as(PatrolTaskEntity::getLevelName, PatrolExecutionEntity::setLevelName)
                .from(PatrolExecutionEntity.class)
                .leftJoin(PatrolTaskEntity.class, join -> join.is(PatrolTaskEntity::getId, PatrolExecutionEntity::getTaskId))
                .where(query);
    }
    @Override public Mono<PagerResult<PatrolExecutionEntity>> queryPager(QueryParamEntity query) {
        var page = query.clone();
        page.setPaging(true);
        // 页数据仍带时段快照；计数不为未使用的快照列关联整个历史任务表。
        return count(page).flatMap(total -> total == 0 ? Mono.just(PagerResult.of(0, java.util.List.of(), page))
                : snapshotQuery(page).fetch().collectList().map(rows -> PagerResult.of(total, rows, page)));
    }
    @Override public Flux<PatrolExecutionEntity> query(QueryParamEntity query) { return snapshotQuery(query).fetch(); }
    /** 已授权任务集合的统计事实投影，无需再次关联时段或读取答案、照片和检查标准快照。 */
    public Flux<PatrolExecutionEntity> queryStatisticsFacts(QueryParamEntity query) {
        query.setIncludes(Set.of("taskId", "pointId", "projectId", "userId", "userName", "receiveTime", "result"));
        return super.query(query);
    }
    @Override public Mono<Integer> count(QueryParamEntity query) {
        // 不识别的列保持原快照查询；不能因省去 JOIN 而忽略分类、等级或时段条件。
        return query.getTerms().stream().allMatch(this::storedTerm) ? super.count(query) : snapshotQuery(query).count();
    }
    private boolean storedTerm(Term term) {
        return (term.getColumn() == null || COUNT_COLUMNS.contains(term.getColumn()))
                && term.getTerms().stream().allMatch(this::storedTerm);
    }
    @Override public Mono<PatrolExecutionEntity> findById(String id) {
        return query(QueryParamEntity.newQuery().is("id", id).getParam()).next();
    }
}
