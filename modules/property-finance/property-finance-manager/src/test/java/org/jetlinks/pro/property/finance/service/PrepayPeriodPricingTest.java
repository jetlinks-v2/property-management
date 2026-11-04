package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson.JSON;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.finance.activity.PrepayQuoteRules.Window;
import org.jetlinks.pro.property.finance.calculation.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.Test;

import java.math.*;
import java.time.LocalDate;
import java.util.Map;

/** 报价逐期调用正式出账引擎；不能用单价乘月份或客户数替代冻结公式。 */
class PrepayPeriodPricingTest {
    final PrepayPeriodPricing pricing =
            new PrepayPeriodPricing(
                    new NaturalBillingPeriodService(),
                    new ReceivableCalculationService(
                            new ReceivableAmountCalculator(
                                    new ChargeFormulaParser(),
                                    new ChargeFormulaEvaluator(),
                                    new BillingProrationService()),
                            new ReceivableLateFeeCalculator()));

    HouseSettingEntity setting(String method, String expression, int scale, int mantissa) {
        var standard = new StandardEntity();
        standard.setSettlementMethod(method);
        standard.setFormulaExpression(expression);
        standard.setChargeRoundType(scale);
        standard.setMantissa(mantissa);
        standard.setVariablesSnapshotJson(
                JSON.toJSONString(
                        java.util.List.of(
                                Map.of(
                                        "variableCode",
                                        "PRICE",
                                        "variableName",
                                        "单价",
                                        "enabled",
                                        true),
                                Map.of(
                                        "variableCode",
                                        "PERIOD_MONTHS",
                                        "variableName",
                                        "期间月数",
                                        "enabled",
                                        true))));
        var row = new HouseSettingEntity();
        row.setId("setting");
        row.setProjectId("project");
        row.setSpaceId("space");
        row.setSubjectId("subject");
        row.setStandardId("standard");
        row.setConfigVersion(7);
        row.setCustomerId("first");
        row.setCustomerRelationshipId("relation");
        row.setEffectiveStartDate("2024-02-15");
        row.setEffectiveEndDate("2025-12-31");
        row.setSnapshotJson(
                JSON.toJSONString(
                        Map.of(
                                "standard",
                                standard,
                                "values",
                                Map.of("PRICE", new BigDecimal("300")),
                                "customers",
                                java.util.List.of(
                                        Map.of("customerId", "first"),
                                        Map.of("customerId", "second")))));
        return row;
    }

    Window window(String start, String end) {
        return new Window(LocalDate.parse(start), LocalDate.parse(end));
    }

    @Test
    void partialLeapMonthUsesFrozenFormulaAndPreservesAllSelectedCustomers() {
        for (String expression : new String[] {"${PRICE}", "${PRICE}*${PERIOD_MONTHS}"}) {
            var source = setting("MONTHLY", expression, 2, 0);
            var before = source.getSnapshotJson();
            var priced = pricing.price(source, window("2024-02-15", "2024-02-29"));
            assertEquals(new BigDecimal("155.17"), priced.amount());
            assertEquals("2024-02-01", priced.draft().getPeriodStart());
            assertEquals("2024-02-29", priced.draft().getPeriodEnd());
            assertEquals("2024-02-15", priced.draft().getCalcStartDate());
            assertEquals(before, priced.draft().getSnapshotJson());
            assertEquals(2, JSON.parseObject(before).getJSONArray("customers").size());
            assertEquals(before, source.getSnapshotJson());
            assertNull(priced.draft().getId());
            assertNull(priced.draft().getCreateTime());
        }
    }

    @Test
    void quarterFormulaUsesThreeRealMonthsAndCannotMultiplyOnePriceByPeriodCount() {
        var priced =
                pricing.price(
                        setting("QUARTERLY", "${PRICE}*${PERIOD_MONTHS}", 2, 0),
                        window("2024-04-01", "2024-06-30"));
        assertEquals(new BigDecimal("900.00"), priced.amount());
        assertEquals("2024-04-01", priced.draft().getPeriodStart());
        assertEquals("2024-06-30", priced.draft().getPeriodEnd());
    }

    @Test
    void respectsConfiguredYuanJiaoFenAndRoundingInBothQuoteAndFormalDraft() {
        for (int scale = 0; scale <= 2; scale++)
            for (int mantissa = 0; mantissa <= 2; mantissa++) {
                var source = setting("MONTHLY", "10/3", scale, mantissa);
                var priced = pricing.price(source, window("2024-03-01", "2024-03-31"));
                var mode =
                        mantissa == 0
                                ? RoundingMode.HALF_UP
                                : mantissa == 1 ? RoundingMode.DOWN : RoundingMode.UP;
                assertEquals(
                        BigDecimal.TEN.divide(new BigDecimal("3"), scale, mode), priced.amount());
                assertEquals(scale, priced.draft().getMoneyScale());
                var formal =
                        ReceivableDrafts.create(
                                source,
                                new BillingPeriod(
                                        LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 31)),
                                org.jetlinks.pro.property.finance.setting.SettingRules.standard(
                                        source.getSnapshotJson()));
                assertEquals(formal.getBillKey(), priced.draft().getBillKey());
                assertEquals(formal.getCalcStartDate(), priced.draft().getCalcStartDate());
                assertEquals(new BigDecimal("0"), priced.draft().getPaidAmount());
            }
    }

    @Test
    void rejectsCrossPeriodOutOfRangeAndMissingSnapshot() {
        var source = setting("MONTHLY", "${PRICE}", 2, 0);
        assertThrows(
                BusinessException.class,
                () -> pricing.price(source, window("2024-03-01", "2024-04-30")));
        assertThrows(
                BusinessException.class,
                () -> pricing.price(source, window("2024-02-01", "2024-02-29")));
        assertThrows(
                BusinessException.class,
                () -> pricing.price(source, window("2025-12-01", "2026-01-01")));
        source.setSnapshotJson("{}");
        assertThrows(
                BusinessException.class,
                () -> pricing.price(source, window("2024-03-01", "2024-03-31")));
    }
}
