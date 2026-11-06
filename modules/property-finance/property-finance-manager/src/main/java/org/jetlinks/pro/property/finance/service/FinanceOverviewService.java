package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.FundAccountEntity;
import org.jetlinks.pro.property.finance.overview.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** 项目经营概览只读聚合；财务查询串行共用快照，基础资产通过独立命令保持原权限上下文。 */
@Service
@RequiredArgsConstructor
public class FinanceOverviewService {
    private final FinanceScope scope;
    private final HouseArchiveSource houses;
    private final ReceivableReportService receivables;
    private final ReceivedReportService received;
    private final ReactiveRepository<FundAccountEntity, String> accounts;
    private final DatabaseOperator database;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<OverviewResult> query(OverviewInput input) {
        return Mono.defer(
                        () -> {
                            if (input == null)
                                return Mono.error(
                                        FinanceScope.error("overview_query_invalid", 400));
                            input.validate();
                            var ids = new ArrayList<>(new LinkedHashSet<>(input.getProjectIds()));
                            // 混入任何不可访问项目都拒绝整请求；不把部分汇总伪装成完整结果。
                            return Flux.fromIterable(ids)
                                    .concatMap(id -> scope.project(id, CrudAssetPermission.read))
                                    .then()
                                    .then(houses.summary(ids))
                                    .flatMap(
                                            asset ->
                                                    receivables
                                                            .overview(input.receivables())
                                                            .flatMap(
                                                                    bills ->
                                                                            received.overview(
                                                                                            input
                                                                                                    .cash(
                                                                                                            false),
                                                                                            input
                                                                                                    .grain())
                                                                                    .flatMap(
                                                                                            cash ->
                                                                                                    balances(
                                                                                                                    ids)
                                                                                                            .flatMap(
                                                                                                                    balances ->
                                                                                                                            received.overviewHandover(
                                                                                                                                            input
                                                                                                                                                    .cash(
                                                                                                                                                            true))
                                                                                                                                    .map(
                                                                                                                                            handover ->
                                                                                                                                                    new OverviewResult(
                                                                                                                                                            asset,
                                                                                                                                                            bills,
                                                                                                                                                            cash,
                                                                                                                                                            balances,
                                                                                                                                                            handover,
                                                                                                                                                            LocalDate
                                                                                                                                                                    .now(
                                                                                                                                                                            ZoneId
                                                                                                                                                                                    .of(
                                                                                                                                                                                            "Asia/Shanghai"))
                                                                                                                                                                    .toString()))))));
                        })
                .as(MonoTracer.create("/property/finance/overview/query"));
    }

    Mono<OverviewResult.Funds> balances(List<String> ids) {
        var query = new QueryParamEntity();
        query.setPaging(false);
        query.toQuery()
                .in("projectId", ids)
                .and("deleted", false)
                .in("type", List.of("ADVANCE", "DEPOSIT"));
        // 账户与收款明细同样按关联空间过滤；不能只校验项目后汇总整项目资金。
        return FinanceSpaceQuery.inject(query, accounts, CrudAssetPermission.read)
                .flatMap(
                        secured -> {
                            var sql =
                                    accounts.nativeQuery()
                                            .select("type", "subjectId", "subjectName", "balance")
                                            .setParam(secured)
                                            .getSql();
                            return database.sql()
                                    .reactive()
                                    .select(
                                            org.hswebframework.ezorm.rdb.executor.SqlRequests.of(
                                                    "select \"type\", \"subjectId\","
                                                        + " \"subjectName\", sum(\"balance\") as"
                                                        + " amount from ("
                                                            + sql.getSql()
                                                            + ") overview_accounts group by"
                                                            + " \"type\", \"subjectId\","
                                                            + " \"subjectName\" order by \"type\","
                                                            + " \"subjectName\", \"subjectId\"",
                                                    sql.getParameters()),
                                            ResultWrappers.map())
                                    .map(
                                            row ->
                                                    new OverviewResult.Balance(
                                                            Objects.toString(row.get("type")),
                                                            Objects.toString(
                                                                    row.get("subjectId"), ""),
                                                            Objects.toString(
                                                                    row.get("subjectName"), ""),
                                                            new BigDecimal(
                                                                            Objects.toString(
                                                                                    row.get(
                                                                                            "amount"),
                                                                                    "0"))
                                                                    .setScale(2)
                                                                    .toPlainString()))
                                    .collectList()
                                    .map(
                                            rows ->
                                                    new OverviewResult.Funds(
                                                            rows,
                                                            balanceTotal(rows, "ADVANCE"),
                                                            balanceTotal(rows, "DEPOSIT")));
                        });
    }

    private static String balanceTotal(List<OverviewResult.Balance> rows, String type) {
        return rows.stream()
                .filter(row -> type.equals(row.type()))
                .map(row -> new BigDecimal(row.amount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2)
                .toPlainString();
    }
}
