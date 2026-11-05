package org.jetlinks.pro.property.finance.report;

/** 明细金额只包含已授权来源行，不返回原主单其他房屋金额。 */
public record ReceivedReportRow(
        String id,
        String sourceId,
        String projectId,
        String projectName,
        String spaceId,
        String fullName,
        String customerName,
        String subjectId,
        String subjectName,
        String receivedAt,
        String calcStartDate,
        String calcEndDate,
        String method,
        String methodText,
        String sourceType,
        String collectorName,
        String remark,
        long rowCount,
        ReceivedReportAmounts amounts) {}
