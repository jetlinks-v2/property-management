package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.*;

/** 原存入交账和撤销共用真实账户行锁，释放状态与credit保护一起回滚。 */
@SpringBootTest(
        classes = HandoverFundPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HandoverFundPersistenceTest extends FundRevokePersistenceTest {
    static final LocalDateTime NOW = DATE.atTime(16, 0);
    @Autowired HandoverService handover;
    @Autowired HandoverRecords records;
    @Autowired ReactiveRepository<HandoverEntity, String> batches;

    HandoverService.Submission handoverRequest() {
        var entry = entries.findById(entryId).block(WAIT);
        var i =
                new HandoverService.Input(
                        List.of(new HandoverSources.Item("FUND", entryId, entry.getVersion())),
                        DATE.toString(),
                        "原存入交账",
                        null,
                        null);
        var p =
                handover.preview(project, i, NOW)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        return new HandoverService.Submission(UUID.randomUUID().toString(), p.token(), i);
    }

    HandoverRecords.Detail confirm(HandoverService.Submission r) {
        return handover.confirm(project, r, NOW)
                .contextWrite(fixture.fixture.allowed())
                .block(WAIT);
    }

    @Test
    void originalTopupHandoverBlocksRevokeAndCancelRestoresEligibility() {
        var row = confirm(handoverRequest());
        assertEquals("100.01", row.batch().getCashAmount().toPlainString());
        assertTrue(credits.findById(entryId).block(WAIT).getDayClosed());
        fixture.fixture.rejects(service.preview(project, input(), DATE), 409);
        var action =
                new HandoverService.Action(
                        UUID.randomUUID().toString(), row.batch().getVersion(), "更正交账");
        handover.action(project, row.batch().getId(), "CANCEL", action, NOW)
                .contextWrite(fixture.fixture.allowed())
                .block(WAIT);
        assertFalse(credits.findById(entryId).block(WAIT).getDayClosed());
        assertNotNull(
                service.preview(project, input(), DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT));
        assertEquals(
                "100.01", accounts.findById(accountId).block(WAIT).getBalance().toPlainString());
    }

    @Test
    void negativeIndependentRefundIsSignedAndPaymentLinkedEntriesAreNotOffered() {
        var i = fixture.input("REFUND", "20");
        i.setAccountId(accountId);
        i.setVersion(accounts.findById(accountId).block(WAIT).getVersion());
        var returned = fixture.run(i);
        var e = returned.entry();
        var input =
                new HandoverService.Input(
                        List.of(new HandoverSources.Item("FUND", e.getId(), e.getVersion())),
                        DATE.toString(),
                        null,
                        null,
                        null);
        var p =
                handover.preview(project, input, NOW)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        assertEquals("-20.00", p.amount());
        assertEquals("-20.00", p.cashAmount());
        entries.createUpdate()
                .set("paymentId", "linked-payment")
                .where("id", e.getId())
                .execute()
                .block(WAIT);
        fixture.fixture.rejects(handover.preview(project, input, NOW), 409);
        var page =
                records.candidates(project, "FUND", new QueryParamEntity())
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        assertEquals(1, page.getTotal());
        assertEquals(entryId, page.getData().get(0).id());
    }

    @Test
    void failedAuditDoesNotLeaveOriginalTopupMarkedAsHanded() throws Exception {
        var req = handoverRequest();
        var db = HouseSettingPersistenceTest.DB;
        var name = "fund_handover_" + UUID.randomUUID().toString().replace("-", "");
        assertEquals(
                0,
                db.execInContainer(
                                "psql",
                                "-U",
                                "postgres",
                                "-d",
                                "setting_test",
                                "-c",
                                "ALTER TABLE pm_fin_handover_log ADD CONSTRAINT "
                                        + name
                                        + " CHECK (project_id <> '"
                                        + project
                                        + "') NOT VALID")
                        .getExitCode());
        try {
            StepVerifier.create(
                            handover.confirm(project, req, NOW)
                                    .contextWrite(fixture.fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            assertFalse(credits.findById(entryId).block(WAIT).getDayClosed());
        } finally {
            db.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_handover_log DROP CONSTRAINT " + name);
        }
    }

    @Configuration
    @Import({
        FundRevokePersistenceTest.Config.class,
        HandoverSources.class,
        HandoverRecords.class,
        HandoverService.class,
        DocumentQueryScope.class
    })
    static class Config {}
}
