package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.document.DocumentIssueInput;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;

/** 收据管理独立授权；预览只读，销方和税务信息从后端来源读取。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/receipts")
@Resource(id = "property-finance-receipts", name = "收据管理")
public class ReceiptDocumentController {
    private final DocumentPreparationService preparation;
    private final DocumentIssueService issue;
    private final DocumentReverseService reverse;
    private final BillingJobProperties properties;

    @PostMapping("/_preview")
    @ResourceAction(id = "create", name = "开具")
    public Mono<DocumentPreparationService.Preview> preview(
            @PathVariable String projectId, @RequestBody DocumentIssueInput input) {
        if (input == null || !"RECEIPT".equals(input.documentType()))
            return Mono.error(FinanceScope.error("document_input_invalid", 400));
        return preparation.preview(
                projectId, input, LocalDateTime.now(ZoneId.of(properties.getZone())));
    }

    @PostMapping("/_confirm")
    @ResourceAction(id = "create", name = "开具")
    public Mono<DocumentStore.Detail> confirm(
            @PathVariable String projectId, @RequestBody DocumentIssueService.Submission input) {
        if (input == null
                || input.input() == null
                || !"RECEIPT".equals(input.input().documentType()))
            return Mono.error(FinanceScope.error("document_input_invalid", 400));
        return issue.confirm(projectId, input, LocalDateTime.now(ZoneId.of(properties.getZone())));
    }

    @GetMapping("/{id}")
    @QueryAction
    public Mono<DocumentStore.Detail> detail(
            @PathVariable String projectId, @PathVariable String id) {
        return issue.detail(projectId, "RECEIPT", id);
    }

    @PostMapping("/{id}/_void")
    @ResourceAction(id = "void", name = "作废")
    public Mono<DocumentStore.Detail> voidReceipt(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody DocumentReverseService.Input input) {
        return reverse.reverse(
                projectId,
                "RECEIPT",
                id,
                input,
                LocalDateTime.now(ZoneId.of(properties.getZone())));
    }
}
