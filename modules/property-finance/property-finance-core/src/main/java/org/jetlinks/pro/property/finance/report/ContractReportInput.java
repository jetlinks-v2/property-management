package org.jetlinks.pro.property.finance.report;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.exception.BusinessException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.*;

/** 合同月报固定筛选；不接受通用查询条件、任意排序或关闭分页。 @since 2.20.0 */
@Getter
@Setter
public class ContractReportInput {
    private String projectId;
    private String month;
    private String keyword;
    private String startDate;
    private String endDate;
    private List<String> spaceIds = List.of();
    private List<String> subjectIds = List.of();
    private int pageIndex;
    private int pageSize = 12;

    public YearMonth validate() {
        if (projectId == null
                || projectId.isBlank()
                || projectId.length() > 64
                || pageIndex < 0
                || pageSize < 1
                || pageSize > 200
                || (long) pageIndex * pageSize > Integer.MAX_VALUE
                || keyword != null && keyword.length() > 200)
            throw error("report_query_invalid", 400);
        ids(spaceIds);
        ids(subjectIds);
        try {
            if (month == null || !month.matches("[0-9]{4}-[0-9]{2}"))
                throw error("report_query_invalid", 400);
            var selected = YearMonth.parse(month);
            if (selected.getYear() < 2) throw error("report_query_invalid", 400);
            var start = startDate == null ? null : LocalDate.parse(startDate);
            var end = endDate == null ? null : LocalDate.parse(endDate);
            if (start != null && !start.toString().equals(startDate)
                    || end != null && !end.toString().equals(endDate)
                    || start != null && end != null && start.isAfter(end))
                throw error("report_query_invalid", 400);
            return selected;
        } catch (DateTimeParseException invalid) {
            throw error("report_query_invalid", 400);
        }
    }

    private static void ids(List<String> ids) {
        if (ids == null
                || ids.size() > 1000
                || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 64))
            throw error("report_query_invalid", 400);
    }

    private static BusinessException error(String key, int status) {
        return new BusinessException.NoStackTrace("error.property_finance_" + key, status);
    }
}
