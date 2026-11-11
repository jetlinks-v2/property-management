package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.report.*;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** 查询与导出独立按钮权限；所有来源先经项目与空间read权限再汇总。 */
@RestController
@Authorize
@RequiredArgsConstructor
@RequestMapping("/property/finance/reports/received")
@Resource(id = "property-finance-report-received", name = "实收统计")
public class ReceivedReportController {
    private final ReceivedReportService reports;
    private final ReceivedReportExportService exports;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<FinanceExportJobs> jobs;
    @PostMapping("/_exports/{format}") @ResourceAction(id="export",name="导出")
    public Mono<org.jetlinks.pro.property.finance.entity.FinanceExportTaskEntity> createExport(@RequestBody ReceivedReportInput input,@PathVariable String format){return jobs.getObject().create("received",input,format);}
    @GetMapping("/_exports/{id}") @ResourceAction(id="export",name="导出")
    public Mono<org.jetlinks.pro.property.finance.entity.FinanceExportTaskEntity> exportStatus(@PathVariable String id){return jobs.getObject().status("received",id);}
    @GetMapping("/_exports/{id}/_download") @ResourceAction(id="export",name="导出")
    public Mono<org.springframework.core.io.Resource> downloadExport(@PathVariable String id){return jobs.getObject().download("received",id);}

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<ReceivedReportResult> query(@RequestBody ReceivedReportInput input) {
        return reports.query(input);
    }

    @PostMapping("/_export/{format}")
    @ResourceAction(id = "export", name = "导出")
    public Mono<ResponseEntity<byte[]>> export(
            @RequestBody ReceivedReportInput input, @PathVariable String format) {
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
                                                                "实收统计."
                                                                        + ("pdf".equals(format)
                                                                                ? "pdf"
                                                                                : "xlsx"),
                                                                StandardCharsets.UTF_8)
                                                        .build()
                                                        .toString())
                                        .body(bytes));
    }
}
