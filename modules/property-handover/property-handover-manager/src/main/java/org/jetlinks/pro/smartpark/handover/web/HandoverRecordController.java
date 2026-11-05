package org.jetlinks.pro.smartpark.handover.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.smartpark.handover.entity.HandoverRecordEntity;
import org.jetlinks.pro.smartpark.handover.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** Project registers share asset checks and conditional logical deletion. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/handover/record")
@Resource(id = "handover-config", name = "承接查验配置")
public class HandoverRecordController {
    private final HandoverConfigurationService service;
    private final HandoverRecordRepository repository;

    @GetMapping("/_query")
    @QueryAction
    public Mono<PagerResult<HandoverRecordEntity>> query(HandoverQuery input) {
        return service.query(repository, input, "title");
    }

    @GetMapping("/{id}")
    @QueryAction
    public Mono<HandoverRecordEntity> read(@PathVariable String id) {
        return service.read(repository, id);
    }

    @PostMapping
    @SaveAction
    public Mono<HandoverRecordEntity> save(@RequestBody HandoverRecordEntity input) {
        return service.save(repository, input);
    }

    @DeleteMapping("/{id}")
    @DeleteAction
    public Mono<Void> delete(@PathVariable String id, @RequestParam Integer version) {
        return service.delete(repository, id, version);
    }
}
