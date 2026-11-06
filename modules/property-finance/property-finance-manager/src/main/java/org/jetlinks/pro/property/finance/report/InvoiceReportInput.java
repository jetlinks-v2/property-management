package org.jetlinks.pro.property.finance.report;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.Getter;
import lombok.Setter;

import java.util.*;

/** 来源覆盖口径；开票条件匹配来源最新蓝票，不接收任意查询表达式。 */
@Getter
@Setter
public class InvoiceReportInput {
    private List<String> projectIds = List.of(), spaceIds = List.of(), subjectIds = List.of();
    private String customerName,
            fullName,
            sourceId,
            sourceType,
            invoiceNo,
            invoiceType,
            invoiceStatus,
            invoiceDateStart,
            invoiceDateEnd;
    private String groupBy = "DETAIL";
    private int pageIndex, pageSize = 12;

    private List<ReportGroupKey> subjectGroups = List.of();

    public void validate() {
        ReportGroupKey.validate(subjectGroups);
        var common = new ReceivableReportInput();
        common.setProjectIds(projectIds);
        common.setSpaceIds(spaceIds);
        common.setSubjectIds(subjectIds);
        common.setCustomerName(customerName);
        common.setFullName(fullName);
        common.setReceivableNo(sourceId);
        common.setDueDateStart(invoiceDateStart);
        common.setDueDateEnd(invoiceDateEnd);
        common.setGroupBy(groupBy);
        common.setPageIndex(pageIndex);
        common.setPageSize(pageSize);
        common.validate();
        if (sourceType != null
                        && !Set.of("RECEIVABLE", "TEMP_PAYMENT", "FUND_TOPUP").contains(sourceType)
                || invoiceStatus != null
                        && !Set.of("UNISSUED", "PARTIAL", "INVOICED", "REVERSED")
                                .contains(invoiceStatus)
                || invoiceType != null && !Set.of("1", "2").contains(invoiceType)
                || invoiceNo != null && invoiceNo.length() > 200)
            throw error("report_query_invalid", 400);
    }
}
