package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.PagerQueryPolicy;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.patrol.entity.PatrolRemovableConfigEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Collection;
import java.util.function.Function;

/** 配置的有效行策略覆盖列表、分页、详情和内部引用；仍复用框架 CRUD 与分页实现。 */
public abstract class PatrolRemovableConfigService<E extends PatrolRemovableConfigEntity>
        extends GenericReactiveCrudService<E, String> {
    protected PatrolRemovableConfigService(ReactiveRepository<E, String> repository) { super(repository); }

    @Override public ReactiveQuery<E> createQuery() { return super.createQuery().isNull("deletedTime"); }

    private QueryParamEntity active(QueryParamEntity source) {
        var query = source.clone();
        // 整体嵌套输入条件，防止输入 OR 将删除约束放宽。
        query.toNestQuery().isNull("deletedTime");
        return query;
    }

    @Override public Flux<E> query(QueryParamEntity query) { return super.query(active(query)); }
    @Override public Mono<Integer> count(QueryParamEntity query) { return super.count(active(query)); }
    @Override public <T> Mono<PagerResult<T>> queryPager(QueryParamEntity query, Function<E, T> mapper, PagerQueryPolicy policy) {
        return super.queryPager(active(query), mapper, policy);
    }
    @Override public Mono<E> findById(String id) { return createQuery().where("id", id).fetchOne(); }
    @Override public Flux<E> findById(Collection<String> ids) { return createQuery().in("id", ids).fetch(); }
    @Override public Mono<E> findById(Mono<String> id) { return id.flatMap(this::findById); }
    @Override public Flux<E> findById(Flux<String> ids) { return ids.buffer(200).concatMap(this::findById); }

    /** 仅用于现有写事务：引用与删除共用行锁，避免校验通过后配置被并发删除。 */
    public Mono<E> findForUpdate(String id) { return createQuery().where("id", id).forUpdate().fetchOne(); }
}
