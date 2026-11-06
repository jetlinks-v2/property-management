package org.jetlinks.pro.property.finance.report;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** 只读现金报表白名单，日期为业务发生日，不能提交SQL或通用QueryParam。 */
@Getter
@Setter
public class ReceivedReportInput {
    private List<String> projectIds = List.of(),
            spaceIds = List.of(),
            subjectIds = List.of(),
            paymentMethods = List.of(),
            sourceTypes = List.of();
    private String customerName,
            fullName,
            paymentNo,
            collectorName,
            receivedDateStart,
            receivedDateEnd;
    private String handoverState;
    private String groupBy = "DETAIL";
    private int pageIndex, pageSize = 12;

    private List<ReportGroupKey> subjectGroups = List.of();
    private List<ReportGroupKey> methodGroups = List.of();

    public void validate() {
        if (handoverState != null
                && !Set.of("UNHANDED", "PENDING", "APPROVED").contains(handoverState))
            throw error("report_query_invalid", 400);
        ReportGroupKey.validate(subjectGroups);
        ReportGroupKey.validate(methodGroups);
        ids(projectIds, 100, true);
        ids(spaceIds, 1000, false);
        ids(subjectIds, 1000, false);
        ids(paymentMethods, 4, false);
        ids(sourceTypes, 8, false);
        if (!Set.of("1", "2", "3", "4").containsAll(paymentMethods)
                || !Set.of(
                                "RECEIVABLE",
                                "TEMPORARY",
                                "REFUND",
                                "TEMPORARY_REFUND",
                                "ADVANCE",
                                "DEPOSIT",
                                "ADVANCE_REFUND",
                                "DEPOSIT_REFUND")
                        .containsAll(sourceTypes)
                || !Set.of("DETAIL", "PROJECT", "SUBJECT", "METHOD", "SOURCE")
                        .contains(Objects.toString(groupBy, ""))
                || pageIndex < 0
                || pageSize < 1
                || pageSize > 200
                || (long) pageIndex * pageSize > Integer.MAX_VALUE)
            throw error("report_query_invalid", 400);
        for (String s : Arrays.asList(customerName, fullName, paymentNo, collectorName))
            if (s != null && s.length() > 200) throw error("report_query_invalid", 400);
        var start = date(receivedDateStart);
        var end = date(receivedDateEnd);
        if (start != null && end != null && start.isAfter(end))
            throw error("report_query_invalid", 400);
    }

    private static void ids(List<String> ids, int limit, boolean required) {
        if (ids == null
                || ids.size() > limit
                || required && ids.isEmpty()
                || ids.stream().anyMatch(x -> x == null || x.isBlank() || x.length() > 64))
            throw error("report_query_invalid", 400);
    }

    private static LocalDate date(String text) {
        if (text == null) return null;
        try {
            var d = LocalDate.parse(text);
            if (!d.toString().equals(text)) throw error("report_query_invalid", 400);
            return d;
        } catch (DateTimeParseException e) {
            throw error("report_query_invalid", 400);
        }
    }

    public boolean periodEnabled() {
        return receivedDateStart != null || receivedDateEnd != null;
    }
}
