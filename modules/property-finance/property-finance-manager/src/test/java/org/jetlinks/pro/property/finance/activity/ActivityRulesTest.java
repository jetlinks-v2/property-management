package org.jetlinks.pro.property.finance.activity;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

class ActivityRulesTest {
    static ActivityInput input() {
        var i = new ActivityInput();
        i.setName("充值赠送");
        i.setActivityType("prepay");
        i.setSubjectId("subject");
        i.setStandardId("standard");
        i.setStartDate("2024-01-01");
        i.setEndDate("2024-12-31");
        i.setTargetIds(List.of("one-node-building"));
        i.setTiers(List.of(new ActivityInput.Tier(12, 1)));
        return i;
    }

    @Test
    void acceptsArbitrarySpaceDepthAndMonthlyTiersWithoutApprovals() {
        assertDoesNotThrow(() -> ActivityRules.validate(input(), "MONTHLY"));
    }

    @Test
    void quarterlyPaidAndGiftMonthsMustBothBeMultiplesOfThree() {
        assertThrows(RuntimeException.class, () -> ActivityRules.validate(input(), "QUARTERLY"));
        var i = input();
        i.setTiers(List.of(new ActivityInput.Tier(12, 3)));
        assertDoesNotThrow(() -> ActivityRules.validate(i, "QUARTERLY"));
    }

    @Test
    void duplicateTiersAndTargetsAreRejected() {
        var i = input();
        i.setTiers(List.of(new ActivityInput.Tier(12, 1), new ActivityInput.Tier(12, 1)));
        assertThrows(RuntimeException.class, () -> ActivityRules.validate(i, "MONTHLY"));
        var repeated = input();
        repeated.setTargetIds(List.of("room", "room"));
        assertThrows(RuntimeException.class, () -> ActivityRules.validate(repeated, "MONTHLY"));
    }

    @Test
    void invalidCalendarAndReversedRangesAreRejected() {
        var i = input();
        i.setStartDate("2024-02-30");
        assertThrows(RuntimeException.class, () -> ActivityRules.validate(i, "MONTHLY"));
        i.setStartDate("2025-01-01");
        assertThrows(RuntimeException.class, () -> ActivityRules.validate(i, "MONTHLY"));
    }

    @Test
    void arrearsRangeRequiredOnlyWhenSelected() {
        var i = input();
        i.setOffsetArrears(true);
        assertThrows(RuntimeException.class, () -> ActivityRules.validate(i, "MONTHLY"));
        i.setOffsetStartDate("2023-01-01");
        i.setOffsetEndDate("2023-12-31");
        assertDoesNotThrow(() -> ActivityRules.validate(i, "MONTHLY"));
        i.setOffsetArrears(false);
        ActivityRules.validate(i, "MONTHLY");
        assertNull(i.getOffsetStartDate());
        assertNull(i.getOffsetEndDate());
    }

    @Test
    void inclusiveStartAndEndAndDateAdjustmentsRetainOldRules() {
        var start = "2024-01-01";
        var end = "2024-12-31";
        assertEquals("RUNNING", ActivityRules.status(start, end, LocalDate.parse(start)));
        assertEquals("RUNNING", ActivityRules.status(start, end, LocalDate.parse(end)));
        assertEquals("ENDED", ActivityRules.status(start, end, LocalDate.parse(end).plusDays(1)));
        var today = LocalDate.of(2024, 5, 1);
        assertDoesNotThrow(() -> ActivityRules.changeEnd(start, end, "2025-01-01", true, today));
        assertThrows(
                RuntimeException.class,
                () -> ActivityRules.changeEnd(start, end, end, true, today));
        assertThrows(
                RuntimeException.class,
                () -> ActivityRules.changeEnd(start, end, "2024-04-30", false, today));
        assertDoesNotThrow(() -> ActivityRules.changeEnd(start, end, "2024-05-01", false, today));
    }

    @Test
    void attachmentsRequireStableFileIdsAndRejectDuplicates() {
        var i = input();
        i.setAttachments(
                List.of(
                        new ActivityInput.Attachment("file", "活动方案.pdf"),
                        new ActivityInput.Attachment("file", "重复.pdf")));
        assertThrows(RuntimeException.class, () -> ActivityRules.validate(i, "MONTHLY"));
    }
}
