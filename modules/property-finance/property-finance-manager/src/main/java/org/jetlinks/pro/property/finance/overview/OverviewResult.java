package org.jetlinks.pro.property.finance.overview;

import org.jetlinks.pro.property.base.archive.PropertyHouseSummary;
import org.jetlinks.pro.property.finance.report.ReceivableReportAmounts;
import org.jetlinks.pro.property.finance.report.ReceivedReportAmounts;

import java.util.List;

/** 金额统一为精确文本；前端仅按统一单位展示，不重新计算财务汇总。 */
public record OverviewResult(
        PropertyHouseSummary houses,
        Receivables receivables,
        Cash cash,
        Funds balances,
        ReceivedReportAmounts handover,
        String asOfDate) {
    public record Receivables(
            ReceivableReportAmounts summary,
            List<Subject> subjects,
            List<AgeBucket> ageBuckets,
            long overdueHouseCount, long billingHouseCount, long owingHouseCount) {}

    public record Subject(String subjectId, String subjectName, ReceivableReportAmounts amounts) {}

    public record AgeBucket(String key, String amount) {}

    public record TrendPoint(
            String period, String incomeAmount, String refundAmount, String netAmount) {}

    public record Cash(ReceivedReportAmounts summary, List<TrendPoint> trend, String grain) {}

    public record Balance(String type, String subjectId, String subjectName, String amount) {}

    public record Funds(List<Balance> items, String advanceAmount, String depositAmount) {}
}
