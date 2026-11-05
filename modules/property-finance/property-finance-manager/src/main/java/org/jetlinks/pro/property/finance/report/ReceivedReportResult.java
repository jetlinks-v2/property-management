package org.jetlinks.pro.property.finance.report;

import org.hswebframework.web.api.crud.entity.PagerResult;

public record ReceivedReportResult(
        String groupBy,
        boolean periodEnabled,
        ReceivedReportAmounts summary,
        PagerResult<ReceivedReportRow> page) {}
