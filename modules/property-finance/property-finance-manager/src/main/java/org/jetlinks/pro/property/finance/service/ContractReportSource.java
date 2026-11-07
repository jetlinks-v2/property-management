package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

/** 同一账单两个截止日的净核销；SQL先按账单聚合，避免把全部历史资金行拉入内存。 */
@Component
@RequiredArgsConstructor
class ContractReportSource {
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<PaymentAllocationEntity, String> lines;
    private final ReactiveRepository<RefundEntity, String> refunds;
    private final ReactiveRepository<RefundAllocationEntity, String> refundLines;
    private final DatabaseOperator database;

    Mono<Map<String, Paid>> paid(List<ReceivableEntity> selected, YearMonth month) {
        if (selected.isEmpty()) return Mono.just(Map.of());
        var q = new QueryParamEntity();
        q.setPaging(false);
        q.toQuery().in("id", selected.stream().map(ReceivableEntity::getId).toList());
        var params = new ArrayList<Object>();
        String with =
                "with b as ("
                        + source(bills, q, params, "id", "projectId", "spaceId")
                        + "), "
                        + "pl as (select l.* from ("
                        + source(
                                lines,
                                all(),
                                params,
                                "id",
                                "receivableId",
                                "projectId",
                                "spaceId",
                                "paymentId",
                                "amount",
                                "deleted",
                                "sourceType")
                        + ") l where l.\"receivableId\" in(select \"id\" from b) or l.\"id\""
                        + " in(select x.\"sourceLineId\" from ("
                        + source(refundLines, all(), params, "sourceLineId", "receivableId")
                        + ") x where x.\"receivableId\" in(select \"id\" from b))), "
                        + "p as (select x.* from ("
                        + source(
                                payments,
                                all(),
                                params,
                                "id",
                                "projectId",
                                "paidAt",
                                "status",
                                "deleted",
                                "amount")
                        + ") x where x.\"id\" in(select \"paymentId\" from pl)), "
                        + "rl as (select x.* from ("
                        + source(
                                refundLines,
                                all(),
                                params,
                                "id",
                                "sourceLineId",
                                "paymentId",
                                "receivableId",
                                "refundId",
                                "projectId",
                                "spaceId",
                                "amount",
                                "deleted")
                        + ") x where x.\"receivableId\" in(select \"id\" from b) or"
                        + " x.\"sourceLineId\" in(select \"id\" from pl)), r as (select x.* from ("
                        + source(
                                refunds,
                                all(),
                                params,
                                "id",
                                "projectId",
                                "refundedAt",
                                "status",
                                "deleted",
                                "destination",
                                "amount")
                        + ") x where x.\"id\" in(select \"refundId\" from rl)), ";
        // 不以INNER JOIN或SUCCEEDED过滤掩盖孤儿、跨项目或失败主单；损坏来源必须显式拒绝。
        String facts =
                """
pf as (
  select b."id" bill, pl."id" source, pl."amount" amount,
    cast(substring(p."paidAt" from 1 for 10) as date) occurred,
    case when p."id" is null or p."deleted" or p."status" <> 'SUCCEEDED'
      or pl."projectId" <> b."projectId" or p."projectId" <> b."projectId"
      or pl."spaceId" <> b."spaceId" or pl."sourceType" <> 'RECEIVABLE'
      or pl."amount" <= 0 or p."amount" < pl."amount" or p."paidAt" is null
      then 1 else 0 end bad
  from b join pl on pl."receivableId"=b."id" and pl."deleted"=false
  left join p on p."id"=pl."paymentId"
), rf as (
  select b."id" bill, rl."sourceLineId" source, rl."amount" amount,
    pl."amount" original_amount,
    cast(substring(r."refundedAt" from 1 for 10) as date) occurred,
    case when r."id" is null or pl."id" is null or p."id" is null
      or r."deleted" or pl."deleted" or p."deleted"
      or r."status" <> 'SUCCEEDED' or p."status" <> 'SUCCEEDED'
      or r."destination" not in ('CASH','BALANCE')
      or pl."sourceType" <> 'RECEIVABLE' or rl."receivableId" is null
      or pl."receivableId" is null or rl."receivableId" <> b."id" or pl."receivableId" <> b."id"
      or rl."paymentId" <> pl."paymentId"
      or rl."projectId" <> b."projectId" or r."projectId" <> b."projectId"
      or pl."projectId" <> b."projectId" or p."projectId" <> b."projectId"
      or rl."spaceId" <> b."spaceId" or pl."spaceId" <> b."spaceId"
      or rl."amount" <= 0 or pl."amount" <= 0 or r."amount" <= 0
      or r."refundedAt" is null or p."paidAt" is null
      or cast(r."refundedAt" as timestamp) < cast(p."paidAt" as timestamp)
      then 1 else 0 end bad
  from rl left join pl on pl."id"=rl."sourceLineId"
  join b on (b."id"=rl."receivableId" or b."id"=pl."receivableId")
  left join r on r."id"=rl."refundId" left join p on p."id"=pl."paymentId"
  where rl."deleted"=false
), f as (
  select bill, amount, occurred, bad from pf
  union all
  select bill, -amount, occurred,
    case when sum(amount) over(partition by source)>original_amount then 1 else bad end
    from rf
)
select b."id" id,
  coalesce(sum(case when f.occurred <= cast(? as date) then f.amount else 0 end),0) opening,
  coalesce(sum(case when f.occurred <= cast(? as date) then f.amount else 0 end),0) closing,
  coalesce(max(f.bad),0) bad
from b left join f on f.bill=b."id" group by b."id"
""";
        params.add(month.minusMonths(1).atEndOfMonth().toString());
        params.add(month.atEndOfMonth().toString());
        return database.sql()
                .reactive()
                .select(SqlRequests.of(with + facts, params.toArray()), ResultWrappers.map())
                .map(
                        row -> {
                            if (((Number) row.get("bad")).intValue() != 0)
                                throw error("contract_report_source_invalid", 409);
                            var opening = new BigDecimal(row.get("opening").toString()).setScale(2);
                            var closing = new BigDecimal(row.get("closing").toString()).setScale(2);
                            if (opening.signum() < 0 || closing.signum() < 0)
                                throw error("contract_report_source_invalid", 409);
                            return Map.entry(row.get("id").toString(), new Paid(opening, closing));
                        })
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .onErrorMap(
                        ContractReportSource::invalidDate,
                        e -> error("contract_report_source_invalid", 409));
    }

    private static boolean invalidDate(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause())
            if (cause instanceof io.r2dbc.spi.R2dbcException sql
                    && sql.getSqlState() != null
                    && sql.getSqlState().startsWith("22")) return true;
        return false;
    }

    private static QueryParamEntity all() {
        var q = new QueryParamEntity();
        q.setPaging(false);
        return q;
    }

    private static String source(
            ReactiveRepository<?, ?> rows,
            QueryParamEntity q,
            List<Object> params,
            String... columns) {
        var sql = rows.nativeQuery().select(columns).setParam(q).getSql();
        params.addAll(Arrays.asList(sql.getParameters()));
        return sql.getSql();
    }

    record Paid(BigDecimal opening, BigDecimal closing) {}
}
