package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.entity.SubjectCategoryEntity;
import org.jetlinks.pro.property.finance.service.SubjectCategoryService;
import org.jetlinks.pro.property.finance.subject.SubjectCategoryInput;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/property/finance/subject-categories")
@Resource(id = "property-finance-subject-category", name = "收费科目目录")
@Authorize
@RequiredArgsConstructor
public class SubjectCategoryController {
    private final SubjectCategoryService service;

    @GetMapping
    @ResourceAction(id = "query", name = "查询")
    public Flux<SubjectCategoryEntity> list() {
        return service.list();
    }

    @PostMapping
    @ResourceAction(id = "save", name = "保存")
    public Mono<SubjectCategoryEntity> create(@RequestBody SubjectCategoryInput input) {
        return service.create(input);
    }

    @PutMapping("/{id}")
    @ResourceAction(id = "save", name = "保存")
    public Mono<SubjectCategoryEntity> update(
            @PathVariable String id, @RequestBody SubjectCategoryInput input) {
        return service.update(id, input);
    }

    @DeleteMapping("/{id}")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> delete(@PathVariable String id, @RequestParam long version) {
        return service.delete(id, version);
    }
}
