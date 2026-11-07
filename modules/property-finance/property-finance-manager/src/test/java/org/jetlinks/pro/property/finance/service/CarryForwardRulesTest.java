package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.finance.carry.*;
import org.jetlinks.pro.property.finance.entity.FundAccountEntity;
import org.jetlinks.pro.property.finance.entity.ReceivableEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** 旧自动结转的调度与每笔减免规则，金额使用精确分值而非浮点数。 */
class CarryForwardRulesTest {
    CarryForwardPlanInput plan() {
        var p = new CarryForwardPlanInput();
        p.setName("物业预收自动结转");
        p.setSubjectIds(List.of("property"));
        p.setPeriodStart("2024-01-01");
        p.setPeriodEnd("2024-12-31");
        p.setFrequency("DAILY");
        p.setExecuteAt("08:30:00");
        p.setZone("Asia/Shanghai");
        p.setEnabled(true);
        p.setIncludeLateFee(false);
        p.setRemissionMode("NONE");
        return p;
    }

    static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void dailySlotBecomesDueOnlyAfterScheduledLocalTimeAndUsesConfiguredZone() {
        var p = plan();
        assertTrue(CarryForwardRules.due(p, Instant.parse("2024-02-15T00:29:59Z")).isEmpty());
        assertEquals(
                "2024-02-15T08:30:00",
                CarryForwardRules.due(p, Instant.parse("2024-02-15T00:30:00Z")).orElseThrow());
        assertEquals(
                "2024-02-15T08:30:00",
                CarryForwardRules.due(p, Instant.parse("2024-02-15T15:59:59Z")).orElseThrow());
        p.setEnabled(false);
        assertTrue(CarryForwardRules.due(p, Instant.parse("2024-02-15T01:00:00Z")).isEmpty());
    }

    @Test
    void weeklyMonthlyAndYearlySlotsPreserveRealCalendarRatherThanClampingDates() {
        var p = plan();
        p.setFrequency("WEEKLY");
        p.setExecuteDay("7");
        assertTrue(CarryForwardRules.due(p, Instant.parse("2024-02-18T01:00:00Z")).isPresent());
        assertTrue(CarryForwardRules.due(p, Instant.parse("2024-02-19T01:00:00Z")).isEmpty());
        p.setFrequency("MONTHLY");
        p.setExecuteDay("31");
        assertTrue(CarryForwardRules.due(p, Instant.parse("2024-02-29T01:00:00Z")).isEmpty());
        assertTrue(CarryForwardRules.due(p, Instant.parse("2024-03-31T01:00:00Z")).isPresent());
        p.setFrequency("YEARLY");
        p.setExecuteDay("02-29");
        assertTrue(CarryForwardRules.due(p, Instant.parse("2024-02-29T01:00:00Z")).isPresent());
        assertTrue(CarryForwardRules.due(p, Instant.parse("2025-02-28T01:00:00Z")).isEmpty());
    }

    @Test
    void rejectsBrokenDatesInjectedCronBadFrequencyAndUnboundedMoney() {
        var p = plan();
        p.setPeriodEnd("2023-12-31");
        assertThrows(BusinessException.class, () -> CarryForwardRules.validate(p));
        p.setPeriodEnd("2024-02-30");
        assertThrows(BusinessException.class, () -> CarryForwardRules.validate(p));
        p.setPeriodEnd("2024-12-31");
        p.setFrequency("MONTHLY");
        p.setExecuteDay("1,15");
        assertThrows(BusinessException.class, () -> CarryForwardRules.validate(p));
        p.setExecuteDay("0");
        assertThrows(BusinessException.class, () -> CarryForwardRules.validate(p));
        p.setFrequency("YEARLY");
        p.setExecuteDay("02-30");
        assertThrows(BusinessException.class, () -> CarryForwardRules.validate(p));
        p.setFrequency("DAILY");
        p.setExecuteDay(null);
        p.setRemissionMode("DISCOUNT");
        for (var invalid : List.of("0", "10.01", "-1", "8.111")) {
            p.setRemissionValue(d(invalid));
            assertThrows(BusinessException.class, () -> CarryForwardRules.validate(p));
        }
        p.setRemissionMode("FIXED");
        p.setRemissionValue(d("1000000000000000000.00"));
        assertThrows(BusinessException.class, () -> CarryForwardRules.validate(p));
    }

