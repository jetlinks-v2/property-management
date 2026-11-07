package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.core.param.SqlTerm;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.*;

/** 实收交账状态只读投影：稳定来源绑定决定状态，释放后保留历史但不继续占用来源。 */
@Component
@RequiredArgsConstructor
class HandoverStateQuery {
    private final ReactiveRepository<HandoverSourceEntity, String> bindings;
    private final ReactiveRepository<HandoverEntity, String> batches;
    private final DatabaseOperator database;

    Mono<Snapshot> active(List<String> projects) {
        return TenantHolder.currentId()
                .defaultIfEmpty("")
                .map(
                        tenant -> {
                            var q = new QueryParamEntity();
                            q.setPaging(false);
                            q.toQuery()
                                    .in("projectId", projects)
                                    .and("tenantId", tenant)
                                    .and("deleted", false);
                            var bq = q.clone();
                            bq.toQuery().in("status", List.of("PENDING", "APPROVED"));
                            var s =
                                    bindings.nativeQuery()
                                            .select(
                                                    "projectId",
                                                    "sourceType",
                                                    "sourceId",
                                                    "batchId")
                                            .setParam(q)
                                            .getSql();
                            var b =
                                    batches.nativeQuery()
                                            .select("id", "projectId", "status")
                                            .setParam(bq)
                                            .getSql();
                            var params = new ArrayList<>(Arrays.asList(s.getParameters()));
                            params.addAll(Arrays.asList(b.getParameters()));
                            return new Snapshot(
                                    "select s.\"projectId\", s.\"sourceType\", s.\"sourceId\","
                                        + " b.\"status\" as \"handoverState\" from ("
                                            + s.getSql()
                                            + ") s join ("
                                            + b.getSql()
                                            + ") b on s.\"batchId\"=b.\"id\" and"
                                            + " s.\"projectId\"=b.\"projectId\"",
                                    params.toArray());
                        });
    }

    Mono<Map<String, String>> payments(Snapshot snapshot, List<String> ids) {
        var params = new ArrayList<>(Arrays.asList(snapshot.parameters()));
        params.addAll(ids);
        return database.sql()
                .reactive()
                .select(
                        SqlRequests.of(
                                "select * from ("
                                        + snapshot.sql()
                                        + ") active_handover where \"sourceType\"='PAYMENT' and"
                                        + " \"sourceId\" in ("
                                        + String.join(",", Collections.nCopies(ids.size(), "?"))
                                        + ")",
                                params.toArray()),
                        ResultWrappers.map())
                .collectMap(
                        row -> Objects.toString(row.get("sourceId")),
                        row -> Objects.toString(row.get("handoverState")));
    }

    record Snapshot(String sql, Object[] parameters) {
        List<Term> terms(List<Term> input) {
            var result = new ArrayList<Term>();
            for (var term : input) {
                if ("handoverState".equals(term.getColumn())) {
                    String state = Objects.toString(term.getValue(), "");
                    if (!"eq".equals(term.getTermType())
                            || !Set.of("UNHANDED", "PENDING", "APPROVED").contains(state)
                            || !term.getTerms().isEmpty())
                        throw FinanceScope.error("report_query_invalid", 400);
                    var params = new ArrayList<>(Arrays.asList(parameters));
                    String predicate =
                            "payment_id "
                                    + ("UNHANDED".equals(state) ? "not in" : "in")
                                    + " (select \"sourceId\" from ("
                                    + sql
                                    + ") active_handover where \"sourceType\"='PAYMENT'";
                    if (!"UNHANDED".equals(state)) {
                        predicate += " and \"handoverState\"=?";
                        params.add(state);
                    }
                    var filter = SqlTerm.of(predicate + ")", params.toArray());
                    filter.setType(term.getType());
                    result.add(filter);
                } else {
                    term.setTerms(terms(term.getTerms()));
                    result.add(term);
                }
            }
            return result;
        }
    }
}
