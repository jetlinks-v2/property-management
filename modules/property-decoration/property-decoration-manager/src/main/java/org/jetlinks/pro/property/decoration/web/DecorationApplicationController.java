package org.jetlinks.pro.property.decoration.web;

import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.hswebframework.web.authorization.annotation.*;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.crud.web.ResponseMessage;
import org.jetlinks.pro.property.decoration.entity.*;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.jetlinks.pro.property.decoration.service.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

/** 装修管理端受控入口；不暴露实体通用保存、改状态或物理删除。 */
@RestController @Authorize @RequiredArgsConstructor
@RequestMapping("/decoration/applications")
@Resource(id="decoration-application",name="装修申请管理")
public class DecorationApplicationController {
    private final DecorationApplicationService applications;
    private final DecorationReviewListService reviewList;
    private final DecorationReviewService reviews;
    private final DecorationPermitService permits;
    private final DecorationTaskService tasks;
    private final DecorationFinanceService finance;
    private final DecorationOverviewService overview;
    private final DecorationDashboardService dashboard;
    private final DecorationPermitDocumentService documents;
    @GetMapping("/_requests/{requestId}") @QueryAction
    public Mono<DecorationApplicationEntity> creationResult(@PathVariable String requestId) { return applications.creationResult(requestId, false); }
    @GetMapping("/{id}/_requests/{requestId}") @QueryAction
    public Mono<DecorationApplicationEntity> actionResult(@PathVariable String id, @PathVariable String requestId) { return applications.actionResult(id, requestId, false); }
    @PostMapping("/_query") @QueryAction
    public Mono<PagerResult<DecorationApplicationEntity>> query(@RequestBody QueryParamEntity query) { return applications.query(query, false); }
    /** 同一查询权限下的轻量列表；完整详情接口保持兼容，不把私有快照带入审核表格。 */
    @PostMapping(value="/_query", params="view=review") @QueryAction
    public Mono<PagerResult<DecorationReviewListService.Row>> reviewList(@RequestBody QueryParamEntity query) { return reviewList.query(query); }
    @PostMapping("/_statistics") @QueryAction
    public Mono<DecorationOverviewService.Report> statistics(@RequestBody QueryParamEntity query) { return overview.query(query); }
    /** 新概况只读指标；旧统计接口保留供已部署前端兼容。 */
    @PostMapping("/_dashboard") @QueryAction
    public Mono<DecorationDashboardService.Report> dashboard(@RequestBody QueryParamEntity query) { return dashboard.query(query); }
    @PostMapping("/_export") @ResourceAction(id="export", name="导出装修记录")
    public Mono<org.springframework.http.ResponseEntity<String>> export(@RequestBody QueryParamEntity query) {
        return applications.export(query).map(rows -> org.springframework.http.ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=decoration.csv")
            .header("Cache-Control", "private, no-store")
            .contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .body(DecorationCsv.write(rows)));
    }
    @GetMapping("/{id}") @QueryAction
    public Mono<DecorationApplicationEntity> detail(@PathVariable String id) { return applications.read(id, false); }
    @PostMapping @ResourceAction(id="create",name="申请装修")
    public Mono<DecorationApplicationEntity> create(@Valid @RequestBody DecorationRequests.Create input) { return applications.create(input, false); }
    @GetMapping("/{id}/history") @QueryAction
    public Flux<DecorationHistoryEntity> history(@PathVariable String id) { return applications.history(id, false); }
    @PostMapping("/{id}/tasks/_query") @QueryAction
    public Mono<PagerResult<DecorationTaskEntity>> tasks(@PathVariable String id, @RequestBody QueryParamEntity query) { return tasks.query(id, query, false); }
    @PostMapping("/{id}/resubmit") @ResourceAction(id="resubmit",name="重传资料")
    public Mono<DecorationApplicationEntity> resubmit(@PathVariable String id, @Valid @RequestBody DecorationRequests.Resubmit input) { return reviews.resubmit(id, input, false); }
    @PostMapping("/{id}/acceptance") @ResourceAction(id="acceptance",name="申请验收")
    public Mono<DecorationApplicationEntity> acceptance(@PathVariable String id, @Valid @RequestBody DecorationRequests.AcceptanceRequest input) { return tasks.requestAcceptance(id, input, false); }
    @PostMapping("/{id}/rectification") @ResourceAction(id="rectification",name="提交整改")
    public Mono<DecorationApplicationEntity> rectification(@PathVariable String id, @Valid @RequestBody DecorationRequests.Rectification input) { return tasks.submitRectification(id, input, false); }
    @PostMapping("/{id}/payment") @ResourceAction(id="payment",name="缴费核验")
    public Mono<DecorationApplicationEntity> payment(@PathVariable String id, @Valid @RequestBody DecorationRequests.Finance input) { return finance.process(id, input, false, false); }
    @PostMapping("/{id}/refund") @ResourceAction(id="refund",name="退款核验")
    public Mono<DecorationApplicationEntity> refund(@PathVariable String id, @Valid @RequestBody DecorationRequests.Finance input) { return finance.process(id, input, true, false); }

