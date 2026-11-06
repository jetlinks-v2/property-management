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

/** 装修管理端受控入口；不暴露实体通用保存、改状态或物理删除。 */
@RestController @Authorize @RequiredArgsConstructor
@RequestMapping("/decoration/applications")
@Resource(id="decoration-application",name="装修申请管理")
public class DecorationApplicationController {
    private final DecorationApplicationService applications;
    private final DecorationReviewService reviews;
    private final DecorationPermitService permits;
    private final DecorationTaskService tasks;
    private final DecorationFinanceService finance;
    private final DecorationOverviewService overview;
    private final DecorationPermitDocumentService documents;
    @GetMapping("/_requests/{requestId}") @QueryAction
    public Mono<DecorationApplicationEntity> creationResult(@PathVariable String requestId) { return applications.creationResult(requestId, false); }
    @GetMapping("/{id}/_requests/{requestId}") @QueryAction
    public Mono<DecorationApplicationEntity> actionResult(@PathVariable String id, @PathVariable String requestId) { return applications.actionResult(id, requestId, false); }
    @PostMapping("/_query") @QueryAction
    public Mono<PagerResult<DecorationApplicationEntity>> query(@RequestBody QueryParamEntity query) { return applications.query(query, false); }
    @PostMapping("/_statistics") @QueryAction
    public Mono<DecorationOverviewService.Report> statistics(@RequestBody QueryParamEntity query) { return overview.query(query); }
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
    @PostMapping("/{id}/end") @ResourceAction(id="end",name="结束装修")
    public Mono<DecorationApplicationEntity> end(@PathVariable String id, @Valid @RequestBody DecorationRequests.Action input) { return reviews.end(id, input); }
    @PostMapping("/{id}/completion-time") @ResourceAction(id="save",name="修改计划竣工时间")
    public Mono<DecorationApplicationEntity> changeEndTime(@PathVariable String id, @Valid @RequestBody DecorationRequests.ChangeEndTime input) { return reviews.changeEndTime(id, input); }
    /** 复用记录修改权限及统一业务范围，不开放通用状态保存。 */
    @PostMapping("/{id}/actual-end-time") @ResourceAction(id="save",name="修改实际结束时间")
    public Mono<DecorationApplicationEntity> changeActualEndTime(@PathVariable String id, @Valid @RequestBody DecorationRequests.ChangeActualEndTime input) { return reviews.changeActualEndTime(id, input); }
    @PostMapping("/{id}/permit") @ResourceAction(id="permit",name="办理备案")
    public Mono<DecorationApplicationEntity> permit(@PathVariable String id, @Valid @RequestBody DecorationRequests.IssuePermit input) { return permits.issue(id, input); }
    @PostMapping("/{id}/permit/print") @ResourceAction(id="print",name="打印备案证明")
    public Mono<DecorationApplicationEntity> print(@PathVariable String id, @Valid @RequestBody DecorationRequests.PrintPermit input) { return permits.print(id, input); }
    /** 含私有项目章，仅允许打印权限，并禁止浏览器或中间代理缓存。 */
    @GetMapping("/{id}/permit/document") @ResourceAction(id="print",name="打印备案证明")
    public Mono<org.springframework.http.ResponseEntity<DecorationPermitDocumentService.Document>> document(@PathVariable String id) {
        return documents.prepare(id).map(document -> org.springframework.http.ResponseEntity.ok()
            .header("Cache-Control", "private, no-store").body(document));
    }
    @PostMapping("/{id}/permit/extend") @ResourceAction(id="extend",name="延期备案")
    public Mono<DecorationApplicationEntity> extend(@PathVariable String id, @Valid @RequestBody DecorationRequests.ExtendPermit input) { return permits.extend(id, input); }
}
