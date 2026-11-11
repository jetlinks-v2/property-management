package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.report.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.util.*;

/** 共享分析管线只负责授权后的筛选、聚合和分页；每类账务来源保留自己的金额规则。 */
@Service
@RequiredArgsConstructor
public class FinanceAnalysisService {
    private final FinanceScope scope;
    private final AnalysisSql sql;
    private final ReceivableAnalysisSource receivables;
    private final MonthlySettlementSource monthly;
    private final FundAnalysisSource funds;
    private final ActivityAnalysisSource activities;
    private final ChangeAnalysisSource changes;

    record Definition(List<String> metrics, Set<String> groups, List<String> detail) {}

    private static final Map<String, Definition> DEFINITIONS =
            Map.of(
                    "arrears",
                            new Definition(
                                    List.of(
                                            "debt", "overdue", "notDue", "days30", "days90",
                                            "days180", "days365", "over365", "undated"),
                                    Set.of(
                                            "PROJECT",
                                            "SUBJECT",
                                            "SPACE",
                                            "CUSTOMER",
                                            "AGE",
                                            "DETAIL"),
                                    List.of(
                                            "billNo",
                                            "fullName",
                                            "customerName",
                                            "subjectName",
                                            "receivableDate",
                                            "dueDate",
                                            "calcStartDate",
                                            "calcEndDate",
                                            "overdueDays",
                                            "age")),
                    "monthly",
                            new Definition(
                                    List.of(
                                            "opening",
                                            "accrual",
                                            "settled",
                                            "priorYears",
                                            "priorMonths",
                                            "currentMonth",
                                            "future",
                                            "cash",
                                            "offset",
                                            "restored",
                                            "debt"),
                                    Set.of("PROJECT", "SUBJECT", "SPACE", "CUSTOMER", "DETAIL"),
                                    List.of(
                                            "billNo",
                                            "fullName",
                                            "customerName",
                                            "subjectName",
                                            "receivableDate",
                                            "calcStartDate",
                                            "calcEndDate")),
                    "funds",
                            new Definition(
                                    List.of(
                                            "opening",
                                            "income",
                                            "refund",
                                            "transferIn",
                                            "transferOut",
                                            "deduct",
                                            "offset",
                                            "offsetReturn",
                                            "other",
                                            "movement",
                                            "closing"),
                                    Set.of(
                                            "PROJECT",
                                            "SUBJECT",
                                            "SPACE",
                                            "CUSTOMER",
                                            "TYPE",
                                            "DETAIL"),
                                    List.of(
                                            "billNo",
                                            "type",
                                            "fullName",
                                            "customerName",
                                            "subjectName",
                                            "occurredAt",
                                            "operation",
                                            "beforeBalance",
                                            "amount",
                                            "afterBalance",
                                            "reason",
                                            "actor")),
                    "activity",
                            new Definition(
                                    List.of("principal", "discount", "periods", "giftMonths"),
                                    Set.of(
                                            "PROJECT",
                                            "SUBJECT",
                                            "SPACE",
                                            "CUSTOMER",
                                            "MONTH",
                                            "ACTIVITY",
                                            "DETAIL",
                                            "PERIOD"),
                                    List.of(
                                            "billNo",
                                            "activityName",
                                            "fullName",
                                            "customerName",
                                            "subjectName",
                                            "occurredAt",
                                            "calcStartDate",
                                            "calcEndDate")),
                    "changes",
                            new Definition(
                                    List.of(
                                            "before",
                                            "adjustment",
                                            "after",
                                            "remission",
                                            "lateRemission",
                                            "settled"),
                                    Set.of(
                                            "PROJECT",
                                            "SUBJECT",
                                            "SPACE",
                                            "CUSTOMER",
                                            "MONTH",
                                            "OPERATION",
                                            "DETAIL"),
                                    List.of(
                                            "billNo",
                                            "fullName",
                                            "customerName",
                                            "subjectName",
                                            "occurredAt",
                                            "operation",
                                            "sourceNo",
                                            "reason",
                                            "actor",
                                            "applicant",
                                            "reviewer",
                                            "approvedAt",
                                            "state")));

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<AnalysisResult> query(String kind, AnalysisInput input) {
        return Mono.defer(
                () -> {
                    input.validate(kind);
                    var def = DEFINITIONS.get(kind);
                    if (!def.groups().contains(input.getGroupBy()))
                        return Mono.error(error("report_query_invalid", 400));
                    return Flux.fromIterable(input.getProjectIds())
                            .concatMap(p -> scope.project(p, CrudAssetPermission.read))
                            .collectMap(p -> p.id(), p -> p.name())
                            .flatMap(
                                    names ->
                                            source(kind, input)
                                                    .map(s -> filter(s, input, kind))
                                                    .flatMap(
                                                            s -> load(kind, input, def, s, names)));
                });
    }

