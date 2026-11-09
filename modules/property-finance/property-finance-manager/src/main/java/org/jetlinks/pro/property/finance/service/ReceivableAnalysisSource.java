package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.report.AnalysisInput;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;

/** 历史取当时已生效的快照，校验账务连续性；关期、发票占用等非金额版本不影响还原。 */
@Component
@RequiredArgsConstructor
class ReceivableAnalysisSource {
    private final AnalysisSql sql;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<ReceivableLogEntity, String> logs;
    private static final String[] COLS = {
        "id",
        "billNo",
        "projectId",
        "spaceId",
        "fullName",
        "customerId",
        "customerName",
        "subjectId",
        "subjectName",
        "receivableDate",
        "dueDate",
        "calcStartDate",
        "calcEndDate",
        "actualReceivableAmount",
        "paidAmount",
        "feeReductionAmount",
        "accruedLateFeeAmount",
        "lateFeeReductionAmount",
        "deleted",
        "split",
        "reviewState",
        "version"
    };

    Mono<AnalysisSql.Source> prepare(AnalysisInput i, boolean monthly) {
        if (!monthly && i.getEndDate().equals(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()))
            return sql.source(bills, i, COLS)
                    .map(
                            s ->
                                    s.wrap(
                                            "select"
                                                + " r.*,greatest(\"actualReceivableAmount\"-\"paidAmount\",0)"
                                                + " as debt,\"actualReceivableAmount\" as"
                                                + " net,\"paidAmount\" as paid from ($SOURCE) r"
                                                + " where \"deleted\"=false and"
                                                + " coalesce(\"split\",false)=false and"
                                                + " \"reviewState\"='APPROVED'"));
        return sql.source(bills, i, COLS)
                .flatMap(
                        b ->
                                sql.source(
                                                logs,
                                                i,
                                                "id",
                                                "projectId",
                                                "spaceId",
                                                "receivableId",
                                                "beforeJson",
                                                "afterJson",
                                                "beforeVersion",
                                                "afterVersion",
                                                "createTime",
                                                "operation")
                                        .map(
                                                l ->
                                                        l.wrap(
                                                                "select * from ($SOURCE) journal"
                                                                    + " where \"operation\" not in"
                                                                    + " ('CHANGE_REQUEST','CHANGE_APPROVE','CHANGE_REJECT','IMPORT_STAGE')"))
                                        .flatMap(
                                                l -> {
                                                    String chain =
                                                            "with b as ($0), l as ($1), ordered as"
                                                                + " (select l.*,"
                                                                + " lag(cast(\"afterJson\" as"
                                                                + " jsonb)) over(partition by"
                                                                + " \"projectId\",\"receivableId\""
                                                                + " order by"
                                                                + " \"afterVersion\",\"createTime\")"
                                                                + " as previous from l) select b.*,"
                                                                + " (select cast(x.\"afterJson\" as"
                                                                + " jsonb) from l x where"
                                                                + " x.\"projectId\"=b.\"projectId\""
                                                                + " and x.\"receivableId\"=b.\"id\""
                                                                + " order by x.\"afterVersion\""
                                                                + " desc,x.\"createTime\" desc"
                                                                + " limit 1) as latest, (select"
                                                                + " cast(x.\"beforeJson\" as jsonb)"
                                                                + " from l x where"
                                                                + " x.\"projectId\"=b.\"projectId\""
                                                                + " and x.\"receivableId\"=b.\"id\""
                                                                + " order by"
                                                                + " x.\"afterVersion\",x.\"createTime\""
                                                                + " limit 1) as initial, (select"
                                                                + " count(*) from ordered x where"
                                                                + " x.\"projectId\"=b.\"projectId\""
                                                                + " and x.\"receivableId\"=b.\"id\""
                                                                + " and previous is not null and "
                                                                    + financial(
                                                                            "cast(x.\"beforeJson\""
                                                                                + " as jsonb)")
                                                                    + "<>"
                                                                    + financial("previous")
                                                                    + ") as broken from b";
                                                    var history = sql.join(chain, b, l);
                                                    return sql.select(
                                                                    history,
                                                                    "select count(*) as missing"
                                                                        + " from ($SOURCE) h where"
                                                                        + " latest is null or"
                                                                        + " initial<>'{}'::jsonb or"
                                                                        + " broken>0 or "
                                                                            + financial("latest")
                                                                            + "<>"
                                                                            + financial(
                                                                                    "to_jsonb(h)"))
                                                            .single()
                                                            .flatMap(
                                                                    check -> {
                                                                        if (((Number)
                                                                                                check
                                                                                                        .get(
                                                                                                                "missing"))
                                                                                        .longValue()
                                                                                > 0)
                                                                            return Mono.error(
                                                                                    FinanceScope
                                                                                            .error(
                                                                                                    "report_history_missing",
                                                                                                    409));
                                                                        var values =
                                                                                new ArrayList<
                                                                                        Object>();
                                                                        values.addAll(
                                                                                b.parameters());
                                                                        values.addAll(
                                                                                l.parameters());
                                                                        long end =
                                                                                LocalDate.parse(
                                                                                                i
                                                                                                        .getEndDate())
                                                                                        .plusDays(1)
                                                                                        .atStartOfDay(
                                                                                                ZoneId
                                                                                                        .of(
                                                                                                                "Asia/Shanghai"))
                                                                                        .toInstant()
                                                                                        .toEpochMilli();
                                                                        long start =
                                                                                monthly
                                                                                        ? LocalDate
                                                                                                .parse(
                                                                                                        i
                                                                                                                .getStartDate())
                                                                                                .atStartOfDay(
                                                                                                        ZoneId
                                                                                                                .of(
                                                                                                                        "Asia/Shanghai"))
                                                                                                .toInstant()
                                                                                                .toEpochMilli()
                                                                                        : end;
                                                                        values.add(start);
                                                                        values.add(end);
                                                                        String query =
                                                                                "with b as ("
                                                                                        + b.sql()
                                                                                        + "), l as"
                                                                                        + " ("
                                                                                        + l.sql()
                                                                                        + "), dates"
                                                                                        + " as(select"
                                                                                        + " cast(?"
                                                                                        + " as bigint)"
                                                                                        + " as start,"
                                                                                        + " cast(?"
                                                                                        + " as bigint)"
                                                                                        + " as finish),"
                                                                                        + " snapshots"
                                                                                        + " as(select"
                                                                                        + " b.*,coalesce((select"
                                                                                        + " cast(x.\"afterJson\""
                                                                                        + " as jsonb)"
                                                                                        + " from l"
                                                                                        + " x where"
                                                                                        + " x.\"receivableId\"=b.\"id\""
                                                                                        + " and x.\"projectId\"=b.\"projectId\""
                                                                                        + " and x.\"createTime\"<start"
                                                                                        + " order"
                                                                                        + " by x.\"afterVersion\""
                                                                                        + " desc"
                                                                                        + " limit"
                                                                                        + " 1),'{}'::jsonb)"
                                                                                        + " as o,coalesce((select"
                                                                                        + " cast(x.\"afterJson\""
                                                                                        + " as jsonb)"
                                                                                        + " from l"
                                                                                        + " x where"
                                                                                        + " x.\"receivableId\"=b.\"id\""
                                                                                        + " and x.\"projectId\"=b.\"projectId\""
                                                                                        + " and x.\"createTime\"<finish"
                                                                                        + " order"
                                                                                        + " by x.\"afterVersion\""
                                                                                        + " desc"
                                                                                        + " limit"
                                                                                        + " 1),'{}'::jsonb)"
                                                                                        + " as c"
                                                                                        + " from b"
                                                                                        + " cross"
                                                                                        + " join"
                                                                                        + " dates)"
                                                                                        + " select"
                                                                                        + " s.\"id\",s.\"projectId\",s.\"spaceId\","
                                                                                        + metadata()
                                                                                        + ", "
                                                                                        + amount(
                                                                                                "c",
                                                                                                "actualReceivableAmount")
                                                                                        + " as net,"
                                                                                        + amount(
                                                                                                "c",
                                                                                                "paidAmount")
                                                                                        + " as paid,"
                                                                                        + " greatest("
                                                                                        + amount(
                                                                                                "c",
                                                                                                "actualReceivableAmount")
                                                                                        + "-"
                                                                                        + amount(
                                                                                                "c",
                                                                                                "paidAmount")
                                                                                        + ",0) as"
                                                                                        + " debt,"
                                                                                        + " greatest("
                                                                                        + amount(
                                                                                                "o",
                                                                                                "actualReceivableAmount")
                                                                                        + "-"
                                                                                        + amount(
                                                                                                "o",
                                                                                                "paidAmount")
                                                                                        + ",0) as"
                                                                                        + " opening,"
                                                                                        + " ("
                                                                                        + amount(
                                                                                                "c",
                                                                                                "actualReceivableAmount")
                                                                                        + "-"
                                                                                        + amount(
                                                                                                "o",
                                                                                                "actualReceivableAmount")
                                                                                        + ") as accrual,"
                                                                                        + " ("
                                                                                        + amount(
                                                                                                "c",
                                                                                                "paidAmount")
                                                                                        + "-"
                                                                                        + amount(
                                                                                                "o",
                                                                                                "paidAmount")
                                                                                        + ") as settled"
                                                                                        + " from"
                                                                                        + " snapshots"
                                                                                        + " s where"
                                                                                        + " c<>'{}'::jsonb"
                                                                                        + " or o<>'{}'::jsonb";
                                                                        return Mono.just(
                                                                                new AnalysisSql
                                                                                        .Source(
                                                                                        query,
                                                                                        values));
                                                                    });
                                                }));
    }

