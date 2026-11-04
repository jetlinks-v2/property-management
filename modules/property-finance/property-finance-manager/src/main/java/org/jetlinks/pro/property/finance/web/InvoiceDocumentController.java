package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.document.DocumentIssueInput;
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
    private final BillingJobProperties properties;

    @PostMapping("/_preview")
    @ResourceAction(id = "create", name = "模拟开票")
    public Mono<DocumentPreparationService.Preview> preview(
            @PathVariable String projectId, @RequestBody DocumentIssueInput input) {
        if (input == null || !"INVOICE".equals(input.documentType()))
            return Mono.error(FinanceScope.error("document_input_invalid", 400));
        return preparation.preview(
                projectId, input, LocalDateTime.now(ZoneId.of(properties.getZone())));
    }

    @PostMapping("/_confirm")
    @ResourceAction(id = "create", name = "模拟开票")
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
        return issue.detail(projectId, "INVOICE", id);
    }

    @PostMapping("/{id}/_reverse")
    @ResourceAction(id = "reverse", name = "模拟红冲")
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
}
