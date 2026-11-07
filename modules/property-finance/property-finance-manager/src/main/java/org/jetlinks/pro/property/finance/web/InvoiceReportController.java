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
@RequestMapping("/property/finance/reports/invoices")
@Resource(id = "property-finance-report-invoice", name = "开票统计")
public class InvoiceReportController {
    private final InvoiceReportService reports;
    private final InvoiceLedgerService ledger;
    private final InvoiceReportExportService exports;

    @PostMapping("/ledger/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<InvoiceLedgerResult> ledger(@RequestBody InvoiceLedgerInput input) {
        return ledger.query(input);
    }

    @PostMapping("/ledger/_export/{format}")
    @ResourceAction(id = "export", name = "导出")
    public Mono<ResponseEntity<byte[]>> exportLedger(
            @RequestBody InvoiceLedgerInput input, @PathVariable String format) {
        if (!java.util.Set.of("excel", "pdf").contains(format))
            return Mono.error(FinanceScope.error("report_query_invalid", 400));
        return org.hswebframework.web.i18n.LocaleUtils.currentReactive()
                .defaultIfEmpty(java.util.Locale.SIMPLIFIED_CHINESE)
                .flatMap(locale -> ledger.exportWorkbook(input, locale))
                .flatMap(
                        book ->
                                Mono.fromCallable(() -> FinanceReportFile.write(book, format))
                                        .subscribeOn(
                                                reactor.core.scheduler.Schedulers.boundedElastic()))
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
                                                                "发票台账."
                                                                        + ("pdf".equals(format)
                                                                                ? "pdf"
                                                                                : "xlsx"),
                                                                StandardCharsets.UTF_8)
                                                        .build()
                                                        .toString())
                                        .body(bytes));
    }

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<InvoiceReportResult> query(@RequestBody InvoiceReportInput input) {
        return reports.query(input);
    }

    @GetMapping("/{project}/{sourceType}/{sourceId}/documents")
    @ResourceAction(id = "query", name = "查询")
    public Mono<java.util.List<InvoiceReportDocument>> related(
            @PathVariable String project,
            @PathVariable String sourceType,
            @PathVariable String sourceId) {
        return reports.related(project, sourceType, sourceId);
    }

    @PostMapping("/_export/{format}")
    @ResourceAction(id = "export", name = "导出")
    public Mono<ResponseEntity<byte[]>> export(
            @RequestBody InvoiceReportInput input, @PathVariable String format) {
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
                                                                "开票统计."
                                                                        + ("pdf".equals(format)
                                                                                ? "pdf"
                                                                                : "xlsx"),
                                                                StandardCharsets.UTF_8)
                                                        .build()
                                                        .toString())
                                        .body(bytes));
    }
}
