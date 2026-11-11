package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.report.AnalysisInput;
import org.springframework.stereotype.Component;

import reactor.core.publisher.*;

import java.util.*;

/** 所有源表先经过正式项目、空间授权；只拼接服务器定义的列和表达式。 */
@Component
@RequiredArgsConstructor
class AnalysisSql {
    private final DatabaseOperator database;

    record Source(String sql, List<Object> parameters) {
        Source wrap(String select) {
            return new Source(select.replace("$SOURCE", sql), parameters);
        }
    }

    <T> Mono<Source> source(
            ReactiveRepository<T, String> rows, AnalysisInput input, String... columns) {
        var q = new QueryParamEntity();
        q.setPaging(false);
        q.setForUpdate(false);
        q.toQuery().in("projectId", input.getProjectIds());
        if (!input.getSpaceIds().isEmpty()) q.toQuery().in("spaceId", input.getSpaceIds());
        return FinanceSpaceQuery.inject(q, rows, CrudAssetPermission.read)
                .map(
                        secured -> {
                            var sql = rows.nativeQuery().select(columns).setParam(secured).getSql();
                            return new Source(sql.getSql(), Arrays.asList(sql.getParameters()));
                        });
    }

    <T> Source related(ReactiveRepository<T, String> rows, AnalysisInput input, String... columns) {
        var q = new QueryParamEntity();
        q.setPaging(false);
        q.toQuery().in("projectId", input.getProjectIds());
        var sql = rows.nativeQuery().select(columns).setParam(q).getSql();
        return new Source(sql.getSql(), Arrays.asList(sql.getParameters()));
    }

    Source join(String sql, Source... sources) {
        var values = new ArrayList<Object>();
        for (int n = 0; n < sources.length; n++) {
            sql = sql.replace("$" + n, sources[n].sql());
            values.addAll(sources[n].parameters());
        }
        return new Source(sql, values);
    }

    Flux<Map<String, Object>> select(Source source, String query, Object... extra) {
        var values = new ArrayList<Object>(source.parameters());
        values.addAll(Arrays.asList(extra));
        return database.sql()
                .reactive()
                .select(
                        SqlRequests.of(query.replace("$SOURCE", source.sql()), values.toArray()),
                        ResultWrappers.map());
    }
}
