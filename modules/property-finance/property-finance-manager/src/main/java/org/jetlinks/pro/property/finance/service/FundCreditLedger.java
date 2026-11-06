package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.stereotype.Component;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 调用方已持有账户锁；按128笔分批消费FIFO来源，与余额、流水处于同一事务。 */
@Component
@RequiredArgsConstructor
public class FundCreditLedger {
    private final ReactiveRepository<FundCreditEntity, String> credits;
    private final ReactiveRepository<FundConsumptionEntity, String> consumptions;

    Mono<FundCreditEntity> source(String project, String account, String entry) {
        return credits.createQuery()
                .where("id", entry)
                .and("entryId", entry)
                .and("projectId", project)
                .and("accountId", account)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("fund_credit_invalid", 409)));
    }

    Mono<Void> record(FundEntryEntity entry, Authentication auth) {
        if (entry.getAmount().signum() > 0) {
            var row = new FundCreditEntity();
            row.setId(entry.getId());
            init(row, auth);
            row.setProjectId(entry.getProjectId());
            row.setAccountId(entry.getAccountId());
            row.setEntryId(entry.getId());
            row.setOriginalAmount(entry.getAmount());
            row.setRemainingAmount(entry.getAmount());
            row.setInvoiceOccupiedAmount(BigDecimal.ZERO.setScale(2));
            row.setDayClosed(false);
            row.setMonthClosed(false);
            return credits.insert(row).then();
        }
        return consumeBatch(entry, auth, entry.getAmount().negate());
    }

    private Mono<Void> consumeBatch(
            FundEntryEntity entry, Authentication auth, BigDecimal remaining) {
        if (remaining.signum() <= 0) return Mono.empty();
        var param = new QueryParamEntity();
        param.setPageIndex(0);
        param.setPageSize(128);
        param.orderBy("createTime");
        param.orderBy("id");
        var query =
                credits.createQuery()
                        .setParam(param)
                        .where("projectId", entry.getProjectId())
                        .and("accountId", entry.getAccountId())
                        .and("deleted", false)
                        .gt("remainingAmount", 0);
        // 指定撤销来源，不能把第二笔存入的撤销错误地消费到FIFO第一笔。
        if ("REVOKE_TOPUP".equals(entry.getAction())) query.and("id", entry.getSourceEntryId());
        return query.fetch()
                .collectList()
                .flatMap(
                        rows -> {
                            if (rows.isEmpty())
                                return Mono.error(error("fund_credit_invalid", 409));
                            var rest = new BigDecimal[] {remaining};
                            return Flux.fromIterable(rows)
                                    .concatMap(
                                            credit -> {
                                                if (rest[0].signum() <= 0) return Mono.empty();
                                                if (credit.getRemainingAmount().signum() <= 0
                                                        || credit.getRemainingAmount()
                                                                        .compareTo(
                                                                                credit
                                                                                        .getOriginalAmount())
                                                                > 0)
                                                    return Mono.error(
                                                            error("fund_credit_invalid", 409));
                                                var amount =
                                                        rest[0].min(credit.getRemainingAmount());
                                                rest[0] = rest[0].subtract(amount);
                                                return consume(entry, credit, amount, auth);
                                            })
                                    .then(Mono.defer(() -> consumeBatch(entry, auth, rest[0])));
                        });
    }

    private Mono<Void> consume(
            FundEntryEntity entry,
            FundCreditEntity credit,
            BigDecimal amount,
            Authentication auth) {
        var row = new FundConsumptionEntity();
        row.setId(
                UUID.nameUUIDFromBytes(
                                (entry.getId() + ":" + credit.getId())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString());
        init(row, auth);
        row.setProjectId(entry.getProjectId());
        row.setAccountId(entry.getAccountId());
        row.setCreditId(credit.getId());
        row.setDebitEntryId(entry.getId());
        row.setAmount(amount);
        return audit(credits.createUpdate(), auth, credit.getVersion())
                .set("remainingAmount", credit.getRemainingAmount().subtract(amount))
                .where("id", credit.getId())
                .and("projectId", entry.getProjectId())
                .and("accountId", entry.getAccountId())
                .and("version", credit.getVersion())
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then(consumptions.insert(row))
                .then();
    }
}
