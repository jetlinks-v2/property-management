package org.jetlinks.pro.property.finance.report;

/** 关联票据只读摘要；整票授权后才展示票号、抬头及票据金额。 */
public record InvoiceReportDocument(
        String id,
        String documentNumber,
        String status,
        String invoiceType,
        String issuedAt,
        String amount,
        String buyerName,
        String remark) {}
