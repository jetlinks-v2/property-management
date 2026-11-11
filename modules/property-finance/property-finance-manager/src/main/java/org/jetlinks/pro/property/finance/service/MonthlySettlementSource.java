package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.report.AnalysisInput;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.*;

/** 现金按收退业务日、费用按原分配账期归属；余额核销独立列示，不当作再次收款。 */
@Component
@RequiredArgsConstructor
class MonthlySettlementSource {
    private final AnalysisSql sql;
    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<PaymentAllocationEntity, String> lines;
    private final ReactiveRepository<RefundEntity, String> refunds;
    private final ReactiveRepository<RefundAllocationEntity, String> refundLines;

    Mono<AnalysisSql.Source> enrich(AnalysisInput i, AnalysisSql.Source receivables) {
        return sql.source(
                        lines,
                        i,
                        "id",
                        "projectId",
                        "spaceId",
                        "paymentId",
                        "receivableId",
                        "method",
                        "amount",
                        "calcStartDate",
                        "calcEndDate")
                .flatMap(
                        l ->
                                sql.source(
                                                refundLines,
                                                i,
                                                "id",
                                                "projectId",
                                                "spaceId",
                                                "sourceLineId",
                                                "refundId",
                                                "amount")
                                        .map(
                                                rl -> {
                                                    var p =
                                                            sql.related(
                                                                    payments,
                                                                    i,
                                                                    "id",
                                                                    "projectId",
                                                                    "paidAt",
                                                                    "status");
                                                    var r =
                                                            sql.related(
                                                                    refunds,
                                                                    i,
                                                                    "id",
                                                                    "projectId",
                                                                    "refundedAt",
                                                                    "status");
                                                    String month = i.getStartDate().substring(0, 7),
                                                            year = i.getStartDate().substring(0, 4);
                                                    String base =
                                                            "with b as ($0), l as ($1), p as ($2),"
                                                                + " rl as ($3), r as ($4),"
                                                                + " movements as (select"
                                                                + " l.\"projectId\",l.\"receivableId\",l.\"method\",l.\"calcStartDate\",l.\"calcEndDate\",l.\"amount\""
                                                                + " as amount,0::numeric as"
                                                                + " refunded from l join p on"
                                                                + " p.\"id\"=l.\"paymentId\" and"
                                                                + " p.\"projectId\"=l.\"projectId\""
                                                                + " where p.\"status\"='SUCCEEDED'"
                                                                + " and substring(p.\"paidAt\" from"
                                                                + " 1 for 10) between '"
                                                                    + i.getStartDate()
                                                                    + "' and '"
                                                                    + i.getEndDate()
                                                                    + "' union all select"
                                                                    + " l.\"projectId\",l.\"receivableId\",l.\"method\",l.\"calcStartDate\",l.\"calcEndDate\",0,rl.\"amount\""
                                                                    + " from rl join r on"
                                                                    + " r.\"id\"=rl.\"refundId\""
                                                                    + " and r.\"projectId\"=rl.\"projectId\""
                                                                    + " join l on"
                                                                    + " l.\"id\"=rl.\"sourceLineId\""
                                                                    + " and l.\"projectId\"=rl.\"projectId\""
                                                                    + " and l.\"spaceId\"=rl.\"spaceId\""
                                                                    + " where"
                                                                    + " r.\"status\"='SUCCEEDED'"
                                                                    + " and substring(r.\"refundedAt\""
                                                                    + " from 1 for 10) between '"
                                                                    + i.getStartDate()
                                                                    + "' and '"
                                                                    + i.getEndDate()
                                                                    + "'), totals as(select"
                                                                    + " \"projectId\",\"receivableId\",sum(case"
                                                                    + " when \"method\" in"
                                                                    + " ('1','2','3','4') then"
                                                                    + " amount else 0 end) as"
                                                                    + " cash,sum(case when"
                                                                    + " \"method\" not in"
                                                                    + " ('1','2','3','4') then"
                                                                    + " amount else 0 end) as"
                                                                    + " offset,sum(refunded) as"
                                                                    + " restored,";
                                                    String calendar =
                                                            "sum(case when \"method\" in"
                                                                + " ('1','2','3','4') and ";
                                                    base +=
                                                            calendar
                                                                    + "substring(\"calcEndDate\""
                                                                    + " from 1 for 4)<'"
                                                                    + year
                                                                    + "' then amount else 0 end) as"
                                                                    + " \"priorYears\","
                                                                    + calendar
                                                                    + "substring(\"calcEndDate\""
                                                                    + " from 1 for 4)='"
                                                                    + year
                                                                    + "' and"
                                                                    + " substring(\"calcEndDate\""
                                                                    + " from 1 for 7)<'"
                                                                    + month
                                                                    + "' then amount else 0 end) as"
                                                                    + " \"priorMonths\","
                                                                    + calendar
                                                                    + "substring(\"calcStartDate\""
                                                                    + " from 1 for 7)<='"
                                                                    + month
                                                                    + "' and"
                                                                    + " substring(\"calcEndDate\""
                                                                    + " from 1 for 7)>='"
                                                                    + month
                                                                    + "' then amount else 0 end) as"
                                                                    + " \"currentMonth\","
                                                                    + calendar
                                                                    + "substring(\"calcStartDate\""
                                                                    + " from 1 for 7)>'"
                                                                    + month
                                                                    + "' then amount else 0 end) as"
                                                                    + " future from movements group"
                                                                    + " by \"projectId\",\"receivableId\")"
                                                                    + " select"
                                                                    + " b.*,coalesce(t.cash,0) as"
                                                                    + " cash,coalesce(t.offset,0)"
                                                                    + " as offset,coalesce(t.restored,0)"
                                                                    + " as restored,coalesce(t.\"priorYears\",0)"
                                                                    + " as \"priorYears\",coalesce(t.\"priorMonths\",0)"
                                                                    + " as \"priorMonths\",coalesce(t.\"currentMonth\",0)"
                                                                    + " as \"currentMonth\",coalesce(t.future,0)"
                                                                    + " as future from b left join"
                                                                    + " totals t on"
                                                                    + " b.\"id\"=t.\"receivableId\""
                                                                    + " and b.\"projectId\"=t.\"projectId\"";
                                                    return sql.join(base, receivables, l, p, rl, r);
                                                }));
    }
}
