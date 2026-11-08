package org.jetlinks.pro.property.decoration.service;

import org.jetlinks.pro.property.base.house.PropertyHouseholdInfo;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DecorationDocumentsTest {
    @Test void constructionDatesKeepShanghaiCalendarDay() {
        var input = new DecorationRequests.Create();
        input.setStartTime(Instant.parse("2026-11-07T16:00:00Z").toEpochMilli());
        input.setCompletionTime(Instant.parse("2026-12-08T15:59:59Z").toEpochMilli());
        var home = new PropertyHouseholdInfo("project", "house", "101", "residential",
            "customer", "owner", "测试业主", "13800000000", "relation", "owner");
        var agreement = new DecorationModels.Agreement();
        agreement.setDeposit(new BigDecimal("1000"));
        var content = DecorationDocuments.content("application", input, home, agreement);
        assertTrue(content.contains("开工日期：2026-11-08\n计划竣工日期：2026-12-08"));
        assertFalse(content.contains("开工日期：2026-11-07"));
    }
}