    private Mono<AnalysisSql.Source> source(String kind, AnalysisInput i) {
        return switch (kind) {
            case "funds" -> funds.prepare(i);
            case "activity" -> activities.prepare(i);
            case "changes" -> changes.prepare(i);
            case "monthly" -> receivables.prepare(i, true).flatMap(s -> monthly.enrich(i, s));
            default ->
                    receivables
                            .prepare(i, kind.equals("monthly"))
                            .map(
                                    s -> {
                                        if (kind.equals("monthly")) return s;
                                        // 日期由严格解析后的ISO自然日生成，业务筛选仍使用参数绑定。
                                        String date = "date '" + i.getEndDate() + "'";
                                        String days = "(" + date + "-cast(\"dueDate\" as date))";
                                        String age =
                                                "case when \"dueDate\" is null then 'UNDATED' when "
                                                        + days
                                                        + "<=0 then 'NOT_DUE' when "
                                                        + days
                                                        + "<=30 then 'DAYS_30' when "
                                                        + days
                                                        + "<=90 then 'DAYS_90' when "
                                                        + days
                                                        + "<=180 then 'DAYS_180' when "
                                                        + days
                                                        + "<=365 then 'DAYS_365' else 'OVER_365'"
                                                        + " end";
                                        s =
                                                s.wrap(
                                                        "select r.*, "
                                                                + age
                                                                + " as age from ($SOURCE) r where"
                                                                + " debt>0");
                                        return s.wrap(
                                                "select a.*, case when age not in"
                                                    + " ('NOT_DUE','UNDATED') then debt else 0 end"
                                                    + " as overdue,"
                                                        + bucket("NOT_DUE", "notDue")
                                                        + ","
                                                        + bucket("DAYS_30", "days30")
                                                        + ","
                                                        + bucket("DAYS_90", "days90")
                                                        + ","
                                                        + bucket("DAYS_180", "days180")
                                                        + ","
                                                        + bucket("DAYS_365", "days365")
                                                        + ","
                                                        + bucket("OVER_365", "over365")
                                                        + ","
                                                        + bucket("UNDATED", "undated")
                                                        + ",greatest("
                                                        + days
                                                        + ",0) as \"overdueDays\" from ($SOURCE)"
                                                        + " a");
                                    });
        };
    }

    private static String bucket(String age, String key) {
        return "case when age='" + age + "' then debt else 0 end as \"" + key + "\"";
    }

    private AnalysisSql.Source filter(AnalysisSql.Source s, AnalysisInput i, String kind) {
        var params = new ArrayList<Object>(s.parameters());
        var where = new ArrayList<String>();
        like(where, params, "customerName", i.getCustomerName());
        like(where, params, "fullName", i.getFullName());
        like(where, params, "subjectName", i.getSubjectName());
        like(where, params, "billNo", i.getBillNo());
        in(where, params, "customerId", i.getCustomerIds());
        in(where, params, "subjectId", i.getSubjectIds());
        if (kind.equals("activity")) {
            like(where, params, "activityName", i.getActivityName());
            if (i.getActivityId() != null) {
                where.add("\"activityId\"=?");
                params.add(i.getActivityId());
            }
        }
        if (kind.equals("funds") && i.getType() != null) {
            where.add("\"type\"=?");
            params.add(i.getType());
        }
        if ((kind.equals("funds") || kind.equals("changes")) && i.getOperation() != null) {
            where.add("operation=?");
            params.add(i.getOperation());
        }
        if (kind.equals("arrears") && i.getAgeBucket() != null) {
            where.add("age=?");
            params.add(i.getAgeBucket());
        }
        if (kind.equals("changes") || kind.equals("activity")) {
            where.add("substring(\"occurredAt\" from 1 for 10) between ? and ?");
            params.add(i.getStartDate());
            params.add(i.getEndDate());
        }
        return new AnalysisSql.Source(
                "select * from ("
                        + s.sql()
                        + ") scoped"
                        + (where.isEmpty() ? "" : " where " + String.join(" and ", where)),
                params);
    }

