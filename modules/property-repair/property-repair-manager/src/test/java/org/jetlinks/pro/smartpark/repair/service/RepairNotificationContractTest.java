package org.jetlinks.pro.smartpark.repair.service;

import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairProcessEntity;
import org.junit.jupiter.api.Test;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

/** 远程命令与发件快照契约：固定站内渠道、可定位事件、无业主隐私，重试不换事件。 */
class RepairNotificationContractTest {
    @Test void commandTargetsOnlyTheAuthorizedEmployeeAndInsideMail(){
        var entry=RepairNotificationService.entry(process(),order(),new RepairNotificationService.Recipient("worker","repair-role","accept"),Locale.SIMPLIFIED_CHINESE);
        var command=RepairNotificationService.command(entry);
        assertEquals(java.util.List.of("worker"),command.getUserIds());
        assertEquals(java.util.List.of("inside-mail"),command.getChannelProviders());
        assertEquals("smart-park-repair",command.getTopicProvider());
        assertEquals("process",command.getDataId());
        assertEquals("dispatch",command.getCode());
        assertEquals(1234L,command.getNotifyTime());
        assertFalse(entry.getDetail().containsKey("contactPhone"));
        assertFalse(entry.getDetail().containsKey("attachmentIds"));
        assertEquals("order",entry.getDetail().get("orderId"));
        assertEquals("application",entry.getDetail().get("applicationId"));
        assertFalse(command.getMessage().contains("13900000000"));
    }
    @Test void duplicateProcessAndRecipientHaveTheSameQueueIdButDifferentRecipientsDoNot(){
        var person=new RepairNotificationService.Recipient("worker",null,"query");
        var first=RepairNotificationService.entry(process(),order(),person,Locale.SIMPLIFIED_CHINESE);
        var retry=RepairNotificationService.entry(process(),order(),person,Locale.SIMPLIFIED_CHINESE);
        var other=RepairNotificationService.entry(process(),order(),new RepairNotificationService.Recipient("other",null,"query"),Locale.SIMPLIFIED_CHINESE);
        assertEquals(first.getId(),retry.getId());
        assertNotEquals(first.getId(),other.getId());
        assertEquals("pending",first.getState());
        assertEquals(0,first.getAttempts());
    }
    @Test void changedTenantOrApplicationRejectsAnOldNotificationTarget(){
        assertTrue(RepairNotificationService.sameScope("tenant","application",order()));
        assertFalse(RepairNotificationService.sameScope("other-tenant","application",order()));
        assertFalse(RepairNotificationService.sameScope("tenant","other-application",order()));
        assertFalse(RepairNotificationService.sameScope(null,"application",order()));
    }
    @Test void deliveryRetryUsesBoundedBackoff(){
        assertEquals(10_000,RepairNotificationService.retryDelay(1));
        assertEquals(20_000,RepairNotificationService.retryDelay(2));
        assertEquals(600_000,RepairNotificationService.retryDelay(10));
    }
    private RepairProcessEntity process(){
        var process=new RepairProcessEntity();process.setId("process");process.setOrderId("order");
        process.setTenantId("tenant");process.setApplicationId("application");
        process.setAction("dispatch");process.setCreateTime(1234L);return process;
    }
    private RepairOrderEntity order(){
        var order=new RepairOrderEntity();order.setId("order");order.setNumber("BS-EXAMPLE");
        order.setExtension("tenantId","tenant");order.setApplicationId("application");
        order.setBusinessProjectId("project");order.setBusinessProjectName("业务项目");order.setStatus(6);
        order.setContactPhone("13900000000");order.setCandidateRoleId("role-ignored");return order;
    }
}
