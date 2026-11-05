package org.jetlinks.pro.property.finance.report;

import org.hswebframework.web.api.crud.entity.PagerResult;

public record InvoiceReportResult(
        String groupBy, InvoiceReportAmounts summary, PagerResult<InvoiceReportRow> page) {}
