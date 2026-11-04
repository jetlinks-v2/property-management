package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.pro.property.finance.document.DocumentIssueInput;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import java.util.*;

/** 真实收款、开具、核销后验证交账约束，不用伪造票据状态替代业务。 */
@SpringBootTest(
        classes = HandoverReceiptPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HandoverReceiptPersistenceTest extends ReceiptVerificationPersistenceTest {
    @Autowired HandoverService handover;
    @Autowired HandoverRecords handoverRecords;

    HandoverRecords.Detail handed(PaymentEntity p) {
        var input =
                new HandoverService.Input(
                        List.of(new HandoverSources.Item("PAYMENT", p.getId(), p.getVersion())),
                        DATE.toString(),
                        null,
                        null,
                        null);
        var preview =
                handover.preview(project, input, NOW).contextWrite(fixture.allowed()).block(WAIT);
        return handover.confirm(
                        project,
                        new HandoverService.Submission(
                                UUID.randomUUID().toString(), preview.token(), input),
                        NOW)
                .contextWrite(fixture.allowed())
                .block(WAIT);
    }

    DocumentEntity receiptFor(ReceivableEntity bill) {
        var base = input(List.of(item("RECEIVABLE", bill.getId(), "10")));
        var i =
                new DocumentIssueInput(
                        "RECEIPT", base.companyId(), base.buyer(), null, null, base.items());
        return issue(submission(i)).document();
    }

    @Test
    void verifiedReceiptBlocksReleasingItsPaymentButNotAnotherPaymentInSameRoom() {
        var bill = documentBill();
        var payment =
                cashier.collect(project, fixture.submission(fixture.input(bill, "30")), DATE)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        var batch = handed(payment.payment());
        var receipt = receiptFor(bill);
        verify(verification(receipt));
        fixture.rejects(
                handover.action(
                        project,
                        batch.batch().getId(),
                        "CANCEL",
                        new HandoverService.Action(
                                UUID.randomUUID().toString(), batch.batch().getVersion(), "更正"),
                        NOW),
                409);
        fixture.rejects(
                handover.action(
                        project,
                        batch.batch().getId(),
                        "REJECT",
                        new HandoverService.Action(
                                UUID.randomUUID().toString(), batch.batch().getVersion(), "驳回"),
                        NOW),
                409);
        var other = documentBill();
        var second =
                cashier.collect(project, fixture.submission(fixture.input(other, "20")), DATE)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        var unrelated = handed(second.payment());
        var cancelled =
                handover.action(
                                project,
                                unrelated.batch().getId(),
                                "CANCEL",
                                new HandoverService.Action(
                                        UUID.randomUUID().toString(),
                                        unrelated.batch().getVersion(),
                                        "独立更正"),
                                NOW)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals("CANCELLED", cancelled.batch().getStatus());
        assertEquals(
                "PENDING",
                handoverRecords
                        .detail(project, batch.batch().getId())
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .batch()
                        .getStatus());
    }

    @Configuration
    @Import({
        ReceiptVerificationPersistenceTest.Config.class,
        HandoverSources.class,
        HandoverRecords.class,
        HandoverService.class,
        DocumentQueryScope.class
    })
    static class Config {}
}
