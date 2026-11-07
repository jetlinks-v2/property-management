package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.document.DocumentIssueInput;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;

/** 发票管理独立授权，模拟开具和红冲有明确标识，不接真实税务通道。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/invoices")
@Resource(id = "property-finance-invoices", name = "发票管理")
public class InvoiceDocumentController {
    private final DocumentPreparationService preparation;
    private final DocumentIssueService issue;
    private final DocumentReverseService reverse;
    private final DocumentRecordService records;
    private final DocumentCandidateService candidates;
    private final DocumentReopenService reopen;
    private final BillingJobProperties properties;
    private final DocumentFileService files;

    @PostMapping("/_query")
    @QueryAction
    public Mono<PagerResult<org.jetlinks.pro.property.finance.document.DocumentLedgerRow>> query(
            @PathVariable String projectId, @RequestBody QueryParamEntity input) {
        return records.ledger(projectId, "INVOICE", input);
    }

    @PostMapping("/{id}/logs/_query")
    @QueryAction
    public Mono<PagerResult<DocumentOperationEntity>> logs(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody QueryParamEntity input) {
        return records.logs(projectId, "INVOICE", id, input);
    }

    @PostMapping("/sources/_query")
    @ResourceAction(id = "create", name = "开票")
    public Mono<PagerResult<DocumentCandidateService.View>> sources(
            @PathVariable String projectId, @RequestBody DocumentCandidateService.Input input) {
        return candidates.query(
                projectId, "INVOICE", input, LocalDateTime.now(ZoneId.of(properties.getZone())));
    }

    @PostMapping("/_preview")
    @ResourceAction(id = "create", name = "开票")
    public Mono<DocumentPreparationService.Preview> preview(
            @PathVariable String projectId, @RequestBody DocumentIssueInput input) {
        if (input == null || !"INVOICE".equals(input.documentType()))
            return Mono.error(FinanceScope.error("document_input_invalid", 400));
        return preparation.preview(
                projectId, input, LocalDateTime.now(ZoneId.of(properties.getZone())));
    }

    @PostMapping("/_confirm")
    @ResourceAction(id = "create", name = "开票")
    public Mono<DocumentStore.Detail> confirm(
            @PathVariable String projectId, @RequestBody DocumentIssueService.Submission input) {
        if (input == null
                || input.input() == null
                || !"INVOICE".equals(input.input().documentType()))
            return Mono.error(FinanceScope.error("document_input_invalid", 400));
        return issue.confirm(projectId, input, LocalDateTime.now(ZoneId.of(properties.getZone())));
    }

    @GetMapping("/{id}")
    @QueryAction
    public Mono<DocumentStore.Detail> detail(
            @PathVariable String projectId, @PathVariable String id) {
        return records.detail(projectId, "INVOICE", id);
    }

    @PostMapping("/{id}/_reverse")
    @ResourceAction(id = "reverse", name = "红冲")
    public Mono<DocumentStore.Detail> reverse(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody DocumentReverseService.Input input) {
        return reverse.reverse(
                projectId,
                "INVOICE",
                id,
                input,
                LocalDateTime.now(ZoneId.of(properties.getZone())));
    }

    @PostMapping("/{id}/reopen/_preview")
    @ResourceAction(id = "reopen", name = "红冲重开")
    public Mono<DocumentPreparationService.Preview> reopenPreview(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody DocumentReopenService.Input input) {
        return reopen.preview(
                projectId, id, input, LocalDateTime.now(ZoneId.of(properties.getZone())));
    }

    @PostMapping("/{id}/reopen/_confirm")
    @ResourceAction(id = "reopen", name = "红冲重开")
    public Mono<DocumentStore.Detail> reopenConfirm(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody DocumentReopenService.Submission input) {
        return reopen.confirm(
                projectId, id, input, LocalDateTime.now(ZoneId.of(properties.getZone())));
    }

    @PostMapping("/_export/excel")
    @ResourceAction(id = "export", name = "导出台账")
    public Mono<org.springframework.http.ResponseEntity<byte[]>> export(
            @PathVariable String projectId, @RequestBody QueryParamEntity input) {
        return files.excel(projectId, "INVOICE", input)
                .map(
                        bytes ->
                                org.springframework.http.ResponseEntity.ok()
                                        .contentType(
                                                org.springframework.http.MediaType.parseMediaType(
                                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                                        .header(
                                                "Content-Disposition",
                                                "attachment; filename=document-ledger.xlsx")
                                        .body(bytes));
    }

    @GetMapping("/{id}/pdf")
    @QueryAction
    public Mono<org.springframework.http.ResponseEntity<byte[]>> pdf(
            @PathVariable String projectId, @PathVariable String id) {
        return files.invoicePdf(projectId, id)
                .map(
                        bytes ->
                                org.springframework.http.ResponseEntity.ok()
                                        .contentType(
                                                org.springframework.http.MediaType.APPLICATION_PDF)
                                        .header(
                                                "Content-Disposition",
                                                "inline; filename=invoice.pdf")
                                        .body(bytes));
    }
}
