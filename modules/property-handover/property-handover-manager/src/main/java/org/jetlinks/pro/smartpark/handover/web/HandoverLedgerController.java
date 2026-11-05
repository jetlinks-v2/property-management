package org.jetlinks.pro.smartpark.handover.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.smartpark.handover.entity.HandoverLedgerEntity;
import org.jetlinks.pro.smartpark.handover.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** Project registers share asset checks and conditional logical deletion. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/handover/ledger")
@Resource(id = "handover-config", name = "承接查验配置")
public class HandoverLedgerController {
    private final HandoverConfigurationService service;
    private final HandoverLedgerRepository repository;

    @GetMapping("/_query")
    @QueryAction
    public Mono<PagerResult<HandoverLedgerEntity>> query(HandoverQuery input) {
        return service.query(repository, input, "title");
    }

    @GetMapping("/{id}")
    @QueryAction
    public Mono<HandoverLedgerEntity> read(@PathVariable String id) {
        return service.read(repository, id);
    }

    @PostMapping
    @SaveAction
    public Mono<HandoverLedgerEntity> save(@RequestBody HandoverLedgerEntity input) {
        return service.save(repository, input);
    }

    @PostMapping("/{id}/photos")
    @SaveAction
    public Mono<HandoverLedgerEntity> photos(@PathVariable String id,
            @RequestBody org.jetlinks.pro.smartpark.handover.api.ActionRequest input) {
        return service.supplementLedger(id, input);
    }

    @DeleteMapping("/{id}")
    @DeleteAction
    public Mono<Void> delete(@PathVariable String id, @RequestParam Integer version) {
        return service.delete(repository, id, version);
    }
}
