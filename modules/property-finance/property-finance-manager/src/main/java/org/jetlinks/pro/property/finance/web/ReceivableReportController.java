package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.report.*;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** 跨项目报表保留独立按钮权限，项目及房屋数据权限由服务统一在聚合前验证。 */
@RestController
@Authorize
@RequiredArgsConstructor
@RequestMapping("/property/finance/reports/receivables")
@Resource(id = "property-finance-report-receivable", name = "应收统计")
public class ReceivableReportController {
    private final ReceivableReportService reports;
    private final ReceivableReportExportService exports;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<ReceivableReportResult> query(@RequestBody ReceivableReportInput input) {
        return reports.query(input);
    }

    @PostMapping("/_export/{format}")
    @ResourceAction(id = "export", name = "导出")
    public Mono<ResponseEntity<byte[]>> export(
            @RequestBody ReceivableReportInput input, @PathVariable String format) {
        return exports.export(input, format)
                .map(
                        bytes ->
                                ResponseEntity.ok()
                                        .contentType(
                                                MediaType.parseMediaType(
                                                        "pdf".equals(format)
                                                                ? "application/pdf"
                                                                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                                        .header(
                                                HttpHeaders.CONTENT_DISPOSITION,
                                                ContentDisposition.attachment()
                                                        .filename(
                                                                "应收统计."
                                                                        + ("pdf".equals(format)
                                                                                ? "pdf"
                                                                                : "xlsx"),
                                                                StandardCharsets.UTF_8)
                                                        .build()
                                                        .toString())
                                        .body(bytes));
    }
}