    private Mono<AnalysisResult> load(
            String kind,
            AnalysisInput i,
            Definition d,
            AnalysisSql.Source source,
            Map<String, String> names) {
        String sums =
                String.join(
                        ",",
                        d.metrics().stream()
                                .map(k -> "coalesce(sum(\"" + k + "\"),0) as \"" + k + "\"")
                                .toList());
        String counts =
                "count(distinct (\"projectId\",\"spaceId\")) as \"spaceCount\", count(*) as"
                        + " \"rowCount\"";
        if (kind.equals("arrears"))
            counts +=
                    ",count(distinct case when overdue>0 then (\"projectId\",\"spaceId\") end) as"
                            + " \"overdueSpaceCount\",min(\"receivableDate\") as"
                            + " \"earliestDate\",max(\"overdueDays\") as \"overdueDays\"";
        var group = group(i.getGroupBy());
        String table = "(" + source.sql() + ") data";
        String body =
                Set.of("DETAIL", "PERIOD").contains(i.getGroupBy())
                        ? "select * from " + table + detailWhere(kind, i)
                        : "select "
                                + group
                                + ", "
                                + sums
                                + ", "
                                + counts
                                + " from "
                                + table
                                + " group by "
                                + group;
        var summarySource = source;
        return sql.select(summarySource, "select " + sums + ", " + counts + " from ($SOURCE) data")
                .single()
                .flatMap(
                        summary ->
                                sql.select(
                                                new AnalysisSql.Source(body, source.parameters()),
                                                "select count(*) as total from ($SOURCE) groups")
                                        .single()
                                        .flatMap(
                                                total -> {
                                                    long n =
                                                            ((Number) total.get("total"))
                                                                    .longValue();
                                                    if (n > Integer.MAX_VALUE)
                                                        return Mono.error(
                                                                error("report_result_limit", 400));
                                                    return sql.select(
                                                                    new AnalysisSql.Source(
                                                                            body,
                                                                            source.parameters()),
                                                                    "select * from ($SOURCE) page"
                                                                            + " order by "
                                                                            + order(i.getGroupBy())
                                                                            + " offset ? rows fetch"
                                                                            + " next ? rows only",
                                                                    (long) i.getPageIndex()
                                                                            * i.getPageSize(),
                                                                    i.getPageSize())
                                                            .map(
                                                                    row ->
                                                                            display(
                                                                                    row,
                                                                                    d.metrics(),
                                                                                    names))
                                                            .collectList()
                                                            .map(
                                                                    rows -> {
                                                                        var q =
                                                                                new QueryParamEntity();
                                                                        q.setPageIndex(
                                                                                i.getPageIndex());
                                                                        q.setPageSize(
                                                                                i.getPageSize());
                                                                        return new AnalysisResult(
                                                                                kind,
                                                                                i.getGroupBy(),
                                                                                columns(
                                                                                        kind,
                                                                                        i
                                                                                                .getGroupBy()),
                                                                                display(
                                                                                        summary,
                                                                                        d.metrics(),
                                                                                        names),
                                                                                PagerResult.of(
                                                                                        (int) n,
                                                                                        rows, q));
                                                                    });
                                                }));
    }

    private static String detailWhere(String kind, AnalysisInput i) {
        return kind.equals("funds")
                ? " where substring(\"occurredAt\" from 1 for 10)>='" + i.getStartDate() + "'"
                : "";
    }

    private static String group(String group) {
        return switch (group) {
            case "PROJECT" -> "\"projectId\"";
            case "SUBJECT" -> "\"projectId\",\"subjectId\",\"subjectName\"";
            case "SPACE" -> "\"projectId\",\"spaceId\",\"fullName\"";
            case "CUSTOMER" -> "\"projectId\",\"customerId\",\"customerName\"";
            case "MONTH" -> "month";
            case "TYPE" -> "\"type\"";
            case "ACTIVITY" -> "\"projectId\",\"activityId\",\"activityName\"";
            case "AGE" -> "age";
            case "OPERATION" -> "operation";
            default -> "";
        };
    }

