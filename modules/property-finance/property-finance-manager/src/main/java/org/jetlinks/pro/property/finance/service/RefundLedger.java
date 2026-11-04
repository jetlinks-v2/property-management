package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.RefundRules;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.util.*;

/** 退款、原核销已退额、应收净已收及审计必须在调用者持锁事务中一并提交。 */
@Component
@RequiredArgsConstructor
public class RefundLedger {
    private final ReactiveRepository<RefundEntity, String> refunds;
    private final ReactiveRepository<RefundAllocationEntity, String> refundLines;
    private final ReactiveRepository<PaymentAllocationEntity, String> sources;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<ReceivableLogEntity, String> logs;

    Mono<List<RefundAllocationEntity>> persist(
            RefundEntity refund,
            RefundSourceService.Sources original,
            List<RefundRules.Share> shares,
            Authentication auth) {
        var bySource = new HashMap<String, PaymentAllocationEntity>();
        original.lines().forEach(line -> bySource.put(line.getId(), line));
        var byBill = new HashMap<String, BigDecimal>();
        // 临时收款只退原资金来源；仅实际关联应收的核销才恢复欠费。
        shares.forEach(
                share -> {
                    var billId = bySource.get(share.sourceId()).getReceivableId();
                    if (billId != null) byBill.merge(billId, share.amount(), BigDecimal::add);
                });
        return refunds.insert(refund)
                .thenMany(
                        Flux.fromIterable(original.bills())
                                .filter(row -> byBill.containsKey(row.getId()))
                                .concatMap(
                                        row ->
                                                updateBill(
                                                        refund,
                                                        row,
                                                        byBill.get(row.getId()),
                                                        auth)))
                .thenMany(
                        Flux.range(0, shares.size())
                                .concatMap(
                                        index -> {
                                            var share = shares.get(index);
                                            var source = bySource.get(share.sourceId());
                                            var line = new RefundAllocationEntity();
                                            line.setId(UUID.randomUUID().toString());
                                            init(line, auth);
                                            line.setProjectId(refund.getProjectId());
                                            line.setRefundId(refund.getId());
                                            line.setSourceLineId(source.getId());
                                            line.setPaymentId(source.getPaymentId());
                                            line.setReceivableId(source.getReceivableId());
                                            line.setSpaceId(source.getSpaceId());
                                            line.setFullName(source.getFullName());
                                            line.setCustomerName(source.getCustomerName());
                                            line.setSubjectName(source.getSubjectName());
                                            line.setSortIndex(index);
                                            line.setAmount(share.amount());
                                            return audit(
                                                            sources.createUpdate(),
                                                            auth,
                                                            source.getVersion())
                                                    .set(
                                                            "refundedAmount",
                                                            source.getRefundedAmount()
                                                                    .add(share.amount()))
                                                    .where("id", source.getId())
                                                    .and("projectId", refund.getProjectId())
                                                    .and("version", source.getVersion())
                                                    .and("deleted", false)
                                                    .execute()
                                                    .flatMap(SubjectService::changed)
                                                    .then(refundLines.insert(line))
                                                    .thenReturn(line);
                                        }))
                .collectList();
    }

    private Mono<Void> updateBill(
            RefundEntity refund, ReceivableEntity before, BigDecimal amount, Authentication auth) {
        var row = new ReceivableEntity();
        BeanUtils.copyProperties(before, row);
        if (row.getPaidAmount().compareTo(amount) < 0)
            return Mono.error(error("refund_source_blocked", 409));
        row.setPaidAmount(row.getPaidAmount().subtract(amount));
        // 原应收（含月结快照）不因退款改写；净已收减少的同额恢复欠费。
        row.setArrears(
                row.getActualReceivableAmount().subtract(row.getPaidAmount()).max(BigDecimal.ZERO));
        row.setVersion(before.getVersion() + 1);
        var log = new ReceivableLogEntity();
        log.setId(UUID.randomUUID().toString());
        log.setProjectId(refund.getProjectId());
        log.setReceivableId(row.getId());
        log.setSpaceId(row.getSpaceId());
        log.setOperation("REFUND");
        log.setReason(refund.getId());
        log.setOperatorId(auth.getUser().getId());
        log.setOperatorName(auth.getUser().getUsername());
        log.setCreateTime(System.currentTimeMillis());
        log.setBeforeVersion(before.getVersion());
        log.setAfterVersion(row.getVersion());
        log.setBeforeJson(JSON.toJSONString(before));
        log.setAfterJson(JSON.toJSONString(row));
        return audit(bills.createUpdate(), auth, before.getVersion())
                .set("paidAmount", row.getPaidAmount())
                .set("arrears", row.getArrears())
                .where("id", row.getId())
                .and("projectId", refund.getProjectId())
                .and("version", before.getVersion())
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then(logs.insert(log))
                .then();
    }
}
