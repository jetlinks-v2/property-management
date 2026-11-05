package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.executor.*;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.ReceivableEntity;
import org.jetlinks.pro.property.finance.report.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import reactor.core.publisher.*;

import java.util.*;

/** 应收统计：官方DSL生成含资产权限的来源查询，再用标准SQL分页与聚合。 */
@Service
@RequiredArgsConstructor
public class ReceivableReportService {
    public static final int EXPORT_LIMIT = 10000;
    private static final String[] COLUMNS = {
        "id",
        "projectId",
        "spaceId",
        "fullName",
        "customerName",
        "subjectId",
        "subjectName",
        "calcStartDate",
        "calcEndDate",
        "receivableDate",
        "dueDate",
        "calculatedAsOfDate",
        "actualReceivableAmount",
        "feeReductionAmount",
        "accruedLateFeeAmount",
        "lateFeeReductionAmount",
        "paidAmount",
        "remark"
    };
    // 所有表达式仅引用固定列名；用户筛选参数由官方QueryParam绑定，不能进入SQL文本。
    private static final String NET = "\"actualReceivableAmount\"";
    private static final String PAID = "\"paidAmount\"";
    private static final String REMISSION =
            "(\"feeReductionAmount\" + case when \"lateFeeReductionAmount\" <"
                    + " \"accruedLateFeeAmount\" then \"lateFeeReductionAmount\" else"
                    + " \"accruedLateFeeAmount\" end)";
    private static final String STATUS =
            "case when "
                    + PAID
                    + " >= "
                    + NET
                    + " then 'PAID' when "
                    + PAID
                    + " <= 0 then 'UNPAID' else 'PARTIAL' end";
    // 未收与旧口径欠款均为逐单净应收减已收后封底；不是按到期日判断的逾期欠费。
    private static final String OUTSTANDING =
            "case when " + NET + " > " + PAID + " then " + NET + " - " + PAID + " else 0 end";
    private static final String SUMS =
            "count(*) as bills, sum("
                    + NET
                    + ") as net, sum("
                    + PAID
                    + ") as paid, sum("
                    + REMISSION
                    + ") as remission, sum("
                    + OUTSTANDING
                    + ") as arrears";
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final FinanceScope scope;
    private final DatabaseOperator database;

