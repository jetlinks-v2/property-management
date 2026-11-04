package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.core.param.SqlTerm;
import org.hswebframework.ezorm.rdb.executor.SqlRequest;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.*;

/** 整张票据/临时收款必须拥有全部房屋权限；用平台注入的明细范围构造分页前的关联约束。 */
@Component
@RequiredArgsConstructor
class DocumentQueryScope {
    private final ReactiveRepository<DocumentLineEntity, String> documentLines;
    private final ReactiveRepository<PaymentAllocationEntity, String> paymentLines;

    Mono<SqlTerm> documents(String project) {
        return allRelated(documentLines, "documentId", project, CrudAssetPermission.read);
    }

    Mono<SqlTerm> payments(String project) {
        return allRelated(paymentLines, "paymentId", project, CrudAssetPermission.save);
    }

    Mono<QueryParamEntity> inject(QueryParamEntity query, CrudAssetPermission permission) {
        return AssetsHolder.currentReactive()
                .switchIfEmpty(Mono.error(error("permission_denied", 403)))
                .flatMap(holder -> holder.injectQuery(query, "space", "spaceId", permission));
    }

    <T> Mono<SqlTerm> allRelated(
            ReactiveRepository<T, String> rows,
            String parent,
            String project,
            CrudAssetPermission permission) {
        var base = new QueryParamEntity();
        base.setPaging(false);
        base.toQuery().and("projectId", project).and("deleted", false);
        return inject(base.clone(), permission)
                .map(
                        secured -> {
                            var visible =
                                    rows.nativeQuery().select("id").setParam(secured).getSql();
                            var any =
                                    rows.nativeQuery()
                                            .select(parent)
                                            .setParam(base.clone())
                                            .getSql();
                            var hidden =
                                    rows.nativeQuery()
                                            .select(parent)
                                            .setParam(base.clone())
                                            .where(
                                                    SqlTerm.of(
                                                            "id not in (" + visible.getSql() + ")",
                                                            visible.getParameters()))
                                            .getSql();
                            // 不收集全量ID，不先分页再过滤；不存在明细或混有无权明细的主单都不能进入total。
                            return SqlTerm.of(
                                    "id in ("
                                            + any.getSql()
                                            + ") and id not in ("
                                            + hidden.getSql()
                                            + ")",
                                    parameters(any, hidden));
                        });
    }

    private static Object[] parameters(SqlRequest first, SqlRequest second) {
        var parameters = new ArrayList<>(Arrays.asList(first.getParameters()));
        parameters.addAll(Arrays.asList(second.getParameters()));
        return parameters.toArray();
    }
}
