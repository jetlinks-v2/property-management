package org.jetlinks.pro.property.finance.activity;

/** 柜台仅提交收费设置、实际付款关系和档位；项目、日期、单价和优惠均由服务端确定。 */
public record PrepayQuoteInput(
        String requestId,
        String settingId,
        String payerRelationshipId,
        String activityId,
        int paidMonths,
        int giftMonths) {}
