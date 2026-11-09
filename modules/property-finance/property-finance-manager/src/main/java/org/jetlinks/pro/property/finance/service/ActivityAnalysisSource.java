package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.report.AnalysisInput;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/** 只统计已经确认收款的活动报价；逐期优惠来自落账快照，不把赠送金额计入现金。 */
@Component
@RequiredArgsConstructor
class ActivityAnalysisSource {
    private final AnalysisSql sql;
    private final ReactiveRepository<PrepayQuoteEntity, String> quotes;
    private final ReactiveRepository<PrepayConsumptionEntity, String> consumptions;
    private final ReactiveRepository<PaymentEntity, String> payments;

    Mono<AnalysisSql.Source> prepare(AnalysisInput i) {
        return sql.source(
                        consumptions,
                        i,
                        "id",
                        "projectId",
                        "spaceId",
                        "quoteId",
                        "paymentId",
                        "activityId")
                .map(
                        c -> {
                            var q =
                                    sql.related(
                                            quotes,
                                            i,
                                            "id",
                                            "projectId",
                                            "spaceId",
                                            "snapshotJson");
                            var p =
                                    sql.related(
                                            payments,
                                            i,
                                            "id",
                                            "projectId",
                                            "billNo",
                                            "paidAt",
                                            "status",
                                            "modifierName",
                                            "remark");
                            var base =
                                    sql.join(
                                            "with c as ($0), q as ($1), p as ($2), facts as (select"
                                                + " c.*, p.\"billNo\",p.\"paidAt\" as"
                                                + " \"occurredAt\",p.\"modifierName\" as"
                                                + " actor,p.\"remark\" as"
                                                + " reason,cast(q.\"snapshotJson\" as jsonb) as"
                                                + " snap from c join q on c.\"quoteId\"=q.\"id\""
                                                + " and c.\"projectId\"=q.\"projectId\" and"
                                                + " c.\"spaceId\"=q.\"spaceId\" join p on"
                                                + " c.\"paymentId\"=p.\"id\" and"
                                                + " c.\"projectId\"=p.\"projectId\" where"
                                                + " p.\"status\"='SUCCEEDED') select facts.*,"
                                                + " snap->>'houseName' as \"fullName\","
                                                + " snap->>'payerName' as"
                                                + " \"customerName\",snap->>'payerCustomerId' as"
                                                + " \"customerId\",cast(snap->>'settingJson' as"
                                                + " jsonb)->>'subjectId' as"
                                                + " \"subjectId\",cast(snap->>'settingJson' as"
                                                + " jsonb)->>'subjectName' as"
                                                + " \"subjectName\",cast(snap->>'activityJson' as"
                                                + " jsonb)->>'name' as"
                                                + " \"activityName\",substring(\"occurredAt\" from"
                                                + " 1 for 7) as month,cast(snap->>'payAmount' as"
                                                + " numeric) as"
                                                + " principal,coalesce((snap->'input'->>'giftMonths')::numeric,0)"
                                                + " as \"giftMonths\",cast(snap->>'originalAmount'"
                                                + " as numeric)-cast(snap->>'payAmount' as numeric)"
                                                + " as discount, (select count(*) from"
                                                + " jsonb_array_elements(snap->'periods')) as"
                                                + " periods, (select min(v->>'start') from"
                                                + " jsonb_array_elements(snap->'periods') v) as"
                                                + " \"calcStartDate\",(select max(v->>'end') from"
                                                + " jsonb_array_elements(snap->'periods') v) as"
                                                + " \"calcEndDate\",'PREPAY' as operation from"
                                                + " facts",
                                            c,
                                            q,
                                            p);
                            if (!"PERIOD".equals(i.getGroupBy())) return base;
                            // 确认事务已将每期本金与优惠全部落账，按期展开后仍以逐期冻结金额汇总。
                            return base.wrap(
                                    "select raw.\"id\"||':'||p.sequence as"
                                        + " id,raw.\"projectId\",raw.\"spaceId\",raw.\"activityId\",raw.\"billNo\",raw.\"occurredAt\",raw.actor,raw.reason,raw.\"fullName\",raw.\"customerName\",raw.\"customerId\",raw.\"subjectId\",raw.\"subjectName\",raw.\"activityName\",raw.month,p.v->>'start'"
                                        + " as \"calcStartDate\",p.v->>'end' as"
                                        + " \"calcEndDate\",(p.v->>'paidAmount')::numeric as"
                                        + " principal,(p.v->>'discountAmount')::numeric as"
                                        + " discount,1::numeric as periods,case when p.sequence=1"
                                        + " then raw.\"giftMonths\" else 0 end as \"giftMonths\""
                                        + " from ($SOURCE) raw cross join lateral"
                                        + " jsonb_array_elements(raw.snap->'periods') with"
                                        + " ordinality as p(v,sequence)");
                        });
    }
}