    @PostMapping("/{id}/review") @ResourceAction(id="review",name="审核资料与保证金")
    public Mono<DecorationApplicationEntity> review(@PathVariable String id, @Valid @RequestBody DecorationRequests.Review input) { return reviews.review(id, input); }
    @PostMapping("/{id}/end") @ResourceAction(id="end",name="补充结束说明")
    public Mono<DecorationApplicationEntity> end(@PathVariable String id, @Valid @RequestBody DecorationRequests.Action input) { return reviews.end(id, input); }
    @PostMapping("/{id}/completion-time") @ResourceAction(id="save",name="修改计划竣工时间")
    public Mono<DecorationApplicationEntity> changeEndTime(@PathVariable String id, @Valid @RequestBody DecorationRequests.ChangeEndTime input) { return reviews.changeEndTime(id, input); }
    /** 复用记录修改权限及统一业务范围，不开放通用状态保存。 */
    @PostMapping("/{id}/actual-end-time") @ResourceAction(id="save",name="修改实际结束时间")
    public Mono<DecorationApplicationEntity> changeActualEndTime(@PathVariable String id, @Valid @RequestBody DecorationRequests.ChangeActualEndTime input) { return reviews.changeActualEndTime(id, input); }
    @PostMapping("/{id}/permit") @ResourceAction(id="permit",name="办理备案")
    public Mono<DecorationApplicationEntity> permit(@PathVariable String id, @Valid @RequestBody DecorationRequests.IssuePermit input) { return permits.issue(id, input); }
    /** 施工登记沿用备案操作授权；申请聚合统一校验范围、员工、版本和幂等。 */
    @PostMapping("/{id}/permit/workers") @ResourceAction(id="permit",name="办理备案")
    public Mono<DecorationApplicationEntity> workers(@PathVariable String id, @Valid @RequestBody DecorationRequests.RegisterWorkers input) { return permits.registerWorkers(id, input); }
    @PostMapping("/{id}/permit/print") @ResourceAction(id="print",name="打印备案证明")
    public Mono<DecorationApplicationEntity> print(@PathVariable String id, @Valid @RequestBody DecorationRequests.PrintPermit input) { return permits.print(id, input); }
    /** 与打印共用现有按钮权限，不修改租户或平台授权定义。 */
    @PostMapping("/{id}/permit/collect") @ResourceAction(id="print",name="打印备案证明")
    public Mono<DecorationApplicationEntity> collect(@PathVariable String id, @Valid @RequestBody DecorationRequests.CollectPermit input) { return permits.collect(id, input); }
    /** 含私有项目章，仅允许打印权限，并禁止浏览器或中间代理缓存。 */
    @GetMapping("/{id}/permit/document") @ResourceAction(id="print",name="打印备案证明")
    public Mono<org.springframework.http.ResponseEntity<ResponseMessage<DecorationPermitDocumentService.Document>>> document(@PathVariable String id) {
        return documents.prepare(id).map(document -> org.springframework.http.ResponseEntity.ok()
            // ResponseEntity 不经过统一响应包装；显式保持 PC/H5 transport 需要的 status/result 契约。
            .header("Cache-Control", "private, no-store").body(ResponseMessage.ok(document)));
    }
    @PostMapping("/{id}/permit/extend") @ResourceAction(id="extend",name="延期备案")
    public Mono<DecorationApplicationEntity> extend(@PathVariable String id, @Valid @RequestBody DecorationRequests.ExtendPermit input) { return permits.extend(id, input); }
    /** 独立虚拟资金接口复用原资金动作权限；未启用模拟提供方时返回503。 */
    @PostMapping("/{id}/simulation/payment") @ResourceAction(id="payment",name="模拟缴费")
    public Mono<DecorationApplicationEntity> simulatePayment(@PathVariable String id, @Valid @RequestBody DecorationRequests.Action input) { return finance.simulate(id, input, false, false); }
    @PostMapping("/{id}/simulation/refund") @ResourceAction(id="refund",name="模拟退款")
    public Mono<DecorationApplicationEntity> simulateRefund(@PathVariable String id, @Valid @RequestBody DecorationRequests.Action input) { return finance.simulate(id, input, true, false); }
}
