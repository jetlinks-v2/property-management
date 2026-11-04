package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.receivable.*;
import org.jetlinks.pro.property.finance.service.ReceivableSplitService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/receivables/{id}/split")
@Resource(id = "property-finance-receivable", name = "应收款管理")
public class ReceivableSplitController {
    private final ReceivableSplitService service;

    @GetMapping("/_options")
    @ResourceAction(id = "split", name = "拆分")
    public Mono<List<ReceivableSplitService.CustomerOption>> options(
            @PathVariable String projectId, @PathVariable String id) {
        return service.options(projectId, id);
    }

    @PostMapping("/_preview")
    @ResourceAction(id = "split", name = "拆分")
    public Mono<ReceivableSplitService.Preview> preview(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody SplitInput input) {
        return service.preview(projectId, id, input, LocalDate.now());
    }

    @PostMapping
    @ResourceAction(id = "split", name = "拆分")
    public Mono<List<ReceivableView>> submit(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody ReceivableSplitService.Submit input) {
        return service.submit(projectId, id, input, LocalDate.now());
    }
}
