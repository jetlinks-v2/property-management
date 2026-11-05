package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.property.finance.document.DocumentIssueInput;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import reactor.core.publisher.Mono;

import java.util.*;

/** 核销是票据登记，不能改变资金；整批和末端审计失败使用真实PG验证原子回滚。 */
@SpringBootTest(
        classes = ReceiptVerificationPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReceiptVerificationPersistenceTest extends DocumentIssuePersistenceTest {
    @Autowired ReceiptVerificationService verification;

    DocumentEntity receipt() {
        var bill = documentBill();
        var original = input(List.of(item("RECEIVABLE", bill.getId(), "50")));
        var receipt =
                new DocumentIssueInput(
                        "RECEIPT",
                        original.companyId(),
                        original.buyer(),
                        null,
                        original.remark(),
                        original.items());
        return issue(submission(receipt)).document();
    }

    ReceiptVerificationService.Input verification(DocumentEntity... rows) {
        return new ReceiptVerificationService.Input(
                UUID.randomUUID().toString(),
                Arrays.stream(rows)
                        .map(r -> new ReceiptVerificationService.Item(r.getId(), r.getVersion()))
                        .toList(),
                "纸电票核对一致");
    }

    List<DocumentStore.Detail> verify(ReceiptVerificationService.Input input) {
        return verification
                .verify(project, input, NOW.plusHours(1))
                .contextWrite(fixture.allowed())
                .block(WAIT);
    }

    @Test
    void closedOperationDateBlocksNewVerificationButReplayAndNextOpenDayStillWork() {
        var first = receipt();
        var original = verification(first);
        verify(original);
        var next = receipt();
        var request = verification(next);
        periods.close(project, NOW.toLocalDate()).contextWrite(fixture.allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                verification
                        .verify(project, request, NOW.plusHours(1))
                        .contextWrite(fixture.allowed()));
        assertTrue(verify(original).get(0).document().getVerified());
        assertFalse(
                Boolean.TRUE.equals(documentRows.findById(next.getId()).block(WAIT).getVerified()));
        assertEquals(
                1,
                operations
                        .createQuery()
                        .where("projectId", project)
                        .and("action", "VERIFY")
                        .count()
                        .block(WAIT));
        var completed =
                verification
                        .verify(project, request, NOW.plusDays(1))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertTrue(completed.get(0).document().getVerified());
        assertEquals(
                2,
                operations
                        .createQuery()
                        .where("projectId", project)
                        .and("action", "VERIFY")
                        .count()
                        .block(WAIT));
        assertEquals(2, documentRows.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void batchRegistersOperatorWithoutChangingMoneyAndRetryRecoversAcrossDay() {
        var a = receipt();
        var b = receipt();
        var input = verification(b, a);
        var before =
                bills.createQuery().where("projectId", project).fetch().collectList().block(WAIT);
        var result = verify(input);
        assertEquals(2, result.size());
        for (var d : result) {
            assertTrue(d.document().getVerified());
            assertEquals("2024-02-15T15:00", d.document().getVerifiedAt());
            assertNotNull(d.document().getVerifiedBy());
            assertNotNull(d.document().getVerifiedName());
            assertEquals("纸电票核对一致", d.document().getVerificationRemark());
            assertEquals("ISSUED", d.document().getStatus());
            assertEquals("50.00", d.document().getAmount().toPlainString());
        }
        for (var row : before) {
            var after = bills.findById(row.getId()).block(WAIT);
            assertEquals(row.getPaidAmount(), after.getPaidAmount());
            assertEquals(row.getInvoiceOccupiedAmount(), after.getInvoiceOccupiedAmount());
            assertEquals(row.getVersion(), after.getVersion());
        }
        var recovered =
                verification
                        .verify(project, input, NOW.plusYears(1))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(
                result.stream().map(v -> v.document().getId()).toList(),
                recovered.stream().map(v -> v.document().getId()).toList());
        assertEquals(
                2,
                operations
                        .createQuery()
                        .where("projectId", project)
                        .and("action", "VERIFY")
                        .count()
                        .block(WAIT));
        fixture.rejects(
                verification.verify(
                        project,
                        new ReceiptVerificationService.Input(
                                input.requestId(), input.items(), "不同备注"),
                        NOW),
                409);
        fixture.rejects(verification.verify(project, verification(a), NOW.plusHours(2)), 409);
        fixture.rejects(
                reverse.reverse(
                        project,
                        "RECEIPT",
                        a.getId(),
                        reversal(
                                result.stream()
                                        .filter(v -> v.document().getId().equals(a.getId()))
                                        .findFirst()
                                        .orElseThrow()
                                        .document(),
                                "核销后作废"),
                        NOW.plusHours(2)),
                409);
    }

    @Test
    void rejectsWholeBatchForVoidedInvoiceStaleOrDuplicateEntry() {
        var a = receipt();
        var b = receipt();
        reverse(b, reversal(b, "误开"));
        fixture.rejects(verification.verify(project, verification(a, b), NOW.plusHours(2)), 409);
        assertFalse(
                Boolean.TRUE.equals(documentRows.findById(a.getId()).block(WAIT).getVerified()));
        var bill = documentBill();
        var invoice =
                issue(submission(input(List.of(item("RECEIVABLE", bill.getId(), "50")))))
                        .document();
        fixture.rejects(verification.verify(project, verification(invoice), NOW.plusHours(2)), 404);
        fixture.rejects(verification.verify(project, verification(a, a), NOW.plusHours(2)), 400);
        var badVersion =
                new ReceiptVerificationService.Input(
                        UUID.randomUUID().toString(),
                        List.of(new ReceiptVerificationService.Item(a.getId(), 99L)),
                        null);
        fixture.rejects(verification.verify(project, badVersion, NOW.plusHours(2)), 409);
        assertEquals(
                0,
                operations
                        .createQuery()
                        .where("projectId", project)
                        .and("action", "VERIFY")
                        .count()
                        .block(WAIT));
    }

    @Test
    void concurrentSameRequestAndConflictingActionsHaveOneOutcome() {
        var a = receipt();
        var request = verification(a);
        var both =
                Mono.zip(
                                verification.verify(project, request, NOW.plusHours(2)),
                                verification.verify(project, request, NOW.plusHours(2)))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(
                both.getT1().get(0).document().getId(), both.getT2().get(0).document().getId());
        assertEquals(
                1,
                operations
                        .createQuery()
                        .where("documentId", a.getId())
                        .and("action", "VERIFY")
                        .count()
                        .block(WAIT));
        var b = receipt();
        var race =
                Mono.zip(
                                verification
                                        .verify(project, verification(b), NOW.plusHours(2))
                                        .materialize(),
                                reverse.reverse(
                                                project,
                                                "RECEIPT",
                                                b.getId(),
                                                reversal(b, "并发误开"),
                                                NOW.plusHours(2))
                                        .materialize())
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertNotEquals(race.getT1().isOnNext(), race.getT2().isOnNext());
        var after = documentRows.findById(b.getId()).block(WAIT);
        assertFalse(Boolean.TRUE.equals(after.getVerified()) && "VOIDED".equals(after.getStatus()));
        if (Boolean.TRUE.equals(after.getVerified())) {
            assertEquals(
                    "ACTIVE",
                    occupationRows
                            .createQuery()
                            .where("documentId", b.getId())
                            .fetchOne()
                            .block(WAIT)
                            .getState());
        }
    }

    @Test
    void auditFailureRollsBackEveryReceiptAndRetryCanSucceed() throws Exception {
        var a = receipt();
        var b = receipt();
        var request = verification(a, b);
        var last =
                request.items().stream()
                        .map(ReceiptVerificationService.Item::id)
                        .sorted()
                        .reduce((x, y) -> y)
                        .orElseThrow();
        sql(
                "alter table pm_fin_doc_operation add constraint reject_verify check (document_id"
                        + " <> '"
                        + last
                        + "' or action <> 'VERIFY')");
        try {
            assertThrows(RuntimeException.class, () -> verify(request));
            for (var row : List.of(a, b)) {
                var after = documentRows.findById(row.getId()).block(WAIT);
                assertFalse(Boolean.TRUE.equals(after.getVerified()));
                assertNull(after.getVerifiedAt());
                assertEquals(row.getVersion(), after.getVersion());
            }
            assertEquals(
                    0,
                    operations
                            .createQuery()
                            .where("projectId", project)
                            .and("action", "VERIFY")
                            .count()
                            .block(WAIT));
        } finally {
            sql("alter table pm_fin_doc_operation drop constraint reject_verify");
        }
        assertEquals(2, verify(request).size());
    }

    @Test
    void anonymousCrossProjectTenantAndRevokedSpaceCannotVerifyOrReplay() {
        var a = receipt();
        var request = verification(a);
        fixture.rejects(verification.verify(UUID.randomUUID().toString(), request, NOW), 404);
        reactor.test.StepVerifier.create(
                        verification
                                .verify(project, request, NOW)
                                .contextWrite(
                                        org.jetlinks.pro.tenant.holder.TenantHolder.makeCurrent(
                                                "other"))
                                .contextWrite(fixture.allowed()))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 404))
                .verify(WAIT);
        reactor.test.StepVerifier.create(verification.verify(project, request, NOW))
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(WAIT);
        verify(request);
        var restricted = fixture.allowed();
        var holder = restricted.<AssetsHolder>get(AssetsHolder.class);
        when(holder.assertPermission(eq("space"), anyCollection(), any()))
                .thenReturn(Mono.error(FinanceScope.error("permission_denied", 403)));
        reactor.test.StepVerifier.create(
                        verification
                                .verify(project, request, NOW.plusYears(1))
                                .contextWrite(restricted))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
    }

    @Test
    void inputBoundariesAndRequestReuseCannotChangeSelectedReceipts() {
        var a = receipt();
        var b = receipt();
        var request = verification(a);
        fixture.rejects(
                verification.verify(
                        project,
                        new ReceiptVerificationService.Input(request.requestId(), List.of(), null),
                        NOW),
                400);
        fixture.rejects(
                verification.verify(
                        project,
                        new ReceiptVerificationService.Input(
                                request.requestId(),
                                java.util.stream.IntStream.range(0, 101)
                                        .mapToObj(
                                                i ->
                                                        new ReceiptVerificationService.Item(
                                                                "doc" + i, 0L))
                                        .toList(),
                                null),
                        NOW),
                400);
        fixture.rejects(
                verification.verify(
                        project,
                        new ReceiptVerificationService.Input(
                                request.requestId(), request.items(), "字".repeat(201)),
                        NOW),
                400);
        fixture.rejects(verification.verify(project, request, NOW.minusMinutes(1)), 409);
        verify(request);
        fixture.rejects(
                verification.verify(
                        project,
                        new ReceiptVerificationService.Input(
                                request.requestId(), verification(b).items(), request.remark()),
                        NOW.plusHours(1)),
                409);
        assertFalse(
                Boolean.TRUE.equals(documentRows.findById(b.getId()).block(WAIT).getVerified()));
    }

    @Test
    void httpVerifiesOnlyReceiptsAndExposesIndependentVerifyAction() throws Exception {
        var row = receipt();
        var controller =
                new org.jetlinks.pro.property.finance.web.ReceiptDocumentController(
                        preparation,
                        issue,
                        reverse,
                        null,
                        null,
                        verification,
                        new BillingJobProperties());
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                controller)
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(fixture.allowed()))
                        .build();
        var path = "/property/finance/projects/" + project + "/receipts/_verify";
        client.post()
                .uri(path)
                .bodyValue(verification(row))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].document.verified")
                .isEqualTo(true)
                .jsonPath("$[0].document.amount")
                .isEqualTo("50.00")
                .jsonPath("$[0].document.requestFingerprint")
                .doesNotExist();
        client.put()
                .uri(path)
                .bodyValue(verification(row))
                .exchange()
                .expectStatus()
                .isEqualTo(405);
        var action =
                org.jetlinks.pro.property.finance.web.ReceiptDocumentController.class
                        .getMethod("verify", String.class, ReceiptVerificationService.Input.class)
                        .getAnnotation(
                                org.hswebframework.web.authorization.annotation.ResourceAction
                                        .class);
        assertEquals("verify", action.id());
    }

    private void sql(String statement) throws Exception {
        var result =
                HouseSettingPersistenceTest.DB.execInContainer(
                        "psql", "-U", "postgres", "-d", "setting_test", "-c", statement);
        assertEquals(0, result.getExitCode(), result.getStderr());
    }

    @Configuration
    @Import({DocumentIssuePersistenceTest.Config.class, ReceiptVerificationService.class})
    static class Config {}
}
