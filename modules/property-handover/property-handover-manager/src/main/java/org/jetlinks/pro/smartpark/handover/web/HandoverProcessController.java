package org.jetlinks.pro.smartpark.handover.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.smartpark.handover.api.ActionRequest;
import org.jetlinks.pro.smartpark.handover.entity.HandoverProcessEntity;
import org.jetlinks.pro.smartpark.handover.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** Project process owns the version used for stage transitions. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/handover/process")
@Resource(id = "handover-process", name = "承接查验流程")
public class HandoverProcessController {
    private final HandoverProcessService service;

    @GetMapping("/_query")
    @QueryAction
    public Mono<PagerResult<HandoverProcessEntity>> query(HandoverQuery input) {
        return service.query(input);
    }

    @GetMapping
    @QueryAction
    public Mono<HandoverProcessEntity> get(@RequestParam String projectId) {
        return service.get(projectId);
    }

    @PostMapping("/{stage}/start")
    @SaveAction
    public Mono<HandoverProcessEntity> start(
            @RequestParam String projectId,
            @PathVariable String stage,
            @RequestBody ActionRequest body) {
        return service.action(projectId, stage, "start", body.getVersion());
    }

    @PostMapping("/{stage}/complete")
    @SaveAction
    public Mono<HandoverProcessEntity> complete(
            @RequestParam String projectId,
            @PathVariable String stage,
            @RequestBody ActionRequest body) {
        return service.action(projectId, stage, "complete", body.getVersion());
    }
}
