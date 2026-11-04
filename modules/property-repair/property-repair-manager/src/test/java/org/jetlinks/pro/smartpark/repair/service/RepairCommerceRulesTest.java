package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.smartpark.repair.api.RepairActionRequest;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RepairCommerceRulesTest {
    private RepairOrderEntity order() {
        var order=new RepairOrderEntity();order.setStatus(1);order.setAssignedUserId("worker");return order;
    }
    @Test void paidWorkCannotFinishBeforeRecordedSignature() {
        var order=order();order.setPaid(true);
        assertThrows(BusinessException.class,()->RepairFlowRules.nextStatus(order,"finish","worker",new RepairActionRequest(),false));
        order.setSigned(true);assertEquals(5,RepairFlowRules.nextStatus(order,"finish","worker",new RepairActionRequest(),false));
    }
    @Test void quoteAndSignatureHaveDistinctGates() {
        var order=order();order.setPaid(true);
        assertEquals(1,RepairFlowRules.nextStatus(order,"quote","worker",new RepairActionRequest(),false));
        assertThrows(BusinessException.class,()->RepairFlowRules.nextStatus(order,"sign","registrar",new RepairActionRequest(),false));
        order.setSignatureRequested(true);
        assertThrows(BusinessException.class,()->RepairFlowRules.nextStatus(order,"quote","worker",new RepairActionRequest(),false));
        assertEquals(1,RepairFlowRules.nextStatus(order,"sign","registrar",new RepairActionRequest(),false));
        order.setSigned(true);assertThrows(BusinessException.class,()->RepairFlowRules.nextStatus(order,"sign","registrar",new RepairActionRequest(),false));
    }
    @Test void commissionPreservesLegacyRateRoundingWithoutPrematureCentRounding() {
        var rates=Map.of("person",new BigDecimal("33.335"),"provider",new BigDecimal("33.33"),"property",new BigDecimal("33.335"));
        Map<String,Object> configuration=Map.of("materialVo",rates,"artificialVo",rates);
        RepairCommerceService.validateCommission(configuration);
        var order=order();var input=new RepairActionRequest();input.setMaterialAmount(new BigDecimal("0.01"));input.setArtificialAmount(new BigDecimal("0.02"));
        RepairCommerceService.applyQuote(order,input,configuration);
        assertEquals(0,new BigDecimal("0.03").compareTo(order.getTotalAmount()));
        assertEquals(0,new BigDecimal("0.010002").compareTo(order.getPersonAmount()));
        assertFalse(order.getSigned());assertTrue(order.getSignatureRequested());
    }
    @Test void disabledOrAbsentCommissionHasZeroAllocations() {
        assertEquals(0,BigDecimal.ZERO.compareTo(RepairCommerceService.commission(BigDecimal.TEN,BigDecimal.ONE,Map.of(),"person")));
    }
    @Test void invalidCommissionIsRejectedBeforeSave() {
        assertThrows(BusinessException.class,()->RepairCommerceService.validateCommission(Map.of()));
        var excess=Map.of("person",60,"provider",30,"property",20);
        assertThrows(BusinessException.class,()->RepairCommerceService.validateCommission(Map.of("materialVo",excess,"artificialVo",excess)));
    }
    @Test void ownerAndEmployeeEvaluationsAreIndependent() {
        var order=order();order.setStatus(5);order.setStaffEvaluated(true);
        assertEquals(5,RepairFlowRules.nextStatus(order,"ownerEvaluate","registrar",new RepairActionRequest(),false));
        order.setOwnerEvaluated(true);assertThrows(BusinessException.class,()->RepairFlowRules.nextStatus(order,"ownerEvaluate","registrar",new RepairActionRequest(),false));
    }
}
