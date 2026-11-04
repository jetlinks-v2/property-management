package org.jetlinks.pro.smartpark.repair.service;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.smartpark.repair.api.RepairActionRequest;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairReviewEntity;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RepairWorkflowRulesTest {
    private RepairOrderEntity order(int status){var order=new RepairOrderEntity();order.setStatus(status);order.setAssignedUserId("worker");return order;}
    private RepairReviewEntity review(String kind){var review=new RepairReviewEntity();review.setKind(kind);return review;}
    @Test void finishAndCallbackEnterArchiveAuditWhenLevelRequiresReview(){
        var order=order(1);order.setLevelAuditRequired(true);
        assertEquals(9,RepairFlowRules.nextStatus(order,"finish","worker",new RepairActionRequest(),false));
        assertEquals(8,RepairFlowRules.nextStatus(order,"finish","worker",new RepairActionRequest(),true));
        order.setStatus(8);
        assertEquals(9,RepairFlowRules.nextStatus(order,"callback","callback-role-user",new RepairActionRequest(),true));
    }
    @Test void rejectedArchiveReturnsToHandlingAndClearsFinishedTime(){
        var order=order(9);order.setFinishTime(1000L);order.setTimedOut(true);
        assertEquals(1,RepairWorkflowService.reviewResult(order,review("archive"),false));
        assertNull(order.getFinishTime());assertFalse(order.getTimedOut());
        assertEquals(5,RepairWorkflowService.reviewResult(order,review("archive"),true));
    }
    @Test void delayApprovalAloneChangesDeadline(){
        var order=order(1);order.setFinishDeadline(1000L);var review=review("delay");review.setDelayUntil(2000L);
        assertEquals(1,RepairWorkflowService.reviewResult(order,review,false));assertEquals(1000L,order.getFinishDeadline());
        assertEquals(1,RepairWorkflowService.reviewResult(order,review,true));assertEquals(2000L,order.getFinishDeadline());
    }
    @Test void abnormalClosureArchivesWhereasAbandonRemainsDistinct(){
        var order=order(1);assertEquals(5,RepairWorkflowService.reviewResult(order,review("close"),true));assertNotNull(order.getFinishTime());
        var abandon=review("abandon");abandon.setReason("重复报事");
        assertEquals(3,RepairWorkflowService.reviewResult(order,abandon,true));assertEquals("重复报事",order.getAbandonReason());
    }
    @Test void rejectedAbandonPreservesOriginalState(){
        for(int status:new int[]{0,1,6,7})assertEquals(status,RepairWorkflowService.reviewResult(order(status),review("abandon"),false));
    }
    @Test void downgradeApprovalUsesSubmittedLevelSnapshot(){
        var order=order(1);var review=review("levelChange");
        review.setTargetLevel(Map.of("id","normal","name","一般","coefficient",1,"auditRequired",false,"color","#333333"));
        assertEquals(1,RepairWorkflowService.reviewResult(order,review,true));assertEquals("normal",order.getLevelId());
        assertEquals(1,order.getLevelCoefficient());assertFalse(order.getLevelAuditRequired());
    }
    @Test void transferRequiresDifferentRecipientAndPostAcceptStateIsPreserved(){
        var input=new RepairActionRequest();input.setAssignedUserId("another-worker");
        assertEquals(6,RepairFlowRules.nextStatus(order(7),"transfer","dispatcher",input,false));
        input.setAssignedUserId("worker");assertThrows(BusinessException.class,()->RepairFlowRules.nextStatus(order(1),"transfer","worker",input,false));
    }
    @Test void employeeEvaluationIsLimitedToFinishedStatesAndCannotRepeat(){
        assertThrows(BusinessException.class,()->RepairFlowRules.nextStatus(order(1),"evaluate","staff",new RepairActionRequest(),false));
        var order=order(5);assertEquals(5,RepairFlowRules.nextStatus(order,"evaluate","staff",new RepairActionRequest(),false));
        order.setStaffEvaluated(true);assertThrows(BusinessException.class,()->RepairFlowRules.nextStatus(order,"evaluate","staff",new RepairActionRequest(),false));
        assertNull(order.getOwnerEvaluated());
    }
}
