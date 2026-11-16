package org.jetlinks.pro.property.decoration.service;

import org.jetlinks.pro.property.decoration.entity.*;
import org.jetlinks.pro.property.decoration.integration.DecorationInsideMailSender;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DecorationProgressNoticeTest {
    @Test void completedNodesPointToTheNextStepAndPartialReviewDoesNotNotifyPayment(){
        var row=new DecorationApplicationEntity();row.setDeleted(false);
        var history=new DecorationHistoryEntity();history.setSnapshot(row);history.setAction("review");
        history.setBeforeStatus("reviewing");history.setAfterStatus("reviewing");row.setStatus("reviewing");
        assertNull(DecorationProgressNotice.from(history));
        Map.of("pending_payment","pay","permit_issued","first","pending_second_acceptance","second","acceptance_passed","refund")
            .forEach((status,step)->{row.setStatus(status);history.setAfterStatus(status);assertEquals(step,DecorationProgressNotice.from(history).step());});
        row.setStatus("pending_permit");history.setAfterStatus("pending_permit");history.setAction("simulation/payment");
        assertEquals("paid",DecorationProgressNotice.from(history).code());
        history.setBeforeStatus("pending_permit");assertNull(DecorationProgressNotice.from(history));
        history.setAction("extendPermit");assertEquals("permitExtended",DecorationProgressNotice.from(history).code());
        history.setAction("refund");history.setBeforeStatus("acceptance_passed");history.setAfterStatus("finished");row.setStatus("finished");
        row.setRefundStatus(0);assertNull(DecorationProgressNotice.from(history));row.setRefundStatus(2);assertEquals("finished",DecorationProgressNotice.from(history).code());
    }
    @Test void notificationCommandUsesTrustedRecipientAndDetailLinkWithoutPrivateData(){
        var row=new DecorationNotificationEntity();row.setId("history-id");row.setUserId("owner");row.setApplicationId("app");
        row.setDecorationId("decoration");row.setStep("pay");row.setTitle("装修办理提醒");row.setEvent("reviewed");row.setCreateTime(100L);
        row.setMessage("资料已全部通过审核，请进行费用缴纳。");row.setDetailUrl("/decoration-h5/?applicationScope=app&side=owner&decorationId=decoration&step=pay");
        var command=DecorationInsideMailSender.command(row);
        assertEquals("owner",command.getUserId());assertEquals(java.util.List.of("inside-mail"),command.getChannelProviders());
        assertEquals("property-decoration",command.getTopicProvider());assertEquals("history-id",command.getDataId());
        assertEquals(row.getMessage(),command.getMessage());assertEquals(row.getDetailUrl(),((Map<?,?>)command.getDetail()).get("url"));
        assertEquals(java.util.Set.of("decorationId","applicationId","step","url"),((Map<?,?>)command.getDetail()).keySet());
    }
    @Test void staffNotificationPayloadLocatesTaskWithoutCredentials(){
        var notice=new DecorationNotificationEntity();notice.setId("notice");notice.setUserId("staff");notice.setApplicationId("app");
        notice.setDecorationId("application");notice.setRecipientSide("staff");notice.setTaskId("task");notice.setStep("task");notice.setCreateTime(1L);
        notice.setDetailUrl("/decoration-h5/?applicationScope=app&side=staff&decorationId=application&step=task&taskId=task");
        var detail=(Map<?,?>)DecorationInsideMailSender.command(notice).getDetail();
        assertEquals("staff",detail.get("side"));assertEquals("task",detail.get("taskId"));
        assertEquals(java.util.Set.of("applicationId","decorationId","side","taskId","step","url"),detail.keySet());
    }

}
