package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.service.DocumentRuleService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** 项目开票规则只提供查询和带版本保存；不开放删除和通用CRUD。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/document-rule")
@Resource(id = "property-finance-document-rule", name = "开票规则")
public class DocumentRuleController {
    private final DocumentRuleService service;

    @GetMapping
    @ResourceAction(id = "query", name = "查询")
    public Mono<DocumentRuleService.View> get(@PathVariable String projectId) {
        return service.get(projectId);
    }

    @PutMapping
    @ResourceAction(id = "save", name = "保存")
    public Mono<DocumentRuleService.View> save(
            @PathVariable String projectId, @RequestBody DocumentRuleService.Input input) {
        return service.save(projectId, input);
    }
}
