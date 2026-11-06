package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.jetlinks.pro.property.finance.report.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import reactor.core.publisher.*;

import java.util.*;

/** 现金实收统一来源集：原收款不减累计退款，退款按自身业务日另列，余额冲抵不重复计现。 */
@Service
@RequiredArgsConstructor
public class ReceivedReportService {
    public static final int EXPORT_LIMIT = 10000;
    private final FinanceScope scope;
    private final HandoverStateQuery handoverStates;
    private final DatabaseOperator database;
    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<PaymentAllocationEntity, String> lines;
    private final ReactiveRepository<RefundEntity, String> refunds;
    private final ReactiveRepository<RefundAllocationEntity, String> refundLines;
    private final ReactiveRepository<FundEntryEntity, String> entries;
    private final ReactiveRepository<FundAccountEntity, String> accounts;
    private static final String CURRENT =
            "case when substring(\"receivedAt\" from 1 for 10) between \"calcStartDate\" and"
                    + " \"calcEndDate\" then amount else 0 end";
    private static final String HANDOVER_SUMS =
            "sum(case when \"handoverState\"='UNHANDED' then amount else 0 end) as unhanded,"
                + " sum(case when \"handoverState\"='PENDING' then amount else 0 end) as pending,"
                + " sum(case when \"handoverState\"='APPROVED' then amount else 0 end) as approved";
    private static final String SUMS =
            "count(*) as rows, sum(case when amount>0 then amount else 0 end) as income, sum(case"
                    + " when amount<0 then -amount else 0 end) as refund, sum(amount) as net, sum("
                    + CURRENT
                    + ") as \"currentAmount\", sum(amount- ("
                    + CURRENT
                    + ")) as \"otherAmount\", "
                    + HANDOVER_SUMS;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<ReceivedReportResult> query(ReceivedReportInput input) {
        return prepare(input)
                .flatMap(p -> load(input, p, false))
                .as(MonoTracer.create("/property/finance/reports/received/query"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<ReceivedReportResult> exportData(ReceivedReportInput input) {
        return prepare(input)
                .flatMap(p -> load(input, p, true))
                .as(MonoTracer.create("/property/finance/reports/received/export"));
    }

    /** 汇总和完整明细共用一个数据库快照；克隆查询不改变页面输入或分组。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<ReportWorkbook> exportWorkbook(
            ReceivedReportInput input, Locale locale, ReceivedReportFile files) {
        var detail =
                com.alibaba.fastjson.JSON.parseObject(
                        com.alibaba.fastjson.JSON.toJSONString(input), ReceivedReportInput.class);
        detail.setGroupBy("DETAIL");
        return ReportWorkbookLoader.load(
                input,
                input.getProjectIds(),
                scope,
                () -> exportData(input).map(result -> files.data(result, locale)),
                () -> exportData(detail).map(result -> files.data(result, locale)),
                locale);
    }

    private Mono<Prepared> prepare(ReceivedReportInput input) {
        return Mono.defer(
                () -> {
                    if (input == null) return Mono.error(error("report_query_invalid", 400));
                    input.validate();
                    return Flux.fromIterable(new LinkedHashSet<>(input.getProjectIds()))
                            .concatMap(id -> scope.project(id, CrudAssetPermission.read))
                            .collectMap(p -> p.id(), p -> p.name(), LinkedHashMap::new)
                            .flatMap(names -> source(input).map(sql -> new Prepared(sql, names)));
                });
    }

    private Mono<Sql> source(ReceivedReportInput i) {
        var p = baseQuery(i);
        p.toQuery().and("status", "SUCCEEDED");
        var l = baseQuery(i);
        l.toQuery().in("method", PaymentRules.MANUAL_METHODS);
        var r = baseQuery(i);
        r.toQuery().and("status", "SUCCEEDED").and("destination", "CASH");
        var rl = baseQuery(i);
        var e = baseQuery(i);
        e.toQuery().not("cashAmount", 0);
        // 逐来源行先注入空间权限；只返回该行金额，不能从主单汇总泄漏同单其他房屋。
        return FinanceSpaceQuery.inject(l, lines, CrudAssetPermission.read)
                .then(FinanceSpaceQuery.inject(rl, refundLines, CrudAssetPermission.read))
                .then(FinanceSpaceQuery.inject(e, entries, CrudAssetPermission.read))
                .then(handoverStates.active(i.getProjectIds()))
                .map(states -> buildSource(i, p, l, r, rl, e, states))
                .flatMap(sql -> collectorNames(sql).map(names -> filterSource(i, sql, names)));
    }

    private Sql buildSource(
            ReceivedReportInput i,
            QueryParamEntity p,
            QueryParamEntity l,
            QueryParamEntity r,
            QueryParamEntity rl,
            QueryParamEntity e,
            HandoverStateQuery.Snapshot states) {
        var params = new ArrayList<Object>();
        String with =
                "with p as ("
                        + nativeSql(
                                payments,
                                p,
                                params,
                                "id",
                                "projectId",
                                "paidAt",
                                "modifierId",
                                "modifierName",
                                "remark")
                        + "), l as ("
                        + nativeSql(
                                lines,
                                l,
                                params,
                                "id",
                                "paymentId",
                                "receivableId",
                                "projectId",
                                "spaceId",
                                "fullName",
                                "customerName",
                                "subjectId",
                                "subjectName",
                                "calcStartDate",
                                "calcEndDate",
                                "method",
                                "methodText",
                                "sourceType",
                                "amount")
                        + "), r as ("
                        + nativeSql(
                                refunds,
                                r,
                                params,
                                "id",
                                "projectId",
                                "refundedAt",
                                "method",
                                "methodText",
                                "modifierId",
                                "modifierName",
                                "reason")
                        + "), rl as ("
                        + nativeSql(
                                refundLines,
                                rl,
                                params,
                                "id",
                                "projectId",
                                "refundId",
                                "sourceLineId",
                                "spaceId",
                                "fullName",
                                "customerName",
                                "subjectName",
                                "amount")
                        + "), e as ("
                        + nativeSql(
                                entries,
                                e,
                                params,
                                "id",
                                "projectId",
                                "accountId",
                                "transactionId",
                                "spaceId",
                                "fullName",
                                "customerName",
                                "subjectName",
                                "occurredAt",
                                "calcStartDate",
                                "calcEndDate",
                                "method",
                                "methodText",
                                "modifierId",
                                "modifierName",
                                "reason",
                                "cashAmount")
                        + "), a as ("
                        + nativeSql(
                                accounts,
                                history(i),
                                params,
                                "id",
                                "projectId",
                                "type",
                                "subjectId")
                        + "), original as ("
                        + nativeSql(
                                lines,
                                history(i),
                                params,
                                "id",
                                "projectId",
                                "spaceId",
                                "receivableId",
                                "sourceType",
                                "subjectId",
                                "calcStartDate",
                                "calcEndDate")
                        + ") ";
        String payment =
                "select l.\"id\", p.\"id\" as \"sourceId\", l.\"projectId\", l.\"spaceId\","
                    + " l.\"fullName\", l.\"customerName\", l.\"subjectId\", l.\"subjectName\","
                    + " p.\"paidAt\" as \"receivedAt\", l.\"calcStartDate\", l.\"calcEndDate\","
                    + " l.\"method\", l.\"methodText\", l.\"sourceType\", p.\"modifierName\" as"
                    + " \"collectorSnapshot\", p.\"remark\", l.\"amount\" as amount,"
                    + " l.\"receivableId\", p.\"modifierId\" as \"collectorId\" from l join p on"
                    + " l.\"paymentId\"=p.\"id\" and l.\"projectId\"=p.\"projectId\"";
        String refund =
                "select rl.\"id\", r.\"id\", rl.\"projectId\", rl.\"spaceId\", rl.\"fullName\","
                    + " rl.\"customerName\", original.\"subjectId\", rl.\"subjectName\","
                    + " r.\"refundedAt\", original.\"calcStartDate\", original.\"calcEndDate\","
                    + " r.\"method\", r.\"methodText\", case when"
                    + " original.\"sourceType\"='TEMPORARY' then 'TEMPORARY_REFUND' else 'REFUND'"
                    + " end, r.\"modifierName\", r.\"reason\", -rl.\"amount\","
                    + " original.\"receivableId\", r.\"modifierId\" from rl join r on"
                    + " rl.\"refundId\"=r.\"id\" and rl.\"projectId\"=r.\"projectId\" join original"
                    + " on rl.\"sourceLineId\"=original.\"id\" and"
                    + " rl.\"projectId\"=original.\"projectId\" and"
                    + " rl.\"spaceId\"=original.\"spaceId\"";
        // 已删账户及原收款行仍保留历史归属；流水名称来自不可变快照，不取账户当前名称。
        String fund =
                "select e.\"id\", e.\"transactionId\", e.\"projectId\", e.\"spaceId\","
                    + " e.\"fullName\", e.\"customerName\", a.\"subjectId\", e.\"subjectName\","
                    + " e.\"occurredAt\", e.\"calcStartDate\", e.\"calcEndDate\", e.\"method\","
                    + " e.\"methodText\", case when e.\"cashAmount\"<0 then a.\"type\" || '_REFUND'"
                    + " else a.\"type\" end, e.\"modifierName\", e.\"reason\", e.\"cashAmount\","
                    + " cast(null as varchar(64)), e.\"modifierId\" from e join a on"
                    + " e.\"accountId\"=a.\"id\" and e.\"projectId\"=a.\"projectId\"";
        String source =
                "(select report_source.*, \"sourceType\" || '-' || \"sourceId\" as \"sourceNo\""
                        + " from ("
                        + payment
                        + " union all "
                        + refund
                        + " union all "
                        + fund
                        + ") report_source) numbered_source";
        // 资金流水的交账身份是entry.id，展示的transactionId不能拿来关联交账。
        source =
                "(select numbered_source.*, coalesce(hs.\"handoverState\", 'UNHANDED') as"
                        + " \"handoverState\" from "
                        + source
                        + " left join handover_states hs on"
                        + " hs.\"projectId\"=numbered_source.\"projectId\" and"
                        + " hs.\"sourceType\"=case when numbered_source.\"sourceType\" in"
                        + " ('RECEIVABLE','TEMPORARY') then 'PAYMENT' when"
                        + " numbered_source.\"sourceType\" in ('REFUND','TEMPORARY_REFUND') then"
                        + " 'REFUND' else 'FUND' end and hs.\"sourceId\"=case when"
                        + " numbered_source.\"sourceType\" in"
                        + " ('RECEIVABLE','TEMPORARY','REFUND','TEMPORARY_REFUND') then"
                        + " numbered_source.\"sourceId\" else numbered_source.\"id\" end)"
                        + " handover_source";
        with = with.stripTrailing() + ", handover_states as (" + states.sql() + ") ";
        params.addAll(Arrays.asList(states.parameters()));
        return new Sql(with, source, params.toArray());
    }

    private Mono<Map<String, String>> collectorNames(Sql sql) {
        // 仅解析已经通过项目、空间权限的流水操作人；限制数量并批量命令查询，避免逐行远程请求。
        return select(
                        sql,
                        "select distinct \"collectorId\" from "
                                + sql.source()
                                + " where \"collectorId\" is not null fetch first 20001 rows only",
                        List.of())
                .map(row -> text(row, "collectorId"))
                .filter(id -> !id.isBlank())
                .collectList()
                .flatMap(
                        ids -> {
                            if (ids.size() > 20000)
                                return Mono.error(error("report_result_limit", 400));
                            return Flux.fromIterable(ids)
                                    .buffer(500)
                                    .concatMap(scope::users)
                                    .collectList()
                                    .map(
                                            users -> {
                                                var names = new LinkedHashMap<String, String>();
                                                for (var user : users) {
                                                    String name = user.getName();
                                                    if (name == null || name.isBlank())
                                                        name = user.getUsername();
                                                    if (name != null && !name.isBlank())
                                                        names.put(user.getId(), name);
                                                }
                                                return names;
                                            });
                        });
    }

    private Sql filterSource(ReceivedReportInput i, Sql sql, Map<String, String> names) {
        var params = new ArrayList<>(Arrays.asList(sql.parameters()));
        String display = "\"collectorSnapshot\"";
        if (!names.isEmpty()) {
            var expression = new StringBuilder("case \"collectorId\"");
            names.forEach(
                    (id, name) -> {
                        expression.append(" when ? then ?");
                        params.add(id);
                        params.add(name);
                    });
            display = expression.append(" else \"collectorSnapshot\" end").toString();
        }
        // 在筛选、摘要及分页之前统一显示姓名；查不到的历史账号保留流水快照，不改写账务数据。
        String source =
                "(select handover_source.*, "
                        + display
                        + " as \"collectorName\" from "
                        + sql.source()
                        + ") named_source";
        var where = new ArrayList<String>();
        in(where, params, "spaceId", i.getSpaceIds());
        in(where, params, "subjectId", i.getSubjectIds());
        ReportGroupKey.filter(where, params, i.getSubjectGroups(), "subjectId", "subjectName");
        ReportGroupKey.filter(where, params, i.getMethodGroups(), "method", "methodText");
        in(where, params, "method", i.getPaymentMethods());
        in(where, params, "sourceType", i.getSourceTypes());
        if (i.getHandoverState() != null)
            in(where, params, "handoverState", List.of(i.getHandoverState()));
        like(where, params, "customerName", i.getCustomerName());
        like(where, params, "fullName", i.getFullName());
        like(where, params, "sourceNo", i.getPaymentNo());
        like(where, params, "collectorName", i.getCollectorName());
        if (i.getCollectorId() != null)
            in(where, params, "collectorId", List.of(i.getCollectorId()));
        if (i.getReceivedDateStart() != null) {
            where.add("substring(\"receivedAt\" from 1 for 10)>=?");
            params.add(i.getReceivedDateStart());
        }
        if (i.getReceivedDateEnd() != null) {
            where.add("substring(\"receivedAt\" from 1 for 10)<=?");
            params.add(i.getReceivedDateEnd());
        }
        if (!where.isEmpty())
            source =
                    "(select * from "
                            + source
                            + " where "
                            + String.join(" and ", where)
                            + ") filtered_source";
        return new Sql(sql.with(), source, params.toArray());
    }

    private static QueryParamEntity history(ReceivedReportInput i) {
        var q = new QueryParamEntity();
        q.setPaging(false);
        q.toQuery().in("projectId", i.getProjectIds());
        return q;
    }

    private static QueryParamEntity baseQuery(ReceivedReportInput i) {
        var q = history(i);
        q.toQuery().and("deleted", false);
        return q;
    }

    private static String nativeSql(
            ReactiveRepository<?, ?> repository,
            QueryParamEntity q,
            List<Object> params,
            String... cols) {
        var sql = repository.nativeQuery().select(cols).setParam(q).getSql();
        params.addAll(Arrays.asList(sql.getParameters()));
        return sql.getSql();
    }

    private static void in(
            List<String> where, List<Object> params, String column, List<String> values) {
        if (!values.isEmpty()) {
            where.add(
                    "\""
                            + column
                            + "\" in ("
                            + String.join(",", Collections.nCopies(values.size(), "?"))
                            + ")");
            params.addAll(values);
        }
    }

    private static void like(List<String> where, List<Object> params, String column, String value) {
        if (value != null && !value.isBlank()) {
            where.add("lower(\"" + column + "\") like ?");
            params.add("%" + value.trim().toLowerCase(Locale.ROOT) + "%");
        }
    }

    private Mono<ReceivedReportResult> load(ReceivedReportInput i, Prepared p, boolean export) {
        String group = group(i.getGroupBy());
        String source = p.sql().source();
        String count =
                group.isEmpty()
                        ? "select count(*) as total from " + source
                        : "select count(*) as total from (select "
                                + group
                                + " from "
                                + source
                                + " group by "
                                + group
                                + ") report_groups";
        // 摘要、总数及数据串行共享事务快照，禁止同一R2DBC连接并行执行查询。
        return select(p.sql(), "select " + SUMS + " from " + source, List.of())
                .single()
                .flatMap(
                        summary ->
                                select(p.sql(), count, List.of())
                                        .single()
                                        .flatMap(
                                                totalRow -> {
                                                    long total =
                                                            ((Number) totalRow.get("total"))
                                                                    .longValue();
                                                    if (total > Integer.MAX_VALUE
                                                            || export && total > EXPORT_LIMIT)
                                                        return Mono.error(
                                                                error(
                                                                        export
                                                                                ? "report_export_limit"
                                                                                : "report_result_limit",
                                                                        400));
                                                    int index = export ? 0 : i.getPageIndex(),
                                                            size =
                                                                    export
                                                                            ? EXPORT_LIMIT
                                                                            : i.getPageSize();
                                                    String sql =
                                                            group.isEmpty()
                                                                    ? "select *, 1 as rows, case"
                                                                          + " when amount>0 then"
                                                                          + " amount else 0 end as"
                                                                          + " income, case when"
                                                                          + " amount<0 then -amount"
                                                                          + " else 0 end as refund,"
                                                                          + " amount as net, "
                                                                            + CURRENT
                                                                            + " as \"currentAmount\","
                                                                            + " amount-("
                                                                            + CURRENT
                                                                            + ") as \"otherAmount\","
                                                                            + " case when"
                                                                            + " \"handoverState\"='UNHANDED'"
                                                                            + " then amount else 0"
                                                                            + " end as unhanded,"
                                                                            + " case when"
                                                                            + " \"handoverState\"='PENDING'"
                                                                            + " then amount else 0"
                                                                            + " end as pending,"
                                                                            + " case when"
                                                                            + " \"handoverState\"='APPROVED'"
                                                                            + " then amount else 0"
                                                                            + " end as approved"
                                                                            + " from "
                                                                            + source
                                                                            + " order by"
                                                                            + " \"receivedAt\""
                                                                            + " desc,"
                                                                            + " \"sourceType\","
                                                                            + " \"id\""
                                                                    : "select "
                                                                            + group
                                                                            + ", "
                                                                            + SUMS
                                                                            + " from "
                                                                            + source
                                                                            + " group by "
                                                                            + group
                                                                            + " order by "
                                                                            + group;
                                                    sql += " offset ? rows fetch next ? rows only";
                                                    return select(
                                                                    p.sql(),
                                                                    sql,
                                                                    List.of(
                                                                            (long) index * size,
                                                                            size))
                                                            .map(
                                                                    row ->
                                                                            row(
                                                                                    row,
                                                                                    p.names(),
                                                                                    i
                                                                                            .periodEnabled()))
                                                            .collectList()
                                                            .map(
                                                                    rows -> {
                                                                        var page =
                                                                                new QueryParamEntity();
                                                                        page.setPageIndex(index);
                                                                        page.setPageSize(size);
                                                                        return new ReceivedReportResult(
                                                                                i.getGroupBy(),
                                                                                i.periodEnabled(),
                                                                                ReceivedReportAmounts
                                                                                        .of(
                                                                                                summary,
                                                                                                i
                                                                                                        .periodEnabled()),
                                                                                PagerResult.of(
                                                                                        (int) total,
                                                                                        rows,
                                                                                        page));
                                                                    });
                                                }));
    }

    private Flux<Map<String, Object>> select(Sql source, String sql, List<Object> extra) {
        var params = new ArrayList<>(Arrays.asList(source.parameters()));
        params.addAll(extra);
        return database.sql()
                .reactive()
                .select(
                        SqlRequests.of(source.with() + sql, params.toArray()),
                        ResultWrappers.map());
    }

    private static String group(String value) {
        return switch (value) {
            case "PROJECT" -> "\"projectId\"";
            case "SUBJECT" -> "\"subjectId\", \"subjectName\"";
            case "METHOD" -> "\"method\", \"methodText\"";
            case "SOURCE" -> "\"sourceType\"";
            case "COLLECTOR" -> "\"collectorId\", \"collectorName\"";
            default -> "";
        };
    }

    private static ReceivedReportRow row(
            Map<String, Object> r, Map<String, String> names, boolean period) {
        String project = text(r, "projectId");
        return new ReceivedReportRow(
                text(r, "id"),
                text(r, "sourceId"),
                text(r, "sourceNo"),
                text(r, "receivableId"),
                project,
                names.get(project),
                text(r, "spaceId"),
                text(r, "fullName"),
                text(r, "customerName"),
                text(r, "subjectId"),
                text(r, "subjectName"),
                text(r, "receivedAt"),
                text(r, "calcStartDate"),
                text(r, "calcEndDate"),
                text(r, "method"),
                text(r, "methodText"),
                text(r, "sourceType"),
                text(r, "collectorName"),
                text(r, "remark"),
                text(r, "handoverState"),
                ((Number) r.get("rows")).longValue(),
                ReceivedReportAmounts.of(r, period),
                text(r, "collectorId"));
    }

    private static String text(Map<String, Object> r, String key) {
        return Objects.toString(r.get(key), null);
    }

    private record Sql(String with, String source, Object[] parameters) {}

    private record Prepared(Sql sql, Map<String, String> names) {}
}