    private static String financial(String snapshot) {
        return "jsonb_build_array(coalesce(("
                + snapshot
                + "->>'actualReceivableAmount')::numeric,0),coalesce(("
                + snapshot
                + "->>'paidAmount')::numeric,0),"
                + snapshot
                + "->>'reviewState',coalesce(("
                + snapshot
                + "->>'deleted')::boolean,false),coalesce(("
                + snapshot
                + "->>'split')::boolean,false))";
    }

    private static String metadata() {
        return String.join(
                ",",
                List.of(
                                "billNo",
                                "fullName",
                                "customerId",
                                "customerName",
                                "subjectId",
                                "subjectName",
                                "receivableDate",
                                "dueDate",
                                "calcStartDate",
                                "calcEndDate")
                        .stream()
                        .map(k -> "coalesce(c->>'" + k + "',o->>'" + k + "') as \"" + k + "\"")
                        .toList());
    }

    private static String amount(String snapshot, String key) {
        return "case when "
                + snapshot
                + "->>'reviewState'='APPROVED' and coalesce(("
                + snapshot
                + "->>'deleted')::boolean,false)=false and coalesce(("
                + snapshot
                + "->>'split')::boolean,false)=false then coalesce(("
                + snapshot
                + "->>'"
                + key
                + "')::numeric,0) else 0 end";
    }
}
