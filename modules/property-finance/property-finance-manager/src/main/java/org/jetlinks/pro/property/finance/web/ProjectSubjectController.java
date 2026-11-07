package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.service.ProjectSubjectService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/property/finance/projects/{projectId}/subjects")
@Resource(id = "property-finance-project-subject", name = "项目收费科目")
@Authorize
@RequiredArgsConstructor
public class ProjectSubjectController {
    private final ProjectSubjectService service;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<ProjectSubjectService.Row>> query(
            @PathVariable String projectId,
            @RequestBody QueryParamEntity input,
            @RequestParam(required = false) String categoryId) {
        return service.query(projectId, input, categoryId);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<ProjectSubjectService.Row> detail(
            @PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }

    @DeleteMapping("/{id}")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> delete(
            @PathVariable String projectId, @PathVariable String id, @RequestParam long version) {
        return service.delete(projectId, id, version);
    }
}
