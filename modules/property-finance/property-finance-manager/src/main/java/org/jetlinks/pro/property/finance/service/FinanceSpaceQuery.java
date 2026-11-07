package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import org.hswebframework.ezorm.core.param.SqlTerm;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.dml.query.SelectColumn;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

/** 跨服务空间授权：在分页、汇总前通过平台资产管理器校验候选空间，不跨库查询 SaaS 空间表。 */
final class FinanceSpaceQuery {
    private static final AssetType SPACE = AssetType.of("space");
    // 与基础空间查询的容量边界一致，只收集去重空间，不收集全量票据/收款行。
    private static final int MAX_SPACES = 20000;

    private FinanceSpaceQuery() {}

    static <T> Mono<QueryParamEntity> inject(
            QueryParamEntity query,
            ReactiveRepository<T, String> rows,
            CrudAssetPermission permission) {
        return AssetsHolder.currentReactive()
                .switchIfEmpty(Mono.error(error("permission_denied", 403)))
                .flatMap(
                        holder ->
                                holder.isScopeIgnored(SPACE)
                                        .flatMap(
                                                ignored -> {
                                                    // 仅遵循平台明确给出的不限制空间范围，不能按本地角色名自行放行。
                                                    if (ignored) return Mono.just(query);
                                                    return restricted(
                                                            query, rows, permission, holder);
                                                }));
    }

    private static <T> Mono<QueryParamEntity> restricted(
            QueryParamEntity query,
            ReactiveRepository<T, String> rows,
            CrudAssetPermission permission,
            AssetsHolder holder) {
        var candidates = query.clone();
        candidates.setPaging(false);
        candidates.setForUpdate(false);
        candidates.setSorts(List.of());
        return rows.nativeQuery()
                .setParam(candidates)
                .select(SelectColumn.of("spaceId", "spaceId"))
                .groupBy(SelectColumn.of("spaceId"))
                .paging(0, MAX_SPACES + 1)
                .fetch(ResultWrappers.map())
                .reactive()
                .collectList()
                .flatMap(
                        candidatesRows -> {
                            if (candidatesRows.size() > MAX_SPACES)
                                return Mono.error(error("space_query_limit", 400));
                            var ids =
                                    candidatesRows.stream()
                                            .map(row -> row.get("spaceId"))
                                            .filter(Objects::nonNull)
                                            .map(Object::toString)
                                            .toList();
                            // 正式 holder 同时保留创建人、维度、权限位和作用域上限；资产提供方可通过 RPC 校验。
                            return holder.filter(
                                            Flux.fromIterable(ids), SPACE, id -> id, permission)
                                    .collectList()
                                    .map(
                                            allowed -> {
                                                query.toNestQuery(
                                                        nested -> {
                                                            if (allowed.isEmpty())
                                                                nested.accept(SqlTerm.of("1 = 0"));
                                                            else nested.in("spaceId", allowed);
                                                        });
                                                return query;
                                            });
                        });
    }
}
