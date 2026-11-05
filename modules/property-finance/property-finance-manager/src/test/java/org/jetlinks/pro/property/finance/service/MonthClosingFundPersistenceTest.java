package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.WAIT;
import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.pro.property.finance.calculation.AccountingCalendarRules;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/** 原存入月结与交账是两个标记，反月结不能撤销交账或改写后续余额。 */
@SpringBootTest(
        classes = MonthClosingFundPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MonthClosingFundPersistenceTest extends HandoverFundPersistenceTest {
    static final LocalDate END = LocalDate.of(2024, 2, 29);
    @Autowired MonthClosingService closing;
    @Autowired AccountingCalendarService calendar;

    MonthClosingService.Result month(String month, String action) {
        var preview =
                closing.preview(project, month, action, END)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        return closing.submit(
                        project,
                        month,
                        action,
                        new MonthClosingService.Submission(
                                UUID.randomUUID().toString(),
                                preview.version(),
                                preview.token(),
                                "资金月结核对"),
                        END)
                .contextWrite(fixture.fixture.allowed())
                .block(WAIT);
    }

    void closeTopup() {
        var batch = confirm(handoverRequest()).batch();
        handover.action(
                        project,
                        batch.getId(),
                        "APPROVE",
                        new HandoverService.Action(
                                UUID.randomUUID().toString(), batch.getVersion(), "原存入审核"),
                        NOW)
                .contextWrite(fixture.fixture.allowed())
                .block(WAIT);
        calendar.create(project, new AccountingCalendarRules.Input(2024, "NATURAL", 1))
                .contextWrite(fixture.fixture.allowed())
                .block(WAIT);
        month("2024-01", "CLOSE");
        var closed = month("2024-02", "CLOSE");
        assertEquals("100.01", closed.amount());
        assertEquals("100.01", closed.cashAmount());
        assertEquals(1, closed.sourceCount());
    }

    @Test
    void monthClosureProtectsOriginalCreditAndReopenKeepsDayHandover() {
        closeTopup();
        assertTrue(credit(entryId).getMonthClosed());
        assertTrue(credit(entryId).getDayClosed());
        money("100.01", accounts.findById(accountId).block(WAIT).getBalance());
        month("2024-02", "REOPEN");
        assertFalse(credit(entryId).getMonthClosed());
        assertTrue(credit(entryId).getDayClosed());
        money("100.01", accounts.findById(accountId).block(WAIT).getBalance());
    }

    @Test
    void reopeningDoesNotRestoreCreditAlreadyConsumedInNewOpenPeriod() {
        closeTopup();
        var next = END.plusDays(1);
        var input = fixture.debit(accounts.findById(accountId).block(WAIT), "REFUND", "20");
        input.setOccurredAt("2024-03-01T12:00:00");
        var preview =
                funds.preview(project, input, next)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        funds.execute(
                        project,
                        new FundsService.Submission(
                                UUID.randomUUID().toString(), preview.token(), input),
                        next)
                .contextWrite(fixture.fixture.allowed())
                .block(WAIT);
        month("2024-02", "REOPEN");
        money("80.01", accounts.findById(accountId).block(WAIT).getBalance());
        money("80.01", credit(entryId).getRemainingAmount());
        assertFalse(credit(entryId).getMonthClosed());
    }

    @Configuration
    @Import({
        HandoverFundPersistenceTest.Config.class,
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
