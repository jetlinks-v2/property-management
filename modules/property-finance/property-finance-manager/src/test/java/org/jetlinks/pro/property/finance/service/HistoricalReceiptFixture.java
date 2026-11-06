package org.jetlinks.pro.property.finance.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 构造规则收紧前的部分收款历史，供退款和票据兼容性测试使用；不调用现行收款入口。 */
class HistoricalReceiptFixture {
    private final PaymentLedger ledger;

    HistoricalReceiptFixture(
            ReactiveRepository<PaymentEntity, String> payments,
            ReactiveRepository<PaymentAllocationEntity, String> lines,
            ReactiveRepository<ReceivableEntity, String> bills,
            ReactiveRepository<ReceivableLogEntity, String> logs) {
        ledger = new PaymentLedger(payments, lines, bills, logs);
    }

    @Transactional
    public Mono<CashierService.Detail> seed(ReceivableEntity original, String value) {
        return Authentication.currentReactive()
                .flatMap(
                        auth -> {
                            var amount = new BigDecimal(value).setScale(2);
                            if (amount.signum() <= 0 || amount.compareTo(original.getArrears()) > 0)
                                return Mono.error(
                                        new IllegalArgumentException(
                                                "Invalid historical receipt amount"));
                            var row = new ReceivableEntity();
                            BeanUtils.copyProperties(original, row);
                            row.setPaidAmount(row.getPaidAmount().add(amount));
                            row.setArrears(
                                    row.getActualReceivableAmount().subtract(row.getPaidAmount()));
                            row.setPaidDate(CashierPersistenceTest.DATE.toString());
                            var payment = new PaymentEntity();
                            payment.setId(UUID.randomUUID().toString());
                            payment.setRequestFingerprint(PaymentRules.digest(payment.getId()));
                            InvoiceCompanyService.init(payment, auth);
                            payment.setProjectId(row.getProjectId());
                            payment.setCompanyId("company");
                            payment.setCompanyName("测试物业公司");
                            payment.setStatus("SUCCEEDED");
                            payment.setPayerName(row.getCustomerName());
                            payment.setPayerRelationshipId(row.getCustomerRelationshipId());
                            payment.setPaidAt(CashierPersistenceTest.DATE + "T10:00:00");
                            payment.setAmount(amount);
                            var result =
                                    new PaymentRules.Result(
                                            List.of(row),
                                            List.of(
                                                    new PaymentRules.Allocation(
                                                            row.getId(), 0, "1", null, amount)),
                                            amount.toPlainString(),
                                            CashierPersistenceTest.DATE.toString(),
                                            "historical-fixture");
                            var company = new FinanceCompanyEntity();
                            company.setId("company");
                            company.setCompanyName("测试物业公司");
                            var config =
                                    new CashierConfiguration.Resolved(
                                            company,
                                            List.of(
                                                    new CashierConfiguration.Funding(
                                                            "1", "现金", null, null, null, null,
                                                            1L)));
                            return ledger.persist(payment, List.of(original), result, config, auth)
                                    .map(
                                            lines ->
                                                    new CashierService.Detail(
                                                            payment, lines, Map.of()));
                        });
    }
}
