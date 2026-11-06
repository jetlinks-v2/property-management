package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.service.ReceiptPrintService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** 受保护的收据PDF输出，不发布永久URL；打印权限与开具、作废分别控制。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "property-finance-receipts", name = "收据管理")
@RequestMapping("/property/finance/projects/{projectId}/receipts")
public class ReceiptPrintController {
    private final ReceiptPrintService printing;

    @GetMapping(value = "/{id}/print.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @ResourceAction(id = "print", name = "打印")
    public Mono<ResponseEntity<byte[]>> print(
            @PathVariable String projectId, @PathVariable String id) {
        return printing.pdf(projectId, id)
                .map(
                        bytes ->
                                ResponseEntity.ok()
                                        .contentType(MediaType.APPLICATION_PDF)
                                        .cacheControl(CacheControl.noStore())
                                        .header(
                                                HttpHeaders.CONTENT_DISPOSITION,
                                                ContentDisposition.inline()
                                                        .filename("receipt.pdf")
                                                        .build()
                                                        .toString())
                                        .header("X-Content-Type-Options", "nosniff")
                                        .body(bytes));
    }
}