    private static String order(String group) {
        return Set.of("DETAIL", "PERIOD").contains(group) ? "\"projectId\",\"id\"" : group(group);
    }

    static List<AnalysisResult.Column> columns(String kind, String group) {
        var d = DEFINITIONS.get(kind);
        var keys = new ArrayList<String>();
        if (Set.of("DETAIL", "PERIOD").contains(group)) {
            keys.add("projectName");
            keys.addAll(d.detail());
        } else {
            keys.addAll(
                    switch (group) {
                        case "PROJECT" -> List.of("projectName");
                        case "SUBJECT" -> List.of("projectName", "subjectName");
                        case "SPACE" -> List.of("projectName", "fullName");
                        case "CUSTOMER" -> List.of("projectName", "customerName");
                        case "ACTIVITY" -> List.of("projectName", "activityName");
                        case "TYPE" -> List.of("type");
                        case "AGE" -> List.of("age");
                        case "OPERATION" -> List.of("operation");
                        default -> List.of("month");
                    });
            keys.add("spaceCount");
            keys.add("rowCount");
            if (kind.equals("arrears"))
                keys.addAll(List.of("overdueSpaceCount", "earliestDate", "overdueDays"));
        }
        if (!kind.equals("funds") || !Set.of("DETAIL", "PERIOD").contains(group))
            keys.addAll(d.metrics());
        return keys.stream()
                .distinct()
                .map(
                        k ->
                                new AnalysisResult.Column(
                                        k,
                                        kind.equals("monthly")&&k.equals("opening")?"期初欠费（元）":AnalysisLabels.label(k),
                                        d.metrics().contains(k)
                                                        && !Set.of("periods", "giftMonths")
                                                                .contains(k)
                                                || Set.of("beforeBalance", "afterBalance", "amount")
                                                        .contains(k)))
                .toList();
    }

    private static Map<String, Object> display(
            Map<String, Object> row, List<String> metrics, Map<String, String> names) {
        var out = new LinkedHashMap<>(row);
        if (out.containsKey("display_reason")) {
            Object reason = out.remove("display_reason");
            out.put("reason", reason);
        }
        Object actor = out.get("actor");
        if (actor != null && actor.toString().matches("(?:pm_[a-zA-Z0-9_]+|[a-fA-F0-9-]{32,36})"))
            out.put("actor", "未记录姓名");
        out.put("projectName", names.get(Objects.toString(row.get("projectId"), "")));
        var moneyKeys = new java.util.HashSet<>(metrics);
        moneyKeys.addAll(List.of("beforeBalance", "afterBalance", "amount"));
        for (var key : moneyKeys)
            out.computeIfPresent(
                    key,
                    (k, v) ->
                            key.equals("periods") || key.equals("giftMonths")
                                    ? new BigDecimal(v.toString()).toBigInteger().toString()
                                    : new BigDecimal(v.toString()).setScale(2).toPlainString());
        // 不返回内部快照、原始JSON和租户控制字段。
        out.keySet()
                .removeIf(
                        k ->
                                Set.of(
                                                "snap",
                                                "a",
                                                "b",
                                                "o",
                                                "c",
                                                "initial",
                                                "latest",
                                                "beforeJson",
                                                "afterJson",
                                                "detailJson",
                                                "snapshotJson")
                                        .contains(k));
        return out;
    }

    private static void like(List<String> w, List<Object> p, String k, String v) {
        if (v != null && !v.isBlank()) {
            w.add("lower(\"" + k + "\") like ?");
            p.add("%" + v.trim().toLowerCase(Locale.ROOT) + "%");
        }
    }

    private static void in(List<String> w, List<Object> p, String k, List<String> v) {
        if (!v.isEmpty()) {
            w.add(
                    "\""
                            + k
                            + "\" in ("
                            + String.join(",", Collections.nCopies(v.size(), "?"))
                            + ")");
            p.addAll(v);
        }
    }
}
