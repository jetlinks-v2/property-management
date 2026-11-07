package org.jetlinks.pro.property.finance.report;

import org.hswebframework.web.api.crud.entity.PagerResult;

/** 同一授权范围和读取快照下的全量摘要及服务端分页，供页面和文件导出消费。 */
public record ReceivableReportResult(
        String groupBy, ReceivableReportAmounts summary, PagerResult<ReceivableReportRow> page) {}
