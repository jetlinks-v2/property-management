package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.calculation.AccountingCalendarRules;
import org.jetlinks.pro.property.finance.entity.MonthClosingMemberEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import java.time.LocalDate;
import java.util.*;

@SpringBootTest(
        classes = MonthClosingRefundPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MonthClosingRefundPersistenceTest extends HandoverRefundPersistenceTest {
    @Autowired MonthClosingService closing;
    @Autowired AccountingCalendarService calendar;
    @Autowired ReactiveRepository<MonthClosingMemberEntity, String> members;

    @Test
    void refundIsNegativeInClosedTotalsAndHasItsOwnPermanentMembership() {
        var bill = fixture.bill("monthly-refund");
        var payment = collect(bill, "100");
        var refund = confirm(submission(input(payment.lines(), "25"))).refund();
        var input =
                new HandoverService.Input(
                        List.of(
                                new HandoverSources.Item(
                                        "PAYMENT",
                                        payment.payment().getId(),
                                        payment.payment().getVersion()),
                                new HandoverSources.Item(
                                        "REFUND", refund.getId(), refund.getVersion())),
                        DATE.toString(),
                        "退款月结",
                        null,
                        null);
        var quote =
                handover.preview(project, input, DATE.atTime(16, 0))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        var batch =
                handover.confirm(
                                project,
                                new HandoverService.Submission(
                                        UUID.randomUUID().toString(), quote.token(), input),
                                DATE.atTime(16, 0))
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .batch();
        handover.action(
                        project,
                        batch.getId(),
                        "APPROVE",
                        new HandoverService.Action(
                                UUID.randomUUID().toString(), batch.getVersion(), "核对"),
                        DATE.atTime(17, 0))
                .contextWrite(fixture.allowed())
                .block(WAIT);
        calendar.create(project, new AccountingCalendarRules.Input(2024, "NATURAL", 1))
                .contextWrite(fixture.allowed())
                .block(WAIT);
        MonthClosingService.Result result = null;
        for (var month : List.of("2024-01", "2024-02")) {
            var preview =
                    closing.preview(project, month, "CLOSE", LocalDate.of(2024, 2, 29))
                            .contextWrite(fixture.allowed())
                            .block(WAIT);
            result =
                    closing.submit(
                                    project,
                                    month,
                                    "CLOSE",
                                    new MonthClosingService.Submission(
                                            UUID.randomUUID().toString(),
                                            preview.version(),
                                            preview.token(),
                                            "收退核对"),
                                    LocalDate.of(2024, 2, 29))
                            .contextWrite(fixture.allowed())
                            .block(WAIT);
        }
        assertEquals("75.00", result.amount());
        assertEquals("75.00", result.cashAmount());
        assertEquals(2, result.sourceCount());
        assertEquals(
                1,
                members.createQuery()
                        .where("operationId", result.operationId())
                        .and("sourceType", "REFUND")
                        .and("sourceId", refund.getId())
                        .count()
                        .block(WAIT));
        assertEquals("25.00", refund.getAmount().toPlainString());
    }

    @Configuration
    @Import({
        HandoverRefundPersistenceTest.Config.class,
        MonthClosingPreparation.class,
        MonthClosingService.class,
        MonthClosingSources.class,
        AccountingCalendarService.class,
        AccountingCalendarRules.class,
        DocumentStore.class
    })
    @ImportAutoConfiguration(JacksonAutoConfiguration.class)
    static class Config {}
}
