package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.function.BiConsumer;
import java.util.function.Function;

/** 为业务列表批量补充当前装修状态，不读取申请详情，也不改变记录自身的办理状态。 */
@Service
@RequiredArgsConstructor
public class DecorationListStatusService {
    private final DecorationScope scope;
    private final DecorationApplicationRepository applications;

    public <T> Mono<PagerResult<T>> fill(Mono<PagerResult<T>> page,
                                        Function<T, String> decorationId,
                                        BiConsumer<T, String> setStatus) {
        return QueryHelper.transformPageResult(page, data -> {
            if (data.isEmpty()) return Mono.just(data);
            var ids = data.stream().map(decorationId).distinct().toList();
            var input = QueryParamEntity.newQuery().in("id", ids).and("deleted", false).getParam();
            input.setPageSize(100); // 业务列表已由 scope 限制为每页最多 100 条。
            // 关联申请独立沿用可信应用、租户及 AssetsHolder 项目范围，不能借任务ID读取其他范围状态。
            return scope.current().flatMap(ctx -> scope.query(input, ctx))
                .flatMapMany(query -> {
                    query.setIncludes(java.util.Set.of("id", "status"));
                    return applications.createQuery().setParam(query).fetch();
                })
                .collectMap(DecorationApplicationEntity::getId)
                .map(statuses -> {
                    data.forEach(row -> {
                        var application = statuses.get(decorationId.apply(row));
                        setStatus.accept(row, application == null ? null : application.getStatus());
                    });
                    return data;
                });
        });
    }
}
