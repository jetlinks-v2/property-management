package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.entity.ReceivableChangeEntity;
import org.jetlinks.pro.property.finance.receivable.*;
import org.jetlinks.pro.property.finance.service.ReceivableAdjustmentService;
import org.jetlinks.pro.property.finance.service.ReceivableChangeAuditService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.LocalDate;

/** 调整权限约束预览和申请；正式改账只允许经本地审批入口发布。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/receivables/{id}/adjustment")
@Resource(id = "property-finance-receivable", name = "应收款管理")
public class ReceivableAdjustmentController {
    private final ReceivableAdjustmentService service;
    private final ReceivableChangeAuditService audit;

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
    public Mono<ReceivableChangeEntity> submit(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody ReceivableAdjustmentService.SubmitInput input) {
        return audit.requestAdjustment(projectId, id, input, LocalDate.now());
    }
}
