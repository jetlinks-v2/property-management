package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.pro.property.finance.calculation.BillingPeriod;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

class ReceivableReportDateRulesTest {
    @Test
    void generatedBusinessDateUsesLegacyDefaultStartMonthEndSeparatelyFromDue() {
        var setting = new HouseSettingEntity();
        setting.setEffectiveStartDate("2024-02-15");
        var row =
                ReceivableDrafts.create(
                        setting,
                        new BillingPeriod(
                                LocalDate.parse("2024-02-01"), LocalDate.parse("2024-04-30")),
                        new StandardEntity());
        assertEquals("2024-02-29", row.getReceivableDate());
        assertEquals("2024-04-30", row.getDueDate());
    }
}
