package org.jetlinks.pro.property.finance.report;

import java.util.List;
import java.util.Map;

/** 同一查询事务形成的导出快照；渲染不再占用数据库连接。 */
public record ReportWorkbook(List<FinanceReportFile.Data> sheets, Map<String, String> conditions) {}
