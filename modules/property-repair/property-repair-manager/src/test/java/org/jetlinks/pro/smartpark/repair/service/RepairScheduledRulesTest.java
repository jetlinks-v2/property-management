package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.smartpark.repair.api.RepairActionRequest;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RepairScheduledRulesTest {
    private RepairOrderEntity order() {
        var order = new RepairOrderEntity();
        order.setStatus(0);
        order.setCreateTime(1_000L);
        return order;
    }

    @Test void autoDispatchUsesMinutesAndRejectsPrematureOrConflictingMode() {
        var order = order();
        var flow = Map.<String,Object>of("autoDispatchEnable", true, "autoDispatchMinutes", 2, "autoDispatchRoleId", "worker");
        assertFalse(RepairScheduledService.dispatchDue(order, flow, 120_999L));
        assertTrue(RepairScheduledService.dispatchDue(order, flow, 121_000L));
        var category = new RepairConfigurationEntity();
        category.setKind("category");
        category.setConfiguration(Map.of("autoDispatchEnable", true, "autoDispatchMinutes", 2,
            "autoDispatchRoleId", "worker", "followRoleId", "worker"));
        assertThrows(BusinessException.class, () -> RepairConfigurationRules.validate(category));
    }

    @Test void followPoolHasNoOwnerUntilAnEligibleCandidateClaimsIt() {
        var order = order();
        RepairCandidateService.setPool(order, "worker-role", "followRole");
        assertEquals(6, order.getStatus());
        assertNull(order.getAssignedUserId());
        assertEquals(7, RepairFlowRules.nextStatus(order, "accept", "eligible-worker", new RepairActionRequest(), false));
        // 角色资格由候选服务实时校验；普通待接单没有候选角色时仍只能由指派人接单。
        order.setCandidateRoleId(null);
        order.setAssignedUserId("assigned-worker");
        assertThrows(BusinessException.class, () -> RepairFlowRules.nextStatus(order, "accept", "other-worker", new RepairActionRequest(), false));
    }

    @Test void defaultEvaluationIsOwnerSideAfterSevenDaysWithoutChangingStaffSide() {
        var order = order();
        order.setStatus(5);
        order.setFinishTime(1_000L);
        order.setStaffEvaluated(true);
        assertFalse(RepairScheduledService.evaluationDue(order, 7 * 86_400_000L + 999));
        assertTrue(RepairScheduledService.evaluationDue(order, 7 * 86_400_000L + 1_000));
        order.setOwnerEvaluated(true);
        assertFalse(RepairScheduledService.evaluationDue(order, 8 * 86_400_000L));
        order.setOwnerEvaluated(false);
        order.setStatus(3);
        assertFalse(RepairScheduledService.evaluationDue(order, 8 * 86_400_000L));
    }

    @Test void scheduledConfigurationCannotCrossTenantApplicationOrBusinessProject() {
        var order = order();
        order.setApplicationId("app-a");
        order.setBusinessProjectId("project-a");
        order.setExtension("tenantId", "tenant-a");
        var category = new RepairConfigurationEntity();
        category.setKind("category");
        category.setEnabled(true);
        category.setApplicationId("app-a");
        category.setBusinessProjectId("project-a");
        category.setExtension("tenantId", "tenant-a");
        assertTrue(RepairScheduledService.sameConfigurationScope(order, category));
        category.setExtension("tenantId", "tenant-b");
        assertFalse(RepairScheduledService.sameConfigurationScope(order, category));
        category.setExtension("tenantId", "tenant-a");
        category.setApplicationId("app-b");
        assertFalse(RepairScheduledService.sameConfigurationScope(order, category));
        category.setApplicationId("app-a");
        category.setBusinessProjectId("project-b");
        assertFalse(RepairScheduledService.sameConfigurationScope(order, category));
    }

    @Test void timeoutIsAnOverdueHandlingOrderAndIsNotMarkedTwice() {
        var order = order();
        order.setStatus(1);
        order.setFinishDeadline(5_000L);
        assertFalse(RepairScheduledService.timeoutDue(order, 5_000L));
        assertTrue(RepairScheduledService.timeoutDue(order, 5_001L));
        order.setTimedOut(true);
        assertFalse(RepairScheduledService.timeoutDue(order, 5_001L));
    }
}
