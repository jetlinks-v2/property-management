package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.document.DocumentIssueInput;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;

/** 发票管理独立授权；预览只读，销方和税务信息从后端来源读取。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/invoices")
@Resource(id = "property-finance-invoices", name = "发票管理")
public class InvoiceDocumentController {
    private final DocumentPreparationService preparation;
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
}