    @Test
    void discountMatchesOldPerBillHalfUpRoundingAndCannotRepeatAfterPartialCarry() {
        var p = plan();
        p.setRemissionMode("DISCOUNT");
        p.setRemissionValue(d("8"));
        var q = CarryForwardRules.quote(p, d("74.81"), d("0.00"), d("20.00"), false);
        assertEquals(d("14.96"), q.feeReduction());
        assertEquals(d("20.00"), q.amount());
        assertEquals(d("39.85"), q.remaining());
        var again = CarryForwardRules.quote(p, q.remaining(), d("0.00"), d("100.00"), true);
        assertEquals(d("0.00"), again.feeReduction());
        assertEquals(d("39.85"), again.amount());
        assertEquals(d("0.00"), again.remaining());
    }

    @Test
    void fixedRemissionIsPerBillCappedAtDebtAndCanSettleWithoutInventingCash() {
        var p = plan();
        p.setRemissionMode("FIXED");
        p.setRemissionValue(d("20.00"));
        var a = CarryForwardRules.quote(p, d("100.00"), d("0.00"), d("1000.00"), false);
        var b = CarryForwardRules.quote(p, d("80.00"), d("0.00"), d("1000.00"), false);
        assertEquals(d("20.00"), a.feeReduction());
        assertEquals(d("20.00"), b.feeReduction());
        p.setRemissionValue(d("50.00"));
        var all = CarryForwardRules.quote(p, d("35.00"), d("0.00"), d("0.00"), false);
        assertEquals(d("35.00"), all.feeReduction());
        assertEquals(d("0.00"), all.amount());
        assertEquals(d("0.00"), all.remaining());
    }

    @Test
    void insufficientZeroBalanceDoesNotGrantPartialRemissionWithoutAnyCarry() {
        var p = plan();
        p.setRemissionMode("FIXED");
        p.setRemissionValue(d("20.00"));
        var q = CarryForwardRules.quote(p, d("100.00"), d("0.00"), d("0.00"), false);
        assertEquals(d("0.00"), q.feeReduction());
        assertEquals(d("0.00"), q.amount());
        assertEquals(d("100.00"), q.remaining());
    }

    @Test
    void lateFeeSwitchKeepsExcludedLateFeeAndSplitsFullRemissionIntoRealComponents() {
        var p = plan();
        p.setRemissionMode("FIXED");
        p.setRemissionValue(d("200.00"));
        var excluded = CarryForwardRules.quote(p, d("110.00"), d("10.00"), d("0.00"), false);
        assertEquals(d("100.00"), excluded.feeReduction());
        assertEquals(d("0.00"), excluded.lateFeeReduction());
        assertEquals(d("10.00"), excluded.remaining());
        p.setIncludeLateFee(true);
        var included = CarryForwardRules.quote(p, d("110.00"), d("10.00"), d("0.00"), false);
        assertEquals(d("100.00"), included.feeReduction());
        assertEquals(d("10.00"), included.lateFeeReduction());
        assertEquals(d("0.00"), included.remaining());
    }

    @Test
    void principalFirstDebtAllocationAllowsLateFeeOnlyWhenSelected() {
        var p = plan();
        var q = CarryForwardRules.quote(p, d("5.00"), d("10.00"), d("100.00"), false);
        assertEquals(d("0.00"), q.amount());
        assertEquals(d("5.00"), q.remaining());
        p.setIncludeLateFee(true);
        assertEquals(
                d("5.00"),
                CarryForwardRules.quote(p, d("5.00"), d("10.00"), d("100.00"), false).amount());
    }

    @Test
    void retainsCentAccuracyBeyondJavascriptSafeIntegerAndRejectsCorruptInputs() {
        var p = plan();
        var q = CarryForwardRules.quote(p, d("9007199254740993.01"), d("0.00"), d("0.01"), false);
        assertEquals(d("9007199254740993.00"), q.remaining());
        assertThrows(
                BusinessException.class,
                () -> CarryForwardRules.quote(p, d("1.00"), d("0"), d("-0.01"), false));
        assertThrows(
                BusinessException.class,
                () -> CarryForwardRules.quote(p, d("1.001"), d("0"), d("1"), false));
    }

