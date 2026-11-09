package org.jetlinks.pro.property.decoration.web;

import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.hswebframework.web.authorization.annotation.*;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.jetlinks.pro.property.decoration.entity.*;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.jetlinks.pro.property.decoration.service.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

/** 本人业主办理入口，所有查询和动作强制当前认证账号。 */
@RestController @Authorize @RequiredArgsConstructor
@RequestMapping("/decoration/mobile/applications")
@Resource(id="decoration-owner",name="业主装修办理")
public class DecorationOwnerController {
    private final DecorationApplicationService applications;
    private final DecorationReviewService reviews;
    private final DecorationPermitService permits;
    private final DecorationTaskService tasks;
    private final DecorationFinanceService finance;
    @GetMapping("/_requests/{requestId}") @QueryAction
    public Mono<DecorationApplicationEntity> creationResult(@PathVariable String requestId) { return applications.creationResult(requestId, true); }
    @GetMapping("/{id}/_requests/{requestId}") @QueryAction
    public Mono<DecorationApplicationEntity> actionResult(@PathVariable String id, @PathVariable String requestId) { return applications.actionResult(id, requestId, true); }
    @PostMapping("/_query") @QueryAction
    public Mono<PagerResult<DecorationApplicationEntity>> query(@RequestBody QueryParamEntity query) { return applications.query(query, true); }
    @GetMapping("/{id}") @QueryAction
    public Mono<DecorationApplicationEntity> detail(@PathVariable String id) { return applications.read(id, true); }
    @PostMapping @ResourceAction(id="create",name="申请装修")
    public Mono<DecorationApplicationEntity> create(@Valid @RequestBody DecorationRequests.Create input) { return applications.create(input, true); }
    @GetMapping("/{id}/history") @QueryAction
    public Flux<DecorationHistoryEntity> history(@PathVariable String id) { return applications.history(id, true); }
    @PostMapping("/{id}/tasks/_query") @QueryAction
    public Mono<PagerResult<DecorationTaskEntity>> tasks(@PathVariable String id, @RequestBody QueryParamEntity query) { return tasks.query(id, query, true); }
    @PostMapping("/{id}/resubmit") @ResourceAction(id="resubmit",name="重传资料")
    public Mono<DecorationApplicationEntity> resubmit(@PathVariable String id, @Valid @RequestBody DecorationRequests.Resubmit input) { return reviews.resubmit(id, input, true); }
    @PostMapping("/{id}/acceptance") @ResourceAction(id="acceptance",name="申请验收")
    public Mono<DecorationApplicationEntity> acceptance(@PathVariable String id, @Valid @RequestBody DecorationRequests.AcceptanceRequest input) { return tasks.requestAcceptance(id, input, true); }
    @PostMapping("/{id}/rectification") @ResourceAction(id="rectification",name="提交整改")
    public Mono<DecorationApplicationEntity> rectification(@PathVariable String id, @Valid @RequestBody DecorationRequests.Rectification input) { return tasks.submitRectification(id, input, true); }
    @PostMapping("/{id}/payment") @ResourceAction(id="payment",name="缴费核验")
    public Mono<DecorationApplicationEntity> payment(@PathVariable String id, @Valid @RequestBody DecorationRequests.Finance input) { return finance.process(id, input, false, true); }
    @PostMapping("/{id}/refund") @ResourceAction(id="refund",name="退款核验")
    public Mono<DecorationApplicationEntity> refund(@PathVariable String id, @Valid @RequestBody DecorationRequests.Finance input) { return finance.process(id, input, true, true); }
    /** 独立虚拟资金接口复用原资金动作权限；未启用模拟提供方时返回503。 */
    @PostMapping("/{id}/simulation/payment") @ResourceAction(id="payment",name="模拟缴费")
    public Mono<DecorationApplicationEntity> simulatePayment(@PathVariable String id, @Valid @RequestBody DecorationRequests.Action input) { return finance.simulate(id, input, false, true); }
    @PostMapping("/{id}/simulation/refund") @ResourceAction(id="refund",name="模拟退款")
    public Mono<DecorationApplicationEntity> simulateRefund(@PathVariable String id, @Valid @RequestBody DecorationRequests.Action input) { return finance.simulate(id, input, true, true); }
}
