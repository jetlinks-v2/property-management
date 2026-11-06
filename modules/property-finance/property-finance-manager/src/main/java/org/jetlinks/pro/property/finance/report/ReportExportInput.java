package org.jetlinks.pro.property.finance.report;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.Getter;
import lombok.Setter;

import java.util.Set;

/** 导出范围是业务白名单，默认维持当前视图；不能借导出关闭权限或截断明细。 */
@Getter
@Setter
public class ReportExportInput {
    private String exportMode = "CURRENT";

    public void validateExport() {
        if (!Set.of("CURRENT", "DETAIL", "BOTH").contains(exportMode == null ? "" : exportMode))
            throw error("report_query_invalid", 400);
    }
}