    @Test
    void tensDiscountLeavesAllDebtAndFractionalCentDiscountUsesOldPayableFirstRounding() {
        var p = plan();
        p.setRemissionMode("DISCOUNT");
        p.setRemissionValue(d("10"));
        var unchanged = CarryForwardRules.quote(p, d("0.03"), d("0"), d("1"), false);
        assertEquals(d("0.00"), unchanged.feeReduction());
        assertEquals(d("0.03"), unchanged.amount());
        p.setRemissionValue(d("5"));
        var half = CarryForwardRules.quote(p, d("0.03"), d("0"), d("1"), false);
        assertEquals(d("0.01"), half.feeReduction());
        assertEquals(d("0.02"), half.amount());
        p.setRemissionMode("FIXED");
        p.setRemissionValue(d("1"));
        assertEquals(
                d("1.00"),
                CarryForwardRules.quote(p, d("5"), d("0"), d("10"), false).feeReduction());
    }

    @Test
    void rejectsMissingScopeInvalidTimeAndDuplicateSubjectWithoutUsingPartialConfiguration() {
        var p = plan();
        p.setSubjectIds(List.of("property", "property"));
        assertThrows(BusinessException.class, () -> CarryForwardRules.validate(p));
        p.setSubjectIds(List.of("property"));
        p.setZone("not-a-zone");
        assertThrows(BusinessException.class, () -> CarryForwardRules.validate(p));
        p.setZone("Asia/Shanghai");
        p.setExecuteAt("25:00:00");
        assertThrows(BusinessException.class, () -> CarryForwardRules.validate(p));
        p.setExecuteAt("08:30:00");
        p.setFrequency("UNSUPPORTED");
        assertThrows(BusinessException.class, () -> CarryForwardRules.validate(p));
        p.setFrequency("DAILY");
        p.setRemissionMode("UNKNOWN");
        assertThrows(BusinessException.class, () -> CarryForwardRules.validate(p));
    }

    @Test
    void standardSelectionRejectsDuplicatesAndRetainsLegacyUnfilteredScope() {
        var p = plan();
        assertTrue(CarryForwardRules.includesStandard(p, "any"));
        p.setStandardIds(List.of());
        assertTrue(CarryForwardRules.includesStandard(p, "any"));
        p.setStandardIds(List.of("chosen"));
        assertTrue(CarryForwardRules.includesStandard(p, "chosen"));
        assertFalse(CarryForwardRules.includesStandard(p, "other"));
        p.setStandardIds(List.of("chosen", "chosen"));
        assertThrows(BusinessException.class, () -> CarryForwardRules.validate(p));
    }

    @Test
    void accountMatchingHonorsAllSelectedCustomersAndScopeWithoutDependingOnHouseLevels() {
        var p = plan();
        var bill = new ReceivableEntity();
        bill.setProjectId("project");
        bill.setSpaceId("leaf-building-with-no-rooms");
        bill.setSubjectId("property");
        bill.setCalcStartDate("2024-02-01");
        bill.setCalcEndDate("2024-02-29");
        bill.setCustomerId("first");
        bill.setSnapshotJson(
                "{\"standard\":{\"companyId\":\"company\"},\"customers\":[{\"customerId\":\"first\"},{\"customerId\":\"second\"}]}");
        var a = new FundAccountEntity();
        a.setProjectId("project");
        a.setSpaceId(bill.getSpaceId());
        a.setType("ADVANCE");
        a.setSubjectId("property");
        a.setCompanyId("company");
        a.setCustomerId("second");
        assertTrue(CarryForwardRules.matches("project", p, bill, a));
        a.setType("DEPOSIT");
        assertFalse(CarryForwardRules.matches("project", p, bill, a));
        a.setType("ADVANCE");
        a.setCompanyId("other-company");
        assertFalse(CarryForwardRules.matches("project", p, bill, a));
        a.setCompanyId("company");
        a.setCustomerId("unselected");
        assertFalse(CarryForwardRules.matches("project", p, bill, a));
        a.setCustomerId("second");
        assertFalse(CarryForwardRules.matches("forged-project", p, bill, a));
        bill.setCalcEndDate("2025-01-01");
        assertFalse(CarryForwardRules.matches("project", p, bill, a));
    }
}
