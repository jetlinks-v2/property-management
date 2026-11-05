package org.jetlinks.pro.smartpark.handover.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.smartpark.handover.api.ActionRequest;
import org.jetlinks.pro.smartpark.handover.entity.HandoverItemEntity;
import org.jetlinks.pro.smartpark.handover.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** Project registers share asset checks and conditional logical deletion. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/handover/item")
@Resource(id = "handover-config", name = "承接查验配置")
public class HandoverItemController {
    private final HandoverConfigurationService service;
    private final HandoverItemRepository repository;

    @GetMapping("/_query")
    @QueryAction
    public Mono<PagerResult<HandoverItemEntity>> query(HandoverQuery input) {
        return service.query(repository, input, "name");
    }

    @GetMapping("/{id}")
    @QueryAction
    public Mono<HandoverItemEntity> read(@PathVariable String id) {
        return service.read(repository, id);
    }

    @PostMapping
    @SaveAction
    public Mono<HandoverItemEntity> save(@RequestBody HandoverItemEntity input) {
        return service.save(repository, input);
    }

    @DeleteMapping("/{id}")
    @DeleteAction
    public Mono<Void> delete(@PathVariable String id, @RequestParam Integer version) {
        return service.delete(repository, id, version);
    }

    @PostMapping("/{id}/confirm")
    @SaveAction
    public Mono<HandoverItemEntity> confirm(
            @PathVariable String id, @RequestBody ActionRequest body) {
        return service.confirm(id, body.getVersion());
    }
}
