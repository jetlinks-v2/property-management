package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;

/** 执行事实只追加；PC 和 H5 查询关联原始时段，不能依照当前规则重算时间或等级。 */
@Service
public class PatrolExecutionService extends GenericReactiveCrudService<PatrolExecutionEntity, String> {
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
        return snapshotQuery(query).fetchPaged();
    }
    @Override public Flux<PatrolExecutionEntity> query(QueryParamEntity query) { return snapshotQuery(query).fetch(); }
    /** 已授权任务集合的统计事实投影，无需再次关联时段或读取答案、照片和检查标准快照。 */
    public Flux<PatrolExecutionEntity> queryStatisticsFacts(QueryParamEntity query) {
        query.setIncludes(Set.of("id", "taskId", "pointId", "projectId", "userId", "userName", "receiveTime", "result"));
        return super.query(query);
    }
    @Override public Mono<Integer> count(QueryParamEntity query) { return snapshotQuery(query).count(); }
    @Override public Mono<PatrolExecutionEntity> findById(String id) {
        return query(QueryParamEntity.newQuery().is("id", id).getParam()).next();
    }
}
