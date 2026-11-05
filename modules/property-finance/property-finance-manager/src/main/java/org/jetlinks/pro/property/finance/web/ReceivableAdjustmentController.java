package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.receivable.*;
import org.jetlinks.pro.property.finance.service.ReceivableAdjustmentService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.LocalDate;

/** 调整权限同时约束预览和发布，不需要旧流程中心审批权限。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/receivables/{id}/adjustment")
@Resource(id = "property-finance-receivable", name = "应收款管理")
public class ReceivableAdjustmentController {
    private final ReceivableAdjustmentService service;

    @PostMapping("/_preview")
    @ResourceAction(id = "adjust", name = "调整")
    public Mono<AdjustmentRules.Result> preview(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody AdjustmentInput input) {
        return service.preview(projectId, id, input, LocalDate.now());
    }

    @PostMapping
    @ResourceAction(id = "adjust", name = "调整")
    public Mono<ReceivableView> submit(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody ReceivableAdjustmentService.SubmitInput input) {
        return service.submit(projectId, id, input, LocalDate.now());
    }
}
