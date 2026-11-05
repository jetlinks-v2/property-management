package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.report.*;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** 合同月报仅开放查询和导出；服务统一校验项目及全部参与账单房屋范围。 */
@RestController
@Authorize
@RequiredArgsConstructor
@RequestMapping("/property/finance/reports/contracts")
@Resource(id = ContractReportService.RESOURCE, name = "合同账单报表")
public class ContractReportController {
    private final ContractReportService reports;
    private final ContractReportExportService exports;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<ContractReportResult> query(@RequestBody ContractReportInput input) {
        return reports.query(input);
    }

    @PostMapping("/{contractId}/{metric}/_details")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<ContractReportResult.Detail>> details(
            @RequestBody ContractReportInput input,
            @PathVariable String contractId,
            @PathVariable String metric) {
        return reports.details(input, contractId, metric);
    }

    @PostMapping("/_export/{format}")
    @ResourceAction(id = "export", name = "导出")
    public Mono<ResponseEntity<byte[]>> export(
            @RequestBody ContractReportInput input, @PathVariable String format) {
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
                                                                "合同账单报表."
                                                                        + ("pdf".equals(format)
                                                                                ? "pdf"
                                                                                : "xlsx"),
                                                                StandardCharsets.UTF_8)
                                                        .build()
                                                        .toString())
                                        .body(bytes));
    }
}
