package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.init;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.springframework.stereotype.Component;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.util.*;

/** 与应收核销共用实收账本，临时款只写收款事实，不触碰任何应收余额。 */
@Component
@RequiredArgsConstructor
public class TemporaryLedger {
    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<PaymentAllocationEntity, String> lines;

    Mono<List<PaymentAllocationEntity>> persist(
            PaymentEntity payment,
            TemporaryInput input,
            TemporarySourceService.Prepared source,
            CashierConfiguration.Resolved config,
            Authentication auth) {
        return payments.insert(payment)
                .thenMany(
                        Flux.range(0, input.getTenders().size())
                                .concatMap(
                                        index -> {
                                            var tender = input.getTenders().get(index);
                                            var funding = config.funding().get(index);
                                            var line = new PaymentAllocationEntity();
                                            line.setId(UUID.randomUUID().toString());
                                            init(line, auth);
                                            line.setSourceType("TEMPORARY");
                                            line.setPaymentId(payment.getId());
                                            line.setProjectId(payment.getProjectId());
                                            line.setSpaceId(source.house().spaceId());
                                            line.setCustomerId(source.customer().customerId());
                                            line.setCustomerName(source.customer().name());
                                            line.setFullName(source.house().fullName());
                                            line.setSubjectName(source.standard().getSubjectName());
                                            line.setStandardId(source.standard().getId());
                                            line.setSubjectId(source.standard().getSubjectId());
                                            line.setCalcStartDate(input.getStartDate());
                                            line.setCalcEndDate(input.getEndDate());
                                            line.setSortIndex(index);
                                            line.setMethod(funding.method());
                                            line.setMethodText(funding.text());
                                            line.setRecordedId(funding.recordedId());
                                            line.setBankId(funding.bankId());
                                            line.setBankName(funding.bankName());
                                            line.setBankAccount(funding.bankAccount());
                                            line.setAmount(TemporaryRules.money(tender.amount()));
                                            line.setRefundedAmount(BigDecimal.ZERO.setScale(2));
                                            return lines.insert(line).thenReturn(line);
                                        }))
                .collectList();
    }
}
