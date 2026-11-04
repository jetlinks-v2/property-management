package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.smartpark.repair.api.RepairActionRequest;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RepairFlowRulesTest {
    private RepairOrderEntity order(int status) {
        RepairOrderEntity order = new RepairOrderEntity();
        order.setStatus(status);
        order.setAssignedUserId("repair-worker");
        return order;
    }
    private RepairActionRequest arrival() {
        RepairActionRequest request = new RepairActionRequest();
        request.setAttachmentIds(List.of("actual-file-id"));
        request.setLevelId("normal-level");
        return request;
    }
    @Test void acceptsIntoWaitFollowRatherThanHandling() {
        assertEquals(6, RepairFlowRules.nextStatus(order(0), "dispatch", "dispatcher", new RepairActionRequest(), false));
        assertEquals(7, RepairFlowRules.nextStatus(order(6), "accept", "repair-worker", new RepairActionRequest(), false));
        assertEquals(1, RepairFlowRules.nextStatus(order(7), "follow", "repair-worker", arrival(), false));
        assertEquals(5, RepairFlowRules.nextStatus(order(1), "finish", "repair-worker", new RepairActionRequest(), false));
    }
    @Test void firstFollowRequiresArrivalPhoto() {
        RepairActionRequest request = arrival();
        request.setAttachmentIds(List.of());
        assertEquals("error.repair.arrival_photo_required", assertThrows(BusinessException.class,
            () -> RepairFlowRules.nextStatus(order(7), "follow", "repair-worker", request, false)).getMessage());
    }
    @Test void firstFollowRequiresLevel() {
        RepairActionRequest request = arrival();
        request.setLevelId(null);
        assertEquals("error.repair.level_required", assertThrows(BusinessException.class,
            () -> RepairFlowRules.nextStatus(order(7), "follow", "repair-worker", request, false)).getMessage());
    }
    @Test void laterFollowDoesNotRequireAnotherLevelOrArrivalPhoto() {
        assertEquals(1, RepairFlowRules.nextStatus(order(1), "follow", "repair-worker", new RepairActionRequest(), false));
    }
    @Test void rejectsOtherEmployeeAcceptAndPrematureFinish() {
        assertEquals("error.repair.not_assignee", assertThrows(BusinessException.class,
            () -> RepairFlowRules.nextStatus(order(6), "accept", "someone-else", new RepairActionRequest(), false)).getMessage());
        assertEquals("error.repair.invalid_state", assertThrows(BusinessException.class,
            () -> RepairFlowRules.nextStatus(order(7), "finish", "repair-worker", new RepairActionRequest(), false)).getMessage());
    }
    @Test void callbackPassAndReworkKeepDistinctResults() {
        assertEquals(8, RepairFlowRules.nextStatus(order(1), "finish", "repair-worker", new RepairActionRequest(), true));
        assertEquals(5, RepairFlowRules.nextStatus(order(8), "callback", "callback-user", new RepairActionRequest(), true));
        RepairActionRequest rework = new RepairActionRequest();
        rework.setRework(true);
        assertEquals(1, RepairFlowRules.nextStatus(order(8), "callback", "callback-user", rework, true));
    }
    @Test void ordinaryAbandonCannotBypassApproval() {
        assertEquals("error.repair.unsupported_action", assertThrows(BusinessException.class,
            () -> RepairFlowRules.nextStatus(order(1), "abandon", "repair-worker", new RepairActionRequest(), false)).getMessage());
    }
}
