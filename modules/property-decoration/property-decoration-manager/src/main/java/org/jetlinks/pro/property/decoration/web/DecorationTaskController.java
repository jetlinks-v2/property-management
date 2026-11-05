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

/** 员工任务入口；指派权限与执行本人/角色资格分别校验。 */
@RestController @Authorize @RequiredArgsConstructor
@RequestMapping("/decoration/tasks") @Resource(id="decoration-task",name="装修巡检验收")
public class DecorationTaskController {
    private final DecorationTaskService tasks;
    @PostMapping("/mine/_query") @QueryAction
    public Mono<PagerResult<DecorationTaskEntity>> mine(@RequestBody QueryParamEntity query) { return tasks.mine(query); }
    @PostMapping("/_query") @QueryAction
    public Mono<PagerResult<DecorationTaskEntity>> query(@RequestBody QueryParamEntity query) { return tasks.query(query); }
    @PostMapping("/{id}/violation/notify") @ResourceAction(id="notify",name="发送整改通知")
    public Mono<DecorationApplicationEntity> notifyViolation(@PathVariable String id, @Valid @RequestBody DecorationRequests.Rectification input) { return tasks.notifyViolation(id, input); }
    @PostMapping("/{id}/rectification/resend") @ResourceAction(id="assign",name="重发整改任务")
    public Mono<DecorationApplicationEntity> resend(@PathVariable String id, @Valid @RequestBody DecorationRequests.Rectification input) { return tasks.resendRectification(id, input); }
    @PostMapping("/{id}/acceptance/assign") @ResourceAction(id="assign",name="指派验收人员")
    public Mono<DecorationApplicationEntity> assignAcceptance(@PathVariable String id, @Valid @RequestBody DecorationRequests.Acceptance input) { return tasks.assignAcceptance(id, input); }
    @PostMapping("/{id}/inspection/assign") @ResourceAction(id="assign",name="指派巡检人员")
    public Mono<DecorationApplicationEntity> assignInspection(@PathVariable String id, @Valid @RequestBody DecorationRequests.Inspection input) { return tasks.assignInspection(id, input); }
    @PostMapping("/{id}/acceptance/feedback") @ResourceAction(id="handle",name="提交验收结果")
    public Mono<DecorationApplicationEntity> acceptanceFeedback(@PathVariable String id, @Valid @RequestBody DecorationRequests.Feedback input) { return tasks.acceptanceFeedback(id, input); }
    @PostMapping("/{id}/inspection/feedback") @ResourceAction(id="handle",name="提交巡检结果")
    public Mono<DecorationApplicationEntity> inspectionFeedback(@PathVariable String id, @Valid @RequestBody DecorationRequests.Feedback input) { return tasks.inspectionFeedback(id, input); }
    @PostMapping("/{id}/rectification/review") @ResourceAction(id="review",name="复核整改")
    public Mono<DecorationApplicationEntity> review(@PathVariable String id, @Valid @RequestBody DecorationRequests.Rectification input) { return tasks.reviewRectification(id, input); }
    @PostMapping("/{id}/rectification/reassign") @ResourceAction(id="assign",name="重指派整改复核角色")
    public Mono<DecorationApplicationEntity> reassign(@PathVariable String id, @Valid @RequestBody DecorationRequests.Rectification input) { return tasks.reassignRole(id, input); }
}
