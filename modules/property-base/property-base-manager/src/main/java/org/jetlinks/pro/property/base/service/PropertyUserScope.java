package org.jetlinks.pro.property.base.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.jetlinks.pro.property.base.entity.PropertyUserEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * 用户镜像列表和目录命令共用的只读权限适配。
 * 通过平台 AssetsHolder 批量校验原 user 资产，本地只执行 pm_user 查询；
 * 授权 ID 在分页、统计前取交集，权限故障直接传播，不缓存或修改调用者上下文。
 */
@Component
@RequiredArgsConstructor
public class PropertyUserScope {
    private final ReactiveRepository<PropertyUserEntity, String> users;
    private final SaasUserSource source;

    @Value("${property.user-sync.max-users:100000}")
    private int maxUsers = 100000;

    /** 保留原筛选和分页；候选扫描受同步上限约束，空授权生成恒假条件。 */
    public Mono<QueryParamEntity> query(QueryParamEntity input) {
        var query = input.clone();
        query.toNestQuery(q -> q.and("deleted", false));
        var candidates = query.clone();
        candidates.setPaging(false);
        candidates.setTotal(null);
        candidates.setIncludes(Set.of("id"));
        candidates.setExcludes(Set.of());
        return Authentication.currentReactive()
            .switchIfEmpty(Mono.error(UnAuthorizedException.NoStackTrace::new))
            .thenMany(users.createQuery().setParam(candidates).fetch().take(maxUsers + 1L))
            .collectList()
            .flatMap(rows -> {
                if (rows.size() > maxUsers) return Mono.error(ArchiveScope.error("too_many", 400));
                // 不把 user 的创建者/组织权限 SQL 注入镜像库，它们依赖 Cloud 的 s_user 等表。
                return source.authorizedIds(rows.stream().map(PropertyUserEntity::getId).toList())
                    .collectList();
            })
            .map(ids -> {
                query.toNestQuery(q -> {
                    if (ids.isEmpty()) q.isNull("id").notNull("id");
                    else q.in("id", ids);
                });
                return query;
            });
    }
}
