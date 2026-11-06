package org.jetlinks.pro.property.finance.overview;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.Getter;
import lombok.Setter;

import org.jetlinks.pro.property.finance.report.ReceivableReportInput;
import org.jetlinks.pro.property.finance.report.ReceivedReportInput;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 概览白名单：日期为应收日/收款业务日，当前房态、余额和交账不受此范围限制。 */
@Getter
@Setter
public class OverviewInput {
    private List<String> projectIds = List.of();
    private String startDate;
    private String endDate;

    public void validate() {
        receivables().validate();
        try {
            var start = LocalDate.parse(startDate);
            var end = LocalDate.parse(endDate);
            if (!start.toString().equals(startDate)
                    || !end.toString().equals(endDate)
                    || start.isAfter(end)
                    || ChronoUnit.DAYS.between(start, end) > 365)
                throw error("overview_query_invalid", 400);
        } catch (java.time.DateTimeException | NullPointerException e) {
            throw error("overview_query_invalid", 400);
        }
    }

    public ReceivableReportInput receivables() {
        var input = new ReceivableReportInput();
        input.setProjectIds(projectIds);
        input.setReceivableDateStart(startDate);
        input.setReceivableDateEnd(endDate);
        return input;
    }

    public ReceivedReportInput cash(boolean current) {
        var input = new ReceivedReportInput();
        input.setProjectIds(projectIds);
        if (!current) {
            input.setReceivedDateStart(startDate);
            input.setReceivedDateEnd(endDate);
        }
        return input;
    }

    public String grain() {
        return ChronoUnit.DAYS.between(LocalDate.parse(startDate), LocalDate.parse(endDate)) > 90
                ? "MONTH"
                : "DAY";
    }
}
