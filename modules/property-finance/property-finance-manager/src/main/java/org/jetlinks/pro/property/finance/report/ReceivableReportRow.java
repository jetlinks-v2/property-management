package org.jetlinks.pro.property.finance.report;

/** 明细及汇总只暴露必要的账单快照，不返回设置JSON或内部财务控制字段。 */
public record ReceivableReportRow(
        String id,
        String billNo,
        String projectId,
        String projectName,
        String spaceId,
        String fullName,
        String customerName,
        String subjectId,
        String subjectName,
        String calcStartDate,
        String calcEndDate,
        String receivableDate,
        String dueDate,
        String calculatedAsOfDate,
        String collectionStatus,
        String remark,
        long billCount,
        ReceivableReportAmounts amounts,
        long overdueDays, String customerId) {}
