package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import java.time.LocalDate;

/** 预存入款、内部转账和冲抵具有不同现金口径，月结不得重记其关联的缴款流水。 */
@SpringBootTest(
        classes = MonthClosingFundPreparationPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MonthClosingFundPreparationPersistenceTest extends HandoverFundPersistenceTest {
    @Autowired MonthClosingPreparation preparation;

    MonthClosingPreparation.Result prepare() {
        var period =
                new MonthClosingPreparation.Period(
                        "2024-02", LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29));
        return preparation
                .prepare(project, period)
                .contextWrite(fixture.fixture.allowed())
                .block(WAIT);
    }

    @Test
    void originalDepositAndIndependentRefundPreserveSignedCashAndApprovalState() {
        var returned = fixture.input("REFUND", "20");
        returned.setAccountId(accountId);
        returned.setVersion(accounts.findById(accountId).block(WAIT).getVersion());
        fixture.run(returned);
        var result = prepare();
        assertEquals(2, result.sources().size());
        assertEquals("80.01", result.amount());
        assertEquals("80.01", result.cashAmount());
        assertEquals(2, result.unhandedCount());
        var submitted = confirm(handoverRequest());
        handover.action(
                        project,
                        submitted.batch().getId(),
                        "APPROVE",
                        new HandoverService.Action(
                                java.util.UUID.randomUUID().toString(),
                                submitted.batch().getVersion(),
                                "存入核对"),
                        NOW)
                .contextWrite(fixture.fixture.allowed())
                .block(WAIT);
        var after = prepare();
        assertEquals(1, after.unhandedCount());
        assertEquals(
                "APPROVED",
                after.sources().stream()
                        .filter(s -> s.id().equals(entryId))
                        .findFirst()
                        .orElseThrow()
                        .reviewState());
        assertEquals(result.amount(), after.amount());
        assertEquals(result.cashAmount(), after.cashAmount());
    }

    @Test
    void internalTransferChangesNeitherNetAmountNorCash() {
        var a = accounts.findById(accountId).block(WAIT);
        var transfer = fixture.debit(a, "TRANSFER", "30");
        transfer.setTargetSpaceId("other-room");
        transfer.setTargetRelationshipId("link");
        transfer.setTargetStandardId(fixture.setting.standardId);
        transfer.setTargetType("ADVANCE");
        fixture.run(transfer);
        var result = prepare();
        assertEquals(3, result.sources().size());
        assertEquals("100.01", result.amount());
        assertEquals("100.01", result.cashAmount());
        assertEquals(
                2, result.sources().stream().filter(s -> s.cashAmount().equals("0.00")).count());
    }

    @Test
    void balanceOffsetIsCountedOnceThroughPaymentAndNeverAsAdditionalCash() {
        var bill = fixture.fixture.bill("offset");
        bills.createUpdate()
                .set("subjectId", fixture.setting.subjectId)
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        var account = accounts.findById(accountId).block(WAIT);
        var offset = fixture.debit(account, "OFFSET", "30");
        offset.setItems(
                java.util.List.of(
                        new org.jetlinks.pro.property.finance.payment.CashierInput.Item(
                                bill.getId(), bill.getVersion(), new java.math.BigDecimal("30"))));
        fixture.run(offset);
        var result = prepare();
        assertEquals(2, result.sources().size());
        assertEquals("130.01", result.amount());
        assertEquals("100.01", result.cashAmount());
        var payment =
                result.sources().stream()
                        .filter(s -> s.type().equals("PAYMENT"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("30.00", payment.amount());
        assertEquals("0.00", payment.cashAmount());
    }

    @Configuration
    @Import({HandoverFundPersistenceTest.Config.class, MonthClosingPreparation.class})
    static class Config {}
}
