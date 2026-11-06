package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.property.finance.entity.FinanceCompanyEntity;
import org.jetlinks.pro.property.finance.service.InvoiceCompanyService;
import org.jetlinks.pro.property.finance.tax.InvoiceCompanyInput;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** 共享税号维护不接受项目字段，不开放框架通用保存和物理删除入口。 */
@RestController
@RequestMapping("/property/finance/companies")
@Resource(id = "property-finance-company", name = "收费税号设置")
@Authorize
@RequiredArgsConstructor
public class InvoiceCompanyController {
    private final InvoiceCompanyService service;
    private final org.jetlinks.pro.property.finance.service.ProjectCompanyService projects;

    @GetMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<FinanceCompanyEntity>> query(QueryParamEntity query) {
        return service.query(query);
    }

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<FinanceCompanyEntity>> queryPost(@RequestBody QueryParamEntity query) {
        return service.query(query);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<InvoiceCompanyService.Detail> detail(@PathVariable String id) {
        return service.detail(id);
    }

    @PostMapping
    @ResourceAction(id = "save", name = "保存")
    public Mono<InvoiceCompanyService.Detail> create(@RequestBody InvoiceCompanyInput input) {
        return service.create(input);
    }

    @PostMapping("/_create-and-bind/{projectId}")
    @ResourceAction(id = "save", name = "新建并关联")
    public Mono<org.jetlinks.pro.property.finance.service.ProjectCompanyService.Row> createAndBind(
            @PathVariable String projectId, @RequestBody InvoiceCompanyInput input) {
        return projects.createAndBind(projectId, input);
    }

    @PutMapping("/{id}")
    @ResourceAction(id = "save", name = "保存")
    public Mono<InvoiceCompanyService.Detail> update(
            @PathVariable String id, @RequestBody InvoiceCompanyInput input) {
        return service.update(id, input);
    }

    @DeleteMapping("/{id}")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> delete(@PathVariable String id, @RequestParam long version) {
        return service.delete(id, version);
    }
}
