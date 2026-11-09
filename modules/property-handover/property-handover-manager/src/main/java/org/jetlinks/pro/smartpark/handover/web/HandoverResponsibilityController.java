package org.jetlinks.pro.smartpark.handover.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.smartpark.handover.entity.HandoverResponsibilityEntity;
import org.jetlinks.pro.smartpark.handover.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** Project registers share asset checks and conditional logical deletion. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/handover/responsibility")
@Resource(id = "handover-config", name = "承接查验配置")
public class HandoverResponsibilityController {
    private final HandoverConfigurationService service;
    private final HandoverResponsibilityRepository repository;

    @GetMapping("/_query")
    @QueryAction
    public Mono<PagerResult<HandoverResponsibilityEntity>> query(HandoverQuery input) {
        return service.queryInformation(repository, input);
    }

    @GetMapping("/{id}")
    @QueryAction
    public Mono<HandoverResponsibilityEntity> read(@PathVariable String id) {
        return service.read(repository, id);
    }

    @PostMapping
    @SaveAction
    public Mono<HandoverResponsibilityEntity> save(
            @RequestBody HandoverResponsibilityEntity input) {
        return service.save(repository, input);
    }

    @DeleteMapping("/{id}")
    @DeleteAction
    public Mono<Void> delete(@PathVariable String id, @RequestParam Integer version) {
        return service.delete(repository, id, version);
    }
}
