package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.InvoiceCompanyPersistenceTest.auth;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson.JSON;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.calculation.AccountingCalendarRules;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 真实日期边界、持久CAS与末端审计故障，不把编辑当成简单覆盖字段。 */
@SpringBootTest(
        classes = AccountingCalendarEditPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AccountingCalendarEditPersistenceTest extends AccountingCalendarPersistenceTest {
    @Autowired AccountingCalendarChangeService changes;
    @Autowired ReactiveRepository<PaymentEntity, String> payments;
    @Autowired ReactiveRepository<RefundEntity, String> refunds;
    @Autowired ReactiveRepository<FundEntryEntity, String> funds;
    @Autowired ReactiveRepository<DocumentEntity, String> documents;
    @Autowired ReactiveRepository<HandoverEntity, String> handovers;
    @Autowired ReactiveRepository<ReceivableEntity, String> bills;

    List<AccountingPeriodEntity> natural() {
        return service.create(project, new AccountingCalendarRules.Input(2024, "NATURAL", null))
                .contextWrite(auth())
                .block(WAIT);
    }

    AccountingCalendarChangeService.Input edited(List<AccountingPeriodEntity> original) {
        var items =
                original.stream()
                        .map(
                                row ->
                                        new AccountingCalendarChangeService.Item(
                                                row.getAccountMonth(),
                                                row.getVersion(),
                                                row.getAccountMonth().equals("2024-02")
                                                        ? "2024-02-04"
                                                        : row.getStartDate(),
                                                row.getAccountMonth().equals("2024-01")
                                                        ? "2024-02-03"
                                                        : row.getEndDate()))
                        .toList();
        return new AccountingCalendarChangeService.Input(items, "调整开账边界");
    }

    @Test
    void validEditChangesBothSidesAndKeepsIdentitiesWithBeforeAndAfterAudit() {
        var original = natural();
        var after = changes.edit(project, 2024, edited(original)).contextWrite(auth()).block(WAIT);
        assertEquals(12, after.size());
        for (int index = 0; index < 12; index++) {
            assertEquals(original.get(index).getId(), after.get(index).getId());
            assertEquals(index < 2 ? 1L : 0L, after.get(index).getVersion());
        }
        assertEquals("2024-02-03", after.get(0).getEndDate());
        assertEquals("2024-02-04", after.get(1).getStartDate());
        assertEquals("MANUAL", after.get(0).getMode());
        var operation =
                logs.createQuery()
                        .where("projectId", project)
                        .and("action", "EDIT")
                        .fetchOne()
                        .block(WAIT);
        var audit = JSON.parseObject(operation.getDetailJson());
        assertEquals("调整开账边界", audit.getString("reason"));
        assertEquals("2024-01-31", audit.getJSONArray("before").getJSONObject(0).getString("end"));
        assertEquals("2024-02-03", audit.getJSONArray("after").getJSONObject(0).getString("end"));
        reject(changes.edit(project, 2024, edited(original)), 409);
        assertEquals(2, logs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void missingPeriodGapAndAnyStaleVersionRejectEntireYear() {
        var original = natural();
        var input = edited(original);
        reject(
                changes.edit(
                        project,
                        2024,
                        new AccountingCalendarChangeService.Input(
                                input.items().subList(0, 11), "缺期")),
                400);
        var bad = new ArrayList<>(input.items());
        var february = bad.get(1);
        bad.set(
                1,
                new AccountingCalendarChangeService.Item(
                        february.month(), february.version(), "2024-02-05", february.end()));
        reject(
                changes.edit(project, 2024, new AccountingCalendarChangeService.Input(bad, "日期空档")),
                400);
        bad = new ArrayList<>(input.items());
        var december = bad.get(11);
        bad.set(
                11,
                new AccountingCalendarChangeService.Item(
                        december.month(), 999L, december.start(), december.end()));
        reject(
                changes.edit(
                        project, 2024, new AccountingCalendarChangeService.Input(bad, "其他月份版本已变")),
                409);
        assertEquals(
                "2024-01-31",
                service.period(project, "2024-01").contextWrite(auth()).block(WAIT).getEndDate());
        assertEquals(1, logs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void concurrentEditsOnlyPublishOneYearAndOneEditAudit() {
        var input = edited(natural());
        var result =
                Flux.merge(
                                changes.edit(project, 2024, input).materialize(),
                                changes.edit(project, 2024, input).materialize())
                        .collectList()
                        .contextWrite(auth())
                        .block(WAIT);
        assertEquals(1, result.stream().filter(signal -> signal.isOnNext()).count());
        assertEquals(
                1,
                result.stream()
                        .filter(
                                signal ->
                                        signal.isOnError()
                                                && TaxItemPersistenceTest.status(
                                                        signal.getThrowable(), 409))
                        .count());
        assertEquals(
                1,
                logs.createQuery()
                        .where("projectId", project)
                        .and("action", "EDIT")
                        .count()
                        .block(WAIT));
    }

    @Test
    void closedBoundaryCannotChangeEvenWhenSubmittedInANewOpenPeriod() {
        var original = natural();
        periods.close(project, LocalDate.of(2024, 1, 31)).contextWrite(auth()).block(WAIT);
        reject(changes.edit(project, 2024, edited(original)), 409);
        assertEquals(
                "2024-01-31",
                service.period(project, "2024-01").contextWrite(auth()).block(WAIT).getEndDate());
        assertEquals(1, logs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-02-01T00:00", "2024-02-03T23:59:59.999999999"})
    void shiftingDatesWithExistingPaymentCannotReassignItsAccountingMonth(String date) {
        var input = edited(natural());
        payment(date);
        reject(changes.edit(project, 2024, input), 409);
        assertEquals(
                "2024-02-01",
                service.period(project, "2024-02").contextWrite(auth()).block(WAIT).getStartDate());
        assertEquals(1, logs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-01-10T12:00", "2024-02-04T00:00"})
    void existingPaymentOutsideTransferredDatesKeepsItsMonthAndDoesNotBlockEdit(String date) {
        var input = edited(natural());
        payment(date);
        var after = changes.edit(project, 2024, input).contextWrite(auth()).block(WAIT);
        assertEquals("2024-02-04", after.get(1).getStartDate());
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void editAuditFailureRollsBackEveryBoundaryAndVersion() throws Exception {
        var original = natural();
        var constraint = "calendar_edit_fail_" + UUID.randomUUID().toString().replace("-", "");
        var added =
                DB.execInContainer(
                        "psql",
                        "-U",
                        "postgres",
                        "-d",
                        "calendar_test",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-c",
                        "ALTER TABLE pm_fin_calendar_log ADD CONSTRAINT "
                                + constraint
                                + " CHECK (action <> 'EDIT' OR project_id <> '"
                                + project
                                + "')");
        assertEquals(0, added.getExitCode(), added.getStderr());
        try {
            StepVerifier.create(changes.edit(project, 2024, edited(original)).contextWrite(auth()))
                    .expectError()
                    .verify(WAIT);
            var retained = service.year(project, 2024).contextWrite(auth()).block(WAIT);
            assertEquals(
                    original.stream().map(AccountingPeriodEntity::getVersion).toList(),
                    retained.stream().map(AccountingPeriodEntity::getVersion).toList());
            assertEquals("2024-01-31", retained.get(0).getEndDate());
            assertEquals("2024-02-01", retained.get(1).getStartDate());
            assertEquals(1, logs.createQuery().where("projectId", project).count().block(WAIT));
        } finally {
            var removed =
                    DB.execInContainer(
                            "psql",
                            "-U",
                            "postgres",
                            "-d",
                            "calendar_test",
                            "-c",
                            "ALTER TABLE pm_fin_calendar_log DROP CONSTRAINT " + constraint);
            assertEquals(0, removed.getExitCode(), removed.getStderr());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"REFUND", "FUND", "DOCUMENT", "HANDOVER", "RECEIVABLE"})
    void eachFinancialFactProtectsTheTransferredDate(String type) {
        var input = edited(natural());
        source(type).block(WAIT);
        StepVerifier.create(changes.edit(project, 2024, input).contextWrite(auth()))
                .expectErrorMatches(
                        error ->
                                TaxItemPersistenceTest.status(error, 409)
                                        && error.getMessage()
                                                .contains("accounting_calendar_in_use"))
                .verify(WAIT);
        assertEquals(
                "2024-01-31",
                service.period(project, "2024-01").contextWrite(auth()).block(WAIT).getEndDate());
        assertEquals(1, logs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void closedStatusAndUnchangedYearCannotCreateAnEditAudit() {
        var original = natural();
        var unchanged =
                new AccountingCalendarChangeService.Input(
                        original.stream()
                                .map(
                                        row ->
                                                new AccountingCalendarChangeService.Item(
                                                        row.getAccountMonth(),
                                                        row.getVersion(),
                                                        row.getStartDate(),
                                                        row.getEndDate()))
                                .toList(),
                        "无变化");
        reject(changes.edit(project, 2024, unchanged), 400);
        rows.createUpdate()
                .set("status", "CLOSED")
                .where("id", original.get(0).getId())
                .execute()
                .block(WAIT);
        reject(changes.edit(project, 2024, edited(original)), 409);
        assertEquals(1, logs.createQuery().where("projectId", project).count().block(WAIT));
    }

    private Mono<Void> source(String type) {
        return switch (type) {
            case "REFUND" -> {
                var row = initialized(new RefundEntity());
                row.setProjectId(project);
                row.setCompanyId("company");
                row.setCompanyName("测试公司");
                row.setRequestFingerprint("0".repeat(64));
                row.setStatus("SUCCEEDED");
                row.setReceiverName("客户");
                row.setRefundedAt("2024-02-02T12:00");
                row.setAmount(new BigDecimal("10.00"));
                row.setMethod("cash");
                row.setMethodText("现金");
                row.setReason("退回费用");
                yield refunds.insert(row).then();
            }
            case "FUND" -> {
                var row = initialized(new FundEntryEntity());
                row.setProjectId(project);
                row.setSpaceId("room");
                row.setAccountId("account");
                row.setTransactionId("transaction");
                row.setAction("TOPUP");
                row.setCompanyId("company");
                row.setCompanyName("测试公司");
                row.setFullName("园区/101");
                row.setCustomerName("客户");
                row.setSubjectName("物业费");
                row.setStandardName("标准");
                row.setOccurredAt("2024-02-02T12:00");
                row.setPayerName("客户");
                row.setMethod("cash");
                row.setMethodText("现金");
                row.setReason("存入");
                row.setRequestFingerprint("0".repeat(64));
                row.setInputSnapshotJson("{}");
                row.setAmount(new BigDecimal("10.00"));
                row.setCashAmount(new BigDecimal("10.00"));
                row.setBeforeBalance(BigDecimal.ZERO);
                row.setAfterBalance(new BigDecimal("10.00"));
                yield funds.insert(row).then();
            }
            case "DOCUMENT" -> {
                var row = initialized(new DocumentEntity());
                row.setTenantId("");
                row.setProjectId(project);
                row.setCreatorId("operator");
                row.setDocumentType("INVOICE");
                row.setChannel("SIMULATED");
                row.setStatus("ISSUED");
                row.setDocumentNumber(UUID.randomUUID().toString());
                row.setRootDocumentId(row.getId());
                row.setReopenCount(0);
                row.setCompanyId("company");
                row.setCompanyName("测试公司");
                row.setSellerTaxNum("tax-number");
                row.setCustomerId("customer");
                row.setCustomerName("客户");
                row.setBuyerName("客户");
                row.setIssueScene("AFTER_PAYMENT");
                row.setIssuedAt("2024-02-02T12:00");
                row.setRequestFingerprint("0".repeat(64));
                row.setBuyerJson("{}");
                row.setRuleJson("{}");
                row.setAmount(new BigDecimal("10.00"));
                row.setTaxAmount(BigDecimal.ZERO);
                row.setNetAmount(new BigDecimal("10.00"));
                yield documents.insert(row).then();
            }
            case "HANDOVER" -> {
                var row = initialized(new HandoverEntity());
                row.setTenantId("");
                row.setProjectId(project);
                row.setRequestFingerprint("0".repeat(64));
                row.setStatus("APPROVED");
                row.setClosingDate("2024-02-02");
                row.setSubmittedAt("2024-02-02T12:00");
                row.setSubmittedBy("operator");
                row.setSourceCount(1);
                row.setAmount(new BigDecimal("10.00"));
                row.setCashAmount(new BigDecimal("10.00"));
                yield handovers.insert(row).then();
            }
            case "RECEIVABLE" -> {
                var row = initialized(new ReceivableEntity());
                row.setBillKey(row.getId());
                row.setProjectId(project);
                row.setSpaceId("room");
                row.setFullName("园区/101");
                row.setSubjectId("subject");
                row.setSubjectName("物业费");
                row.setStandardId("standard");
                row.setStandardName("标准");
                row.setConfigVersion(0);
                row.setCustomerId("customer");
                row.setCustomerRelationshipId("relationship");
                row.setCustomerName("客户");
                row.setPeriodStart("2024-01-01");
                row.setPeriodEnd("2024-01-31");
                row.setCalcStartDate("2024-01-01");
                row.setCalcEndDate("2024-01-31");
                row.setDueDate("2024-02-02");
                row.setAccruedAsOfDate("2024-01-31");
                row.setCalculatedAsOfDate("2024-01-31");
                row.setPeriodStatus("CLOSED");
                row.setSnapshotJson("{}");
                row.setMoneyScale(2);
                row.setFrozen(false);
                row.setPaymentLocked(false);
                row.setBankCollectionLocked(false);
                row.setLateFeeLocked(false);
                row.setFinancialClosed(false);
                row.setContractReceivableAmount(new BigDecimal("10.00"));
                row.setAccruedReceivableAmount(new BigDecimal("10.00"));
                row.setAdjustmentAmount(BigDecimal.ZERO);
                row.setAssessmentAmount(BigDecimal.ZERO);
                row.setTemporaryDispatchAmount(BigDecimal.ZERO);
                row.setFeeReductionAmount(BigDecimal.ZERO);
                row.setAccruedLateFeeAmount(BigDecimal.ZERO);
                row.setLateFeeReductionAmount(BigDecimal.ZERO);
                row.setFinalLateFeeAmount(BigDecimal.ZERO);
                row.setActualReceivableAmount(new BigDecimal("10.00"));
                row.setPaidAmount(BigDecimal.ZERO);
                row.setArrears(new BigDecimal("10.00"));
                row.setInvoiceOccupiedAmount(BigDecimal.ZERO);
                row.setPaymentOccupiedAmount(BigDecimal.ZERO);
                yield bills.insert(row).then();
            }
            default -> throw new AssertionError(type);
        };
    }

    @Test
    void damagedPersistedCalendarIsAVisibleConflictWithoutOverwritingIt() {
        var original = natural();
        rows.createUpdate()
                .set("endDate", "2024-02-30")
                .where("id", original.get(0).getId())
                .execute()
                .block(WAIT);
        StepVerifier.create(changes.edit(project, 2024, edited(original)).contextWrite(auth()))
                .expectErrorMatches(
                        error ->
                                TaxItemPersistenceTest.status(error, 409)
                                        && error.getMessage()
                                                .contains("accounting_calendar_conflict"))
                .verify(WAIT);
        assertEquals("2024-02-30", rows.findById(original.get(0).getId()).block(WAIT).getEndDate());
        assertEquals(1, logs.createQuery().where("projectId", project).count().block(WAIT));
    }

    private <T extends FinanceEntity> T initialized(T row) {
        row.setId(UUID.randomUUID().toString());
        InvoiceCompanyService.init(
                row, auth().get(org.hswebframework.web.authorization.Authentication.class));
        return row;
    }

    private void payment(String date) {
        var row = new PaymentEntity();
        row.setId(UUID.randomUUID().toString());
        InvoiceCompanyService.init(
                row, auth().get(org.hswebframework.web.authorization.Authentication.class));
        row.setProjectId(project);
        row.setCompanyId("company");
        row.setCompanyName("测试公司");
        row.setRequestFingerprint("0".repeat(64));
        row.setStatus("SUCCEEDED");
        row.setPayerName("客户");
        row.setPaidAt(date);
        row.setAmount(new BigDecimal("100.00"));
        payments.insert(row).block(WAIT);
    }

    @Configuration
    @Import({
        AccountingCalendarPersistenceTest.Config.class,
        AccountingCalendarChangeService.class,
        AccountingCalendarUsage.class
    })
    static class Config {}
}
