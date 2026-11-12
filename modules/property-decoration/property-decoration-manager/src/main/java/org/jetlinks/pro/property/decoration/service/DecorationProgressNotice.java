package org.jetlinks.pro.property.decoration.service;

import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;
import org.jetlinks.pro.property.decoration.entity.DecorationHistoryEntity;
import java.util.Objects;

/** 只在真实流程节点发生时生成通知；分项审核未全部通过，不提前提醒缴费。 */
record DecorationProgressNotice(String code,String step) {
    static DecorationProgressNotice from(DecorationHistoryEntity history) {
        DecorationApplicationEntity row=history.getSnapshot();
        if(row==null||Boolean.TRUE.equals(row.getDeleted()))return null;
        if("extendPermit".equals(history.getAction()))return new DecorationProgressNotice("permitExtended","permit");
        if(Objects.equals(history.getBeforeStatus(),history.getAfterStatus()))return null;
        return switch(row.getStatus()) {
            case "reviewing" -> new DecorationProgressNotice("submitted","apply");
            case "rejected" -> new DecorationProgressNotice("rejected","apply");
            case "pending_payment" -> new DecorationProgressNotice("reviewed","pay");
            case "pending_permit" -> "payment".equals(history.getAction())||"simulation/payment".equals(history.getAction())
                ?new DecorationProgressNotice("paid","permit"):null;
            case "permit_issued" -> new DecorationProgressNotice("permitReady","first");
            case "first_acceptance_waiting" -> new DecorationProgressNotice("firstRequested","first");
            case "second_acceptance_waiting" -> new DecorationProgressNotice("secondRequested","second");
            case "first_acceptance_in_progress" -> new DecorationProgressNotice("firstAssigned","first");
            case "second_acceptance_in_progress" -> new DecorationProgressNotice("secondAssigned","second");
            case "first_acceptance_failed" -> new DecorationProgressNotice("firstFailed","first");
            case "second_acceptance_failed" -> new DecorationProgressNotice("secondFailed","second");
            case "pending_second_acceptance" -> new DecorationProgressNotice("firstPassed","second");
            case "acceptance_passed" -> new DecorationProgressNotice("secondPassed","refund");
            case "finished" -> row.getRefundStatus()!=null&&row.getRefundStatus()==2
                ?new DecorationProgressNotice("finished","refund"):null;
            default -> null;
        };
    }
}
