package org.jetlinks.pro.property.finance.report;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** 报表只接受业务筛选项，不接受调用方SQL、通用查询条件、锁定或关闭分页选项。 */
@Getter
@Setter
public class ReceivableReportInput extends ReportExportInput {
    private List<String> projectIds = List.of();
    private List<String> spaceIds = List.of();
    private List<String> subjectIds = List.of();
    private String customerName;
    private String fullName;
    private String receivableNo;
    private String receivableDateStart;
    private String receivableDateEnd;
    private String dueDateStart;
    private String dueDateEnd;
    private String collectionStatus;
    private Boolean overdueOnly;
    private String groupBy = "DETAIL";
    private int pageIndex;
    private int pageSize = 12;

    private List<ReportGroupKey> subjectGroups = List.of();

    public void validate() {
        validateExport();
        ReportGroupKey.validate(subjectGroups);
        ids(projectIds, 100, true);
        ids(spaceIds, 1000, false);
        ids(subjectIds, 1000, false);
        if (!Set.of("DETAIL", "PROJECT", "SUBJECT").contains(Objects.toString(groupBy, ""))
                || collectionStatus != null
                        && !Set.of("UNPAID", "PARTIAL", "PAID").contains(collectionStatus)
                || pageIndex < 0
                || pageSize < 1
                || pageSize > 200
                || (long) pageIndex * pageSize > Integer.MAX_VALUE)
            throw error("report_query_invalid", 400);
        for (String s : Arrays.asList(customerName, fullName, receivableNo))
            if (s != null && s.length() > 200) throw error("report_query_invalid", 400);
        var businessStart = date(receivableDateStart);
        var businessEnd = date(receivableDateEnd);
        if (businessStart != null && businessEnd != null && businessStart.isAfter(businessEnd))
            throw error("report_query_invalid", 400);
        var start = date(dueDateStart);
        var end = date(dueDateEnd);
        if (start != null && end != null && start.isAfter(end))
            throw error("report_query_invalid", 400);
    }

    private static void ids(List<String> ids, int max, boolean required) {
        if (ids == null
                || ids.size() > max
                || required && ids.isEmpty()
                || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 64))
            throw error("report_query_invalid", 400);
    }

    private static LocalDate date(String text) {
        if (text == null) return null;
        try {
            var date = LocalDate.parse(text);
            if (!date.toString().equals(text)) throw error("report_query_invalid", 400);
            return date;
        } catch (DateTimeParseException invalid) {
            throw error("report_query_invalid", 400);
        }
    }
}
