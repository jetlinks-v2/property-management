package org.jetlinks.pro.property.finance.report;

import org.hswebframework.web.api.crud.entity.PagerResult;

import java.util.*;

/** 页面、分页和导出共享同一白名单字段与全范围合计。 */
public record AnalysisResult(
        String report,
        String groupBy,
        List<Column> columns,
        Map<String, Object> summary,
        PagerResult<Map<String, Object>> page) {
    public record Column(String key, String label, boolean money) {}
}
