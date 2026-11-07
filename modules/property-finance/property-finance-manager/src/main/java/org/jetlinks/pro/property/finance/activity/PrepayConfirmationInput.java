package org.jetlinks.pro.property.finance.activity;

import org.jetlinks.pro.property.finance.payment.CashierInput;

import java.util.List;

/** 柜台确认只提交真实支付信息，逐期应收及优惠取不可变报价，不接收客户端分配金额。 */
public record PrepayConfirmationInput(
        String requestId,
        Long version,
        String companyId,
        String paidAt,
        List<CashierInput.Tender> tenders,
        String remark,
        String attachmentName,
        String attachmentFileId) {}
