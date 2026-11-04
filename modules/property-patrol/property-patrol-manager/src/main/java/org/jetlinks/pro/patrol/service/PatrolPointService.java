package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.crud.query.PagerQueryPolicy;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity;
import org.jetlinks.pro.patrol.entity.PatrolPointEntity;
import org.jetlinks.pro.patrol.service.terms.PatrolPointCategoryTermBuilder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 巡更点位持久化。对外写入统一经过配置入口校验。 */
@Service
public class PatrolPointService extends GenericReactiveCrudService<PatrolPointEntity, String> {
    private final ReactiveRepository<PatrolPointCategoryEntity, String> categories;

    public PatrolPointService(ReactiveRepository<PatrolPointEntity, String> repository,
                              ReactiveRepository<PatrolPointCategoryEntity, String> categories) {
        super(repository);
        this.categories = categories;
    }

    @Override public Flux<PatrolPointEntity> query(QueryParamEntity source) {
        return fill(super.query(categoryQuery(source)));
    }

    @Override public Mono<Integer> count(QueryParamEntity source) { return super.count(categoryQuery(source)); }

    @Override public <T> Mono<PagerResult<T>> queryPager(QueryParamEntity source,
                                                       Function<PatrolPointEntity, T> mapper, PagerQueryPolicy policy) {
        return QueryHelper.transformPageResult(super.queryPager(categoryQuery(source), Function.identity(), policy),
                rows -> fill(Flux.fromIterable(rows)).map(mapper).collectList());
    }

    @Override public Mono<PatrolPointEntity> findById(String id) { return fill(super.findById(id).flux()).singleOrEmpty(); }
    @Override public Flux<PatrolPointEntity> findById(Collection<String> ids) { return fill(super.findById(ids)); }

    private Flux<PatrolPointEntity> fill(Flux<PatrolPointEntity> source) {
        // 父点位的查询/详情已由其资产 owner 授权；关系没有独立数据权限，不扩大父级结果。
        return source.buffer(200).concatMap(rows -> QueryHelper.combineOneToMany(
                Flux.fromIterable(rows), PatrolPointEntity::getId, categories.createQuery(),
                PatrolPointCategoryEntity::getPointId, (point, links) -> point.setCategoryIds(links.isEmpty()
                        ? List.of(point.getCategoryId()) : links.stream().filter(PatrolPointCategoryEntity::isEnabled)
                        .map(PatrolPointCategoryEntity::getCategoryId).sorted().toList())));
    }

    private QueryParamEntity categoryQuery(QueryParamEntity source) {
        var query = source.clone();
        query.setTerms(query.getTerms().stream().map(this::categoryTerm).toList());
        return query;
    }

    /** 已发布 categoryId 的等值/包含/排除条件改为真实关系，保留分页和任意嵌套 OR。 */
    private Term categoryTerm(Term source) {
        var result = source.clone();
        result.setTerms(source.getTerms().stream().map(this::categoryTerm).toList());
        if (!"categoryId".equals(source.getColumn()) || !source.getTerms().isEmpty()) return result;
        String operator = source.getTermType();
        if (!List.of("eq", "in", "nin", "not", "neq").contains(operator)) return result;
        boolean negative = List.of("nin", "not", "neq").contains(operator);
        if (source.getOptions().contains("not")) negative = !negative;
        String positive = "in".equals(operator) || "nin".equals(operator) ? "in" : "eq";
        String relation = PatrolPointCategoryTermBuilder.TERM_TYPE;
        var match = Map.of("enabled", (Object) true, "categoryId$" + positive, source.getValue());
        QueryParamEntity expression;
        if (!negative) {
            expression = QueryParamEntity.newQuery().and("id", relation, match).orNest()
                    .and("id", relation + "$not", Map.of()).and("categoryId", positive, source.getValue())
                    .end().getParam();
        } else {
            // 排除含 A 的点位，而非“找到任意不等于 A 的分类”；多分类不能因此漏掉排除条件。
            expression = QueryParamEntity.newQuery().and("id", relation + "$not", match).nest()
                    .and("id", relation, Map.of()).or("categoryId", "in".equals(positive) ? "nin" : "not", source.getValue())
                    .end().getParam();
        }
        result.setColumn(null); result.setValue(null); result.setTermType("eq"); result.getOptions().clear();
        result.setTerms(expression.getTerms());
        return result;
    }
}
