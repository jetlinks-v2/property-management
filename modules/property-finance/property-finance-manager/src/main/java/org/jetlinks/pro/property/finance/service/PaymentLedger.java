package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.springframework.stereotype.Component;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.util.*;

/** 仅由已持锁且处于事务中的收款入口调用；实收、核销、应收和审计不可拆开提交。 */
@Component
@RequiredArgsConstructor
public class PaymentLedger {
    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<PaymentAllocationEntity, String> lines;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<ReceivableLogEntity, String> logs;

    Mono<List<PaymentAllocationEntity>> persist(
            PaymentEntity payment,
            List<ReceivableEntity> originals,
            PaymentRules.Result result,
            CashierConfiguration.Resolved configuration,
            Authentication auth) {
        var before = new HashMap<String, ReceivableEntity>();
        originals.forEach(row -> before.put(row.getId(), row));
        var after = new HashMap<String, ReceivableEntity>();
        result.rows().forEach(row -> after.put(row.getId(), row));
        return payments.insert(payment)
                .thenMany(
                        Flux.fromIterable(result.rows())
                                .concatMap(
                                        row -> {
                                            var original = before.get(row.getId());
                                            return audit(
                                                            bills.createUpdate(),
                                                            auth,
                                                            original.getVersion())
                                                    .set("paidAmount", row.getPaidAmount())
                                                    .set("paidDate", row.getPaidDate())
                                                    .set("arrears", row.getArrears())
                                                    .set(
                                                            "contractReceivableAmount",
                                                            row.getContractReceivableAmount())
                                                    .set(
                                                            "accruedReceivableAmount",
                                                            row.getAccruedReceivableAmount())
                                                    .set(
                                                            "accruedAsOfDate",
                                                            row.getAccruedAsOfDate())
                                                    .set(
                                                            "calculatedAsOfDate",
                                                            row.getCalculatedAsOfDate())
                                                    .set("periodStatus", row.getPeriodStatus())
                                                    .set(
                                                            "accruedLateFeeAmount",
                                                            row.getAccruedLateFeeAmount())
                                                    .set(
                                                            "finalLateFeeAmount",
                                                            row.getFinalLateFeeAmount())
                                                    .set(
                                                            "actualReceivableAmount",
                                                            row.getActualReceivableAmount())
                                                    .where("id", row.getId())
                                                    .and("projectId", payment.getProjectId())
                                                    .and("version", original.getVersion())
                                                    .and("deleted", false)
                                                    .execute()
                                                    .flatMap(SubjectService::changed)
                                                    .then(
                                                            Mono.defer(
                                                                    () -> {
                                                                        row.setVersion(
                                                                                original
                                                                                                .getVersion()
                                                                                        + 1);
                                                                        var log =
                                                                                new ReceivableLogEntity();
                                                                        log.setId(
                                                                                UUID.randomUUID()
                                                                                        .toString());
                                                                        log.setProjectId(
                                                                                payment
                                                                                        .getProjectId());
                                                                        log.setReceivableId(
                                                                                row.getId());
                                                                        log.setSpaceId(
                                                                                row.getSpaceId());
                                                                        log.setOperation("COLLECT");
                                                                        log.setReason(
                                                                                payment.getId());
                                                                        log.setOperatorId(
                                                                                auth.getUser()
                                                                                        .getId());
                                                                        log.setOperatorName(
                                                                                auth.getUser()
                                                                                        .getUsername());
                                                                        log.setCreateTime(
                                                                                System
                                                                                        .currentTimeMillis());
                                                                        log.setBeforeVersion(
                                                                                original
                                                                                        .getVersion());
                                                                        log.setAfterVersion(
                                                                                row.getVersion());
                                                                        log.setBeforeJson(
                                                                                JSON.toJSONString(
                                                                                        original));
                                                                        log.setAfterJson(
                                                                                JSON.toJSONString(
                                                                                        row));
                                                                        return logs.insert(log);
                                                                    }));
                                        }))
                .thenMany(
                        Flux.range(0, result.allocations().size())
                                .concatMap(
                                        index -> {
                                            var allocation = result.allocations().get(index);
                                            var funding =
                                                    configuration
                                                            .funding()
                                                            .get(allocation.tenderIndex());
                                            var bill = after.get(allocation.receivableId());
                                            var line = new PaymentAllocationEntity();
                                            line.setId(UUID.randomUUID().toString());
                                            init(line, auth);
                                            line.setPaymentId(payment.getId());
                                            line.setProjectId(payment.getProjectId());
                                            line.setSpaceId(bill.getSpaceId());
                                            line.setReceivableId(bill.getId());
                                            line.setCustomerId(bill.getCustomerId());
                                            line.setCustomerName(bill.getCustomerName());
                                            line.setFullName(bill.getFullName());
                                            line.setSubjectName(bill.getSubjectName());
                                            line.setCalcStartDate(bill.getCalcStartDate());
                                            line.setCalcEndDate(bill.getCalcEndDate());
                                            line.setSortIndex(index);
                                            line.setMethod(funding.method());
                                            line.setMethodText(funding.text());
                                            line.setRecordedId(funding.recordedId());
                                            line.setBankId(funding.bankId());
                                            line.setBankName(funding.bankName());
                                            line.setBankAccount(funding.bankAccount());
                                            line.setAmount(allocation.amount());
                                            line.setRefundedAmount(BigDecimal.ZERO.setScale(2));
                                            return lines.insert(line).thenReturn(line);
                                        }))
                .collectList();
    }
}
