package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import java.util.*;

/** 实际出款退款以负数交账，不能改写原实收或误当成第二笔收入。 */
@SpringBootTest(
        classes = HandoverRefundPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HandoverRefundPersistenceTest extends RefundPersistenceTest {
    @Autowired HandoverService handover;
    @Autowired HandoverRecords handoverRecords;

    @Test
    void actualRefundPreservesOriginalPaymentAndProducesNegativeCashHandover() {
        var bill = fixture.bill("refund-handover");
        var payment = collect(bill, "100");
        var returned = confirm(submission(input(payment.lines(), "25"))).refund();
        var input =
                new HandoverService.Input(
                        List.of(
                                new HandoverSources.Item(
                                        "REFUND", returned.getId(), returned.getVersion())),
                        DATE.toString(),
                        null,
                        null,
                        null);
        var preview =
                handover.preview(project, input, DATE.atTime(16, 0))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals("-25.00", preview.amount());
        assertEquals("-25.00", preview.cashAmount());
        var saved =
                handover.confirm(
                                project,
                                new HandoverService.Submission(
                                        UUID.randomUUID().toString(), preview.token(), input),
                                DATE.atTime(16, 0))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals("-25.00", saved.batch().getAmount().toPlainString());
        assertEquals(
                "100.00",
                sources.createQuery()
                        .where("paymentId", payment.payment().getId())
                        .fetchOne()
                        .block(WAIT)
                        .getAmount()
                        .toPlainString());
        assertEquals(
                0,
                handoverRecords
                        .candidates(project, "REFUND", new QueryParamEntity())
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getTotal());
    }

    @Configuration
    @Import({
        RefundPersistenceTest.Config.class,
        HandoverSources.class,
        HandoverRecords.class,
        HandoverService.class,
        DocumentQueryScope.class
    })
    static class Config {}
}
