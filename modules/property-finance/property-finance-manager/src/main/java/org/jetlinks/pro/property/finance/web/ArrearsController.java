package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryOption;
import org.jetlinks.pro.property.finance.entity.ArrearsConfigEntity;
import org.jetlinks.pro.property.finance.service.ArrearsService;
import org.jetlinks.pro.property.finance.service.SubjectDictionaryService;
import org.jetlinks.pro.property.finance.subject.ArrearsConfigView;
import org.jetlinks.pro.property.finance.subject.ArrearsInput;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/property/finance/arrears-configs")
@Resource(id = "property-finance-arrears", name = "欠费通知等级")
@Authorize
@RequiredArgsConstructor
public class ArrearsController {
    private final ArrearsService service;
    private final SubjectDictionaryService dictionaries;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<ArrearsConfigView>> query(@RequestBody QueryParamEntity input) {
        return service.query(input);
    }

    @GetMapping("/_options")
    @ResourceAction(id = "query", name = "查询")
    public Flux<ArrearsConfigEntity> options() {
        return service.options();
    }

    @GetMapping("/_levels")
    @ResourceAction(id = "query", name = "查询")
    public Mono<List<PropertyDictionaryOption>> levels() {
        return dictionaries.options("arrears-level");
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<ArrearsService.Detail> detail(@PathVariable String id) {
        return service.detail(id);
    }

    @PostMapping
    @ResourceAction(id = "save", name = "保存")
    public Mono<ArrearsService.Detail> create(@RequestBody ArrearsInput input) {
        return service.create(input);
    }

    @PutMapping("/{id}")
    @ResourceAction(id = "save", name = "保存")
    public Mono<ArrearsService.Detail> update(
            @PathVariable String id, @RequestBody ArrearsInput input) {
        return service.update(id, input);
    }

    @DeleteMapping("/{id}")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> delete(@PathVariable String id, @RequestParam long version) {
        return service.delete(id, version);
    }
}
