package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import java.time.LocalDate;

/** 实收与现金退款在同一会计账期按原始正负金额核对，退款不覆盖原缴款。 */
@SpringBootTest(
        classes = MonthClosingRefundPreparationPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MonthClosingRefundPreparationPersistenceTest extends HandoverRefundPersistenceTest {
    @Autowired MonthClosingPreparation preparation;

    @Test
    void paymentAndRefundAreBothPresentAndNetCashMatchesOriginalFlows() {
        var payment = collect(fixture.bill("month-refund"), "100");
        var refund = confirm(submission(input(payment.lines(), "25"))).refund();
        var period =
                new MonthClosingPreparation.Period(
                        "2024-02", LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29));
        var result =
                preparation.prepare(project, period).contextWrite(fixture.allowed()).block(WAIT);
        assertEquals(2, result.sources().size());
        assertEquals("75.00", result.amount());
        assertEquals("75.00", result.cashAmount());
        assertEquals(
                "-25.00",
                result.sources().stream()
                        .filter(s -> s.id().equals(refund.getId()))
                        .findFirst()
                        .orElseThrow()
                        .amount());
        assertEquals(
                "100.00",
                result.sources().stream()
                        .filter(s -> s.id().equals(payment.payment().getId()))
                        .findFirst()
                        .orElseThrow()
                        .amount());
        assertEquals(2, result.unhandedCount());
    }

    @Configuration
    @Import({HandoverRefundPersistenceTest.Config.class, MonthClosingPreparation.class})
    static class Config {}
}
