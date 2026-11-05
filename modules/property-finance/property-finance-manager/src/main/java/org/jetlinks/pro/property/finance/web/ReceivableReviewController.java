package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.List;

/** 普通应收二次审核已取消；保留旧路径返回明确停用响应，不再修改账单。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/receivables")
@Resource(id = "property-finance-receivable", name = "应收款管理")
public class ReceivableReviewController {
    private final ReceivableReviewService service;
    private final BillingJobProperties properties;

    public record Input(String requestId, List<ReceivableReviewService.Item> items, String reason) {
        ReceivableReviewService.Input action(String action) {
            return new ReceivableReviewService.Input(requestId, action, items, reason);
        }
    }

    @PostMapping("/_approve")
    @ResourceAction(id = "approve", name = "审核")
    public Mono<List<ReceivableReviewService.Result>> approve(
            @PathVariable String projectId, @RequestBody Input input) {
        return submit(projectId, input, "APPROVE");
    }

    @PostMapping("/_reject")
    @ResourceAction(id = "reject", name = "驳回")
    public Mono<List<ReceivableReviewService.Result>> reject(
            @PathVariable String projectId, @RequestBody Input input) {
        return submit(projectId, input, "REJECT");
    }

    @PostMapping("/_unapprove")
    @ResourceAction(id = "unapprove", name = "反审核")
    public Mono<List<ReceivableReviewService.Result>> unapprove(
            @PathVariable String projectId, @RequestBody Input input) {
        return submit(projectId, input, "UNAPPROVE");
    }

    private Mono<List<ReceivableReviewService.Result>> submit(
            String projectId, Input input, String action) {
        // 兼容旧客户端的明确拒绝；不能通过旧接口撤销设置/导入审批已经发布的账单。
        return Mono.error(FinanceScope.error("receivable_review_removed", 410));
    }
}
