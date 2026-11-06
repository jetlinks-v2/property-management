package org.jetlinks.pro.property.finance.report;

import org.hswebframework.web.api.crud.entity.PagerResult;

import java.util.Map;

public record InvoiceLedgerResult(
        String groupBy, Map<String, String> summary, PagerResult<Map<String, Object>> page) {}
