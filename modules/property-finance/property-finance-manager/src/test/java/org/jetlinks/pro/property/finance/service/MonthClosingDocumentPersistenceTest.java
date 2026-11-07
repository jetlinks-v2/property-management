package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.WAIT;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.calculation.AccountingCalendarRules;
import org.jetlinks.pro.property.finance.entity.MonthClosingMemberEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import java.time.LocalDate;
import java.util.*;

@SpringBootTest(
        classes = MonthClosingDocumentPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MonthClosingDocumentPersistenceTest extends DocumentIssuePersistenceTest {
    @Autowired MonthClosingService closing;
    @Autowired AccountingCalendarService calendar;
    @Autowired ReactiveRepository<MonthClosingMemberEntity, String> members;

    @Test
    void closedDocumentKeepsMembershipWhileNewPeriodRedInvoiceRemainsLegal() {
        var bill = documentBill();
        var invoice =
                issue(submission(input(List.of(item("RECEIVABLE", bill.getId(), "50")))))
                        .document();
        var end = LocalDate.of(2024, 2, 29);
        calendar.create(project, new AccountingCalendarRules.Input(2024, "NATURAL", 1))
                .contextWrite(fixture.allowed())
                .block(WAIT);
        MonthClosingService.Result closed = null;
        for (var month : List.of("2024-01", "2024-02")) {
            var preview =
                    closing.preview(project, month, "CLOSE", end)
                            .contextWrite(fixture.allowed())
                            .block(WAIT);
            closed =
                    closing.submit(
                                    project,
                                    month,
                                    "CLOSE",
                                    new MonthClosingService.Submission(
                                            UUID.randomUUID().toString(),
                                            preview.version(),
                                            preview.token(),
                                            "票据月结"),
                                    end)
                            .contextWrite(fixture.allowed())
                            .block(WAIT);
        }
        assertEquals(
                1,
                members.createQuery()
                        .where("operationId", closed.operationId())
                        .and("sourceType", "DOCUMENT")
                        .and("sourceId", invoice.getId())
                        .count()
                        .block(WAIT));
        var red =
                reverse.reverse(
                                project,
                                "INVOICE",
                                invoice.getId(),
                                reversal(invoice, "下期红冲"),
                                end.plusDays(1).atTime(10, 0))
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .document();
        assertEquals("-50.00", red.getAmount().toPlainString());
        assertEquals(
                0,
                members.createQuery()
                        .where("operationId", closed.operationId())
                        .and("sourceId", red.getId())
                        .count()
                        .block(WAIT));
        var preview =
                closing.preview(project, "2024-02", "REOPEN", end.plusDays(1))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        closing.submit(
                        project,
                        "2024-02",
                        "REOPEN",
                        new MonthClosingService.Submission(
                                UUID.randomUUID().toString(),
                                preview.version(),
                                preview.token(),
                                "反结核对"),
                        end.plusDays(1))
                .contextWrite(fixture.allowed())
                .block(WAIT);
        assertEquals(
                "0.00",
                bills.findById(bill.getId())
                        .block(WAIT)
                        .getInvoiceOccupiedAmount()
                        .toPlainString());
        assertEquals(
                "-50.00",
                documentRows.findById(red.getId()).block(WAIT).getAmount().toPlainString());
    }

    @Configuration
    @Import({
        DocumentIssuePersistenceTest.Config.class,
        MonthClosingPreparation.class,
        MonthClosingService.class,
        MonthClosingSources.class,
        AccountingCalendarService.class,
        AccountingCalendarRules.class,
        HandoverSources.class
    })
    static class Config {}
}
