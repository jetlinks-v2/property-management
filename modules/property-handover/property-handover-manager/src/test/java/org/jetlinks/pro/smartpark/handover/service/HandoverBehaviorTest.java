package org.jetlinks.pro.smartpark.handover.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.smartpark.handover.api.ActionRequest;
import org.junit.jupiter.api.Test;

/** 以完整业务状态序列断言初审/终审和退回后重新整改，不把实现形状当作正确性。 */
class HandoverBehaviorTest {
    @Test
    void rectificationRequiresBothReviewsAndRejectionRestartsTheCycle() {
        var input = new ActionRequest();
        assertArrayEquals(new int[] {1, 0}, HandoverRules.next(0, 0, "accept", input));
        input.setDescription("防水已返工");
        assertArrayEquals(new int[] {2, 0}, HandoverRules.next(1, 0, "rectify", input));
        input.setPassed(true);
        assertArrayEquals(new int[] {2, 1}, HandoverRules.next(2, 0, "initial-review", input));
        assertThrows(
                BusinessException.class, () -> HandoverRules.next(2, 0, "final-review", input));
        assertArrayEquals(new int[] {3, 2}, HandoverRules.next(2, 1, "final-review", input));
        input.setPassed(false);
        assertThrows(
                BusinessException.class, () -> HandoverRules.next(2, 1, "final-review", input));
        input.setReason("渗漏复验未通过");
        assertArrayEquals(new int[] {4, 0}, HandoverRules.next(2, 1, "final-review", input));
        assertArrayEquals(new int[] {1, 0}, HandoverRules.next(4, 0, "accept", input));
        assertArrayEquals(new int[] {2, 0}, HandoverRules.next(1, 0, "rectify", input));
    }

    @Test
    void completedProblemsCannotBeAcceptedAssignedOrAppendedWithPhotos() {
        var input = new ActionRequest();
        input.setOrganizationId("external-a");
        for (String action : java.util.List.of("accept", "assign", "photos")) {
            assertThrows(BusinessException.class, () -> HandoverRules.next(3, 2, action, input));
        }
    }

    @Test
    void allEightStagesAndTimeRangeFiltersRemainAvailable() {
        assertEquals(
                java.util.List.of(
                        "kickoff",
                        "material",
                        "disclosure",
                        "site",
                        "physical",
                        "rectification",
                        "quality",
                        "summary"),
                HandoverRules.STAGES);
        var query = new HandoverQuery();
        query.setInspectionTimeFrom(100L);
        query.setInspectionTimeTo(200L);
        var terms = query.parameter("description").getTerms();
        assertTrue(
                terms.stream()
                        .anyMatch(
                                t ->
                                        "inspectionTime".equals(t.getColumn())
                                                && "gte".equals(t.getTermType())
                                                && Long.valueOf(100).equals(t.getValue())));
        assertTrue(
                terms.stream()
                        .anyMatch(
                                t ->
                                        "inspectionTime".equals(t.getColumn())
                                                && "lte".equals(t.getTermType())
                                                && Long.valueOf(200).equals(t.getValue())));
    }
}
