package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.receivable.*;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 真实 PostgreSQL 验证申请不改账、审批事务、并发/过期以及跨日重新试算保护。 */
class ReceivableChangeAuditTest extends HouseSettingPersistenceTest {
    private ReceivableEntity source() {
        var result =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        return bills.createQuery()
                .where("settingId", result.setting().getId())
                .fetchOne()
                .block(WAIT);
    }

    private AdjustmentInput adjustment(ReceivableEntity bill) {
        var input = new AdjustmentInput();
        input.setVersion(bill.getVersion());
        input.setAdjustmentMode("DIRECT");
        input.setDirectDirection("INCREASE");
        input.setDirectAmount("10.00");
        input.setReason("补充费用");
        input.setAttachmentFileIds(List.of("file-id"));
        return input;
    }

    private ReceivableChangeEntity request(ReceivableEntity bill) {
        var input = adjustment(bill);
        var preview =
                adjustments
                        .preview(project, bill.getId(), input, DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        return changeAudit
                .requestAdjustment(
                        project,
                        bill.getId(),
                        new ReceivableAdjustmentService.SubmitInput(input, preview.token()),
                        DATE)
                .contextWrite(allowed())
                .block(WAIT);
    }

    private ReceivableChangeAuditService.Decision decision(ReceivableChangeEntity row) {
        return new ReceivableChangeAuditService.Decision(row.getVersion(), "核对确认");
    }

    @Test
    void requestOnlyStagesThenApprovePublishesExactlyOnce() {
        var source = source();
        var request = request(source);
        assertEquals("PENDING", request.getStatus());
        assertEquals(source.getVersion(), bills.findById(source.getId()).block(WAIT).getVersion());
        assertEquals(
                source.getActualReceivableAmount(),
                bills.findById(source.getId()).block(WAIT).getActualReceivableAmount());
        var detail =
                changeAudit.detail(project, request.getId()).contextWrite(allowed()).block(WAIT);
        assertEquals("10.00", detail.preview().getString("delta"));
        assertEquals("10.00", detail.input().getString("directAmount"));
        var results =
                Flux.merge(
                                changeAudit
                                        .decide(
                                                project,
                                                request.getId(),
                                                decision(request),
                                                true,
                                                DATE)
                                        .map(v -> "OK")
                                        .onErrorReturn("CONFLICT"),
                                changeAudit
                                        .decide(
                                                project,
                                                request.getId(),
                                                decision(request),
                                                true,
                                                DATE)
                                        .map(v -> "OK")
                                        .onErrorReturn("CONFLICT"))
                        .collectList()
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(1, results.stream().filter("OK"::equals).count());
        assertEquals(
                source.getActualReceivableAmount().add(new BigDecimal("10.00")),
                bills.findById(source.getId()).block(WAIT).getActualReceivableAmount());
        assertEquals("APPROVED", changeRows.findById(request.getId()).block(WAIT).getStatus());
        assertEquals(
                1,
                receivableLogs
                        .createQuery()
                        .where("receivableId", source.getId())
                        .and("operation", "ADJUST")
                        .count()
                        .block(WAIT));
    }

    @Test
    void rejectionPreservesSourceAndAllowsNewRequestAfterPeriodClose() {
        var source = source();
        var request = request(source);
        financialPeriods.close(project, DATE).contextWrite(allowed()).block(WAIT);
        reject(
                changeAudit.decide(project, request.getId(), decision(request), true, DATE),
                "period_closed");
        var rejected =
                changeAudit
                        .decide(project, request.getId(), decision(request), false, DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("REJECTED", rejected.getStatus());
        assertEquals("核对确认", rejected.getReviewReason());
        assertEquals(source.getVersion(), bills.findById(source.getId()).block(WAIT).getVersion());
    }

    @Test
    void duplicatePendingAndStaleSourceFailWithoutPublishing() {
        var source = source();
        var request = request(source);
        var input = adjustment(source);
        var preview =
                adjustments
                        .preview(project, source.getId(), input, DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        reject(
                changeAudit.requestAdjustment(
                        project,
                        source.getId(),
                        new ReceivableAdjustmentService.SubmitInput(input, preview.token()),
                        DATE),
                "change_pending");
        receivables
                .change(
                        project,
                        source.getId(),
                        new ReceivableService.ChangeInput(source.getVersion(), "冻结"),
                        "FREEZE")
                .contextWrite(allowed())
                .block(WAIT);
        reject(
                changeAudit.decide(project, request.getId(), decision(request), true, DATE),
                "change_source_changed");
        assertEquals("PENDING", changeRows.findById(request.getId()).block(WAIT).getStatus());
        assertEquals(
                0,
                receivableLogs
                        .createQuery()
                        .where("receivableId", source.getId())
                        .and("operation", "ADJUST")
                        .count()
                        .block(WAIT));
        changeAudit
                .decide(project, request.getId(), decision(request), false, DATE)
                .contextWrite(allowed())
                .block(WAIT);
    }

    @Test
    void dateSplitStaysPendingThenArchivesSourceWithTwoChildren() {
        var source = source();
        var input =
                new SplitInput(
                        source.getVersion(), LocalDate.of(2024, 2, 12), 2, "link", "link", "变更入住人");
        var preview =
                splits.preview(project, source.getId(), input, DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var request =
                changeAudit
                        .requestSplit(
                                project,
                                source.getId(),
                                new ReceivableSplitService.Submit(input, preview.token()),
                                DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertFalse(bills.findById(source.getId()).block(WAIT).getSplit());
        assertEquals(
                0,
                bills.createQuery()
                        .where("sourceReceivableId", source.getId())
                        .count()
                        .block(WAIT));
        changeAudit
                .decide(project, request.getId(), decision(request), true, DATE)
                .contextWrite(allowed())
                .block(WAIT);
        assertTrue(bills.findById(source.getId()).block(WAIT).getSplit());
        var children =
                bills.createQuery()
                        .where("sourceReceivableId", source.getId())
                        .fetch()
                        .collectList()
                        .block(WAIT);
        assertEquals(2, children.size());
        assertEquals(
                source.getActualReceivableAmount(),
                children.stream()
                        .map(ReceivableEntity::getActualReceivableAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void nextDayAmountChangesRequireNewRequestEvenWhenSourceVersionHasNotChanged() {
        var source = source();
        var request = request(source);
        reject(
                changeAudit.decide(
                        project, request.getId(), decision(request), true, DATE.plusDays(1)),
                "change_preview_changed");
        assertEquals("PENDING", changeRows.findById(request.getId()).block(WAIT).getStatus());
        assertEquals(source.getVersion(), bills.findById(source.getId()).block(WAIT).getVersion());
    }

    @Test
    void requestWrongTokenHasNoDraftAndWrongProjectCannotReadOrReview() {
        var source = source();
        reject(
                changeAudit.requestAdjustment(
                        project,
                        source.getId(),
                        new ReceivableAdjustmentService.SubmitInput(adjustment(source), "invalid"),
                        DATE),
                "adjustment_preview_expired");
        assertEquals(0, changeRows.createQuery().where("projectId", project).count().block(WAIT));
        var request = request(source);
        reject(changeAudit.detail(project + "other", request.getId()), "change_not_found");
        reject(
                changeAudit.decide(
                        project + "other", request.getId(), decision(request), true, DATE),
                "change_not_found");
    }

    private void reject(Mono<?> flow, String code) {
        StepVerifier.create(flow.contextWrite(allowed()))
                .expectErrorMatches(
                        error -> error.getMessage() != null && error.getMessage().contains(code))
                .verify(WAIT);
    }

    @Test
    void dueCloseIsBlockedByPendingRequestAndCanRetryAfterRejection() {
        var source = source();
        var request = request(source);
        var date = LocalDate.of(2024, 3, 1);
        reject(
                generation.scheduled(
                        project,
                        source.getSettingId(),
                        date,
                        org.jetlinks.pro.property.finance.setting.BillingTaskKind.CLOSE),
                "change_pending");
        assertEquals("OPEN", bills.findById(source.getId()).block(WAIT).getPeriodStatus());
        changeAudit
                .decide(project, request.getId(), decision(request), false, date)
                .contextWrite(allowed())
                .block(WAIT);
        generation
                .scheduled(
                        project,
                        source.getSettingId(),
                        date,
                        org.jetlinks.pro.property.finance.setting.BillingTaskKind.CLOSE)
                .block(WAIT);
        assertEquals("CLOSED", bills.findById(source.getId()).block(WAIT).getPeriodStatus());
    }

    @Test
    void historicalWithoutSettingGetsDailyLateFeeAndTaskRetriesWithoutDuplicateAmounts() {
        var source = source();
        rows.createUpdate()
                .set("deleted", true)
                .where("id", source.getSettingId())
                .execute()
                .block(WAIT);
        var snapshot = com.alibaba.fastjson.JSON.parseObject(source.getSnapshotJson());
        var standard = snapshot.getJSONObject("standard");
        standard.put("isDelay", 1);
        standard.put("delayCalcForm", 0);
        standard.put("delayRoundType", 0);
        standard.put("rateAmount", "1.00");
        standard.put("delayBaseType", "CURRENT_RECEIVABLE");
        bills.createUpdate()
                .setNull("settingId")
                .set("sourceType", "HISTORICAL_IMPORT")
                .set("tenantId", "")
                .set("snapshotJson", snapshot.toJSONString())
                .set("periodStatus", "CLOSED")
                .set("dueDate", DATE.toString())
                .where("id", source.getId())
                .execute()
                .block(WAIT);
        var day = DATE.plusDays(3);
        var results =
                tasks.scan(org.jetlinks.pro.property.finance.setting.BillingTaskKind.LATE_FEE, day)
                        .block(WAIT);
        assertTrue(results.succeeded() > 0);
        var after = bills.findById(source.getId()).block(WAIT);
        assertEquals(new BigDecimal("3.00"), after.getAccruedLateFeeAmount());
        assertEquals(source.getContractReceivableAmount(), after.getContractReceivableAmount());
        assertEquals(
                source.getActualReceivableAmount().add(new BigDecimal("3.00")),
                after.getActualReceivableAmount());
        tasks.scan(org.jetlinks.pro.property.finance.setting.BillingTaskKind.LATE_FEE, day)
                .block(WAIT);
        assertEquals(after.getVersion(), bills.findById(source.getId()).block(WAIT).getVersion());
        var task =
                taskRows.createQuery()
                        .where("receivableId", source.getId())
                        .and("businessDate", day.toString())
                        .fetchOne()
                        .block(WAIT);
        assertEquals("SUCCEEDED", task.getStatus());
        assertNull(task.getSettingId());
        // 人工重试恢复同一历史任务身份；同日重算为替换累计值，不产生复利。
        taskRows.createUpdate()
                .set("status", "FAILED")
                .where("id", task.getId())
                .execute()
                .block(WAIT);
        tasks.retry(project, task.getId()).contextWrite(allowed()).block(WAIT);
        tasks.resume().block(WAIT);
        assertEquals("SUCCEEDED", taskRows.findById(task.getId()).block(WAIT).getStatus());
        assertEquals(
                after.getActualReceivableAmount(),
                bills.findById(source.getId()).block(WAIT).getActualReceivableAmount());
    }

    @Test
    void wrongTenantHistoricalTaskFailsBeforeAnyFinancialWrite() {
        var source = source();
        bills.createUpdate()
                .setNull("settingId")
                .set("tenantId", "tenant-A")
                .where("id", source.getId())
                .execute()
                .block(WAIT);
        reject(
                generation.scheduledReceivable(
                        project,
                        source.getId(),
                        DATE,
                        org.jetlinks.pro.property.finance.setting.BillingTaskKind.LATE_FEE),
                "task_tenant_invalid");
        assertEquals(source.getVersion(), bills.findById(source.getId()).block(WAIT).getVersion());
    }

    @Test
    void finalAuditFailureRollsBackAppliedAdjustmentAndReviewStatus() throws Exception {
        var source = source();
        var request = request(source);
        String constraint =
                "change_audit_" + java.util.UUID.randomUUID().toString().replace("-", "");
        var ddl =
                DB.execInContainer(
                        "psql",
                        "-U",
                        "postgres",
                        "-d",
                        "setting_test",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-c",
                        "ALTER TABLE pm_fin_receivable_log ADD CONSTRAINT "
                                + constraint
                                + " CHECK (operation <> 'CHANGE_APPROVE' OR receivable_id <> '"
                                + source.getId()
                                + "')");
        assertEquals(0, ddl.getExitCode());
        try {
            StepVerifier.create(
                            changeAudit
                                    .decide(project, request.getId(), decision(request), true, DATE)
                                    .contextWrite(allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    source.getVersion(), bills.findById(source.getId()).block(WAIT).getVersion());
            assertEquals(
                    source.getActualReceivableAmount(),
                    bills.findById(source.getId()).block(WAIT).getActualReceivableAmount());
            assertEquals("PENDING", changeRows.findById(request.getId()).block(WAIT).getStatus());
            assertEquals(
                    0,
                    receivableLogs
                            .createQuery()
                            .where("receivableId", source.getId())
                            .and("operation", "ADJUST")
                            .count()
                            .block(WAIT));
        } finally {
            assertEquals(
                    0,
                    DB.execInContainer(
                                    "psql",
                                    "-U",
                                    "postgres",
                                    "-d",
                                    "setting_test",
                                    "-c",
                                    "ALTER TABLE pm_fin_receivable_log DROP CONSTRAINT "
                                            + constraint)
                            .getExitCode());
        }
    }

    @Test
    void spacePermissionIsRecheckedForDetailAndApprovalAndMissingReasonCannotPublish() {
        var source = source();
        var request = request(source);
        var denied = org.mockito.Mockito.mock(org.jetlinks.pro.assets.AssetsHolder.class);
        org.mockito.Mockito.when(
                        denied.assertPermission(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyCollection(),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.error(FinanceScope.error("forbidden", 403)));
        var context = allowed().put(org.jetlinks.pro.assets.AssetsHolder.class, denied);
        StepVerifier.create(changeAudit.detail(project, request.getId()).contextWrite(context))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(WAIT);
        StepVerifier.create(
                        changeAudit
                                .decide(project, request.getId(), decision(request), true, DATE)
                                .contextWrite(context))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(WAIT);
        reject(
                changeAudit.decide(
                        project,
                        request.getId(),
                        new ReceivableChangeAuditService.Decision(request.getVersion(), " "),
                        true,
                        DATE),
                "change_invalid");
        assertEquals(source.getVersion(), bills.findById(source.getId()).block(WAIT).getVersion());
        assertEquals("PENDING", changeRows.findById(request.getId()).block(WAIT).getStatus());
    }

    @Test
    void reviewMigrationCanBeRepeatedAndBackfillsOnlyKnownTenantOwnership() throws Exception {
        var source = source();
        bills.createUpdate().setNull("tenantId").where("id", source.getId()).execute().block(WAIT);
        var script =
                java.nio.file.Files.readString(
                        java.nio.file.Path.of("../sql/migrate-receivable-change-review.sql"));
        for (int i = 0; i < 2; i++) {
            var result =
                    DB.execInContainer(
                            "psql",
                            "-U",
                            "postgres",
                            "-d",
                            "setting_test",
                            "-v",
                            "ON_ERROR_STOP=1",
                            "-c",
                            script);
            assertEquals(0, result.getExitCode(), result.getStderr());
        }
        assertEquals("", bills.findById(source.getId()).block(WAIT).getTenantId());
        assertEquals(
                source.getActualReceivableAmount(),
                bills.findById(source.getId()).block(WAIT).getActualReceivableAmount());
    }

    @Test
    void migrationRejectsUnresolvedHistoricalTenantInsteadOfGuessingDefault() throws Exception {
        var source = source();
        var unresolved = project + "-unresolved";
        bills.createUpdate()
                .setNull("tenantId")
                .setNull("settingId")
                .set("projectId", unresolved)
                .where("id", source.getId())
                .execute()
                .block(WAIT);
        try {
            var script =
                    java.nio.file.Files.readString(
                            java.nio.file.Path.of("../sql/migrate-receivable-change-review.sql"));
            var result =
                    DB.execInContainer(
                            "psql",
                            "-U",
                            "postgres",
                            "-d",
                            "setting_test",
                            "-v",
                            "ON_ERROR_STOP=1",
                            "-c",
                            script);
            assertNotEquals(0, result.getExitCode());
            assertTrue(result.getStderr().contains("tenant ownership is unresolved"));
            assertNull(bills.findById(source.getId()).block(WAIT).getTenantId());
        } finally {
            bills.createUpdate()
                    .set("tenantId", "")
                    .set("settingId", source.getSettingId())
                    .set("projectId", project)
                    .where("id", source.getId())
                    .execute()
                    .block(WAIT);
        }
    }
}
