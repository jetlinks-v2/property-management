package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.report.AnalysisInput;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.*;

/** 余额按不可变流水的业务日重建，现金与内部余额转移分列，不读取今天余额冒充期初。 */
@Component
@RequiredArgsConstructor
class FundAnalysisSource {
    private final AnalysisSql sql;
    private final ReactiveRepository<FundEntryEntity, String> entries;
    private final ReactiveRepository<FundAccountEntity, String> accounts;

    Mono<AnalysisSql.Source> prepare(AnalysisInput i) {
        return sql.source(
                        entries,
                        i,
                        "id",
                        "billNo",
                        "projectId",
                        "spaceId",
                        "accountId",
                        "transactionId",
                        "action",
                        "fullName",
                        "customerName",
                        "subjectName",
                        "occurredAt",
                        "reason",
                        "modifierName",
                        "amount",
                        "cashAmount",
                        "beforeBalance",
                        "afterBalance",
                        "createTime",
                        "paymentId")
                .flatMap(
                        e ->
                                sql.source(accounts, i, "id", "projectId", "spaceId", "balance")
                                        .flatMap(
                                                a -> {
                                                    var checked =
                                                            sql.join(
                                                                    "with a as ($0), e as ($1),"
                                                                        + " ordered as (select"
                                                                        + " e.*,lag(\"afterBalance\",1,0::numeric)"
                                                                        + " over(partition by"
                                                                        + " \"projectId\",\"accountId\""
                                                                        + " order by"
                                                                        + " \"createTime\",\"id\")"
                                                                        + " as previous from e)"
                                                                        + " select count(*) as"
                                                                        + " missing from a where"
                                                                        + " a.\"balance\"<>coalesce((select"
                                                                        + " sum(e.\"amount\") from"
                                                                        + " e where"
                                                                        + " e.\"accountId\"=a.\"id\""
                                                                        + " and e.\"projectId\"=a.\"projectId\"),0)"
                                                                        + " or exists(select 1 from"
                                                                        + " ordered o where"
                                                                        + " o.\"accountId\"=a.\"id\""
                                                                        + " and o.\"projectId\"=a.\"projectId\""
                                                                        + " and (o.\"beforeBalance\"<>o.previous"
                                                                        + " or o.\"afterBalance\"-o.\"beforeBalance\"<>o.\"amount\"))",
                                                                    a,
                                                                    e);
                                                    return sql.select(
                                                                    checked,
                                                                    "select * from ($SOURCE)"
                                                                        + " checked")
                                                            .single()
                                                            .flatMap(
                                                                    check ->
                                                                            ((Number)
                                                                                                            check
                                                                                                                    .get(
                                                                                                                            "missing"))
                                                                                                    .longValue()
                                                                                            > 0
                                                                                    ? Mono.error(
                                                                                            FinanceScope
                                                                                                    .error(
                                                                                                            "report_history_missing",
                                                                                                            409))
                                                                                    : Mono.just(e));
                                                }))
                .flatMap(
                        e -> {
                            var a =
                                    sql.related(
                                            accounts,
                                            i,
                                            "id",
                                            "projectId",
                                            "type",
                                            "subjectId",
                                            "customerId");
                            var raw =
                                    sql.join(
                                            "with e as ($0), a as ($1) select e.*, a.\"type\","
                                                + " a.\"subjectId\", a.\"customerId\", row_number()"
                                                + " over(partition by"
                                                + " e.\"projectId\",e.\"accountId\" order by"
                                                + " e.\"createTime\",e.\"id\") as sequence from e"
                                                + " join a on a.\"id\"=e.\"accountId\" and"
                                                + " a.\"projectId\"=e.\"projectId\"",
                                            e,
                                            a);
                            return sql.select(
                                            raw,
                                            "select count(*) as missing from ($SOURCE) f where"
                                                + " sequence=1 and \"beforeBalance\"<>0")
                                    .single()
                                    .flatMap(
                                            check -> {
                                                if (((Number) check.get("missing")).longValue() > 0)
                                                    return Mono.error(
                                                            FinanceScope.error(
                                                                    "report_history_missing", 409));
                                                var params =
                                                        new ArrayList<Object>(raw.parameters());
                                                params.add(i.getStartDate());
                                                params.add(i.getEndDate());
                                                return Mono.just(
                                                        new AnalysisSql.Source(
                                                                "with raw as ("
                                                                        + raw.sql()
                                                                        + "), dates as (select"
                                                                        + " cast(? as varchar) as"
                                                                        + " start,cast(? as"
                                                                        + " varchar) as finish)"
                                                                        + " select raw.*,"
                                                                        + " \"action\" as"
                                                                        + " operation,"
                                                                        + " \"modifierName\" as"
                                                                        + " actor,"
                                                                        + " substring(\"occurredAt\""
                                                                        + " from 1 for 7) as month,"
                                                                        + " case when"
                                                                        + " substring(\"occurredAt\""
                                                                        + " from 1 for 10)<start"
                                                                        + " then amount else 0 end"
                                                                        + " as opening, amount as"
                                                                        + " closing, case when"
                                                                        + " substring(\"occurredAt\""
                                                                        + " from 1 for 10)>=start"
                                                                        + " then amount else 0 end"
                                                                        + " as movement, case when"
                                                                        + " substring(\"occurredAt\""
                                                                        + " from 1 for 10)>=start"
                                                                        + " and \"cashAmount\">0"
                                                                        + " then \"cashAmount\""
                                                                        + " else 0 end as income,"
                                                                        + " case when"
                                                                        + " substring(\"occurredAt\""
                                                                        + " from 1 for 10)>=start"
                                                                        + " and \"cashAmount\"<0"
                                                                        + " then -\"cashAmount\""
                                                                        + " else 0 end as refund, "
                                                                        + movement(
                                                                                "TRANSFER_IN",
                                                                                "transferIn")
                                                                        + ", "
                                                                        + movement(
                                                                                "TRANSFER_OUT",
                                                                                "transferOut")
                                                                        + ", "
                                                                        + movement(
                                                                                "DEDUCT", "deduct")
                                                                        + ", "
                                                                        + movement(
                                                                                "OFFSET", "offset")
                                                                        + ", "
                                                                        + movement(
                                                                                "RETURN_OFFSET",
                                                                                "offsetReturn")
                                                                        + ", case when"
                                                                        + " substring(\"occurredAt\""
                                                                        + " from 1 for 10)>=start"
                                                                        + " and \"action\" not in"
                                                                        + " ('TOPUP','REFUND','TRANSFER_IN','TRANSFER_OUT','DEDUCT','OFFSET','RETURN_OFFSET')"
                                                                        + " then amount else 0 end"
                                                                        + " as other from raw cross"
                                                                        + " join dates where"
                                                                        + " substring(\"occurredAt\""
                                                                        + " from 1 for 10)<=finish",
                                                                params));
                                            });
                        });
    }

    private static String movement(String action, String name) {
        return "case when substring(\"occurredAt\" from 1 for 10)>=start and \"action\"='"
                + action
                + "' then abs(amount) else 0 end as \""
                + name
                + "\"";
    }
}
