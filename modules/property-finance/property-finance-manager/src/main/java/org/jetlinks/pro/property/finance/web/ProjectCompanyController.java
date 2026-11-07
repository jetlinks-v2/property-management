package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.property.finance.service.ProjectCompanyService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 每个入口同时检查菜单动作及目标项目组织资产，不允许通过关系 ID 跨项目操作。 */
@RestController
@RequestMapping("/property/finance/projects/{projectId}/companies")
@Resource(id = "property-finance-project-company", name = "项目开票公司关联")
@Authorize
@RequiredArgsConstructor
public class ProjectCompanyController {
    private final ProjectCompanyService service;

    @GetMapping
    @ResourceAction(id = "query", name = "查询")
    public Flux<ProjectCompanyService.Row> list(@PathVariable String projectId) {
        return service.list(projectId);
    }

    @PostMapping("/_candidates")
    @ResourceAction(id = "query", name = "查询")
    public Mono<
                    org.hswebframework.web.api.crud.entity.PagerResult<
                            org.jetlinks.pro.property.finance.entity.FinanceCompanyEntity>>
            candidates(
                    @PathVariable String projectId,
                    @RequestBody org.hswebframework.web.api.crud.entity.QueryParamEntity query) {
        return service.candidates(projectId, query);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<org.jetlinks.pro.property.finance.service.InvoiceCompanyService.Detail> detail(
            @PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }

    @PostMapping
    @ResourceAction(id = "save", name = "关联")
    public Mono<ProjectCompanyService.Row> bind(
            @PathVariable String projectId, @RequestBody Binding input) {
        return service.bind(projectId, input.companyId());
    }

    @PutMapping("/{id}/default")
    @ResourceAction(id = "save", name = "设置默认")
    public Mono<Void> setDefault(
            @PathVariable String projectId, @PathVariable String id, @RequestBody Version input) {
        if (input.version() == null || input.version() < 0)
            return Mono.error(
                    org.jetlinks.pro.property.finance.service.FinanceScope.error(
                            "version_required", 400));
        return service.setDefault(projectId, id, input.version());
    }

    @DeleteMapping("/{id}")
    @ResourceAction(id = "save", name = "解除关联")
    public Mono<Void> unbind(
            @PathVariable String projectId, @PathVariable String id, @RequestParam long version) {
        return service.unbind(projectId, id, version);
    }

    public record Binding(String companyId) {}

    public record Version(Long version) {}
}
