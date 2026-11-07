package org.jetlinks.pro.property.finance.report;

/** 每个规范来源只占一行；最新蓝票信息受整票资产权限约束。 */
public record InvoiceReportRow(
        String id,
        String sourceType,
        String projectId,
        String projectName,
        String spaceId,
        String fullName,
        String customerName,
        String subjectId,
        String subjectName,
        String invoiceStatus,
        String documentId,
        String invoiceNo,
        String invoiceType,
        String invoiceDate,
        String buyerName,
        String invoiceContent,
        String remark,
        long rowCount,
        InvoiceReportAmounts amounts) {
    public InvoiceReportRow withContent(String content) {
        return new InvoiceReportRow(
                id,
                sourceType,
                projectId,
                projectName,
                spaceId,
                fullName,
                customerName,
                subjectId,
                subjectName,
                invoiceStatus,
                documentId,
                invoiceNo,
                invoiceType,
                invoiceDate,
                buyerName,
                content,
                remark,
                rowCount,
                amounts);
    }
}
