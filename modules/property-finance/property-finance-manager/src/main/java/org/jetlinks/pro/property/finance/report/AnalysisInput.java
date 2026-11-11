package org.jetlinks.pro.property.finance.report;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.*;

/** 分析报表业务白名单；费用归属、业务发生时间和历史截止时点不能相互替代。 */
@Getter
@Setter
public class AnalysisInput extends ReportExportInput {
    private List<String> projectIds = List.of(),
            spaceIds = List.of(),
            customerIds = List.of(),
            subjectIds = List.of();
    private String activityId;
    private String startDate,
            endDate,
            customerName,
            fullName,
            subjectName,
            operation,
            type,
            activityName,
            billNo,
            ageBucket;
    private String groupBy = "PROJECT";
    private int pageIndex, pageSize = 12;

    public void validate(String report) {
        validateExport();
        var proxy = new ReceivableReportInput();
        proxy.setProjectIds(projectIds);
        proxy.setSpaceIds(spaceIds);
        proxy.setCustomerIds(customerIds);
        proxy.setSubjectIds(subjectIds);
        proxy.validate();
        if (!Set.of(
                                "PROJECT",
                                "SUBJECT",
                                "SPACE",
                                "CUSTOMER",
                                "MONTH",
                                "ACTIVITY",
                                "TYPE",
                                "OPERATION",
                                "AGE",
                                "DETAIL",
                                "PERIOD")
                        .contains(Objects.toString(groupBy, ""))
                || pageIndex < 0
                || pageSize < 1
                || pageSize > 200
                || (long) pageIndex * pageSize > Integer.MAX_VALUE)
            throw error("report_query_invalid", 400);
        if (!Set.of("arrears", "monthly", "funds", "activity", "changes").contains(report))
            throw error("report_query_invalid", 400);
        for (var value :
                Arrays.asList(
                        activityId,
                        customerName,
                        fullName,
                        subjectName,
                        operation,
                        type,
                        activityName,
                        billNo,
                        ageBucket))
            if (value != null && value.length() > 200) throw error("report_query_invalid", 400);
        try {
            if (endDate == null || !LocalDate.parse(endDate).toString().equals(endDate))
                throw error("report_query_invalid", 400);
            if (startDate != null
                    && (!LocalDate.parse(startDate).toString().equals(startDate)
                            || startDate.compareTo(endDate) > 0))
                throw error("report_query_invalid", 400);
            if (!report.equals("arrears") && startDate == null)
                throw error("report_query_invalid", 400);
            if (report.equals("monthly")
                    && (!startDate.endsWith("-01")
                            || !LocalDate.parse(startDate)
                                    .withDayOfMonth(LocalDate.parse(startDate).lengthOfMonth())
                                    .toString()
                                    .equals(endDate))) throw error("report_month_required", 400);
        } catch (java.time.format.DateTimeParseException e) {
            throw error("report_query_invalid", 400);
        }
    }
}