    /** 单次报表的摘要、总数和分页共用只读快照，避免收款并发使同一响应互相矛盾。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<ReceivableReportResult> query(ReceivableReportInput input) {
        return prepare(input)
                .flatMap(
                        prepared ->
                                load(input, prepared, input.getPageIndex(), input.getPageSize()))
                .as(MonoTracer.create("/property/finance/reports/receivables/query"));
    }

    /** 导出读取与页面同一权限/汇总口径；数据库先识别超限，不返回截断的财务报表。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<ReceivableReportResult> exportData(ReceivableReportInput input) {
        return prepare(input)
                .flatMap(
                        prepared ->
                                count(input.getGroupBy(), prepared)
                                        .flatMap(
                                                total ->
                                                        total > EXPORT_LIMIT
                                                                ? Mono.error(
                                                                        error(
                                                                                "report_export_limit",
                                                                                400))
                                                                : load(
                                                                        input,
                                                                        prepared,
                                                                        0,
                                                                        EXPORT_LIMIT)))
                .as(MonoTracer.create("/property/finance/reports/receivables/export"));
    }

    private Mono<Prepared> prepare(ReceivableReportInput input) {
        return Mono.defer(
                () -> {
                    if (input == null) return Mono.error(error("report_query_invalid", 400));
                    input.validate();
                    // 任一项目无权则整请求拒绝，禁止静默丢掉项目后显示不完整财务总额。
                    return Flux.fromIterable(new LinkedHashSet<>(input.getProjectIds()))
                            .concatMap(id -> scope.project(id, CrudAssetPermission.read))
                            .collectMap(p -> p.id(), p -> p.name(), LinkedHashMap::new)
                            .flatMap(
                                    names ->
                                            secured(input)
                                                    .map(
                                                            query -> {
                                                                var sql =
                                                                        bills.nativeQuery()
                                                                                .select(COLUMNS)
                                                                                .setParam(query)
                                                                                .getSql();
                                                                String source =
                                                                        "("
                                                                                + sql.getSql()
                                                                                + ") report_source";
                                                                if (input.getCollectionStatus()
                                                                        != null)
                                                                    source =
                                                                            "(select * from "
                                                                                    + source
                                                                                    + " where "
                                                                                    + STATUS
                                                                                    + " = ?)"
                                                                                    + " filtered_source";
                                                                var params =
                                                                        new ArrayList<>(
                                                                                Arrays.asList(
                                                                                        sql
                                                                                                .getParameters()));
                                                                if (input.getCollectionStatus()
                                                                        != null)
                                                                    params.add(
                                                                            input
                                                                                    .getCollectionStatus());
                                                                var where = new ArrayList<String>();
                                                                ReportGroupKey.filter(
                                                                        where,
                                                                        params,
                                                                        input.getSubjectGroups(),
                                                                        "subjectId",
                                                                        "subjectName");
                                                                if (!where.isEmpty())
                                                                    source =
                                                                            "(select * from "
                                                                                    + source
                                                                                    + " where "
                                                                                    + String.join(
                                                                                            " and ",
                                                                                            where)
                                                                                    + ") drilled_source";
                                                                return new Prepared(
                                                                        source,
                                                                        params.toArray(),
                                                                        names);
                                                            }));
                });
    }

    private Mono<QueryParamEntity> secured(ReceivableReportInput input) {
        var q = new QueryParamEntity();
        q.setPaging(false);
        q.setForUpdate(false);
        var terms =
                q.toQuery()
                        .in("projectId", input.getProjectIds())
                        .and("deleted", false)
                        .and("reviewState", "APPROVED")
                        .nest()
                        .isNull("split")
                        .or()
                        .is("split", false)
                        .end();
        if (!input.getSpaceIds().isEmpty()) terms.in("spaceId", input.getSpaceIds());
        if (!input.getSubjectIds().isEmpty()) terms.in("subjectId", input.getSubjectIds());
        if (input.getReceivableDateStart() != null)
            terms.gte("receivableDate", input.getReceivableDateStart());
        if (input.getReceivableDateEnd() != null)
            terms.lte("receivableDate", input.getReceivableDateEnd());
        if (input.getDueDateStart() != null) terms.gte("dueDate", input.getDueDateStart());
        if (input.getDueDateEnd() != null) terms.lte("dueDate", input.getDueDateEnd());
        if (input.getCustomerName() != null && !input.getCustomerName().isBlank())
            terms.like("customerName", "%" + input.getCustomerName().trim() + "%");
        if (input.getFullName() != null && !input.getFullName().isBlank())
            terms.like("fullName", "%" + input.getFullName().trim() + "%");
        if (input.getReceivableNo() != null && !input.getReceivableNo().isBlank())
            terms.like("id", "%" + input.getReceivableNo().trim() + "%");
        // 权限先进入来源SQL，所有汇总、count、分组与导出均不可见无权房屋。
        return AssetsHolder.currentReactive()
                .switchIfEmpty(Mono.error(error("permission_denied", 403)))
                .flatMap(
                        holder ->
                                holder.injectQuery(
                                        q, "space", "spaceId", CrudAssetPermission.read));
    }

    private Mono<ReceivableReportResult> load(
            ReceivableReportInput input, Prepared prepared, int index, int size) {
        return select("select " + SUMS + " from " + prepared.source(), prepared.parameters())
                .single()
                .flatMap(
                        summary ->
                                count(input.getGroupBy(), prepared)
                                        .flatMap(
                                                total -> {
                                                    var params =
                                                            new ArrayList<>(
                                                                    Arrays.asList(
                                                                            prepared.parameters()));
                                                    params.add((long) index * size);
                                                    params.add(size);
                                                    return select(
                                                                    pageSql(
                                                                            input.getGroupBy(),
                                                                            prepared.source()),
                                                                    params.toArray())
                                                            .map(
                                                                    row ->
                                                                            row(
                                                                                    row,
                                                                                    prepared
                                                                                            .names(),
                                                                                    input
                                                                                            .getGroupBy()))
                                                            .collectList()
                                                            .map(
                                                                    rows ->
                                                                            result(
                                                                                    input
                                                                                            .getGroupBy(),
                                                                                    summary,
                                                                                    total,
                                                                                    rows,
                                                                                    index,
                                                                                    size));
                                                }));
    }

    private static ReceivableReportResult result(
            String group,
            Map<String, Object> summary,
            long total,
            List<ReceivableReportRow> rows,
            int index,
            int size) {
        var page = new QueryParamEntity();
        page.setPageIndex(index);
        page.setPageSize(size);
        if (total > Integer.MAX_VALUE) throw error("report_result_limit", 400);
        return new ReceivableReportResult(
                group,
                ReceivableReportAmounts.of(summary),
                PagerResult.of((int) total, rows, page));
    }

    private Mono<Long> count(String group, Prepared p) {
        String sql =
                "DETAIL".equals(group)
                        ? "select count(*) as total from " + p.source()
                        : "select count(*) as total from (select "
                                + groupColumns(group)
                                + " from "
                                + p.source()
                                + " group by "
                                + groupColumns(group)
                                + ") report_groups";
        return select(sql, p.parameters())
                .single()
                .map(row -> ((Number) row.get("total")).longValue());
    }

    private static String pageSql(String group, String source) {
        String select;
        if ("DETAIL".equals(group)) {
            select =
                    "select *, "
                            + NET
                            + " as net, "
                            + PAID
                            + " as paid, "
                            + REMISSION
                            + " as remission, "
                            + OUTSTANDING
                            + " as arrears, "
                            + STATUS
                            + " as status, 1 as bills from "
                            + source
                            + " order by \"dueDate\" desc, \"id\" desc";
        } else {
            String cols = groupColumns(group);
            select =
                    "select "
                            + cols
                            + ", "
                            + SUMS
                            + " from "
                            + source
                            + " group by "
                            + cols
                            + " order by "
                            + cols;
        }
        return select + " offset ? rows fetch next ? rows only";
    }

    private static String groupColumns(String group) {
        return "PROJECT".equals(group) ? "\"projectId\"" : "\"subjectId\", \"subjectName\"";
    }

    private Flux<Map<String, Object>> select(String sql, Object[] parameters) {
        return database.sql()
                .reactive()
                .select(SqlRequests.of(sql, parameters), ResultWrappers.map());
    }

    private static ReceivableReportRow row(
            Map<String, Object> row, Map<String, String> names, String group) {
        String project = text(row, "projectId");
        return new ReceivableReportRow(
                text(row, "id"),
                project,
                names.get(project),
                text(row, "spaceId"),
                text(row, "fullName"),
                text(row, "customerName"),
                text(row, "subjectId"),
                text(row, "subjectName"),
                text(row, "calcStartDate"),
                text(row, "calcEndDate"),
                text(row, "receivableDate"),
                text(row, "dueDate"),
                text(row, "calculatedAsOfDate"),
                text(row, "status"),
                text(row, "remark"),
                ((Number) row.get("bills")).longValue(),
                ReceivableReportAmounts.of(row));
    }

    private static String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), null);
    }

    private record Prepared(String source, Object[] parameters, Map<String, String> names) {}
}
