package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.report.AnalysisInput;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/** 已落账日志才进入统计；审批申请和驳回记录不冒充正式金额变动。 */
@Component
@RequiredArgsConstructor
class ChangeAnalysisSource {
    private final AnalysisSql sql;
    private final ReactiveRepository<ReceivableLogEntity, String> logs;
    private final ReactiveRepository<ReceivableChangeEntity, String> changes;

    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<RefundEntity, String> refunds;
    private final ReactiveRepository<ReceivableEntity, String> bills;

    Mono<AnalysisSql.Source> prepare(AnalysisInput i) {
        return sql.source(
                        logs,
                        i,
                        "id",
                        "projectId",
                        "spaceId",
                        "receivableId",
                        "operation",
                        "reason",
                        "operatorName",
                        "createTime",
                        "beforeJson",
                        "afterJson",
                        "detailJson",
                        "beforeVersion")
                .map(
                        s -> {
                            var approvals =
                                    sql.related(
                                            changes,
                                            i,
                                            "id",
                                            "projectId",
                                            "spaceId",
                                            "receivableId",
                                            "sourceVersion",
                                            "operation",
                                            "applicantName",
                                            "reviewedName",
                                            "reviewedAt",
                                            "status");
                            s =
                                    sql.join(
                                            "select l.*,c.\"applicantName\" as"
                                                + " applicant,c.\"reviewedName\" as"
                                                + " reviewer,to_char(to_timestamp(c.\"reviewedAt\"/1000.0)"
                                                + " at time zone"
                                                + " 'Asia/Shanghai','YYYY-MM-DD\"T\"HH24:MI:SS') as"
                                                + " \"approvedAt\",'EFFECTIVE' as state from ($0) l"
                                                + " left join ($1) c on"
                                                + " c.\"projectId\"=l.\"projectId\" and"
                                                + " c.\"spaceId\"=l.\"spaceId\" and"
                                                + " c.\"receivableId\"=l.\"receivableId\" and"
                                                + " c.\"sourceVersion\"=l.\"beforeVersion\" and"
                                                + " c.\"operation\"=l.\"operation\" and"
                                                + " c.\"status\"='APPROVED'",
                                            s,
                                            approvals);
                            var paid =
                                    sql.related(payments, i, "id", "projectId", "billNo", "remark");
                            var returned =
                                    sql.related(refunds, i, "id", "projectId", "billNo", "reason");
                            var bill =
                                    sql.related(bills, i, "id", "projectId", "spaceId", "billNo");
                            s =
                                    sql.join(
                                            "select l.*,b.\"billNo\" as"
                                                + " original_no,coalesce(p.\"billNo\",r.\"billNo\")"
                                                + " as \"sourceNo\",case when p.\"id\" is not null"
                                                + " then nullif(p.\"remark\",'') else"
                                                + " coalesce(nullif(r.\"reason\",''),l.\"reason\")"
                                                + " end as display_reason from ($0) l left join"
                                                + " ($1) p on p.\"id\"=l.\"reason\" and"
                                                + " p.\"projectId\"=l.\"projectId\" left join ($2)"
                                                + " r on r.\"id\"=l.\"reason\" and"
                                                + " r.\"projectId\"=l.\"projectId\" left join ($3)"
                                                + " b on b.\"id\"=l.\"receivableId\" and"
                                                + " b.\"projectId\"=l.\"projectId\" and"
                                                + " b.\"spaceId\"=l.\"spaceId\"",
                                            s,
                                            paid,
                                            returned,
                                            bill);
                            return s.wrap(
                                    "select j.*, coalesce(a->>'billNo',original_no) as"
                                        + " \"billNo\",a->>'fullName' as"
                                        + " \"fullName\",a->>'customerId' as"
                                        + " \"customerId\",a->>'customerName' as"
                                        + " \"customerName\",a->>'subjectId' as"
                                        + " \"subjectId\",a->>'subjectName' as"
                                        + " \"subjectName\",\"operatorName\" as"
                                        + " actor,to_char(to_timestamp(\"createTime\"/1000.0) at"
                                        + " time zone 'Asia/Shanghai','YYYY-MM-DD\"T\"HH24:MI:SS')"
                                        + " as \"occurredAt\",to_char(to_timestamp(\"createTime\"/1000.0)"
                                        + " at time zone 'Asia/Shanghai','YYYY-MM') as month, "
                                            + num("b", "actualReceivableAmount")
                                            + " as before, "
                                            + num("a", "actualReceivableAmount")
                                            + " as after, "
                                            + delta("actualReceivableAmount")
                                            + " as adjustment, "
                                            + delta("feeReductionAmount")
                                            + " as remission, "
                                            + delta("lateFeeReductionAmount")
                                            + " as \"lateRemission\", "
                                            + delta("paidAmount")
                                            + " as settled from (select l.*,cast(\"beforeJson\" as"
                                            + " jsonb) as b,cast(\"afterJson\" as jsonb) as a from"
                                            + " ($SOURCE) l where \"operation\" in"
                                            + " ('ADJUST','SPLIT','REFUND','COLLECT','PREPAY_COLLECT','OFFSET','RETURN_OFFSET','RETURN_BALANCE','REDUCE','REDUCE_LATE_FEE'))"
                                            + " j");
                        });
    }

    private static String num(String snap, String field) {
        return "coalesce((" + snap + "->>'" + field + "')::numeric,0)";
    }

    private static String delta(String field) {
        return "(" + num("a", field) + "-" + num("b", field) + ")";
    }
}
