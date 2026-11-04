package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.smartpark.repair.service.RepairDirectory;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepairFlowConfigurationTest {
    @Test void storedFlowWithoutNewFlagsRetainsExistingBehavior() {
        assertTrue(RepairFlowConfiguration.enabled(Map.of("finishTimeLimitDays", 1)));
        assertFalse(RepairFlowConfiguration.enabled(Map.of("flowEnabled", false)));
        assertFalse(RepairFlowConfiguration.enabled(Map.of("flowConfigured", false)));
        assertEquals("error.repair.flow_disabled", assertThrows(BusinessException.class,
            () -> RepairFlowConfiguration.requireEnabled(Map.of("flowEnabled", false))).getMessage());
    }
    @Test void disabledFlowDoesNotCreateCandidatePoolOrTouchEmployeeDirectory() {
        var people=mock(RepairDirectory.class);var orders=mock(RepairOrderService.class);
        var service=new RepairCandidateService(people,mock(RepairAssigneeService.class),orders);
        var order=new RepairOrderEntity();order.setStatus(0);
        var category=new RepairConfigurationEntity();category.setEnabled(true);
        category.setConfiguration(Map.of("flowEnabled",false,"followRoleId","worker-role"));
        StepVerifier.create(service.initialize(order,category,new RepairContext("tenant","app","user","员工")))
            .expectNext(order).verifyComplete();
        assertTrue(category.getEnabled());assertEquals(0,order.getStatus());assertNull(order.getCandidateRoleId());
        verifyNoInteractions(people,orders);
    }
    @Test void disabledAndUnconfiguredFlowsDoNotAutoDispatchOverdueOrders() {
        var order=new RepairOrderEntity();order.setCreateTime(0L);
        var data=new java.util.HashMap<String,Object>(Map.of("autoDispatchEnable",true,"autoDispatchMinutes",1,"autoDispatchRoleId","workers"));
        assertTrue(RepairScheduledService.dispatchDue(order,data,120000));
        data.put("flowEnabled",false);assertFalse(RepairScheduledService.dispatchDue(order,data,120000));
        data.remove("flowEnabled");data.put("flowConfigured",false);
        assertFalse(RepairScheduledService.dispatchDue(order,data,120000));
    }
    @Test void zeroDayDeadlineAndTwoHundredCharacterRemarkMatchLegacyForm() {
        var category=new RepairConfigurationEntity();category.setKind("category");
        category.setConfiguration(Map.of("finishTimeLimitDays",0,"remark","修".repeat(200)));
        assertDoesNotThrow(()->RepairConfigurationRules.validate(category));
        category.setConfiguration(Map.of("finishTimeLimitDays",366));
        assertDoesNotThrow(()->RepairConfigurationRules.validate(category));
        category.setConfiguration(Map.of("finishTimeLimitDays",-1));
        assertThrows(BusinessException.class,()->RepairConfigurationRules.validate(category));
        category.setConfiguration(Map.of("remark","修".repeat(201)));
        assertThrows(BusinessException.class,()->RepairConfigurationRules.validate(category));
    }
    @Test void explicitlyConfiguredFlowRequiresItsLegacyAuditAndCallbackRoles() {
        var category=new RepairConfigurationEntity();category.setKind("category");
        var data=new java.util.HashMap<String,Object>(Map.of("flowConfigured",true));category.setConfiguration(data);
        assertThrows(BusinessException.class,()->RepairConfigurationRules.validate(category));
        for(String key:java.util.List.of("closeRoleId","delayRoleId","levelChangeRoleId"))data.put(key,"reviewer");
        data.put("callbackRequired",true);
        assertThrows(BusinessException.class,()->RepairConfigurationRules.validate(category));
        data.put("callbackRoleId","callback");assertDoesNotThrow(()->RepairConfigurationRules.validate(category));
    }
}
