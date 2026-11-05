package org.jetlinks.pro.smartpark.handover.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.smartpark.handover.api.*;
import org.jetlinks.pro.smartpark.handover.entity.HandoverProblemEntity;
import org.jetlinks.pro.smartpark.handover.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** Explicit action routes keep function permissions aligned with the workflow. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/handover/problem")
@Resource(id = "handover-problem", name = "承接查验问题")
public class HandoverProblemController {
    private final HandoverProblemService service;

    @GetMapping("/_query")
    @QueryAction
    public Mono<PagerResult<HandoverProblemEntity>> query(HandoverQuery input) {
        return service.query(input);
    }

    @GetMapping("/_counts")
    @QueryAction
    public Mono<Map<String, Long>> counts(HandoverQuery input) {
        return service.counts(input);
    }

    @GetMapping("/{id}")
    @QueryAction
    public Mono<HandoverProblemEntity> read(@PathVariable String id) {
        return service.read(id);
    }

    @PostMapping
    @SaveAction
    public Mono<HandoverProblemEntity> create(@RequestBody ProblemRequest input) {
        HandoverRules.require(input.getSourceReportId() == null, "report_command_required");
        return service.create(input);
    }

    @PutMapping("/{id}")
    @SaveAction
    public Mono<HandoverProblemEntity> edit(
            @PathVariable String id, @RequestBody ProblemRequest input) {
        return service.edit(id, input);
    }

    @DeleteMapping("/{id}")
    @DeleteAction
    public Mono<Void> delete(@PathVariable String id, @RequestParam Integer version) {
        return service.delete(id, version);
    }

    @PostMapping("/_final-review")
    @ResourceAction(id = "final-review", name = "终审")
    public Mono<List<HandoverProblemEntity>> finalReview(@RequestBody BatchReviewRequest body) {
        return service.finalReview(body);
    }

    @PostMapping("/{id}/assign")
    @ResourceAction(id = "assign", name = "分配单位")
    public Mono<HandoverProblemEntity> assign(
            @PathVariable String id, @RequestBody ActionRequest body) {
        return service.action(id, "assign", body);
    }

    @PostMapping("/{id}/accept")
    @ResourceAction(id = "accept", name = "接收问题")
    public Mono<HandoverProblemEntity> accept(
            @PathVariable String id, @RequestBody ActionRequest body) {
        return service.action(id, "accept", body);
    }

    @PostMapping("/{id}/rectify")
    @ResourceAction(id = "rectify", name = "提交整改")
    public Mono<HandoverProblemEntity> rectify(
            @PathVariable String id, @RequestBody ActionRequest body) {
        return service.action(id, "rectify", body);
    }

    @PostMapping("/{id}/initial-review")
    @ResourceAction(id = "initial-review", name = "初审")
    public Mono<HandoverProblemEntity> initial_review(
            @PathVariable String id, @RequestBody ActionRequest body) {
        return service.action(id, "initial-review", body);
    }

    @PostMapping("/{id}/final-review")
    @ResourceAction(id = "final-review", name = "终审")
    public Mono<HandoverProblemEntity> final_review(
            @PathVariable String id, @RequestBody ActionRequest body) {
        return service.action(id, "final-review", body);
    }

    @PostMapping("/{id}/photos")
    @ResourceAction(id = "save", name = "补录照片")
    public Mono<HandoverProblemEntity> photos(
            @PathVariable String id, @RequestBody ActionRequest body) {
        return service.action(id, "photos", body);
    }
}
